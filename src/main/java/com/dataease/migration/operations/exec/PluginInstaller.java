package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.operations.media.*;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Uses the application's permission/license checks and verifies its persisted registration. */
final class PluginInstaller {
    private final HostCommands host;
    private final MediaService media;
    private final ObjectMapper json;
    private final DatabaseArchive database;
    private final ComposeManager compose;

    PluginInstaller(
            HostCommands host, ComposeManager compose, MediaService media, ObjectMapper json, DatabaseArchive database) {
        this.host = host;
        this.compose = compose;
        this.media = media;
        this.json = json; this.database = database;
    }

    void precheck(
            Map<String, Object> plugin,
            Map<String, Object> i,
            Map<String, String> c,
            Map<String, String> operationCredentials,
            String prefix)
            throws Exception {
        Map<String, Object> verified = compatible(plugin, i);
        prefix(prefix);
        if (registered(i, c, text(verified, "module", "")) != null)
            throw new IllegalArgumentException("此模块已在目标登记；请使用DataEase自身的插件更新流程");
        try (var transport = new ContainerApplicationHttp(host, compose, i, c)) {
            String session = session(transport, prefix, operationCredentials);
            requireLicense(transport, prefix, session);
            JsonNode rows =
                    request(transport, prefix, "/plugin/query", session, null, null);
            if (!rows.isArray()) throw new IllegalArgumentException("目标插件查询接口返回格式不受支持");
        }
    }

    Map<String, Object> install(
            Map<String, Object> plugin,
            Map<String, Object> i,
            Map<String, String> c,
            Map<String, String> operationCredentials,
            String prefix,
            Runnable beforeWrite,
            Consumer<String> log)
            throws Exception {
        Map<String, Object> verified = compatible(plugin, i);
        Path jar = media.getPath(plugin);
        if (Files.size(jar) > 500L * 1024 * 1024)
            throw new IllegalArgumentException("插件超过DataEase接口500MB限制");
        String module = text(verified, "module", ""), version = text(verified, "version", "");
        String filename = module + "-" + version.replaceAll("[^A-Za-z0-9._-]", "_") + ".jar";
        if (registered(i, c, module) != null) throw new IllegalArgumentException("插件模块已存在，已阻止覆盖安装");
        String file = path(text(i, "path", "")) + "/data/plugin/" + filename;
        if (!host.capture(i, c, "if [ -e " + quote(file) + " ]; then printf yes; fi").isBlank())
            throw new IllegalArgumentException("目标存在同名插件文件，请先在DataEase核对其归属");
        boolean submitted = false;
        try (var transport = new ContainerApplicationHttp(host, compose, i, c)) {
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            String session;
            while (true) {
                try {
                    session = session(transport, prefix, operationCredentials);
                    requireLicense(transport, prefix, session);
                    request(transport, prefix, "/plugin/query", session, null, null);
                    break;
                } catch (SocketException | ApiUnavailable unavailable) {
                    if (System.nanoTime() >= deadline) throw unavailable;
                    Thread.sleep(1000);
                }
            }
            beforeWrite.run();
            submitted = true;
            request(transport, prefix, "/plugin/install", session, jar, filename);
            JsonNode rows =
                    request(transport, prefix, "/plugin/query", session, null, null);
            Map<String, Object> record = registered(i, c, module);
            if (record == null
                    || !version.equals(record.get("version"))
                    || !Objects.equals(verified.get("flag"), record.get("flag"))
                    || !filename.equals(record.get("jarName")))
                throw new IllegalStateException("接口已响应，但插件数据库登记与上传包身份不一致；请使用保护备份处理");
            boolean listed = false;
            if (rows.isArray())
                for (JsonNode row : rows)
                    if (row.path("id").asText().equals(String.valueOf(record.get("id")))
                            && row.path("version").asText().equals(version)) listed = true;
            if (!listed) throw new IllegalStateException("插件未出现在目标应用查询结果中，不能确认安装成功");
            String digest =
                    host.capture(
                                    i,
                                    c,
                                    "if command -v sha256sum >/dev/null 2>&1; then sha256sum "
                                            + quote(file)
                                            + "; else shasum -a 256 "
                                            + quote(file)
                                            + "; fi")
                            .split("\\s+")[0];
            if (!digest.equals(verified.get("sha256")))
                throw new IllegalStateException("安装后的插件文件摘要不一致");
        } catch (IOException failure) {
            if (!submitted) throw failure;
            SocketException unknown = new SocketException("DataEase插件接口连接中断，安装结果尚未确认");
            unknown.initCause(failure);
            throw unknown;
        }
        log.accept("已通过DataEase安装接口登记，并核对应用查询、数据库身份及插件文件SHA-256");
        return Map.of(
                "module",
                module,
                "version",
                version,
                "sha256",
                verified.get("sha256"),
                "registrationVerified",
                true);
    }

    private String session(ContainerApplicationHttp transport, String prefix,
            Map<String, String> credentials) throws Exception {
        String password = credentials.getOrDefault("dataeaseAdminPassword", "");
        if (password.isBlank() || password.length() > 256)
            throw new IllegalArgumentException("请输入目标 DataEase 的 admin 密码。");
        try {
            return ExecutionService.adminSession(transport, json, prefix(prefix), password);
        } catch (SocketException unavailable) {
            throw unavailable;
        } catch (Exception rejected) {
            throw new IllegalArgumentException("无法登录目标 DataEase，请核对 admin 密码、API 前缀和本地登录设置。");
        }
    }

