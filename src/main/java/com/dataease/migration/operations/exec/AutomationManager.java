package com.dataease.migration.operations.exec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads the live native scheduler and changes only its enforced, per-definition allowlist. */
final class AutomationManager {
    private final HostCommands host;
    private final ComposeManager compose;
    private final ObjectMapper json;

    AutomationManager(HostCommands host, ComposeManager compose, ObjectMapper json) {
        this.host = host; this.compose = compose; this.json = json;
    }

    Map<String, Object> inventory(Map<String, Object> instance, Map<String, String> credentials) throws Exception {
        try (var channel = new ContainerApplicationHttp(host, compose, instance, credentials)) {
            return decorate(read(channel.request(ApplicationIsolationGuard.ENDPOINT, "", null, null)), instance, credentials);
        }
    }

    Map<String, Object> apply(Map<String, Object> instance, Map<String, String> credentials,
            String snapshot, List<String> selected) throws Exception {
        try (var channel = new ContainerApplicationHttp(host, compose, instance, credentials)) {
            Map<String, Object> raw = read(channel.request(ApplicationIsolationGuard.ENDPOINT, "", null, null));
            String guardSnapshot = String.valueOf(raw.get("snapshot"));
            Map<String, Object> before = decorate(raw, instance, credentials);
            if (!snapshot.equals(before.get("snapshot"))) throw new IllegalArgumentException("自动任务已变化，请刷新后重新检查");
            List<Map<String, Object>> jobs = jobs(before);
            if (jobs.stream().filter(row -> selected.contains(row.get("id")) && Boolean.TRUE.equals(row.get("available"))).count() != selected.size())
                throw new IllegalArgumentException("所选任务不存在、业务记录不可识别或不支持恢复");
            Map<String, Object> result = read(channel.control(json.writeValueAsString(Map.of("snapshot", guardSnapshot, "selected", selected))));
            Map<String, Object> verified = read(channel.request(ApplicationIsolationGuard.ENDPOINT, "", null, null));
            Map<String, Object> decorated;
            try { decorated = decorate(verified, instance, credentials); }
            catch (Exception unavailable) {
                revoke(channel);
                throw new IllegalStateException("业务配置回读未完成，已撤销全部恢复授权，请重新检查");
            }
            Set<String> actual = new HashSet<>();
            for (var row : jobs(verified)) if (Boolean.TRUE.equals(row.get("enabled"))) actual.add(String.valueOf(row.get("id")));
            if (!actual.equals(new HashSet<>(selected)) || !Objects.equals(result.get("epoch"), verified.get("epoch"))
                    || !Objects.equals(before.get("businessSnapshot"), decorated.get("businessSnapshot"))) {
                // Revoke every grant if the independent runtime read-back disagrees.
                revoke(channel);
                throw new IllegalStateException("实际调度状态与选择不一致，已撤销全部恢复授权，请重新检查");
            }
            return decorated;
        }
    }

    private void revoke(ContainerApplicationHttp channel) throws Exception {
        try {
            read(channel.control(json.writeValueAsString(Map.of("revoke", true, "selected", List.of()))));
            var actual = read(channel.request(ApplicationIsolationGuard.ENDPOINT, "", null, null));
            if (!Boolean.TRUE.equals(actual.get("schedulersPaused"))) throw new IllegalStateException();
        } catch (Exception unavailable) {
            throw new java.net.SocketException("撤销后的实际调度暂停状态未确认，请先现场核查");
        }
    }

    private Map<String, Object> read(ContainerApplicationHttp.Response response) throws Exception {
        if (response.status() != 200) {
            if (response.status() == 409) {
                var value = json.readTree(response.body());
                throw new IllegalArgumentException(value.path("message").asText("自动任务状态已变化，请刷新后重新检查"));
            }
            throw new IllegalArgumentException("自动任务保护组件未就绪；请通过平台重新启动并完成验收后重试");
        }
        Map<String, Object> result = json.readValue(response.body(), new TypeReference<>() {});
        if (!Integer.valueOf(2).equals(result.get("guardVersion")) || !(result.get("jobs") instanceof List<?>))
            throw new IllegalArgumentException("此应用调度保护组件不支持逐项恢复，请通过平台重新启动并完成验收");
        return result;
    }

