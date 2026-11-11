package com.dataease.migration.operations.media;

import com.dataease.migration.operations.core.OperationsApiException;
import com.dataease.migration.operations.core.OperationsFiles;
import com.dataease.migration.operations.core.OperationsProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** A reusable library of uploaded bytes and explicitly verified compatibility manifests. */
@Service
public final class MediaService {
    private static final long MAX_UPLOAD = 20L * 1024 * 1024 * 1024;
    private static final int MAX_MANIFEST = 1024 * 1024;
    private static final String UUID_PATTERN =
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final Set<String> DATABASES =
            Set.of("MySQL", "PostgreSQL", "Oracle", "DM", "Kingbase", "SQL Server", "GreatSQL");
    private final Path root;
    private final Path backups;
    private final ObjectMapper json;

    public MediaService(OperationsProperties properties, ObjectMapper json) throws IOException {
        root = properties.dataDirectory().resolve("media");
        backups = properties.dataDirectory().resolve("backups");
        OperationsFiles.createPrivateDirectory(root);
        this.json = json;
    }

    public Map<String, Object> upload(InputStream source, String kind, String filename)
            throws Exception {
        return upload(source, kind, filename, null, null);
    }

    public void discardDownloadReservation(String id) throws IOException {
        if (!id.matches(UUID_PATTERN)) throw bad("下载入库标识无效");
        ArchiveSupport.deleteTree(root.resolve(id));
    }

