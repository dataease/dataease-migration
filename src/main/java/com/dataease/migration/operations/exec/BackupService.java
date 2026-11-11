package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.operations.core.OperationsProperties;
import com.dataease.migration.operations.media.ArchiveSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;


@Service
public class BackupService {
    private final Path root;
    private final byte[] key;
    private final ObjectMapper json;
    private final HostCommands host;
    private final ComposeManager compose;
    private final DatabaseArchive database;

    public BackupService(
            OperationsProperties properties,
            ObjectMapper json,
            HostCommands host,
            ComposeManager compose,
            DatabaseArchive database)
            throws Exception {
        this.json = json;
        this.host = host;
        this.compose = compose;
        this.database = database;
        root = properties.dataDirectory().resolve("backups");
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path keyPath = properties.dataDirectory().resolve("backup.key");
        if (!Files.exists(keyPath)) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            Files.createFile(
                    keyPath,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.write(keyPath, bytes);
        }
        key = Files.readAllBytes(keyPath);
        if (key.length != 32) throw new IllegalStateException("备份密钥文件无效");
        // The data directory process lock excludes any active job from another JVM.
        try (var entries = Files.list(root)) {
            for (Path path : entries.filter(path -> List.of(".work-", ".verify-", ".restore-", ".restore-check-", ".plugin-coverage-", ".import-", ".export-")
                    .stream().anyMatch(prefix -> path.getFileName().toString().startsWith(prefix))).toList())
                ArchiveSupport.deleteTree(path);
        }
    }

