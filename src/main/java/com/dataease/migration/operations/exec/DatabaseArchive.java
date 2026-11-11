package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.text;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/** Logical MySQL archive. Binary values remain bytes; DDL and stored objects are preserved. */
@Component
public class DatabaseArchive {
    private final ObjectMapper json;

    public DatabaseArchive(ObjectMapper json) {
        this.json = json;
    }

    public static Connection connect(Map<String, Object> i, Map<String, String> c)
            throws SQLException {
        return connect(i, c, true);
    }

    public static Connection connectServer(Map<String, Object> i, Map<String, String> c)
            throws SQLException {
        return connect(i, c, false);
    }

    private static Connection connect(Map<String, Object> i, Map<String, String> c, boolean selectDatabase)
            throws SQLException {
        if (!text(i, "dbType", "MySQL").equals("MySQL"))
            throw new IllegalArgumentException("首版自动数据备份仅支持MySQL");
        String name = text(i, "dbName", "");
        if (!name.matches("[A-Za-z0-9_.$-]+")) throw new IllegalArgumentException("数据库名称无效");
        String dbHost = text(i, "dbHost", text(i, "host", ""));
        if (dbHost.contains(":")) dbHost = "[" + dbHost + "]";
        String url =
                "jdbc:mysql://"
                        + dbHost
                        + ":"
                        + text(i, "dbPort", "3306")
                        + "/"
                        + (selectDatabase ? name : "")
                        + "?useUnicode=true&characterEncoding=UTF-8&connectTimeout=15000&socketTimeout=600000&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&tinyInt1isBit=false";
        return DriverManager.getConnection(
                url,
                c.getOrDefault("dbUser", text(i, "dbUser", "dataease")),
                c.getOrDefault("dbPassword", ""));
    }

    public record DatabaseState(String identity, boolean present) {}

    /** Observe the managed server without requiring a database that a failed DROP may have removed. */
    public static DatabaseState observe(Map<String, Object> instance, Map<String, String> credentials)
            throws SQLException {
        try (Connection connection = connectServer(instance, credentials);
                Statement query = connection.createStatement()) {
            query.setQueryTimeout(10);
            String uuid;
            try (ResultSet row = query.executeQuery("SELECT @@server_uuid")) {
                if (!row.next()) throw new SQLException("无法读取实际MySQL服务身份");
                uuid = row.getString(1);
            }
            boolean present;
            try (PreparedStatement schema = connection.prepareStatement(
                    "SELECT 1 FROM INFORMATION_SCHEMA.SCHEMATA WHERE BINARY SCHEMA_NAME=BINARY ?")) {
                schema.setString(1, text(instance, "dbName", ""));
                schema.setQueryTimeout(10);
                try (ResultSet rows = schema.executeQuery()) { present = rows.next(); }
            }
            return new DatabaseState(uuid + "/" + text(instance, "dbName", ""), present);
        }
    }

