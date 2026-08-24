package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseConnection;
import com.dataease.migration.model.DatabaseInfo;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 没有可用 mysql/mysqldump 时使用的 JDBC 回退迁移器，负责复制 MySQL 结构和数据。
 * 大表数据采用流式读取、批量改写和分段提交，避免一次性加载全表或逐行网络往返。
 */
@Component
public class JdbcDatabaseMigrator implements DatabaseMigrator {
    /**
     * 单批行数需要同时兼顾吞吐和内存：Connector/J 会将一批改写成多值 INSERT，而同步日志表
     * 可能含有较大的 TEXT/LONGTEXT，批次过大会显著抬高迁移进程及 MySQL 的瞬时内存占用。
     */
    private static final int BATCH_SIZE = 500;
    /**
     * 每 20 批提交一次，减少逐批提交的 fsync 开销；用 BATCH_SIZE 计算可保证提交前没有待执行批次。
     */
    private static final int COMMIT_INTERVAL = BATCH_SIZE * 20;

    @Override
    public void migrate(DatabaseInfo source, DatabaseInfo target, MigrationJob job) throws SQLException {
        DatabaseConnection sourceConnection = DatabaseConnection.fromJdbcUrl(source.jdbcUrl());
        DatabaseConnection targetConnection = DatabaseConnection.fromJdbcUrl(target.jdbcUrl());
        try (Connection sourceDb = connect(source, false); Connection targetDb = connect(target, true)) {
            sourceDb.setAutoCommit(false);
            recreateDatabase(targetDb, targetConnection, job);
            targetDb.setAutoCommit(false);
            execute(targetDb, "SET FOREIGN_KEY_CHECKS = 0");
            job.log("JDBC 批量写入已启用：每批 " + BATCH_SIZE + " 行，每 "
                    + COMMIT_INTERVAL + " 行提交一次。");
            try {
                List<String> tables = findObjects(sourceDb, sourceConnection.database(), "BASE TABLE");
                job.log("正在迁移 " + tables.size() + " 个数据表结构。");
                for (String table : tables) {
                    execute(targetDb, rewriteDatabaseReference(
                            showCreate(sourceDb, "TABLE", sourceConnection.database(), table),
                            sourceConnection.database(), targetConnection.database()));
                }

                for (String table : tables) {
                    copyTableData(sourceDb, targetDb, sourceConnection.database(), targetConnection.database(), table, job);
                }

                migrateViews(sourceDb, targetDb, sourceConnection.database(), targetConnection.database(), job);
                migrateRoutines(sourceDb, targetDb, sourceConnection.database(), targetConnection.database(), job);
                migrateTriggers(sourceDb, targetDb, sourceConnection.database(), targetConnection.database(), job);
                migrateEvents(sourceDb, targetDb, sourceConnection.database(), targetConnection.database(), job);
                targetDb.commit();
            } catch (SQLException e) {
                targetDb.rollback();
                throw e;
            } finally {
                execute(targetDb, "SET FOREIGN_KEY_CHECKS = 1");
                targetDb.setAutoCommit(true);
            }
        }
    }