    private Map<String, Object> compatible(Map<String, Object> plugin, Map<String, Object> i)
            throws Exception {
        Map<String, Object> verified = media.verify(plugin);
        if (!Boolean.TRUE.equals(verified.get("installSupported")))
            throw new IllegalArgumentException("插件缺少受支持的原生描述");
        String issue = dataEaseVersionIssue(text(i, "version", ""), text(verified, "requireVersion", ""));
        if (!issue.isEmpty()) throw new IllegalArgumentException(issue);
        return verified;
    }

    static String dataEaseVersionIssue(String targetVersion, String minimumVersion) {
        String target = targetVersion.replaceFirst("^[vV]", "");
        String minimum = minimumVersion.replaceFirst("^[vV]", "");
        String versionPattern = "[23]\\.\\d{1,8}\\.\\d{1,8}";
        if (!target.matches(versionPattern)) return "无法识别目标 DataEase 版本";
        if (!minimum.matches(versionPattern)) return "缺少可识别的最低 DataEase 版本声明";
        if (target.charAt(0) != minimum.charAt(0)) return "声明适用于 DataEase V" + minimum.charAt(0);
        String[] actual = target.split("\\."), required = minimum.split("\\.");
        for (int n = 1; n < 3; n++) {
            int comparison = Integer.compare(Integer.parseInt(actual[n]), Integer.parseInt(required[n]));
            if (comparison < 0) return "要求 DataEase v" + minimum + " 或更新版本";
            if (comparison > 0) break;
        }
        return "";
    }

    private JsonNode request(
            ContainerApplicationHttp transport,
            String prefix,
            String route,
            String token,
            Path upload,
            String filename)
            throws Exception {
        var response =
                transport.request(prefix(prefix) + route, token, upload, filename);
        if (upload == null && Set.of(502, 503).contains(response.status()))
            throw new ApiUnavailable();
        if (response.status() < 200 || response.status() >= 300)
            throw new IllegalArgumentException(
                    "DataEase插件接口拒绝请求（HTTP " + response.status() + "），请核对管理员令牌、插件管理权限及企业授权");
        if (response.body().length == 0 && upload != null) return json.nullNode();
        JsonNode result;
        try {
            result = json.readTree(response.body());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("DataEase插件接口返回格式不受支持，请核对API前缀");
        }
        if (result == null) throw new IllegalArgumentException("DataEase插件接口返回空内容");
        if (result.isObject() && result.has("code")) {
            if (!result.path("code").isIntegralNumber() || result.path("code").intValue() != 0)
                throw new IllegalArgumentException("DataEase未确认插件请求成功，请检查令牌、授权及目标应用日志");
            return result.path("data");
        }
        return result;
    }

    private void requireLicense(ContainerApplicationHttp transport, String prefix,
            String token) throws Exception {
        var response = transport.validateLicense(prefix(prefix), token);
        if (Set.of(502, 503).contains(response.status())) throw new ApiUnavailable();
        if (response.status() == 401 || response.status() == 403)
            throw new IllegalArgumentException("请提供目标 DataEase 管理员的有效会话令牌。");
        if (response.status() != 200)
            throw new IllegalArgumentException("无法读取 DataEase 企业授权状态，请核对 API 前缀及实例运行状态。");
        var body = json.readTree(response.body());
        if (body == null || !body.isObject())
            throw new IllegalArgumentException("DataEase 授权检查未返回有效状态。");
        if (body.has("code")) {
            if (!Set.of(0, 200).contains(body.path("code").asInt(-1)))
                throw new IllegalArgumentException("DataEase 未授权读取企业授权状态，请核对管理员会话令牌。");
            body = body.path("data");
        }
        if (!"valid".equals(body.path("status").asText()))
            throw new IllegalArgumentException("目标 DataEase 缺少有效企业授权。请在 DataEase 更新授权后重试。");
    }

    private Map<String, Object> registered(
            Map<String, Object> i, Map<String, String> c, String module) throws Exception {
        try (Connection connection = database.connect(i, c);
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id,version,flag,jar_name FROM xpack_plugin WHERE"
                                        + " module_name=?")) {
            statement.setString(1, module);
            statement.setQueryTimeout(10);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                Map<String, Object> record =
                        Map.of(
                                "id",
                                result.getString(1),
                                "version",
                                result.getString(2),
                                "flag",
                                result.getString(3),
                                "jarName",
                                result.getString(4));
                if (result.next()) throw new IllegalArgumentException("目标存在重复插件登记，须先在DataEase处理");
                return record;
            }
        }
    }

    private static String prefix(String value) {
        if (value == null || !value.matches("(?:/[A-Za-z0-9_-]+)*/de2api"))
            throw new IllegalArgumentException("API前缀须为 /de2api 或带部署上下文的对应路径");
        return value;
    }

    private static final class ApiUnavailable extends IOException {
        ApiUnavailable() {
            super("DataEase应用接口尚未就绪");
        }
    }
}
