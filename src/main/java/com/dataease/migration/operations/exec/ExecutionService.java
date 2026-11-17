package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.model.*;
import com.dataease.migration.operations.core.OperationsApiException;
import com.dataease.migration.operations.media.*;
import com.dataease.migration.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

@Service
public class ExecutionService {
    public static final String NO_PLUGIN_REGISTRY_DETAIL = "按本次操作的插件覆盖范围，此环境未启用插件登记模块";
    private static final Set<String> PREVIOUS_DECTL_SHA256 = Set.of(
            "7f4fe3a3df3047deb629775eb8c3917041c51e80e93bb8c0b285291ec6153ebe",
            "9ee7407bb3dcdd81e66c546d2e1a52a8e198553c0b4ac12b634731034d0ee11f");
    public record Inspection(Map<String, Object> instance, Map<String, String> credentials) {}

    public Map<String, Object> automation(Map<String, Object> instance, Map<String, String> credentials) throws Exception {
        return new AutomationManager(host, compose, json, database).inventory(instance, credentials);
    }

    public Map<String, Object> restoreAutomation(Map<String, Object> instance, Map<String, String> credentials,
            String snapshot, List<String> selected) throws Exception {
        return new AutomationManager(host, compose, json, database).apply(instance, credentials, snapshot, selected);
    }

    private final HostCommands host;
    private final ComposeManager compose;
    private final BackupService backups;
    private final MigrationService migration;
    private final MediaService media;
    private final ObjectMapper json;
    private final DatabaseArchive database;

    public ExecutionService(
            HostCommands host,
            ComposeManager compose,
            BackupService backups,
            MigrationService migration,
            MediaService media,
            ObjectMapper json, DatabaseArchive database) {
        this.host = host;
        this.compose = compose;
        this.backups = backups;
        this.migration = migration;
        this.media = media;
        this.json = json; this.database = database;
    }

    public Inspection inspect(Map<String, Object> draft, Map<String, String> credentials)
            throws Exception {
        Map<String, String> secrets = new LinkedHashMap<>(credentials);
        Map<String, Object> i = compose.inspect(draft, secrets);
        return inspectDatabase(i, secrets);
    }

