package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.operations.core.OperationsFiles;
import com.dataease.migration.operations.media.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Owns the deployment rather than invoking the vendor's interactive/root installer. */
final class OfflineComposeInstaller {
    private static final String PREPARE_IMAGES = "准备并核验离线镜像";
    private static final String WRITE_CONFIG = "写入部署配置和资源";

    static List<String> stageTitles(boolean upgrade) {
        return List.of(PREPARE_IMAGES, WRITE_CONFIG, verifyEnvironmentStage(upgrade));
    }

    private static String verifyEnvironmentStage(boolean upgrade) {
        return upgrade ? "核对升级环境" : "启动并核对内置 MySQL";
    }

    private final HostCommands host;
    private final ComposeManager compose;
    private final ObjectMapper json;
    private final DatabaseArchive database;

    OfflineComposeInstaller(HostCommands host, ComposeManager compose, ObjectMapper json, DatabaseArchive database) {
        this.host = host;
        this.compose = compose;
        this.json = json; this.database = database;
    }

    void verifyVendorUpgrade(Map<String, Object> current, Map<String, Object> next,
            Map<String, Object> instance, Map<String, String> credentials, boolean rollback) throws Exception {
        var previousFiles = deploymentInputs(current);
        var nextFiles = deploymentInputs(next);
        if (!previousFiles.equals(nextFiles))
            throw new IllegalArgumentException("此升级会改变原厂配置、网关文件或 JDBC 驱动。请使用原厂升级流程。");
        var live = compose.config(instance, credentials);
        if (!Boolean.TRUE.equals(live.get("_vendorEnterprise")) || !Objects.equals(live.get("name"), instance.get("composeProject")))
            throw new IllegalArgumentException("无法确认受管企业版 Compose 项目。");
        var before = vendorImages(current, instance);
        var after = vendorImages(next, instance);
        String app = text(instance, "applicationService", "");
        String architecture = text(instance, "arch", "");
        if (!(live.get("services") instanceof Map<?, ?> services) || !before.keySet().equals(services.keySet()))
            throw new IllegalArgumentException("实际 Compose 服务组成与当前版本离线包不一致。");
        for (var entry : before.entrySet()) {
            String service = entry.getKey();
            var image = entry.getValue();
            if (!(services.get(service) instanceof Map<?, ?> definition)
                    || !Objects.equals(definition.get("image"), image.get("reference"))
                    || !imageMatches(instance, credentials, image, architecture))
                throw new IllegalArgumentException("实际 " + service + " 镜像与当前版本离线包不一致，无法保证回退。");
            String container = compose.containerId(instance, credentials, service);
            Object activeImage = container.isBlank() ? null : compose.container(instance, credentials, container).get("Image");
            boolean unchangedContainer = rollback && Set.of(app, "task-actuator").contains(service)
                    && (container.isBlank() || imageIdentityMatches(after.get(service), activeImage));
            if (!imageIdentityMatches(image, activeImage) && !unchangedContainer)
                throw new IllegalArgumentException("实际 " + service + " 容器与当前版本离线包不一致，无法保证回退。");
            if (!Set.of(app, "task-actuator").contains(service)
                    && (!Objects.equals(image.get("reference"), after.get(service).get("reference"))
                        || !Objects.equals(image.get("id"), after.get(service).get("id"))))
                throw new IllegalArgumentException("升级包会替换 " + service + "，请使用原厂升级流程。");
        }
    }

