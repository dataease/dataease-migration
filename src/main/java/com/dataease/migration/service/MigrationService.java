package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseConnection;
import com.dataease.migration.model.MigrationRequest;
import com.dataease.migration.model.ServerInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class MigrationService {
    private static final String FILE_ARCHIVE_PREFIX = "/tmp/dataease-files-";
    private static final String[] DATA_DIRECTORIES = {"i18n", "font", "exportData", "map", "geo", "appearance"};

    private final SshCommandExecutor ssh;
    private final DatabaseMigrationSelector databaseMigrator;
    private final PluginMigrationService pluginMigrationService;
    private final Executor migrationExecutor;
    private final Map<String, MigrationJob> jobs = new ConcurrentHashMap<>();

    public MigrationService(SshCommandExecutor ssh, DatabaseMigrationSelector databaseMigrator,
                            PluginMigrationService pluginMigrationService,
                            @Qualifier("migrationExecutor") Executor migrationExecutor) {
        this.ssh = ssh;
        this.databaseMigrator = databaseMigrator;
        this.pluginMigrationService = pluginMigrationService;
        this.migrationExecutor = migrationExecutor;
    }

    public String start(MigrationRequest request) {
        DatabaseConnection.fromJdbcUrl(request.sourceDatabase().jdbcUrl());
        DatabaseConnection.fromJdbcUrl(request.targetDatabase().jdbcUrl());

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
        try {
            job.log("开始迁移任务。");
            localFiles = Files.createTempFile("dataease-files-", ".tar.gz");

            migrateFiles(request.sourceServer(), request.targetServer(), remoteFiles, localFiles, job);
            migrateDatabase(request, job);
            pluginMigrationService.updatePlugins(request.targetDatabase(), job);
            job.log("迁移完成。");
        } catch (Exception e) {
            job.log("迁移失败：" + safeMessage(e));
        } finally {
            cleanup(request.sourceServer(), remoteFiles, job);
            cleanup(request.targetServer(), remoteFiles, job);
            deleteQuietly(localFiles);
            job.complete();
        }
    }

    private void migrateFiles(ServerInfo source, ServerInfo target, String remoteArchive, Path localArchive,
                              MigrationJob job) throws Exception {
        job.log("开始迁移文件：i18n、font、exportData、map、geo、appearance 及插件。");
        String sourceDataDirectory = source.installPath() + "/data";
        String directories = String.join(" ", DATA_DIRECTORIES);
        ssh.execute(source, "tar -C " + ShellEscaper.quote(sourceDataDirectory) + " -czf "
                + ShellEscaper.quote(remoteArchive) + " " + directories, job);
        job.log("正在从 DataEase 2.0 服务器下载文件归档。");
        ssh.download(source, remoteArchive, localArchive);
        job.log("正在上传文件归档到 DataEase 3.0 服务器。");
        ssh.upload(target, localArchive, remoteArchive);
        ssh.execute(target, "mkdir -p " + ShellEscaper.quote(target.installPath() + "/data")
                + " && tar -C " + ShellEscaper.quote(target.installPath() + "/data")
                + " -xzf " + ShellEscaper.quote(remoteArchive), job);
        String targetPluginDirectory = target.installPath() + "/data/plugin";
        ssh.execute(target, "mkdir -p " + ShellEscaper.quote(targetPluginDirectory), job);
        for (Path pluginJar : pluginMigrationService.pluginJars()) {
            String targetPlugin = targetPluginDirectory + "/" + pluginJar.getFileName();
            job.log("正在上传插件：" + pluginJar.getFileName() + "。");
            ssh.upload(target, pluginJar, targetPlugin);
        }
        job.log("文件迁移完成。");
    }

    private void migrateDatabase(MigrationRequest request, MigrationJob job) throws Exception {
        job.log("开始通过内置 MySQL JDBC 工具迁移数据库。");
        databaseMigrator.migrate(request.sourceDatabase(), request.targetDatabase(), job);
        job.log("数据库迁移完成。");
    }

    private void cleanup(ServerInfo server, String remoteFiles, MigrationJob job) {
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

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // The operating system will eventually clean the temporary directory.
        }
    }
}