    private Inspection inspectDatabase(Map<String, Object> i, Map<String, String> secrets) throws Exception {
        if (text(i, "dbType", "MySQL").equals("MySQL")) {
            try (Connection c = database.connect(i, secrets);
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT 1")) {
                if (!rs.next()) throw new IllegalArgumentException("数据库读取检查失败");
            }
            try (Connection connection = database.connect(i, secrets)) {
                i.put("databaseIdentity", databaseIdentity(connection));
            }
            verifyManagedDatabaseIdentity(i, secrets);
        }
        if (text(i, "dbType", "MySQL").equals("MySQL")) {
            try (Connection connection = database.connect(i, secrets);
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
                                && !"external".equals(environment.get("engineMode"))) {
                            boolean shared;
                            try { shared = new SharedEngineConfiguration(json, database).verify(i, secrets); }
                            catch (Exception unsupported) { shared = false; }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> actual = (Map<String, Object>) environment;
                            actual.put("engineMode", shared ? "shared" : "external");
                            actual.put("engineProtection", shared ? "managed" : "external");
                            actual.put("engineType", shared ? "MySQL" : "无法确认MySQL引擎与元数据库共库");
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
        verifyManagedDatabaseIdentity(i, secrets, false);
    }

    private void verifyManagedDatabaseIdentity(Map<String, Object> i, Map<String, String> secrets, boolean stopped)
            throws Exception {
        if (!text(i, "databaseService", "").isBlank()) {
            if (!Boolean.TRUE.equals(i.get("databaseRouteVerified")))
                throw new IllegalArgumentException(
                        "应用数据库地址未指向受管MySQL服务的容器名称或网络别名，不能验证隔离后的连接；请先核对部署配置");
            String identity;
            if (stopped) identity = compose.stoppedMigrationDatabaseIdentityFile(i, secrets);
            else {
                String id = compose.containerId(i, secrets, text(i, "databaseService", ""));
                identity = host.capture(i, secrets, "docker exec " + quote(id) + " cat /var/lib/mysql/auto.cnf");
            }
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
        verifyRestoreDeployment(draft, actual);
        var state = database.observe(actual, secrets);
        if (text(draft, "databaseIdentity", "").isBlank()
                || !text(draft, "databaseIdentity", "").equals(state.identity()))
            throw new IllegalArgumentException("恢复目标的实际MySQL服务身份已变化，请重新核对目标，不能沿用原任务保护恢复");
        actual.put("databaseIdentity", state.identity());
        actual.put("metadataDatabasePresent", state.present());
        verifyManagedDatabaseIdentity(actual, secrets);
        return new Inspection(actual, secrets);
    }

    public void validateRestoreTarget(Map<String, Object> target, Inspection actual) {
        verifyRestoreDeployment(target, actual.instance());
        if (!Objects.equals(target.get("version"), actual.instance().get("version"))
                || !Objects.equals(target.get("databaseIdentity"), actual.instance().get("databaseIdentity")))
            throw new IllegalArgumentException("恢复目标实际版本或数据库身份已变化，请验证并保存连接后重试。");
    }

    private void verifyRestoreDeployment(Map<String, Object> expected, Map<String, Object> observed) {
        for (String field : List.of("hostIdentity", "path", "composeProject", "applicationService", "databaseService"))
            if (!Objects.equals(expected.get(field), observed.get(field)))
                throw new IllegalArgumentException("恢复目标的 Docker 主机或部署已变化，请验证并保存连接后重试。");
    }

    public Map<String, Object> backupRecoveryResources(Map<String, Object> target,
            Map<String, String> credentials, Consumer<String> log) throws Exception {
        Inspection actual = inspectRecoveryTarget(target, credentials);
        return backups.create(actual.instance(), actual.credentials(), "资源备份", false, false,
                redact(actual.credentials(), log));
    }

    public void createMissingProtectionDatabase(Map<String, Object> target,
            Map<String, String> credentials, Map<String, Object> backup) throws Exception {
        backups.validateRestore(backup, target, credentials);
        if (!Boolean.TRUE.equals(target.get("protectionRecovery"))
                || !Boolean.FALSE.equals(target.get("metadataDatabasePresent")))
            throw new IllegalArgumentException("只能从原失败任务的保护备份重建已确认缺失的元数据库");
        var before = database.observe(target, credentials);
        if (before.present() || !before.identity().equals(text(backup, "databaseIdentity", "")))
            throw new IllegalArgumentException("元数据库状态或实际服务身份已变化，未执行重建");
        validateDatabaseRebuildPrivileges(target, credentials);
        try (Connection connection = database.connectServer(target, credentials);
                Statement query = connection.createStatement()) {
            query.setQueryTimeout(15);
            query.execute("CREATE DATABASE " + DatabaseArchive.identifier(text(target, "dbName", ""))
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        var after = database.observe(target, credentials);
        if (!after.present() || !after.identity().equals(before.identity()))
            throw new IllegalStateException("元数据库重建未通过实际身份复核");
        target.put("metadataDatabasePresent", true);
    }

    public void validateDatabaseRebuildPrivileges(Map<String, Object> target,
            Map<String, String> credentials) throws Exception {
        try (Connection connection = database.connectServer(target, credentials)) {
            DatabaseRebuildPrivileges.require(connection, text(target, "dbName", ""));
        }
    }

    public interface CheckProgress {
        CheckProgress NONE = new CheckProgress() {};
        default void start(String title) {}
        default void result(Map<String, Object> check) {}
    }

    public List<Map<String, Object>> health(Map<String, Object> i, Map<String, String> c)
            throws Exception {
        return health(i, c, CheckProgress.NONE);
    }

    public List<Map<String, Object>> health(Map<String, Object> i, Map<String, String> c,
            CheckProgress progress) throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>();
        Consumer<Map<String, Object>> add = check -> { checks.add(check); progress.result(check); };
        progress.start("应用与容器状态");
        try {
            Map<String, Object> actual = compose.inspect(i, new LinkedHashMap<>(c));
            i.put("status", actual.get("status"));
            add.accept(
                    check(
                            "application",
                            "应用与容器状态",
                            text(actual, "status", "").equals("运行中"),
                            text(actual, "applicationService", "") + " · " + actual.get("status")));
            add.accept(
                    check(
                            "version",
                            "实际版本与架构",
                            Objects.equals(actual.get("version"), i.get("version"))
                                    && Objects.equals(actual.get("arch"), i.get("arch")),
                            actual.get("version") + " / " + actual.get("arch")));
        } catch (Exception e) {
            i.put("status", "待检查");
            add.accept(check("application", "应用与容器状态", false, safe(e)));
        }
        progress.start("元数据库连接");
        try (Connection connection = database.connect(i, c);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            add.accept(
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
            add.accept(check("database", "元数据库连接", false, safe(e)));
        }
        progress.start("磁盘与资源目录");
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
            String[] columns = result.trim().split("\\s+");
            long availableKiB = columns.length >= 4 ? Long.parseLong(columns[3]) : -1;
            add.accept(check("resources", "磁盘与资源目录", availableKiB >= 0,
                    availableKiB < 0 ? "无法核对资源目录和磁盘余量。"
                            : String.format(Locale.ROOT, "资源目录可读，磁盘可用 %.1f GiB。", availableKiB / 1048576.0)));
        } catch (Exception e) {
            add.accept(check("resources", "磁盘与资源目录", false, safe(e)));
        }
        progress.start("DataEase 公共接口");
        try {
            try (var transport = new ContainerApplicationHttp(host, compose, i, c)) {
                var response = transport.request("/de2api/dekey", "", null, null);
                int code = response.status();
                add.accept(
                        check("entry", "DataEase公共接口", code == 200 && validPublicKey(response.body()),
                                "实际DataEase公钥接口HTTP状态：" + code));
            }
        } catch (Exception unavailable) {
            add.accept(check("entry", "DataEase公共接口", false, "容器内应用入口不可用，请核对启动、端口与部署上下文"));
        }
        add.accept(
                check(
                        "isolation",
                        "隔离编排支持",
                        isolationSupported(i),
                        isolationSupported(i)
                                ? ComposeManager.isManagedEnterprise(i)
                                        ? "企业版单机 Compose，可隔离应用和配套服务，关闭 APISIX 外部入口"
                                        : "单应用与MySQL Compose，可使用internal网络与本机端口"
                                : "此环境尚无可验证隔离编排，不能自动执行变更"));
        return checks;
    }

    public Map<String, Object> migrationExcelHealth(Map<String, Object> target, Map<String, String> credentials) {
        String title = "Excel / CSV 引擎查询";
        try {
            int tables = verifyMigrationExcelQueries(target, credentials);
            return check("migration-excel", title, true, tables == 0 ? "没有本地 Excel / CSV 工作表。"
                    : "V3 应用已通过数据引擎读取 " + tables + " 张工作表。");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return check("migration-excel", title, false, "V3 应用无法查询已导入的工作表，或查询结果与引擎表不一致。请核对 V3 数据引擎后重新验收。");
        }
    }

    private int verifyMigrationExcelQueries(Map<String, Object> target, Map<String, String> credentials)
            throws Exception {
        var engine = new SharedEngineConfiguration(json, database);
        var sampleSizes = engine.excelSampleSizes(target, credentials);
        if (sampleSizes.isEmpty()) return 0;
        if (!engine.verify(target, credentials))
            throw new IllegalStateException("V3 数据引擎未指向已核对的目标元数据库。");
        List<String> tables = new ArrayList<>(sampleSizes.keySet());
        var route = SharedEngineConfiguration.snapshotRoute(credentials);
        try (var transport = new ContainerApplicationHttp(host, compose, target, credentials)) {
            for (int start = 0; start < tables.size(); start += 32) {
                List<String> batch = tables.subList(start, Math.min(start + 32, tables.size()));
                var response = transport.control(json.writeValueAsString(
                        Map.of("operation", "verifyExcel", "tables", batch, "route", route, "repairMissingPool", start == 0)));
                var values = json.readTree(response.body()).path("nonempty");
                if (response.status() != 200 || !values.isArray() || values.size() != batch.size())
                    throw new IllegalStateException("V3 应用的 Excel 引擎查询未通过。");
                for (int n = 0; n < batch.size(); n++)
                    if (!values.get(n).isBoolean() || values.get(n).asBoolean() != (sampleSizes.get(batch.get(n)) > 0))
                        throw new IllegalStateException("V3 应用的 Excel 查询结果与引擎表不一致。");
            }
        }
        return tables.size();
    }

    public Optional<Boolean> backupPluginRegistry(Map<String, Object> record) throws Exception {
        return backups.pluginRegistryPresent(record);
    }

    public List<Map<String, Object>> acceptanceHealth(Map<String, Object> i, Map<String, String> c,
            boolean pluginRegistryExpected, CheckProgress progress)
            throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>(health(i, c, progress));
        Consumer<Map<String, Object>> add = check -> { checks.add(check); progress.result(check); };
        if (ComposeManager.isManagedEnterprise(i)) {
            progress.start("企业版配套服务");
            try {
                compose.verifyEnterprise(i, c, false);
                add.accept(check("enterprise-services", "企业版配套服务", true,
                        "etcd、截图服务、同步执行器和 APISIX 首页均已响应。"));
            } catch (Exception error) {
                add.accept(check("enterprise-services", "企业版配套服务", false, safe(error)));
            }
        }
        progress.start("实际网络与访问隔离");
        try {
            compose.verifyIsolation(i, c);
            add.accept(check("runtime-isolation", "实际网络与访问隔离", true,
                    ComposeManager.isManagedEnterprise(i) ? "已核对企业版内部网络，应用及 APISIX 均未发布端口"
                            : "已复核指定internal网络及本机端口绑定策略；浏览器入口需单独核对"));
        } catch (Exception unavailable) {
            add.accept(check("runtime-isolation", "实际网络与访问隔离", false, "当前网络或端口已偏离隔离配置，禁止完成验收"));
        }
        progress.start("数据库自动任务暂停");
        try {
            validateDatabaseAutomation(i, c);
            add.accept(check("database-automation", "数据库自动任务暂停", true, "未发现启用的MySQL事件任务"));
        } catch (Exception unavailable) {
            add.accept(check("database-automation", "数据库自动任务暂停", false, "MySQL事件任务仍启用或无法核验，请由DBA处理后重新检查"));
        }
        progress.start("自动任务强制暂停");
        try {
            compose.verifyGuard(i, c);
            add.accept(check("scheduler-standby", "自动任务强制暂停", true, "已从应用进程核验Quartz待机且无正在执行的任务"));
        } catch (Exception unavailable) {
            add.accept(check("scheduler-standby", "自动任务强制暂停", false, "无法核验应用内调度隔离组件，禁止开放访问"));
        }
        progress.start("插件登记与资源文件");
        try (Connection connection = database.connect(i, c);
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
            var pluginCheck = new LinkedHashMap<>(check("plugins", "插件登记与资源文件", true,
                    present ? "已核对" + count + "个登记插件文件可读；授权和实际业务使用需管理员核对"
                            : NO_PLUGIN_REGISTRY_DETAIL));
            pluginCheck.put("registryPresent", present);
            add.accept(pluginCheck);
        } catch (Exception e) {
            add.accept(check("plugins", "插件登记与资源文件", false, "插件登记表、文件或读取权限未通过，请修复后重新验收"));
        }
        progress.start("元数据库结构");
        try (Connection connection = database.connect(i, c);
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery("SELECT 1 FROM core_datasource LIMIT 1")) {
                add.accept(check("metadata-schema", "元数据库结构", true, "已核对数据源元数据表可读。"));
            }
        } catch (Exception unavailable) {
            add.accept(check("metadata-schema", "元数据库结构", false, "无法读取数据源元数据表，请检查初始化或迁移日志。"));
        }
        return checks;
    }

    public Map<String, Object> inspectHost(Map<String, Object> config, Map<String, String> credentials)
            throws Exception {
        String[] system = host.capture(config, credentials, """
                uname -s && uname -m
                if test -s /etc/machine-id; then sha256sum /etc/machine-id | cut -d' ' -f1; fi
                """).split("\\R");
        if (system.length == 0 || !"Linux".equals(system[0]))
            throw new IllegalArgumentException("已连接到服务器。当前实例管理支持 Linux 服务器。");
        if (system.length != 3 || !system[2].matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("无法识别服务器身份，请检查 /etc/machine-id 是否存在且可读。");
        String arch = switch (system[1]) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "ARM64";
            default -> throw new IllegalArgumentException("目标服务器架构暂不支持。");
        };
        return Map.of("arch", arch, "machineIdentity", system[2]);
    }

    public String hostIdentity(Map<String, Object> config, Map<String, String> credentials)
            throws Exception {
        String identity = host.capture(config, credentials,
                "if ! command -v docker >/dev/null 2>&1; then printf 'docker-missing'; "
                        + "elif ! docker info --format '{{.ID}}' 2>/dev/null; then printf 'docker-unavailable'; fi");
        if (identity.equals("docker-missing"))
            throw new IllegalArgumentException("SSH 已连通，但目标环境找不到 docker 命令。请在目标服务器安装 Docker Engine 和 Docker Compose。");
        if (identity.isBlank() || identity.equals("docker-unavailable"))
            throw new IllegalArgumentException("找到了 docker 命令，但无法访问 Docker 服务。请检查服务是否启动，以及当前连接账号的 Docker 权限。");
        return identity;
    }

    public void precheckTarget(
            Map<String, Object> config, Map<String, String> creds, Map<String, Object> pkg,
            Consumer<String> starting, Consumer<Map<String, Object>> result) throws Exception {
        String target = path(text(config, "path", ""));
        starting.accept("Docker 与 Compose");
        OfflineDockerRuntime.Plan runtime = new OfflineDockerRuntime(host)
                .probe(config, creds, pkg);
        requireSeparateDockerPaths(config, creds, !runtime.bootstrap());
        config.put("hostIdentity", runtime.identity());
        config.put("runtimeBootstrap", runtime.bootstrap());
        try {
            result.accept(check(runtime.bootstrap() ? "runtime-bootstrap" : "runtime", "Docker 与 Compose", true,
                    runtime.description()));
            result.accept(
                    check(
                            "target-architecture",
                            "目标服务器架构",
                            runtime.arch().equals(config.get("arch")),
                            "实际架构：" + runtime.arch()));
            if (ComposeManager.isVendorEnterprise(config) && !runtime.bootstrap())
                checkVendorCompose(config, creds);
            if (ComposeManager.isVendorEnterprise(config)) {
                starting.accept("dectl 命令安装位置");
                boolean sameHost = config.containsKey("sameHostSourceProject");
                if (!sameHost) compose.checkVendorDectlDestination(config, creds);
                result.accept(check("target-dectl", "dectl 命令安装位置", true,
                        sameHost ? "V3 使用独立目录内的 bin/dectl。" : "可安装主机侧运维命令。"));
            }
            boolean sameHost = config.containsKey("sameHostSourceProject");
            if (sameHost) requireSeparateMigrationDirectories(config, creds);
            starting.accept("已有 DataEase 部署");
            boolean other = !runtime.bootstrap() && hasDataEase(config, creds);
            result.accept(
                    check(
                            "single-host",
                            "已有DataEase部署",
                            !other,
                            other ? "该服务器已有 DataEase 容器，请接入已有环境"
                                    : sameHost ? "仅发现所选 V2 的 Compose 部署。"
                                    : runtime.mode() == OfflineDockerRuntime.Mode.REPAIR
                                            ? "补回 Docker 命令文件后会核对已有容器，发现 DataEase 实例时停止新安装。"
                                            : "未发现已有 DataEase 应用容器。"));
            starting.accept("独立安装目录");
            String exists =
                    host.capture(
                            config,
                            creds,
                            "if [ -L " + quote(target) + " ]; then printf occupied; elif [ -e "
                                    + quote(target)
                                    + " ]; then if [ -d "
                                    + quote(target)
                                    + " ] && [ -z \"$(ls -A "
                                    + quote(target)
                                    + ")\" ]; then printf empty; else printf occupied; fi; else"
                                    + " printf absent; fi");
            result.accept(
                    check(
                            "target-directory",
                            "独立安装目录",
                            !exists.equals("occupied"),
                            exists.equals("occupied") ? "目标目录已有内容，请接入已有环境或选择空目录" : "安装目录可用于新实例"));
            starting.accept("目录权限与可用空间");
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
            long required = 10L * 1024 * 1024;
            result.accept(check("target-disk", "目录权限与可用空间", available >= required,
                    available < 0 ? "无法读取目标安装目录的可用空间。"
                            : String.format(Locale.ROOT, "目标安装目录可用 %.1f GiB，至少需要 10 GiB。",
                                    available / (1024.0 * 1024))));
            starting.accept("目标端口");
            if ("Linux".equals(host.capture(config, creds, "uname -s"))) {
                List<Integer> requested = new ArrayList<>();
                requested.add(Integer.parseInt(text(config, "port", "8100")));
                if (!ComposeManager.isVendorEnterprise(config))
                    requested.add(Integer.parseInt(text(config, "dbPort", "3306")));
                Set<Integer> ports = new LinkedHashSet<>(requested);
                for (int port : ports) {
                    starting.accept("目标端口 " + port);
                    if (port < 1 || port > 65535) throw new IllegalArgumentException("端口须为1–65535");
                    String listeners = host.capture(config, creds, "ss -H -ltn 'sport = :" + port + "'");
                    result.accept(check("target-port", "目标端口 " + port, listeners.isBlank(),
                            listeners.isBlank() ? "端口可用" : "端口已被使用，请调整目标配置。"));
                }
                if (ports.size() != requested.size())
                    result.accept(check("target-port", "目标端口冲突", false,
                            "所选端口与其他部署端口重复，请调整服务端口。"));
            }
        } catch (Exception e) {
            result.accept(check("target-runtime", "服务器与运行条件", false, safe(e)));
        }
    }

    private boolean hasDataEase(Map<String, Object> config, Map<String, String> creds) throws Exception {
        String containers = host.capture(config, creds,
                "docker ps -a --format '{{.Image}}|{{.Label \"com.docker.compose.project\"}}|{{.Label \"com.docker.compose.project.working_dir\"}}'");
        for (String line : containers.lines().toList()) {
            String[] fields = line.split("\\|", -1);
            String image = fields[0].substring(fields[0].lastIndexOf('/') + 1).split("[:@]", 2)[0];
            if (!(image.equals("dataease") || image.startsWith("dataease-") && !image.equals("dataease-migration")))
                continue;
            if (fields.length == 3 && fields[1].equals(config.get("sameHostSourceProject"))
                    && fields[2].equals(config.get("sameHostSourcePath"))) continue;
            return true;
        }
        return false;
    }

    private void checkVendorCompose(Map<String, Object> config, Map<String, String> creds) throws Exception {
        String sample = """
                services:
                  probe:
                    image: deops-probe
                    ports: !reset []
                    networks: !override {default: {}}
                """;
        String result = host.capture(config, creds, "printf '%s' " + quote(sample)
                + " | " + COMPOSE + " -f - config --quiet >/dev/null 2>&1 && printf yes || printf no");
        if (!result.equals("yes"))
            throw new IllegalArgumentException("目标 Compose 无法解析企业版验收隔离覆盖，请更新 Docker Compose。");
    }

    public boolean isolationSupported(Map<String, Object> instance) {
        return compose.isolationSupported(instance);
    }

    public void validateBuiltinDatabaseAddress(
            Map<String, Object> config, Map<String, String> credentials) throws Exception {
        if (ComposeManager.isVendorEnterprise(config)) {
            if (!"ssh".equals(text(config, "connection", "")))
                throw new IllegalArgumentException("原厂企业版内置 MySQL 通过 SSH 访问，请使用远程服务器 · SSH。");
            return;
        }
        if ("local".equals(config.get("connection"))) return;
        var address = java.net.InetAddress.getByName(text(config, "dbHost", ""));
        if (address.isLoopbackAddress())
            throw new IllegalArgumentException("内置 MySQL 需要运维中心可直连的目标服务器地址。");
        String assigned = host.capture(config, credentials,
                "if command -v ip >/dev/null 2>&1; then ip -o addr show to "
                        + quote(address.getHostAddress()) + "; else printf unknown; fi");
        if (assigned.isBlank())
            throw new IllegalArgumentException("目标连接地址未配置在服务器网卡上，内置 MySQL 无法绑定。请使用运维中心可直连的服务器网卡地址。");
    }

    public void precheckPlugin(
            Map<String, Object> plugin,
            Map<String, Object> instance,
            Map<String, String> credentials,
            Map<String, String> operationCredentials,
            String prefix)
            throws Exception {
        new PluginInstaller(host, compose, media, json, database)
                .precheck(plugin, instance, credentials, operationCredentials, prefix);
    }

    public Map<String, Object> installPlugin(
            Map<String, Object> plugin,
            Map<String, Object> instance,
            Map<String, String> credentials,
            Map<String, String> operationCredentials,
            String prefix,
            Runnable beforeWrite,
            Consumer<String> log)
            throws Exception {
        return new PluginInstaller(host, compose, media, json, database)
                .install(plugin, instance, credentials, operationCredentials, prefix, beforeWrite, log);
    }

    public void validateRollbackPackage(
            Map<String, Object> packageRecord,
            Map<String, Object> backup,
            Map<String, Object> target)
            throws Exception {
        requireRollbackPackage(media.verifiedPackage(packageRecord), backup, target);
    }

    public String validateUpgradeSpace(Map<String, Object> packageRecord, Map<String, Object> target,
            Map<String, String> credentials) throws Exception {
        var pkg = media.verifiedPackage(packageRecord);
        var footprint = backups.upgradeFootprint(target, credentials);
        String targetSpace;
        if (DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter"))) {
            targetSpace = new OfflineComposeInstaller(host, compose, json, database)
                    .requireUpgradeSpace(pkg, target, credentials, footprint);
        } else {
            Path content = Path.of(text(pkg, "contentRoot", ""));
            long unpacked = 0;
            if (!(pkg.get("files") instanceof Map<?, ?> files) || files.isEmpty())
                throw new IllegalArgumentException("升级介质缺少已校验的文件清单。");
            for (Object file : files.keySet())
                unpacked = Math.addExact(unpacked, Files.size(MediaService.safeChild(content, String.valueOf(file))));
            long distribution = Math.addExact(unpacked, 16 * UpgradeCapacity.MIB);
            var tmp = new UpgradeCapacity.Demand("/tmp", "目标临时目录", Math.multiplyExact(distribution, 2));
            var docker = UpgradeCapacity.dockerDemand(host, target, credentials, Math.multiplyExact(distribution, 3));
            var data = new UpgradeCapacity.Demand(path(text(target, "path", "")), "目标安装目录", distribution);
            var mysql = new UpgradeCapacity.Demand(footprint.databaseDirectory(), "目标 MySQL 数据盘", footprint.database());
            var importPhase = new ArrayList<>(docker);
            importPhase.addAll(List.of(tmp, data));
            var rollback = new ArrayList<>(docker);
            rollback.addAll(List.of(data, mysql, new UpgradeCapacity.Demand("/tmp", "目标临时目录",
                    Math.multiplyExact(footprint.resources(), 2))));
            targetSpace = UpgradeCapacity.requireSpace(host, target, credentials, footprint.repository(), footprint.repositoryBytes(),
                    List.of(new UpgradeCapacity.Demand("/tmp", "目标临时目录", footprint.resources())),
                    importPhase, rollback);
            targetSpace += "；" + UpgradeCapacity.requireLocal(Path.of(System.getProperty("java.io.tmpdir")),
                    "运维中心分发临时目录", distribution);
        }
        return targetSpace + "。";
    }

    public void validateVendorRelease(Map<String, Object> beforeRecord, Map<String, Object> afterRecord,
            Map<String, Object> target, Map<String, String> credentials, boolean rollback) throws Exception {
        var before = media.verifiedPackage(beforeRecord);
        var after = media.verifiedPackage(afterRecord);
        if (!text(before, "version", "").matches("v[23]\\.\\d+\\.\\d+")
                || !text(after, "version", "").matches("v[23]\\.\\d+\\.\\d+")
                || text(before, "version", "").charAt(1) != text(after, "version", "").charAt(1))
            throw new IllegalArgumentException("企业版离线升级与回退仅支持相同主版本。");
        for (var pkg : List.of(before, after)) {
            if (!DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter")) || !"enterprise".equals(pkg.get("edition"))
                    || !Objects.equals(pkg.get("arch"), target.get("arch")))
                throw new IllegalArgumentException("企业版升级与回退需要架构匹配的两份原厂完整离线包。");
            DataEaseOfflinePackage.requireManagedDeployment(pkg, "restore");
        }
        if (!Objects.equals(before.get("version"), target.get("version")))
            throw new IllegalArgumentException("当前版本与回退离线包不一致。");
        validateUninstall(target, credentials); // Confirm the platform owns this exact deployment and CLI.
        new OfflineComposeInstaller(host, compose, json, database).verifyVendorUpgrade(before, after, target, credentials, rollback);
    }

    public String validateVendorUpgradePlugins(Map<String, Object> target, Map<String, String> credentials,
            String releaseVersion) throws Exception {
        var plugins = new ArrayList<UpgradePlugin>();
        var incompatible = new ArrayList<String>();
        try (Connection connection = database.connect(target, credentials);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT name, version, require_version, jar_name FROM xpack_plugin ORDER BY name, id")) {
            statement.setQueryTimeout(10);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = Objects.toString(rows.getString(1), "").strip();
                    if (!name.matches("[\\p{L}\\p{M}\\p{N} ._+()（）-]{1,80}")) name = "未命名插件";
                    String version = Objects.toString(rows.getString(2), "").strip();
                    String label = version.matches("[vV]?\\d[0-9A-Za-z.+_-]{0,31}") ? name + " " + version : name;
                    String issue = PluginInstaller.dataEaseVersionIssue(
                            releaseVersion, Objects.toString(rows.getString(3), "").strip());
                    String jar = Objects.toString(rows.getString(4), "");
                    if (!issue.isEmpty()) incompatible.add(label + "（" + issue + "）");
                    else if (jar.length() > 255 || !jar.endsWith(".jar") || jar.contains("/") || jar.contains("\\")
                            || jar.chars().anyMatch(Character::isISOControl))
                        incompatible.add(label + "（插件文件登记无效）");
                    else plugins.add(new UpgradePlugin(label, jar));
                }
            }
        } catch (SQLException error) {
            String detail = switch (error.getErrorCode()) {
                case 1146 -> "元数据库缺少插件登记表，请核对当前实例的元数据库结构。";
                case 1044, 1045, 1142 -> "MySQL 拒绝读取插件登记，请核对实例保存的数据库账号和读取权限。";
                default -> error instanceof SQLTimeoutException
                        ? "读取插件登记超时，请检查内置 MySQL 状态后重试。"
                        : "无法读取插件登记，请先在实例管理验证数据库连接，再检查插件登记表是否可读。";
            };
            throw new SQLException(detail, error.getSQLState(), error.getErrorCode(), error);
        }
        if (!incompatible.isEmpty())
            throw new IllegalArgumentException("有 " + incompatible.size() + " 个插件无法随升级保留："
                    + summarizeUpgradePlugins(incompatible) + "。请在 DataEase 中核对插件并安装适配目标版本的插件。");
        if (plugins.isEmpty()) return "未发现已登记插件。";
        String directory = path(text(target, "path", "")) + "/data/plugin";
        String jars = plugins.stream().map(UpgradePlugin::jar).distinct().map(HostCommands::quote)
                .collect(java.util.stream.Collectors.joining(" "));
        Set<String> unreadable = new HashSet<>(host.capture(target, credentials, "cd " + quote(directory)
                + " 2>/dev/null || { printf '%s\\n' " + jars + "; exit 0; }; for jar in " + jars + "; do "
                + "if ! test -f \"$jar\" || ! test -r \"$jar\" || ! test -s \"$jar\"; "
                + "then printf '%s\\n' \"$jar\"; fi; done").lines().toList());
        if (!unreadable.isEmpty()) {
            var missing = plugins.stream().filter(plugin -> unreadable.contains(plugin.jar()))
                    .map(UpgradePlugin::label).toList();
            throw new IllegalArgumentException("以下插件文件缺失、为空或不可读："
                    + summarizeUpgradePlugins(missing) + "。请在 DataEase 中修复插件后重试。");
        }
        String labels = summarizeUpgradePlugins(plugins.stream().map(UpgradePlugin::label).toList());
        return "已核对 " + plugins.size() + " 个插件（" + labels
                + "），目标版本满足插件声明，文件可读。升级保留现有插件，请在隔离验收时验证使用这些插件的业务。";
    }

