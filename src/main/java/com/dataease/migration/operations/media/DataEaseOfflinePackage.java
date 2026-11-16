package com.dataease.migration.operations.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verifies the vendor's offline Compose layout. Vendor scripts are never run. */
public final class DataEaseOfflinePackage {
    public static final String ADAPTER = "dataease-compose-v1";
    public static final List<String> DOCKER_BINARIES =
            List.of("docker", "dockerd", "docker-compose", "containerd", "containerd-shim-runc-v2",
                    "ctr", "runc", "docker-init", "docker-proxy");
    public static final Map<String, String> ENTERPRISE_SERVICES = Map.of(
            "apisix", "apisix", "etcd", "etcd", "api", "de-playwright-api",
            "task-actuator", "dataease-sync-task");

    private DataEaseOfflinePackage() {}

    public static Optional<Path> locate(Path content) throws Exception {
        try (var files = Files.walk(content, 5)) {
            List<Path> roots =
                    files.filter(p -> p.endsWith("dataease/docker-compose.yml"))
                            .map(p -> p.getParent().getParent())
                            .toList();
            if (roots.size() > 1) throw new IllegalArgumentException("介质中存在多套安装目录，无法自动适配");
            return roots.stream().findFirst();
        }
    }

    public static Map<String, Object> inspect(Path root, ObjectMapper json) throws Exception {
        if (!Files.isRegularFile(root.resolve("dataease/templates/application.yml"))
                || !Files.isDirectory(root.resolve("images")))
            throw new IllegalArgumentException("离线包缺少应用配置模板或 images 目录");
        Object compose =
                new Yaml().load(Files.readString(root.resolve("dataease/docker-compose.yml")));
        if (!(compose instanceof Map<?, ?> m)
                || !(m.get("services") instanceof Map<?, ?> services)
                || !services.containsKey("dataease"))
            throw new IllegalArgumentException("不是受支持的 DataEase Compose 安装结构");
        Map<String, Object> app = null, database = null;
        Map<String, Map<String, Object>> enterpriseImages = new TreeMap<>();
        String arch = null, version = null;
        List<Path> archives;
        try (var files = Files.list(root.resolve("images"))) {
            archives =
                    files.filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .filter(
                                    p ->
                                            p.toString().endsWith(".tar")
                                                    || p.toString().endsWith(".tar.gz"))
                            .sorted()
                            .toList();
        }
        if (archives.isEmpty() || archives.size() > 32)
            throw new IllegalArgumentException("离线包须包含完整 Docker-save 镜像归档，最多32个");
        for (Path archive : archives) {
            ArchiveSupport.TarIndex index = ArchiveSupport.imageIndex(archive);
            byte[] manifestBytes = index.metadata().get("manifest.json");
            if (manifestBytes == null)
                throw new IllegalArgumentException("镜像归档缺少 Docker-save manifest.json");
            JsonNode manifest = json.readTree(manifestBytes);
            if (!manifest.isArray() || manifest.isEmpty() || manifest.size() > 64)
                throw new IllegalArgumentException("镜像清单格式无效");
            for (JsonNode image : manifest) {
                String config = image.path("Config").asText();
                byte[] configBytes = index.metadata().get(config);
                if (configBytes == null) throw new IllegalArgumentException("镜像缺少完整配置");
                if (!image.path("Layers").isArray() || image.path("Layers").isEmpty())
                    throw new IllegalArgumentException("镜像缺少文件层");
                for (JsonNode layer : image.path("Layers"))
                    if (!index.files().contains(layer.asText()))
                        throw new IllegalArgumentException("镜像文件层缺失");
                JsonNode cfg = json.readTree(configBytes);
                if (!"linux".equals(cfg.path("os").asText()))
                    throw new IllegalArgumentException("首版只支持 Linux 容器镜像");
                String actualArch =
                        switch (cfg.path("architecture").asText()) {
                            case "arm64" -> "ARM64";
                            case "amd64" -> "x86_64";
                            default -> throw new IllegalArgumentException("镜像 CPU 架构不受支持");
                        };
                String id =
                        "sha256:"
                                + HexFormat.of()
                                        .formatHex(
                                                MessageDigest.getInstance("SHA-256")
                                                        .digest(configBytes));
                for (JsonNode tag : image.path("RepoTags")) {
                    String reference = tag.asText();
                    int colon = reference.lastIndexOf(':');
                    if (colon <= reference.lastIndexOf('/')
                            || !reference.matches("[A-Za-z0-9][A-Za-z0-9._/:-]{1,255}")) continue;
                    String repository = reference.substring(0, colon);
                    repository = repository.substring(repository.lastIndexOf('/') + 1);
                    String imageVersion = reference.substring(colon + 1);
                    boolean application = repository.equals("dataease");
                    boolean mysql = repository.equals("mysql");
                    if (!application && !mysql && !ENTERPRISE_SERVICES.containsValue(repository)) continue;
                    if ((application || mysql) && arch != null && !arch.equals(actualArch))
                        throw new IllegalArgumentException("应用和数据库镜像架构不一致");
                    if (application || mysql) arch = actualArch;
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("reference", reference);
                    info.put("id", id);
                    Set<String> identities = new LinkedHashSet<>();
                    identities.add(id);
                    byte[] ociBytes = index.metadata().get("index.json");
                    if (ociBytes != null) {
                        for (JsonNode descriptor : json.readTree(ociBytes).path("manifests")) {
                            String named =
                                    descriptor
                                            .path("annotations")
                                            .path("io.containerd.image.name")
                                            .asText();
                            if (normalizeReference(named).equals(normalizeReference(reference))
                                    && containsConfig(
                                            index, descriptor, id, json, new HashSet<>(), 0))
                                identities.add(descriptor.path("digest").asText());
                        }
                    }
                    info.put("identities", new ArrayList<>(identities));
                    info.put("archive", root.relativize(archive).toString().replace('\\', '/'));
                    if (index.dockerLoadBytes() != null) info.put("dockerLoadBytes", index.dockerLoadBytes());
                    if (application) {
                        if (!imageVersion.matches("v?[23]\\.\\d+\\.\\d+")) continue;
                        String exact =
                                imageVersion.startsWith("v") ? imageVersion : "v" + imageVersion;
                        if (app != null && (!app.get("id").equals(id) || !version.equals(exact)))
                            throw new IllegalArgumentException("离线包含多个应用版本，无法确定安装目标");
                        app = info;
                        version = exact;
                    } else if (mysql) {
                        if (!imageVersion.matches("8\\.(0|4)\\.\\d+")) continue;
                        if (database != null && !database.get("id").equals(id))
                            throw new IllegalArgumentException("离线包含多个数据库镜像，无法确定目标");
                        database = info;
                    } else {
                        info.put("arch", actualArch);
                        String key = normalizeReference(reference) + "/" + actualArch;
                        Map<String, Object> existing = enterpriseImages.putIfAbsent(key, info);
                        if (existing != null && !existing.get("id").equals(id))
                            throw new IllegalArgumentException("企业版离线包存在相同标签的不同镜像");
                    }
                }
            }
        }
        if (app == null || database == null)
            throw new IllegalArgumentException("离线包须包含精确版本 DataEase 与 MySQL 8.0/8.4 镜像");
        if (version.startsWith("v2.")) {
            String[] numbers = version.substring(1).split("\\.");
            if (Integer.parseInt(numbers[1]) < 10
                    || Integer.parseInt(numbers[1]) == 10 && Integer.parseInt(numbers[2]) < 26)
                throw new IllegalArgumentException("内置适配器仅支持 V2.10.26 及以上版本");
        } else {
            if (!Files.isRegularFile(root.resolve("dataease/templates/jpa/application-mysql.yml")))
                throw new IllegalArgumentException("V3 离线包缺少 MySQL 配置模板");
            List<Path> drivers = new ArrayList<>();
            for (String directory : List.of("drivers", "dataease/drivers"))
                if (Files.isDirectory(root.resolve(directory)))
                    try (var files = Files.list(root.resolve(directory))) {
                        drivers.addAll(
                                files.filter(
                                                p ->
                                                        p.getFileName()
                                                                .toString()
                                                                .matches(
                                                                        "mariadb-java-client-[A-Za-z0-9._-]+\\.jar"))
                                        .toList());
                    }
            if (drivers.size() != 1) throw new IllegalArgumentException("V3离线包须包含唯一MariaDB JDBC驱动");
            try (java.util.jar.JarFile driver =
                    new java.util.jar.JarFile(drivers.get(0).toFile())) {
                if (driver.getJarEntry("org/mariadb/jdbc/Driver.class") == null)
                    throw new IllegalArgumentException("离线包MariaDB JDBC驱动结构无效");
            }
        }
        Map<String, String> hashes = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                hashes.put(
                        root.relativize(file).toString().replace('\\', '/'),
                        ArchiveSupport.sha256(file));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("arch", arch);
        result.putAll(deploymentMetadata(root, version));
        boolean enterprise = "enterprise".equals(result.get("edition"));
        result.put("purposes", List.of("install", "restore"));
        result.put("offlineComplete", true);
        result.put("operationsProtocol", 1);
        result.put("deployment", "single-compose");
        result.put("adapter", ADAPTER);
        result.put("images", Map.of("application", app, "database", database));
        if (enterprise) result.put("enterpriseImages", enterpriseImages(root, enterpriseImages, arch, version));
        result.put("bundledDocker", bundledDocker(root, arch));
        result.put("files", hashes);
        return result;
    }

