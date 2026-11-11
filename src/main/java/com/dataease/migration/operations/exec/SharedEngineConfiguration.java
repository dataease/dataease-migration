package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.model.DatabaseConnection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Relocates managed connections and local Excel files; external business connections stay intact. */
final class SharedEngineConfiguration {
    private final ObjectMapper json;

    SharedEngineConfiguration(ObjectMapper json) { this.json = json; }

    boolean verify(Map<String, Object> instance, Map<String, String> credentials) throws Exception {
        DatabaseConnection route = route(credentials);
        try (Connection connection = DatabaseArchive.connect(instance, credentials)) {
            Codec codec = codec(connection);
            try (Statement query = connection.createStatement();
                    ResultSet rows = query.executeQuery("SELECT type,configuration FROM core_de_engine")) {
                int count = 0;
                while (rows.next()) {
                    if (!"mysql".equalsIgnoreCase(rows.getString(1))
                            || !matches(decode(rows.getString(2), codec), route)) return false;
                    count++;
                }
                return count > 0;
            }
        }
    }

    void relocate(Map<String, Object> source, Map<String, String> sc,
            Map<String, Object> target, Map<String, String> tc) throws Exception {
        relocate(route(sc), target, tc);
    }

    void validateExcelFiles(Map<String, Object> source, Map<String, String> credentials,
            HostCommands host) throws Exception {
        excelFiles(source, credentials, host, false);
    }

    void relocateExcelFiles(Map<String, Object> target, Map<String, String> credentials,
            HostCommands host) throws Exception {
        excelFiles(target, credentials, host, true);
    }

    private void excelFiles(Map<String, Object> instance, Map<String, String> credentials,
            HostCommands host, boolean relocate) throws Exception {
        String oldRoot = "/opt/dataease2.0/data/excel/", newRoot = "/opt/dataease3.0/data/excel/";
        try (Connection connection = DatabaseArchive.connect(instance, credentials)) {
            Codec codec = codec(connection);
            List<Map.Entry<Long, String>> updates = new ArrayList<>();
            try (Statement query = connection.createStatement();
                    ResultSet rows = query.executeQuery("SELECT id,configuration FROM core_datasource WHERE type='Excel'")) {
                while (rows.next()) {
                    var sheets = decodeJson(rows.getString(2), codec);
                    if (!sheets.isArray() || sheets.isEmpty())
                        throw new IllegalArgumentException("本地Excel配置无法识别，不能确认原文件迁移范围");
                    for (var sheet : sheets) {
                        String file = sheet.path("path").asText("");
                        if (!sheet.isObject() || !file.startsWith(oldRoot)
                                || file.length() == oldRoot.length()
                                || file.contains("\\") || file.chars().anyMatch(Character::isISOControl)
                                || !Path.of(file).normalize().toString().equals(file))
                            throw new IllegalArgumentException("本地Excel原文件不在受管V2目录内，已阻止迁移");
                        String relative = file.substring(oldRoot.length());
                        String actual = path(text(instance, "path", "")) + "/data/excel/" + relative;
                        try {
                            host.run(instance, credentials, "test -f " + quote(actual)
                                    + " && test -r " + quote(actual)
                                    + " && test ! -L " + quote(actual), line -> {});
                        } catch (Exception missing) {
                            throw new IllegalArgumentException("本地Excel原文件缺失或不可读，已阻止迁移", missing);
                        }
                        if (relocate) ((com.fasterxml.jackson.databind.node.ObjectNode) sheet)
                                .put("path", newRoot + relative);
                    }
                    if (relocate) updates.add(Map.entry(rows.getLong(1), encode(sheets, codec)));
                }
            }
            if (!relocate) return;
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE core_datasource SET configuration=? WHERE id=? AND type='Excel'")) {
                for (var value : updates) {
                    update.setString(1, value.getValue()); update.setLong(2, value.getKey());
                    if (update.executeUpdate() != 1) throw new SQLException("迁入Excel配置已变化");
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback(); throw failure;
            }
        }
    }

    /** Store the route only; application credentials stay out of the backup manifest. */
    static Map<String, Object> snapshotRoute(Map<String, String> credentials) {
        DatabaseConnection route = route(credentials);
        return Map.of("host", route.host(), "port", route.port(), "database", route.database());
    }

    static DatabaseConnection restoreRoute(Map<String, Object> manifest,
            Map<String, Object> target, Map<String, String> credentials) {
        if (manifest.get("sharedEngineRoute") instanceof Map<?, ?> saved) {
            String host = text(saved, "host", ""), database = text(saved, "database", "");
            int port = Integer.parseInt(text(saved, "port", "0"));
            if (host.isBlank() || database.isBlank() || port < 1 || port > 65535)
                throw new IllegalArgumentException("备份缺少有效的共库引擎连接位置");
            return new DatabaseConnection(host, port, database);
        }
        // Legacy same-instance protection backups may use the unchanged runtime route. Never
        // guess a source application route from its management JDBC address during relocation.
        if (Objects.equals(manifest.get("instanceId"), target.get("id"))
                && !text(manifest, "databaseIdentity", "").isBlank()
                && Objects.equals(manifest.get("databaseIdentity"), target.get("databaseIdentity")))
            return route(credentials);
        throw new IllegalArgumentException("该旧备份未记录共库引擎连接位置，不能安全异机还原；请重新创建备份");
    }