    private record UpgradePlugin(String label, String jar) {}

    private static String summarizeUpgradePlugins(List<String> labels) {
        int displayed = Math.min(labels.size(), 5);
        return String.join("、", labels.subList(0, displayed)) + (labels.size() > displayed ? "等" : "");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> recoverV2ComponentUpgrade(Map<String, Object> oldMedia, Map<String, Object> newMedia,
            Map<String, Object> backup, Map<String, Object> original, Map<String, String> credentials,
            Consumer<String> log) throws Exception {
        var before = media.verifiedPackage(oldMedia);
        var after = media.verifiedPackage(newMedia);
        requireRollbackPackage(before, backup, original);
        if (!DataEaseOfflinePackage.supportsV2ComponentUpgrade(before, after))
            throw new IllegalArgumentException("升级与回退介质与原任务不一致。");
        backups.verify(backup);
        var runtime = compose.inspect(original, new LinkedHashMap<>(credentials));
        verifyRestoreDeployment(original, runtime);
        compose.stop(runtime, credentials, log);
        var patch = new V2PatchUpgrade(host, compose, database);
        patch.restore(runtime, credentials, log);
        var restored = compose.inspect(original, new LinkedHashMap<>(credentials));
        if (!Objects.equals(original.get("version"), restored.get("version")))
            throw new IllegalStateException("旧版部署配置未成功恢复。");
        var oldImage = (Map<String, Object>) ((Map<?, ?>) before.get("images")).get("database");
        new OfflineComposeInstaller(host, compose, json, database).recreateV2Mysql(restored, credentials, oldImage);
        var observed = inspectRecoveryTarget(original, credentials);
        validateRestoreTarget(original, observed);
        backups.restore(backup, observed.instance(), observed.credentials(), log);
        service(observed.instance(), observed.credentials(), true, true, log);
        var result = inspect(observed.instance(), observed.credentials());
        validateRestoreTarget(original, result);
        patch.removeRecoveredJournal(result.instance(), result.credentials());
        log.accept("已核验旧版 DataEase、内置 MySQL、整体备份内容及隔离状态。");
        return result.instance();
    }

    private void requireRollbackPackage(
            Map<String, Object> pkg, Map<String, Object> backup, Map<String, Object> target) {
        boolean vendor = ComposeManager.isVendorEnterprise(target);
        if (ComposeManager.isManagedEnterprise(target) && !vendor || vendor != "enterprise".equals(pkg.get("edition")))
            throw new IllegalArgumentException("回退离线包的版本类型与目标部署不一致。");
        DataEaseOfflinePackage.requireManagedDeployment(pkg, "restore");
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
        Map<String, Object> pkg = media.verifiedPackage(packageRecord);
        requireRollbackPackage(pkg, backup, target);
        new OfflineComposeInstaller(host, compose, json, database)
                .install(pkg, target, credentials, true, log, InstallProgress.NONE);
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
        String sourceIdentity = text(source, "databaseIdentity", ""),
                targetIdentity = text(target, "databaseIdentity", "");
        boolean sharedDatabase = !sourceIdentity.isBlank() && !targetIdentity.isBlank()
                ? sourceIdentity.equals(targetIdentity)
                : text(source, "dbHost", "").equalsIgnoreCase(text(target, "dbHost", ""))
                        && text(source, "dbPort", "3306").equals(text(target, "dbPort", "3306"))
                        && text(source, "dbName", "").equals(text(target, "dbName", ""));
        if (sharedDatabase)
            throw new IllegalArgumentException("源与目标数据库不能相同");
    }

    public void validateMigrationEndpoints(Map<String, Object> source, Map<String, String> sc,
            Map<String, Object> target, Map<String, String> tc) throws Exception {
        for (var endpoint : List.of(Map.entry(source, sc), Map.entry(target, tc))) {
            Inspection actual = inspect(endpoint.getKey(), new LinkedHashMap<>(endpoint.getValue()));
            validateMigrationIdentity(endpoint.getKey(), actual);
            for (String field : List.of("runtimeDbUrl", "runtimeDbUser", "runtimeDbPassword"))
                if (!Objects.equals(endpoint.getValue().get(field), actual.credentials().get(field)))
                    throw new IllegalArgumentException("迁移环境应用数据库连接已变化，请维护连接并重新预检查");
        }
    }

    public void validateMigrationIdentity(Map<String, Object> expected, Inspection actual) {
        for (String field : List.of("hostIdentity", "databaseIdentity", "version", "path",
                "composeProject", "applicationService", "databaseService"))
            if (!Objects.equals(expected.get(field), actual.instance().get(field)))
                throw new IllegalArgumentException("迁移环境身份或部署已变化，请维护连接并重新预检查");
    }

    public void validateMigrationSchemas(Map<String, Object> source, Map<String, String> sc,
            Map<String, Object> target, Map<String, String> tc) throws Exception {
        migration.validateDependencies(text(target, "path", ""));
        try (var sourceRoute = database.route(source, sc); var targetRoute = database.route(target, tc)) {
            migration.validateSchemas(databaseInfo(sourceRoute.instance(), sc), databaseInfo(targetRoute.instance(), tc));
        }
        backups.validateMigrationResources(source, sc, target, tc);
        try (Connection a = database.connect(source, sc);
                Connection b = database.connect(target, tc)) {
            DatabaseArchive.requireNoEnabledEvents(a);
            DatabaseArchive.requireNoEnabledEvents(b);
            DatabaseRebuildPrivileges.require(b);
        }
    }

    public int validateMigrationExcelTables(Map<String, Object> source, Map<String, String> credentials)
            throws Exception {
        return new SharedEngineConfiguration(json, database).validateExcelTables(source, credentials).size();
    }

    public void validateDatabaseAutomation(Map<String, Object> instance, Map<String, String> credentials)
            throws Exception {
        try (Connection connection = database.connect(instance, credentials)) {
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
            boolean databaseStarted = false;
            try {
                if (Boolean.TRUE.equals(i.get("migrationDatabaseStopped"))) {
                    var observed = verifyStoppedMigrationSource(i, c);
                    if (Boolean.TRUE.equals(observed.instance().get("migrationDatabaseStopped"))) {
                        compose.setMigrationDatabaseRunning(i, c, true);
                        databaseStarted = true;
                    }
                    awaitMigrationDatabase(i, c);
                }
                if (isolated) compose.start(i, c, true, log);
                else compose.startExisting(i, c, log);
            } catch (Exception failure) {
                if (databaseStarted && !uncertainCompletion(failure)) {
                    try {
                        compose.verifyStopped(i, c);
                        compose.setMigrationDatabaseRunning(i, c, false);
                    } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        } else compose.stop(i, c, log);
    }

    public Map<String, Object> backup(
            Map<String, Object> i,
            Map<String, String> c,
            String scope,
            boolean includeImages,
            Consumer<String> log)
            throws Exception {
        return backup(i, c, scope, includeImages, false, log);
    }

    public Map<String, Object> protectionBackup(Map<String, Object> i, Map<String, String> c,
            Consumer<String> log) throws Exception {
        return backup(i, c, "整体备份", false, true, log);
    }

    private Map<String, Object> backup(Map<String, Object> i, Map<String, String> c, String scope,
            boolean includeImages, boolean protection, Consumer<String> log) throws Exception {
        Inspection found = inspectBackupSource(i, c, scope);
        Map<String, Object> actual = found.instance();
        requireBackupScope(actual, scope);
        return backups.create(actual, found.credentials(), scope, includeImages, protection, redact(found.credentials(), log));
    }

    public Inspection inspectBackupSource(Map<String, Object> i, Map<String, String> c, String scope) throws Exception {
        if (!"资源备份".equals(scope)) return inspect(i, c);
        var credentials = new LinkedHashMap<>(c);
        return new Inspection(compose.inspect(i, credentials), credentials);
    }

    public void requireBackupScope(Map<String, Object> actual, String scope) {
        if ("资源备份".equals(scope)) return;
        Map<?, ?> env = actual.get("environmentConfig") instanceof Map<?, ?> e ? e : Map.of();
        if (!"MySQL".equals(actual.get("dbType")))
            throw new IllegalArgumentException("当前元数据库不是 MySQL，可先备份资源文件，数据库需使用对应的备份工具。");
        if (Set.of("cluster", "multi-node").contains(text(env, "deployment", "")))
            throw new IllegalArgumentException("当前为多节点部署，可归档所选节点的资源。整套部署需同时保护其余节点。");
        if ("external".equals(env.get("engineMode")))
            throw new IllegalArgumentException("已识别到独立数据引擎或无法证明引擎与元数据库共库。可先备份资源，另行备份该引擎。");
        if (!"shared".equals(env.get("engineMode")))
            throw new IllegalArgumentException("未能确认数据引擎与元数据库共库。可先备份资源，再通过实例详情核对数据引擎配置。");
    }

    public Map<String, Object> validateRestore(
            Map<String, Object> record, Map<String, Object> target, Map<String, String> credentials)
            throws Exception {
        return backups.validateRestore(record, target, credentials);
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
            Consumer<String> log,
            Consumer<MigrationService.Phase> progress,
            Runnable targetWriteStarted)
            throws Exception {
        validateMigrationEndpoints(source, sc, target, tc);
        validateMigrationSchemas(source, sc, target, tc);
        validateMigration(source, target);
        try (Connection a = database.connect(source, sc);
                Connection b = database.connect(target, tc)) {
            String sourceId = databaseIdentity(a), targetId = databaseIdentity(b);
            if (sourceId.equals(targetId))
                throw new IllegalArgumentException("两端实际指向同一MySQL数据库，已阻止覆盖");
        }
        compose.verifyStopped(source, sc);
        compose.verifyStopped(target, tc);
        var engine = new SharedEngineConfiguration(json, database);
        var excelTables = engine.snapshotExcelTables(source, sc);
        Consumer<String> safeLog = redact(sc, redact(tc, log));
        try (var sourceRoute = database.route(source, sc); var targetRoute = database.route(target, tc)) {
            MigrationRequest request = new MigrationRequest(
                    host.server(source, sc), databaseInfo(sourceRoute.instance(), sc),
                    host.server(target, tc), databaseInfo(targetRoute.instance(), tc), copyLogs);
            migration.execute(request, new MigrationJob(safeLog), progress, () -> {
                targetWriteStarted.run();
                safeLog.accept("开始替换目标资源；从此阶段起目标可能已发生写入。");
                for (String relative : List.of("i18n", "font", "exportData", "map", "geo", "appearance",
                        "static-resource", "plugin"))
                    host.run(target, tc,
                            "rm -rf -- " + quote(path(text(target, "path", "")) + "/data/" + relative),
                            line -> {});
            });
        }
        progress.accept(MigrationService.Phase.ENGINE);
        engine.relocate(source, sc, target, tc);
        if (!excelTables.equals(engine.snapshotExcelTables(target, tc)))
            throw new IllegalStateException("V3 的本地 Excel / CSV 工作表引用或行数与 V2 不一致。");
        safeLog.accept("已将共库引擎与内置Demo数据源重绑定至目标应用实际数据库连接，业务外部数据源保持原配置");
        if (!excelTables.references().isEmpty())
            safeLog.accept("已核对 " + excelTables.references().size() + " 个本地 Excel / CSV 数据源，"
                    + excelTables.counts().size() + " 张工作表行数与 V2 一致。");
    }

    private static final String READ_OFFLINE_PACKAGE = "读取离线包入库记录";
    private static final String PREPARE_DOCKER = "准备 Docker 与 Compose";
    private static final String DISTRIBUTE_OFFLINE_PACKAGE = "分发并核验离线包";
    private static final String EXECUTE_OFFLINE_DEPLOYMENT = "执行离线部署";

    public static List<String> installStageTitles(boolean offlineCompose, boolean upgrade) {
        var stages = new ArrayList<String>();
        stages.add(READ_OFFLINE_PACKAGE);
        if (offlineCompose) {
            if (!upgrade) stages.add(PREPARE_DOCKER);
            stages.addAll(OfflineComposeInstaller.stageTitles(upgrade));
        } else stages.addAll(List.of(DISTRIBUTE_OFFLINE_PACKAGE, EXECUTE_OFFLINE_DEPLOYMENT));
        return stages;
    }

    public interface InstallProgress {
        InstallProgress NONE = new InstallProgress() {};

        default void start(String title, int milestone) {}
        default void detail(String result) {}
        default void complete(String result) {}
    }

    public void install(
            Map<String, Object> packageRecord,
            Map<String, Object> config,
            Map<String, String> creds,
            boolean upgrade,
            Consumer<String> log)
            throws Exception {
        install(packageRecord, config, creds, upgrade, log, InstallProgress.NONE);
    }

    public void install(
            Map<String, Object> packageRecord, Map<String, Object> config, Map<String, String> creds,
            boolean upgrade, Consumer<String> log, InstallProgress progress) throws Exception {
        progress.start(READ_OFFLINE_PACKAGE, 20);
        Map<String, Object> pkg = media.verifiedPackage(packageRecord);
        if (!upgrade && "external".equals(config.get("dbMode")))
            throw new IllegalArgumentException("外部数据库暂无法通过安装后的隔离验收，请选择内置 MySQL，或完成部署后接入实例。");
        if (!"MySQL".equals(config.get("dbType")))
            throw new IllegalArgumentException("当前自动部署仅支持 MySQL。");
        if (!"已校验".equals(pkg.get("status"))
                || !Integer.valueOf(1).equals(pkg.get("operationsProtocol")))
            throw new IllegalArgumentException("此离线包未提供可执行的operationsProtocol=1非交互安装适配入口");
        String purpose =
                upgrade
                        ? "upgrade"
                        : "restore".equals(config.get("operationPurpose")) ? "restore" : "install";
        DataEaseOfflinePackage.requireManagedDeployment(pkg, purpose);
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
        progress.complete("已读取 " + pkg.get("version") + " 的入库校验结果，并核对目标架构与数据库。");
        if (DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter"))) {
            if (!upgrade) {
                progress.start(PREPARE_DOCKER, 30);
                var runtime = new OfflineDockerRuntime(host);
                var planned = runtime.probe(config, creds, pkg);
                if (!planned.identity().equals(config.get("hostIdentity"))
                        || planned.bootstrap() != Boolean.TRUE.equals(config.get("runtimeBootstrap"))
                        || !planned.arch().equals(config.get("arch")))
                    throw new IllegalStateException("目标服务器或 Docker 环境已变化，请重新预检查。");
                requireSeparateDockerPaths(config, creds, !planned.bootstrap());
                if (config.containsKey("sameHostSourceProject"))
                    requireSeparateMigrationDirectories(config, creds);
                String runtimeResult;
                if (planned.bootstrap()) {
                    runtime.install(pkg, config, creds, planned, log);
                    runtimeResult = planned.mode() == OfflineDockerRuntime.Mode.REPAIR
                            ? "已补回 Docker 与 Compose 命令文件，原 Docker 服务继续运行。"
                            : "已从离线包安装 Docker 与 Compose，Docker 服务已启动。";
                } else {
                    runtimeResult = "已复用目标服务器的 Docker 与 Compose " + planned.composeVersion() + "。";
                    log.accept(runtimeResult);
                }
                config.put("hostIdentity", hostIdentity(config, creds));
                if (hasDataEase(config, creds))
                    throw new IllegalStateException("目标服务器已存在 DataEase 容器，已停止新安装。");
                if ("enterprise".equals(pkg.get("edition"))) checkVendorCompose(config, creds);
                progress.complete(runtimeResult);
            }
            new OfflineComposeInstaller(host, compose, json, database)
                    .install(pkg, config, creds, upgrade, log, progress);
            return;
        }
        progress.start(DISTRIBUTE_OFFLINE_PACKAGE, 40);
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
            progress.complete("目标服务器上的离线包摘要与平台一致。");
            progress.start(EXECUTE_OFFLINE_DEPLOYMENT, 65);
            Map<String, Object> operationConfig = new LinkedHashMap<>();
            operationConfig.put("operation", purpose);
            operationConfig.put("instance", config);
            Map<String, String> databaseCredentials = new LinkedHashMap<>();
            for (String key : List.of("dbUser", "dbPassword"))
                if (creds.containsKey(key)) databaseCredentials.put(key, creds.get(key));
            operationConfig.put("credentials", databaseCredentials);
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
            progress.complete("实际安装版本已核对，应用保持停止，等待隔离启动。");
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
            log.accept("复核元数据库自动任务仍保持暂停。");
            validateDatabaseAutomation(i, c);
        } else {
            compose.verifyIsolation(i, c);
            compose.verifyGuard(i, c);
            log.accept("验收完成，已复核并保留当前隔离容器及自动任务待机状态");
        }
    }

    public void verifyServicesStopped(Map<String, Object> i, Map<String, String> c) throws Exception {
        compose.verifyStopped(i, c);
    }

    public List<String> runningMigrationServices(Map<String, Object> source, Map<String, String> credentials)
            throws Exception {
        return compose.runningMigrationServices(source, credentials);
    }

    public void restoreMigrationSource(Map<String, Object> source, Map<String, String> credentials,
            List<String> previouslyRunning, Consumer<String> log) throws Exception {
        if (!hostIdentity(source, credentials).equals(source.get("hostIdentity")))
            throw new IllegalStateException("V2 主机身份已变化，无法恢复源服务。");
        if (!compose.migrationDatabaseRunning(source, credentials)) {
            verifyStoppedMigrationSource(source, credentials);
            compose.setMigrationDatabaseRunning(source, credentials, true);
            awaitMigrationDatabase(source, credentials);
        }
        compose.restoreMigrationServices(source, credentials, previouslyRunning, log);
    }

    public void stopFailedMigrationTarget(Map<String, Object> target, Map<String, String> credentials,
            Consumer<String> log) throws Exception {
        var observed = compose.inspect(target, new LinkedHashMap<>(credentials));
        validateMigrationIdentity(target, new Inspection(observed, credentials));
        compose.stop(target, credentials, log);
    }

    public Inspection verifyStoppedMigrationSource(Map<String, Object> source, Map<String, String> credentials)
            throws Exception {
        Map<String, String> actualCredentials = new LinkedHashMap<>(credentials);
        var actual = compose.inspect(source, actualCredentials);
        return verifyStoppedMigrationSource(source, actual, actualCredentials);
    }

    public Inspection inspectForService(Map<String, Object> instance, Map<String, String> credentials)
            throws Exception {
        Map<String, String> actualCredentials = new LinkedHashMap<>(credentials);
        var actual = compose.inspect(instance, actualCredentials);
        boolean managed = canVerifyStoppedDatabase(instance);
        if (managed) {
            validateMigrationIdentity(instance, new Inspection(actual, actualCredentials));
            if (!compose.migrationDatabaseRunning(actual, actualCredentials))
                return verifyStoppedMigrationSource(instance, actual, actualCredentials);
            actual.put("migrationDatabaseStopped", false);
        }
        var inspected = inspectDatabase(actual, actualCredentials);
        if (managed) validateMigrationIdentity(instance, inspected);
        return inspected;
    }

    public Inspection inspectConnection(Map<String, Object> instance, Map<String, String> credentials)
            throws Exception {
        var inspected = inspectForService(instance, credentials);
        if (Boolean.TRUE.equals(inspected.instance().get("migrationDatabaseStopped")))
            throw new IllegalStateException("原 MySQL 数据身份已核对。内置 MySQL 已停止，请先启动实例，再验证并保存连接。");
        return inspected;
    }

    private static boolean canVerifyStoppedDatabase(Map<String, Object> instance) {
        return Boolean.TRUE.equals(instance.get("migrationDatabaseStopped"))
                || ComposeManager.isVendorEnterprise(instance)
                && !text(instance, "databaseService", "").isBlank()
                && !text(instance, "databaseIdentity", "").isBlank();
    }

    private Inspection verifyStoppedMigrationSource(Map<String, Object> source, Map<String, Object> actual,
            Map<String, String> actualCredentials) throws Exception {
        if (!"已停止".equals(actual.get("status")) || !ComposeManager.isManagedEnterprise(actual))
            throw new IllegalStateException("内置 MySQL 已停止，但应用或部署状态未通过核对。");
        validateMigrationIdentity(source, new Inspection(actual, actualCredentials));
        compose.verifyStopped(actual, actualCredentials);
        boolean databaseRunning = compose.migrationDatabaseRunning(actual, actualCredentials);
        verifyManagedDatabaseIdentity(actual, actualCredentials, !databaseRunning);
        actual.put("migrationDatabaseStopped", !databaseRunning);
        return new Inspection(actual, actualCredentials);
    }

    private void awaitMigrationDatabase(Map<String, Object> source, Map<String, String> credentials) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            try (Connection connection = database.connect(source, credentials)) {
                if (!Objects.equals(source.get("databaseIdentity"), databaseIdentity(connection)))
                    throw new IllegalStateException("内置 MySQL 实际身份已变化。");
                return;
            } catch (SQLException unavailable) {
                Thread.sleep(1500);
            }
        }
        throw new IllegalStateException("内置 MySQL 已启动，但数据库尚未就绪。");
    }