    static Map<String, Object> deploymentMetadata(Path root, String version) throws Exception {
        Map<String, String> defaults = vendorEnvironment(root.resolve("install.conf"));
        String edition = defaults.getOrDefault("DE_INSTALL_MODE", "unknown");
        if (!Set.of("community", "enterprise").contains(edition)) edition = "unknown";
        List<String> dbTypes = new ArrayList<>(List.of("MySQL"));
        if (version.startsWith("v3.")) {
            Path templates = root.resolve("dataease/templates/jpa");
            Map<String, String> profiles = Map.of(
                    "pg", "PostgreSQL", "oracle", "Oracle", "dm", "DM",
                    "kingbase", "Kingbase", "sqlserver", "SQL Server", "greatsql", "GreatSQL");
            profiles.entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .filter(entry -> Files.isRegularFile(templates.resolve("application-" + entry.getKey() + ".yml")))
                    .map(Map.Entry::getValue).forEach(dbTypes::add);
        }
        Map<String, Object> result = new LinkedHashMap<>(Map.of("edition", edition, "dbTypes", dbTypes));
        if ("enterprise".equals(edition)) {
            boolean v2 = version.startsWith("v2.");
            String dbName = defaults.getOrDefault(v2 ? "DE_MYSQL_DB" : "DE_DB_DATABASE", "");
            String dbHost = defaults.getOrDefault(v2 ? "DE_MYSQL_HOST" : "DE_DB_HOST", "");
            if (dbName.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) result.put("defaultDatabaseName", dbName);
            if (dbHost.matches("[a-z][a-z0-9_-]+")) result.put("defaultDatabaseHost", dbHost);
            int gateway = defaultPort(defaults, "DE_APISIX_PORT");
            int app = defaultPort(defaults, "DE_PORT");
            if (gateway > 0) result.put("defaultServicePort", gateway);
            if (v2 && app > 0) result.put("defaultApplicationPort", app);
        }
        return result;
    }

