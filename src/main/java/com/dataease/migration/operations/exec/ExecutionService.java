package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.model.*;
import com.dataease.migration.operations.media.*;
import com.dataease.migration.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

@Service
public class ExecutionService {
    public record Inspection(Map<String, Object> instance, Map<String, String> credentials) {}

    public Map<String, Object> automation(Map<String, Object> instance, Map<String, String> credentials) throws Exception {
        return new AutomationManager(host, compose, json).inventory(instance, credentials);
    }

    public Map<String, Object> restoreAutomation(Map<String, Object> instance, Map<String, String> credentials,
            String snapshot, List<String> selected) throws Exception {
        return new AutomationManager(host, compose, json).apply(instance, credentials, snapshot, selected);
    }

    private final HostCommands host;
    private final ComposeManager compose;
    private final BackupService backups;
    private final MigrationService migration;
    private final MediaService media;
    private final ObjectMapper json;

    public ExecutionService(
            HostCommands host,
            ComposeManager compose,
            BackupService backups,
            MigrationService migration,
            MediaService media,
            ObjectMapper json) {
        this.host = host;
        this.compose = compose;
        this.backups = backups;
        this.migration = migration;
        this.media = media;
        this.json = json;
    }

    public Inspection inspect(Map<String, Object> draft, Map<String, String> credentials)
            throws Exception {
        Map<String, String> secrets = new LinkedHashMap<>(credentials);
        Map<String, Object> i = compose.inspect(draft, secrets);
        if (text(i, "dbType", "MySQL").equals("MySQL")) {
            try (Connection c = DatabaseArchive.connect(i, secrets);
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT 1")) {
                if (!rs.next()) throw new IllegalArgumentException("数据库读取检查失败");
            }
            try (Connection database = DatabaseArchive.connect(i, secrets)) {
                i.put("databaseIdentity", databaseIdentity(database));
            }
            verifyManagedDatabaseIdentity(i, secrets);
        }
        if (text(i, "dbType", "MySQL").equals("MySQL")) {
            try (Connection connection = DatabaseArchive.connect(i, secrets);
                    PreparedStatement q =
                            connection.prepareStatement(
                                    "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=?"
                                            + " AND TABLE_NAME='core_de_engine'")) {
                q.setString(1, text(i, "dbName", ""));
                boolean exists;
                try (ResultSet rs = q.executeQuery()) {
                    exists = rs.next();
                }
                if (exists) {
                    try (Statement query = connection.createStatement();
                            ResultSet engines =
                                    query.executeQuery("SELECT type FROM core_de_engine")) {
                        Set<String> types = new LinkedHashSet<>();
                        while (engines.next()) types.add(engines.getString(1));
                        if (types.stream().anyMatch(type -> !"MySQL".equalsIgnoreCase(type))) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> environment =
                                    (Map<String, Object>) i.get("environmentConfig");
                            environment.put("engineMode", "external");
                            environment.put("engineProtection", "external");
                            environment.put("engineType", String.join(",", types));
                        } else if (i.get("environmentConfig") instanceof Map<?, ?> environment
                                && "shared".equals(environment.get("engineMode"))) {
                            boolean shared;
                            try { shared = new SharedEngineConfiguration(json).verify(i, secrets); }
                            catch (Exception unsupported) { shared = false; }
                            if (!shared) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> actual = (Map<String, Object>) environment;
                                actual.put("engineMode", "external");
                                actual.put("engineProtection", "external");
                                actual.put("engineType", "无法确认MySQL引擎与元数据库共库");
                            }
                        }
                    }
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> environment = (Map<String, Object>) i.get("environmentConfig");
                    environment.put("engineMode", "unknown");
                    environment.put("engineProtection", "unknown");
                    environment.put("engineType", "元数据库中缺少引擎配置");
                }
            }
        }
        i.put("connectionCheckedAt", java.time.Instant.now().toString());
        return new Inspection(i, secrets);
    }

    private void verifyManagedDatabaseIdentity(Map<String, Object> i, Map<String, String> secrets)
            throws Exception {
        if (!text(i, "databaseService", "").isBlank()) {
            if (!Boolean.TRUE.equals(i.get("databaseRouteVerified")))
                throw new IllegalArgumentException(
                        "应用数据库地址未指向受管MySQL服务的容器名称或网络别名，不能验证隔离后的连接；请先核对部署配置");
            String id = compose.containerId(i, secrets, text(i, "databaseService", ""));
            String identity =
                    host.capture(
                            i,
                            secrets,
                            "docker exec " + quote(id) + " cat /var/lib/mysql/auto.cnf");
            var matcher =
                    java.util.regex.Pattern.compile(
                                    "(?m)^server-uuid\\s*=\\s*([a-fA-F0-9-]{36})\\s*$")
                            .matcher(identity);
            if (!matcher.find()
                    || !String.valueOf(i.get("databaseIdentity"))
                            .startsWith(matcher.group(1) + "/"))
                throw new IllegalArgumentException("填写的数据库连接与该环境MySQL容器的实际身份不一致，已阻止接入或变更");
        }
    }

    public Inspection inspectRecoveryTarget(Map<String, Object> draft, Map<String, String> credentials)
            throws Exception {
        Map<String, String> secrets = new LinkedHashMap<>(credentials);
        Map<String, Object> actual = compose.inspect(draft, secrets);
        if (!isolationSupported(actual)) throw new IllegalArgumentException("保护恢复仅支持已识别的单机MySQL Compose环境");
        var state = DatabaseArchive.observe(actual, secrets);
        if (text(draft, "databaseIdentity", "").isBlank()
                || !text(draft, "databaseIdentity", "").equals(state.identity()))
            throw new IllegalArgumentException("恢复目标的实际MySQL服务身份已变化，请重新核对目标，不能沿用原任务保护恢复");
        actual.put("databaseIdentity", state.identity());
        actual.put("metadataDatabasePresent", state.present());
        verifyManagedDatabaseIdentity(actual, secrets);
        return new Inspection(actual, secrets);
    }

    public Map<String, Object> backupRecoveryResources(Map<String, Object> target,
            Map<String, String> credentials, Consumer<String> log) throws Exception {
        Inspection actual = inspectRecoveryTarget(target, credentials);
        return backups.create(actual.instance(), actual.credentials(), "资源备份", false,
                redact(actual.credentials(), log));
    }

    public void createMissingProtectionDatabase(Map<String, Object> target,
            Map<String, String> credentials, Map<String, Object> backup) throws Exception {
        backups.validateRestore(backup, target, credentials);
        if (!Boolean.TRUE.equals(target.get("protectionRecovery"))
                || !Boolean.FALSE.equals(target.get("metadataDatabasePresent")))
            throw new IllegalArgumentException("只能从原失败任务的保护备份重建已确认缺失的元数据库");
        var before = DatabaseArchive.observe(target, credentials);
        if (before.present() || !before.identity().equals(text(backup, "databaseIdentity", "")))
            throw new IllegalArgumentException("元数据库状态或实际服务身份已变化，未执行重建");
        validateDatabaseRebuildPrivileges(target, credentials);
        try (Connection connection = DatabaseArchive.connectServer(target, credentials);
                Statement query = connection.createStatement()) {
            query.setQueryTimeout(15);
            query.execute("CREATE DATABASE " + DatabaseArchive.identifier(text(target, "dbName", ""))
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        var after = DatabaseArchive.observe(target, credentials);
        if (!after.present() || !after.identity().equals(before.identity()))
            throw new IllegalStateException("元数据库重建未通过实际身份复核");
        target.put("metadataDatabasePresent", true);
    }

    public void validateDatabaseRebuildPrivileges(Map<String, Object> target,
            Map<String, String> credentials) throws Exception {
        try (Connection connection = DatabaseArchive.connectServer(target, credentials)) {
            DatabaseRebuildPrivileges.require(connection, text(target, "dbName", ""));
        }
    }

    public List<Map<String, Object>> health(Map<String, Object> i, Map<String, String> c)
            throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>();
        try {
            Map<String, Object> actual = compose.inspect(i, new LinkedHashMap<>(c));
            i.put("status", actual.get("status"));
            checks.add(
                    check(
                            "application",
                            "应用与容器状态",
                            text(actual, "status", "").equals("运行中"),
                            text(actual, "applicationService", "") + " · " + actual.get("status")));
            checks.add(
                    check(
                            "version",
                            "实际版本与架构",
                            Objects.equals(actual.get("version"), i.get("version"))
                                    && Objects.equals(actual.get("arch"), i.get("arch")),
                            actual.get("version") + " / " + actual.get("arch")));
        } catch (Exception e) {
            i.put("status", "待检查");
            checks.add(check("application", "应用与容器状态", false, safe(e)));
        }
        try (Connection connection = DatabaseArchive.connect(i, c);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            checks.add(
                    check(
                            "database",
                            "元数据库连接",
                            rs.next(),
                            text(i, "dbHost", "")
                                    + ":"
                                    + text(i, "dbPort", "3306")
                                    + " / "
                                    + text(i, "dbName", "")));
        } catch (Exception e) {
            checks.add(check("database", "元数据库连接", false, safe(e)));
        }
        try {
            String result =
                    host.capture(
                            i,
                            c,
                            "test -d "
                                    + quote(path(text(i, "path", "")) + "/data")
                                    + " && test -r "
                                    + quote(text(i, "path", "") + "/data")
                                    + " && df -Pk "
                                    + quote(text(i, "path", ""))
                                    + " | tail -1");
            checks.add(check("resources", "磁盘与资源目录", !result.isBlank(), result));
        } catch (Exception e) {
            checks.add(check("resources", "磁盘与资源目录", false, safe(e)));
        }
        try {
            try (var transport = new ContainerApplicationHttp(host, compose, i, c)) {
                var response = transport.request("/de2api/dekey", "", null, null);
                int code = response.status();
                checks.add(
                        check("entry", "DataEase公共接口", code == 200 && validPublicKey(response.body()),
                                "实际DataEase公钥接口HTTP状态：" + code));
            }
        } catch (Exception unavailable) {
            checks.add(check("entry", "DataEase公共接口", false, "容器内应用入口不可用，请核对启动、端口与部署上下文"));
        }
        checks.add(
                check(
                        "isolation",
                        "隔离编排支持",
                        isolationSupported(i),
                        isolationSupported(i)
                                ? "单应用与MySQL Compose，可使用internal网络与本机端口"
                                : "此环境尚无可验证隔离编排，不能自动执行变更"));
        return checks;
    }

    public Optional<Boolean> backupPluginRegistry(Map<String, Object> record) throws Exception {
        return backups.pluginRegistryPresent(record);
    }

    public List<Map<String, Object>> acceptanceHealth(Map<String, Object> i, Map<String, String> c,
            boolean pluginRegistryExpected)
            throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>(health(i, c));
        try {
            compose.verifyIsolation(i, c);
            checks.add(check("runtime-isolation", "实际网络与访问隔离", true, "已复核指定internal网络及本机端口绑定策略；浏览器入口需单独核对"));
        } catch (Exception unavailable) {
            checks.add(check("runtime-isolation", "实际网络与访问隔离", false, "当前网络或端口已偏离隔离配置，禁止完成验收"));
        }
        try {
            validateDatabaseAutomation(i, c);
            checks.add(check("database-automation", "数据库自动任务暂停", true, "未发现启用的MySQL事件任务"));
        } catch (Exception unavailable) {
            checks.add(check("database-automation", "数据库自动任务暂停", false, "MySQL事件任务仍启用或无法核验，请由DBA处理后重新检查"));
        }
        try {
            compose.verifyGuard(i, c);
            checks.add(check("scheduler-standby", "自动任务强制暂停", true, "已从应用进程核验Quartz待机且无正在执行的任务"));
        } catch (Exception unavailable) {
            checks.add(check("scheduler-standby", "自动任务强制暂停", false, "无法核验应用内调度隔离组件，禁止开放访问"));
        }
        try (Connection connection = DatabaseArchive.connect(i, c);
                Statement statement = connection.createStatement();
                ResultSet registry = statement.executeQuery("SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='xpack_plugin'")) {
            boolean present = registry.next();
            if (!present && pluginRegistryExpected)
                throw new IllegalStateException("保护范围要求的插件登记表缺失");
            int count = 0;
            if (present) try (Statement query = connection.createStatement();
                    ResultSet plugins = query.executeQuery("SELECT name,jar_name FROM xpack_plugin")) {
            while (plugins.next()) {
                String name = plugins.getString(2);
                if (name == null || !Path.of(name).getFileName().toString().equals(name))
                    throw new IllegalStateException("插件登记文件名无效");
                host.capture(
                        i,
                        c,
                        "test -r " + quote(path(text(i, "path", "")) + "/data/plugin/" + name));
                count++;
            }
            }
            checks.add(
                    check(
                            "plugins",
                            "插件登记与资源文件",
                            true,
                            present ? "已核对" + count + "个登记插件文件可读；授权和实际业务使用需管理员核对"
                                    : "按本次操作的插件覆盖范围，此环境未启用插件登记模块"));
        } catch (Exception e) {
            checks.add(check("plugins", "插件登记与资源文件", false, "插件登记表、文件或读取权限未通过，请修复后重新验收"));
        }
        Map<?, ?> environment = i.get("environmentConfig") instanceof Map<?, ?> e ? e : Map.of();
        String query = text(environment, "acceptanceQuery", "").trim();
        if (query.isBlank())
            checks.add(
                    check(
                            "business-query",
                            "代表性业务查询",
                            false,
                            "请在环境组成中配置代表性只读验收SQL；SELECT 1仅代表数据库连接，不替代业务查询"));
        else
            try {
                if (!query.matches("(?is)^SELECT\\s+.+")
                        || query.contains(";")
                        || query.matches(
                                "(?is).*(?:\\bINTO\\b|\\bFOR\\s+UPDATE\\b|\\bLOCK\\b|\\bOUTFILE\\b|\\bDUMPFILE\\b).*"))
                    throw new IllegalArgumentException("验收SQL必须为不含写入或多语句的SELECT");
                try (Connection connection = DatabaseArchive.connect(i, c)) {
                    connection.setReadOnly(true);
                    connection.setAutoCommit(false);
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(10);
                        statement.setMaxRows(1);
                        try (ResultSet rs = statement.executeQuery(query)) {
                            checks.add(
                                    check(
                                            "business-query",
                                            "代表性业务查询",
                                            rs.next(),
                                            "已在只读事务执行配置的业务查询，最多读取一行"));
                        }
                    } finally {
                        connection.rollback();
                    }
                }
            } catch (Exception e) {
                checks.add(check("business-query", "代表性业务查询", false, safe(e)));
            }
        return checks;
    }

    public String hostIdentity(Map<String, Object> config, Map<String, String> credentials)
            throws Exception {
        return host.capture(config, credentials, "docker info --format '{{.ID}}'");
    }

    public List<Map<String, Object>> precheckTarget(
            Map<String, Object> config, Map<String, String> creds) throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>();
        String target = path(text(config, "path", ""));
        config.put("hostIdentity", hostIdentity(config, creds));
        try {
            String system =
                    host.capture(
                            config,
                            creds,
                            "uname -s && uname -m && docker compose version --short");
            boolean linux = system.startsWith("Linux") || system.startsWith("Darwin");
            checks.add(check("runtime", "服务器与Compose", linux, system));
            String actualArch =
                    system.split("\\r?\\n")[1].matches("(?i)(arm64|aarch64)")
                            ? "ARM64"
                            : system.split("\\r?\\n")[1].matches("(?i)(amd64|x86_64)")
                                    ? "x86_64"
                                    : "unknown";
            checks.add(
                    check(
                            "target-architecture",
                            "目标服务器架构",
                            actualArch.equals(config.get("arch")),
                            "实际架构：" + actualArch));
            String installedImages =
                    host.capture(config, creds, "docker ps -a --format '{{.Image}}'");
            boolean other =
                    Arrays.stream(installedImages.split("\\r?\\n"))
                            .map(image -> image.substring(image.lastIndexOf('/') + 1).split(":")[0])
                            .anyMatch(
                                    image ->
                                            image.equals("dataease")
                                                    || image.startsWith("dataease-"));
            checks.add(
                    check(
                            "single-host",
                            "已有DataEase部署",
                            !other,
                            other ? "该服务器已有DataEase容器，请接入已有环境；首版不自动新建第二实例" : "未发现已有DataEase应用容器"));
            String exists =
                    host.capture(
                            config,
                            creds,
                            "if [ -e "
                                    + quote(target)
                                    + " ]; then if [ -d "
                                    + quote(target)
                                    + " ] && [ -z \"$(ls -A "
                                    + quote(target)
                                    + ")\" ]; then printf empty; else printf occupied; fi; else"
                                    + " printf absent; fi");
            checks.add(
                    check(
                            "target-directory",
                            "独立安装目录",
                            !exists.equals("occupied"),
                            exists.equals("occupied") ? "目标目录已有内容，请接入已有环境或选择空目录" : "安装目录可用于新实例"));
            String parent = Path.of(target).getParent().toString();
            String disk =
                    host.capture(
                            config,
                            creds,
                            "test -w "
                                    + quote(parent)
                                    + " && df -Pk "
                                    + quote(parent)
                                    + " | tail -1");
            String[] columns = disk.trim().split("\\s+");
            long available = columns.length >= 4 ? Long.parseLong(columns[3]) : -1;
            checks.add(check("target-disk", "目录权限与可用空间", available >= 1024L * 1024 * 10, disk));
        } catch (Exception e) {
            checks.add(check("target-runtime", "服务器与运行条件", false, safe(e)));
        }
        return checks;
    }

    public boolean isolationSupported(Map<String, Object> instance) {
        return compose.isolationSupported(instance);
    }

    public void precheckPlugin(
            Map<String, Object> plugin,
            Map<String, Object> instance,
            Map<String, String> credentials,
            Map<String, String> operationCredentials,
            String prefix)
            throws Exception {
        new PluginInstaller(host, compose, media, json)
                .precheck(plugin, instance, credentials, operationCredentials, prefix);
    }

    public Map<String, Object> installPlugin(
            Map<String, Object> plugin,
            Map<String, Object> instance,
            Map<String, String> credentials,
            Map<String, String> operationCredentials,
            String prefix,
            Consumer<String> log)
            throws Exception {
        return new PluginInstaller(host, compose, media, json)
                .install(plugin, instance, credentials, operationCredentials, prefix, log);
    }

    public void validateRollbackPackage(
            Map<String, Object> packageRecord,
            Map<String, Object> backup,
            Map<String, Object> target)
            throws Exception {
        Map<String, Object> pkg = media.verify(packageRecord);
        if (!DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter"))
                || !Objects.equals(pkg.get("version"), backup.get("version"))
                || !Objects.equals(pkg.get("arch"), target.get("arch"))
                || !text(pkg, "version", "")
                        .substring(0, 3)
                        .equals(text(target, "version", "").substring(0, 3)))
            throw new IllegalArgumentException("版本回退需与保护备份精确版本、架构匹配的内置Compose离线包，且只能在同一主版本内执行");
    }

    public void rollbackApplication(
            Map<String, Object> packageRecord,
            Map<String, Object> backup,
            Map<String, Object> target,
            Map<String, String> credentials,
            Consumer<String> log)
            throws Exception {
        validateRollbackPackage(packageRecord, backup, target);
        new OfflineComposeInstaller(host, compose, json)
                .install(media.verify(packageRecord), target, credentials, true, log);
    }

    public void validateMigration(Map<String, Object> source, Map<String, Object> target)
            throws Exception {
        String version = text(source, "version", "").replaceFirst("^[vV]", "");
        if (!version.equals("dev-v2")) {
            String[] v = version.split("\\.");
            if (v.length != 3
                    || !v[0].equals("2")
                    || Integer.parseInt(v[1]) < 10
                    || Integer.parseInt(v[1]) == 10 && Integer.parseInt(v[2]) < 26)
                throw new IllegalArgumentException("源端必须为v2.10.26及以上的V2");
        }
        if (!text(target, "version", "").matches("v?3\\.\\d+\\.\\d+"))
            throw new IllegalArgumentException("目标必须为已安装的精确V3版本");
        if (!text(source, "dbType", "").equals("MySQL")
                || !text(target, "dbType", "").equals("MySQL"))
            throw new IllegalArgumentException("V2迁移只支持MySQL到MySQL");
        if (!isolationSupported(source) || !isolationSupported(target))
            throw new IllegalArgumentException("源端与目标均需为可识别的单应用与MySQL Compose部署");
        String a = path(text(source, "path", "")), b = path(text(target, "path", ""));
        boolean sameHost =
                text(source, "host", "").equalsIgnoreCase(text(target, "host", ""))
                        || text(source, "connection", "").equals("local")
                                && text(target, "connection", "").equals("local");
        if (sameHost && (Path.of(a).startsWith(Path.of(b)) || Path.of(b).startsWith(Path.of(a))))
            throw new IllegalArgumentException("同机源目标安装目录不能相同或互相包含");
        if (text(source, "dbHost", "").equalsIgnoreCase(text(target, "dbHost", ""))
                && text(source, "dbPort", "3306").equals(text(target, "dbPort", "3306"))
                && text(source, "dbName", "").equals(text(target, "dbName", "")))
            throw new IllegalArgumentException("源与目标数据库不能相同");
    }

    public void validateMigrationEndpoints(Map<String, Object> source, Map<String, String> sc,
            Map<String, Object> target, Map<String, String> tc) throws Exception {
        for (var endpoint : List.of(Map.entry(source, sc), Map.entry(target, tc))) {
            Inspection actual = inspect(endpoint.getKey(), new LinkedHashMap<>(endpoint.getValue()));
            for (String field : List.of("hostIdentity", "databaseIdentity", "version", "path",
                    "composeProject", "applicationService", "databaseService"))
                if (!Objects.equals(endpoint.getKey().get(field), actual.instance().get(field)))
                    throw new IllegalArgumentException("迁移环境身份或部署已变化，请维护连接并重新预检查");
            for (String field : List.of("runtimeDbUrl", "runtimeDbUser", "runtimeDbPassword"))
                if (!Objects.equals(endpoint.getValue().get(field), actual.credentials().get(field)))
                    throw new IllegalArgumentException("迁移环境应用数据库连接已变化，请维护连接并重新预检查");
        }
    }

    public void validateMigrationSchemas(Map<String, Object> source, Map<String, String> sc,
            Map<String, Object> target, Map<String, String> tc) throws Exception {
        migration.validateDependencies(text(target, "path", ""));
        migration.validateSchemas(databaseInfo(source, sc), databaseInfo(target, tc));
        backups.validateMigrationResources(source, sc, target, tc);
        new SharedEngineConfiguration(json).validateExcelFiles(source, sc, host);
        try (Connection a = DatabaseArchive.connect(source, sc);
                Connection b = DatabaseArchive.connect(target, tc)) {
            DatabaseArchive.requireNoEnabledEvents(a);
            DatabaseArchive.requireNoEnabledEvents(b);
            DatabaseRebuildPrivileges.require(b);
        }
    }

    public void validateDatabaseAutomation(Map<String, Object> instance, Map<String, String> credentials)
            throws Exception {
        try (Connection connection = DatabaseArchive.connect(instance, credentials)) {
            DatabaseArchive.requireNoEnabledEvents(connection);
        }
    }

    public void service(
            Map<String, Object> i,
            Map<String, String> c,
            boolean start,
            boolean isolated,
            Consumer<String> log)
            throws Exception {
        if (start) {
            if (isolated) compose.start(i, c, true, log);
            else compose.startExisting(i, c, log);
        } else compose.stop(i, c, log);
    }

    public Map<String, Object> backup(
            Map<String, Object> i,
            Map<String, String> c,
            String scope,
            boolean includeImages,
            Consumer<String> log)
            throws Exception {
        Inspection found = inspect(i, new LinkedHashMap<>(c));
        Map<String, Object> actual = found.instance();
        Map<?, ?> env = actual.get("environmentConfig") instanceof Map<?, ?> e ? e : Map.of();
        if (!scope.equals("资源备份")
                && !"shared".equals(String.valueOf(env.getOrDefault("engineMode", null))))
            throw new IllegalArgumentException("首版自动数据保护仅覆盖与元数据库共库的引擎；外部引擎需独立适配与备份凭证");
        return backups.create(actual, found.credentials(), scope, includeImages, redact(found.credentials(), log));
    }

    public void validateRestore(
            Map<String, Object> record, Map<String, Object> target, Map<String, String> credentials)
            throws Exception {
        backups.validateRestore(record, target, credentials);
    }

    public Map<String, Object> verifyBackup(Map<String, Object> record) throws Exception {
        return backups.verify(record);
    }

    public Path exportBackup(Map<String, Object> record, char[] password) throws Exception {
        return backups.exportPortable(record, password);
    }

    public Map<String, Object> importBackup(Map<String, Object> record, char[] password) throws Exception {
        return backups.importBackup(record, password);
    }

    public void restore(
            Map<String, Object> backup,
            Map<String, Object> target,
            Map<String, String> c,
            Consumer<String> log)
            throws Exception {
        backups.restore(backup, target, c, redact(c, log));
    }

    public void migrate(
            Map<String, Object> source,
            Map<String, String> sc,
            Map<String, Object> target,
            Map<String, String> tc,
            boolean copyLogs,
            Consumer<String> log)
            throws Exception {
        validateMigrationEndpoints(source, sc, target, tc);
        validateMigrationSchemas(source, sc, target, tc);
        validateMigration(source, target);
        try (Connection a = DatabaseArchive.connect(source, sc);
                Connection b = DatabaseArchive.connect(target, tc)) {
            String sourceId = databaseIdentity(a), targetId = databaseIdentity(b);
            if (sourceId.equals(targetId))
                throw new IllegalArgumentException("两端实际指向同一MySQL数据库，已阻止覆盖");
        }
        for (Map.Entry<Map<String, Object>, Map<String, String>> endpoint :
                List.of(Map.entry(source, sc), Map.entry(target, tc))) {
            String id =
                    compose.containerId(
                            endpoint.getKey(),
                            endpoint.getValue(),
                            text(endpoint.getKey(), "applicationService", ""));
            if (!id.isBlank()
                    && Boolean.TRUE.equals(
                            ((Map<?, ?>)
                                            compose.container(
                                                            endpoint.getKey(),
                                                            endpoint.getValue(),
                                                            id)
                                                    .get("State"))
                                    .get("Running")))
                throw new IllegalStateException("迁移前两端应用必须均已停止");
        }
        Consumer<String> safeLog = redact(sc, redact(tc, log));
        safeLog.accept("开始替换目标资源；从此阶段起目标可能已发生写入");
        for (String relative :
                List.of(
                        "i18n",
                        "font",
                        "exportData",
                        "map",
                        "geo",
                        "appearance",
                        "static-resource",
                        "excel",
                        "plugin"))
            host.run(
                    target,
                    tc,
                    "rm -rf -- " + quote(path(text(target, "path", "")) + "/data/" + relative),
                    line -> {});
        MigrationRequest request =
                new MigrationRequest(
                        host.server(source, sc),
                        databaseInfo(source, sc),
                        host.server(target, tc),
                        databaseInfo(target, tc),
                        copyLogs);
        migration.execute(request, new MigrationJob(safeLog));
        new SharedEngineConfiguration(json).relocate(source, sc, target, tc);
        new SharedEngineConfiguration(json).relocateExcelFiles(target, tc, host);
        safeLog.accept("已将共库引擎与内置Demo数据源重绑定至目标应用实际数据库连接，业务外部数据源保持原配置");
        safeLog.accept("已核对本地Excel原文件并将受管文件路径转换至V3目录");
    }

    public void install(
            Map<String, Object> packageRecord,
            Map<String, Object> config,
            Map<String, String> creds,
            boolean upgrade,
            Consumer<String> log)
            throws Exception {
        Map<String, Object> pkg = media.verify(packageRecord);
        if (!"已校验".equals(pkg.get("status"))
                || !Integer.valueOf(1).equals(pkg.get("operationsProtocol")))
            throw new IllegalArgumentException("此离线包未提供可执行的operationsProtocol=1非交互安装适配入口");
        String purpose =
                upgrade
                        ? "upgrade"
                        : "restore".equals(config.get("operationPurpose")) ? "restore" : "install";
        if (!(pkg.get("purposes") instanceof List<?> purposes) || !purposes.contains(purpose))
            throw new IllegalArgumentException("离线包不允许当前用途");
        if (!Objects.equals(pkg.get("arch"), config.get("arch")))
            throw new IllegalArgumentException("离线包架构不匹配");
        if (!(pkg.get("dbTypes") instanceof List<?> dbTypes)
                || !dbTypes.contains(config.get("dbType")))
            throw new IllegalArgumentException("离线包不支持目标元数据库");
        if (upgrade) {
            String current = text(config, "version", "").replace("v", "");
            String target = text(pkg, "version", "").replace("v", "");
            if (current.charAt(0) != target.charAt(0))
                throw new IllegalArgumentException("跨主版本请使用V2到V3迁移");
            if (compareVersions(target, current) <= 0)
                throw new IllegalArgumentException("升级目标版本必须更高");
        }
        if (DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter"))) {
            new OfflineComposeInstaller(host, compose, json)
                    .install(pkg, config, creds, upgrade, log);
            return;
        }
        Path content = Path.of(text(pkg, "contentRoot", ""));
        Path archive = Files.createTempFile("deops-install-", ".tar.gz");
        String remote = "/tmp/deops-install-" + UUID.randomUUID();
        Path configuration = Files.createTempFile("deops-install-config-", ".json");
        try {
            Process tar =
                    new ProcessBuilder(
                                    "tar",
                                    "--no-xattrs",
                                    "-C",
                                    content.toString(),
                                    "-czf",
                                    archive.toString(),
                                    ".")
                            .start();
            if (!tar.waitFor(10, java.util.concurrent.TimeUnit.MINUTES) || tar.exitValue() != 0)
                throw new IllegalStateException("离线包分发归档失败");
            host.run(config, creds, "umask 077; mkdir -p " + quote(remote), line -> {});
            host.upload(config, creds, archive, remote + "/package.tar.gz");
            String localHash = ArchiveSupport.sha256(archive);
            String remoteHash =
                    host.capture(
                                    config,
                                    creds,
                                    "if command -v sha256sum >/dev/null 2>&1; then sha256sum "
                                            + quote(remote + "/package.tar.gz")
                                            + "; else shasum -a 256 "
                                            + quote(remote + "/package.tar.gz")
                                            + "; fi")
                            .split("\\s+")[0];
            if (!localHash.equals(remoteHash)) throw new IllegalStateException("目标服务器介质摘要不一致");
            Map<String, Object> operationConfig = new LinkedHashMap<>();
            operationConfig.put("operation", purpose);
            operationConfig.put("instance", config);
            operationConfig.put("credentials", creds);
            operationConfig.put("offline", true);
            operationConfig.put("startApplication", false);
            operationConfig.put("accessMode", "isolated");
            Files.writeString(
                    configuration,
                    json.writeValueAsString(operationConfig),
                    StandardCharsets.UTF_8);
            host.upload(config, creds, configuration, remote + "/operation.json");
            host.run(
                    config,
                    creds,
                    "chmod 600 "
                            + quote(remote + "/operation.json")
                            + " && mkdir "
                            + quote(remote + "/content")
                            + " && tar -C "
                            + quote(remote + "/content")
                            + " -xzf "
                            + quote(remote + "/package.tar.gz"),
                    line -> {});
            Path entry = MediaService.safeChild(content, text(pkg, "entrypoint", ""));
            String relative = content.relativize(entry).toString();
            log.accept("正在执行已校验的离线安装适配入口");
            host.run(
                    config,
                    creds,
                    "cd "
                            + quote(remote + "/content")
                            + " && /bin/sh "
                            + quote(relative)
                            + " --operations-config "
                            + quote(remote + "/operation.json"),
                    redact(creds, log));
            Map<String, Object> expected = new LinkedHashMap<>(config);
            expected.remove("version");
            Map<String, Object> installed = compose.inspect(expected, new LinkedHashMap<>(creds));
            if (!Objects.equals(installed.get("version"), pkg.get("version")))
                throw new IllegalStateException("实际安装版本与离线包不一致");
            if ("运行中".equals(installed.get("status"))) {
                compose.stop(installed, creds, log);
                throw new IllegalStateException("安装适配入口未遵守禁止启动应用的协议，已停止应用；请修正介质后重试");
            }
            if (!isolationSupported(installed))
                throw new IllegalStateException("安装完成但无法建立受支持隔离，应用保持停止");
            config.putAll(installed);
            log.accept("已验证实际安装版本，应用保持停止，等待隔离启动");
        } finally {
            Files.deleteIfExists(configuration);
            Files.deleteIfExists(archive);
            try {
                host.run(config, creds, "rm -rf -- " + quote(remote), line -> {});
            } catch (Exception ignored) {
            }
        }
    }

    public void release(
            Map<String, Object> i, Map<String, String> c, boolean open, Consumer<String> log)
            throws Exception {
        if (open) {
            compose.start(i, c, false, log);
        } else {
            compose.verifyIsolation(i, c);
            compose.verifyGuard(i, c);
            log.accept("验收完成，已复核并保留当前隔离容器及自动任务待机状态");
        }
    }

    private static DatabaseInfo databaseInfo(Map<String, Object> i, Map<String, String> c) {
        String h = text(i, "dbHost", text(i, "host", ""));
        if (h.contains(":")) h = "[" + h + "]";
        return new DatabaseInfo(
                "jdbc:mysql://"
                        + h
                        + ":"
                        + text(i, "dbPort", "3306")
                        + "/"
                        + text(i, "dbName", "")
                        + "?connectTimeout=15000&socketTimeout=600000&useSSL=false&allowPublicKeyRetrieval=true",
                c.getOrDefault("dbUser", text(i, "dbUser", "dataease")),
                c.getOrDefault("dbPassword", ""));
    }

    private static String databaseIdentity(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT @@server_uuid,DATABASE()")) {
            rs.next();
            return rs.getString(1) + "/" + rs.getString(2);
        }
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int n = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (n != 0) return n;
        }
        return 0;
    }

    private static Consumer<String> redact(Map<String, String> c, Consumer<String> log) {
        return value -> {
            String line = value;
            for (String key :
                    c.keySet().stream()
                            .filter(
                                    name ->
                                            name.toLowerCase(java.util.Locale.ROOT)
                                                            .matches(
                                                                    ".*(?:password|privatekey|passphrase|secret).*")
                                                    || name.equals("runtimeDbUrl"))
                            .toList()) {
                String secret = c.get(key);
                if (secret != null && !secret.isBlank()) line = line.replace(secret, "[REDACTED]");
            }
            log.accept(line);
        };
    }

    private boolean validPublicKey(byte[] response) {
        if (response.length > 8192) return false;
        String value = new String(response, java.nio.charset.StandardCharsets.UTF_8).trim();
        try {
            var body = json.readTree(response);
            if (body == null) return false;
            if (body.isTextual()) value = body.asText();
            else if (body.isObject() && Set.of(0, 200).contains(body.path("code").asInt(-1))
                    && body.path("data").isTextual()) value = body.path("data").asText();
            else return false;
        } catch (com.fasterxml.jackson.core.JacksonException plainKey) {
            // The native String endpoint may omit a JSON envelope.
        } catch (java.io.IOException unavailable) { return false; }
        return value.length() > 100 && value.matches("[A-Za-z0-9+/=_-]+");
    }

    private static Map<String, Object> check(String code, String title, boolean ok, String detail) {
        return Map.of(
                "code",
                code,
                "title",
                title,
                "status",
                ok ? "passed" : "blocked",
                "detail",
                detail);
    }

    private static String safe(Exception e) {
        return e instanceof SQLException
                ? "数据库连接或读取失败，请核对地址、账号与权限"
                : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