    public void stopMigrationSourceDatabase(Map<String, Object> source, Map<String, String> credentials)
            throws Exception {
        var actual = verifyStoppedMigrationSource(source, credentials);
        if (!Boolean.TRUE.equals(actual.instance().get("migrationDatabaseStopped")))
            validateMigrationIdentity(source, inspect(source, credentials));
        compose.setMigrationDatabaseRunning(source, credentials, false);
    }

    public void validateUninstall(Map<String, Object> i, Map<String, String> c) throws Exception {
        validateUninstall(i, c, sha256(uninstallProgram()));
    }

    private void validateUninstall(Map<String, Object> i, Map<String, String> c, String expected) throws Exception {
        String root = path(text(i, "path", "")), project = text(i, "composeProject", "");
        if (!ComposeManager.isVendorEnterprise(i) || !project.matches("[a-z0-9][a-z0-9_-]*")
                || !hostIdentity(i, c).equals(i.get("hostIdentity")))
            throw new IllegalArgumentException("只能卸载已核对身份的受管企业版单机实例。");
        requireSeparateDockerPaths(i, c, true);
        String script = root + "/bin/dectl";
        String actual = host.capture(i, c, "test ! -L " + quote(root) + " && test -d "
                + quote(root + "/.ops-install-claim") + " && test ! -L " + quote(root + "/bin")
                + " && test ! -L " + quote(root + "/.ops-install-claim")
                + " && test ! -L " + quote(root + "/.ops-runtime")
                + " && test ! -L " + quote(root + "/.ops-runtime/compose-project")
                + " && test ! -L " + quote(script)
                + " && test -x " + quote(script) + " && test \"$(cat "
                + quote(root + "/.ops-runtime/compose-project") + ")\" = " + quote(project)
                + " && sha256sum " + quote(script) + " | cut -d' ' -f1");
        boolean legacy = actual.equals("6ab24d9ca447b3f517cd4e268dc6a1d2d2f7834b5c6f70803340a2ca30379d9b")
                && !(i.get("composeFiles") instanceof List<?> files && files.contains(ComposeManager.INSTANCE_OVERRIDE));
        if (!expected.equals(actual) && !PREVIOUS_DECTL_SHA256.contains(actual) && !legacy)
            throw new IllegalStateException("目标目录或卸载程序已变化，禁止卸载。");
        var cfg = compose.config(i, c);
        if (!project.equals(cfg.get("name")) || !Boolean.TRUE.equals(cfg.get("_vendorEnterprise")))
            throw new IllegalStateException("目标 Compose 项目已变化，禁止卸载。");
        String app = compose.containerId(i, c, text(i, "applicationService", ""));
        if (!app.matches("[a-f0-9]{64}") || !(compose.container(i, c, app).get("Config") instanceof Map<?, ?> container)
                || !(container.get("Labels") instanceof Map<?, ?> labels)
                || !root.equals(labels.get("com.docker.compose.project.working_dir"))
                || !project.equals(labels.get("com.docker.compose.project")))
            throw new IllegalStateException("应用容器归属无法核对，禁止卸载。");
    }

