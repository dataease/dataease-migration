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

@Component
public class JdbcDatabaseMigrator implements DatabaseMigrator {
    private static final int BATCH_SIZE = 500;

    @Override
    public void migrate(DatabaseInfo source, DatabaseInfo target, MigrationJob job) throws SQLException {
        DatabaseConnection sourceConnection = DatabaseConnection.fromJdbcUrl(source.jdbcUrl());
        DatabaseConnection targetConnection = DatabaseConnection.fromJdbcUrl(target.jdbcUrl());
        try (Connection sourceDb = connect(source); Connection targetDb = connect(target)) {
            sourceDb.setAutoCommit(false);
            recreateDatabase(targetDb, targetConnection, job);
            targetDb.setAutoCommit(false);
            execute(targetDb, "SET FOREIGN_KEY_CHECKS = 0");
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

    private Connection connect(DatabaseInfo database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
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
        try (Statement read = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement write = target.prepareStatement(insert)) {
            read.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rows = read.executeQuery(select)) {
                int rowCount = 0;
                while (rows.next()) {
                    for (int index = 0; index < columns.size(); index++) {
                        write.setObject(index + 1, rows.getObject(index + 1));
                    }
                    write.addBatch();
                    rowCount++;
                    if (rowCount % BATCH_SIZE == 0) {
                        write.executeBatch();
                        target.commit();
                    }
                }
                if (rowCount % BATCH_SIZE != 0) {
                    write.executeBatch();
                    target.commit();
                }
                job.log("数据表 " + table + " 迁移完成，共 " + rowCount + " 行。");
            }
        }
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