    public void exportDatabase(
            Map<String, Object> i, Map<String, String> c, Path root, Consumer<String> log)
            throws Exception {
        Files.createDirectories(root.resolve("tables"));
        List<Map<String, Object>> tables = new ArrayList<>();
        List<String> views = new ArrayList<>(),
                routines = new ArrayList<>(),
                triggers = new ArrayList<>(),
                events = new ArrayList<>();
        String db = text(i, "dbName", "");
        try (Connection connection = connect(i, c)) {
            requireNoEnabledEvents(connection);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            List<String> names = new ArrayList<>(), viewNames = new ArrayList<>();
            try (Statement s = connection.createStatement();
                    ResultSet rs = s.executeQuery("SHOW FULL TABLES")) {
                while (rs.next()) {
                    if ("VIEW".equalsIgnoreCase(rs.getString(2))) viewNames.add(rs.getString(1));
                    else names.add(rs.getString(1));
                }
            }
            for (String name : names) {
                Map<String, Object> table = new LinkedHashMap<>();
                table.put("name", name);
                table.put("ddl", create(connection, "TABLE", name));
                String file = "tables/" + UUID.randomUUID() + ".jsonl";
                table.put("file", file);
                List<Map<String, Object>> columns = new ArrayList<>();
                Set<String> generated = new HashSet<>();
                try (PreparedStatement s =
                        connection.prepareStatement(
                                "SELECT COLUMN_NAME,GENERATION_EXPRESSION FROM"
                                        + " INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND"
                                        + " TABLE_NAME=?")) {
                    s.setString(1, db);
                    s.setString(2, name);
                    try (ResultSet rs = s.executeQuery()) {
                        while (rs.next())
                            if (rs.getString(2) != null && !rs.getString(2).isBlank())
                                generated.add(rs.getString(1));
                    }
                }
                long rows = 0;
                try (Statement s =
                        connection.createStatement(
                                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    s.setFetchSize(Integer.MIN_VALUE);
                    try (ResultSet rs = s.executeQuery("SELECT * FROM " + identifier(name));
                            BufferedWriter out =
                                    Files.newBufferedWriter(
                                            root.resolve(file), StandardCharsets.UTF_8)) {
                        ResultSetMetaData md = rs.getMetaData();
                        List<Integer> selected = new ArrayList<>();
                        for (int n = 1; n <= md.getColumnCount(); n++) {
                            if (generated.contains(md.getColumnName(n))) continue;
                            int type = md.getColumnType(n);
                            columns.add(
                                    Map.of(
                                            "name",
                                            md.getColumnName(n),
                                            "type",
                                            type,
                                            "binary",
                                            isBinary(type)));
                            selected.add(n);
                        }
                        while (rs.next()) {
                            List<Object> values = new ArrayList<>();
                            for (int col : selected) {
                                int type = md.getColumnType(col);
                                Object v = isBinary(type) ? rs.getBytes(col) : rs.getString(col);
                                values.add(v);
                            }
                            out.write(json.writeValueAsString(values));
                            out.newLine();
                            rows++;
                        }
                    }
                }
                table.put("columns", columns);
                table.put("rows", rows);
                tables.add(table);
                log.accept("已导出数据表 " + name + "，" + rows + " 行");
            }
            for (String name : viewNames) views.add(create(connection, "VIEW", name));
            try (PreparedStatement s =
                    connection.prepareStatement(
                            "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM INFORMATION_SCHEMA.ROUTINES"
                                    + " WHERE ROUTINE_SCHEMA=?")) {
                s.setString(1, db);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next())
                        routines.add(create(connection, rs.getString(2), rs.getString(1)));
                }
            }
            try (PreparedStatement s =
                    connection.prepareStatement(
                            "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE"
                                    + " TRIGGER_SCHEMA=?")) {
                s.setString(1, db);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) triggers.add(create(connection, "TRIGGER", rs.getString(1)));
                }
            }
            try (PreparedStatement s =
                    connection.prepareStatement(
                            "SELECT EVENT_NAME FROM INFORMATION_SCHEMA.EVENTS WHERE"
                                    + " EVENT_SCHEMA=?")) {
                s.setString(1, db);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) events.add(create(connection, "EVENT", rs.getString(1)));
                }
            }
            connection.commit();
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("database", db);
        manifest.put("tables", tables);
        manifest.put("views", views);
        manifest.put("routines", routines);
        manifest.put("triggers", triggers);
        manifest.put("events", events);
        json.writeValue(root.resolve("database.json").toFile(), manifest);
    }

    @SuppressWarnings("unchecked")
    public void restoreDatabase(
            Map<String, Object> i, Map<String, String> c, Path root, Consumer<String> log)
            throws Exception {
        Map<String, Object> manifest =
                json.readValue(root.resolve("database.json").toFile(), new TypeReference<>() {});
        String original = String.valueOf(manifest.get("database")), target = text(i, "dbName", "");
        try (Connection connection = connect(i, c);
                Statement statement = connection.createStatement()) {
            requireNoEnabledEvents(connection);
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            List<String> existingTables = new ArrayList<>(), existingViews = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SHOW FULL TABLES")) {
                while (rs.next()) {
                    if ("VIEW".equalsIgnoreCase(rs.getString(2)))
                        existingViews.add(rs.getString(1));
                    else existingTables.add(rs.getString(1));
                }
            }
            for (String view : existingViews) statement.execute("DROP VIEW " + identifier(view));
            for (String table : existingTables)
                statement.execute("DROP TABLE " + identifier(table));
            for (String kind : List.of("TRIGGER", "PROCEDURE", "FUNCTION", "EVENT")) {
                String sql =
                        switch (kind) {
                            case "TRIGGER" ->
                                    "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE"
                                            + " TRIGGER_SCHEMA=?";
                            case "EVENT" ->
                                    "SELECT EVENT_NAME FROM INFORMATION_SCHEMA.EVENTS WHERE"
                                            + " EVENT_SCHEMA=?";
                            default ->
                                    "SELECT ROUTINE_NAME FROM INFORMATION_SCHEMA.ROUTINES WHERE"
                                            + " ROUTINE_SCHEMA=? AND ROUTINE_TYPE='"
                                            + kind
                                            + "'";
                        };
                List<String> names = new ArrayList<>();
                try (PreparedStatement q = connection.prepareStatement(sql)) {
                    q.setString(1, target);
                    try (ResultSet rs = q.executeQuery()) {
                        while (rs.next()) names.add(rs.getString(1));
                    }
                }
                for (String name : names)
                    statement.execute("DROP " + kind + " IF EXISTS " + identifier(name));
            }
            for (Map<String, Object> table : (List<Map<String, Object>>) manifest.get("tables")) {
                String name = String.valueOf(table.get("name"));
                statement.execute(rebind(String.valueOf(table.get("ddl")), original, target));
                // Older Connector/J metadata reported TINYINT(1) as BIT. Its getBytes()
                // output was numeric text, while a real BIT column contains packed bits.
                // Consult the restored physical schema so old backups stay lossless too.
                Set<String> tinyIntegers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE()"
                                + " AND TABLE_NAME=? AND DATA_TYPE='tinyint'")) {
                    query.setString(1, name);
                    try (ResultSet fields = query.executeQuery()) {
                        while (fields.next()) tinyIntegers.add(fields.getString(1));
                    }
                }
                List<Map<String, Object>> columns =
                        (List<Map<String, Object>>) table.get("columns");
                String names =
                        String.join(
                                ",",
                                columns.stream()
                                        .map(x -> identifier(String.valueOf(x.get("name"))))
                                        .toList());
                String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
                Path file = root.resolve(String.valueOf(table.get("file"))).normalize();
                if (!file.startsWith(root)) throw new IllegalArgumentException("备份数据路径无效");
                long rows = 0;
                try (PreparedStatement insert =
                                connection.prepareStatement(
                                        "INSERT INTO "
                                                + identifier(name)
                                                + " ("
                                                + names
                                                + ") VALUES ("
                                                + placeholders
                                                + ")");
                        BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        List<Object> values = json.readValue(line, new TypeReference<>() {});
                        if (values.size() != columns.size()) throw new IOException("备份列数不一致");
                        for (int n = 0; n < values.size(); n++) {
                            Object v = values.get(n);
                            int type = ((Number) columns.get(n).get("type")).intValue();
                            if (v == null) insert.setNull(n + 1, type);
                            else if (Boolean.TRUE.equals(columns.get(n).get("binary"))) {
                                byte[] bytes = Base64.getDecoder().decode(String.valueOf(v));
                                if (type == Types.BIT && tinyIntegers.contains(
                                        String.valueOf(columns.get(n).get("name")))) {
                                    String number = new String(bytes, StandardCharsets.US_ASCII);
                                    if (!number.matches("-?[0-9]{1,3}"))
                                        throw new IOException("旧备份TINYINT字段编码无法识别");
                                    insert.setString(n + 1, number);
                                } else insert.setBytes(n + 1, bytes);
                            }
                            else insert.setString(n + 1, String.valueOf(v));
                        }
                        insert.addBatch();
                        if (++rows % 500 == 0) insert.executeBatch();
                    }
                    insert.executeBatch();
                }
                if (rows != ((Number) table.get("rows")).longValue())
                    throw new IOException("数据表恢复行数不一致：" + name);
                log.accept("已恢复数据表 " + name + "，" + rows + " 行");
            }
            for (String category : List.of("routines", "triggers", "events"))
                for (String ddl : (List<String>) manifest.get(category)) {
                    String rebound = rebind(ddl, original, target);
                    if (category.equals("events"))
                        rebound =
                                rebound.replaceFirst(
                                        "(?is)\\bENABLE\\s+(?=(?:COMMENT\\s+'(?:''|[^'])*'\\s+)?DO\\b)",
                                        "DISABLE ");
                    statement.execute(rebound);
                }
            List<String> pending = new ArrayList<>((List<String>) manifest.get("views"));
            while (!pending.isEmpty()) {
                int count = pending.size();
                SQLException failure = null;
                for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                    String ddl = it.next();
                    try {
                        statement.execute(rebind(ddl, original, target));
                        it.remove();
                    } catch (SQLException e) {
                        failure = e;
                    }
                }
                if (pending.size() == count) throw failure;
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    public void requireNoEnabledEvents(Map<String, Object> instance, Map<String, String> credentials)
            throws SQLException {
        try (Connection connection = connect(instance, credentials)) {
            requireNoEnabledEvents(connection);
        }
    }

    static void requireNoEnabledEvents(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet events = query.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.EVENTS WHERE EVENT_SCHEMA=DATABASE() AND STATUS='ENABLED'")) {
            if (!events.next() || events.getLong(1) > 0)
                throw new IllegalArgumentException(
                        "数据库包含启用的MySQL事件，停止应用无法暂停这些任务；请由DBA停用并确认任务结束后重新预检查");
        }
    }

    private static String create(Connection c, String type, String name) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SHOW CREATE " + type + " " + identifier(name))) {
            if (!rs.next()) throw new SQLException("无法读取对象定义");
            ResultSetMetaData md = rs.getMetaData();
            for (int n = 1; n <= md.getColumnCount(); n++) {
                String label = md.getColumnLabel(n).toLowerCase(Locale.ROOT);
                if (label.startsWith("create ") || label.equals("sql original statement")) {
                    String ddl = rs.getString(n);
                    if (ddl == null || ddl.isBlank()) throw new SQLException("缺少读取存储对象定义的权限");
                    return ddl;
                }
            }
            return rs.getString(2);
        }
    }

    private static boolean isBinary(int type) {
        return type == Types.BINARY
                || type == Types.VARBINARY
                || type == Types.LONGVARBINARY
                || type == Types.BLOB
                || type == Types.BIT;
    }

    public static String identifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    private static String rebind(String sql, String original, String target) {
        return sql.replace(identifier(original) + ".", identifier(target) + ".");
    }
}