    private static byte[] uninstallProgram() throws java.io.IOException {
        try (var resource = ExecutionService.class.getResourceAsStream("/operations/vendor-dectl.sh")) {
            if (resource == null) throw new IllegalStateException("缺少受管卸载程序。");
            return resource.readAllBytes();
        }
    }

    private static String sha256(byte[] content) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    public void uninstall(Map<String, Object> i, Map<String, String> c, Consumer<String> log) throws Exception {
        byte[] program = uninstallProgram();
        String expected = sha256(program);
        validateUninstall(i, c, expected);
        String root = path(text(i, "path", "")), project = text(i, "composeProject", "");
        String script = root + "/bin/dectl";
        String staged = root + "/bin/.dectl-" + UUID.randomUUID();
        Path local = Files.createTempFile("deops-uninstall-", ".sh");
        try {
            Files.write(local, program);
            host.run(i, c, "umask 077; set -C; : > " + quote(staged), line -> {});
            host.upload(i, c, local, staged);
            host.run(i, c, "test \"$(sha256sum " + quote(staged) + " | cut -d' ' -f1)\" = " + quote(expected)
                    + " && chmod 700 " + quote(staged) + " && mv -fT -- " + quote(staged) + " " + quote(script)
                    + " && printf '%s\\n' " + quote(project) + " | " + quote(script) + " uninstall",
                    line -> { if (line.startsWith("已清理 ")) log.accept(line); });
        } finally {
            Files.deleteIfExists(local);
        }
        host.run(i, c, "test ! -e " + quote(root) + " && test ! -L " + quote(root)
                + " && remaining=$(docker ps -aq --filter "
                + quote("label=com.docker.compose.project=" + project) + ") && test -z \"$remaining\"", line -> {});
        if (!hostIdentity(i, c).equals(i.get("hostIdentity")))
            throw new IllegalStateException("卸载后未能确认原 Docker 服务，请检查目标机。");
        host.run(i, c, COMPOSE + " version --short", line -> {});
    }

