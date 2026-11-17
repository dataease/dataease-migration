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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 JDBC 复制 MySQL 结构和数据。
 * 大表数据采用流式读取、批量改写和分段提交，避免一次性加载全表或逐行网络往返。
 */
@Component
public class JdbcDatabaseMigrator {
    /**
     * 单批行数需要同时兼顾吞吐和内存：Connector/J 会将一批改写成多值 INSERT，而同步日志表
     * 可能含有较大的 TEXT/LONGTEXT，批次过大会显著抬高迁移进程及 MySQL 的瞬时内存占用。
     */
    private static final int BATCH_SIZE = 500;
    /**
     * 每 20 批提交一次，减少逐批提交的 fsync 开销；用 BATCH_SIZE 计算可保证提交前没有待执行批次。
     */
    private static final int COMMIT_INTERVAL = BATCH_SIZE * 20;
    private static final String BACKTICK_IDENTIFIER = "`(?:``|[^`])*`";
    private static final Pattern SQL_TOKENS = sqlTokens(false, false);
    private static final Pattern SQL_TOKENS_ANSI = sqlTokens(true, false);
    private static final Pattern SQL_TOKENS_NO_ESCAPES = sqlTokens(false, true);
    private static final Pattern SQL_TOKENS_ANSI_NO_ESCAPES = sqlTokens(true, true);

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
                    createObject(sourceDb, targetDb, "TABLE", sourceConnection.database(), targetConnection.database(), table);
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
        // TINYINT(1) is a numeric SQL type, not restricted to 0/1. Preserve its
        // physical values instead of letting Connector/J coerce them to Boolean.
        properties.setProperty("tinyInt1isBit", "false");
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
        List<Column> columns = migratableColumns(source, sourceDatabase, table);
        if (columns.isEmpty()) {
            return;
        }
        String sourceTable = qualifiedName(sourceDatabase, table);
        String targetTable = qualifiedName(targetDatabase, table);
        String quotedColumns = String.join(", ", columns.stream().map(c -> ShellEscaper.sqlIdentifier(c.name())).toList());
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
                        if (columns.get(index).tinyInteger()) {
                            int value = rows.getInt(index + 1);
                            if (rows.wasNull()) write.setNull(index + 1, java.sql.Types.TINYINT);
                            else write.setInt(index + 1, value);
                        } else write.setObject(index + 1, rows.getObject(index + 1));
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

    private record Column(String name, boolean tinyInteger) {}

