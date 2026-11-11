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
    private final HostCommands host;
    private final ObjectMapper json;

    public ComposeManager(HostCommands host, ObjectMapper json) {
        this.host = host;
        this.json = json;
    }

    public Map<String, Object> config(Map<String, Object> i, Map<String, String> c)
            throws Exception {
        String path = path(text(i, "path", ""));
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
                                    + " '%s' \"$f\"; exit 0; fi; done; exit 3");
        if (!Set.of("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")
                .contains(file)) throw new IllegalArgumentException("无法识别 Compose 配置文件");
        List<String> files = new ArrayList<>();
        files.add(file);
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
        for (String line : dotenv.split("\\r?\\n")) {
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
        String flags = String.join(" ", files.stream().map(name -> "-f " + quote(name)).toList());
        String output =
                host.capture(
                        i,
                        c,
                        "cd "
                                + quote(path)
                                + " && docker compose "
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
        return config;
    }

    private boolean exists(Map<String, Object> i, Map<String, String> c, String file)
            throws Exception {
        return "yes"
                .equals(host.capture(i, c, "if [ -f " + quote(file) + " ]; then printf yes; fi"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> inspect(Map<String, Object> draft, Map<String, String> secrets)
            throws Exception {
        Map<String, Object> i = new LinkedHashMap<>(draft);
        i.entrySet()
                .removeIf(
                        e ->
                                e.getValue() == null
                                        || e.getValue() instanceof String value && value.isBlank());
        secrets.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
        String path = path(text(i, "path", ""));
        i.put("path", host.capture(i, secrets, "cd " + quote(path) + " && pwd -P"));
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
        Map<String, Object> cfg = config(i, secrets);
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
                    || repository.startsWith("dataease-")) {
                if (app != null) throw new IllegalArgumentException("识别到多个 DataEase 应用，首版仅支持单应用节点");
                app = entry.getKey();
            }
        }
        if (app == null) throw new IllegalArgumentException("未找到 DataEase 应用服务");
        Map<String, Object> appCfg = (Map<String, Object>) services.get(app);
        String image = text(appCfg, "image", "");
        String tag = image.substring(image.lastIndexOf(':') + 1).split("@")[0];
        if (!tag.matches("v?[23]\\.\\d+\\.\\d+") && !tag.equals("dev-v2") && !tag.equals("dev-v3"))
            throw new IllegalArgumentException("应用镜像缺少可识别精确版本标签");
        i.put("version", tag.startsWith("v") || tag.startsWith("dev") ? tag : "v" + tag);
        i.put("applicationService", app);
        i.put("databaseService", db == null ? "" : db);
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
        List<Map<String, Object>> ports =
                appCfg.get("ports") instanceof List<?> l
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
            if (discoveredHost.equals(db) || discoveredHost.equals("mysql")) {
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
        return i;
    }

    public boolean isolationSupported(Map<String, Object> i) {
        return Set.of("ARM64", "x86_64").contains(text(i, "arch", ""))
                && !text(i, "databaseService", "").isBlank()
                && Integer.parseInt(text(i, "serviceCount", "0")) == 2
                && text(i, "dbType", "").equals("MySQL");
    }

    public String command(Map<String, Object> i) {
        List<?> files =
                i.get("composeFiles") instanceof List<?> f && !f.isEmpty()
                        ? f
                        : List.of(text(i, "composeFile", "docker-compose.yml"));
        return "cd "
                + quote(path(text(i, "path", "")))
                + " && docker compose "
                + String.join(
                        " ", files.stream().map(f -> "-f " + quote(String.valueOf(f))).toList());
    }

    public String containerId(Map<String, Object> i, Map<String, String> c, String service)
            throws Exception {
        return host.capture(i, c, command(i) + " ps -a -q " + quote(service)).trim();
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
        host.run(i, c, command(i) + " stop " + quote(app), line -> {});
        String id = containerId(i, c, app);
        if (!id.isBlank()
                && Boolean.TRUE.equals(
                        ((Map<?, ?>) container(i, c, id).get("State")).get("Running")))
            throw new IllegalStateException("应用仍在运行，禁止继续覆盖");
        log.accept("已停止 DataEase 应用，未停止数据库服务");
    }

    public void startExisting(Map<String, Object> i, Map<String, String> c, Consumer<String> log)
            throws Exception {
        String app = text(i, "applicationService", "");
        if (app.isBlank()) throw new IllegalArgumentException("尚未识别应用服务");
        host.run(i, c, command(i) + " start " + quote(app), line -> {});
        String id = containerId(i, c, app);
        if (id.isBlank()
                || !Boolean.TRUE.equals(
                        ((Map<?, ?>) container(i, c, id).get("State")).get("Running")))
            throw new IllegalStateException("应用未启动");
        log.accept("已启动原有应用容器，保留其既有网络和调度配置");
    }

    @SuppressWarnings("unchecked")
    public void start(
            Map<String, Object> i, Map<String, String> c, boolean isolated, Consumer<String> log)
            throws Exception {
        if (isolated && !isolationSupported(i))
            throw new IllegalArgumentException("此环境无法验证隔离网络与自动任务暂停，首版仅支持一个应用与一个MySQL的Compose环境");
        Map<String, Object> cfg = config(i, c);
        Map<String, Object> services = (Map<String, Object>) cfg.get("services");
        String app = text(i, "applicationService", "");
        Map<String, Object> application =
                new LinkedHashMap<>((Map<String, Object>) services.get(app));
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
        generated.put("services", Map.of(app, application));
        if (isolated) {
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
            if (ports.isEmpty()) throw new IllegalArgumentException("未识别可隔离的应用端口");
            List<Map<String, Object>> bound = new ArrayList<>();
            for (Map<String, Object> port : ports) {
                Map<String, Object> p = new LinkedHashMap<>(port);
                p.put("host_ip", "127.0.0.1");
                bound.add(p);
            }
            application.put("ports", bound);
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
                host.run(
                        i,
                        c,
                        "chmod 600 "
                                + quote(file)
                                + " && cd "
                                + quote(text(i, "path", ""))
                                + " && docker compose -p "
                                + quote(text(i, "composeProject", "dataease"))
                                + " -f "
                                + quote(file)
                                + " up -d --pull never --no-deps --force-recreate "
                                + quote(app),
                        line -> {});
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
            awaitGuard(i, c);
            log.accept(isolated ? "已验证internal隔离网络、仅本机端口绑定策略与运行中的Quartz强制待机；实际浏览器访问需另行核对，端口配置不代表端口已发布" : "业务访问已开放；已核验Quartz仍由运维组件强制保持待机");
        } catch (Exception failure) {
            try { stop(i, c, line -> {}); }
            catch (Exception stopFailure) { failure.addSuppressed(stopFailure); }
            throw failure;
        }
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