    private void requireSeparateDockerPaths(
            Map<String, Object> i, Map<String, String> c, boolean dockerAvailable) throws Exception {
        Path install = Path.of(path(text(i, "path", "")));
        String command = "cd " + quote(install.getParent().toString()) + " && pwd -P";
        if (dockerAvailable) command += " && docker info --format '{{.DockerRootDir}}'"
                + " && readlink -f -- \"$(command -v docker)\""
                + " && { if command -v docker-compose >/dev/null 2>&1; then readlink -f -- \"$(command -v docker-compose)\"; fi; }";
        var actual = host.capture(i, c, command).lines().toList();
        if (actual.isEmpty() || dockerAvailable && actual.size() < 3
                || actual.stream().anyMatch(item -> !item.startsWith("/")))
            throw new IllegalStateException("无法核对 DataEase 安装目录与 Docker 运行目录。");
        Path resolved = Path.of(actual.get(0)).resolve(install.getFileName());
        var reserved = new ArrayList<>(List.of("/opt/dataease-operations", "/etc/docker", "/etc/systemd",
                "/usr/local/bin", "/usr/bin", "/usr/local/lib/docker", "/usr/lib/docker",
                "/var/lib/docker", "/var/lib/containerd", "/run/docker", "/run/containerd"));
        reserved.addAll(actual.subList(1, actual.size()));
        if (reserved.stream().map(Path::of).anyMatch(item -> resolved.startsWith(item) || item.startsWith(resolved)))
            throw new IllegalArgumentException("DataEase 安装目录与 Docker 运行文件或数据目录重叠，请使用独立目录。");
    }