    private Map<String, Object> decorate(Map<String, Object> result, Map<String, Object> instance,
            Map<String, String> credentials) throws Exception {
        List<String> signatures = new ArrayList<>();
        String runtimeSnapshot = String.valueOf(result.get("snapshot"));
        try (Connection connection = DatabaseArchive.connect(instance, credentials)) {
            for (var row : jobs(result)) {
                String group = String.valueOf(row.get("group")), category = String.valueOf(row.get("category"));
                String query = switch (category) {
                    case "reports" -> "SELECT name FROM xpack_report_task WHERE id=?";
                    case "alerts" -> "SELECT name FROM xpack_threshold_info WHERE id=?";
                    case "sync" -> Set.of("SYNC_TASK", "SYNC_SIMPLE_TASK").contains(group)
                            ? "SELECT _name AS name FROM per_sync_task_info WHERE id=?"
                            : "SELECT d.name FROM core_datasource_task t JOIN core_datasource d ON t.ds_id=d.id WHERE t.id=?";
                    default -> "";
                };
                row.put("name", "任务 " + row.get("taskId"));
                if (!query.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setString(1, String.valueOf(row.get("taskId"))); statement.setQueryTimeout(10);
                        try (ResultSet rs = statement.executeQuery()) {
                            if (!rs.next()) throw new SQLException();
                            row.put("name", rs.getString(1));
                            if (rs.next()) throw new SQLException();
                        }
                    } catch (SQLException unavailable) {
                        row.put("available", false); row.put("reason", "无法对应实际业务任务，请先在DataEase核对");
                    }
                }
                if (!query.isEmpty()) {
                    try {
                        String id = String.valueOf(row.get("taskId"));
                        String table = switch (category) {
                            case "reports" -> "xpack_report_task";
                            case "alerts" -> "xpack_threshold_info";
                            default -> Set.of("SYNC_TASK", "SYNC_SIMPLE_TASK").contains(group) ? "per_sync_task_info" : "core_datasource_task";
                        };
                        Map<String, String> definition = definition(connection, "SELECT * FROM " + table + " WHERE id=?", id);
                        boolean enabled = switch (table) {
                            case "xpack_report_task" -> Set.of("0", "1").contains(definition.getOrDefault("status", ""));
                            case "xpack_threshold_info" -> Set.of("1", "true").contains(definition.getOrDefault("enable", ""));
                            case "per_sync_task_info" -> Set.of("WAITING", "RUNNING", "SUCCESS", "FAIL", "UNEXECUTED").contains(definition.getOrDefault("_status", "").toUpperCase(Locale.ROOT))
                                    && !Set.of("1", "true").contains(definition.getOrDefault("deleted", ""));
                            default -> !Set.of("Stopped", "Suspend").contains(definition.getOrDefault("task_status", ""));
                        };
                        // Runtime status changes between waiting/running must not invalidate a confirmed plan.
                        definition.remove("status"); definition.remove("_status"); definition.remove("task_status");
                        definition.put("enabled", String.valueOf(enabled));
                        if (table.equals("xpack_report_task")) definition.put("delivery", hash(definition(connection, "SELECT * FROM xpack_report_info WHERE task_id=? ORDER BY id", id)));
                        if (table.equals("core_datasource_task")) definition.put("datasource", hash(definition(connection,
                                "SELECT id,name,type,configuration FROM core_datasource WHERE id=?", group)));
                        signatures.add(hash(List.of(row.get("id"), definition)));
                        if (!enabled) { row.put("available", false); row.put("reason", "业务任务已停用，请先在DataEase启用并刷新"); }
                    } catch (SQLException unsupported) {
                        row.put("available", false); row.put("reason", "业务任务配置无法完整核验，请先在DataEase处理");
                        signatures.add(hash(List.of(row.get("id"), "unavailable")));
                    }
                }
                row.remove("definition"); // A digest is useful for guard checks, not for users.
            }
        }
        result.put("snapshot", hash(List.of(runtimeSnapshot, signatures)));
        result.put("businessSnapshot", hash(signatures));
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    private String hash(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
    }

    private Map<String, String> definition(Connection connection, String query, String id) throws Exception {
        Map<String, String> fields = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, id); statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                int row = 0;
                while (rs.next()) {
                    if (++row > 1000) throw new SQLException("Unsupported task definition size");
                    var columns = rs.getMetaData();
                    for (int n = 1; n <= columns.getColumnCount(); n++) {
                        String key = columns.getColumnLabel(n).toLowerCase(Locale.ROOT);
                        if (Set.of("last_exec_time", "last_exec_status", "last_execute_status", "trigger_last_time", "trigger_next_time",
                                "increment_value", "qrtz_instance", "update_time", "status_time").contains(key)) continue;
                        // Only state flags are read as plain values, and none leave this method's caller.
                        if (Set.of("status", "_status", "task_status", "enable", "deleted").contains(key)) {
                            fields.put(key, Objects.toString(rs.getString(n), "")); continue;
                        }
                        var digest = MessageDigest.getInstance("SHA-256");
                        boolean binary = Set.of(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
                                .contains(columns.getColumnType(n));
                        try (var bytes = binary ? rs.getBinaryStream(n)
                                : new java.io.ByteArrayInputStream(Objects.toString(rs.getString(n), "<NULL>").getBytes(StandardCharsets.UTF_8))) {
                            if (bytes != null) {
                                byte[] buffer = new byte[8192]; int length; long total = 0;
                                while ((length = bytes.read(buffer)) != -1) {
                                    total += length; if (total > 32L * 1024 * 1024) throw new SQLException("Unsupported task definition size");
                                    digest.update(buffer, 0, length);
                                }
                            } else digest.update((byte) 0);
                        }
                        fields.put(row + ":" + key, HexFormat.of().formatHex(digest.digest()));
                    }
                }
                if (row == 0) throw new SQLException("Task definition missing");
            }
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> jobs(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("jobs");
    }
}