    /** Only the downloader supplies a reserved ID; HTTP upload never accepts an artifact ID. */
    public Map<String, Object> upload(InputStream source, String kind, String filename, String reservedId, String expectedSha256)
            throws Exception {
        if (!Set.of("package", "plugin", "backup", "install", "upgrade").contains(kind))
            throw bad("不支持的介质类型");
        if (reservedId != null && (!reservedId.matches(UUID_PATTERN) || !Set.of("package", "plugin").contains(kind)))
            throw bad("下载入库标识无效");
        String id = reservedId == null ? UUID.randomUUID().toString() : reservedId;
        Path directory = root.resolve(id), file = directory.resolve("artifact");
        if (reservedId != null && Files.exists(directory)) ArchiveSupport.deleteTree(directory);
        OperationsFiles.createPrivateDirectory(directory);
        try {
            try (InputStream input = source;
                    OutputStream output =
                            Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                OperationsFiles.restrictFile(file);
                byte[] buffer = new byte[65536];
                int count;
                long size = 0;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_UPLOAD) throw bad("上传介质超过 20GB 上限");
                    output.write(buffer, 0, count);
                }
                if (size == 0) throw bad("上传文件为空");
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id);
            record.put("kind", kind.equals("install") || kind.equals("upgrade") ? "package" : kind);
            record.put("fileName", filename(filename));
            record.put("artifactPath", file.toString());
            record.put("sizeBytes", Files.size(file));
            record.put("size", size(Files.size(file)));
            String digest = ArchiveSupport.sha256(file);
            if (expectedSha256 != null && !expectedSha256.equals(digest))
                throw bad("下载介质摘要在入库前发生变化");
            record.put("sha256", digest);
            record.put("uploadedAt", Instant.now().toString());
            record.put("status", "待校验");
            // BackupService decrypts and validates the actual backup manifest; upload metadata
            // never supplies one.
            return kind.equals("backup") ? record : verify(record);
        } catch (Exception exception) {
            ArchiveSupport.deleteTree(directory);
            throw exception;
        }
    }

    public synchronized Map<String, Object> verify(Map<String, Object> record) throws Exception {
        Path file = getPath(record);
        String digest = ArchiveSupport.sha256(file);
        if (!(record.get("sha256") instanceof String expected) || !digest.equals(expected))
            throw bad("介质 SHA-256 与入库记录不一致，已阻止使用");
        Map<String, Object> result = cleanRecord(record);
        result.put("sha256", digest);
        if ("backup".equals(record.get("kind"))
                || String.valueOf(record.get("id")).startsWith("BK-")) {
            result.put("status", "待校验");
            return result;
        }
        if (!Set.of("package", "plugin").contains(String.valueOf(record.get("kind"))))
            throw bad("介质类型无效");
        Path content = extract(file, digest);
        Path supplied = file.getParent().resolve("operator-manifest.json");
        if ("plugin".equals(record.get("kind"))) {
            Path embedded =
                    Files.isRegularFile(content.resolve("META-INF/dataease-plugin.json"))
                            ? content.resolve("META-INF/dataease-plugin.json")
                            : content.resolve("plugin.json");
            if (Files.exists(supplied))
                return plugin(record, content, readManifest(supplied), "operator");
            if (!Files.isRegularFile(embedded)) {
                Map<String, Object> nativeInfo = nativePlugin(content);
                if (!nativeInfo.isEmpty())
                    return plugin(record, content, nativeInfo, "jar-descriptor");
                return unavailable(result, "待补充清单", "JAR 缺少插件描述及兼容清单，请补充可核验的版本信息。");
            }
            return plugin(record, content, readManifest(embedded), "embedded");
        }
        if (Files.exists(supplied)) {
            Map<String, Object> manifest = readManifest(supplied);
            return validatePackage(
                    record, locateRoot(content, manifest.get("packageRoot")), manifest, "operator");
        }
        List<Path> manifests;
        try (var paths = Files.walk(content)) {
            manifests =
                    paths.filter(
                                    path ->
                                            Files.isRegularFile(path)
                                                    && path.getFileName()
                                                            .toString()
                                                            .equals("operations-manifest.json"))
                            .limit(2)
                            .toList();
        }
        if (manifests.isEmpty()) {
            Optional<Path> vendor = DataEaseOfflinePackage.locate(content);
            if (vendor.isPresent()) {
                try {
                    return builtinPackage(record, vendor.get());
                } catch (IllegalArgumentException invalid) {
                    return unavailable(result, "待补充清单", invalid.getMessage());
                }
            }
            return unavailable(
                    result,
                    "待补充清单",
                    "离线包未提供平台执行清单。请补充精确版本、架构、数据库、用途、全部文件摘要及 operationsProtocol=1"
                            + " 的非交互入口；不会从文件名推断，也不会执行原始安装脚本。");
        }
        if (manifests.size() != 1)
            return unavailable(result, "校验失败", "离线包存在多个 operations-manifest.json，无法确定唯一执行入口。");
        Map<String, Object> manifest = readManifest(manifests.get(0));
        try {
            return validatePackage(record, manifests.get(0).getParent(), manifest, "embedded");
        } catch (OperationsApiException exception) {
            return unavailable(result, "校验失败", exception.getMessage());
        }
    }

    /** An administrator can attach an explicit adapter manifest to an original vendor archive. */
    public synchronized Map<String, Object> attachManifest(
            Map<String, Object> record, Map<String, Object> manifest) throws Exception {
        if (!Set.of("package", "plugin").contains(String.valueOf(record.get("kind"))))
            throw bad("备份清单必须从实际备份归档解析，不能手工替换");
        if (manifest == null || json.writeValueAsBytes(manifest).length > MAX_MANIFEST)
            throw bad("介质清单为空或超过 1MB 上限");
        Path file = getPath(record);
        String digest = ArchiveSupport.sha256(file);
        if (!digest.equals(record.get("sha256"))) throw bad("介质内容已改变，不能补充执行清单");
        Path content = extract(file, digest);
        Map<String, Object> result;
        if ("plugin".equals(record.get("kind")))
            result = plugin(record, content, manifest, "operator");
        else
            result =
                    validatePackage(
                            record,
                            locateRoot(content, manifest.get("packageRoot")),
                            manifest,
                            "operator");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) result.get("manifest");
        if (normalized == null || !"已校验".equals(result.get("status")))
            throw bad(String.valueOf(result.getOrDefault("validationMessage", "介质清单无效")));
        OperationsFiles.writePrivate(
                file.getParent().resolve("operator-manifest.json"),
                json.writeValueAsBytes(normalized));
        result.put(
                "revision",
                record.get("revision") instanceof Number number ? number.longValue() + 1 : 1L);
        return result;
    }

    private Map<String, Object> validatePackage(
            Map<String, Object> record, Path packageRoot, Map<String, Object> raw, String source)
            throws Exception {
        if (DataEaseOfflinePackage.ADAPTER.equals(raw.get("adapter")))
            return builtinPackage(record, packageRoot, raw);
        String version = version(raw.get("version")), arch = text(raw.get("arch"));
        if (!Set.of("x86_64", "ARM64").contains(arch)) throw bad("清单架构须为 x86_64 或 ARM64");
        List<String> dbTypes = strings(raw.get("dbTypes"), "数据库支持");
        if (dbTypes.stream().anyMatch(type -> !DATABASES.contains(type))
                || version.startsWith("v2.") && !dbTypes.equals(List.of("MySQL")))
            throw bad("数据库支持清单无效；V2 仅支持 MySQL");
        List<String> purposes = strings(raw.get("purposes"), "允许用途");
        if (purposes.stream()
                .anyMatch(purpose -> !Set.of("install", "upgrade", "restore").contains(purpose)))
            throw bad("允许用途只能为 install、upgrade、restore");
        if (!Boolean.TRUE.equals(raw.get("offlineComplete"))) throw bad("清单未声明离线介质完整，不能执行");
        if (!(raw.get("operationsProtocol") instanceof Number protocol)
                || protocol.doubleValue() != 1.0)
            throw bad("缺少 operationsProtocol=1 平台非交互执行协议；不能直接运行原始安装脚本");
        if (raw.containsKey("deployment") && !"single-compose".equals(raw.get("deployment")))
            throw bad("首版仅执行单机 Docker Compose 介质");
        if (!(raw.get("files") instanceof Map<?, ?> files)
                || files.isEmpty()
                || files.size() > 200_000) throw bad("清单必须提供全部介质文件的 SHA-256");
        Map<String, String> hashes = new TreeMap<>();
        for (var entry : files.entrySet()) {
            String relative = relative(String.valueOf(entry.getKey()));
            String hash = text(entry.getValue());
            if (!hash.matches("[a-fA-F0-9]{64}")) throw bad("文件 SHA-256 格式无效");
            if (hashes.put(relative, hash.toLowerCase(Locale.ROOT)) != null)
                throw bad("清单存在重复文件路径");
            Path item = safeChild(packageRoot, relative);
            if (!Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(item)
                    || !ArchiveSupport.sha256(item).equalsIgnoreCase(hash))
                throw bad("介质文件缺失或 SHA-256 不匹配");
        }
        try (var paths = Files.walk(packageRoot)) {
            for (Path item : paths.toList()) {
                if (Files.isSymbolicLink(item)
                        || !(Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)
                                || Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)))
                    throw bad("介质内容含链接或不支持的文件类型");
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = packageRoot.relativize(item).toString().replace('\\', '/');
                if (!relative.equals("operations-manifest.json") && !hashes.containsKey(relative))
                    throw bad("清单未覆盖离线包中的全部文件，不能执行未校验的脚本或依赖");
            }
        }
        String entrypoint = relative(text(raw.get("entrypoint")));
        if (!hashes.containsKey(entrypoint)
                || !Files.isRegularFile(safeChild(packageRoot, entrypoint)))
            throw bad("平台执行入口不在文件摘要清单中");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", version);
        manifest.put("arch", arch);
        manifest.put("dbTypes", dbTypes);
        manifest.put("purposes", purposes);
        manifest.put("offlineComplete", true);
        manifest.put("operationsProtocol", 1);
        manifest.put("deployment", "single-compose");
        manifest.put("entrypoint", entrypoint);
        manifest.put("files", hashes);
        Path content = getPath(record).getParent().resolve("content");
        manifest.put("packageRoot", content.relativize(packageRoot).toString().replace('\\', '/'));
        if (raw.containsKey("name")) manifest.put("name", boundedName(raw.get("name")));
        String minimum = null;
        for (String key :
                List.of(
                        "minimumUpgradeSource",
                        "minUpgradeVersion",
                        "minimumSourceVersion",
                        "minSourceVersion",
                        "minimumUpgradeVersion"))
            if (raw.containsKey(key)) {
                String candidate = version(raw.get(key));
                if (!candidate.substring(0, 3).equals(version.substring(0, 3)))
                    throw bad("普通升级最低来源版本必须与目标主版本一致");
                if (minimum != null && !minimum.equals(candidate)) throw bad("清单中的最低升级来源版本声明不一致");
                minimum = candidate;
            }
        if (purposes.contains("upgrade") && minimum == null)
            throw bad("升级介质必须声明 minimumUpgradeSource 最低来源版本");
        if (minimum != null) {
            manifest.put("minimumUpgradeSource", minimum);
            manifest.put("minSourceVersion", minimum);
        }
        Map<String, Object> result = cleanRecord(record);
        result.putAll(manifest); // Only the explicit non-secret whitelist above is promoted.
        result.put("manifest", manifest);
        result.put("manifestSource", source);
        result.put("contentRoot", packageRoot.toString());
        result.put("status", "已校验");
        result.put("installSupported", true);
        result.put("publisherVerified", false);
        result.put("validationBasis", "sha256-and-explicit-adapter-manifest");
        result.put("validationMessage", "已核对全部文件摘要、兼容声明和平台执行协议；执行前仍需检查目标环境。");
        return result;
    }

    private Map<String, Object> builtinPackage(Map<String, Object> record, Path root)
            throws Exception {
        return builtinPackage(record, root, Map.of());
    }

    private Map<String, Object> builtinPackage(
            Map<String, Object> record, Path root, Map<String, Object> supplied) throws Exception {
        Map<String, Object> manifest = DataEaseOfflinePackage.inspect(root, json);
        if (supplied.containsKey("minimumUpgradeSource")) {
            String minimum = version(supplied.get("minimumUpgradeSource"));
            if (!minimum.substring(0, 3)
                    .equals(String.valueOf(manifest.get("version")).substring(0, 3)))
                throw bad("升级最低来源版本必须与离线包主版本一致");
            manifest.put("minimumUpgradeSource", minimum);
            manifest.put("purposes", List.of("install", "upgrade", "restore"));
        }
        Path content = getPath(record).getParent().resolve("content");
        manifest.put("packageRoot", content.relativize(root).toString().replace('\\', '/'));
        Map<String, Object> result = cleanRecord(record);
        result.putAll(manifest);
        result.put("manifest", manifest);
        result.put("contentRoot", root.toString());
        result.put("manifestSource", "builtin");
        result.put("status", "已校验");
        result.put("installSupported", true);
        result.put("publisherVerified", false);
        result.put("validationBasis", "docker-save-metadata-and-all-file-sha256");
        result.put("validationMessage", "已核对单机Compose介质的目录、镜像及驱动；平台使用内置MySQL适配器部署，完成后隔离验收。");
        return result;
    }

    private Map<String, Object> plugin(
            Map<String, Object> record, Path content, Map<String, Object> raw, String source)
            throws Exception {
        Map<String, Object> result = cleanRecord(record);
        Map<String, Object> nativeInfo = nativePlugin(content);
        raw = new LinkedHashMap<>(raw);
        if (!nativeInfo.isEmpty()) {
            for (String key : List.of("version", "module", "flag", "requireVersion", "major")) {
                if (raw.containsKey(key) && !Objects.equals(raw.get(key), nativeInfo.get(key)))
                    return unavailable(result, "校验失败", "补充清单与JAR内的插件身份或兼容声明不一致");
                raw.put(key, nativeInfo.get(key));
            }
        }
        String pluginVersion = text(raw.get("version")), major = text(raw.get("major"));
        if (!pluginVersion.matches("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")
                || !Set.of("V2", "V3").contains(major))
            return unavailable(result, "校验失败", "插件缺少有效版本和 V2 / V3 兼容声明");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", pluginVersion);
        manifest.put("major", major);
        for (String key : List.of("name", "pluginId", "module", "requireVersion", "flag"))
            if (raw.containsKey(key)) manifest.put(key, boundedName(raw.get(key)));
        if (raw.containsKey("minVersion"))
            manifest.put("minVersion", version(raw.get("minVersion")));
        result.putAll(manifest);
        result.put("manifest", manifest);
        result.put("manifestSource", source);
        result.put("status", "已校验");
        boolean installable =
                !nativeInfo.isEmpty()
                        && Set.of("ds", "df", "view", "sync-source", "sync-sink")
                                .contains(manifest.get("flag"));
        result.put("installSupported", installable);
        result.put("publisherVerified", false);
        result.put(
                "validationMessage",
                installable
                        ? "已校验JAR描述、CRC及兼容版本；安装需目标DataEase管理员令牌与有效授权，并通过应用接口登记核验。"
                        : "已校验兼容声明；缺少受支持的原生插件描述，暂不能通过应用接口安装。");
        return result;
    }

    private Map<String, Object> nativePlugin(Path content) throws Exception {
        Path directory = content.resolve("plugin");
        if (!Files.isDirectory(directory)) return Map.of();
        List<Path> descriptors;
        try (var files = Files.list(directory)) {
            descriptors =
                    files.filter(p -> p.getFileName().toString().matches("extensions.*\\.json"))
                            .toList();
        }
        if (descriptors.isEmpty()) return Map.of();
        if (descriptors.size() != 1) throw bad("JAR含多个原生插件描述，无法确定安装身份");
        Map<String, Object> descriptor = readManifest(descriptors.get(0));
        String module = text(descriptor.get("moduleName")),
                minimum = text(descriptor.get("requireVersion"));
        if (!module.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")
                || !minimum.matches("v?[23]\\.\\d+\\.\\d+")) throw bad("插件模块标识或最低DataEase版本无效");
        if (!Files.isRegularFile(directory.resolve(module + ".svg"))) throw bad("原生插件缺少模块图标");
        boolean classes;
        try (var files = Files.walk(content)) {
            classes = files.anyMatch(p -> p.toString().endsWith(".class"));
        }
        if (!classes) throw bad("插件JAR不包含实现类");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("module", module);
        result.put("name", descriptor.getOrDefault("name", module));
        result.put("version", descriptor.get("version"));
        result.put("flag", descriptor.get("flag"));
        result.put("requireVersion", minimum);
        result.put("major", minimum.replaceFirst("^[vV]", "").startsWith("2.") ? "V2" : "V3");
        return result;
    }

    private Path extract(Path file, String digest) throws Exception {
        Path content = file.getParent().resolve("content"),
                marker = file.getParent().resolve("extracted.sha256");
        // Execution may be consuming this tree after verify returns. Never delete a published tree.
        if (Files.isDirectory(content, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(content)
                && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(marker)
                && Files.readString(marker).equals(digest)) return content;
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) throw bad("介质解包缓存状态异常，请重新上传介质");
        Path staging = file.getParent().resolve("unpack-" + UUID.randomUUID());
        OperationsFiles.createPrivateDirectory(staging);
        try {
            ArchiveSupport.extract(file, staging);
            // An unmarked tree can only be left by interrupted first-time extraction.
            ArchiveSupport.deleteTree(content);
            Files.move(staging, content, StandardCopyOption.ATOMIC_MOVE);
            OperationsFiles.writePrivate(
                    marker, digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return content;
        } catch (Exception exception) {
            ArchiveSupport.deleteTree(staging);
            throw bad("归档不完整、包含不安全路径或存在不支持的文件类型，已拒绝使用");
        }
    }

    private Map<String, Object> readManifest(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || Files.size(path) > MAX_MANIFEST)
            throw bad("介质清单路径无效或超过 1MB 上限");
        try {
            Map<String, Object> manifest =
                    json.readValue(
                            Files.readAllBytes(path),
                            new TypeReference<LinkedHashMap<String, Object>>() {});
            if (manifest == null) throw bad("介质清单不能为 null");
            return manifest;
        } catch (IOException exception) {
            throw bad("介质清单不是有效 JSON 对象");
        }
    }

    private Path locateRoot(Path content, Object declared) throws IOException {
        if (declared != null && !text(declared).isBlank() && !text(declared).equals("."))
            return safeChild(content, text(declared));
        try (var children = Files.list(content)) {
            List<Path> top = children.toList();
            return top.size() == 1 && Files.isDirectory(top.get(0), LinkOption.NOFOLLOW_LINKS)
                    ? top.get(0)
                    : content;
        }
    }

    /**
     * Only exact, server-generated artifact locations can be opened; vault keys are outside both
     * namespaces.
     */
    public Path getPath(Map<String, Object> record) throws IOException {
        String id = text(record.get("id"));
        Path expected;
        if (id.matches(UUID_PATTERN)) expected = root.resolve(id).resolve("artifact");
        else if (id.startsWith("BK-") && id.substring(3).matches(UUID_PATTERN))
            expected = backups.resolve(id.substring(3) + ".debackup");
        else throw bad("介质记录标识无效");
        Path declared;
        try {
            declared = Path.of(text(record.get("artifactPath"))).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw bad("介质记录路径无效");
        }
        if (!expected.equals(declared)
                || Files.isSymbolicLink(expected)
                || Files.isSymbolicLink(expected.getParent())
                || !Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS))
            throw bad("介质文件不存在或记录路径不匹配");
        return expected;
    }

    public synchronized void delete(Map<String, Object> record) throws Exception {
        Path file = getPath(record);
        if (file.startsWith(root)) ArchiveSupport.deleteTree(file.getParent());
        else Files.delete(file);
    }

    public static Path safeChild(Path root, String relative) {
        String canonical = relative(relative);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path path = normalizedRoot.resolve(canonical).normalize();
        if (!path.startsWith(normalizedRoot) || path.equals(normalizedRoot)) throw bad("介质清单路径不合法");
        for (Path ancestor = path;
                ancestor != null && ancestor.startsWith(normalizedRoot);
                ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) throw bad("介质清单路径不能经过符号链接");
            if (ancestor.equals(normalizedRoot)) break;
        }
        return path;
    }

    private static String relative(String value) {
        if (value == null
                || value.isBlank()
                || value.chars().anyMatch(character -> character < 32)
                || value.contains("\\")
                || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*")) throw bad("介质清单路径不合法");
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException exception) {
            throw bad("介质清单路径无效");
        }
        for (Path part : path) if (part.toString().equals("..")) throw bad("介质清单不能引用上级目录");
        String result = path.normalize().toString().replace('\\', '/');
        if (result.isBlank() || result.equals(".")) throw bad("介质清单必须引用具体文件或目录");
        return result;
    }

    private static String version(Object value) {
        String version = text(value);
        if (!version.matches("v?[23]\\.\\d{1,8}\\.\\d{1,8}"))
            throw bad("清单须声明精确的 V2 / V3 版本，例如 v3.0.0");
        return version.startsWith("v") ? version : "v" + version;
    }

    private static List<String> strings(Object value, String label) {
        if (!(value instanceof List<?> values)
                || values.isEmpty()
                || values.size() > 32
                || values.stream().anyMatch(item -> !(item instanceof String)))
            throw bad(label + "清单无效");
        List<String> result = values.stream().map(String::valueOf).distinct().toList();
        if (result.size() != values.size()) throw bad(label + "清单存在重复值");
        return result;
    }

    private static String boundedName(Object value) {
        String name = text(value);
        if (name.isBlank()
                || name.length() > 200
                || name.chars().anyMatch(character -> character < 32)) throw bad("清单名称无效");
        return name;
    }

    private static String filename(String value) {
        if (value == null || value.isBlank()) return "archive";
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank()
                || name.length() > 255
                || name.chars().anyMatch(character -> character < 32)) throw bad("上传文件名无效");
        return name;
    }

    private static String text(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private static String size(long size) {
        return size >= 1024L * 1024 * 1024
                ? String.format(Locale.ROOT, "%.2f GB", size / (1024d * 1024 * 1024))
                : size >= 1024L * 1024
                        ? String.format(Locale.ROOT, "%.2f MB", size / (1024d * 1024))
                        : size + " B";
    }

    private static OperationsApiException bad(String message) {
        return OperationsApiException.badRequest(message);
    }

    private static Map<String, Object> cleanRecord(Map<String, Object> record) {
        Map<String, Object> copy = new LinkedHashMap<>(record);
        for (String key :
                List.of(
                        "contentRoot",
                        "entrypoint",
                        "adapter",
                        "images",
                        "manifest",
                        "manifestSource",
                        "installSupported",
                        "validationMessage")) copy.remove(key);
        return copy;
    }

    private static Map<String, Object> unavailable(
            Map<String, Object> record, String status, String message) {
        record.put("status", status);
        record.put("installSupported", false);
        record.put("validationMessage", message);
        return record;
    }
}