    private void requireSeparateMigrationDirectories(Map<String, Object> config, Map<String, String> credentials)
            throws Exception {
        Path target = Path.of(path(text(config, "path", "")));
        String sourcePath = path(text(config, "sameHostSourcePath", ""));
        String source = host.capture(config, credentials, "cd " + quote(sourcePath) + " && pwd -P");
        String parent = host.capture(config, credentials, "cd " + quote(target.getParent().toString()) + " && pwd -P");
        Path realSource = Path.of(path(source));
        Path realTarget = Path.of(path(parent)).resolve(target.getFileName());
        if (realSource.startsWith(realTarget) || realTarget.startsWith(realSource))
            throw new IllegalArgumentException("新 V3 安装目录与 V2 安装目录重叠，请使用独立目录。");
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
        String value = publicKeyEnvelope(json, response);
        return value.length() > 100 && value.matches("[A-Za-z0-9+/=_-]+");
    }

    private static String publicKeyEnvelope(ObjectMapper json, byte[] response) {
        if (response.length > 8192) return "";
        String value = new String(response, java.nio.charset.StandardCharsets.UTF_8).trim();
        try {
            var body = json.readTree(response);
            if (body == null) return "";
            if (body.isTextual()) value = body.asText();
            else if (body.isObject() && Set.of(0, 200).contains(body.path("code").asInt(-1))
                    && body.path("data").isTextual()) value = body.path("data").asText();
            else return "";
        } catch (com.fasterxml.jackson.core.JacksonException plainKey) {
            // The native String endpoint may omit a JSON envelope.
        } catch (java.io.IOException unavailable) { return ""; }
        return value;
    }