    private Map<String, Object> deploymentInputs(Map<String, Object> pkg) throws Exception {
        Path root = Path.of(text(pkg, "contentRoot", ""));
        var result = new TreeMap<String, Object>();
        var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
        for (String file : VENDOR_COMPOSE_FILES) {
            Object data = yaml.load(Files.readString(MediaService.safeChild(root, "dataease/" + file)));
            var structure = json.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (file.equals("docker-compose.yml") || file.equals("docker-compose-task.yml")) {
                String name = file.equals("docker-compose.yml") ? "dataease" : "task-actuator";
                if (!(structure.get("services") instanceof Map<?, ?> services) || !(services.get(name) instanceof Map<?, ?> service))
                    throw new IllegalArgumentException("无法核对离线包的应用与同步执行器配置。");
                service.remove("image");
            }
            result.put(file, structure);
        }
        if (pkg.get("files") instanceof Map<?, ?> files) files.forEach((name, digest) -> {
            String file = String.valueOf(name);
            if (file.startsWith("dataease/templates/") && !file.equals("dataease/templates/version")
                    || file.startsWith("dataease/apisix/") || file.startsWith("dataease/bin/mysql/")
                    || file.startsWith("drivers/") || file.startsWith("dataease/drivers/"))
                result.put(file, digest);
        });
        var defaults = DataEaseOfflinePackage.vendorEnvironment(root.resolve("install.conf"));
        for (String key : List.of("DE_VERSION", "DE_TAG", "DE_IMAGE_TAG")) defaults.remove(key);
        result.put("install.conf", defaults);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> vendorImages(Map<String, Object> pkg, Map<String, Object> instance) {
        var images = (Map<String, Map<String, Object>>) pkg.get("images");
        var result = new LinkedHashMap<>((Map<String, Map<String, Object>>) pkg.get("enterpriseImages"));
        result.put(text(instance, "applicationService", ""), images.get("application"));
        result.put(text(instance, "databaseService", ""), images.get("database"));
        return result;
    }

    @SuppressWarnings("unchecked")
    String requireUpgradeSpace(Map<String, Object> pkg, Map<String, Object> instance,
            Map<String, String> credentials, BackupService.UpgradeFootprint backup) throws Exception {
        Path content = Path.of(text(pkg, "contentRoot", ""));
        var images = (Map<String, Map<String, Object>>) pkg.get("images");
        var required = new ArrayList<>(List.of(images.get("application")));
        if (ComposeManager.isVendorEnterprise(instance)) {
            var extra = (Map<String, Map<String, Object>>) pkg.get("enterpriseImages");
            required.add(extra.get("task-actuator"));
        }
        String architecture = text(instance, "arch", "");
        var archives = new LinkedHashMap<String, Object>();
        for (var image : required) {
            if (imageMatches(instance, credentials, image, architecture)) continue;
            if (ComposeManager.isVendorEnterprise(instance) && imageTagExists(instance, credentials, image))
                throw new IllegalArgumentException("服务器已有同名但摘要不同的升级镜像，请先核对镜像来源。");
            String archive = text(image, "archive", "");
            archives.putIfAbsent(archive, image.get("dockerLoadBytes"));
        }
        long transfer = 0, load = 0;
        for (var archive : archives.entrySet()) {
            long bytes = Files.size(MediaService.safeChild(content, archive.getKey()));
            transfer = Math.max(transfer, bytes);
            load = Math.addExact(load, UpgradeCapacity.dockerLoadEstimate(archive.getValue(), bytes));
        }
        String data = path(text(instance, "path", ""));
        long resources = backup.resources();
        var docker = UpgradeCapacity.dockerDemand(host, instance, credentials, load);
        var transferTmp = new UpgradeCapacity.Demand("/tmp", "目标临时目录", transfer);
        var resourceTmp = new UpgradeCapacity.Demand("/tmp", "目标临时目录", Math.multiplyExact(resources, 2));
        var deployment = new UpgradeCapacity.Demand(data, "目标安装目录", resources);
        var mysql = new UpgradeCapacity.Demand(backup.databaseDirectory(), "目标 MySQL 数据盘", backup.database());
        var importPhase = new ArrayList<>(docker);
        importPhase.add(transferTmp);
        var rollback = new ArrayList<>(docker);
        rollback.addAll(List.of(resourceTmp, deployment, mysql));
        return UpgradeCapacity.requireSpace(host, instance, credentials, backup.repository(), backup.repositoryBytes(),
                List.of(new UpgradeCapacity.Demand("/tmp", "目标临时目录", resources)),
                importPhase, rollback);
    }

    @SuppressWarnings("unchecked")
    void install(
            Map<String, Object> pkg,
            Map<String, Object> config,
            Map<String, String> credentials,
            boolean upgrade,
            Consumer<String> log,
            ExecutionService.InstallProgress progress)
            throws Exception {
        progress.start(PREPARE_IMAGES, 45);
        Path content = Path.of(text(pkg, "contentRoot", ""));
        String target = path(text(config, "path", ""));
        String version = text(pkg, "version", ""),
                generation = version.startsWith("v2.") ? "2.0" : "3.0";
        Map<String, Object> images = (Map<String, Object>) pkg.get("images");
        Map<String, Object> appImage = (Map<String, Object>) images.get("application");
        Map<String, Object> dbImage = (Map<String, Object>) images.get("database");
        Map<String, Map<String, Object>> enterprise = pkg.get("enterpriseImages") instanceof Map<?, ?> entries
                ? (Map<String, Map<String, Object>>) entries : Map.of();
        boolean vendorRelease = upgrade && ComposeManager.isVendorEnterprise(config);
        if (!upgrade) {
            if (!credentials.getOrDefault("dbUser", text(config, "dbUser", "")).equals("root"))
                throw new IllegalArgumentException("内置 MySQL 安装请使用 root 管理账号");
            if (credentials.getOrDefault("dbPassword", "").length() < 8)
                throw new IllegalArgumentException("新建 MySQL 管理密码至少8个字符");
            String database = text(config, "dbName", "");
            if (!database.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                throw new IllegalArgumentException("新数据库名称仅支持字母、数字与下划线");
        } else if (!"已停止"
                .equals(compose.inspect(config, new LinkedHashMap<>(credentials)).get("status")))
            throw new IllegalArgumentException("升级前须确认应用停止");
        String remote = "/tmp/deops-offline-" + UUID.randomUUID();
        host.run(config, credentials, "umask 077; mkdir " + quote(remote), line -> {});
        try {
            Set<String> archives = new LinkedHashSet<>();
            List<Map<String, Object>> requiredImages = new ArrayList<>(List.of(appImage));
            if (vendorRelease) requiredImages.add(enterprise.get("task-actuator"));
            else if (!upgrade) {
                requiredImages.add(dbImage);
                requiredImages.addAll(enterprise.values());
            }
            String arch = text(pkg, "arch", "");
            boolean reusedImage = false;
            for (Map<String, Object> image : requiredImages) {
                if (imageMatches(config, credentials, image, arch)) reusedImage = true;
                else {
                    if ((vendorRelease || config.containsKey("sameHostSourceProject")) && imageTagExists(config, credentials, image))
                        throw new IllegalStateException("目标服务器上的同名镜像与离线包不一致，已停止加载。");
                    archives.add(text(image, "archive", ""));
                }
            }
            if (reusedImage)
                log.accept("已复用与离线包镜像 ID 和架构一致的本地镜像");
            int imported = 0;
            for (String archive : archives) {
                Path source = MediaService.safeChild(content, archive);
                String staged = remote + "/" + UUID.randomUUID() + ".image";
                String position = "镜像归档 " + (imported + 1) + " / " + archives.size();
                long size = Files.size(source);
                progress.detail("正在复制 " + position + "。");
                host.upload(config, credentials, source, staged, transferred -> progress.detail(
                        "正在传输 " + position + "，已传 " + (transferred / 1048576)
                                + " / " + (size / 1048576) + " MiB。"));
                progress.detail("正在校验 " + position + " 的文件摘要。");
                verifyCopy(config, credentials, MediaService.packageFileDigest(pkg, archive), staged);
                progress.detail("正在将 " + position + " 导入目标 Docker。");
                host.run(
                        config,
                        credentials,
                        "docker image load --input " + quote(staged),
                        line -> {});
                host.run(config, credentials, "rm -- " + quote(staged), line -> {});
                imported++;
            }
            for (Map<String, Object> image : requiredImages)
                if (!imageMatches(config, credentials, image, arch))
                    throw new IllegalStateException("离线镜像ID或实际架构不匹配，已阻止部署");
            log.accept("已核对全部离线镜像 ID 及实际 CPU 架构");
            progress.complete("已核对 " + requiredImages.size() + " 个镜像的 ID 和架构，导入 "
                    + imported + " 个离线镜像归档。");
            progress.start(WRITE_CONFIG, 60);
            if (vendorRelease) {
                compose.verifyStopped(config, credentials);
                String release = target + "/" + ComposeManager.RELEASE_OVERRIDE;
                String staged = release + "-" + UUID.randomUUID();
                var updated = Map.of("services", Map.of("dataease", Map.of("image", appImage.get("reference")),
                        "task-actuator", Map.of("image", enterprise.get("task-actuator").get("reference"))));
                upload(config, credentials, json.writeValueAsBytes(updated), staged);
                host.run(config, credentials, "test ! -L " + quote(release) + " && mv -fT -- "
                        + quote(staged) + " " + quote(release), line -> {});
                updateVendorDectl(config, credentials, target);
            } else if (upgrade) {
                Map<String, Object> cfg = compose.config(config, credentials);
                Map<String, Object> services = (Map<String, Object>) cfg.get("services");
                Map<String, Object> app =
                        (Map<String, Object>) services.get(config.get("applicationService"));
                app.put("image", appImage.get("reference"));
                app.put("pull_policy", "never");
                cfg.remove("_composeFile");
                cfg.remove("_composeFiles");
                upload(
                        config,
                        credentials,
                        json.writeValueAsBytes(ComposeManager.literalConfig(cfg)),
                        target + "/" + config.get("composeFile"));
            } else {
                // A second mkdir claims the empty directory; concurrent/partially installed targets
                // fail closed.
                host.run(
                        config,
                        credentials,
                        "test ! -L "
                                + quote(target)
                                + " && { test ! -e "
                                + quote(target)
                                + " || { test -d "
                                + quote(target)
                                + " && test -z \"$(ls -A "
                                + quote(target)
                                + ")\"; }; } && umask 077 && mkdir -p "
                                + quote(target)
                                + " && mkdir "
                                + quote(target + "/.ops-install-claim"),
                        line -> {});
                String parent = Path.of(target).getParent().toString();
                String canonicalParent =
                        host.capture(config, credentials, "cd " + quote(parent) + " && pwd -P");
                target = canonicalParent + "/" + Path.of(target).getFileName();
                config.put("path", target);
                if (enterprise.isEmpty())
                    createDeployment(config, credentials, content, target, generation, appImage, dbImage);
                else
                    createVendorDeployment(config, credentials, content, target, generation);
                log.accept("已生成应用与数据库部署配置，尚未启动应用");
                copyResources(config, credentials, content, target, remote);
                log.accept("已复制离线包内置字体、地图与静态资源");
            }
            if (!vendorRelease) {
                copyDrivers(config, credentials, pkg, content, target, generation);
                log.accept("已核对并更新元数据库驱动");
            }
            config.put("deploymentAdapter", enterprise.isEmpty()
                    ? DataEaseOfflinePackage.ADAPTER : ComposeManager.VENDOR_ENTERPRISE_ADAPTER);
            config.put("version", version);
            config.put("applicationService", "dataease");
            progress.complete(vendorRelease ? "已更新应用和同步执行器的独立版本配置。"
                    : upgrade ? "应用镜像配置和数据库驱动已更新。"
                    : "部署配置、内置资源和数据库驱动已准备完成。");
            progress.start(verifyEnvironmentStage(upgrade), 75);
            if (!upgrade) {
                String dbService = enterprise.isEmpty() ? "mysql" : text(config, "databaseService", "");
                config.put("databaseService", dbService);
                config.put("composeFile", "docker-compose.yml");
                if (!enterprise.isEmpty()) {
                    Map<String, Object> expectedImages = new LinkedHashMap<>();
                    expectedImages.put("dataease", appImage.get("reference"));
                    expectedImages.put(dbService, dbImage.get("reference"));
                    enterprise.forEach((name, image) -> expectedImages.put(name, image.get("reference")));
                    compose.validateVendorDeployment(config, credentials, expectedImages);
                    installVendorDectl(config, credentials, target);
                }
                host.run(
                        config,
                        credentials,
                        compose.command(config) + " up -d --pull never --no-deps " + quote(dbService),
                        line -> {});
                awaitDatabase(config, credentials);
            }
            Map<String, Object> expected = new LinkedHashMap<>(config);
            expected.remove("version");
            Map<String, Object> installed =
                    compose.inspect(expected, new LinkedHashMap<>(credentials));
            if (!version.equals(installed.get("version"))
                    || !compose.isolationSupported(installed)
                    || !"已停止".equals(installed.get("status")))
                throw new IllegalStateException("安装实际版本、停止状态或隔离拓扑未通过核查");
            config.putAll(installed);
            log.accept(enterprise.isEmpty() ? "单机应用与 MySQL 部署已就绪，等待隔离启动"
                    : "企业版六项服务已完成离线配置，MySQL 已就绪，等待隔离启动");
            progress.complete(upgrade ? "已核对实际版本和隔离条件，应用保持停止。"
                    : "已连接内置 MySQL，并核对部署版本和隔离条件。");
        } finally {
            try {
                host.run(config, credentials, "rm -rf -- " + quote(remote), line -> {});
            } catch (Exception ignored) {
                /* Task records retain uncertain outcomes. */
            }
        }
    }

    private void createDeployment(
            Map<String, Object> config,
            Map<String, String> c,
            Path content,
            String target,
            String generation,
            Map<String, Object> appImage,
            Map<String, Object> dbImage)
            throws Exception {
        int port = port(config, "port", 8100), dbPort = port(config, "dbPort", 3306);
        String bind =
                InetAddress.getByName(text(config, "dbHost", text(config, "host", "")))
                        .getHostAddress();
        if (!"local".equals(config.get("connection"))
                && InetAddress.getByName(bind).isLoopbackAddress())
            throw new IllegalArgumentException("远程新建数据库请填写运维平台可直连的服务器IP");
        List<String> resources =
                List.of(
                        "static-resource",
                        "geo",
                        "map",
                        "appearance",
                        "exportData",
                        "plugin",
                        "font",
                        "driver",
                        "i18n",
                        "report",
                        "report-artifacts");
        List<String> directories = new ArrayList<>(List.of("conf", "logs", "cache", "data/mysql"));
        resources.forEach(name -> directories.add("data/" + name));
        host.run(
                config,
                c,
                "umask 077; mkdir -p "
                        + String.join(
                                " ",
                                directories.stream().map(d -> quote(target + "/" + d)).toList()),
                line -> {});
        List<String> volumes =
                new ArrayList<>(
                        List.of(
                                target + "/conf:/opt/apps/config",
                                target + "/conf:/opt/dataease" + generation + "/conf",
                                target + "/logs:/opt/dataease" + generation + "/logs",
                                target + "/cache:/opt/dataease" + generation + "/cache"));
        resources.forEach(
                name ->
                        volumes.add(
                                target
                                        + "/data/"
                                        + name
                                        + ":/opt/dataease"
                                        + generation
                                        + "/data/"
                                        + name));
        Map<String, String> environment = new LinkedHashMap<>();
        for (String prefix : List.of("DE_DB_", "DE_MYSQL_")) {
            environment.put(prefix + "HOST", "mysql");
            environment.put(prefix + "PORT", "3306");
            environment.put(prefix + "USER", "root");
            environment.put(prefix + "PASSWORD", c.get("dbPassword"));
            environment.put(
                    prefix + "PARAMS",
                    "useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true");
        }
        environment.put("DE_DB_DATABASE", text(config, "dbName", ""));
        environment.put("DE_MYSQL_DB", text(config, "dbName", ""));
        environment.put("DE_SPRING_PROFILE", generation.equals("3.0") ? "mysql" : "");
        environment.put("DE_CONTEXT_PATH", "");
        environment.put("DE_LOGIN_TIMEOUT", "960");
        environment.put("DE_EXPORT_VIEWS_LIMIT", "100000");
        environment.put("DE_EXPORT_DATASET_LIMIT", "100000");
        environment.put("DE_ORIGIN_LIST", "http://localhost:" + port);
        environment.put("DE_SERVERS", "dataease");
        environment.put("DE_PLAYWRIGHT_SERVER", "http://127.0.0.1:3000/screenshot");
        environment.put("QUARTZ_ENABLED", "true");
        environment.put("SPRING_QUARTZ_AUTO_STARTUP", "false");
        environment.put("SPRING_CONFIG_ADDITIONAL_LOCATION", "file:/opt/apps/config/");
        environment.put("DATAEASE_PATH_SUBSTITULE", "/opt/dataease" + generation + "/conf/substitule.json");
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("image", appImage.get("reference"));
        application.put("pull_policy", "never");
        application.put(
                "ports",
                List.of(
                        Map.of(
                                "target",
                                8100,
                                "published",
                                String.valueOf(port),
                                "host_ip",
                                "127.0.0.1")));
        application.put("volumes", volumes);
        application.put("environment", environment);
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("image", dbImage.get("reference"));
        database.put("pull_policy", "never");
        database.put(
                "environment",
                Map.of(
                        "MYSQL_ROOT_PASSWORD",
                        c.get("dbPassword"),
                        "MYSQL_ROOT_HOST",
                        "%",
                        "MYSQL_DATABASE",
                        config.get("dbName")));
        database.put(
                "ports",
                List.of(
                        Map.of(
                                "target",
                                3306,
                                "published",
                                String.valueOf(dbPort),
                                "host_ip",
                                bind)));
        database.put("volumes", List.of(target + "/data/mysql:/var/lib/mysql"));
        database.put(
                "command",
                List.of(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci",
                        "--max-allowed-packet=67108864"));
        String project = "deops-" + UUID.randomUUID().toString().substring(0, 12);
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("name", project);
        Map<String, Object> services = new LinkedHashMap<>(Map.of("dataease", application, "mysql", database));
        deployment.put("services", services);
        String appTemplate = Files.readString(content.resolve("dataease/templates/application.yml"));
        upload(
                config,
                c,
                json.writeValueAsBytes(
                        ComposeManager.literalConfig(deployment)),
                target + "/docker-compose.yml");
        upload(
                config,
                c,
                "DE_INSTALL_MODE=community\nDE_EXTERNAL_DB=false\n"
                        .getBytes(StandardCharsets.UTF_8),
                target + "/.env");
        upload(
                config,
                c,
                appTemplate.getBytes(StandardCharsets.UTF_8),
                target + "/conf/application.yml");
        if (c.containsKey("initialAdminPassword"))
            upload(config, c, json.writeValueAsBytes(Map.of("pwd", c.get("initialAdminPassword"),
                    "tokenSecret", deploymentSecret())), target + "/conf/substitule.json");
        if (generation.equals("3.0"))
            upload(
                    config,
                    c,
                    Files.readAllBytes(
                            content.resolve("dataease/templates/jpa/application-mysql.yml")),
                    target + "/conf/application-mysql.yml");
        config.put("composeProject", project);
    }

    private static final List<String> VENDOR_COMPOSE_FILES = List.of("docker-compose.yml",
            "docker-compose-mysql.yml", "docker-compose-apisix.yml", "docker-compose-task.yml",
            "docker-compose-playwright.yml");

    private void installVendorDectl(Map<String, Object> config, Map<String, String> credentials,
            String target) throws Exception {
        boolean sameHost = config.containsKey("sameHostSourceProject");
        if (!sameHost) compose.checkVendorDectlDestination(config, credentials);
        String launcher = target + "/bin/dectl";
        host.run(config, credentials, "umask 077; mkdir -p " + quote(target + "/.ops-runtime"), line -> {});
        updateVendorDectl(config, credentials, target);
        uploadText(config, credentials, target, ".ops-runtime/compose-project", text(config, "composeProject", "") + "\n");
        host.run(config, credentials, "chmod 700 " + quote(launcher)
                + (sameHost ? "" : " && ln -s -- " + quote(launcher) + " /usr/local/bin/dectl"), line -> {});
    }

    private void updateVendorDectl(Map<String, Object> config, Map<String, String> credentials, String target) throws Exception {
        String launcher = target + "/bin/dectl", staged = launcher + "-" + UUID.randomUUID();
        try (var resource = OfflineComposeInstaller.class.getResourceAsStream("/operations/vendor-dectl.sh")) {
            if (resource == null) throw new IllegalStateException("离线部署的 dectl 命令缺失。");
            upload(config, credentials, resource.readAllBytes(), staged);
        }
        host.run(config, credentials, "chmod 700 " + quote(staged) + " && mv -fT -- "
                + quote(staged) + " " + quote(launcher), line -> {});
    }

    private void createVendorDeployment(Map<String, Object> config, Map<String, String> c,
            Path content, String target, String generation) throws Exception {
        Path vendor = content.resolve("dataease");
        boolean v2 = generation.equals("2.0");
        String dbPrefix = v2 ? "DE_MYSQL_" : "DE_DB_";
        String dbNameKey = dbPrefix + (v2 ? "DB" : "DATABASE");
        Map<String, String> env = DataEaseOfflinePackage.vendorEnvironment(content.resolve("install.conf"));
        String database = text(config, "dbName", "");
        String dbService = env.get(dbPrefix + "HOST"), defaultDb = env.get(dbNameKey);
        if (!"enterprise".equals(env.get("DE_INSTALL_MODE")) || dbService == null
                || !dbService.matches("[a-z][a-z0-9_-]+") || !"3306".equals(env.get(dbPrefix + "PORT"))
                || !"root".equals(env.get(dbPrefix + "USER"))
                || !"false".equals(env.get(v2 ? "DE_EXTERNAL_MYSQL" : "DE_EXTERNAL_DB"))
                || List.of("DE_EXTERNAL_APISIX", "DE_EXTERNAL_PLAYWRIGHT", "DE_EXTERNAL_SYNC_TASK")
                        .stream().anyMatch(key -> !"false".equals(env.get(key))))
            throw new IllegalArgumentException("企业版离线包的内置服务配置不受支持。");

        String standardDirectory = "dataease" + generation;
        Path targetDirectory = Path.of(target);
        boolean standardName = targetDirectory.getFileName().toString().equals(standardDirectory);
        env.put("DE_BASE", targetDirectory.getParent().toString());
        if (!standardName) env.put("DE_OPS_INSTALL_DIR", target);
        env.put(dbNameKey, database);
        env.put(dbPrefix + "PASSWORD", c.get("dbPassword"));
        env.put("DE_APISIX_PORT", String.valueOf(port(config, "port", 9080)));
        env.put("DE_APISIX_KEY", deploymentSecret());
        if (v2 && "true".equals(env.get("DE_SYNC_AUTH_ENABLED"))) {
            env.put("DE_SYNC_AUTH_SECRET", deploymentSecret());
            env.put("DE_SYNC_AUTH_VERSION", "1");
        }
        env.put("DE_OPS_VENDOR_COMPOSE", "true");

        List<String> directories = List.of("conf", "logs", "cache", "data/mysql", "data/etcd_data",
                "data/static-resource", "data/geo", "data/map", "data/appearance", "data/exportData",
                "data/plugin", "data/font", "data/driver", "data/i18n", "data/report",
                "data/report-artifacts", "apisix/apisix_conf", "apisix/plugins", "apisix/logs", "bin/mysql");
        host.run(config, c, "umask 077; mkdir -p "
                + String.join(" ", directories.stream().map(d -> quote(target + "/" + d)).toList())
                + " && chmod 777 " + quote(target + "/data/etcd_data") + " " + quote(target + "/apisix/logs"),
                line -> {});
        for (String file : VENDOR_COMPOSE_FILES) {
            String yaml = Files.readString(vendor.resolve(file));
            // The native installer renders this service-key placeholder in its running directory.
            if (file.equals("docker-compose.yml") || file.equals("docker-compose-mysql.yml")) {
                if (!yaml.contains("DE_MYSQL_HOST:"))
                    throw new IllegalArgumentException("企业版离线包缺少原厂 MySQL 服务名占位符。");
                yaml = yaml.replace("DE_MYSQL_HOST:", dbService + ":");
            }
            // V3 enterprise's native installer removes the direct application port.
            if (!v2 && file.equals("docker-compose.yml")) {
                String published = java.util.regex.Pattern.quote("${DE_PORT}:8100");
                String rendered = yaml.replaceFirst("(?m)^    ports:[ \t]*\r?\n      - " + published + "[ \t]*\r?\n", "");
                if (rendered.equals(yaml)) throw new IllegalArgumentException("V3 原厂应用端口配置无法识别。");
                yaml = rendered;
            }
            if (!standardName)
                yaml = yaml.replace("${DE_BASE}/" + standardDirectory, "${DE_OPS_INSTALL_DIR}");
            uploadText(config, c, target, file, yaml);
        }
        String dotenv = env.entrySet().stream().map(entry -> entry.getKey() + "='" + dotenvValue(entry.getValue()) + "'")
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        uploadText(config, c, target, ".env", dotenv);
        uploadText(config, c, target, "conf/application.yml",
                renderVendorTemplate(vendor.resolve("templates/application.yml"), env)
                        .replace("DE_APISIX_KEY", env.get("DE_APISIX_KEY")));
        if (!v2)
            uploadText(config, c, target, "conf/application-mysql.yml",
                    renderVendorTemplate(vendor.resolve("templates/jpa/application-mysql.yml"), env));
        uploadText(config, c, target, "conf/mysql.env", renderVendorTemplate(vendor.resolve("templates/mysql.env"), env));
        uploadText(config, c, target, "conf/my.cnf", Files.readString(vendor.resolve("templates/my.cnf")));
        String init = Files.readString(vendor.resolve("bin/mysql/init.sql"));
        if (!database.equals(defaultDb)) {
            if (defaultDb == null || !init.contains("`" + defaultDb + "`"))
                throw new IllegalArgumentException("无法应用所选数据库名称。");
            init = init.replace("`" + defaultDb + "`", "`" + database + "`");
        }
        uploadText(config, c, target, "bin/mysql/init.sql", init);
        // The image drops privileges before reading these vendor files. Keep credential files private.
        host.run(config, c, "chmod 755 " + quote(target + "/bin/mysql")
                + " && chmod 644 " + quote(target + "/bin/mysql/init.sql") + " "
                + quote(target + "/conf/my.cnf"), line -> {});
        String gateway = "apisix/apisix_conf/config.yaml";
        uploadText(config, c, target, gateway, Files.readString(vendor.resolve(gateway))
                .replace("DE_APISIX_KEY", env.get("DE_APISIX_KEY")));
        for (String asset : List.of(gateway, "apisix/plugins/cas-auth.lua", "apisix/plugins/hmac-auth.lua")) {
            if (!asset.equals(gateway)) uploadText(config, c, target, asset, Files.readString(vendor.resolve(asset)));
            host.run(config, c, "chmod 644 " + quote(target + "/" + asset), line -> {});
        }
        config.put("composeFiles", VENDOR_COMPOSE_FILES);
        config.put("databaseService", dbService);
        config.put("dbHost", dbService);
        config.put("dbPort", 3306);
        if (config.containsKey("sameHostSourceProject")) {
            createInstanceOverride(config, c, target);
            env.put("DE_OPS_SAME_HOST_TARGET", "true");
            env.put("COMPOSE_PROJECT_NAME", text(config, "composeProject", ""));
            String updated = env.entrySet().stream()
                    .map(entry -> entry.getKey() + "='" + dotenvValue(entry.getValue()) + "'")
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            uploadText(config, c, target, ".env", updated);
        }
    }

    private void createInstanceOverride(Map<String, Object> config, Map<String, String> credentials, String target)
            throws Exception {
        var original = compose.config(config, credentials);
        var services = (Map<?, ?>) original.get("services");
        String project = text(config, "composeProject", "");
        if (!project.matches("deops-v3-[a-f0-9]{12}"))
            throw new IllegalArgumentException("无法确认独立的 V3 Compose 项目。");
        Map<String, Object> definitions = new LinkedHashMap<>();
        for (var entry : services.entrySet()) {
            String service = String.valueOf(entry.getKey());
            if (!service.matches("[a-z0-9_-]+") || !(entry.getValue() instanceof Map<?, ?> definition)
                    || !(definition.get("networks") instanceof Map<?, ?> networks) || networks.size() != 1)
                throw new IllegalArgumentException("V3 离线包的网络配置不支持同机迁移。");
            String alias = text(definition, "container_name", service);
            if (!alias.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("V3 容器网络别名无效。");
            definitions.put(service, Map.of("container_name", project + "-" + service,
                    "networks", Map.of(String.valueOf(networks.keySet().iterator().next()), Map.of("aliases", List.of(alias)))));
        }
        uploadText(config, credentials, target, ComposeManager.INSTANCE_OVERRIDE,
                json.writeValueAsString(Map.of("name", project, "services", definitions)));
        var files = new ArrayList<>(VENDOR_COMPOSE_FILES);
        files.add(ComposeManager.INSTANCE_OVERRIDE);
        config.put("composeFiles", files);
    }

    private static String dotenvValue(String value) {
        return value.replace("'", "\\'");
    }

    private static String renderVendorTemplate(Path template, Map<String, String> env) throws Exception {
        var variable = java.util.regex.Pattern.compile("\\$\\{(DE_[A-Z0-9_]+)\\}").matcher(Files.readString(template));
        return variable.replaceAll(match -> {
            String value = env.get(match.group(1));
            if (value == null) throw new IllegalArgumentException("离线包缺少原厂参数 " + match.group(1));
            return java.util.regex.Matcher.quoteReplacement(value);
        });
    }

    private void uploadText(Map<String, Object> config, Map<String, String> c, String target,
            String file, String value) throws Exception {
        upload(config, c, value.getBytes(StandardCharsets.UTF_8), target + "/" + file);
    }

    private static String deploymentSecret() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    private void copyDrivers(
            Map<String, Object> i,
            Map<String, String> c,
            Map<String, Object> pkg,
            Path content,
            String target,
            String generation)
            throws Exception {
        List<Path> drivers = new ArrayList<>();
        for (String dir : List.of("drivers", "dataease/drivers"))
            if (Files.isDirectory(content.resolve(dir)))
                try (var files = Files.list(content.resolve(dir))) {
                    drivers.addAll(
                            files.filter(
                                            p ->
                                                    p.getFileName()
                                                            .toString()
                                                            .matches(
                                                                    "mariadb-java-client-[A-Za-z0-9._-]+\\.jar"))
                                    .toList());
                }
        if (!generation.equals("3.0")) return;
        if (drivers.size() != 1) throw new IllegalArgumentException("V3离线包必须包含唯一的MariaDB JDBC驱动");
        String name = drivers.get(0).getFileName().toString();
        host.run(i, c, "mkdir -p " + quote(target + "/data/driver"), line -> {});
        Path source = drivers.get(0);
        String stage = target + "/data/driver/.ops-driver-" + UUID.randomUUID();
        host.upload(i, c, source, stage);
        String relative = content.relativize(source).toString().replace('\\', '/');
        verifyCopy(i, c, MediaService.packageFileDigest(pkg, relative), stage);
        host.run(
                i,
                c,
                "find "
                        + quote(target + "/data/driver")
                        + " -maxdepth 1 -type f -name 'mariadb-java-client-*.jar' -delete && mv "
                        + quote(stage)
                        + " "
                        + quote(target + "/data/driver/" + name),
                line -> {});
    }

    private void copyResources(
            Map<String, Object> i,
            Map<String, String> c,
            Path content,
            String target,
            String remote)
            throws Exception {
        Path resources = content.resolve("dataease/data");
        if (!Files.isDirectory(resources)) return;
        List<String> selected =
                List.of("static-resource", "geo", "map", "appearance", "exportData", "font", "i18n")
                        .stream()
                        .filter(name -> Files.isDirectory(resources.resolve(name)))
                        .toList();
        if (selected.isEmpty()) return;
        Path tar = Files.createTempFile("deops-offline-resources-", ".tar.gz");
        try {
            List<String> args =
                    new ArrayList<>(
                            List.of(
                                    "tar",
                                    "--no-xattrs",
                                    "--exclude=._*",
                                    "--exclude=__MACOSX",
                                    "-C",
                                    resources.toString(),
                                    "-czf",
                                    tar.toString()));
            args.addAll(selected);
            ProcessBuilder builder =
                    new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("COPYFILE_DISABLE", "1");
            Process process = builder.start();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
                        || process.exitValue() != 0) throw new IllegalStateException("离线资源归档失败");
            } finally {
                process.destroyForcibly();
            }
            String staged = remote + "/resources.tar.gz";
            host.upload(i, c, tar, staged);
            verifyCopy(i, c, ArchiveSupport.sha256(tar), staged);
            host.run(
                    i,
                    c,
                    "COPYFILE_DISABLE=1 tar -C "
                            + quote(target + "/data")
                            + " -xzf "
                            + quote(staged),
                    line -> {});
        } finally {
            Files.deleteIfExists(tar);
        }
    }

    private boolean imageMatches(
            Map<String, Object> i, Map<String, String> c, Map<String, Object> image, String arch)
            throws Exception {
        String actual =
                host.capture(
                        i,
                        c,
                        "docker image inspect --format '{{.Id}} {{.Architecture}} {{.Os}}' "
                                + quote(text(image, "reference", "")) + " 2>/dev/null || :");
        String[] parts = actual.split("\\s+");
        return parts.length == 3 && imageIdentityMatches(image, parts[0])
                && (arch.equals("ARM64") ? "arm64" : "amd64").equals(parts[1])
                && parts[2].equals("linux");
    }

    private boolean imageIdentityMatches(Map<String, Object> image, Object identity) {
        return image.get("identities") instanceof List<?> list ? list.contains(identity)
                : Objects.equals(image.get("id"), identity);
    }

    private boolean imageTagExists(Map<String, Object> config, Map<String, String> credentials,
            Map<String, Object> image) throws Exception {
        return !host.capture(config, credentials, "docker image inspect --format '{{.Id}}' "
                + quote(text(image, "reference", "")) + " 2>/dev/null || :").isBlank();
    }

    private void verifyCopy(Map<String, Object> i, Map<String, String> c, String expected, String remote)
            throws Exception {
        String digest =
                host.capture(
                                i,
                                c,
                                "if command -v sha256sum >/dev/null 2>&1; then sha256sum "
                                        + quote(remote)
                                        + "; else shasum -a 256 "
                                        + quote(remote)
                                        + "; fi")
                        .split("\\s+")[0];
        if (!expected.equals(digest))
            throw new IllegalStateException("分发文件摘要不一致");
    }

    private void upload(Map<String, Object> i, Map<String, String> c, byte[] bytes, String remote)
            throws Exception {
        Path directory = Files.createTempDirectory("deops-config-");
        Path temp = directory.resolve("payload.private");
        try {
            OperationsFiles.writePrivate(temp, bytes);
            host.upload(i, c, temp, remote);
            host.run(i, c, "chmod 600 " + quote(remote), line -> {});
        } finally {
            Files.deleteIfExists(temp);
            Files.deleteIfExists(directory);
        }
    }

    private void awaitDatabase(Map<String, Object> i, Map<String, String> c) throws Exception {
        long until = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (true) {
            try (Connection ignored = database.connect(i, c)) {
                return;
            } catch (java.sql.SQLException unavailable) {
                if (System.nanoTime() >= until)
                    throw new IllegalStateException(ComposeManager.isVendorEnterprise(i)
                            ? "内置 MySQL 未就绪或 SSH 转发未连通，请核对容器状态与 SSH 转发权限。"
                            : "新MySQL未在限定时间内就绪，应用保持停止");
                Thread.sleep(1000);
            }
        }
    }

    private static int port(Map<String, Object> i, String key, int fallback) {
        int value = Integer.parseInt(text(i, key, String.valueOf(fallback)));
        if (value < 1 || value > 65535) throw new IllegalArgumentException("端口须为1–65535");
        return value;
    }
}
