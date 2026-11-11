package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.operations.core.OperationsFiles;
import com.dataease.migration.operations.media.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Owns the deployment rather than invoking the vendor's interactive/root installer. */
final class OfflineComposeInstaller {
    private final HostCommands host;
    private final ComposeManager compose;
    private final ObjectMapper json;

    OfflineComposeInstaller(HostCommands host, ComposeManager compose, ObjectMapper json) {
        this.host = host;
        this.compose = compose;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    void install(
            Map<String, Object> pkg,
            Map<String, Object> config,
            Map<String, String> credentials,
            boolean upgrade,
            Consumer<String> log)
            throws Exception {
        Path content = Path.of(text(pkg, "contentRoot", ""));
        String target = path(text(config, "path", ""));
        String version = text(pkg, "version", ""),
                generation = version.startsWith("v2.") ? "2.0" : "3.0";
        Map<String, Object> images = (Map<String, Object>) pkg.get("images");
        Map<String, Object> appImage = (Map<String, Object>) images.get("application");
        Map<String, Object> dbImage = (Map<String, Object>) images.get("database");
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
            archives.add(text(appImage, "archive", ""));
            if (!upgrade) archives.add(text(dbImage, "archive", ""));
            for (String archive : archives) {
                Path source = MediaService.safeChild(content, archive);
                String staged = remote + "/" + UUID.randomUUID() + ".image";
                host.upload(config, credentials, source, staged);
                verifyCopy(config, credentials, source, staged);
                host.run(
                        config,
                        credentials,
                        "docker image load --input " + quote(staged),
                        line -> {});
            }
            verifyImage(config, credentials, appImage, text(pkg, "arch", ""));
            if (!upgrade) verifyImage(config, credentials, dbImage, text(pkg, "arch", ""));
            log.accept("已离线导入并核对镜像ID及实际CPU架构");
            if (upgrade) {
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
                createDeployment(
                        pkg, config, credentials, content, target, generation, appImage, dbImage);
                log.accept("已生成应用与数据库部署配置，尚未启动应用");
                copyResources(config, credentials, content, target, remote);
                log.accept("已复制离线包内置字体、地图与静态资源");
            }
            copyDrivers(config, credentials, content, target, generation);
            log.accept("已核对并更新元数据库驱动");
            config.put("deploymentAdapter", DataEaseOfflinePackage.ADAPTER);
            config.put("version", version);
            config.put("applicationService", "dataease");
            if (!upgrade) {
                config.put("databaseService", "mysql");
                config.put("composeFile", "docker-compose.yml");
                host.run(
                        config,
                        credentials,
                        compose.command(config) + " up -d --pull never --no-deps mysql",
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
            log.accept("单机应用与MySQL部署已就绪，应用保持停止，等待隔离启动");
        } finally {
            try {
                host.run(config, credentials, "rm -rf -- " + quote(remote), line -> {});
            } catch (Exception ignored) {
                /* Task records retain uncertain outcomes. */
            }
        }
    }

    private void createDeployment(
            Map<String, Object> pkg,
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
        environment.put("DE_SPRING_PROFILE", "mysql");
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
        upload(
                config,
                c,
                json.writeValueAsBytes(
                        ComposeManager.literalConfig(
                                Map.of(
                                        "name",
                                        project,
                                        "services",
                                        Map.of("dataease", application, "mysql", database)))),
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
                Files.readAllBytes(content.resolve("dataease/templates/application.yml")),
                target + "/conf/application.yml");
        if (generation.equals("3.0"))
            upload(
                    config,
                    c,
                    Files.readAllBytes(
                            content.resolve("dataease/templates/jpa/application-mysql.yml")),
                    target + "/conf/application-mysql.yml");
        config.put("composeProject", project);
    }

    private void copyDrivers(
            Map<String, Object> i,
            Map<String, String> c,
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
        verifyCopy(i, c, source, stage);
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
            verifyCopy(i, c, tar, staged);
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

    private void verifyImage(
            Map<String, Object> i, Map<String, String> c, Map<String, Object> image, String arch)
            throws Exception {
        String actual =
                host.capture(
                        i,
                        c,
                        "docker image inspect --format '{{.Id}} {{.Architecture}} {{.Os}}' "
                                + quote(text(image, "reference", "")));
        String[] parts = actual.split("\\s+");
        List<?> identities =
                image.get("identities") instanceof List<?> list ? list : List.of(image.get("id"));
        if (parts.length != 3
                || !identities.contains(parts[0])
                || !(arch.equals("ARM64") ? "arm64" : "amd64").equals(parts[1])
                || !parts[2].equals("linux"))
            throw new IllegalStateException("离线镜像ID或实际架构不匹配，已阻止部署");
    }

    private void verifyCopy(Map<String, Object> i, Map<String, String> c, Path file, String remote)
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
        if (!ArchiveSupport.sha256(file).equals(digest))
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
            try (Connection ignored = DatabaseArchive.connect(i, c)) {
                return;
            } catch (java.sql.SQLException unavailable) {
                if (System.nanoTime() >= until)
                    throw new IllegalStateException("新MySQL未在限定时间内就绪，应用保持停止");
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