    private Connection connect(DatabaseInfo database, boolean optimizeBatchWrites) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), connectionProperties(database, optimizeBatchWrites));
    }

    /**
     * 只为目标连接启用批量改写。源连接负责流式读取，无需写入优化；目标连接关闭服务端预编译后，
     * Connector/J 才能稳定地把 executeBatch 改写为一条多值 INSERT。单独传 Properties 还会覆盖
     * JDBC URL 中的同名配置，避免页面输入的参数意外关闭迁移工具的批量策略。
     */
    static Properties connectionProperties(DatabaseInfo database, boolean optimizeBatchWrites) {
        Properties properties = new Properties();
        properties.setProperty("user", database.username());
        properties.setProperty("password", database.password());
        if (optimizeBatchWrites) {
            properties.setProperty("rewriteBatchedStatements", "true");
            properties.setProperty("useServerPrepStmts", "false");
        }
        return properties;
    }

    private void recreateDatabase(Connection target, DatabaseConnection connection, MigrationJob job) throws SQLException {
        String database = ShellEscaper.sqlIdentifier(connection.database());
        job.log("正在删除并重建 DataEase 3.0 MySQL 数据库：" + connection.database() + "。");
        execute(target, "DROP DATABASE IF EXISTS " + database);
        execute(target, "CREATE DATABASE " + database
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        execute(target, "USE " + database);
    }

    private List<String> findObjects(Connection connection, String database, String type) throws SQLException {
        if (!"BASE TABLE".equals(type) && !"VIEW".equals(type)) {
            throw new IllegalArgumentException("不支持的数据库对象类型：" + type);
        }
        List<String> objects = new ArrayList<>();
        String query = "SHOW FULL TABLES FROM " + ShellEscaper.sqlIdentifier(database)
                + " WHERE Table_type = '" + type + "'";
        try (Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(query)) {
                while (result.next()) {
                    objects.add(result.getString(1));
                }
            }
        }
        return objects;
    }

    private void copyTableData(Connection source, Connection target, String sourceDatabase, String targetDatabase,
                               String table, MigrationJob job) throws SQLException {
        List<String> columns = migratableColumns(source, sourceDatabase, table);
        if (columns.isEmpty()) {
            return;
        }
        String sourceTable = qualifiedName(sourceDatabase, table);
        String targetTable = qualifiedName(targetDatabase, table);
        String quotedColumns = String.join(", ", columns.stream().map(ShellEscaper::sqlIdentifier).toList());
        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());
        String select = "SELECT " + quotedColumns + " FROM " + sourceTable;
        String insert = "INSERT INTO " + targetTable + " (" + quotedColumns + ") VALUES (" + placeholders + ")";

        job.log("正在迁移数据表：" + table + "。");
        long startedNanos = System.nanoTime();
        try (Statement read = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement write = target.prepareStatement(insert)) {
            read.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rows = read.executeQuery(select)) {
                int rowCount = 0;
                int pendingBatchRows = 0;
                while (rows.next()) {
                    for (int index = 0; index < columns.size(); index++) {
                        write.setObject(index + 1, rows.getObject(index + 1));
                    }
                    write.addBatch();
                    rowCount++;
                    pendingBatchRows++;
                    if (pendingBatchRows == BATCH_SIZE) {
                        write.executeBatch();
                        write.clearBatch();
                        pendingBatchRows = 0;
                    }
                    if (rowCount % COMMIT_INTERVAL == 0) {
                        // COMMIT_INTERVAL 是 BATCH_SIZE 的整数倍，此时所有 addBatch 内容均已落库。
                        target.commit();
                        job.log("数据表 " + table + " 已迁移 " + rowCount + " 行，平均 "
                                + rowsPerSecond(rowCount, startedNanos) + " 行/秒。");
                    }
                }
                if (pendingBatchRows > 0) {
                    write.executeBatch();
                    write.clearBatch();
                }
                // 整万行已在循环内提交；这里只提交最后不足一个提交区间的尾批，避免空提交。
                if (rowCount % COMMIT_INTERVAL != 0) {
                    target.commit();
                }
                job.log("数据表 " + table + " 迁移完成，共 " + rowCount + " 行，耗时 "
                        + formatElapsed(startedNanos) + "，平均 " + rowsPerSecond(rowCount, startedNanos)
                        + " 行/秒。");
            }
        }
    }

    private long rowsPerSecond(int rowCount, long startedNanos) {
        long elapsedNanos = Math.max(1, System.nanoTime() - startedNanos);
        return Math.round(rowCount * 1_000_000_000.0 / elapsedNanos);
    }

    private String formatElapsed(long startedNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (elapsedMillis < 1_000) {
            return elapsedMillis + " 毫秒";
        }
        return String.format(Locale.ROOT, "%.2f 秒", elapsedMillis / 1_000.0);
    }

    private List<String> migratableColumns(Connection connection, String database, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW COLUMNS FROM " + qualifiedName(database, table))) {
            while (result.next()) {
                String extra = result.getString("Extra");
                if (extra == null || !extra.contains("GENERATED")) {
                    columns.add(result.getString("Field"));
                }
            }
        }
        return columns;
    }

    private void migrateViews(Connection source, Connection target, String sourceDatabase, String targetDatabase,
                              MigrationJob job) throws SQLException {
        for (String view : findObjects(source, sourceDatabase, "VIEW")) {
            job.log("正在迁移视图：" + view + "。");
            execute(target, rewriteDatabaseReference(showCreate(source, "VIEW", sourceDatabase, view),
                    sourceDatabase, targetDatabase));
        }
    }

    private void migrateRoutines(Connection source, Connection target, String sourceDatabase, String targetDatabase,
                                 MigrationJob job) throws SQLException {
        String query = "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ?";
        try (PreparedStatement statement = source.prepareStatement(query)) {
            statement.setString(1, sourceDatabase);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(1);
                    String type = result.getString(2);
                    job.log("正在迁移" + ("PROCEDURE".equals(type) ? "存储过程" : "函数") + "：" + name + "。");
                    execute(target, rewriteDatabaseReference(showCreate(source, type, sourceDatabase, name),
                            sourceDatabase, targetDatabase));
                }
            }
        }
    }

    private void migrateTriggers(Connection source, Connection target, String sourceDatabase, String targetDatabase,
                                 MigrationJob job) throws SQLException {
        try (Statement statement = source.createStatement();
             ResultSet result = statement.executeQuery("SHOW TRIGGERS FROM "
                     + ShellEscaper.sqlIdentifier(sourceDatabase))) {
            while (result.next()) {
                String name = result.getString("Trigger");
                job.log("正在迁移触发器：" + name + "。");
                execute(target, rewriteDatabaseReference(showCreate(source, "TRIGGER", sourceDatabase, name),
                        sourceDatabase, targetDatabase));
            }
        }
    }

    private void migrateEvents(Connection source, Connection target, String sourceDatabase, String targetDatabase,
                               MigrationJob job) throws SQLException {
        try (Statement statement = source.createStatement();
             ResultSet result = statement.executeQuery("SHOW EVENTS FROM "
                     + ShellEscaper.sqlIdentifier(sourceDatabase))) {
            while (result.next()) {
                String name = result.getString("Name");
                job.log("正在迁移事件：" + name + "。");
                execute(target, rewriteDatabaseReference(showCreate(source, "EVENT", sourceDatabase, name),
                        sourceDatabase, targetDatabase));
            }
        }
    }

    private String showCreate(Connection connection, String type, String database, String object) throws SQLException {
        String statement = "SHOW CREATE " + type + " " + qualifiedName(database, object);
        try (Statement query = connection.createStatement(); ResultSet result = query.executeQuery(statement)) {
            if (!result.next()) {
                throw new SQLException("无法读取 " + type + " 定义：" + object);
            }
            return createDefinition(result);
        }
    }

    private String createDefinition(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String label = metadata.getColumnLabel(column);
            if (label.startsWith("Create ") || label.contains("Statement")) {
                return result.getString(column);
            }
        }
        throw new SQLException("数据库未返回对象创建语句");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String qualifiedName(String database, String object) {
        return ShellEscaper.sqlIdentifier(database) + "." + ShellEscaper.sqlIdentifier(object);
    }

    private String rewriteDatabaseReference(String definition, String sourceDatabase, String targetDatabase) {
        return definition.replace(ShellEscaper.sqlIdentifier(sourceDatabase) + ".",
                ShellEscaper.sqlIdentifier(targetDatabase) + ".");
    }
}
