package com.dataease.migration.operations.exec;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Read the effective grants before a migration may drop and recreate its target database. */
final class DatabaseRebuildPrivileges {
    private static final Pattern GRANT = Pattern.compile(
            "(?is)^(GRANT|REVOKE) (.+?) ON (.+?) (?:TO|FROM) .+$");
    private static final Pattern SCOPE = Pattern.compile("^(?:\\*|`((?:``|[^`])*)`)\\s*\\.\\s*\\*$");
    private static final String ROLE = "`(?:``|[^`])+`@`(?:``|[^`])*`";

    static void require(Connection connection) throws SQLException {
        require(connection, connection.getCatalog());
    }

    static void require(Connection connection, String database) throws SQLException {
        if (database == null || database.isBlank()) throw new IllegalArgumentException("未选择目标元数据库");
        if ("1".equals(scalar(connection, "SELECT @@read_only OR @@super_read_only")))
            throw new IllegalArgumentException("目标MySQL处于只读模式，已阻止数据库重建");
        String roles = scalar(connection, "SELECT CURRENT_ROLE()");
        if (!"NONE".equalsIgnoreCase(roles)
                && !roles.matches(ROLE + "(?:\\s*,\\s*" + ROLE + ")*"))
            throw new IllegalArgumentException("无法核验目标数据库的有效角色授权");
        boolean foldCase = Integer.parseInt(scalar(connection, "SELECT @@lower_case_table_names")) > 0;
        boolean partialRevokes;
        try { partialRevokes = "1".equals(scalar(connection, "SELECT @@partial_revokes")); }
        catch (SQLException unavailable) {
            if (unavailable.getErrorCode() != 1193) throw unavailable;
            partialRevokes = false;
        }
        Set<String> allowed = new HashSet<>(), denied = new HashSet<>();
        try (Statement query = connection.createStatement()) {
            query.setQueryTimeout(10);
            try (ResultSet rows = query.executeQuery("SHOW GRANTS FOR CURRENT_USER"
                    + ("NONE".equalsIgnoreCase(roles) ? "" : " USING " + roles))) {
                while (rows.next()) {
                    var grant = GRANT.matcher(rows.getString(1));
                    if (!grant.matches()) continue;
                    var scope = SCOPE.matcher(grant.group(3));
                    if (!scope.matches()) continue;
                    String pattern = scope.group(1);
                    if (pattern != null && !matches(database, pattern.replace("``", "`"), foldCase, partialRevokes))
                        continue;
                    Set<String> privileges = new HashSet<>();
                    for (String value : grant.group(2).split(",")) privileges.add(value.trim().toUpperCase(Locale.ROOT));
                    if (privileges.contains("ALL PRIVILEGES")) privileges.addAll(Set.of("CREATE", "DROP"));
                    ("REVOKE".equalsIgnoreCase(grant.group(1)) ? denied : allowed).addAll(privileges);
                }
            }
        }
        allowed.removeAll(denied);
        if (!allowed.containsAll(Set.of("CREATE", "DROP")))
            throw new IllegalArgumentException("无法确认目标账户具备元数据库CREATE/DROP权限，已阻止迁移；请核对当前有效角色和授权");
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement query = connection.createStatement()) {
            query.setQueryTimeout(10);
            try (ResultSet row = query.executeQuery(sql)) {
                if (!row.next() || row.getString(1) == null) throw new SQLException("无法读取数据库授权状态");
                return row.getString(1);
            }
        }
    }

    private static boolean matches(String name, String pattern, boolean foldCase, boolean literal) {
        StringBuilder expression = new StringBuilder("^");
        boolean escaped = false;
        for (char value : pattern.toCharArray()) {
            if (!literal && !escaped && value == '\\') { escaped = true; continue; }
            expression.append(!literal && !escaped && value == '%' ? ".*"
                    : !literal && !escaped && value == '_' ? "." : Pattern.quote(String.valueOf(value)));
            escaped = false;
        }
        if (escaped) expression.append(Pattern.quote("\\"));
        return Pattern.compile(expression.append('$').toString(), foldCase ? Pattern.CASE_INSENSITIVE : 0)
                .matcher(name).matches();
    }
}
