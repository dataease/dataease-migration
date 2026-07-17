package com.dataease.migration.service;

public final class ShellEscaper {

    private ShellEscaper() {
    }

    public static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("命令参数不能为空");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static String sqlIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据库名不能为空");
        }
        return "`" + value.replace("`", "``") + "`";
    }
}
