package com.dataease.migration.model;

import java.net.URI;
import java.net.URISyntaxException;

public record DatabaseConnection(String host, int port, String database) {

    public static DatabaseConnection fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("仅支持 jdbc:mysql://host:port/database 格式的 JDBC URL");
        }

        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            String path = uri.getPath();
            if (uri.getHost() == null || path == null || path.length() <= 1 || path.indexOf('/', 1) >= 0) {
                throw new IllegalArgumentException("JDBC URL 必须包含数据库主机和数据库名");
            }
            return new DatabaseConnection(uri.getHost(), uri.getPort() == -1 ? 3306 : uri.getPort(), path.substring(1));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("JDBC URL 格式无效", e);
        }
    }
}