    public static Map<String, String> vendorEnvironment(Path settings) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        if (!Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS) || Files.size(settings) > 65536)
            return env;
        for (String line : Files.readAllLines(settings)) {
            int equal = line.indexOf('=');
            if (equal < 0) continue;
            String name = line.substring(0, equal).strip();
            if (!name.matches("DE_[A-Z0-9_]+")) continue;
            String value = line.substring(equal + 1).strip();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))))
                value = value.substring(1, value.length() - 1);
            env.put(name, value);
        }
        return env;
    }

    private static int defaultPort(Map<String, String> defaults, String name) {
        String raw = defaults.getOrDefault(name, "");
        if (!raw.matches("[0-9]{1,5}")) return 0;
        int port = Integer.parseInt(raw);
        return port <= 65535 ? port : 0;
    }

    private static boolean bundledDocker(Path root, String arch) throws Exception {
        int machine = "ARM64".equals(arch) ? 183 : 62;
        for (String name : DOCKER_BINARIES) {
            Path binary = root.resolve("docker/bin").resolve(name);
            if (!Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)) return false;
            try (var input = Files.newInputStream(binary)) {
                byte[] header = input.readNBytes(20);
                if (header.length != 20 || header[0] != 0x7f || header[1] != 'E'
                        || header[2] != 'L' || header[3] != 'F' || header[4] != 2
                        || header[5] != 1 || Byte.toUnsignedInt(header[18]) != machine
                        || header[19] != 0) return false;
            }
        }
        return true;
    }

    private static Map<String, Map<String, Object>> enterpriseImages(Path root,
            Map<String, Map<String, Object>> available, String arch, String version) throws Exception {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (String group : List.of("apisix", "playwright", "task")) {
            Path file = root.resolve("dataease/docker-compose-" + group + ".yml");
            Set<String> expected = switch (group) {
                case "apisix" -> Set.of("apisix", "etcd");
                case "playwright" -> Set.of("api");
                default -> Set.of("task-actuator");
            };
            if (!Files.isRegularFile(file) || Files.size(file) > 65536)
                throw new IllegalArgumentException("企业版离线包缺少 " + group + " 服务配置");
            var yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            Object contents = yaml.load(Files.readString(file));
            if (!(contents instanceof Map<?, ?> doc) || !(doc.get("services") instanceof Map<?, ?> services)
                    || !services.keySet().equals(expected))
                throw new IllegalArgumentException("不支持此企业版离线包的 " + group + " 服务组成");
            for (String name : expected) {
                if (!(services.get(name) instanceof Map<?, ?> service)
                        || !(service.get("image") instanceof String reference))
                    throw new IllegalArgumentException("企业版离线包缺少 " + name + " 镜像声明");
                String shortRef = reference.substring(reference.lastIndexOf('/') + 1);
                Map<String, Object> image = available.get(normalizeReference(reference) + "/" + arch);
                if (!shortRef.startsWith(ENTERPRISE_SERVICES.get(name) + ":") || image == null
                        || (name.equals("task-actuator") && !shortRef.endsWith(":" + version)))
                    throw new IllegalArgumentException("企业版离线包缺少匹配版本和架构的 " + name + " 镜像");
                result.put(name, image);
            }
        }
        for (String file : List.of("apisix/apisix_conf/config.yaml", "apisix/plugins/cas-auth.lua",
                "apisix/plugins/hmac-auth.lua")) {
            Path asset = root.resolve("dataease").resolve(file);
            if (!Files.isRegularFile(asset) || Files.size(asset) > 1024 * 1024)
                throw new IllegalArgumentException("企业版离线包缺少 APISIX 配置或插件文件");
        }
        if (!Files.readString(root.resolve("dataease/apisix/apisix_conf/config.yaml")).contains("key: DE_APISIX_KEY")
                || !Files.readString(root.resolve("dataease/templates/application.yml")).contains("key: DE_APISIX_KEY"))
            throw new IllegalArgumentException("企业版离线包的 APISIX 密钥配置结构不受支持");
        return result;
    }

    public static void requireManagedDeployment(Map<String, Object> pkg, String purpose) {
        if (ADAPTER.equals(pkg.get("adapter")) && "enterprise".equals(pkg.get("edition"))
                && !managedEnterprisePurposes(pkg).contains(purpose)) {
            if ("upgrade".equals(purpose) && managedEnterprisePurposes(pkg).contains("restore"))
                throw new IllegalArgumentException("请在软件包详情填写介质提供方验证的最低可升级来源版本。");
            throw new IllegalArgumentException("企业版需要包含全部配套服务的完整离线包。");
        }
    }

    static List<String> managedEnterprisePurposes(Map<String, Object> pkg) {
        if (!(pkg.get("enterpriseImages") instanceof Map<?, ?> images)
                || !images.keySet().equals(ENTERPRISE_SERVICES.keySet())) return List.of();
        return pkg.containsKey("minimumUpgradeSource") ? List.of("install", "restore", "upgrade")
                : List.of("install", "restore");
    }

    private static String normalizeReference(String reference) {
        return reference.startsWith("docker.io/") ? reference.substring(10) : reference;
    }

    private static boolean containsConfig(
            ArchiveSupport.TarIndex index,
            JsonNode descriptor,
            String config,
            ObjectMapper json,
            Set<String> seen,
            int depth)
            throws Exception {
        String digest = descriptor.path("digest").asText();
        if (depth > 8 || !digest.matches("sha256:[a-f0-9]{64}") || !seen.add(digest)) return false;
        byte[] bytes = index.metadata().get("blobs/sha256/" + digest.substring(7));
        if (bytes == null) return false;
        String actual =
                "sha256:"
                        + HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!digest.equals(actual)) throw new IllegalArgumentException("OCI镜像描述摘要不一致");
        JsonNode blob = json.readTree(bytes);
        if (config.equals(blob.path("config").path("digest").asText())) return true;
        for (JsonNode child : blob.path("manifests"))
            if (containsConfig(index, child, config, json, seen, depth + 1)) return true;
        return false;
    }
}