    private List<Column> migratableColumns(Connection connection, String database, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COLUMN_NAME,GENERATION_EXPRESSION,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            query.setString(1, database); query.setString(2, table);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String expression = result.getString(2);
                    if (expression == null || expression.isBlank())
                        columns.add(new Column(result.getString(1), "tinyint".equalsIgnoreCase(result.getString(3))));
                }
            }
        }
        return columns;
    }

    private void migrateViews(Connection source, Connection target, String sourceDatabase, String targetDatabase,
                              MigrationJob job) throws SQLException {
        for (String view : findObjects(source, sourceDatabase, "VIEW")) {
            job.log("正在迁移视图：" + view + "。");
            createObject(source, target, "VIEW", sourceDatabase, targetDatabase, view);
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
                    createObject(source, target, type, sourceDatabase, targetDatabase, name);
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
                createObject(source, target, "TRIGGER", sourceDatabase, targetDatabase, name);
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
                createObject(source, target, "EVENT", sourceDatabase, targetDatabase, name);
            }
        }
    }

    private void createObject(Connection source, Connection target, String type, String sourceDatabase,
                              String targetDatabase, String object) throws SQLException {
        CreateDefinition definition = showCreate(source, type, sourceDatabase, object);
        String sql = rewriteDatabaseReference(definition, sourceDatabase, targetDatabase);
        if (definition.sqlMode() == null) {
            execute(target, sql);
            return;
        }
        // Stored objects retain the SQL mode active when their body is created.
        execute(target, "SET @de_migration_previous_sql_mode = @@SESSION.sql_mode");
        try (PreparedStatement mode = target.prepareStatement("SET SESSION sql_mode = ?")) {
            mode.setString(1, definition.sqlMode());
            mode.execute();
            execute(target, sql);
        } finally {
            execute(target, "SET SESSION sql_mode = @de_migration_previous_sql_mode");
        }
    }

    private CreateDefinition showCreate(Connection connection, String type, String database, String object)
            throws SQLException {
        String statement = "SHOW CREATE " + type + " " + qualifiedName(database, object);
        try (Statement query = connection.createStatement(); ResultSet result = query.executeQuery(statement)) {
            if (!result.next()) {
                throw new SQLException("无法读取 " + type + " 定义：" + object);
            }
            ResultSetMetaData metadata = result.getMetaData();
            String sql = null;
            String sqlMode = null;
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                String label = metadata.getColumnLabel(column);
                if (label.startsWith("Create ") || label.contains("Statement")) {
                    sql = result.getString(column);
                } else if ("sql_mode".equalsIgnoreCase(label)) {
                    sqlMode = result.getString(column);
                }
            }
            if (sql == null) {
                throw new SQLException("数据库未返回对象创建语句：" + object);
            }
            return new CreateDefinition(sql, sqlMode);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String qualifiedName(String database, String object) {
        return ShellEscaper.sqlIdentifier(database) + "." + ShellEscaper.sqlIdentifier(object);
    }

    private String rewriteDatabaseReference(CreateDefinition definition, String sourceDatabase, String targetDatabase) {
        if (sourceDatabase.equals(targetDatabase)) {
            return definition.sql();
        }
        List<String> modes = definition.sqlMode() == null ? List.of() : List.of(definition.sqlMode().split(","));
        boolean ansiQuotes = modes.contains("ANSI_QUOTES");
        boolean noEscapes = modes.contains("NO_BACKSLASH_ESCAPES");
        Pattern tokens = ansiQuotes ? (noEscapes ? SQL_TOKENS_ANSI_NO_ESCAPES : SQL_TOKENS_ANSI)
                : (noEscapes ? SQL_TOKENS_NO_ESCAPES : SQL_TOKENS);
        String backtickName = ShellEscaper.sqlIdentifier(sourceDatabase);
        String doubleQuotedName = '"' + sourceDatabase.replace("\"", "\"\"") + '"';
        String replacement = Matcher.quoteReplacement(ShellEscaper.sqlIdentifier(targetDatabase));
        Matcher matcher = tokens.matcher(definition.sql());
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group("qualifier");
            if (sourceDatabase.equals(name) || backtickName.equals(name) || (ansiQuotes && doubleQuotedName.equals(name))) {
                matcher.appendReplacement(rewritten, replacement);
            }
        }
        return matcher.appendTail(rewritten).toString();
    }

    private static Pattern sqlTokens(boolean ansiQuotes, boolean noEscapes) {
        String singleQuotedString = quotedToken('\'', !noEscapes);
        String doubleQuotedToken = quotedToken('"', !ansiQuotes && !noEscapes);
        String quotedNames = ansiQuotes ? BACKTICK_IDENTIFIER + "|" + doubleQuotedToken : BACKTICK_IDENTIFIER;
        String bareName = "[\\p{Alnum}_$\\x{0080}-\\x{FFFF}]++";
        String comments = "/\\*.*?\\*/|(?:#|--(?=\\s)).*?(?=\\R|\\z)";
        // Match literals and comments as a whole before considering identifiers.
        return Pattern.compile("(?s)" + comments + "|" + singleQuotedString
                + (ansiQuotes ? "" : "|" + doubleQuotedToken)
                + "|(?<qualifier>" + quotedNames + "|" + bareName + ")(?=\\s*\\.)|" + quotedNames + "|" + bareName);
    }

    private static String quotedToken(char quote, boolean backslashEscapes) {
        String escape = backslashEscapes ? "\\\\.|" : "";
        String ordinary = "[^" + quote + (backslashEscapes ? "\\\\" : "") + "]++";
        return quote + "(?:" + quote + quote + "|" + escape + ordinary + ")*+" + quote;
    }

    private record CreateDefinition(String sql, String sqlMode) {}
}
