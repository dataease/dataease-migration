package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseConnection;
import com.dataease.migration.model.MigrationRequest;
import com.dataease.migration.model.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 编排一次完整的 V2 到 V3 迁移。
 *
 * <p>源端和目标端各自判断为本地文件访问或 SSH 访问，文件传输完成后再依次执行数据库复制、
 * 通用升级、插件升级和同步管理专项升级。这个顺序不能交换：后续步骤依赖前一步创建的 V3 表结构，
 * 且任一步失败都应阻止尚未开始的后续转换。</p>
 */
@Service
public class MigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationService.class);
    private static final String FILE_ARCHIVE_PREFIX = "/tmp/dataease-files-";
    private static final String SYNC_TASK_LOG_DIRECTORY = "logs/sync-task/task-handler-log";
    /**
     * V2 持久化数据目录。不同版本或部署方式可能缺少其中部分目录，所以打包时按实际存在情况选择；
     * excel 必须保留，否则 Excel 数据集的原始上传文件会在数据库迁移成功后丢失。
     */
    private static final String[] DATA_DIRECTORIES = {
            "i18n", "font", "exportData", "map", "geo", "appearance", "static-resource", "excel"
    };

    private final SshCommandExecutor ssh;
    private final DataEaseVersionValidator dataEaseVersionValidator;
    private final DatabaseMigrationSelector databaseMigrator;
    private final TargetDatabaseUpgradeService targetDatabaseUpgradeService;
    private final PluginMigrationService pluginMigrationService;
    private final SyncManagementMigrationService syncManagementMigrationService;
    private final Executor migrationExecutor;
    private final boolean copySyncTaskLogs;
    private final Map<String, MigrationJob> jobs = new ConcurrentHashMap<>();

    public MigrationService(SshCommandExecutor ssh, DataEaseVersionValidator dataEaseVersionValidator,
                            DatabaseMigrationSelector databaseMigrator,
                            TargetDatabaseUpgradeService targetDatabaseUpgradeService,
                            PluginMigrationService pluginMigrationService,
                            SyncManagementMigrationService syncManagementMigrationService,
                            @Qualifier("migrationExecutor") Executor migrationExecutor,
                            @Value("${migration.files.copy-sync-task-logs:false}") boolean copySyncTaskLogs) {
        this.ssh = ssh;
        this.dataEaseVersionValidator = dataEaseVersionValidator;
        this.databaseMigrator = databaseMigrator;
        this.targetDatabaseUpgradeService = targetDatabaseUpgradeService;
        this.pluginMigrationService = pluginMigrationService;
        this.syncManagementMigrationService = syncManagementMigrationService;
        this.migrationExecutor = migrationExecutor;
        this.copySyncTaskLogs = copySyncTaskLogs;
        LOGGER.info("同步任务物理日志复制已{}；启动参数：--migration.files.copy-sync-task-logs={}",
                copySyncTaskLogs ? "启用" : "关闭", copySyncTaskLogs);
    }

    public String start(MigrationRequest request) {
        if (request.sourceServer() == null || request.targetServer() == null) {
            throw new IllegalArgumentException("迁移必须填写源端和目标端服务器配置");
        }
        validateServer(request.sourceServer(), "DataEase 2.0");
        validateServer(request.targetServer(), "DataEase 3.0");
        DatabaseConnection sourceDatabase = DatabaseConnection.fromJdbcUrl(request.sourceDatabase().jdbcUrl());
        DatabaseConnection targetDatabase = DatabaseConnection.fromJdbcUrl(request.targetDatabase().jdbcUrl());
        // 目标库会在数据库阶段先被删除；任务入队前拒绝明确相同的库，避免把源数据一起删除。
        if (sameDatabase(sourceDatabase, targetDatabase)) {
            throw new IllegalArgumentException("源数据库和目标数据库不能相同，目标库会被删除并重建");
        }

        String id = UUID.randomUUID().toString();
        MigrationJob job = new MigrationJob();
        jobs.put(id, job);
        migrationExecutor.execute(() -> runMigration(request, job, id));
        return id;
    }

    public MigrationJob getJob(String id) {
        MigrationJob job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("迁移任务不存在或已过期");
        }
        return job;
    }

    private void runMigration(MigrationRequest request, MigrationJob job, String id) {
        Path localFiles = null;
        String remoteFiles = FILE_ARCHIVE_PREFIX + id + ".tar.gz";
        String currentStage = "初始化迁移任务";
        boolean succeeded = false;
        long taskStartedNanos = System.nanoTime();
        long stageStartedNanos;
        try {
            job.log("开始完整迁移任务；源端和目标端将分别自动选择本地文件访问或 SSH 访问。");
            localFiles = Files.createTempFile("dataease-files-", ".tar.gz");

            currentStage = "文件迁移";
            stageStartedNanos = System.nanoTime();
            migrateFiles(request.sourceServer(), request.targetServer(), remoteFiles, localFiles, job);
            logElapsed(job, currentStage, stageStartedNanos);

            currentStage = "数据库迁移";
            stageStartedNanos = System.nanoTime();
            migrateDatabase(request, job);
            logElapsed(job, currentStage, stageStartedNanos);
            // 先完成通用 V3 表结构升级，再写入通用插件，最后执行依赖 V3/JPA 字段的同步管理专项迁移。
            currentStage = "V3 通用数据库结构升级";
            stageStartedNanos = System.nanoTime();
            targetDatabaseUpgradeService.execute(request.targetDatabase(), job);
            logElapsed(job, currentStage, stageStartedNanos);
            currentStage = "通用插件迁移";
            stageStartedNanos = System.nanoTime();
            String targetInstallPath = request.targetServer().installPath();
            pluginMigrationService.updatePlugins(request.targetDatabase(), targetInstallPath, job);
            logElapsed(job, currentStage, stageStartedNanos);
            currentStage = "同步管理专项迁移";
            stageStartedNanos = System.nanoTime();
            syncManagementMigrationService.execute(request.targetDatabase(), targetInstallPath, job);
            logElapsed(job, currentStage, stageStartedNanos);
            job.log("完整迁移完成，总耗时：" + formatElapsed(taskStartedNanos) + "。");
            succeeded = true;
        } catch (Exception e) {
            // 页面展示失败阶段和根因链，服务端日志保留完整堆栈，便于实施和研发分别定位。
            job.log("迁移失败，阶段：" + currentStage + "；原因：" + detailedMessage(e));
            LOGGER.error("迁移任务 {} 在阶段 [{}] 失败", id, currentStage, e);
        } finally {
            cleanup(request.sourceServer(), remoteFiles, job);
            cleanup(request.targetServer(), remoteFiles, job);
            deleteQuietly(localFiles);
            job.complete(succeeded);
        }
    }

    /**
     * 文件迁移固定经过迁移程序所在机器的临时归档，从而用同一流程覆盖本地→本地、本地→远程、
     * 远程→本地和远程→远程四种组合；只有文件访问方式不同，迁移内容保持一致。
     */
    private void migrateFiles(ServerInfo source, ServerInfo target, String remoteArchive, Path localArchive,
                              MigrationJob job) throws Exception {
        dataEaseVersionValidator.validate(source, job);
        job.log("开始迁移文件：i18n、font、exportData、map、geo、appearance、static-resource、excel 及插件。");
        String sourceDataDirectory = source.installPath() + "/data";
        boolean sourceLocal = isLocalHost(source.host());
        boolean targetLocal = isLocalHost(target.host());
        long stepStartedNanos = System.nanoTime();
        if (sourceLocal) {
            job.log("源端地址 " + source.host() + " 属于本机，直接读取本地安装目录，不建立 SSH 连接。");
            executeLocal(buildFileArchiveCommand(sourceDataDirectory, localArchive.toString()), job);
            job.log("本地文件归档完成，大小：" + formatBytes(Files.size(localArchive))
                    + "，耗时：" + formatElapsed(stepStartedNanos) + "。");
        } else {
            ssh.execute(source, buildFileArchiveCommand(sourceDataDirectory, remoteArchive), job);
            logElapsed(job, "源端文件归档", stepStartedNanos);
            job.log("正在从 DataEase 2.0 服务器下载文件归档。");
            stepStartedNanos = System.nanoTime();
            ssh.download(source, remoteArchive, localArchive);
            job.log("文件归档下载完成，大小：" + formatBytes(Files.size(localArchive))
                    + "，耗时：" + formatElapsed(stepStartedNanos) + "。");
        }

        if (targetLocal) {
            job.log("目标端地址 " + target.host() + " 属于本机，直接写入本地安装目录，不建立 SSH 连接。");
            stepStartedNanos = System.nanoTime();
            extractArchiveLocally(target, localArchive, job);
            logElapsed(job, "目标端本地文件解压", stepStartedNanos);
            stepStartedNanos = System.nanoTime();
            copyPluginsLocally(target, job);
            logElapsed(job, "本地插件文件复制", stepStartedNanos);
        } else {
            job.log("正在上传文件归档到 DataEase 3.0 服务器。");
            stepStartedNanos = System.nanoTime();
            ssh.upload(target, localArchive, remoteArchive);
            logElapsed(job, "目标端文件归档上传", stepStartedNanos);
            stepStartedNanos = System.nanoTime();
            ssh.execute(target, "mkdir -p " + ShellEscaper.quote(target.installPath() + "/data")
                    + " && tar -C " + ShellEscaper.quote(target.installPath() + "/data")
                    + " -xzf " + ShellEscaper.quote(remoteArchive), job);
            logElapsed(job, "目标端文件解压", stepStartedNanos);
            String targetPluginDirectory = target.installPath() + "/data/plugin";
            ssh.execute(target, "mkdir -p " + ShellEscaper.quote(targetPluginDirectory), job);
            stepStartedNanos = System.nanoTime();
            for (Path pluginJar : pluginMigrationService.pluginJars()) {
                String targetPlugin = targetPluginDirectory + "/" + pluginJar.getFileName();
                job.log("正在上传插件：" + pluginJar.getFileName() + "。");
                ssh.upload(target, pluginJar, targetPlugin);
            }
            logElapsed(job, "插件文件上传", stepStartedNanos);
        }
        if (copySyncTaskLogs) {
            migrateSyncTaskLogs(source, target, remoteArchive, localArchive, sourceLocal, targetLocal, job);
        } else {
            job.log("未启用同步任务日志复制，跳过 " + SYNC_TASK_LOG_DIRECTORY
                    + "；可在启动时添加 --migration.files.copy-sync-task-logs=true 开启。");
        }
        job.log("文件迁移完成。");
    }

    /**
     * 同步任务物理日志可能远大于业务附件，因此默认不迁移，并在开关启用时使用独立归档步骤。
     * 此时主数据归档已经完成解压，可以安全复用临时文件路径，避免同时保留两份大归档占满磁盘。
     */
    private void migrateSyncTaskLogs(ServerInfo source, ServerInfo target, String remoteArchive, Path localArchive,
                                     boolean sourceLocal, boolean targetLocal, MigrationJob job) throws Exception {
        job.log("开始迁移同步任务日志：" + SYNC_TASK_LOG_DIRECTORY + "。");
        long stepStartedNanos = System.nanoTime();
        String sourceArchive = sourceLocal ? localArchive.toString() : remoteArchive;
        String archiveCommand = buildSyncTaskLogArchiveCommand(source.installPath(), sourceArchive);
        if (sourceLocal) {
            executeLocal(archiveCommand, job);
        } else {
            ssh.execute(source, archiveCommand, job);
            ssh.download(source, remoteArchive, localArchive);
        }
        job.log("同步任务日志归档准备完成，大小：" + formatBytes(Files.size(localArchive))
                + "，耗时：" + formatElapsed(stepStartedNanos) + "。");

        String targetLogParent = target.installPath() + "/logs/sync-task";
        stepStartedNanos = System.nanoTime();
        if (targetLocal) {
            executeLocal("mkdir -p " + ShellEscaper.quote(targetLogParent)
                    + " && tar -C " + ShellEscaper.quote(targetLogParent)
                    + " -xzf " + ShellEscaper.quote(localArchive.toString()), job);
        } else {
            ssh.upload(target, localArchive, remoteArchive);
            ssh.execute(target, "mkdir -p " + ShellEscaper.quote(targetLogParent)
                    + " && tar -C " + ShellEscaper.quote(targetLogParent)
                    + " -xzf " + ShellEscaper.quote(remoteArchive), job);
        }
        logElapsed(job, "同步任务日志目标端合并", stepStartedNanos);
        job.log("同步任务日志迁移完成。");
    }

    private void extractArchiveLocally(ServerInfo target, Path localArchive, MigrationJob job) throws Exception {
        String targetDataDirectory = target.installPath() + "/data";
        executeLocal("mkdir -p " + ShellEscaper.quote(targetDataDirectory)
                + " && tar -C " + ShellEscaper.quote(targetDataDirectory)
                + " -xzf " + ShellEscaper.quote(localArchive.toString()), job);
    }

    private void copyPluginsLocally(ServerInfo target, MigrationJob job) throws Exception {
        Path targetPluginDirectory = Path.of(target.installPath(), "data", "plugin");
        Files.createDirectories(targetPluginDirectory);
        for (Path pluginJar : pluginMigrationService.pluginJars()) {
            Path targetPlugin = targetPluginDirectory.resolve(pluginJar.getFileName());
            job.log("正在复制本地插件：" + pluginJar.getFileName() + "。");
            Files.copy(pluginJar, targetPlugin, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 本地分支复用与 SSH 分支相同的、已完成 shell 引用的归档命令，并把输出写入任务日志。
     * 显式使用 /bin/sh 是为了让命令语法在支持的 macOS/Linux 环境中保持一致。
     */
    private void executeLocal(String command, MigrationJob job) throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start();
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                job.log(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("本地文件操作失败，退出码：" + exitCode);
        }
    }

    /**
     * 构造源端归档命令。使用 shell 位置参数只把真实存在的目录传给 tar：
     * 一方面兼容没有 i18n、appearance 等可选目录的 V2 环境，另一方面所有路径都经过引用处理，
     * 避免安装路径包含空格或特殊字符时被错误拆分。如果所有候选目录都不存在，则判定源安装目录
     * 无效或不完整并中止，不能用空归档伪装成文件迁移成功。
     */
    static String buildFileArchiveCommand(String sourceDataDirectory, String remoteArchive) {
        String directories = String.join(" ", Arrays.stream(DATA_DIRECTORIES)
                .map(ShellEscaper::quote)
                .toList());
        return "cd " + ShellEscaper.quote(sourceDataDirectory) + " || exit 1; "
                + "set --; "
                + "for directory in " + directories + "; do "
                + "if [ -d \"$directory\" ]; then set -- \"$@\" \"$directory\"; "
                + "else echo \"跳过不存在的数据目录：$directory\"; fi; "
                + "done; "
                + "if [ \"$#\" -eq 0 ]; then "
                + "echo \"未找到任何可迁移的 DataEase 2.0 数据目录\" >&2; exit 1; fi; "
                + "tar -czf " + ShellEscaper.quote(remoteArchive) + " \"$@\""
                + " && du -h " + ShellEscaper.quote(remoteArchive);
    }

    static String buildSyncTaskLogArchiveCommand(String sourceInstallPath, String archive) {
        String sourceLogParent = sourceInstallPath + "/logs/sync-task";
        String sourceLogDirectory = sourceInstallPath + "/" + SYNC_TASK_LOG_DIRECTORY;
        return "if [ -d " + ShellEscaper.quote(sourceLogDirectory) + " ]; then "
                + "tar -C " + ShellEscaper.quote(sourceLogParent) + " -czf " + ShellEscaper.quote(archive)
                + " task-handler-log; "
                + "else echo " + ShellEscaper.quote("跳过不存在的同步任务日志目录：" + sourceLogDirectory) + "; "
                + "tar -czf " + ShellEscaper.quote(archive) + " -T /dev/null; fi"
                + " && du -h " + ShellEscaper.quote(archive);
    }

    /**
     * SSH 字段采用条件校验：本地地址不建立 SSH 连接，可留空；远程地址必须提供完整连接信息。
     * 安装目录在两种模式下都会进入 shell 命令，因此无论本地或远程都先执行同一套路径校验。
     */
    private static void validateServer(ServerInfo server, String productName) {
        if (server.host() == null || server.host().isBlank()) {
            throw new IllegalArgumentException(productName + " 服务器 IP 不能为空");
        }
        validateLinuxInstallPath(server.installPath(), productName);
        if (isLocalHost(server.host())) {
            return;
        }
        if (server.port() < 1 || server.port() > 65535) {
            throw new IllegalArgumentException(productName + " SSH 端口必须介于 1 到 65535");
        }
        if (server.username() == null || server.username().isBlank()) {
            throw new IllegalArgumentException(productName + " 远程地址必须填写 SSH 用户名");
        }
        if (server.password() == null || server.password().isBlank()) {
            throw new IllegalArgumentException(productName + " 远程地址必须填写 SSH 密码");
        }
    }

    /**
     * localhost、环回地址以及绑定在当前机器网卡上的地址都视为本地地址。域名解析失败时按远程地址处理，
     * 后续会按远程服务器规则校验 SSH 配置，避免误将不可识别的主机当成本地文件系统。
     */
    static boolean isLocalHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("localhost".equalsIgnoreCase(normalized) || "localhost.".equalsIgnoreCase(normalized)) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(normalized)) {
                if (address.isLoopbackAddress()
                        || (!address.isAnyLocalAddress() && NetworkInterface.getByInetAddress(address) != null)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 无法解析或读取本机网卡时按远程地址处理。
        }
        return false;
    }

    /**
     * 比较源/目标是否明确指向同一库。除主机名文本比较外还比较解析后的地址，避免 localhost 与
     * 127.0.0.1 这类别名绕过删除保护；解析失败时保守退回文本比较，实际连接错误仍由数据库阶段报告。
     */
    static boolean sameDatabase(DatabaseConnection source, DatabaseConnection target) {
        if (source.port() != target.port() || !source.database().equals(target.database())) {
            return false;
        }
        if (source.host().equalsIgnoreCase(target.host())) {
            return true;
        }
        try {
            InetAddress[] sourceAddresses = InetAddress.getAllByName(source.host());
            InetAddress[] targetAddresses = InetAddress.getAllByName(target.host());
            return Arrays.stream(sourceAddresses)
                    .anyMatch(sourceAddress -> Arrays.stream(targetAddresses).anyMatch(sourceAddress::equals));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 校验 DataEase 安装目录使用无歧义的 Linux/macOS 绝对路径。
     */
    private static void validateLinuxInstallPath(String installPath, String productName) {
        if (installPath == null || installPath.isBlank()) {
            throw new IllegalArgumentException(productName + " 安装目录不能为空");
        }
        boolean containsRelativeSegment = Arrays.stream(installPath.split("/", -1))
                .anyMatch(segment -> ".".equals(segment) || "..".equals(segment));
        boolean containsControlCharacter = installPath.chars().anyMatch(Character::isISOControl);
        if ("/".equals(installPath) || !installPath.startsWith("/") || installPath.contains("\\")
                || containsRelativeSegment || containsControlCharacter) {
            throw new IllegalArgumentException(productName + " 安装目录必须是非根目录的规范 Linux 绝对路径");
        }
    }

    private void migrateDatabase(MigrationRequest request, MigrationJob job) throws Exception {
        job.log("开始迁移数据库。");
        databaseMigrator.migrate(request.sourceDatabase(), request.targetDatabase(), job);
        job.log("数据库迁移完成。");
    }

    private void cleanup(ServerInfo server, String remoteFiles, MigrationJob job) {
        // 本地归档由 deleteQuietly 负责；只有远程分支会在服务器 /tmp 中创建同名文件。
        if (isLocalHost(server.host())) {
            return;
        }
        try {
            ssh.execute(server, "rm -f " + ShellEscaper.quote(remoteFiles), job);
        } catch (Exception e) {
            job.log("清理远端临时文件失败：" + safeMessage(e));
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /**
     * 将包装异常和底层异常按发生顺序展示，避免页面只能看到“脚本执行失败”等上层通用信息。
     * 使用有序集合去重，并限制异常链深度，防止驱动产生循环 cause 时日志无限增长。
     */
    private String detailedMessage(Throwable exception) {
        Set<String> reasons = new LinkedHashSet<>();
        Throwable current = exception;
        int depth = 0;
        while (current != null && depth++ < 10) {
            String message = current.getMessage();
            String detail = message == null || message.isBlank()
                    ? current.getClass().getSimpleName()
                    : current.getClass().getSimpleName() + "：" + message;
            reasons.add(detail);
            current = current.getCause();
        }
        return String.join("；根因：", reasons);
    }

    private void logElapsed(MigrationJob job, String operation, long startedNanos) {
        job.log(operation + "耗时：" + formatElapsed(startedNanos) + "。");
    }

    private String formatElapsed(long startedNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (elapsedMillis < 1_000) {
            return elapsedMillis + " 毫秒";
        }
        return String.format(Locale.ROOT, "%.2f 秒", elapsedMillis / 1_000.0);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1_024) {
            return bytes + " B";
        }
        if (bytes < 1_024L * 1_024L) {
            return String.format(Locale.ROOT, "%.2f KiB", bytes / 1_024.0);
        }
        if (bytes < 1_024L * 1_024L * 1_024L) {
            return String.format(Locale.ROOT, "%.2f MiB", bytes / (1_024.0 * 1_024.0));
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1_024.0 * 1_024.0 * 1_024.0));
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 任务结果不应被临时文件清理失败覆盖；操作系统会继续按策略清理临时目录。
        }
    }
}