    public Map<String, Object> create(
            Map<String, Object> i,
            Map<String, String> c,
            String scope,
            boolean images,
            Consumer<String> log)
            throws Exception {
        if (!Set.of("数据备份", "资源备份", "整体备份").contains(scope))
            throw new IllegalArgumentException("不支持的备份范围");
        if (images) throw new IllegalArgumentException("首版备份不打包容器镜像，请保留匹配版本离线介质");
        boolean full = scope.equals("整体备份"),
                data = !scope.equals("资源备份"),
                resources = !scope.equals("数据备份");
        String id = UUID.randomUUID().toString();
        Path temp = Files.createTempDirectory(root, ".work-");
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
        Path payload = temp.resolve("payload");
        Files.createDirectories(payload);
        try {
            List<String> roots = List.of();
            if (data) database.exportDatabase(i, c, payload, log);
            if (resources) {
                roots = resourceRoots(i, c, full);
                if (roots.isEmpty()) throw new IllegalArgumentException("安装目录未发现可备份资源");
                archiveResources(i, c, roots, payload.resolve("resources.tar.gz"));
                log.accept("已归档并验证资源与部署配置");
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("formatVersion", 1);
            manifest.put("id", "BK-" + id);
            manifest.put("kind", "backup");
            manifest.put("name", text(i, "name", "DataEase") + " · " + scope);
            manifest.put("scope", scope);
            for (String field : List.of("version", "arch", "dbType"))
                manifest.put(field, i.get(field));
            if (data) manifest.put("sharedEngineRoute", SharedEngineConfiguration.snapshotRoute(c));
            String databaseIdentity = "";
            try {
                databaseIdentity = identity(i, c);
            } catch (Exception failure) {
                if (data) throw failure;
            }
            manifest.put("databaseIdentity", databaseIdentity);
            manifest.put("instanceId", i.get("id"));
            manifest.put("instanceName", i.get("name"));
            manifest.put("instanceSnapshot", snapshot(i));
            manifest.put("environmentConfig", i.getOrDefault("environmentConfig", Map.of()));
            manifest.put("recoveryPoint", Instant.now().toString());
            manifest.put("date", Instant.now().toString());
            manifest.put("consistent", text(i, "status", "").equals("已停止") ? "pause" : "online");
            manifest.put("resourceRoots", roots);
            manifest.put("mirror", false);
            manifest.put("encrypted", true);
            manifest.put("drillStatus", "未演练");
            manifest.put("storageHost", "operations-platform");
            manifest.put("storageLabel", "运维平台备份库（物理位置待确认）");
            manifest.put("offsite", null);
            manifest.put("coverage", coverage(scope, roots));
            Map<String, String> checksums = new LinkedHashMap<>();
            try (var files = Files.walk(payload)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                    checksums.put(payload.relativize(file).toString(), ArchiveSupport.sha256(file));
            }
            manifest.put("files", checksums);
            json.writeValue(payload.resolve("manifest.json").toFile(), manifest);
            Path zip = temp.resolve("payload.zip");
            ArchiveSupport.zip(payload, zip);
            Path artifact = root.resolve(id + ".debackup");
            encrypt(zip, artifact);
            Map<String, Object> record = new LinkedHashMap<>(manifest);
            record.put("artifactPath", artifact.toString());
            record.put("sha256", ArchiveSupport.sha256(artifact));
            record.put("sizeBytes", Files.size(artifact));
            record.put("size", Files.size(artifact) + " B");
            record.put("status", "已校验");
            verify(record);
            log.accept("备份已完成AES-GCM加密、文件清单和SHA-256校验");
            return record;
        } finally {
            ArchiveSupport.deleteTree(temp);
        }
    }

    public Map<String, Object> verify(Map<String, Object> record) throws Exception {
        Path temp = Files.createTempDirectory(root, ".verify-");
        try {
            Map<String, Object> manifest = unpack(record, temp);
            Map<String, Object> result = new LinkedHashMap<>(manifest);
            result.put("artifactPath", artifact(record).toString());
            result.put("sha256", ArchiveSupport.sha256(artifact(record)));
            result.put("sizeBytes", Files.size(artifact(record)));
            result.put("size", Files.size(artifact(record)) + " B");
            result.put("status", "已校验");
            for (String field : List.of("name", "drillStatus", "offsite", "mirror", "storageHost", "storageLabel"))
                if (record.containsKey(field)) result.put(field, record.get(field));
            return result;
        } finally {
            ArchiveSupport.deleteTree(temp);
        }
    }

    public Optional<Boolean> pluginRegistryPresent(Map<String, Object> record) throws Exception {
        Path temporary = Files.createTempDirectory(root, ".plugin-coverage-");
        try {
            unpack(record, temporary);
            Path metadata = temporary.resolve("database.json");
            if (!Files.isRegularFile(metadata)) return Optional.empty();
            Map<?, ?> snapshot = json.readValue(metadata.toFile(), Map.class);
            if (!(snapshot.get("tables") instanceof List<?> tables))
                throw new IllegalArgumentException("备份缺少数据库表清单");
            return Optional.of(tables.stream().anyMatch(value -> value instanceof Map<?, ?> table
                    && "xpack_plugin".equalsIgnoreCase(String.valueOf(table.get("name")))));
        } finally { ArchiveSupport.deleteTree(temporary); }
    }

    public void validateRestore(
            Map<String, Object> record, Map<String, Object> target, Map<String, String> credentials)
            throws Exception {
        Path temporary = Files.createTempDirectory(root, ".restore-check-");
        try {
            Map<String, Object> manifest = unpack(record, temporary);
            validateIdentity(manifest, target, credentials, record.containsKey("importedAt"));
            if (!"资源备份".equals(manifest.get("scope"))) {
                SharedEngineConfiguration.restoreRoute(manifest, target, credentials);
                if (!text(target, "applicationService", "").isBlank()
                        && !Boolean.FALSE.equals(target.get("metadataDatabasePresent")))
                    database.requireNoEnabledEvents(target, credentials);
            }
            if (!text(target, "applicationService", "").isBlank()
                    && !"数据备份".equals(manifest.get("scope"))) {
                @SuppressWarnings("unchecked")
                List<String> incoming = (List<String>) manifest.get("resourceRoots");
                validateResourceBounds(
                        target,
                        credentials,
                        incoming.stream()
                                .filter(relative -> relative.startsWith("data/"))
                                .toList());
            }
        } finally {
            ArchiveSupport.deleteTree(temporary);
        }
    }

    @SuppressWarnings("unchecked")
    public void restore(
            Map<String, Object> record,
            Map<String, Object> target,
            Map<String, String> c,
            Consumer<String> log)
            throws Exception {
        Path temp = Files.createTempDirectory(root, ".restore-");
        try {
            Map<String, Object> manifest = unpack(record, temp);
            String scope = String.valueOf(manifest.get("scope"));
            if (!Objects.equals(manifest.get("version"), target.get("version"))
                    || !Objects.equals(manifest.get("arch"), target.get("arch"))
                    || !Objects.equals(manifest.get("dbType"), target.get("dbType")))
                throw new IllegalArgumentException("还原目标版本、架构和数据库必须与备份一致");
            validateIdentity(manifest, target, c, record.containsKey("importedAt"));
            com.dataease.migration.model.DatabaseConnection previousRoute =
                    scope.equals("资源备份") ? null
                            : SharedEngineConfiguration.restoreRoute(manifest, target, c);
            String app = compose.containerId(target, c, text(target, "applicationService", ""));
            if (!app.isBlank()
                    && Boolean.TRUE.equals(
                            ((Map<?, ?>) compose.container(target, c, app).get("State"))
                                    .get("Running")))
                throw new IllegalStateException("目标应用仍在运行，不能覆盖");
            Path extracted = temp.resolve("resources");
            List<String> selected = new ArrayList<>();
            Map<String, String> runtime = new LinkedHashMap<>(c);
            if (!scope.equals("数据备份")) {
                // Read the target's application-side datasource before overwriting configuration.
                // Its container DNS/port and app password differ from the platform management JDBC
                // route.
                compose.inspect(target, runtime);
                ArchiveSupport.extract(temp.resolve("resources.tar.gz"), extracted);
                for (String relative : (List<String>) manifest.get("resourceRoots")) {
                    safeRelative(relative);
                    if (relative.startsWith("data/")) selected.add(relative);
                    else if (relative.equals("conf") || relative.equals("config")) {
                        try (var files = Files.walk(extracted.resolve(relative))) {
                            for (Path file : files.filter(Files::isRegularFile).toList()) {
                                String item = extracted.relativize(file).toString();
                                if (!Set.of(
                                                "conf/mysql.env",
                                                "conf/my.cnf",
                                                "config/mysql.env",
                                                "config/my.cnf")
                                        .contains(item)) selected.add(item);
                            }
                        }
                    }
                }
                validateResourceBounds(
                        target, c, selected); // BEFORE any database DROP or file deletion.
                for (String file :
                        List.of(
                                "conf/application.yml",
                                "config/application.yml",
                                "conf/application.yaml",
                                "config/application.yaml",
                                "conf/application-mysql.yml",
                                "config/application-mysql.yml",
                                "conf/application-mysql.yaml",
                                "config/application-mysql.yaml"))
                    patchDatabase(extracted.resolve(file), target, runtime);
            }
            if (!scope.equals("资源备份")) {
                // Data-only restores also need the target application's actual runtime route.
                if (scope.equals("数据备份")) compose.inspect(target, runtime);
                database.restoreDatabase(target, c, temp, log);
                new SharedEngineConfiguration(json).relocate(previousRoute, target, runtime);
            }
            if (!scope.equals("数据备份")) replaceResources(target, c, extracted, selected, log);
            log.accept("已还原备份内容；保留目标运行端数据库连接及数据库卷，目标尚未开放业务访问");
        } finally {
            ArchiveSupport.deleteTree(temp);
        }
    }

    private static String identity(Map<String, Object> instance, Map<String, String> credentials)
            throws Exception {
        if (!text(instance, "dbType", "MySQL").equals("MySQL")) return "";
        try (java.sql.Connection connection = DatabaseArchive.connect(instance, credentials);
                java.sql.Statement statement = connection.createStatement();
                java.sql.ResultSet result =
                        statement.executeQuery("SELECT @@server_uuid,DATABASE()")) {
            result.next();
            return result.getString(1) + "/" + result.getString(2);
        }
    }

    private void validateIdentity(
            Map<String, Object> manifest,
            Map<String, Object> target,
            Map<String, String> credentials, boolean imported)
            throws Exception {
        boolean rehearsal =
                "rehearsal"
                        .equals(
                                text(
                                        target,
                                        "restorePurpose",
                                        text(target, "operationPurpose", "")));
        Map<?, ?> snapshot = manifest.get("instanceSnapshot") instanceof Map<?, ?> s ? s : Map.of();
        boolean sameId = Objects.equals(manifest.get("instanceId"), target.get("id"));
        String originalHost = text(snapshot, "hostIdentity", "");
        // A fresh platform assigns new record IDs. Rebind only an imported backup whose
        // physical Docker host, exact deployment directory and live MySQL identity all match.
        if (!sameId && imported && !originalHost.isBlank()
                && originalHost.equals(text(target, "hostIdentity", ""))
                && path(text(snapshot, "path", "")).equals(path(text(target, "path", "")))
                && !text(manifest, "databaseIdentity", "").isBlank()
                && text(manifest, "databaseIdentity", "").equals(text(target, "databaseIdentity", ""))
                && !text(target, "applicationService", "").isBlank()
                && text(manifest, "databaseIdentity", "").equals(identity(target, credentials)))
            sameId = true;

        if (!sameId
                && !originalHost.isBlank()
                && originalHost.equals(text(target, "hostIdentity", ""))) {
            Path originalPath = Path.of(path(text(snapshot, "path", ""))),
                    targetPath = Path.of(path(text(target, "path", "")));
            if (originalPath.startsWith(targetPath) || targetPath.startsWith(originalPath))
                throw new IllegalArgumentException("恢复目标与来源实际指向同一部署目录，已阻止异实例覆盖");
        }
        if (rehearsal && sameId && !"rehearsal".equals(text(snapshot, "environmentPurpose", "")))
            throw new IllegalArgumentException("恢复演练不能覆盖来源生产实例");
        if (text(target, "applicationService", "").isBlank())
            return; // The new host is rechecked after installation, before restore writes.
        String original = text(manifest, "databaseIdentity", "");
        if (original.isBlank() && !"资源备份".equals(manifest.get("scope")))
            throw new IllegalArgumentException("备份缺少实际数据库身份，不能安全执行覆盖式还原");
        String actualIdentity;
        if (Boolean.FALSE.equals(target.get("metadataDatabasePresent"))) {
            if (!Boolean.TRUE.equals(target.get("protectionRecovery")) || !sameId
                    || !"整体备份".equals(manifest.get("scope")))
                throw new IllegalArgumentException("缺失元数据库仅允许原失败任务绑定的整体保护恢复");
            var observed = DatabaseArchive.observe(target, credentials);
            if (!observed.identity().equals(original)
                    || !observed.identity().equals(text(target, "databaseIdentity", "")))
                throw new IllegalArgumentException("缺失数据库的实际MySQL服务与原保护备份不一致");
            target.put("metadataDatabasePresent", observed.present());
            actualIdentity = observed.identity();
        } else actualIdentity = identity(target, credentials);
        if (!original.isBlank() && original.equals(actualIdentity) && !sameId)
            throw new IllegalArgumentException("恢复目标实际指向备份来源数据库，已阻止异实例覆盖；请选择独立数据库");
    }

    @SuppressWarnings("unchecked")
    private List<Path> databaseVolumePaths(Map<String, Object> target, Map<String, String> c)
            throws Exception {
        Map<String, Object> config = compose.config(target, c),
                services = (Map<String, Object>) config.get("services");
        Map<String, Object> db =
                services.get(text(target, "databaseService", "")) instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of();
        Set<String> sources = new LinkedHashSet<>();
        if (db.get("volumes") instanceof List<?> volumes)
            for (Object item : volumes)
                if (item instanceof Map<?, ?> volume
                        && "bind".equals(String.valueOf(volume.get("type")))
                        && volume.get("source") != null)
                    sources.add(String.valueOf(volume.get("source")));
        String dbService = text(target, "databaseService", "");
        if (!dbService.isBlank()) {
            String id = compose.containerId(target, c, dbService);
            if (!id.isBlank()) {
                Map<String, Object> current = compose.container(target, c, id);
                if (current.get("Mounts") instanceof List<?> mounts)
                    for (Object item : mounts)
                        if (item instanceof Map<?, ?> mount
                                && "bind".equals(mount.get("Type"))
                                && mount.get("Source") != null) {
                            String source = String.valueOf(mount.get("Source"));
                            if (source.startsWith("/host_mnt/"))
                                source = source.substring("/host_mnt".length());
                            sources.add(source);
                        }
            }
        }
        List<Path> protectedPaths = new ArrayList<>();
        for (String source : sources) {
            String canonical =
                    host.capture(
                            target,
                            c,
                            "if [ -d "
                                    + quote(source)
                                    + " ]; then cd "
                                    + quote(source)
                                    + " && pwd -P; else cd "
                                    + quote(Path.of(source).getParent().toString())
                                    + " && printf '%s/%s' \"$(pwd -P)\" "
                                    + quote(Path.of(source).getFileName().toString())
                                    + "; fi");
            protectedPaths.add(Path.of(canonical).normalize());
        }
        return protectedPaths;
    }

    public void validateMigrationResources(Map<String, Object> source, Map<String, String> sc,
            Map<String, Object> target, Map<String, String> tc) throws Exception {
        List<String> resources = List.of("i18n", "font", "exportData", "map", "geo",
                "appearance", "static-resource", "excel", "plugin").stream()
                .map(name -> "data/" + name).toList();
        // Migration copies and deletes a fixed set of resource directories. They must never
        // overlap a MySQL bind mount, even when a full backup excludes that database directory.
        validateResourceBounds(source, sc, resources);
        validateResourceBounds(target, tc, resources);
    }

    private void validateResourceBounds(
            Map<String, Object> target, Map<String, String> c, List<String> incoming)
            throws Exception {
        List<Path> protectedPaths = databaseVolumePaths(target, c);
        Path base = Path.of(path(text(target, "path", "")));
        for (String relative : incoming) {
            safeRelative(relative);
            Path destination = base.resolve(relative).normalize();
            for (Path protectedPath : protectedPaths)
                if (destination.startsWith(protectedPath) || protectedPath.startsWith(destination))
                    throw new IllegalArgumentException(
                            "备份资源与目标MySQL卷或数据库配置挂载冲突，未执行数据库或文件覆盖：" + relative);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> resourceRoots(Map<String, Object> i, Map<String, String> c, boolean full)
            throws Exception {
        String path = path(text(i, "path", ""));
        host.capture(
                i,
                c,
                "test -d " + quote(path + "/data") + " && test ! -L " + quote(path + "/data"));
        Set<String> excluded = new HashSet<>();
        for (Path protectedPath : databaseVolumePaths(i, c)) {
            String source = protectedPath.toString();
            if (source.equals(path + "/data"))
                throw new IllegalArgumentException("数据库与应用共用data根目录，无法安全区分文件保护范围");
            if (source.startsWith(path + "/data/"))
                excluded.add("data/" + source.substring((path + "/data/").length()).split("/")[0]);
        }
        List<String> roots = new ArrayList<>();
        String children =
                host.capture(
                        i,
                        c,
                        "if [ -d "
                                + quote(path + "/data")
                                + " ]; then find "
                                + quote(path + "/data")
                                + " -mindepth 1 -maxdepth 1 -print; fi");
        for (String child : children.split("\\r?\\n")) {
            if (child.isBlank()) continue;
            if (!child.startsWith(path + "/data/")
                    || child.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("资源路径包含无法归档的文件名");
            String relative = child.substring(path.length() + 1);
            if (!excluded.contains(relative)) roots.add(relative);
        }
        List<String> configFiles =
                new ArrayList<>(List.of("conf", "config", ".env", "install.conf"));
        if (i.get("composeFiles") instanceof List<?> composeFiles)
            for (Object file : composeFiles) configFiles.add(String.valueOf(file));
        else configFiles.add(text(i, "composeFile", "docker-compose.yml"));
        if (full)
            for (String relative : configFiles) {
                String exists =
                        host.capture(
                                i,
                                c,
                                "if [ -e "
                                        + quote(path + "/" + relative)
                                        + " ]; then printf yes; fi");
                if (exists.equals("yes")) roots.add(relative);
            }
        return roots;
    }

    private void archiveResources(
            Map<String, Object> i, Map<String, String> c, List<String> roots, Path output)
            throws Exception {
        String temporary = "/tmp/deops-backup-" + UUID.randomUUID() + ".tar.gz";
        String args = String.join(" ", roots.stream().map(HostCommands::quote).toList());
        try {
            String symlinks =
                    host.capture(
                            i,
                            c,
                            "cd "
                                    + quote(path(text(i, "path", "")))
                                    + " && find "
                                    + args
                                    + " -type l -print");
            if (!symlinks.isBlank())
                throw new IllegalArgumentException("资源中存在符号链接，首版不自动归档；请先确认资源实际边界");
            host.run(
                    i,
                    c,
                    "umask 077; COPYFILE_DISABLE=1 tar --no-xattrs -C "
                            + quote(text(i, "path", ""))
                            + " -czf "
                            + quote(temporary)
                            + " -- "
                            + args,
                    line -> {});
            host.download(i, c, temporary, output);
            Path check = Files.createTempDirectory(output.getParent(), "resource-check-");
            try {
                ArchiveSupport.extract(output, check);
            } finally {
                ArchiveSupport.deleteTree(check);
            }
        } finally {
            try {
                host.run(i, c, "rm -f -- " + quote(temporary), line -> {});
            } catch (Exception ignored) {
            }
        }
    }

    private void replaceResources(
            Map<String, Object> i,
            Map<String, String> c,
            Path extracted,
            List<String> roots,
            Consumer<String> log)
            throws Exception {
        String remote = "/tmp/deops-restore-" + UUID.randomUUID();
        Path tar = Files.createTempFile("deops-restore-", ".tar.gz");
        try {
            Process p =
                    new ProcessBuilder(
                                    "tar",
                                    "--no-xattrs",
                                    "-C",
                                    extracted.toString(),
                                    "-czf",
                                    tar.toString(),
                                    ".")
                            .redirectErrorStream(true)
                            .start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0)
                throw new IOException("还原资源重新打包失败");
            host.run(i, c, "umask 077; mkdir -p " + quote(remote), line -> {});
            host.upload(i, c, tar, remote + "/resources.tar.gz");
            host.run(
                    i,
                    c,
                    "mkdir "
                            + quote(remote + "/content")
                            + " && tar -C "
                            + quote(remote + "/content")
                            + " -xzf "
                            + quote(remote + "/resources.tar.gz"),
                    line -> {});
            List<String> current = resourceRoots(i, c, false);
            Set<String> remove = new LinkedHashSet<>(current);
            remove.addAll(roots);
            for (String relative : remove) {
                if (!relative.startsWith("data/") && !roots.contains(relative)) continue;
                safeRelative(relative);
                host.run(
                        i,
                        c,
                        "rm -rf -- " + quote(path(text(i, "path", "")) + "/" + relative),
                        line -> {});
            }
            for (String relative : roots) {
                safeRelative(relative);
                String destination = path(text(i, "path", "")) + "/" + relative;
                host.run(
                        i,
                        c,
                        "mkdir -p "
                                + quote(Path.of(destination).getParent().toString())
                                + " && cp -a -- "
                                + quote(remote + "/content/" + relative)
                                + " "
                                + quote(destination),
                        line -> {});
            }
            log.accept("已替换资源范围，保留数据库容器持久卷");
        } finally {
            Files.deleteIfExists(tar);
            try {
                host.run(i, c, "rm -rf -- " + quote(remote), line -> {});
            } catch (Exception ignored) {
            }
        }
    }

    private static void safeRelative(String value) {
        Path p = Path.of(value);
        if (p.isAbsolute() || value.contains("..") || value.contains("\\") || p.getNameCount() == 0)
            throw new IllegalArgumentException("备份资源路径无效");
    }

    @SuppressWarnings("unchecked")
    private void patchDatabase(
            Path path, Map<String, Object> target, Map<String, String> credentials)
            throws Exception {
        if (!Files.isRegularFile(path)) return;
        Yaml yaml = new Yaml();
        Map<String, Object> config;
        try {
            config = yaml.load(Files.readString(path));
        } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
            throw new IllegalArgumentException("备份中的部署配置YAML格式错误，已中止恢复");
        }
        if (config == null) return;
        Map<String, Object> spring =
                config.get("spring") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : new LinkedHashMap<>();
        Map<String, Object> datasource =
                spring.get("datasource") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : new LinkedHashMap<>();
        if (!credentials.containsKey("runtimeDbUrl")
                || !credentials.containsKey("runtimeDbUser")
                || !credentials.containsKey("runtimeDbPassword"))
            throw new IllegalArgumentException("未识别目标应用侧数据库连接，不能用平台管理地址替代运行端配置");
        datasource.put("url", credentials.get("runtimeDbUrl"));
        datasource.put("username", credentials.get("runtimeDbUser"));
        datasource.put("password", credentials.get("runtimeDbPassword"));
        if (datasource.get("hikari") instanceof Map<?, ?> existing) {
            Map<String, Object> hikari = (Map<String, Object>) existing;
            if (hikari.containsKey("jdbc-url"))
                hikari.put("jdbc-url", credentials.get("runtimeDbUrl"));
            if (hikari.containsKey("username"))
                hikari.put("username", credentials.get("runtimeDbUser"));
            if (hikari.containsKey("password"))
                hikari.put("password", credentials.get("runtimeDbPassword"));
        }
        spring.put("datasource", datasource);
        config.put("spring", spring);
        Files.writeString(path, yaml.dump(config));
    }

    private Path artifact(Map<String, Object> record) {
        Path file = Path.of(text(record, "artifactPath", "")).toAbsolutePath().normalize();
        Path data = root.getParent().toAbsolutePath().normalize();
        if (!(file.startsWith(root.toAbsolutePath().normalize())
                        || file.startsWith(data.resolve("media")))
                || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file)) throw new IllegalArgumentException("备份文件不可访问");
        return file;
    }

    private Map<String, Object> unpack(Map<String, Object> record, Path destination)
            throws Exception {
        Path file = artifact(record);
        String expected = text(record, "sha256", "");
        if (!expected.isBlank()
                && !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        ArchiveSupport.sha256(file).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("备份文件SHA-256不一致");
        Path zip = destination.resolve("payload.zip");
        decrypt(file, zip);
        ArchiveSupport.extract(zip, destination);
        Files.delete(zip);
        Map<String, Object> manifest =
                json.readValue(
                        destination.resolve("manifest.json").toFile(), new TypeReference<>() {});
        if (!Objects.equals(manifest.get("formatVersion"), 1)
                || !(manifest.get("files") instanceof Map<?, ?> checksums))
            throw new IllegalArgumentException("备份清单格式不支持");
        for (var entry : checksums.entrySet()) {
            String relative = String.valueOf(entry.getKey());
            safeRelative(relative);
            Path part = destination.resolve(relative).normalize();
            if (!part.startsWith(destination)
                    || !Files.isRegularFile(part)
                    || !ArchiveSupport.sha256(part).equals(String.valueOf(entry.getValue())))
                throw new IllegalArgumentException("备份内容缺失或摘要不匹配");
        }
        String scope = String.valueOf(manifest.get("scope"));
        if (!Set.of("资源备份", "数据备份", "整体备份").contains(scope))
            throw new IllegalArgumentException("备份声明了不支持的还原范围");
        if (!scope.equals("资源备份") && !Files.isRegularFile(destination.resolve("database.json"))
                || !scope.equals("数据备份")
                        && !Files.isRegularFile(destination.resolve("resources.tar.gz")))
            throw new IllegalArgumentException("备份未包含所声明范围");
        return manifest;
    }

    private void encrypt(Path source, Path output) throws Exception {
        BackupCipher.encrypt(source, output, key);
    }

    private void decrypt(Path source, Path output) throws Exception {
        BackupCipher.decrypt(source, output, key);
    }

    /** Exports only this backup payload, never the platform encryption key or credential vault. */
    public Path exportPortable(Map<String, Object> record, char[] password) throws Exception {
        BackupCipher.validatePassword(password);
        Path temp = Files.createTempDirectory(root, ".export-");
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
        try {
            unpack(record, temp); // Authenticate and verify every payload before re-encryption.
            Path payload = temp.resolve("payload");
            Files.createDirectory(payload);
            try (var paths = Files.list(temp)) {
                for (Path part : paths.filter(path -> !path.equals(payload)).toList())
                    Files.move(part, payload.resolve(part.getFileName()));
            }
            Path plain = temp.resolve("export.zip");
            ArchiveSupport.zip(payload, plain);
            Path output = temp.resolve("backup.debackup");
            BackupCipher.export(plain, output, password);
            // Retain only the encrypted response until the HTTP stream has closed.
            try (var paths = Files.list(temp)) {
                for (Path part : paths.filter(path -> !path.equals(output)).toList())
                    ArchiveSupport.deleteTree(part);
            }
            return output;
        } catch (Exception failure) {
            ArchiveSupport.deleteTree(temp);
            throw failure;
        }
    }

    public Map<String, Object> importBackup(Map<String, Object> record, char[] password) throws Exception {
        Path file = artifact(record);
        String importedDigest = ArchiveSupport.sha256(file);
        if (!importedDigest.equals(text(record, "sha256", "")))
            throw new IllegalArgumentException("导入备份文件摘要已变化。");
        if (BackupCipher.portable(file)) {
            Path temp = Files.createTempDirectory(root, ".import-");
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
            try {
                Path plain = temp.resolve("payload.zip"), managed = temp.resolve("managed.debackup");
                BackupCipher.importFile(file, plain, password);
                encrypt(plain, managed);
                Map<String, Object> candidate = new LinkedHashMap<>(record);
                candidate.put("artifactPath", managed.toString());
                candidate.put("sha256", ArchiveSupport.sha256(managed));
                verify(candidate); // Nothing is adopted before manifest and checksums pass.
                Files.move(managed, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                record.put("sha256", ArchiveSupport.sha256(file));
            } finally {
                ArchiveSupport.deleteTree(temp);
            }
        }
        Map<String, Object> verified = verify(record);
        for (String field : List.of("id", "kind", "artifactPath", "fileName"))
            if (record.containsKey(field)) verified.put(field, record.get(field));
        verified.put("importSha256", importedDigest);
        verified.put("importedAt", Instant.now().toString());
        verified.put("drillStatus", "未演练");
        verified.put("offsite", null);
        verified.put("mirror", false);
        verified.put("storageHost", "operations-platform");
        verified.put("storageLabel", "运维平台备份库（物理位置待确认）");
        return verified;
    }

    private static Map<String, Object> snapshot(Map<String, Object> i) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field :
                List.of(
                        "id",
                        "name",
                        "environment",
                        "host",
                        "path",
                        "version",
                        "arch",
                        "port",
                        "dbType",
                        "dbHost",
                        "dbPort",
                        "dbName",
                        "dbUser",
                        "connection",
                        "environmentConfig",
                        "applicationService",
                        "databaseService",
                        "composeFile",
                        "composeFiles",
                        "composeProject",
                        "serviceCount",
                        "databaseIdentity",
                        "hostIdentity",
                        "environmentPurpose"))
            if (i.containsKey(field)) result.put(field, i.get(field));
        return result;
    }

    private static List<Map<String, Object>> coverage(String scope, List<String> roots) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean full = scope.equals("整体备份");
        for (String name : List.of("metadata", "engine", "files", "plugins", "config")) {
            boolean included =
                    switch (name) {
                        case "metadata", "engine" -> !scope.equals("资源备份");
                        case "files", "plugins" -> !scope.equals("数据备份");
                        default -> full;
                    };
            rows.add(
                    Map.of(
                            "id",
                            name,
                            "name",
                            Map.of(
                                            "metadata",
                                            "元数据库",
                                            "engine",
                                            "数据引擎中的数据",
                                            "files",
                                            "资源文件与驱动",
                                            "plugins",
                                            "插件文件与登记信息",
                                            "config",
                                            "部署配置与恢复密钥")
                                    .get(name),
                            "mode",
                            included ? "included" : "excluded",
                            "required",
                            full,
                            "location",
                            "备份归档清单",
                            "detail",
                            name.equals("engine") ? "首版仅支持与元数据库共库的数据引擎" : "按归档实际内容核验"));
        }
        return rows;
    }
}
