package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.model.DatabaseConnection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;

@Component
public class ComposeManager {
    public static final String ENTERPRISE_ADAPTER = "dataease-enterprise-compose-v1";
    public static final String VENDOR_ENTERPRISE_ADAPTER = "dataease-enterprise-vendor-compose-v1";
    static final String ETCD_DATA = "data/etcd_data";
    static final String INSTANCE_OVERRIDE = ".ops-compose-instance.yml";
    static final String RELEASE_OVERRIDE = ".ops-compose-release.yml";
    static final String MANAGED_EDITION = "com.dataease.operations.edition";
    private static final List<String> ENTERPRISE_SUPPORT = List.of("etcd", "api", "task-actuator", "apisix");
    private final HostCommands host;
    private final ObjectMapper json;

    public ComposeManager(HostCommands host, ObjectMapper json) {
        this.host = host;
        this.json = json;
    }

    public Map<String, Object> config(Map<String, Object> i, Map<String, String> c)
            throws Exception {
        return config(i, c, installationDirectory(i, c));
    }

    private Map<String, Object> config(Map<String, Object> i, Map<String, String> c, String path)
            throws Exception {
        String file = text(i, "composeFile", "");
        if (file.isBlank())
            file =
                    host.capture(
                            i,
                            c,
                            "cd "
                                    + quote(path)
                                    + " && for f in docker-compose.yml docker-compose.yaml"
                                    + " compose.yml compose.yaml; do if [ -f \"$f\" ]; then printf"
                                    + " '%s' \"$f\"; exit 0; fi; done");
        if (file.isBlank()) throw missingCompose();
        if (!Set.of("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")
                .contains(file)) throw new IllegalArgumentException("无法识别 Compose 配置文件");
        String main = quote(path + "/" + file);
        String availability = host.capture(i, c, "if [ ! -f " + main + " ]; then printf missing;"
                + " elif [ ! -r " + main + " ]; then printf unreadable; fi");
        if ("missing".equals(availability)) throw missingCompose();
        if ("unreadable".equals(availability))
            throw new InstallationFilesUnavailable("无法读取已登记的 Compose 文件。请核对部署文件及当前连接账号的读取权限。");
        List<String> files = new ArrayList<>(List.of(file));
        Map<String, String> environment = new LinkedHashMap<>();
        String dotenv =
                host.capture(
                        i,
                        c,
                        "if [ -f "
                                + quote(path + "/.env")
                                + " ]; then cat "
                                + quote(path + "/.env")
                                + "; fi");
        for (String line : dotenv.lines().toList()) {
            int equal = line.indexOf('=');
            if (equal > 0 && !line.stripLeading().startsWith("#"))
                environment.put(
                        line.substring(0, equal).trim(), unquote(line.substring(equal + 1).trim()));
        }
        if (!"true".equalsIgnoreCase(environment.get("DE_EXTERNAL_DB"))
                && exists(i, c, path + "/docker-compose-mysql.yml"))
            files.add("docker-compose-mysql.yml");
        if (!"community".equalsIgnoreCase(environment.get("DE_INSTALL_MODE")))
            for (String[] option :
                    List.of(
                            new String[] {"apisix", "DE_EXTERNAL_APISIX"},
                            new String[] {"task", "DE_EXTERNAL_SYNC_TASK"},
                            new String[] {"playwright", "DE_EXTERNAL_PLAYWRIGHT"})) {
                String extra = "docker-compose-" + option[0] + ".yml";
                if (!"true".equalsIgnoreCase(environment.get(option[1]))
                        && exists(i, c, path + "/" + extra)) files.add(extra);
            }
        if ("true".equals(environment.get("DE_OPS_SAME_HOST_TARGET"))) {
            if (!exists(i, c, path + "/" + INSTANCE_OVERRIDE))
                throw new IllegalArgumentException("同机 V3 实例缺少独立的 Compose 配置。");
            files.add(INSTANCE_OVERRIDE);
        }
        if ("true".equals(environment.get("DE_OPS_VENDOR_COMPOSE")) && exists(i, c, path + "/" + RELEASE_OVERRIDE))
            files.add(RELEASE_OVERRIDE);
        String flags = String.join(" ", files.stream().map(name -> "-f " + quote(name)).toList());
        String output =
                host.capture(
                        i,
                        c,
                        "cd "
                                + quote(path)
                                + " && " + COMPOSE + " "
                                + flags
                                + " config --format json");
        Map<String, Object> config;
        try {
            config =
                    json.convertValue(
                            runtimeConfig(
                                    json.readValue(
                                            output, new TypeReference<Map<String, Object>>() {})),
                            new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Compose解析结果不是有效配置，请在服务器核对Compose版本与部署文件");
        }
        config.put("_composeFile", file);
        config.put("_composeFiles", files);
        if ("true".equals(environment.get("DE_OPS_VENDOR_COMPOSE"))) config.put("_vendorEnterprise", true);
        return config;
    }

    private boolean exists(Map<String, Object> i, Map<String, String> c, String file)
            throws Exception {
        return "yes"
                .equals(host.capture(i, c, "if [ -f " + quote(file) + " ]; then printf yes; fi"));
    }

    public static final class InstallationFilesUnavailable extends IllegalStateException {
        InstallationFilesUnavailable(String message) {
            super(message);
        }
    }

    private static InstallationFilesUnavailable missingCompose() {
        return new InstallationFilesUnavailable("安装目录缺少 Compose 文件。请核对原部署文件，或从该实例的备份恢复后重试。");
    }

    private String installationDirectory(Map<String, Object> i, Map<String, String> c) throws Exception {
        String directory = quote(path(text(i, "path", "")));
        String actual = host.capture(i, c, "if [ ! -e " + directory + " ]; then printf missing;"
                + " elif [ ! -d " + directory + " ] || [ ! -r " + directory + " ] || [ ! -x " + directory
                + " ]; then printf unreadable; else cd " + directory + " && pwd -P; fi");
        return switch (actual) {
            case "missing" -> throw new InstallationFilesUnavailable(
                    "目标服务器上找不到已登记的安装目录。请在备份与还原中查找该实例的整体备份，恢复原部署后重试。");
            case "unreadable" -> throw new InstallationFilesUnavailable(
                    "当前连接账号无法读取已登记的安装目录。请核对目录及账号权限后重试。");
            default -> path(actual);
        };
    }

    public Map<String, Object> inspect(Map<String, Object> draft, Map<String, String> secrets)
            throws Exception {
        return inspectDeployment(draft, secrets).instance();
    }

    record DeploymentInspection(Map<String, Object> instance, Map<String, Object> composeConfig,
                                String applicationContainer) {}

    @SuppressWarnings("unchecked")
    DeploymentInspection inspectDeployment(Map<String, Object> draft, Map<String, String> secrets)
            throws Exception {
        Map<String, Object> i = new LinkedHashMap<>(draft);
        i.entrySet()
                .removeIf(
                        e ->
                                e.getValue() == null
                                        || e.getValue() instanceof String value && value.isBlank());
        secrets.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
        String path = installationDirectory(i, secrets);
        i.put("path", path);
        i.put("hostIdentity", host.capture(i, secrets, "docker info --format '{{.ID}}'"));
        i.put("sshUser", secrets.getOrDefault("sshUser", text(i, "sshUser", "root")));
        i.put("sshPort", secrets.getOrDefault("sshPort", text(i, "sshPort", "22")));
        i.put(
                "sshAuth",
                secrets.getOrDefault(
                        "sshAuth",
                        text(
                                i,
                                "sshAuth",
                                secrets.getOrDefault("privateKey", "").isBlank()
                                        ? "password"
                                        : "key")));
        Map<String, Object> cfg = config(i, secrets, path);
        Map<String, Object> services = (Map<String, Object>) cfg.get("services");
        if (services == null || services.isEmpty())
            throw new IllegalArgumentException("Compose 没有可识别服务");
        String app = null, db = null;
        for (var entry : services.entrySet()) {
            Map<String, Object> service = (Map<String, Object>) entry.getValue();
            String image = text(service, "image", "");
            String repository =
                    image.substring(image.lastIndexOf('/') + 1)
                            .split(":")[0]
                            .toLowerCase(Locale.ROOT);
            if (Set.of("mysql", "greatsql", "mariadb").contains(repository)) {
                if (db != null) throw new IllegalArgumentException("识别到多个数据库服务，首版需明确单个MySQL服务边界");
                db = entry.getKey();
            } else if (entry.getKey().equals("dataease")
                    || repository.equals("dataease")
                    || repository.startsWith("dataease-") && !repository.equals("dataease-sync-task")) {
                if (app != null) throw new IllegalArgumentException("识别到多个 DataEase 应用，首版仅支持单应用节点");
                app = entry.getKey();
            }
        }
        if (app == null) throw new IllegalArgumentException("未找到 DataEase 应用服务");
        Map<String, Object> appCfg = (Map<String, Object>) services.get(app);
        boolean vendorEnterprise = Boolean.TRUE.equals(cfg.get("_vendorEnterprise"));
        if (vendorEnterprise && !enterpriseTopology(services, db))
            throw new IllegalArgumentException("原厂企业版 Compose 服务组成已变化。");
        boolean enterprise = vendorEnterprise || managedEnterprise(services, appCfg);
        if (vendorEnterprise) i.put("deploymentAdapter", VENDOR_ENTERPRISE_ADAPTER);
        else if (enterprise) i.put("deploymentAdapter", ENTERPRISE_ADAPTER);
        else if (isManagedEnterprise(i)) i.remove("deploymentAdapter");
        String image = text(appCfg, "image", "");
        String tag = image.substring(image.lastIndexOf(':') + 1).split("@")[0];
        if (!tag.matches("v?[23]\\.\\d+\\.\\d+") && !tag.equals("dev-v2") && !tag.equals("dev-v3"))
            throw new IllegalArgumentException("应用镜像缺少可识别精确版本标签");
        i.put("version", tag.startsWith("v") || tag.startsWith("dev") ? tag : "v" + tag);
        i.put("applicationService", app);
        i.put("databaseService", db == null ? "" : db);
        i.remove("databaseImage");
        if (db != null)
            i.put("databaseImage", text((Map<?, ?>) services.get(db), "image", ""));
        i.put("composeFile", cfg.get("_composeFile"));
        i.put("composeFiles", cfg.get("_composeFiles"));
        i.put("composeProject", cfg.getOrDefault("name", Path.of(path).getFileName().toString()));
        i.put("serviceCount", services.size());
        i.put("image", image);
        String architecture = host.capture(i, secrets, "uname -m");
        i.put(
                "arch",
                architecture.matches("(?i)(aarch64|arm64)")
                        ? "ARM64"
                        : architecture.matches("(?i)(x86_64|amd64)") ? "x86_64" : "unknown");
        String id = containerId(i, secrets, app);
        boolean running = false;
        if (!id.isBlank()) {
            Map<String, Object> details = container(i, secrets, id);
            running =
                    Boolean.TRUE.equals(
                            ((Map<String, Object>) details.get("State")).get("Running"));
        }
        i.put("status", running ? "运行中" : "已停止");
        Map<?, ?> entrypoint = enterprise ? (Map<?, ?>) services.get("apisix") : appCfg;
        List<Map<String, Object>> ports =
                entrypoint.get("ports") instanceof List<?> l
                        ? (List<Map<String, Object>>) (List<?>) l
                        : List.of();
        if (!ports.isEmpty())
            i.put(
                    "port",
                    String.valueOf(
                            ports.get(0).getOrDefault("published", ports.get(0).get("target"))));
        else if (!i.containsKey("port")) throw new IllegalArgumentException("应用未声明可识别端口");
        Map<String, String> env = new LinkedHashMap<>();
        if (appCfg.get("environment") instanceof Map<?, ?> e)
            e.forEach(
                    (k, v) -> {
                        if (v != null) env.put(String.valueOf(k), String.valueOf(v));
                    });
        Map<String, String> settings = new LinkedHashMap<>();
        for (String relative :
                List.of(
                        "conf/application.yml",
                        "config/application.yml",
                        "conf/application.yaml",
                        "config/application.yaml",
                        "dataease.env",
                        ".env",
                        "install.conf")) {
            try {
                String content =
                        host.capture(
                                i,
                                secrets,
                                "if [ -f "
                                        + quote(path + "/" + relative)
                                        + " ]; then cat "
                                        + quote(path + "/" + relative)
                                        + "; fi");
                if (content.isBlank()) continue;
                if (relative.endsWith(".yml") || relative.endsWith(".yaml")) {
                    Object value;
                    try {
                        value = new Yaml().load(content);
                    } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
                        throw new IllegalArgumentException("部署配置YAML格式错误，请在服务器修正后重新识别");
                    }
                    if (value instanceof Map<?, ?> m) flatten(m, "", settings);
                } else
                    for (String line : content.split("\\r?\\n")) {
                        int equal = line.indexOf('=');
                        if (equal > 0 && !line.stripLeading().startsWith("#"))
                            settings.put(
                                    line.substring(0, equal).trim(),
                                    unquote(line.substring(equal + 1).trim()));
                    }
            } catch (IllegalArgumentException e) {
                throw e;
            }
        }
        Map<String, String> profileVariables = new LinkedHashMap<>(settings);
        profileVariables.putAll(env);
        Set<String> databaseProfiles = new LinkedHashSet<>();
        for (String key :
                List.of(
                        "DE_SPRING_PROFILE",
                        "SPRING_PROFILES_ACTIVE",
                        "SPRING_PROFILES_INCLUDE",
                        "spring.profiles.active",
                        "spring.profiles.include")) {
            String selected = resolve(profileVariables.get(key), profileVariables);
            if (selected != null)
                for (String profile : selected.split(","))
                    if (Set.of("mysql", "pg", "oracle", "dm", "kingbase", "sqlserver", "greatsql")
                            .contains(profile.trim())) databaseProfiles.add(profile.trim());
        }
        if (databaseProfiles.size() > 1)
            throw new IllegalArgumentException("存在多个元数据库profile，请先明确部署配置");
        for (String profile : databaseProfiles) {
            String content =
                    host.capture(
                            i,
                            secrets,
                            "for f in "
                                    + String.join(
                                            " ",
                                            List.of("conf", "config").stream()
                                                    .flatMap(
                                                            directory ->
                                                                    List.of("yml", "yaml").stream()
                                                                            .map(
                                                                                    extension ->
                                                                                            quote(
                                                                                                    path
                                                                                                            + "/"
                                                                                                            + directory
                                                                                                            + "/application-"
                                                                                                            + profile
                                                                                                            + "."
                                                                                                            + extension)))
                                                    .toList())
                                    + "; do if [ -f \"$f\" ]; then cat \"$f\"; exit 0; fi; done");
            if (!content.isBlank())
                try {
                    Object parsed = new Yaml().load(content);
                    if (parsed instanceof Map<?, ?> map) flatten(map, "", settings);
                } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
                    throw new IllegalArgumentException("数据库profile配置格式错误，请在服务器修正后重新识别");
                }
        }
        settings.putAll(env);
        String jdbc =
                first(
                        settings,
                        "SPRING_DATASOURCE_HIKARI_JDBC_URL",
                        "spring.datasource.hikari.jdbc-url",
                        "SPRING_DATASOURCE_URL",
                        "spring.datasource.url");
        jdbc = resolve(jdbc, settings);
        if (jdbc != null) {
            String actualType =
                    (jdbc.startsWith("jdbc:mysql:") || jdbc.startsWith("jdbc:mariadb:"))
                            ? "MySQL"
                            : jdbc.startsWith("jdbc:postgresql:")
                                    ? "PostgreSQL"
                                    : jdbc.startsWith("jdbc:oracle:")
                                            ? "Oracle"
                                            : jdbc.startsWith("jdbc:dm:")
                                                    ? "DM"
                                                    : jdbc.startsWith("jdbc:kingbase8:")
                                                            ? "Kingbase"
                                                            : jdbc.startsWith("jdbc:sqlserver:")
                                                                    ? "SQL Server"
                                                                    : "未知";
            i.put("dbType", actualType);
        }
        if (jdbc != null
                && (jdbc.startsWith("jdbc:mysql://") || jdbc.startsWith("jdbc:mariadb://"))) {
            DatabaseConnection d =
                    DatabaseConnection.fromJdbcUrl(
                            jdbc.replaceFirst("^jdbc:mariadb:", "jdbc:mysql:"));
            i.putIfAbsent("dbType", "MySQL");
            i.putIfAbsent("dbHost", d.host());
            i.putIfAbsent("dbPort", d.port());
            if (i.containsKey("dbName") && !String.valueOf(i.get("dbName")).equals(d.database()))
                throw new IllegalArgumentException("填写的数据库名与部署配置不一致");
            i.put("dbName", d.database());
        }
        String database =
                first(
                        settings,
                        "DE_DB_DATABASE",
                        "DE_MYSQL_DB",
                        "DE_MYSQL_DATABASE",
                        "MYSQL_DATABASE");
        String dbHost = first(settings, "DE_DB_HOST", "DE_MYSQL_HOST", "MYSQL_HOST");
        if (database != null) i.putIfAbsent("dbName", database);
        if (dbHost != null) i.putIfAbsent("dbHost", dbHost);
        String dbPort = first(settings, "DE_DB_PORT", "DE_MYSQL_PORT", "MYSQL_PORT");
        if (dbPort != null) i.putIfAbsent("dbPort", dbPort);
        String
                username =
                        resolve(
                                first(
                                        settings,
                                        "SPRING_DATASOURCE_HIKARI_USERNAME",
                                        "spring.datasource.hikari.username",
                                        "SPRING_DATASOURCE_USERNAME",
                                        "spring.datasource.username",
                                        "DE_DB_USER",
                                        "DE_MYSQL_USER",
                                        "MYSQL_USER"),
                                settings),
                password =
                        resolve(
                                first(
                                        settings,
                                        "SPRING_DATASOURCE_HIKARI_PASSWORD",
                                        "spring.datasource.hikari.password",
                                        "SPRING_DATASOURCE_PASSWORD",
                                        "spring.datasource.password",
                                        "DE_DB_PASSWORD",
                                        "DE_MYSQL_PASSWORD",
                                        "MYSQL_PASSWORD"),
                                settings);
        if (jdbc != null) secrets.put("runtimeDbUrl", jdbc);
        if (username != null) secrets.put("runtimeDbUser", username);
        if (password != null) secrets.put("runtimeDbPassword", password);
        if (username != null && !secrets.containsKey("dbUser")) secrets.put("dbUser", username);
        if (password != null && !secrets.containsKey("dbPassword"))
            secrets.put("dbPassword", password);
        if (db != null) {
            Map<String, Object> dbc = (Map<String, Object>) services.get(db);
            Set<String> aliases = new HashSet<>(Set.of(db));
            if (dbc.get("container_name") instanceof String name) aliases.add(name);
            if (dbc.get("networks") instanceof Map<?, ?> networks)
                for (Object value : networks.values())
                    if (value instanceof Map<?, ?> network
                            && network.get("aliases") instanceof List<?> names)
                        names.forEach(name -> aliases.add(String.valueOf(name)));
            if (jdbc != null
                    && (jdbc.startsWith("jdbc:mysql://") || jdbc.startsWith("jdbc:mariadb://"))) {
                String runtimeHost =
                        DatabaseConnection.fromJdbcUrl(
                                        jdbc.replaceFirst("^jdbc:mariadb:", "jdbc:mysql:"))
                                .host();
                i.put("databaseRouteVerified", aliases.contains(runtimeHost));
            }
            if (dbc.get("environment") instanceof Map<?, ?> e) {
                if (!secrets.containsKey("dbPassword")) {
                    Object pass = e.get("MYSQL_ROOT_PASSWORD");
                    if (pass != null) {
                        secrets.put("dbUser", "root");
                        secrets.put("dbPassword", String.valueOf(pass));
                    }
                }
                if (!i.containsKey("dbName") && e.get("MYSQL_DATABASE") != null)
                    i.put("dbName", e.get("MYSQL_DATABASE"));
            }
            String discoveredHost = text(i, "dbHost", db);
            if (vendorEnterprise) {
                if (!Boolean.TRUE.equals(i.get("databaseRouteVerified")) || dbc.containsKey("ports")
                        || Boolean.TRUE.equals(dbc.get("publish_all_ports")))
                    throw new IllegalArgumentException("原厂内置 MySQL 必须使用容器网络且不发布宿主机端口。");
                i.put("dbHost", db);
                i.put("dbPort", 3306);
            } else if (discoveredHost.equals(db) || discoveredHost.equals("mysql")) {
                List<?> dbPorts = dbc.get("ports") instanceof List<?> l ? l : List.of();
                if (!dbPorts.isEmpty()
                        && dbPorts.get(0) instanceof Map<?, ?> p
                        && p.get("published") != null) {
                    i.put("dbHost", i.get("host"));
                    i.put("dbPort", p.get("published"));
                } else throw new IllegalArgumentException("MySQL仅容器网络可达，请填写运维平台可直连的数据库地址与端口");
            }
        }
        i.putIfAbsent("dbType", "MySQL");
        i.putIfAbsent("dbPort", "3306");
        i.putIfAbsent("dbHost", i.get("host"));
        i.putIfAbsent("dbUser", secrets.getOrDefault("dbUser", "dataease"));
        if (!i.containsKey("dbName")) throw new IllegalArgumentException("未识别元数据库，请补充数据库名称与连接配置");
        Map<String, Object> environment =
                i.get("environmentConfig") instanceof Map<?, ?> e
                        ? new LinkedHashMap<>((Map<String, Object>) e)
                        : new LinkedHashMap<>();
        environment.put("deployment", "single-compose");
        environment.putIfAbsent("engineMode", "unknown");
        environment.putIfAbsent("engineProtection", "unknown");
        i.put("environmentConfig", environment);
        i.put("isolationSupported", isolationSupported(i));
        i.remove("password");
        i.remove("dbPassword");
        i.remove("privateKey");
        i.remove("passphrase");
        i.remove("hostKey");
        return new DeploymentInspection(i, cfg, id);
    }

    public boolean isolationSupported(Map<String, Object> i) {
        return Set.of("ARM64", "x86_64").contains(text(i, "arch", ""))
                && !text(i, "databaseService", "").isBlank()
                && Integer.parseInt(text(i, "serviceCount", "0")) == (isManagedEnterprise(i) ? 6 : 2)
                && text(i, "dbType", "").equals("MySQL");
    }

    public static boolean isManagedEnterprise(Map<String, Object> i) {
        return ENTERPRISE_ADAPTER.equals(i.get("deploymentAdapter")) || isVendorEnterprise(i);
    }

    public static boolean isVendorEnterprise(Map<String, Object> i) {
        return VENDOR_ENTERPRISE_ADAPTER.equals(i.get("deploymentAdapter"));
    }

    String gatewayBackupBinding(Map<String, Object> instance, Map<String, String> credentials, boolean captureState)
            throws Exception {
        if (!isVendorEnterprise(instance) || !isolationSupported(instance))
            throw new IllegalArgumentException("目标不是受管的原厂单机企业版部署。");
        String base = installationDirectory(instance, credentials);
        String id = containerId(instance, credentials, "etcd");
        if (!id.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("没有找到原厂 etcd 容器，无法核对网关状态。");
        var actual = container(instance, credentials, id);
        if (!(actual.get("Config") instanceof Map<?, ?> config)
                || !(config.get("Labels") instanceof Map<?, ?> labels)
                || !Objects.equals(instance.get("composeProject"), labels.get("com.docker.compose.project"))
                || !base.equals(labels.get("com.docker.compose.project.working_dir"))
                || !"etcd".equals(labels.get("com.docker.compose.service"))
                || !Set.of("1001", "1001:0").contains(text(config, "User", ""))
                || !(actual.get("Mounts") instanceof List<?> mounts) || mounts.size() != 1
                || !(mounts.get(0) instanceof Map<?, ?> mount)
                || !"bind".equals(mount.get("Type")) || !Boolean.TRUE.equals(mount.get("RW"))
                || !(base + "/" + ETCD_DATA).equals(mount.get("Source"))
                || !"/bitnami/etcd".equals(mount.get("Destination"))
                || !text(actual, "Image", "").matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("实际 etcd 镜像、运行身份或挂载与原厂部署不一致。");
        String data = quote(base + "/" + ETCD_DATA);
        String hash = host.capture(instance, credentials, "cd " + quote(base)
                + " && test ! -L " + data + " && test \"$(realpath -- " + data + ")\" = " + data
                + (captureState ? " && test -f " + data + "/data/member/snap/db" : "")
                + " && files=$(sha256sum -- .env conf/application.yml apisix/apisix_conf/config.yaml)"
                + " && printf '%s' \"$files\" | sha256sum | cut -d ' ' -f 1");
        if (!hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("无法核对企业版网关配置或 etcd 状态目录。");
        return text(actual, "Image", "") + "/" + hash;
    }

    private static boolean managedEnterprise(Map<String, Object> services, Map<String, Object> app) {
        return enterpriseTopology(services, "mysql")
                && app.get("labels") instanceof Map<?, ?> labels && "enterprise".equals(labels.get(MANAGED_EDITION));
    }

    private static boolean enterpriseTopology(Map<String, Object> services, String database) {
        if (services == null || database == null) return false;
        Set<String> expected = new HashSet<>(ENTERPRISE_SUPPORT);
        expected.add("dataease");
        expected.add(database);
        return expected.size() == 6 && expected.equals(services.keySet());
    }

    void validateVendorDeployment(Map<String, Object> instance, Map<String, String> credentials,
            Map<String, Object> images) throws Exception {
        Map<String, Object> cfg = config(instance, credentials);
        if (!Boolean.TRUE.equals(cfg.get("_vendorEnterprise"))
                || !(cfg.get("services") instanceof Map<?, ?> services)
                || !services.keySet().equals(images.keySet())
                || !(cfg.get("networks") instanceof Map<?, ?> networks) || networks.size() != 1)
            throw new IllegalArgumentException("原厂企业版离线包没有声明完整的单机 Compose 服务。");
        String root = path(text(instance, "path", "")) + "/";
        String db = text(instance, "databaseService", "");
        for (var entry : services.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> service)
                    || !Objects.equals(service.get("image"), images.get(name))
                    || !(service.get("networks") instanceof Map<?, ?> attached)
                    || !networks.keySet().equals(attached.keySet())
                    || List.of("network_mode", "pid", "cap_add", "devices", "links", "external_links",
                            "extra_hosts", "extends", "build", "command", "entrypoint", "volumes_from")
                            .stream().anyMatch(field -> service.get(field) != null)
                    || (!"api".equals(name) && (Boolean.TRUE.equals(service.get("privileged"))
                            || service.containsKey("ipc")))
                    || (!Set.of("dataease", "apisix").contains(name) && service.containsKey("ports"))
                    || Boolean.TRUE.equals(service.get("publish_all_ports")))
                throw new IllegalArgumentException("原厂服务 " + name + " 包含不受支持的镜像、权限或网络配置。");
            if (service.get("volumes") instanceof List<?> volumes)
                for (Object volume : volumes)
                    if (!(volume instanceof Map<?, ?> mount) || !"bind".equals(mount.get("type"))
                            || !(mount.get("source") instanceof String source) || !source.startsWith(root)
                            || !Path.of(source).normalize().toString().equals(source))
                        throw new IllegalArgumentException("原厂服务 " + name + " 的挂载超出安装目录。");
            if (name.equals(db) && !name.equals(service.get("container_name"))) {
                Object definition = attached.values().iterator().next();
                if (!instance.containsKey("sameHostSourceProject")
                        || !(definition instanceof Map<?, ?> network)
                        || !(network.get("aliases") instanceof List<?> aliases) || !aliases.contains(db))
                    throw new IllegalArgumentException("原厂内置 MySQL 网络名称与应用配置不一致。");
            }
        }
        String project = text(cfg, "name", "");
        if (!project.matches("[a-z0-9][a-z0-9_-]*"))
            throw new IllegalArgumentException("无法确认原厂 Compose 项目名称。");
        String active = host.capture(instance, credentials, "docker ps -a --filter "
                + quote("label=com.docker.compose.project=" + project) + " --format '{{.ID}}'");
        if (!active.isBlank())
            throw new IllegalArgumentException("目标机已有同名 Compose 项目，请选择独立安装目录。");
        instance.put("composeProject", project);
    }

    private static String isolationNetwork(Map<String, Object> i) {
        return "deops_" + text(i, "composeProject", "dataease").replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    static String enterpriseAlias(String service) {
        return switch (service) {
            case "etcd" -> "apisix-etcd";
            case "api" -> "de-playwright-api";
            case "task-actuator" -> "sync-task-actuator";
            default -> service;
        };
    }

    void checkVendorDectlDestination(Map<String, Object> i, Map<String, String> c) throws Exception {
        String available = host.capture(i, c, "if test -e /usr/local/bin/dectl || test -L /usr/local/bin/dectl"
                + " || test -e /usr/bin/dectl || test -L /usr/bin/dectl; then printf occupied;"
                + " elif test -d /usr/local/bin && test -w /usr/local/bin; then printf available; fi");
        if ("occupied".equals(available))
            throw new IllegalArgumentException("目标服务器已有 dectl，请核对已有 DataEase 安装。");
        if (!"available".equals(available))
            throw new IllegalArgumentException("SSH 账号不能写入 /usr/local/bin，无法安装 dectl 命令。");
    }

    public String command(Map<String, Object> i) {
        List<?> files =
                i.get("composeFiles") instanceof List<?> f && !f.isEmpty()
                        ? f
                        : List.of(text(i, "composeFile", "docker-compose.yml"));
        return "cd "
                + quote(path(text(i, "path", "")))
                + " && " + COMPOSE + " "
                + String.join(
                        " ", files.stream().map(f -> "-f " + quote(String.valueOf(f))).toList());
    }

    public String containerId(Map<String, Object> i, Map<String, String> c, String service)
            throws Exception {
        return host.capture(i, c, command(i) + " ps -a -q " + quote(service)).trim();
    }

    String privateMysqlAddress(Map<String, Object> instance, Map<String, String> credentials)
            throws Exception {
        String service = text(instance, "databaseService", "");
        String id = containerId(instance, credentials, service);
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalStateException("未找到唯一的内置 MySQL 容器。");
        Map<String, Object> actual = container(instance, credentials, id);
        if (!(actual.get("State") instanceof Map<?, ?> state) || !Boolean.TRUE.equals(state.get("Running"))
                || !(actual.get("Config") instanceof Map<?, ?> config)
                || !(config.get("Labels") instanceof Map<?, ?> labels)
                || !Objects.equals(instance.get("composeProject"), labels.get("com.docker.compose.project"))
                || !service.equals(labels.get("com.docker.compose.service"))
                || !(actual.get("HostConfig") instanceof Map<?, ?> hostConfig)
                || Boolean.TRUE.equals(hostConfig.get("PublishAllPorts"))
                || "host".equals(hostConfig.get("NetworkMode"))
                || hostConfig.get("PortBindings") instanceof Map<?, ?> bound && !bound.isEmpty()
                || !(actual.get("NetworkSettings") instanceof Map<?, ?> settings)
                || settings.get("Ports") instanceof Map<?, ?> published
                        && published.values().stream().anyMatch(Objects::nonNull)
                || !(settings.get("Networks") instanceof Map<?, ?> networks))
            throw new IllegalStateException("无法确认内置 MySQL 的容器身份和未发布端口状态。");
        String isolation = isolationNetwork(instance);
        List<?> nativeNetworks = networks.entrySet().stream()
                .filter(entry -> !isolation.equals(entry.getKey())).map(Map.Entry::getValue).toList();
        if (nativeNetworks.size() != 1 || !(nativeNetworks.get(0) instanceof Map<?, ?> network)
                || !(network.get("IPAddress") instanceof String address)
                || !address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
            throw new IllegalStateException("无法确认内置 MySQL 的单一私有容器地址。");
        return address;
    }

    public Map<String, Object> container(Map<String, Object> i, Map<String, String> c, String id)
            throws Exception {
        List<Map<String, Object>> list =
                json.readValue(
                        host.capture(i, c, "docker inspect " + quote(id)),
                        new TypeReference<>() {});
        if (list.size() != 1) throw new IllegalArgumentException("容器归属不唯一");
        return list.get(0);
    }

    public void stop(Map<String, Object> i, Map<String, String> c, Consumer<String> log)
            throws Exception {
        String app = text(i, "applicationService", "");
        if (app.isBlank()) throw new IllegalArgumentException("尚未识别应用服务");
        String services = isManagedEnterprise(i) ? " apisix api task-actuator etcd" : "";
        host.run(i, c, command(i) + " stop " + quote(app) + services, line -> {});
        verifyStopped(i, c);
        log.accept(isManagedEnterprise(i) ? "已停止企业版应用和配套服务，MySQL 保持运行"
                : "已停止 DataEase 应用，未停止数据库服务");
    }

    void verifyStopped(Map<String, Object> i, Map<String, String> c) throws Exception {
        String app = text(i, "applicationService", "");
        if (app.isBlank()) throw new IllegalArgumentException("尚未识别应用服务");
        String id = containerId(i, c, app);
        if (!id.isBlank()
                && Boolean.TRUE.equals(
                        ((Map<?, ?>) container(i, c, id).get("State")).get("Running")))
            throw new IllegalStateException("应用仍在运行，禁止继续覆盖");
        if (isManagedEnterprise(i)) for (String name : ENTERPRISE_SUPPORT) {
            String other = containerId(i, c, name);
            if (!other.isBlank() && Boolean.TRUE.equals(((Map<?, ?>) container(i, c, other).get("State")).get("Running")))
                throw new IllegalStateException("企业版服务 " + name + " 仍在运行");
        }
    }

    List<String> runningMigrationServices(Map<String, Object> i, Map<String, String> c) throws Exception {
        List<String> running = new ArrayList<>();
        for (String service : migrationApplicationServices(i)) {
            var actual = migrationContainer(i, c, service, false);
            if (actual != null && Boolean.TRUE.equals(((Map<?, ?>) actual.get("State")).get("Running")))
                running.add(service);
        }
        return running;
    }

    void restoreMigrationServices(Map<String, Object> i, Map<String, String> c, List<String> previous,
            Consumer<String> log) throws Exception {
        if (previous.isEmpty()) return;
        List<String> services = migrationApplicationServices(i);
        if (!services.containsAll(previous)) throw new IllegalStateException("迁移实例服务清单已变化。");
        for (String service : previous) migrationContainer(i, c, service, true);
        host.run(i, c, command(i) + " start " + String.join(" ", previous.stream().map(HostCommands::quote).toList()),
                line -> {});
        if (!runningMigrationServices(i, c).equals(previous))
            throw new IllegalStateException("实例未恢复到迁移前的运行状态。");
        if (isManagedEnterprise(i) && previous.containsAll(services)) awaitEnterpriseGateway(i, c);
        log.accept("已核验 " + text(i, "name", "实例") + " 恢复到迁移前的服务状态。");
    }

    private List<String> migrationApplicationServices(Map<String, Object> i) {
        List<String> services = new ArrayList<>();
        if (isManagedEnterprise(i)) services.addAll(ENTERPRISE_SUPPORT);
        services.add(text(i, "applicationService", ""));
        return services;
    }

    boolean migrationDatabaseRunning(Map<String, Object> i, Map<String, String> c) throws Exception {
        var actual = migrationContainer(i, c, text(i, "databaseService", ""), true);
        return Boolean.TRUE.equals(((Map<?, ?>) actual.get("State")).get("Running"));
    }

    String stoppedMigrationDatabaseIdentityFile(Map<String, Object> i, Map<String, String> c)
            throws Exception {
        String service = text(i, "databaseService", "");
        Map<String, Object> database = migrationContainer(i, c, service, true);
        if (database.get("Mounts") instanceof List<?> mounts) {
            for (Object value : mounts) {
                if (!(value instanceof Map<?, ?> mount) || !"bind".equals(mount.get("Type"))
                        || !"/var/lib/mysql".equals(mount.get("Destination"))) continue;
                String source = path(text(mount, "Source", ""));
                if (!Path.of(source).startsWith(Path.of(path(text(i, "path", "")))))
                    throw new IllegalStateException("内置 MySQL 数据挂载与实例安装目录不一致。");
                String file = quote(source + "/auto.cnf");
                String identity = host.capture(i, c, "if [ -f " + file + " ] && [ -r " + file
                        + " ]; then cat " + file + "; fi");
                if (identity.isBlank()) throw new InstallationFilesUnavailable(
                        "内置 MySQL 的原数据目录缺失或无法读取。请恢复该实例的数据后再启动。");
                return identity;
            }
        }
        String id = text(database, "Id", "");
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalStateException("无法确认内置 MySQL 容器身份。");
        return host.capture(i, c, "docker cp " + quote(id + ":/var/lib/mysql/auto.cnf") + " - | tar -xO");
    }

    void setMigrationDatabaseRunning(Map<String, Object> i, Map<String, String> c, boolean start) throws Exception {
        if (!migrationDatabaseRunning(i, c) && !start) return;
        host.run(i, c, command(i) + (start ? " start " : " stop ") + quote(text(i, "databaseService", "")),
                line -> {});
        if (migrationDatabaseRunning(i, c) != start)
            throw new IllegalStateException("无法核对 V2 内置 MySQL 的运行状态。");
    }

    private Map<String, Object> migrationContainer(Map<String, Object> i, Map<String, String> c, String service,
            boolean required) throws Exception {
        if (!isolationSupported(i) || service.isBlank())
            throw new IllegalStateException("尚未确认迁移实例的独立 Compose 服务。");
        String id = containerId(i, c, service);
        if (id.isBlank() && !required) return null;
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalStateException("无法确认迁移实例的容器身份。");
        var actual = container(i, c, id);
        if (!(actual.get("Config") instanceof Map<?, ?> config)
                || !(config.get("Labels") instanceof Map<?, ?> labels)
                || !Objects.equals(i.get("composeProject"), labels.get("com.docker.compose.project"))
                || !Objects.equals(i.get("path"), labels.get("com.docker.compose.project.working_dir"))
                || !service.equals(labels.get("com.docker.compose.service")))
            throw new IllegalStateException("迁移容器归属与已接入实例不一致。");
        return actual;
    }

    public void startExisting(Map<String, Object> i, Map<String, String> c, Consumer<String> log)
            throws Exception {
        String app = text(i, "applicationService", "");
        if (app.isBlank()) throw new IllegalArgumentException("尚未识别应用服务");
        String services = isManagedEnterprise(i) ? " etcd api task-actuator apisix" : "";
        host.run(i, c, command(i) + " start " + quote(app) + services, line -> {});
        String id = containerId(i, c, app);
        if (id.isBlank()
                || !Boolean.TRUE.equals(
                        ((Map<?, ?>) container(i, c, id).get("State")).get("Running")))
            throw new IllegalStateException("应用未启动");
        if (isManagedEnterprise(i)) {
            try {
                awaitEnterpriseGateway(i, c);
            } catch (Exception failure) {
                try { stop(i, c, line -> {}); }
                catch (Exception stopFailure) { failure.addSuppressed(stopFailure); }
                throw failure;
            }
        }
        log.accept("已启动原有应用容器，保留其既有网络和调度配置");
    }

    @SuppressWarnings("unchecked")
    public void start(
            Map<String, Object> i, Map<String, String> c, boolean isolated, Consumer<String> log)
            throws Exception {
        if (isolated && !isolationSupported(i))
            throw new IllegalArgumentException("此环境无法验证单机 Compose 网络隔离与自动任务暂停。");
        if (isVendorEnterprise(i)) {
            startVendorEnterprise(i, c, isolated, log);
            return;
        }
        Map<String, Object> cfg = config(i, c);
        Map<String, Object> services = (Map<String, Object>) cfg.get("services");
        String app = text(i, "applicationService", "");
        Map<String, Object> application =
                new LinkedHashMap<>((Map<String, Object>) services.get(app));
        boolean enterprise = isManagedEnterprise(i);
        if (enterprise && !managedEnterprise(services, application))
            throw new IllegalArgumentException("企业版部署配置已改变，不能继续隔离或开放访问。");
        Map<String, Object> environment =
                application.get("environment") instanceof Map<?, ?> e
                        ? new LinkedHashMap<>((Map<String, Object>) e)
                        : new LinkedHashMap<>();
        environment.put("QUARTZ_ENABLED", "true");
        environment.put("SPRING_QUARTZ_AUTO_STARTUP", "false");
        application.put("environment", environment);
        installGuard(i, c, application);
        application.remove("depends_on");
        application.put("pull_policy", "never");
        application.remove("restart");
        application.put("restart", isolated ? "no" : "unless-stopped");
        Map<String, Object> generated = new LinkedHashMap<>(cfg);
        generated.remove("_composeFile");
        generated.remove("_composeFiles");
        Map<String, Object> starting = new LinkedHashMap<>();
        starting.put(app, application);
        if (enterprise) for (String name : ENTERPRISE_SUPPORT) {
            Map<String, Object> service = new LinkedHashMap<>((Map<String, Object>) services.get(name));
            requireEnterpriseService(name, service);
            service.put("pull_policy", "never");
            service.put("restart", isolated ? "no" : "unless-stopped");
            starting.put(name, service);
        }
        if (enterprise) requireEnterpriseService(app, application);
        if (enterprise && !isolated) {
            var gatewayPort = gatewayBusinessPort(i, starting);
            starting.forEach((name, service) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> definition = (Map<String, Object>) service;
                if ("apisix".equals(name)) definition.put("ports", List.of(gatewayPort));
                else definition.remove("ports");
            });
        }
        generated.put("services", starting);
        if (isolated) {
            log.accept("准备内部网络并核对 MySQL 容器。");
            String network =
                    "deops_"
                            + text(i, "composeProject", "dataease")
                                    .replaceAll("[^A-Za-z0-9_.-]", "_");
            String exists =
                    host.capture(
                            i,
                            c,
                            "docker network ls --filter name=^"
                                    + network
                                    + "$ --format '{{.Name}}'");
            if (exists.isBlank())
                host.run(i, c, "docker network create --internal " + quote(network), line -> {});
            List<Map<String, Object>> networkDetails =
                    json.readValue(
                            host.capture(i, c, "docker network inspect " + quote(network)),
                            new TypeReference<>() {});
            if (networkDetails.size() != 1
                    || !Boolean.TRUE.equals(networkDetails.get(0).get("Internal")))
                throw new IllegalStateException("隔离网络不是internal网络，已中止");
            String db = text(i, "databaseService", "");
            String dbId = containerId(i, c, db);
            if (dbId.isBlank()) throw new IllegalStateException("未找到MySQL容器");
            Map<String, Object> dbContainer = container(i, c, dbId);
            if (!Boolean.TRUE.equals(((Map<?, ?>) dbContainer.get("State")).get("Running")))
                throw new IllegalStateException("MySQL未运行，不能隔离启动");
            Map<String, Object> networks =
                    (Map<String, Object>)
                            ((Map<String, Object>) dbContainer.get("NetworkSettings"))
                                    .get("Networks");
            if (!networks.containsKey(network))
                host.run(
                        i,
                        c,
                        "docker network connect --alias "
                                + quote(db)
                                + " "
                                + quote(network)
                                + " "
                                + quote(dbId),
                        line -> {});
            application.remove("network_mode");
            application.put("networks", Map.of("opsisolated", Map.of()));
            generated.put(
                    "networks", Map.of("opsisolated", Map.of("external", true, "name", network)));
            List<Map<String, Object>> ports =
                    application.get("ports") instanceof List<?> l
                            ? (List<Map<String, Object>>) (List<?>) l
                            : List.of();
            if (ports.isEmpty() && !enterprise) throw new IllegalArgumentException("未识别可隔离的应用端口");
            List<Map<String, Object>> bound = new ArrayList<>();
            for (Map<String, Object> port : ports) {
                Map<String, Object> p = new LinkedHashMap<>(port);
                p.put("host_ip", "127.0.0.1");
                bound.add(p);
            }
            if (enterprise) {
                application.remove("ports");
                for (String name : ENTERPRISE_SUPPORT) {
                    Map<String, Object> service = (Map<String, Object>) starting.get(name);
                    service.remove("ports");
                    service.put("networks", Map.of("opsisolated", Map.of("aliases", List.of(enterpriseAlias(name)))));
                }
            } else application.put("ports", bound);
        }
        String file =
                path(text(i, "path", ""))
                        + (isolated ? "/.ops-compose-isolated.json" : "/.ops-compose-open.json");
        try {
            Path temp = Files.createTempFile("deops-compose-", ".json");
            try {
                Files.writeString(
                        temp,
                        json.writeValueAsString(literalConfig(generated)),
                        StandardCharsets.UTF_8);
                host.upload(i, c, temp, file);
                String run = "cd " + quote(text(i, "path", "")) + " && " + COMPOSE + " -p "
                        + quote(text(i, "composeProject", "dataease")) + " -f " + quote(file)
                        + " up -d --pull never --no-deps --force-recreate ";
                host.run(i, c, "chmod 600 " + quote(file), line -> {});
                if (enterprise) {
                    host.run(i, c, run + "etcd", line -> {});
                    awaitEtcd(i, c);
                    host.run(i, c, run + "api task-actuator apisix", line -> {});
                    awaitEnterpriseDependencies(i, c);
                }
                log.accept("启动 DataEase 应用，等待运行核验。");
                host.run(i, c, run + quote(app), line -> {});
            } finally {
                Files.deleteIfExists(temp);
            }
            String id = containerId(i, c, app);
            Map<String, Object> actual = container(i, c, id);
            if (!Boolean.TRUE.equals(((Map<?, ?>) actual.get("State")).get("Running")))
                throw new IllegalStateException("应用未成功启动");
            Map<String, Object> actualConfig = (Map<String, Object>) actual.get("Config");
            List<String> env = (List<String>) actualConfig.get("Env");
            if (!env.contains("QUARTZ_ENABLED=true")
                    || !env.contains("SPRING_QUARTZ_AUTO_STARTUP=false"))
                throw new IllegalStateException("自动任务暂停配置未生效");
            if (isolated) verifyIsolation(i, c);
            log.accept("等待应用内自动任务保护组件就绪。");
            awaitGuard(i, c);
            if (enterprise) {
                awaitEnterpriseGateway(i, c);
                if (isolated) verifyIsolation(i, c);
                else verifyEnterprisePublishedPorts(i, c);
                log.accept(isolated ? "企业版各服务已在内部网络就绪，APISIX 入口未发布，Quartz 保持待机"
                        : "企业版 APISIX 入口已发布并通过访问核验，Quartz 保持待机");
            } else if (isolated) {
                log.accept("已核验应用内部网络、访问范围及 Quartz 暂停状态。");
            } else {
                log.accept("核验应用实际发布端口。");
                if (!(actual.get("NetworkSettings") instanceof Map<?, ?> settings)
                        || !(settings.get("Ports") instanceof Map<?, ?> published)
                        || published.values().stream().noneMatch(value -> value instanceof List<?> bindings
                                && bindings.stream().anyMatch(binding -> binding instanceof Map<?, ?> port
                                        && text(i, "port", "").equals(port.get("HostPort")))))
                    throw new IllegalStateException("应用未发布部署配置中的业务端口。");
                log.accept("业务端口已发布，Quartz 继续待机。");
            }
        } catch (Exception failure) {
            if (isolated && !uncertainCompletion(failure)) {
                try { stop(i, c, line -> {}); }
                catch (Exception stopFailure) { failure.addSuppressed(stopFailure); }
            }
            throw failure;
        }
    }

    private void startVendorEnterprise(Map<String, Object> i, Map<String, String> c,
            boolean isolated, Consumer<String> log) throws Exception {
        Map<String, Object> cfg = config(i, c);
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) cfg.get("services");
        String db = text(i, "databaseService", "");
        if (!Boolean.TRUE.equals(cfg.get("_vendorEnterprise")) || !enterpriseTopology(services, db))
            throw new IllegalArgumentException("原厂企业版 Compose 服务组成已变化。");
        log.accept(isolated ? "准备隔离网络与自动任务保护配置。" : "准备业务网络与自动任务保护配置。");
        String cliOpen = quote(path(text(i, "path", "")) + "/.ops-runtime/cli-open");
        if (isolated) host.run(i, c, "rm -f -- " + cliOpen, line -> {});
        privateMysqlAddress(i, c);
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("environment", Map.of("QUARTZ_ENABLED", "true", "SPRING_QUARTZ_AUTO_STARTUP", "false"));
        installGuard(i, c, app);
        writeOverride(i, c, ".ops-compose-guard.yml", vendorGuard(i, services, app));

        String command = command(i) + " -f .ops-compose-guard.yml";
        String network = isolationNetwork(i);
        if (isolated) {
            ensureDatabaseIsolationNetwork(i, c, network);
            StringBuilder override = new StringBuilder("services:\n");
            List<String> names = new ArrayList<>(ENTERPRISE_SUPPORT);
            names.add("dataease");
            for (String name : names) {
                override.append("  ").append(name)
                        .append(":\n    ports: !reset []\n    restart: \"no\"\n")
                        .append("    networks: !override {opsisolated: {aliases: [")
                        .append(enterpriseAlias(name)).append("]}}\n");
                if ("api".equals(name)) override.append("    privileged: false\n    ipc: !reset null\n");
            }
            override.append("networks:\n  opsisolated:\n    external: true\n    name: ").append(network).append('\n');
            writeOverride(i, c, ".ops-compose-isolated.yml", override.toString());
            command += " -f .ops-compose-isolated.yml";
        }
        String up = command + " up -d --pull never --no-deps --force-recreate ";
        try {
            if (isolated) verifyVendorOverride(i, c, command);
            else verifyVendorOpenPorts(i, c, command);
            log.accept("重建 etcd 容器并等待服务就绪。");
            host.run(i, c, up + "etcd", line -> {});
            awaitEtcd(i, c);
            log.accept("启动 APISIX、截图服务与同步执行器，检查服务响应。");
            host.run(i, c, up + "api task-actuator apisix", line -> {});
            awaitEnterpriseDependencies(i, c);
            log.accept("启动 DataEase，等待应用和调度保护组件就绪。");
            host.run(i, c, up + "dataease", line -> {});
            if (isolated) verifyIsolation(i, c);
            awaitGuard(i, c);
            log.accept("核验 APISIX 路由和实际端口。");
            awaitEnterpriseGateway(i, c);
            if (isolated) verifyIsolation(i, c);
            else {
                verifyEnterprisePublishedPorts(i, c);
                String dbId = containerId(i, c, db);
                Map<?, ?> settings = (Map<?, ?>) container(i, c, dbId).get("NetworkSettings");
                if (settings.get("Networks") instanceof Map<?, ?> attached && attached.containsKey(network)) {
                    host.run(i, c, "docker network disconnect " + quote(network) + " " + quote(dbId), line -> {});
                    host.run(i, c, "docker network rm " + quote(network), line -> {});
                }
            }
            privateMysqlAddress(i, c);
            if (!isolated) host.run(i, c, "umask 077; touch -- " + cliOpen, line -> {});
            log.accept(isolated ? "原厂企业版服务已在独立覆盖配置下隔离运行，应用与网关未发布端口。"
                    : "已核验仅 APISIX 发布业务端口，DataEase 应用和 MySQL 端口保持内部访问。");
        } catch (Exception failure) {
            if (isolated && !uncertainCompletion(failure)) {
                try { stop(i, c, line -> {}); }
                catch (Exception stopFailure) { failure.addSuppressed(stopFailure); }
            }
            throw failure;
        }
    }

    private void ensureDatabaseIsolationNetwork(Map<String, Object> i, Map<String, String> c,
            String network) throws Exception {
        String exists = host.capture(i, c, "docker network ls --filter name=^" + network + "$ --format '{{.Name}}'");
        if (exists.isBlank())
            host.run(i, c, "docker network create --internal --label "
                    + quote("io.dataease.operations.project=" + text(i, "composeProject", ""))
                    + " " + quote(network), line -> {});
        List<Map<String, Object>> networks = json.readValue(
                host.capture(i, c, "docker network inspect " + quote(network)), new TypeReference<>() {});
        if (networks.size() != 1 || !Boolean.TRUE.equals(networks.get(0).get("Internal")))
            throw new IllegalStateException("隔离网络不是 internal 网络。");
        String db = text(i, "databaseService", "");
        String id = containerId(i, c, db);
        Map<?, ?> settings = (Map<?, ?>) container(i, c, id).get("NetworkSettings");
        if (!(settings.get("Networks") instanceof Map<?, ?> attached) || !attached.containsKey(network))
            host.run(i, c, "docker network connect --alias " + quote(db) + " "
                    + quote(network) + " " + quote(id), line -> {});
    }

    private void writeOverride(Map<String, Object> i, Map<String, String> c, String name, String yaml)
            throws Exception {
        Path local = Files.createTempFile("deops-compose-", ".yml");
        try {
            Files.writeString(local, yaml, StandardCharsets.UTF_8);
            String remote = path(text(i, "path", "")) + "/" + name;
            host.upload(i, c, local, remote);
            host.run(i, c, "chmod 600 " + quote(remote), line -> {});
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private String vendorGuard(Map<String, Object> i, Map<String, Object> services, Map<String, Object> app)
            throws Exception {
        StringBuilder yaml = new StringBuilder("services:\n");
        for (String name : services.keySet()) {
            yaml.append("  ").append(json.writeValueAsString(name)).append(":\n");
            if ("dataease".equals(name))
                for (var setting : app.entrySet())
                    yaml.append("    ").append(setting.getKey()).append(": ")
                            .append(json.writeValueAsString(literalConfig(setting.getValue()))).append('\n');
            if ("apisix".equals(name))
                yaml.append("    ports: !override ")
                        .append(json.writeValueAsString(literalConfig(List.of(gatewayBusinessPort(i, services))))).append('\n');
            else yaml.append("    ports: !reset []\n");
        }
        return yaml.toString();
    }

    private void verifyVendorOpenPorts(Map<String, Object> i, Map<String, String> c, String command)
            throws Exception {
        Map<String, Object> merged = json.readValue(host.capture(i, c, command + " config --format json"),
                new TypeReference<>() {});
        if (!(merged.get("services") instanceof Map<?, ?> services))
            throw new IllegalStateException("无法读取企业版端口覆盖配置。");
        gatewayBusinessPort(i, services);
        for (var entry : services.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> service) || "host".equals(service.get("network_mode"))
                    || service.get("ports") instanceof List<?> ports && ports.size() > ("apisix".equals(entry.getKey()) ? 1 : 0))
                throw new IllegalStateException("企业版端口覆盖配置未生效，只允许发布 APISIX 业务端口。");
        }
    }

    private static Map<?, ?> gatewayBusinessPort(Map<String, Object> i, Map<?, ?> services) {
        if (services.get("apisix") instanceof Map<?, ?> gateway && gateway.get("ports") instanceof List<?> ports) {
            var matching = ports.stream().filter(item -> item instanceof Map<?, ?> port
                    && "9080".equals(String.valueOf(port.get("target")))
                    && "tcp".equals(text(port, "protocol", "tcp"))
                    && text(i, "port", "9080").equals(String.valueOf(port.get("published")))).toList();
            if (matching.size() == 1) return (Map<?, ?>) matching.get(0);
        }
        throw new IllegalStateException("未识别到唯一的 APISIX 业务端口映射。");
    }

    private void verifyVendorOverride(Map<String, Object> i, Map<String, String> c, String command)
            throws Exception {
        Map<String, Object> merged = json.readValue(
                host.capture(i, c, command + " config --format json"), new TypeReference<>() {});
        if (!(merged.get("services") instanceof Map<?, ?> services))
            throw new IllegalStateException("无法读取隔离覆盖配置。");
        List<String> names = new ArrayList<>(ENTERPRISE_SUPPORT);
        names.add("dataease");
        for (String name : names)
            if (!(services.get(name) instanceof Map<?, ?> service)
                    || service.get("ports") instanceof List<?> ports && !ports.isEmpty()
                    || Boolean.TRUE.equals(service.get("privileged"))
                    || "host".equals(service.get("ipc"))
                    || "host".equals(service.get("pid"))
                    || service.containsKey("network_mode")
                    || !(service.get("networks") instanceof Map<?, ?> networks)
                    || !networks.keySet().equals(Set.of("opsisolated")))
                throw new IllegalStateException("企业版服务 " + name + " 的隔离覆盖配置未生效。");
    }

    private void installGuard(Map<String, Object> i, Map<String, String> c,
            Map<String, Object> application) throws Exception {
        String directory = path(text(i, "path", "")) + "/.ops-runtime";
        host.run(i, c, "umask 077; mkdir -p " + quote(directory), line -> {});
        Path temporary = Files.createTempFile("deops-guard-", ".jar");
        try {
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporary))) {
                output.putNextEntry(new JarEntry("META-INF/spring.factories"));
                output.write(("org.springframework.context.ApplicationContextInitializer="
                        + ApplicationIsolationGuard.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                List<Class<?>> guardClasses = new ArrayList<>(List.of(ApplicationIsolationGuard.class));
                guardClasses.addAll(List.of(ApplicationIsolationGuard.class.getDeclaredClasses()));
                for (Class<?> type : guardClasses) {
                    String name = type.getName().replace('.', '/') + ".class";
                    try (InputStream input = type.getResourceAsStream("/" + name)) {
                        if (input == null) throw new IllegalStateException("应用调度隔离组件缺失");
                        output.putNextEntry(new JarEntry(name));
                        input.transferTo(output);
                        output.closeEntry();
                    }
                }
            }
            String staged = directory + "/guard-" + UUID.randomUUID() + ".tmp";
            host.upload(i, c, temporary, staged);
            host.run(i, c, "chmod 644 " + quote(staged) + " && mv -f "
                    + quote(staged) + " " + quote(directory + "/guard.jar"), line -> {});
        } finally { Files.deleteIfExists(temporary); }
        List<Object> volumes = new ArrayList<>();
        if (application.get("volumes") instanceof List<?> existing) volumes.addAll(existing);
        String home = text(i, "version", "").matches("v?2\\..*")
                ? "/opt/dataease2.0" : "/opt/dataease3.0";
        // Avoid nested file mounts below the data bind: Docker creates empty placeholders there.
        // Such placeholders can enter resource backups and break a later classpath scan.
        boolean driverBound = volumes.stream().anyMatch(value -> value instanceof Map<?, ?> mount
                && "bind".equals(mount.get("type"))
                && (home + "/data").equals(mount.get("target"))
                && (path(text(i, "path", "")) + "/data").equals(mount.get("source")));
        if (driverBound) {
            String driver = path(text(i, "path", "")) + "/data/driver";
            host.run(i, c, "mkdir -p " + quote(driver) + " && cp " + quote(directory + "/guard.jar")
                    + " " + quote(driver + "/deops-isolation-guard.tmp") + " && mv -f "
                    + quote(driver + "/deops-isolation-guard.tmp") + " "
                    + quote(driver + "/deops-isolation-guard.jar"), line -> {});
        }
        volumes.removeIf(value -> value instanceof Map<?, ?> mount
                && (home + "/data/driver/deops-isolation-guard.jar").equals(mount.get("target")));
        for (String target : List.of("/opt/apps/deops-isolation-guard.jar")) {
            volumes.removeIf(value -> value instanceof Map<?, ?> mount && target.equals(mount.get("target")));
            volumes.add(Map.of("type", "bind", "source", directory + "/guard.jar",
                    "target", target, "read_only", true));
        }
        application.put("volumes", volumes);
    }

    private static void requireEnterpriseService(String name, Map<String, Object> service) {
        if (Boolean.TRUE.equals(service.get("privileged"))
                || List.of("network_mode", "pid", "ipc", "cap_add", "devices", "links",
                        "external_links", "extra_hosts", "extends", "build")
                        .stream().anyMatch(service::containsKey)
                || (!"apisix".equals(name) && service.containsKey("ports")))
            throw new IllegalArgumentException("企业版服务 " + name + " 的权限、网络或端口配置超出受管范围。");
    }

    private void awaitEnterpriseDependencies(Map<String, Object> i, Map<String, String> c) throws Exception {
        awaitEnterprise(i, c, true);
    }

    private void awaitEtcd(Map<String, Object> i, Map<String, String> c) throws Exception {
        String etcd = containerId(i, c, "etcd");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
        while (!etcd.isBlank() && System.nanoTime() < deadline) {
            if (etcdReady(i, c, etcd)) return;
            Thread.sleep(1000);
        }
        throw new IllegalStateException("企业版 etcd 未通过健康检查。");
    }

    private boolean etcdReady(Map<String, Object> i, Map<String, String> c, String id) throws Exception {
        return "ready".equals(host.capture(i, c, "docker exec " + quote(id)
                + " etcdctl endpoint health >/dev/null 2>&1 && printf ready || printf unavailable"));
    }

    private void awaitEnterpriseGateway(Map<String, Object> i, Map<String, String> c) throws Exception {
        awaitEnterprise(i, c, false);
    }

    private void awaitEnterprise(Map<String, Object> i, Map<String, String> c, boolean dependenciesOnly) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
        Exception last = null;
        do {
            try {
                verifyEnterprise(i, c, dependenciesOnly);
                return;
            } catch (IllegalStateException | IllegalArgumentException notReady) {
                last = notReady;
                Thread.sleep(1500);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(last.getMessage(), last);
    }

    void verifyEnterprise(Map<String, Object> i, Map<String, String> c, boolean dependenciesOnly) throws Exception {
        if (!isManagedEnterprise(i)) return;
        Map<String, String> ids = new LinkedHashMap<>();
        for (String service : ENTERPRISE_SUPPORT) {
            String id = containerId(i, c, service);
            if (id.isBlank() || !Boolean.TRUE.equals(((Map<?, ?>) container(i, c, id).get("State")).get("Running")))
                throw new IllegalStateException("企业版服务 " + service + " 未运行。");
            ids.put(service, id);
        }
        if (!etcdReady(i, c, ids.get("etcd")))
            throw new IllegalStateException("企业版 etcd 未通过健康检查。");
        String api = "docker exec " + quote(ids.get("api")) + " curl -sS --max-time 4 -o /dev/null ";
        String result = host.capture(i, c, api + "-w '%{http_code}' http://127.0.0.1:3000/health 2>/dev/null || :");
        if (!result.equals("200")) throw new IllegalStateException("企业版截图服务未通过健康检查。");
        result = host.capture(i, c, api + "-w '%{http_code}' http://sync-task-actuator:9001/ 2>/dev/null || :");
        if (!result.matches("[234][0-9]{2}")) throw new IllegalStateException("企业版同步执行器尚未响应。");
        result = host.capture(i, c, api + "-w '%{http_code}' http://apisix:"
                + (dependenciesOnly ? "9180/apisix/admin/routes" : "9080/") + " 2>/dev/null || :");
        if (dependenciesOnly ? !result.matches("200|401|403") : !result.matches("[23][0-9]{2}"))
            throw new IllegalStateException(dependenciesOnly ? "企业版 APISIX 管理服务尚未就绪。"
                    : "企业版 APISIX 未能转发 DataEase 首页，请检查网关路由。");
    }

    private void verifyEnterprisePublishedPorts(Map<String, Object> i, Map<String, String> c) throws Exception {
        Set<String> services = new LinkedHashSet<>(ENTERPRISE_SUPPORT);
        services.add(text(i, "applicationService", "dataease"));
        services.add(text(i, "databaseService", ""));
        for (String name : services) {
            var details = container(i, c, containerId(i, c, name));
            if (!(details.get("NetworkSettings") instanceof Map<?, ?> settings))
                throw new IllegalStateException("无法读取企业版容器的端口状态。");
            Map<?, ?> ports = settings.get("Ports") instanceof Map<?, ?> values ? values : Map.of();
            boolean gateway = "apisix".equals(name);
            if (gateway && (!(ports.get("9080/tcp") instanceof List<?> bindings) || bindings.isEmpty()
                    || bindings.stream().anyMatch(value -> !(value instanceof Map<?, ?> binding)
                            || !text(i, "port", "9080").equals(binding.get("HostPort")))))
                throw new IllegalStateException("企业版 APISIX 未发布指定业务端口。");
            if (ports.entrySet().stream().anyMatch(entry -> (!gateway || !"9080/tcp".equals(entry.getKey()))
                    && entry.getValue() instanceof List<?> bindings && !bindings.isEmpty()))
                throw new IllegalStateException("企业版容器发布了额外端口，只允许 APISIX 业务端口。");
        }
    }

    public void verifyIsolation(Map<String, Object> i, Map<String, String> c) throws Exception {
        String expected = "deops_" + text(i, "composeProject", "dataease")
                .replaceAll("[^A-Za-z0-9_.-]", "_");
        String id = containerId(i, c, text(i, "applicationService", ""));
        Map<String, Object> actual = container(i, c, id);
        if (!(actual.get("State") instanceof Map<?, ?> state) || !Boolean.TRUE.equals(state.get("Running")))
            throw new IllegalStateException("隔离应用未运行");
        if (!(actual.get("NetworkSettings") instanceof Map<?, ?> settings)
                || !(settings.get("Networks") instanceof Map<?, ?> networks)
                || networks.size() != 1 || !networks.containsKey(expected))
            throw new IllegalStateException("应用实际网络已偏离指定隔离网络");
        List<Map<String, Object>> details = json.readValue(
                host.capture(i, c, "docker network inspect " + quote(expected)), new TypeReference<>() {});
        if (details.size() != 1 || !Boolean.TRUE.equals(details.get(0).get("Internal")))
            throw new IllegalStateException("应用实际网络不是internal网络");
        if (isManagedEnterprise(i)) {
            verifyEnterpriseIsolation(actual, expected);
            Set<String> members = new HashSet<>();
            members.add(id);
            for (String name : ENTERPRISE_SUPPORT) {
                String auxiliary = containerId(i, c, name);
                verifyEnterpriseIsolation(container(i, c, auxiliary), expected);
                members.add(auxiliary);
            }
            members.add(containerId(i, c, text(i, "databaseService", "")));
            if (!(details.get(0).get("Containers") instanceof Map<?, ?> attached)
                    || !attached.keySet().equals(members))
                throw new IllegalStateException("企业版内部网络的成员与本实例服务不一致。");
            return;
        }
        if (!(actual.get("HostConfig") instanceof Map<?, ?> config)
                || !(config.get("PortBindings") instanceof Map<?, ?> bindings) || bindings.isEmpty())
            throw new IllegalStateException("未识别隔离应用的端口绑定");
        for (Object value : bindings.values()) {
            if (!(value instanceof List<?> list) || list.isEmpty())
                throw new IllegalStateException("隔离应用端口绑定无效");
            for (Object item : list)
                if (!(item instanceof Map<?, ?> binding) || !"127.0.0.1".equals(binding.get("HostIp")))
                    throw new IllegalStateException("应用端口未限制为本机访问");
        }
        // An internal-only Docker network may leave requested ports unpublished. Inspect the
        // effective bindings as well, without mistaking the requested configuration for access.
        if (settings.get("Ports") instanceof Map<?, ?> published)
            for (Object value : published.values()) {
                if (value == null) continue;
                if (!(value instanceof List<?> list))
                    throw new IllegalStateException("无法核验隔离应用的实际端口发布状态");
                for (Object item : list)
                    if (!(item instanceof Map<?, ?> binding)
                            || !"127.0.0.1".equals(binding.get("HostIp")))
                        throw new IllegalStateException("隔离应用存在非本机的实际端口发布");
            }
    }

    private static void verifyEnterpriseIsolation(Map<String, Object> actual, String expected) {
        if (!(actual.get("State") instanceof Map<?, ?> state) || !Boolean.TRUE.equals(state.get("Running"))
                || !(actual.get("HostConfig") instanceof Map<?, ?> hostConfig)
                || Boolean.TRUE.equals(hostConfig.get("Privileged"))
                || "host".equals(hostConfig.get("IpcMode"))
                || "host".equals(hostConfig.get("PidMode"))
                || Boolean.TRUE.equals(hostConfig.get("PublishAllPorts"))
                || hostConfig.get("PortBindings") instanceof Map<?, ?> configured && !configured.isEmpty()
                || !(actual.get("NetworkSettings") instanceof Map<?, ?> networkSettings)
                || !(networkSettings.get("Networks") instanceof Map<?, ?> attached)
                || !attached.keySet().equals(Set.of(expected))
                || networkSettings.get("Ports") instanceof Map<?, ?> ports
                        && ports.values().stream().anyMatch(Objects::nonNull))
            throw new IllegalStateException("企业版服务没有保持在未发布端口的内部网络中。");
    }

    public void verifyGuard(Map<String, Object> i, Map<String, String> c) throws Exception {
        try (var transport = new ContainerApplicationHttp(host, this, i, c)) {
            var response = transport.request(ApplicationIsolationGuard.ENDPOINT, "", null, null);
            var result = json.readTree(response.body());
            if (response.status() != 200 || result.path("guardVersion").asInt() != 2
                    || !result.path("schedulersPaused").asBoolean())
                throw new IllegalStateException("无法核验运行中的Quartz强制待机状态");
        }
    }

    private void awaitGuard(Map<String, Object> i, Map<String, String> c) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4);
        try (var transport = new ContainerApplicationHttp(host, this, i, c)) {
            while (System.nanoTime() < deadline) {
                String id = containerId(i, c, text(i, "applicationService", ""));
                if (id.isBlank() || !Boolean.TRUE.equals(((Map<?, ?>) container(i, c, id).get("State")).get("Running")))
                    throw new IllegalStateException("隔离应用启动失败，请核对应用启动日志；未进入业务验收");
                try {
                    var response = transport.request(ApplicationIsolationGuard.ENDPOINT, "", null, null);
                    var result = json.readTree(response.body());
                    if (response.status() == 200 && result.path("guardVersion").asInt() == 2
                            && result.path("schedulersPaused").asBoolean()) return;
                } catch (java.net.SocketException | com.fasterxml.jackson.core.JacksonException notReady) {
                    // A live container is not proof that DataEase or its scheduler guard is ready.
                }
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("应用调度隔离组件未就绪，已停止应用；该镜像可能不支持外部启动组件");
    }

    private static String first(Map<String, String> m, String... keys) {
        for (String key : keys) {
            String value = m.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /** Compose interpolates values even in JSON; resolved runtime values must stay literal. */
    public static Object literalConfig(Object value) {
        if (value instanceof String text) return text.replace("$", "$$");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> escaped = new LinkedHashMap<>();
            map.forEach((key, item) -> escaped.put(String.valueOf(key), literalConfig(item)));
            return escaped;
        }
        if (value instanceof List<?> list)
            return list.stream().map(ComposeManager::literalConfig).toList();
        return value;
    }

    /** `compose config` escapes literal dollar signs so its output can be used as Compose input. */
    public static Object runtimeConfig(Object value) {
        if (value instanceof String text) return text.replace("$$", "$");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> runtime = new LinkedHashMap<>();
            map.forEach((key, item) -> runtime.put(String.valueOf(key), runtimeConfig(item)));
            return runtime;
        }
        if (value instanceof List<?> list)
            return list.stream().map(ComposeManager::runtimeConfig).toList();
        return value;
    }

    private static String unquote(String v) {
        return v.length() > 1
                        && (v.startsWith("\"") && v.endsWith("\"")
                                || v.startsWith("'") && v.endsWith("'"))
                ? v.substring(1, v.length() - 1)
                : v;
    }

    private static String resolve(String value, Map<String, String> env) {
        if (value == null) return null;
        var matcher =
                java.util.regex.Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}").matcher(value);
        StringBuffer b = new StringBuffer();
        while (matcher.find()) {
            String replacement = env.getOrDefault(matcher.group(1), matcher.group(2));
            if (replacement == null) return null;
            matcher.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(b);
        return b.toString();
    }

    private static void flatten(Map<?, ?> map, String prefix, Map<String, String> target) {
        for (var e : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m) flatten(m, key, target);
            else if (e.getValue() != null) target.put(key, String.valueOf(e.getValue()));
        }
    }
}
