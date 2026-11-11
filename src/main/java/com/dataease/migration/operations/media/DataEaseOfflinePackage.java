package com.dataease.migration.operations.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Structural adapter for the vendor's community Compose layout. Vendor scripts are never run. */
public final class DataEaseOfflinePackage {
    public static final String ADAPTER = "dataease-compose-v1";

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
                    if (!application && !repository.equals("mysql")) continue;
                    if (arch != null && !arch.equals(actualArch))
                        throw new IllegalArgumentException("应用和数据库镜像架构不一致");
                    arch = actualArch;
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
                    if (application) {
                        if (!imageVersion.matches("v?[23]\\.\\d+\\.\\d+")) continue;
                        String exact =
                                imageVersion.startsWith("v") ? imageVersion : "v" + imageVersion;
                        if (app != null && (!app.get("id").equals(id) || !version.equals(exact)))
                            throw new IllegalArgumentException("离线包含多个应用版本，无法确定安装目标");
                        app = info;
                        version = exact;
                    } else {
                        if (!imageVersion.matches("8\\.(0|4)\\.\\d+")) continue;
                        if (database != null && !database.get("id").equals(id))
                            throw new IllegalArgumentException("离线包含多个数据库镜像，无法确定目标");
                        database = info;
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
        result.put("dbTypes", List.of("MySQL"));
        result.put("purposes", List.of("install", "restore"));
        result.put("offlineComplete", true);
        result.put("operationsProtocol", 1);
        result.put("deployment", "single-compose");
        result.put("adapter", ADAPTER);
        result.put("images", Map.of("application", app, "database", database));
        result.put("files", hashes);
        return result;
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