    void relocate(DatabaseConnection previous, Map<String, Object> target,
            Map<String, String> tc) throws Exception {
        DatabaseConnection next = route(tc);
        if (tc.getOrDefault("runtimeDbUser", "").isBlank()
                || tc.getOrDefault("runtimeDbPassword", "").isBlank())
            throw new IllegalArgumentException("缺少目标应用实际数据库凭据，不能重绑定受管引擎");
        try (Connection connection = DatabaseArchive.connect(target, tc)) {
            Codec codec = codec(connection);
            connection.setAutoCommit(false);
            try {
                List<Map.Entry<Long, String>> updates = new ArrayList<>();
                try (Statement query = connection.createStatement();
                        ResultSet rows = query.executeQuery("SELECT id,type,configuration FROM core_de_engine")) {
                    while (rows.next()) {
                        Map<String, Object> config = decode(rows.getString(3), codec);
                        if (!"mysql".equalsIgnoreCase(rows.getString(2)) || !matches(config, previous))
                            throw new IllegalArgumentException("迁入引擎不属于已保护的源元数据库，已阻止隔离启动");
                        updates.add(Map.entry(rows.getLong(1), encode(rebind(config, next, tc), codec)));
                    }
                }
                if (updates.isEmpty()) throw new IllegalArgumentException("迁入环境缺少可验证的受管引擎");
                try (PreparedStatement update = connection.prepareStatement("UPDATE core_de_engine SET configuration=? WHERE id=?")) {
                    for (var value : updates) {
                        update.setString(1, value.getValue()); update.setLong(2, value.getKey()); update.executeUpdate();
                    }
                }
                try (PreparedStatement query = connection.prepareStatement("SELECT type,configuration FROM core_datasource WHERE id=985188400292302848");
                        ResultSet rows = query.executeQuery()) {
                    if (rows.next() && "mysql".equalsIgnoreCase(rows.getString(1))) {
                        Map<String, Object> demo = decode(rows.getString(2), codec);
                        if (matches(demo, previous))
                            try (PreparedStatement update = connection.prepareStatement("UPDATE core_datasource SET configuration=? WHERE id=985188400292302848")) {
                                update.setString(1, encode(rebind(demo, next, tc), codec)); update.executeUpdate();
                            }
                    }
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback(); throw failure;
            }
        }
        if (!verify(target, tc)) throw new IllegalStateException("目标受管引擎重绑定未通过复核");
    }

    private static DatabaseConnection route(Map<String, String> credentials) {
        String url = credentials.getOrDefault("runtimeDbUrl", "");
        if (url.isBlank()) throw new IllegalArgumentException("未识别应用实际元数据库连接");
        return DatabaseConnection.fromJdbcUrl(url.replaceFirst("^jdbc:mariadb:", "jdbc:mysql:"));
    }

    private static boolean matches(Map<String, Object> config, DatabaseConnection route) {
        String mode = text(config, "urlType", "");
        return (mode.isBlank() || "hostName".equalsIgnoreCase(mode))
                && !Boolean.parseBoolean(text(config, "useSSH", "false"))
                && text(config, "host", "").equalsIgnoreCase(route.host())
                && text(config, "port", "3306").equals(String.valueOf(route.port()))
                && text(config, "dataBase", "").equals(route.database());
    }

    private static Map<String, Object> rebind(Map<String, Object> original, DatabaseConnection route,
            Map<String, String> credentials) {
        Map<String, Object> result = new LinkedHashMap<>(original);
        result.put("host", route.host()); result.put("port", route.port());
        result.put("dataBase", route.database());
        result.put("username", credentials.get("runtimeDbUser"));
        result.put("password", credentials.get("runtimeDbPassword"));
        return result;
    }

    private record Codec(byte[] key, byte[] iv) {}

    private static Codec codec(Connection connection) throws Exception {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT aes_key FROM core_rsa")) {
            if (!rows.next()) throw new IllegalArgumentException("DataEase配置加密元数据缺失");
            String key = rows.getString(1);
            if (key == null || !Set.of(16, 24, 32).contains(key.getBytes(StandardCharsets.UTF_8).length) || rows.next())
                throw new IllegalArgumentException("DataEase配置加密元数据无法识别");
            String iv = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)), 0, 8);
            return new Codec(key.getBytes(StandardCharsets.UTF_8), iv.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Map<String, Object> decode(String value, Codec current) throws Exception {
        var decoded = decodeJson(value, current);
        if (!decoded.isObject()) throw new IllegalArgumentException("DataEase引擎配置必须为对象");
        return json.convertValue(decoded, new TypeReference<>() {});
    }

    private com.fasterxml.jackson.databind.JsonNode decodeJson(String value, Codec current) throws Exception {
        if (value != null && (value.stripLeading().startsWith("{") || value.stripLeading().startsWith("[")))
            return json.readTree(value);
        for (Codec candidate : List.of(current, new Codec("www.fit2cloud.co".getBytes(StandardCharsets.UTF_8), "1234567890123456".getBytes(StandardCharsets.UTF_8)))) {
            try {
                String decoded = new String(cipher(Cipher.DECRYPT_MODE, candidate).doFinal(Base64.getDecoder().decode(value)), StandardCharsets.UTF_8);
                return json.readTree(decoded);
            } catch (Exception unsupported) { /* Try the documented legacy configuration encoding. */ }
        }
        throw new IllegalArgumentException("无法识别DataEase配置加密格式，不能确认备份覆盖");
    }

    private String encode(Object value, Codec codec) throws Exception {
        return Base64.getEncoder().encodeToString(cipher(Cipher.ENCRYPT_MODE, codec).doFinal(json.writeValueAsBytes(value)));
    }

    private static Cipher cipher(int mode, Codec codec) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(codec.key(), "AES"), new IvParameterSpec(codec.iv()));
        return cipher;
    }
}