    public void initializeAdmin(Map<String, Object> i, Map<String, String> c, String password)
            throws Exception {
        compose.verifyIsolation(i, c);
        try (Connection connection = database.connect(i, c)) {
            boolean databaseAccounts;
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT 1 FROM information_schema.tables "
                            + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='per_user' AND TABLE_TYPE='BASE TABLE'")) {
                databaseAccounts = rows.next();
            }
            if (databaseAccounts || ComposeManager.isVendorEnterprise(i)) {
                Map<String, Object> actual = new LinkedHashMap<>(i);
                actual.put("databaseIdentity", databaseIdentity(connection));
                verifyManagedDatabaseIdentity(actual, c);
                try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE per_user SET pwd=?,pwd_update_time=? WHERE id=1 AND BINARY account='admin' "
                                    + "AND enable=1 AND origin=0 AND pwd REGEXP '^[0-9a-f]{32}$'")) {
                    String digest = adminPasswordDigest(password);
                    update.setString(1, digest);
                    update.setLong(2, System.currentTimeMillis());
                    if (update.executeUpdate() != 1)
                        throw new IllegalStateException("无法确认数据库中的本地 admin 账号，未完成初始密码设置。");
                }
            }
        } catch (SQLException unavailable) {
            throw new IllegalStateException("DataEase admin 账号初始化失败，请核对元数据库。", unavailable);
        }
        try {
            verifyAdminLogin(i, c, password);
        } catch (Exception unavailable) {
            throw new IllegalStateException("未能通过 DataEase 登录接口核实初始密码，实例仍保持隔离。", unavailable);
        }
    }

    public void verifyAdminLogin(Map<String, Object> i, Map<String, String> c, String password) throws Exception {
        try (var transport = new ContainerApplicationHttp(host, compose, i, c)) {
            adminSession(transport, json, "/de2api", password);
        }
    }

    public String verifySavedAdminLogin(Map<String, Object> expected, Map<String, String> credentials,
            Map<String, String> candidates, boolean isolated) throws Exception {
        var secrets = new LinkedHashMap<>(credentials);
        var deployment = adminDeployment(expected, secrets, isolated);
        var actual = deployment.instance();
        String reference;
        try (Connection connection = database.connect(actual, secrets)) {
            verifyAdminDatabase(expected, actual, secrets, connection);
            if (ComposeManager.isManagedEnterprise(actual)) {
                reference = matchingAdminReference(candidates, localAdmin(connection).digest());
            } else {
                // Community editions can keep the admin password in application configuration.
                reference = candidates.size() == 1 ? candidates.keySet().iterator().next() : "";
            }
        }
        if (reference.isBlank()) throw new IllegalArgumentException("已保存的密码与当前账号无法匹配。");
        try (var transport = new ContainerApplicationHttp(host, actual, secrets,
                deployment.applicationContainer(), deployment.composeConfig())) {
            adminSession(transport, json, "/de2api", candidates.get(reference));
        }
        return reference;
    }

    public void resetInstanceAdmin(Map<String, Object> expected, Map<String, String> credentials,
            Map<String, String> candidates, String generated, boolean isolated,
            Consumer<String> beforeWrite) throws Exception {
        var secrets = new LinkedHashMap<>(credentials);
        var deployment = adminDeployment(expected, secrets, isolated);
        var actual = deployment.instance();
        if (!ComposeManager.isManagedEnterprise(actual))
            throw OperationsApiException.badRequest("此部署使用社区版登录配置，请在 DataEase 中维护管理员密码。");
        try (Connection connection = database.connect(actual, secrets);
                var transport = new ContainerApplicationHttp(host, actual, secrets,
                        deployment.applicationContainer(), deployment.composeConfig())) {
            verifyAdminDatabase(expected, actual, secrets, connection);
            var previous = localAdmin(connection);
            var next = new AdminAccount(adminPasswordDigest(generated), System.currentTimeMillis());
            beforeWrite.accept(matchingAdminReference(candidates, previous.digest()));
            try {
                if (!replaceAdminAccount(connection, previous, next))
                    throw OperationsApiException.conflict("管理员账号已变化，请重新打开实例后重试。");
            } catch (SQLException uncertain) {
                throw OperationsApiException.conflict("暂时无法确认密码是否写入。请先尝试查看已验证的密码。");
            }
            try {
                adminSession(transport, json, "/de2api", generated);
            } catch (Exception unverified) {
                if (unverified instanceof InterruptedException) Thread.currentThread().interrupt();
                try {
                    if (replaceAdminAccount(connection, next, previous))
                        throw OperationsApiException.conflict("新密码未通过登录验证，已恢复原密码。");
                } catch (SQLException rollback) {
                    unverified.addSuppressed(rollback);
                }
                throw OperationsApiException.conflict("新密码未通过登录验证，暂时无法确认账号状态。请重新核对实例。");
            }
        }
    }

    private ComposeManager.DeploymentInspection adminDeployment(Map<String, Object> expected,
            Map<String, String> credentials, boolean isolated) throws Exception {
        var deployment = compose.inspectDeployment(expected, credentials);
        var actual = deployment.instance();
        if (!"运行中".equals(actual.get("status")) || !"MySQL".equals(actual.get("dbType"))
                || text(actual, "databaseService", "").isBlank() || !compose.isolationSupported(actual))
            throw OperationsApiException.badRequest("请确认 DataEase 和受管 MySQL 已运行，再验证管理员账号。");
        if (isolated) compose.verifyIsolation(actual, credentials);
        return deployment;
    }

    private void verifyAdminDatabase(Map<String, Object> expected, Map<String, Object> actual,
            Map<String, String> credentials, Connection connection) throws Exception {
        actual.put("databaseIdentity", databaseIdentity(connection));
        verifyManagedDatabaseIdentity(actual, credentials);
        for (String field : List.of("hostIdentity", "databaseIdentity", "version", "path",
                "composeProject", "applicationService", "databaseService"))
            if (text(expected, field, "").isBlank() || !Objects.equals(expected.get(field), actual.get(field)))
                throw OperationsApiException.conflict("实例或元数据库身份已变化，请验证并保存连接。");
    }

    private record AdminAccount(String digest, long updatedAt) {}

    private static final String LOCAL_ADMIN = "id=1 AND BINARY account='admin' AND enable=1 AND origin=0";

    private static AdminAccount localAdmin(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT pwd,pwd_update_time FROM per_user WHERE " + LOCAL_ADMIN)) {
            query.setQueryTimeout(10);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    String digest = rows.getString(1);
                    long time = rows.getLong(2);
                    if (!rows.wasNull() && digest != null && digest.matches("[0-9a-f]{32}") && !rows.next())
                        return new AdminAccount(digest, time);
                }
            }
        }
        throw OperationsApiException.badRequest("无法确认受支持且已启用的 DataEase 本地 admin 账号。");
    }

    private static boolean replaceAdminAccount(Connection connection, AdminAccount expected, AdminAccount next)
            throws SQLException {
        try (var update = connection.prepareStatement("UPDATE per_user SET pwd=?,pwd_update_time=? WHERE "
                + LOCAL_ADMIN + " AND BINARY pwd=? AND pwd_update_time=?")) {
            update.setQueryTimeout(10);
            update.setString(1, next.digest());
            update.setLong(2, next.updatedAt());
            update.setString(3, expected.digest());
            update.setLong(4, expected.updatedAt());
            return update.executeUpdate() == 1;
        }
    }

    private static String matchingAdminReference(Map<String, String> candidates, String digest) throws Exception {
        for (var entry : candidates.entrySet())
            if (adminPasswordDigest(entry.getValue()).equals(digest)) return entry.getKey();
        return "";
    }

    private static String adminPasswordDigest(String password) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(password.getBytes(StandardCharsets.UTF_8)));
    }

    static String adminSession(ContainerApplicationHttp transport, ObjectMapper json, String prefix,
            String password) throws Exception {
        var key = transport.request(prefix + "/dekey", "", null, null);
        if (key.status() != 200) throw new IllegalStateException();
        String separator = Base64.getUrlEncoder().encodeToString("-pk_separator-".getBytes(StandardCharsets.UTF_8));
        String envelope = publicKeyEnvelope(json, key.body());
        int boundary = envelope.lastIndexOf(separator);
        if (boundary < 1) throw new IllegalStateException();
        byte[] aesKey = envelope.substring(boundary + separator.length()).getBytes(StandardCharsets.UTF_8);
        var aes = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                new javax.crypto.spec.IvParameterSpec(Arrays.copyOf(
                        java.security.MessageDigest.getInstance("SHA-256").digest(aesKey), 16)));
        String publicKey = new String(aes.doFinal(Base64.getDecoder().decode(envelope.substring(0, boundary))),
                StandardCharsets.UTF_8).replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s+", "");
        var rsa = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(javax.crypto.Cipher.ENCRYPT_MODE, java.security.KeyFactory.getInstance("RSA").generatePublic(
                new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
        var response = transport.login(prefix, json.writeValueAsString(Map.of(
                "name", Base64.getEncoder().encodeToString(rsa.doFinal("admin".getBytes(StandardCharsets.UTF_8))),
                "pwd", Base64.getEncoder().encodeToString(rsa.doFinal(password.getBytes(StandardCharsets.UTF_8))))));
        var body = json.readTree(response.body());
        if (response.status() != 200 || !Set.of(0, 200).contains(body.path("code").asInt(-1))
                || body.path("data").path("token").asText().isBlank()) throw new IllegalStateException();
        return body.path("data").path("token").asText();
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
