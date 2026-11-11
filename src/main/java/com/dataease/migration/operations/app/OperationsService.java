package com.dataease.migration.operations.app;

import com.dataease.migration.operations.core.CredentialVault;
import com.dataease.migration.operations.core.OperationsApiException;
import com.dataease.migration.operations.core.OperationsProperties;
import com.dataease.migration.operations.core.OperationsStore;
import com.dataease.migration.operations.exec.ExecutionService;
import com.dataease.migration.operations.media.MediaService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Single-node durable application coordinator. All mutation eligibility is enforced here. */
@Service
@EnableScheduling
public final class OperationsService {
    private static final Set<String> MUTATIONS =
            Set.of("install", "upgrade", "migrate", "backup", "restore", "plugin", "service", "automation");
    private static final Set<String> KINDS =
            Set.of(
                    "install", "upgrade", "migrate", "backup", "restore", "check", "plugin",
                    "service", "automation");
    private static final Set<String> ACTIVE =
            Set.of(
                    "queued",
                    "running",
                    "pending_acceptance",
                    "accepting",
                    "aborting",
                    "reconciliation_required",
                    "reconciling");
    private static final Set<String> SCOPES = Set.of("整体备份", "数据备份", "资源备份");
    private static final ZoneId PLAN_ZONE = ZoneId.of("Asia/Shanghai");
    private final OperationsStore store;
    private final CredentialVault vault;
    private final ExecutionService execution;
    private final MediaService media;
    private final ObjectMapper mapper;
    private final OperationsProperties properties;
    private final ExecutorService executor =
            new ThreadPoolExecutor(
                    4,
                    4,
                    60,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(128),
                    runnable -> {
                        Thread thread = new Thread(runnable, "operations-task");
                        thread.setDaemon(false);
                        return thread;
                    });

    public OperationsService(
            OperationsStore store,
            CredentialVault vault,
            ExecutionService execution,
            MediaService media,
            ObjectMapper mapper,
            OperationsProperties properties) {
        this.store = store;
        this.vault = vault;
        this.execution = execution;
        this.media = media;
        this.mapper = mapper;
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void recoverInterruptedTasks() {
        for (Map<String, Object> task : store.list("tasks")) {
            String state = text(task, "state");
            if (!Set.of("queued", "running", "aborting", "accepting", "reconciling")
                    .contains(state)) continue;
            boolean neverStarted = "queued".equals(state) && !hasExecutionEvidence(task);
            if (MUTATIONS.contains(text(task, "kind")) && !neverStarted) {
                markReconciliationRequired(
                        text(task, "id"),
                        null,
                        "service_restart",
                        "运维服务在变更执行期间中断，不能确认旧的远端进程或SQL已结束。");
                continue;
            }
            task.put("state", "failed");
            task.put("status", "失败");
            task.put("endedAt", now());
            task.put("failureStage", text(task, "currentStage"));
            task.put(
                    "error",
                    neverStarted
                            ? "运维服务重启；该排队任务没有开始执行，未自动续跑，请重新预检查。"
                            : "只读检查在服务重启时中断，未自动续跑，可重新运行检查。");
            store.put("tasks", text(task, "id"), task);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS))
                properties.retainLockUntilProcessExit();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            properties.retainLockUntilProcessExit();
        }
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String collection :
                List.of(
                        "instances",
                        "packages",
                        "plugins",
                        "backups",
                        "tasks",
                        "downloads",
                        "plans",
                        "healthPlans")) {
            List<Map<String, Object>> records = store.list(collection);
            if (!collection.equals("instances")) Collections.reverse(records);
            result.put(collection, records.stream().map(record -> collection.equals("downloads")
                    ? com.dataease.migration.operations.media.PackageDownloadService.publicView(record)
                    : publicRecord(record)).toList());
        }
        return result;
    }

    public Map<String, Object> probe(Map<String, Object> request) throws Exception {
        Map<String, Object> config = cleanConfig(map(request, "config"));
        requireEnvironment(text(config, "environment"));
        Map<String, String> input = credentials(request.get("credentials"));
        ExecutionService.Inspection found;
        try {
            found = execution.inspect(config, input);
        } catch (Exception error) {
            throw bad("连接识别失败：" + connectionError(error, input));
        }
        Map<String, Object> instance =
                connectionSummary(new LinkedHashMap<>(found.instance()), found.credentials());
        String reference = vault.put(found.credentials());
        String id = id("probe");
        Map<String, Object> record =
                obj(
                        "id",
                        id,
                        "instance",
                        instance,
                        "credentialRef",
                        reference,
                        "expiresAt",
                        Instant.now().plusSeconds(600).toString());
        synchronized (this) {
            store.put("probes", id, record);
        }
        return obj(
                "probeId",
                id,
                "instance",
                publicRecord(instance),
                "expiresAt",
                record.get("expiresAt"));
    }

    public synchronized Map<String, Object> register(Map<String, Object> request) {
        Map<String, Object> probe = store.get("probes", required(request, "probeId"));
        if (expired(probe)) throw bad("接入识别已过期，请重新测试连接。");
        Map<String, Object> instance = new LinkedHashMap<>(map(probe, "instance"));
        instance.put("name", required(request, "name"));
        String environment = required(request, "environment");
        requireEnvironment(environment);
        instance.put("environment", environment);
        rejectDuplicate(instance, null);
        String id = id("instance");
        instance.put("id", id);
        instance.put("credentialRef", probe.get("credentialRef"));
        instance.put("revision", 1);
        instance.put("createdAt", now());
        instance.put("connectionProfile", profile(instance, 1));
        instance.put("environmentConfig", environmentConfig(instance));
        store.put("instances", id, instance);
        store.remove("probes", text(probe, "id"));
        return publicRecord(instance);
    }

    public Map<String, Object> updateConnection(String id, Map<String, Object> request)
            throws Exception {
        Map<String, Object> previous;
        synchronized (this) {
            previous = requireUnlocked(id);
        }
        Map<String, String> updated = new LinkedHashMap<>(secrets(previous));
        Map<String, String> supplied = credentials(request.get("credentials"));
        String oldAuth =
                updated.getOrDefault(
                        "sshAuth",
                        updated.getOrDefault("privateKey", "").isBlank() ? "password" : "key");
        String auth = supplied.getOrDefault("sshAuth", oldAuth);
        if (!"local".equals(text(previous, "connection")) && !auth.equals(oldAuth)) {
            if (auth.equals("password") && supplied.getOrDefault("sshPassword", "").isBlank())
                throw bad("切换为密码认证时，请填写新的 SSH 密码。");
            if (auth.equals("key") && supplied.getOrDefault("privateKey", "").isBlank())
                throw bad("切换为私钥认证时，请选择新的私钥文件。");
        }
        supplied.forEach(
                (key, value) -> {
                    if (!value.isBlank()) updated.put(key, value);
                });
        if (auth.equals("password")) {
            updated.remove("privateKey");
            updated.remove("passphrase");
        } else updated.remove("sshPassword");
        updated.put("sshAuth", auth);
        ExecutionService.Inspection found;
        try {
            found = execution.inspect(previous, updated);
        } catch (Exception error) {
            throw bad("连接更新失败：" + connectionError(error, updated));
        }
        synchronized (this) {
            Map<String, Object> current = requireUnlocked(id);
            if (number(current, "revision") != number(previous, "revision"))
                throw conflict("连接配置已变化，请重新打开维护。");
            String old = text(current, "credentialRef");
            current.putAll(cleanConfig(found.instance()));
            connectionSummary(current, found.credentials());
            current.put("id", id);
            current.put("name", previous.get("name"));
            current.put("environment", previous.get("environment"));
            rejectDuplicate(current, id);
            current.put("credentialRef", vault.put(found.credentials()));
            current.put("revision", number(previous, "revision") + 1);
            current.put("connectionProfile", profile(current, number(current, "revision")));
            store.put("instances", id, current);
            if (!old.isBlank()) vault.delete(old);
            return publicRecord(current);
        }
    }

    public synchronized Map<String, Object> updateEnvironment(
            String id, Map<String, Object> request) {
        Map<String, Object> instance = store.get("instances", id);
        if (isLocked(id)) {
            boolean pendingTarget =
                    store.list("tasks").stream()
                            .anyMatch(
                                    task ->
                                            "pending_acceptance".equals(text(task, "state"))
                                                    && id.equals(text(task, "targetId")));
            boolean changing =
                    store.list("tasks").stream()
                            .anyMatch(
                                    task ->
                                            Set.of(
                                                                    "queued",
                                                                    "running",
                                                                    "accepting",
                                                                    "aborting",
                                                                    "reconciliation_required",
                                                                    "reconciling")
                                                            .contains(text(task, "state"))
                                                    && (id.equals(text(task, "instanceId"))
                                                            || id.equals(text(task, "targetId"))));
            Map<String, Object> requested = new LinkedHashMap<>(map(request, "environmentConfig")),
                    existing = new LinkedHashMap<>(map(instance, "environmentConfig"));
            Object query = requested.remove("acceptanceQuery");
            existing.remove("acceptanceQuery");
            if (!pendingTarget
                    || changing
                    || !request.keySet().equals(Set.of("environmentConfig"))
                    || query == null
                    || !requested.isEmpty()
                            && !Objects.equals(canonical(requested), canonical(existing)))
                throw conflict("待验收实例仅允许维护代表性只读验收查询，其他配置保持锁定。");
            if (String.valueOf(query).length() > 16000) throw bad("验收查询过长。");
            Map<String, Object> updated = new LinkedHashMap<>(map(instance, "environmentConfig"));
            updated.put("acceptanceQuery", String.valueOf(query));
            instance.put("environmentConfig", updated);
            instance.put("revision", number(instance, "revision") + 1);
            store.put("instances", id, instance);
            for (Map<String, Object> task : store.list("tasks"))
                if ("pending_acceptance".equals(text(task, "state"))
                        && id.equals(text(task, "targetId"))) {
                    Map<String, Object> acceptance = new LinkedHashMap<>(map(task, "acceptance"));
                    acceptance.put("automatedPassed", false);
                    acceptance.put("releaseConfirmed", false);
                    acceptance.put("checks", List.of());
                    task.put("acceptance", acceptance);
                    store.put("tasks", text(task, "id"), task);
                }
            return publicRecord(instance);
        }
        if (request.containsKey("name")) instance.put("name", required(request, "name"));
        if (request.containsKey("environment")) {
            requireEnvironment(text(request, "environment"));
            instance.put("environment", request.get("environment"));
        }
        if (request.containsKey("environmentConfig"))
            instance.put("environmentConfig", cleanEnvironment(map(request, "environmentConfig")));
        instance.put("revision", number(instance, "revision") + 1);
        return publicRecord(store.put("instances", id, instance));
    }

    public Map<String, Object> precheck(Map<String, Object> request) throws Exception {
        Map<String, Object> command = normalize(request);
        if (request.get("credentials") instanceof Map<?, ?> input && !input.isEmpty())
            command.put("credentialRef", vault.put(credentials(input)));
        List<Map<String, Object>> checks = checks(command);
        String id = id("precheck");
        Map<String, Object> record =
                obj(
                        "id",
                        id,
                        "command",
                        command,
                        "fingerprint",
                        fingerprint(command),
                        "checks",
                        checks,
                        "passed",
                        passed(checks),
                        "expiresAt",
                        Instant.now().plusSeconds(600).toString(),
                        "sourceInstance",
                        publicRecord(source(command)),
                        "targetInstance",
                        publicRecord(target(command)),
                        "targetName",
                        text(target(command), "name"),
                        "targetId",
                        text(target(command), "id"));
        synchronized (this) {
            store.put("prechecks", id, record);
        }
        return publicRecord(record);
    }

    public Map<String, Object> submit(Map<String, Object> request) throws Exception {
        String precheckId = required(request, "precheckId");
        Map<String, Object> stored;
        synchronized (this) {
            stored = store.get("prechecks", precheckId);
        }
        if (expired(stored) || !bool(stored, "passed")) throw conflict("预检查未通过或已过期，请重新检查。");
        Map<String, Object> command = new LinkedHashMap<>(map(stored, "command"));
        Map<String, Object> candidate = normalize(request);
        if (command.containsKey("credentialRef"))
            candidate.put("credentialRef", command.get("credentialRef"));
        if (command.containsKey("targetHostIdentity"))
            candidate.put("targetHostIdentity", command.get("targetHostIdentity"));
        if (!fingerprint(candidate).equals(text(stored, "fingerprint")))
            throw conflict("操作配置或环境已变化，请重新预检查并确认目标。");
        Map<String, String> provided = credentials(request.get("credentials"));
        Map<String, String> checkedCredentials = secrets(command);
        for (var entry : provided.entrySet())
            if (!entry.getValue().isBlank()
                    && !Objects.equals(entry.getValue(), checkedCredentials.get(entry.getKey())))
                throw conflict("认证信息已变化，请重新预检查。");
        List<Map<String, Object>> checks =
                checks(command); // A token never substitutes a fresh server-side check.
        if (!passed(checks)) throw conflict(firstBlocked(checks));
        synchronized (this) {
            if (expired(store.get("prechecks", precheckId))) throw conflict("预检查已过期。");
            if (!fingerprint(command).equals(text(stored, "fingerprint")))
                throw conflict("环境已变化，请重新检查。");
            Map<String, Object> target = target(command);
            String kind = text(command, "kind");
            if (Set.of("migrate", "restore", "install", "upgrade", "plugin", "automation").contains(kind)
                    && !text(target, "name").equals(text(request, "confirmTargetName")))
                throw bad("请输入完整目标实例名称以确认覆盖。");
            for (String instanceId : operationIds(command)) requireUnlocked(instanceId);
            if (Set.of("install", "restore").contains(kind) && text(command, "targetId").isBlank())
                for (Map<String, Object> existing : store.list("instances"))
                    if (samePhysicalHost(existing, target) || sharesDatabase(existing, target))
                        throw conflict("目标服务器或元数据库已被已接入环境使用，请选择空服务器与独立数据库。");
            if (kind.equals("migrate") && text(command, "targetId").isBlank()) {
                ExecutionService.Inspection found = execution.inspect(target, secrets(command));
                Map<String, Object> manual = new LinkedHashMap<>(found.instance());
                manual.putAll(
                        obj("name", target.get("name"), "environment", target.get("environment")));
                requireEnvironment(text(manual, "environment"));
                rejectDuplicate(manual, null);
                String targetId = id("instance");
                manual.put("id", targetId);
                connectionSummary(manual, found.credentials());
                manual.put("credentialRef", vault.put(found.credentials()));
                manual.put("revision", 1);
                manual.put("createdAt", now());
                manual.put("connectionProfile", profile(manual, 1));
                manual.put("environmentConfig", environmentConfig(manual));
                store.put("instances", targetId, manual);
                command.put("targetId", targetId);
            }
            Map<String, Object> task = queue(command, checks);
            store.remove(
                    "prechecks",
                    precheckId); // Single-use authorization; retries require a fresh snapshot.
            return publicRecord(task);
        }
    }

    private Map<String, Object> queue(
            Map<String, Object> command, List<Map<String, Object>> checks) {
        String kind = text(command, "kind"), id = id("task");
        Map<String, Object> source = source(command), target = target(command);
        if (Set.of("install", "restore").contains(kind) && text(target, "id").isBlank()) {
            target = new LinkedHashMap<>(target);
            target.put("id", id("instance"));
            command.put("newTargetId", target.get("id"));
            Map<String, Object> pending = new LinkedHashMap<>(target);
            pending.put("status", kind.equals("install") ? "待安装" : "恢复准备中");
            pending.put("revision", 1);
            pending.put("credentialRef", command.get("credentialRef"));
            pending.put("createdAt", now());
            store.put("instances", text(target, "id"), pending);
        }
        String targetId = text(target, "id"),
                instanceId = source.isEmpty() ? targetId : text(source, "id");
        Map<String, Object> task =
                obj(
                        "id",
                        id,
                        "kind",
                        kind,
                        "state",
                        "queued",
                        "status",
                        "排队中",
                        "progress",
                        0,
                        "createdAt",
                        now(),
                        "date",
                        now(),
                        "command",
                        command,
                        "precheck",
                        checks,
                        "instanceId",
                        instanceId,
                        "targetId",
                        targetId,
                        "instanceName",
                        source.isEmpty() ? text(target, "name") : text(source, "name"),
                        "targetName",
                        text(target, "name"),
                        "host",
                        text(target, "host"),
                        "instanceSnapshot",
                        source,
                        "targetInstanceSnapshot",
                        target,
                        "dataChanged",
                        false,
                        "stages",
                        plannedStages(kind),
                        "logs",
                        new ArrayList<>(),
                        "scope",
                        text(command, "scope"),
                        "version",
                        text(target, "version"),
                        "accessMode",
                        "isolated",
                        "automation",
                        pausedAutomation());
        String purpose = text(command, "restorePurpose");
        if (purpose.isBlank()) purpose = text(command, "purpose");
        if ("rehearsal".equals(text(target, "environmentPurpose"))
                || "演练".equals(text(target, "tag"))) purpose = "rehearsal";
        task.put("restorePurpose", purpose);
        task.put(
                "title",
                label(kind)
                        + " · "
                        + (kind.equals("migrate") ? text(source, "name") : text(target, "name")));
        if (!text(command, "packageId").isBlank())
            task.put(
                    "packageSnapshot",
                    store.get(
                            kind.equals("plugin") ? "plugins" : "packages",
                            text(command, "packageId")));
        if (!text(command, "backupId").isBlank()) {
            Map<String, Object> backup = store.get("backups", text(command, "backupId"));
            task.put("backupId", backup.get("id"));
            task.put(
                    "backupSource",
                    obj(
                            "id",
                            backup.get("id"),
                            "name",
                            backup.get("name"),
                            "instanceName",
                            backup.get("instanceName"),
                            "version",
                            backup.get("version")));
        }
        store.put("tasks", id, task);
        try {
            executor.submit(() -> execute(id));
        } catch (RejectedExecutionException busy) {
            task.put("state", "failed");
            task.put("status", "失败");
            task.put("error", "任务队列已满或服务正在停止，未执行环境变更。");
            task.put("endedAt", now());
            store.put("tasks", id, task);
            throw conflict("任务队列已满，请稍后重新预检查。");
        }
        return task;
    }

    private void execute(String taskId) {
        Map<String, Object> task = store.get("tasks", taskId), command = map(task, "command");
        String kind = text(task, "kind");
        Consumer<String> log = line -> appendLog(taskId, line);
        try {
            updateTask(taskId, obj("state", "running", "status", "执行中", "startedAt", now()));
            Map<String, Object> source = source(command), target = target(command);
            Map<String, String> sourceCredentials = secrets(source),
                    targetCredentials = targetCredentials(command, target);
            if (kind.equals("migrate")) {
                stage(
                        taskId,
                        "复核两端连接与执行条件",
                        5,
                        () -> {
                            execution.validateMigration(source, target);
                            execution.validateMigrationEndpoints(source, sourceCredentials, target, targetCredentials);
                            execution.validateMigrationSchemas(source, sourceCredentials, target, targetCredentials);
                        });
                stage(
                        taskId,
                        "暂停自动任务并停止两端应用",
                        15,
                        () -> {
                            execution.service(source, sourceCredentials, false, false, log);
                            setInstance(
                                    source, obj("status", "已停止", "automation", pausedAutomation()));
                            execution.service(target, targetCredentials, false, false, log);
                            setInstance(
                                    target, obj("status", "已停止", "automation", pausedAutomation()));
                        });
                stage(
                        taskId,
                        "创建并校验源端保护备份",
                        28,
                        () -> {
                            Map<String, Object> backup =
                                    saveBackup(
                                            execution.backup(
                                                    source, sourceCredentials, "整体备份", false, log),
                                            source,
                                            "迁移源端保护备份",
                                            taskId);
                            updateTask(taskId, obj("sourceBackupId", backup.get("id")));
                        });
                stage(
                        taskId,
                        "创建并校验目标保护备份",
                        43,
                        () -> {
                            Map<String, Object> backup =
                                    saveBackup(
                                            execution.backup(
                                                    target, targetCredentials, "整体备份", false, log),
                                            target,
                                            "迁移目标保护备份",
                                            taskId);
                            updateTask(taskId, obj("targetBackupId", backup.get("id")));
                        });
                stage(
                        taskId,
                        "复制资源、重建数据库并升级结构",
                        55,
                        () -> {
                            verifyMigrationProtection(taskId, source, target);
                            updateTask(taskId, obj("dataChanged", true));
                            execution.migrate(
                                    source,
                                    sourceCredentials,
                                    target,
                                    targetCredentials,
                                    bool(command, "copySyncTaskLogs"),
                                    log);
                        });
                stage(
                        taskId,
                        "隔离启动目标环境",
                        90,
                        () -> execution.service(target, targetCredentials, true, true, log));
                pendingAcceptance(taskId, target, command);
            } else if (kind.equals("backup")) {
                String scope = text(command, "scope");
                boolean pause = !"online".equals(text(command, "consistent"));
                boolean running = !"已停止".equals(text(source, "status"));
                if (pause && running)
                    stage(
                            taskId,
                            "暂停应用写入",
                            10,
                            () -> {
                                execution.service(source, sourceCredentials, false, false, log);
                                setInstance(source, obj("status", "已停止"));
                            });
                stage(
                        taskId,
                        "导出数据、归档资源并校验",
                        30,
                        () -> {
                            Map<String, Object> backup =
                                    saveBackup(
                                            execution.backup(
                                                    source,
                                                    sourceCredentials,
                                                    scope,
                                                    bool(command, "includeImages"),
                                                    log),
                                            source,
                                            "手动备份",
                                            taskId);
                            backup.put("consistent", pause ? "pause" : "online");
                            backup.put(
                                    "name",
                                    text(command, "name").isBlank()
                                            ? text(source, "name") + " · " + scope
                                            : text(command, "name"));
                            store.put("backups", text(backup, "id"), backup);
                            updateTask(taskId, obj("backupId", backup.get("id")));
                        });
                if (pause && running)
                    stage(
                            taskId,
                            "恢复备份前应用状态",
                            90,
                            () -> {
                                execution.service(
                                        source,
                                        sourceCredentials,
                                        true,
                                        "isolated".equals(text(source, "accessMode")),
                                        log);
                                setInstance(source, obj("status", source.get("status")));
                            });
                succeed(taskId);
                try {
                    pruneRetention(text(command, "planId"));
                } catch (Exception cleanupError) {
                    appendLog(taskId, "新备份已完成；旧副本清理失败，已保留旧备份。");
                }
            } else if (kind.equals("restore")) {
                target.put("restorePurpose", text(task, "restorePurpose"));
                Map<String, Object> backup = store.get("backups", text(command, "backupId"));
                boolean protectionRecovery = !text(command, "protectionTaskId").isBlank();
                if (protectionRecovery) {
                    requireProtectionRestore(command, backup, target);
                    execution.verifyBackup(backup);
                    ExecutionService.Inspection actual = execution.inspectRecoveryTarget(target, targetCredentials);
                    target.putAll(actual.instance());
                    target.put("protectionRecovery", true);
                    targetCredentials.putAll(actual.credentials());
                }
                execution.validateRestore(backup, target, targetCredentials);
                if (!text(command, "targetId").isBlank())
                    stage(
                            taskId,
                            "保护现有目标并停止应用",
                            15,
                            () -> {
                                execution.service(target, targetCredentials, false, false, log);
                                setInstance(target, obj("status", "已停止"));
                                if (protectionRecovery) {
                                    // A failed target can have a partial or missing schema. Its
                                    // original verified full backup remains the recovery anchor.
                                    requireProtectionRestore(command, backup, target);
                                    execution.verifyBackup(backup);
                                    updateTask(taskId, obj("targetBackupId", backup.get("id"),
                                            "inheritedProtectionTaskId", command.get("protectionTaskId")));
                                    Map<String, Object> resources = saveBackup(
                                            execution.backupRecoveryResources(target, targetCredentials, log),
                                            target, "保护恢复前资源副本", taskId);
                                    updateTask(taskId, obj("targetResourceBackupId", resources.get("id")));
                                    log.accept("已保留当前资源；沿用原失败任务的整体保护备份，不把损坏目标另存为完整恢复点");
                                    return;
                                }
                                Map<String, Object> protection =
                                        saveBackup(
                                                execution.backup(
                                                        target,
                                                        targetCredentials,
                                                        "整体备份",
                                                        false,
                                                        log),
                                                target,
                                                "还原前保护备份",
                                                taskId);
                                updateTask(taskId, obj("targetBackupId", protection.get("id")));
                            });
                else
                    stage(
                            taskId,
                            "准备独立恢复环境",
                            15,
                            () -> {
                                target.put("operationPurpose", "restore");
                                execution.install(
                                        store.get("packages", text(command, "packageId")),
                                        target,
                                        targetCredentials,
                                        false,
                                        log);
                            });
                if (protectionRecovery && Boolean.FALSE.equals(target.get("metadataDatabasePresent")))
                    stage(taskId, "重建缺失的元数据库", 30, () -> {
                        // Identity, original backup and resource bounds have been checked before
                        // this first database write. Recheck at the destructive boundary.
                        requireProtectionRestore(command, backup, target);
                        updateTask(taskId, obj("dataChanged", true));
                        execution.createMissingProtectionDatabase(target, targetCredentials, backup);
                    });
                if (!text(command, "protectionTaskId").isBlank()
                        && !text(backup, "version").equals(text(target, "version"))) {
                    requireProtectionRestore(command, backup, target);
                    stage(
                            taskId,
                            "回退应用至保护备份版本",
                            35,
                            () -> {
                                updateTask(taskId, obj("dataChanged", true));
                                execution.rollbackApplication(
                                        store.get("packages", required(command, "packageId")),
                                        backup,
                                        target,
                                        targetCredentials,
                                        log);
                            });
                }
                stage(
                        taskId,
                        "还原所选备份内容",
                        45,
                        () -> {
                            updateTask(taskId, obj("dataChanged", true));
                            execution.restore(backup, target, targetCredentials, log);
                        });
                stage(
                        taskId,
                        "隔离启动恢复目标",
                        90,
                        () -> execution.service(target, targetCredentials, true, true, log));
                pendingAcceptance(taskId, target, command);
            } else if (kind.equals("install") || kind.equals("upgrade")) {
                if (kind.equals("upgrade"))
                    stage(
                            taskId,
                            "停服并建立升级保护备份",
                            10,
                            () -> {
                                execution.service(source, sourceCredentials, false, false, log);
                                setInstance(source, obj("status", "已停止"));
                                Map<String, Object> backup =
                                        saveBackup(
                                                execution.backup(
                                                        source,
                                                        sourceCredentials,
                                                        "整体备份",
                                                        false,
                                                        log),
                                                source,
                                                "升级保护备份",
                                                taskId);
                                updateTask(taskId, obj("targetBackupId", backup.get("id")));
                            });
                stage(
                        taskId,
                        "校验并执行离线介质",
                        40,
                        () -> {
                            updateTask(taskId, obj("dataChanged", true));
                            execution.install(
                                    store.get("packages", text(command, "packageId")),
                                    target,
                                    targetCredentials,
                                    kind.equals("upgrade"),
                                    log);
                        });
                stage(
                        taskId,
                        "隔离启动并核对环境",
                        90,
                        () -> execution.service(target, targetCredentials, true, true, log));
                pendingAcceptance(taskId, target, command);
            } else if (kind.equals("plugin")) {
                stage(
                        taskId,
                        "停服并建立插件安装保护备份",
                        10,
                        () -> {
                            execution.service(source, sourceCredentials, false, false, log);
                            setInstance(source, obj("status", "已停止"));
                            Map<String, Object> protection =
                                    saveBackup(
                                            execution.backup(
                                                    source, sourceCredentials, "整体备份", false, log),
                                            source,
                                            "插件安装保护备份",
                                            taskId);
                            updateTask(taskId, obj("targetBackupId", protection.get("id")));
                        });
                stage(
                        taskId,
                        "隔离启动并暂停自动任务",
                        35,
                        () -> execution.service(target, targetCredentials, true, true, log));
                stage(
                        taskId,
                        "调用DataEase安装接口并核验登记",
                        60,
                        () -> {
                            updateTask(taskId, obj("dataChanged", true));
                            Map<String, Object> result =
                                    execution.installPlugin(
                                            store.get("plugins", text(command, "packageId")),
                                            target,
                                            targetCredentials,
                                            secrets(command),
                                            text(map(command, "config"), "apiPrefix"),
                                            log);
                            updateTask(taskId, obj("pluginResult", result));
                        });
                pendingAcceptance(taskId, target, command);
            } else if (kind.equals("check")) {
                stage(
                        taskId,
                        "检查应用、数据库与资源",
                        20,
                        () -> {
                            List<Map<String, Object>> results =
                                    normalizedChecks(execution.health(source, sourceCredentials));
                            Map<String, Object> report =
                                    obj(
                                            "instanceId",
                                            source.get("id"),
                                            "checkedAt",
                                            now(),
                                            "checks",
                                            results);
                            setInstance(
                                    source,
                                    obj(
                                            "healthReport",
                                            report,
                                            "status",
                                            source.get("status"),
                                            "lastCheck",
                                            now(),
                                            "health",
                                            passed(results) ? "正常" : "关注"));
                            updateTask(taskId, obj("report", report));
                        });
                succeed(taskId);
            } else if (kind.equals("automation")) {
                stage(taskId, "复核验收、目标身份与调度清单", 10, () -> {
                    requireAutomationAccepted(source);
                    ExecutionService.Inspection actual = execution.inspect(source, sourceCredentials);
                    if (!versionMatches(source, actual.instance())) throw conflict("实际环境身份已变化，未恢复自动任务。");
                    requireAutomationSnapshot(command, execution.automation(source, sourceCredentials));
                });
                stage(taskId, "更新授权清单并回读实际调度状态", 50, () -> {
                    updateTask(taskId, obj("automationChangeStarted", true));
                    Map<String, Object> result = execution.restoreAutomation(source, sourceCredentials,
                            text(command, "automationSnapshot"), strings(command.get("selectedJobs")));
                    Map<String, Object> flags = new LinkedHashMap<>(pausedAutomation());
                    for (var row : maps(result.get("jobs")))
                        if (bool(row, "enabled") && flags.containsKey(text(row, "category"))) flags.put(text(row, "category"), true);
                    setInstance(source, obj("automation", flags, "automationCheckedAt", now()));
                    updateTask(taskId, obj("automationResult", result));
                    log.accept("已独立回读运行中的调度器：仅所选任务获准运行；未选择及未知任务继续拦截。应用重启后授权失效，需重新核验。");
                });
                succeed(taskId);
            } else if (kind.equals("service")) {
                boolean start = "start".equals(text(command, "action"));
                stage(
                        taskId,
                        start ? "启动应用" : "停止应用",
                        20,
                        () ->
                                execution.service(
                                        source,
                                        sourceCredentials,
                                        start,
                                        "isolated".equals(text(source, "accessMode")),
                                        log));
                setInstance(
                        source,
                        obj(
                                "status",
                                start
                                        ? ("isolated".equals(text(source, "accessMode"))
                                                ? "隔离运行"
                                                : "运行中")
                                        : "已停止", "automation", pausedAutomation()));
                succeed(taskId);
            } else throw bad("该操作尚无经过验证的执行适配器，未执行任何变更。");
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted() || hasInterruptCause(failure);
            try {
                if (MUTATIONS.contains(kind) && uncertainCompletion(failure)) {
                    markReconciliationRequired(
                            taskId,
                            failure,
                            "execution_interrupted",
                            "执行超时、网络断开或中断，平台不能确认远端子进程或SQL已经结束。");
                    appendLog(taskId, "执行结束状态待确认，实例锁已保留；必须先完成管理员现场核查。");
                } else {
                    synchronized (this) {
                        Map<String, Object> latest = store.get("tasks", taskId);
                        latest.put("state", "failed");
                        latest.put("status", "失败");
                        latest.put("endedAt", now());
                        latest.put("failureStage", text(latest, "currentStage"));
                        latest.put("duration", duration(latest));
                        String reason = safeError(failure, command);
                        latest.put(
                                "error",
                                reason
                                        + (kind.equals("automation")
                                                ? " 自动任务状态请以实时清单为准；执行结果未确认时请先现场核查。"
                                                : bool(latest, "dataChanged")
                                                ? " 目标可能已部分写入，请使用本任务目标保护备份恢复。若应用版本发生变化，请先核对并更新连接识别。"
                                                : " 已在目标写入前中止，请核对应用状态后重新预检查。"));
                        List<Map<String, Object>> stages = maps(latest.get("stages"));
                        for (Map<String, Object> stage : stages)
                            if (text(stage, "title").equals(text(latest, "currentStage"))) {
                                stage.put("status", "failed");
                                stage.put("endedAt", now());
                            }
                        latest.put("stages", stages);
                        store.put("tasks", taskId, latest);
                        if (MUTATIONS.contains(kind) && !kind.equals("automation")) {
                            String targetId = text(latest, "targetId");
                            store.find("instances", targetId)
                                    .ifPresent(
                                            instance ->
                                                    setInstance(
                                                            instance,
                                                            obj(
                                                                    "status",
                                                                    "待检查",
                                                                    "health",
                                                                    "未检查",
                                                                    "accessMode",
                                                                    "unknown",
                                                                    "recoveryRequired",
                                                                    bool(instance, "recoveryRequired")
                                                                            || bool(latest, "dataChanged"))));
                        }
                    }
                    appendLog(taskId, "操作失败：" + safeError(failure, command));
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private List<Map<String, Object>> checks(Map<String, Object> command) throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>();
        String kind = text(command, "kind");
        Map<String, Object> source = source(command), target = target(command);
        for (String instanceId : operationIds(command)) {
            boolean busy = isLocked(instanceId);
            Map<String, Object> lock = check("实例互斥", busy ? "该实例有执行中任务或待验收变更" : "没有互斥任务", !busy);
            if (busy) store.list("tasks").stream().filter(task -> ACTIVE.contains(text(task, "state"))
                    && (instanceId.equals(text(task, "instanceId")) || instanceId.equals(text(task, "targetId"))))
                .findFirst().ifPresent(task -> lock.put("remediation", obj("type", "task", "taskId", task.get("id"),
                        "label", "查看占用任务", "hint", "该实例被执行中或待验收任务占用。请先处理该任务，平台不会并发覆盖实例。")));
            checks.add(lock);
        }
        if (!source.isEmpty()) {
            try {
                ExecutionService.Inspection found = execution.inspect(source, secrets(source));
                boolean matches = versionMatches(source, found.instance());
                source.putAll(cleanConfig(found.instance()));
                if (!text(source, "id").isBlank() && text(source, "id").equals(text(target, "id")))
                    target.putAll(source);
                checks.add(
                        check(
                                "源环境识别",
                                matches
                                        ? text(found.instance(), "version")
                                                + " · "
                                                + text(found.instance(), "status")
                                        : "实际版本或安装目录已变化，请重新接入或维护连接",
                                matches));
            } catch (Exception error) {
                checks.add(check("源环境连接", safeError(error, command), false));
            }
        }
        if (Set.of("install", "restore").contains(kind) && text(command, "targetId").isBlank()) {
            try {
                checks.addAll(
                        normalizedChecks(
                                execution.precheckTarget(
                                        target, targetCredentials(command, target))));
                String identity = text(target, "hostIdentity");
                if (identity.isBlank())
                    checks.add(check("目标主机身份", "无法确认实际Docker主机身份，不能预留新部署目标。", false));
                else command.put("targetHostIdentity", identity);
            } catch (Exception error) {
                checks.add(check("新目标运行环境", safeError(error, command), false));
            }
            for (Map<String, Object> existing : store.list("instances"))
                if (samePhysicalHost(existing, target))
                    checks.add(check("目标主机隔离", "首版不在已有 DataEase 主机自动部署第二套实例，请选择空服务器。", false));
        } else if (!target.isEmpty() && !Objects.equals(text(source, "id"), text(target, "id"))) {
            try {
                boolean protectionRecovery = kind.equals("restore")
                        && !text(command, "protectionTaskId").isBlank();
                if (protectionRecovery) {
                    Map<String, Object> backup = store.get("backups", required(command, "backupId"));
                    requireProtectionRestore(command, backup, target);
                    execution.verifyBackup(backup);
                }
                ExecutionService.Inspection found =
                        protectionRecovery
                                ? execution.inspectRecoveryTarget(target, targetCredentials(command, target))
                                : execution.inspect(target, targetCredentials(command, target));
                boolean matches = versionMatches(target, found.instance());
                target.putAll(cleanConfig(found.instance()));
                if (protectionRecovery) {
                    target.put("protectionRecovery", true);
                    target.put("metadataDatabasePresent", found.instance().get("metadataDatabasePresent"));
                    checks.add(check("原任务保护恢复", Boolean.FALSE.equals(target.get("metadataDatabasePresent"))
                            ? "MySQL服务身份一致，目标元数据库缺失；将从原目标整体保护备份重建"
                            : "MySQL服务身份一致；原目标整体保护备份保留，当前资源单独保护", true));
                }
                if (text(command, "targetId").isBlank() && !text(target, "hostIdentity").isBlank())
                    command.put("targetHostIdentity", target.get("hostIdentity"));
                checks.add(
                        check(
                                "目标环境识别",
                                matches
                                        ? text(found.instance(), "version")
                                                + " · "
                                                + text(found.instance(), "status")
                                        : "目标实际版本或安装目录已变化",
                                matches));
            } catch (Exception error) {
                checks.add(check("目标连接", safeError(error, command), false));
            }
        }
        if (kind.equals("automation")) {
            try {
                requireAutomationAccepted(source);
                requireAutomationSnapshot(command, execution.automation(source, secrets(source)));
                checks.add(check("验收与自动任务恢复范围", "环境已验收并开放；已核对实际任务、计划与可用性，仅恢复所选任务", true));
            } catch (Exception error) {
                checks.add(check("自动任务恢复条件", safeError(error, command), false));
            }
        }
        if (kind.equals("backup") && bool(command, "includeImages"))
            checks.add(check("镜像保护范围", "首版备份尚不包含容器镜像，请取消镜像打包，恢复时使用匹配离线介质。", false));
        if (kind.equals("service") && "start".equals(text(command, "action"))
                && recoveryRequired(source))
            checks.add(check("失败环境启动保护", "该环境访问状态未确认，请先处理失败任务并从目标保护备份恢复，不能直接开放启动。", false));
        if (kind.equals("backup")
                && !"资源备份".equals(text(command, "scope"))
                && !"MySQL".equals(text(source, "dbType")))
            checks.add(check("元数据库备份范围", "首版自动数据与整体备份仅支持 MySQL；其他数据库可备份资源文件。", false));
        if (Set.of("install", "restore").contains(kind)
                && text(command, "targetId").isBlank()
                && !"MySQL".equals(text(target, "dbType")))
            checks.add(check("首版自动部署范围", "首版新安装与异机重建的隔离编排仅支持 MySQL，其他数据库暂不执行。", false));
        if (kind.equals("backup") && text(command, "destination").equals("local"))
            checks.add(check("备份存储位置", "当前执行层仅支持运维平台受管备份库，请选择平台备份库。", false));
        if (kind.equals("backup") && text(command, "consistent").equals("online"))
            checks.add(
                    obj(
                            "title",
                            "在线一致性限制",
                            "detail",
                            "在线备份无法保证元数据库与资源同一时刻，恢复后必须核对资源匹配。",
                            "status",
                            "warning"));
        if (Set.of("migrate", "restore", "install").contains(kind)
                && text(target, "name").isBlank()) checks.add(check("目标身份", "请填写完整目标实例名称。", false));
        if (kind.equals("migrate")) {
            try {
                execution.validateMigration(source, target);
                execution.validateMigrationSchemas(source, secrets(source), target, targetCredentials(command, target));
                checks.add(check("迁移版本、目录与数据库隔离", "V2/MySQL → V3/MySQL；源与目标目录、数据库独立", true));
                checks.add(check("核心表、权限类型与同步任务", "已只读核验核心表、社区版/企业版权限能力、目标CREATE/DROP授权及同步任务参数", true));
            } catch (Exception error) {
                checks.add(check("迁移兼容性", safeError(error, command), false));
            }
            if (text(command, "targetId").isBlank())
                for (Map<String, Object> instance : store.list("instances"))
                    if (sameEndpoint(instance, target))
                        checks.add(check("未接入目标去重", "该目标已经接入，请选择已有 V3 实例。", false));
        }
        if (kind.equals("plugin")) {
            try {
                execution.precheckPlugin(
                        store.get("plugins", required(command, "packageId")),
                        source,
                        secrets(source),
                        secrets(command),
                        text(map(command, "config"), "apiPrefix"));
                checks.add(check("插件身份、兼容性与应用授权", "已核对原生JAR身份、最低版本、目标应用接口及模块未重复登记", true));
            } catch (Exception error) {
                checks.add(check("插件安装条件", safeError(error, command), false));
            }
        }
        if (Set.of("migrate", "upgrade", "restore", "install", "plugin").contains(kind)) {
            boolean newDeployment =
                    Set.of("install", "restore").contains(kind)
                            && text(command, "targetId").isBlank();
            if (newDeployment) {
                Map<String, Object> pkg =
                        text(command, "packageId").isBlank()
                                ? Map.of()
                                : store.get("packages", text(command, "packageId"));
                boolean protocol = number(pkg, "operationsProtocol") == 1;
                checks.add(
                        check(
                                "隔离启动执行约束",
                                protocol
                                        ? "离线适配入口禁止启动应用；安装后必须核对实际隔离拓扑，不满足时保持停止"
                                        : "离线介质必须声明非交互安装协议并禁止自行启动应用",
                                protocol));
            } else {
                boolean supported = execution.isolationSupported(target);
                checks.add(
                        check(
                                "隔离与自动任务保护",
                                supported
                                        ? "可使用受控 Compose 网络隔离目标，业务入口在验收前保持关闭"
                                        : "该部署无法验证隔离与自动任务暂停，不能安全启动变更目标",
                                supported));
            }
        }
        if (Set.of("migrate", "upgrade", "plugin").contains(kind)
                || kind.equals("backup") && "整体备份".equals(text(command, "scope"))) {
            boolean coverage = coverageReady(source);
            checks.add(
                    check(
                            "源端备份覆盖",
                            coverage ? "元数据库、受管引擎、资源、插件和配置可保护" : "外部或多节点依赖未纳入可验证备份，不能自动执行该操作",
                            coverage));
        }
        if ((Set.of("upgrade", "plugin").contains(kind)
                || kind.equals("backup") && !"资源备份".equals(text(command, "scope")))
                && "MySQL".equals(text(source, "dbType"))) {
            try {
                execution.validateDatabaseAutomation(source, secrets(source));
                checks.add(check("数据库自动任务", "未发现启用的MySQL事件任务", true));
            } catch (Exception error) {
                checks.add(check("数据库自动任务", safeError(error, command), false));
            }
        }
        if (kind.equals("migrate") || kind.equals("upgrade")) {
            boolean coverage = coverageReady(target);
            checks.add(check("目标保护覆盖", coverage ? "目标可创建整体保护备份" : "目标依赖未纳入整体保护备份", coverage));
        }
        if (Set.of("install", "upgrade").contains(kind)
                || kind.equals("restore") && text(command, "targetId").isBlank()) {
            try {
                Map<String, Object> pkg = store.get("packages", required(command, "packageId"));
                validatePackage(pkg, target, kind, source, command);
                checks.add(
                        check("离线介质兼容性", text(pkg, "version") + " · " + text(pkg, "arch"), true));
            } catch (Exception error) {
                checks.add(check("离线介质兼容性", safeError(error, command), false));
            }
        }
        if (kind.equals("restore")) {
            try {
                if (!text(command, "targetId").isBlank()
                        && text(command, "protectionTaskId").isBlank() && !coverageReady(target))
                    throw bad("当前目标无法建立完整保护备份；若目标已因变更损坏，请从原失败任务发起保护恢复。");
                Map<String, Object> backup = store.get("backups", required(command, "backupId"));
                execution.verifyBackup(backup);
                target.put(
                        "restorePurpose",
                        text(command, "restorePurpose").isBlank()
                                ? text(command, "purpose")
                                : text(command, "restorePurpose"));
                execution.validateRestore(backup, target, targetCredentials(command, target));
                if (!text(command, "targetId").isBlank()
                        && !"资源备份".equals(text(backup, "scope"))) {
                    execution.validateDatabaseRebuildPrivileges(target, targetCredentials(command, target));
                    checks.add(check("目标数据库重建授权", "已只读核验当前有效角色的CREATE/DROP权限", true));
                }
                checks.add(
                        check(
                                "实际数据库身份与资源边界",
                                text(command, "targetId").isBlank()
                                        ? "已核验备份；新目标安装后会在覆盖前再次核对实际数据库身份与挂载边界"
                                        : "已核对目标实际数据库身份和资源挂载边界，未将别名当成独立库",
                                true));
                boolean valid =
                        "已校验".equals(text(backup, "status"))
                                && text(backup, "version").equals(text(target, "version"))
                                && text(backup, "dbType").equals(text(target, "dbType"))
                                && text(backup, "arch").equals(text(target, "arch"));
                if (!text(command, "protectionTaskId").isBlank()) {
                    requireProtectionRestore(command, backup, target);
                    if (!text(backup, "version").equals(text(target, "version"))) {
                        execution.validateRollbackPackage(
                                store.get("packages", required(command, "packageId")),
                                backup,
                                target);
                        valid =
                                "已校验".equals(text(backup, "status"))
                                        && text(backup, "dbType").equals(text(target, "dbType"))
                                        && text(backup, "arch").equals(text(target, "arch"));
                        checks.add(check("保护恢复版本回退", "先建立当前目标保护备份，再离线回退应用版本并还原原保护数据", valid));
                    }
                }
                if (text(command, "targetId").isBlank() && !"整体备份".equals(text(backup, "scope")))
                    valid = false;
                checks.add(
                        check(
                                "恢复点与目标兼容性",
                                valid ? "备份摘要、精确版本、数据库及架构匹配" : "恢复目标须匹配备份精确版本、架构及数据库；新环境仅接受整体备份",
                                valid));
                if ("rehearsal".equals(text(command, "restorePurpose"))
                        && text(backup, "instanceId").equals(text(target, "id"))
                        && !"rehearsal".equals(text(target, "environmentPurpose")))
                    checks.add(check("演练隔离", "演练不能覆盖来源生产实例。", false));
                if (bool(command, "sourceOffline")
                        && sameHost(
                                text(map(backup, "instanceSnapshot"), "host"),
                                text(target, "host")))
                    checks.add(check("原服务器状态", "原服务器不可用，不能在该服务器上选择恢复目标。", false));
            } catch (Exception error) {
                checks.add(check("恢复介质", safeError(error, command), false));
            }
        }
        if (Set.of("migrate", "restore", "upgrade", "install").contains(kind)) {
            Set<String> excluded = operationIds(command);
            if (kind.equals("restore"))
                excluded.add(
                        text(store.get("backups", required(command, "backupId")), "instanceId"));
            for (Map<String, Object> other : store.list("instances"))
                if (!excluded.contains(text(other, "id"))) {
                    if (kind.equals("restore")) {
                        Map<String, Object> backup =
                                store.get("backups", required(command, "backupId"));
                        if (sameEndpoint(other, map(backup, "instanceSnapshot"))
                                || !text(backup, "databaseIdentity").isBlank()
                                        && text(backup, "databaseIdentity")
                                                .equals(text(other, "databaseIdentity"))) continue;
                    }
                    Map<String, Object> observed = other;
                    boolean sameName =
                            !text(other, "dbName").isBlank()
                                    && text(other, "dbName")
                                            .equalsIgnoreCase(text(target, "dbName"));
                    if (sameName
                            && text(other, "databaseIdentity").isBlank()
                            && "MySQL".equals(text(other, "dbType"))) {
                        try {
                            observed = execution.inspect(other, secrets(other)).instance();
                        } catch (Exception error) {
                            checks.add(check("共享元数据库识别", "另一个环境的数据库身份尚未确认，请维护其连接后再执行覆盖。", false));
                            continue;
                        }
                    }
                    if (sharesDatabase(observed, target))
                        checks.add(
                                check(
                                        "共享元数据库保护",
                                        "目标元数据库也被 " + text(other, "name") + " 使用，不能只停止本次目标后覆盖。",
                                        false));
                }
        }
        if (checks.isEmpty())
            checks.add(check("操作目标", "已确认操作对象", !source.isEmpty() || !target.isEmpty()));
        return checks.stream().map(CheckGuidance::apply).toList();
    }

    private void validatePackage(
            Map<String, Object> pkg,
            Map<String, Object> target,
            String kind,
            Map<String, Object> source,
            Map<String, Object> command)
            throws Exception {
        Map<String, Object> verified = media.verify(pkg);
        if (!Set.of("verified", "已校验").contains(text(verified, "status"))) throw bad("介质尚未通过校验。");
        if (!normalizeArch(text(pkg, "arch")).equals(normalizeArch(text(target, "arch"))))
            throw bad("离线包与目标服务器架构不匹配。");
        if (!strings(pkg.get("dbTypes")).contains(text(target, "dbType")))
            throw bad("离线包不支持所选元数据库。");
        if (com.dataease.migration.operations.media.DataEaseOfflinePackage.ADAPTER.equals(
                        verified.get("adapter"))
                && !kind.equals("upgrade")) {
            Map<String, String> credentials = targetCredentials(command, target);
            if (!credentials.getOrDefault("dbUser", text(target, "dbUser")).equals("root")
                    || credentials.getOrDefault("dbPassword", "").length() < 8)
                throw bad("内置MySQL安装需root管理账号和至少8个字符的密码。");
            if (!text(target, "dbName").matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                throw bad("新数据库名称仅支持字母、数字与下划线。");
        }
        if (kind.equals("upgrade")) {
            if (!major(text(pkg, "version")).equals(major(text(source, "version"))))
                throw bad("普通升级必须在相同主版本内，V2 → V3 请使用迁移。");
            if (compareVersion(text(pkg, "version"), text(source, "version")) <= 0)
                throw bad("升级目标版本必须高于当前版本。");
            String minimum = "";
            for (String key :
                    List.of(
                            "minimumUpgradeSource",
                            "minUpgradeVersion",
                            "minimumSourceVersion",
                            "minSourceVersion",
                            "minimumUpgradeVersion"))
                if (!text(pkg, key).isBlank()) {
                    minimum = text(pkg, key);
                    break;
                }
            if (minimum.isBlank()) throw bad("离线包未声明最低升级来源版本，请补充经过验证的兼容清单。");
            if (compareVersion(text(source, "version"), minimum) < 0)
                throw bad("当前实例版本低于离线包声明的最低升级来源版本。");
        } else if (!text(pkg, "version").equals(text(target, "version")))
            throw bad("所选离线包版本与目标不一致。");
        if (!strings(pkg.get("purposes")).isEmpty() && !strings(pkg.get("purposes")).contains(kind))
            throw bad("所选离线包未声明允许本次用途。");
    }

    private void requireProtectionRestore(
            Map<String, Object> command, Map<String, Object> backup, Map<String, Object> target) {
        Map<String, Object> previous = store.get("tasks", required(command, "protectionTaskId"));
        if (!"failed".equals(text(previous, "state"))
                || !bool(previous, "dataChanged")
                || !text(previous, "targetId").equals(text(target, "id"))
                || !text(previous, "targetBackupId").equals(text(backup, "id"))
                || !text(backup, "instanceId").equals(text(target, "id"))
                || !"整体备份".equals(text(backup, "scope"))
                || !Set.of("upgrade", "migrate", "restore", "plugin")
                        .contains(text(previous, "kind")))
            throw bad("保护恢复必须绑定已失败变更任务、原目标实例及该任务的整体保护备份。");
    }

    public Map<String, Object> task(String id) {
        return publicRecord(store.get("tasks", id));
    }

    public List<String> logs(String id) {
        return strings(store.get("tasks", id).get("logs"));
    }

    public Map<String, Object> automation(String id) throws Exception {
        Map<String, Object> instance;
        synchronized (this) { instance = requireUnlocked(id); requireAutomationAccepted(instance); }
        return execution.automation(instance, secrets(instance));
    }

    private synchronized void requireAutomationAccepted(Map<String, Object> instance) {
        if (!"open".equals(text(instance, "accessMode")) || recoveryRequired(instance)
                || "rehearsal".equals(text(instance, "environmentPurpose")) || "演练".equals(text(instance, "tag")))
            throw conflict("自动任务只能在验收通过且已开放访问的正式环境恢复，隔离和演练环境继续暂停。");
        if (!"MySQL".equals(text(instance, "dbType"))
                || !"single-compose".equals(text(map(instance, "environmentConfig"), "deployment")))
            throw conflict("自动任务恢复仅支持已识别的单机 Docker Compose / MySQL 环境。");
        List<Map<String, Object>> history = store.list("tasks"); Collections.reverse(history);
        for (var task : history) {
            if (!text(instance, "id").equals(text(task, "targetId"))
                    || !Set.of("migrate", "restore", "install", "upgrade", "plugin").contains(text(task, "kind"))) continue;
            if ("succeeded".equals(text(task, "state"))
                    && "open".equals(text(map(task, "acceptance"), "releaseMode"))
                    && !text(map(task, "acceptance"), "completedAt").isBlank()) return;
            throw conflict("最近一次环境变更尚未完成开放验收，请先处理该任务。");
        }
        throw conflict("该实例没有已完成的开放验收记录，不能跳过验收恢复自动任务。");
    }

    private void requireAutomationSnapshot(Map<String, Object> command, Map<String, Object> actual) {
        if (!text(command, "automationSnapshot").equals(text(actual, "snapshot")))
            throw conflict("自动任务清单或运行状态已变化，请刷新并重新预检查。");
        List<String> selected = strings(command.get("selectedJobs"));
        long available = maps(actual.get("jobs")).stream()
                .filter(row -> selected.contains(text(row, "id")) && bool(row, "available")).count();
        if (available != selected.size()) throw conflict("所选任务已停用、不存在或不支持恢复，请刷新清单。");
    }

    public Map<String, Object> service(String id, Map<String, Object> request) throws Exception {
        if (!Set.of("start", "stop").contains(text(request, "action")))
            throw bad("服务操作只能为 start 或 stop。");
        Map<String, Object> command =
                obj("kind", "service", "sourceId", id, "action", request.get("action"));
        Map<String, Object> check = precheck(command);
        command.put("precheckId", check.get("id"));
        return submit(command);
    }

    private List<Map<String, Object>> acceptanceHealth(Map<String, Object> task,
            Map<String, Object> target) throws Exception {
        String kind = text(task, "kind");
        Optional<Boolean> expected = Optional.empty();
        if ("restore".equals(kind)) {
            String backup = text(map(task, "command"), "backupId");
            expected = execution.backupPluginRegistry(store.get("backups", backup));
        }
        if (expected.isEmpty() && !text(task, "targetBackupId").isBlank())
            expected = execution.backupPluginRegistry(store.get("backups", text(task, "targetBackupId")));
        boolean required = "migrate".equals(kind) || "plugin".equals(kind)
                || expected.orElse(!"install".equals(kind));
        return execution.acceptanceHealth(target, secrets(target), required);
    }

    public Map<String, Object> acceptanceCheck(String id) throws Exception {
        Map<String, Object> task;
        synchronized (this) {
            task = store.get("tasks", id);
            requirePending(task);
        }
        Map<String, Object> target = store.get("instances", text(task, "targetId"));
        List<Map<String, Object>> checks =
                normalizedChecks(acceptanceHealth(task, target)).stream().map(CheckGuidance::apply).toList();
        synchronized (this) {
            task = store.get("tasks", id);
            requirePending(task);
            if (number(store.get("instances", text(task, "targetId")), "revision")
                    != number(target, "revision")) throw conflict("验收配置已变化，请重新运行验收检查。");
            Map<String, Object> acceptance = new LinkedHashMap<>(map(task, "acceptance"));
            acceptance.put("checks", checks);
            acceptance.put("automatedPassed", passed(checks));
            acceptance.put("checkedAt", now());
            acceptance.put("releaseConfirmed", false);
            task.put("acceptance", acceptance);
            store.put("tasks", id, task);
            return publicRecord(task);
        }
    }

    public Map<String, Object> abortAcceptance(String id, Map<String, Object> input)
            throws Exception {
        Map<String, Object> task, target;
        synchronized (this) {
            task = store.get("tasks", id);
            requirePending(task);
            target = store.get("instances", text(task, "targetId"));
            if (!text(target, "name").equals(text(input, "confirmTargetName")))
                throw bad("请输入完整目标实例名称以确认停止目标并终止验收。");
            task.put("state", "aborting");
            task.put("status", "终止中");
            store.put("tasks", id, task);
        }
        try {
            execution.service(target, secrets(target), false, true, line -> appendLog(id, line));
            synchronized (this) {
                task = store.get("tasks", id);
                setInstance(
                        target,
                        obj(
                                "status",
                                "已停止",
                                "accessMode",
                                "isolated",
                                "automation",
                                pausedAutomation()));
                task.put("state", "failed");
                task.put("status", "失败");
                task.put("endedAt", now());
                task.put("failureStage", "业务验收已终止");
                task.put("error", "管理员已停止目标并终止验收，未自动回滚。"
                        + (!text(task, "targetBackupId").isBlank()
                                ? "本任务的目标保护备份已保留，可从该备份发起恢复。" : "请核对环境后重新准备目标。")
                        + ("migrate".equals(text(task, "kind")) ? "源V2数据保留，应用不会自动重启。" : ""));
                task.put("duration", duration(task));
                setInstance(target, obj("recoveryRequired", bool(task, "dataChanged")));
                store.put("tasks", id, task);
                return publicRecord(task);
            }
        } catch (Exception error) {
            if (uncertainCompletion(error)) {
                markReconciliationRequired(
                        id, error, "abort_interrupted", "终止验收的远端停服命令未能确认结束，不能直接重试变更。");
                throw conflict("远端命令结束状态待确认；请先完成现场核查，再处理失败恢复。");
            }
            setInstance(target, obj("status", "状态待确认", "accessMode", "unknown"));
            updateTask(
                    id,
                    obj(
                            "state",
                            "pending_acceptance",
                            "status",
                            "待验收",
                            "error",
                            "目标停服未确认，验收终止未完成；实例仍锁定，请核对容器状态后重试。"));
            throw conflict("目标停服未确认，验收终止未完成；实例仍锁定，请核对容器状态后重试。");
        }
    }

    /**
     * The administrator verifies OS processes and database sessions on site. The platform only
     * verifies its observable application state; it does not claim to enumerate every remote child.
     */
    public Map<String, Object> reconcile(String id, Map<String, Object> input) throws Exception {
        Map<String, Object> task;
        List<Map<String, Object>> endpoints;
        synchronized (this) {
            task = store.get("tasks", id);
            if (!"reconciliation_required".equals(text(task, "state")))
                throw conflict("该任务当前不需要现场核查，不能使用此入口解锁。");
            if (!Boolean.TRUE.equals(input.get("remoteProcessesStopped")))
                throw bad("请先在现场核对并终止本任务遗留的远端进程及SQL，再明确确认。");
            String targetId = text(task, "targetId");
            Map<String, Object> target =
                    targetId.isBlank()
                            ? map(task, "targetInstanceSnapshot")
                            : store.get("instances", targetId);
            String targetName = text(target, "name");
            if (targetName.isBlank()) targetName = text(task, "targetName");
            if (targetName.isBlank() || !targetName.equals(text(input, "confirmTargetName")))
                throw bad("请输入完整目标实例名称以确认本次现场核查。");
            endpoints = reconciliationEndpoints(task);
            task.put("state", "reconciling");
            task.put("status", "核查中");
            task.put(
                    "reconciliation",
                    obj(
                            "status",
                            "checking",
                            "administrator",
                            "admin",
                            "remoteProcessesStopped",
                            true,
                            "confirmedAt",
                            now(),
                            "statement",
                            "管理员声明已在现场核对并终止遗留OS进程及SQL；平台仅核查可观察的两端应用停止状态。"));
            store.put("tasks", id, task);
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        try {
            for (Map<String, Object> endpoint : endpoints) {
                ExecutionService.Inspection inspected = text(endpoint, "databaseIdentity").isBlank()
                        ? execution.inspect(endpoint, secrets(endpoint))
                        : execution.inspectRecoveryTarget(endpoint, secrets(endpoint));
                Map<String, Object> actual = inspected.instance();
                boolean stopped = "已停止".equals(text(actual, "status"));
                observations.add(
                        obj(
                                "instanceId",
                                endpoint.get("id"),
                                "instanceName",
                                endpoint.get("name"),
                                "host",
                                endpoint.get("host"),
                                "status",
                                stopped ? "passed" : "blocked",
                                "applicationState",
                                actual.get("status"),
                                "checkedAt",
                                now()));
                if (!stopped)
                    throw conflict(
                            "平台检查发现 " + text(endpoint, "name") + " 的应用尚未停止。实例锁保留，请完成现场处理后重新核查。");
            }
            synchronized (this) {
                task = store.get("tasks", id);
                if (!"reconciling".equals(text(task, "state"))) throw conflict("核查状态已变化，未解除实例锁。");
                Map<String, Object> reconciliation =
                        new LinkedHashMap<>(map(task, "reconciliation"));
                reconciliation.put("status", "resolved");
                reconciliation.put("applicationChecks", observations);
                reconciliation.put("completedAt", now());
                task.put("reconciliation", reconciliation);
                task.put("state", "failed");
                task.put("status", "失败");
                task.put("endedAt", now());
                task.put(
                        "error",
                        "管理员已确认现场遗留进程和SQL处理完成，平台检查确认两端应用已停止。"
                                + "实例锁已解除，保护备份保留；未自动回滚、还原或开放访问，请重新预检查后处理。");
                task.put("duration", duration(task));
                store.put("tasks", id, task);
                for (Map<String, Object> endpoint : endpoints)
                    setInstance(endpoint, obj("status", "已停止"));
                return publicRecord(task);
            }
        } catch (Exception error) {
            boolean interrupted = Thread.interrupted() || hasInterruptCause(error);
            try {
                synchronized (this) {
                    task = store.get("tasks", id);
                    Map<String, Object> reconciliation =
                            new LinkedHashMap<>(map(task, "reconciliation"));
                    reconciliation.put("status", "blocked");
                    reconciliation.put("applicationChecks", observations);
                    reconciliation.put("lastCheckedAt", now());
                    task.put("reconciliation", reconciliation);
                    task.put("state", "reconciliation_required");
                    task.put("status", "状态待确认");
                    task.put("error", "平台仍不能确认两端应用均已停止，实例锁继续保留。请核对现场进程、SQL和连接后重新核查。");
                    store.put("tasks", id, task);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            throw conflict("现场核查未通过，实例锁继续保留；请确认两端应用停止且连接可读后重试。");
        }
    }

    private List<Map<String, Object>> reconciliationEndpoints(Map<String, Object> task) {
        Set<String> ids = operationIds(map(task, "command"));
        for (String key : List.of("instanceId", "targetId"))
            if (!text(task, key).isBlank()) ids.add(text(task, key));
        if (ids.isEmpty()) throw conflict("任务缺少明确的环境记录，无法核查应用状态，实例锁未解除。");
        List<Map<String, Object>> endpoints = new ArrayList<>();
        for (String instanceId : ids) endpoints.add(store.get("instances", instanceId));
        return endpoints;
    }

    private synchronized void markReconciliationRequired(
            String id, Exception failure, String reason, String context) {
        boolean interrupted = Thread.interrupted() || hasInterruptCause(failure);
        try {
            Map<String, Object> task = store.get("tasks", id);
            task.put("state", "reconciliation_required");
            task.put("status", "状态待确认");
            task.put("interruptedAt", now());
            task.remove("endedAt");
            task.put("failureStage", text(task, "currentStage"));
            task.put("uncertaintyReason", reason);
            String detail = failure == null ? "" : safeError(failure, map(task, "command")) + " ";
            task.put(
                    "error",
                    detail
                            + context
                            + " 实例锁和保护备份均已保留；管理员须现场核对并终止遗留进程及SQL，"
                            + "再使用现场核查入口确认两端应用停止。平台不会自动重试、回滚或放行。");
            Map<String, Object> acceptance = new LinkedHashMap<>(map(task, "acceptance"));
            if (!acceptance.isEmpty()) {
                acceptance.put("automatedPassed", false);
                acceptance.put("releaseConfirmed", false);
                task.put("acceptance", acceptance);
            }
            List<Map<String, Object>> stages = maps(task.get("stages"));
            for (Map<String, Object> stage : stages)
                if (text(stage, "title").equals(text(task, "currentStage"))) {
                    stage.put("status", "unknown");
                    stage.remove("endedAt");
                }
            task.put("stages", stages);
            store.put("tasks", id, task);
            Set<String> ids = operationIds(map(task, "command"));
            for (String key : List.of("instanceId", "targetId"))
                if (!text(task, key).isBlank()) ids.add(text(task, key));
            for (String instanceId : ids)
                store.find("instances", instanceId)
                        .ifPresent(
                                instance ->
                                        setInstance(
                                                instance,
                                                obj("status", "状态待确认", "accessMode", "unknown",
                                                        "recoveryRequired", bool(instance, "recoveryRequired")
                                                                || instanceId.equals(text(task, "targetId"))
                                                                        && bool(task, "dataChanged"))));
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean hasExecutionEvidence(Map<String, Object> task) {
        if (bool(task, "dataChanged")
                || !text(task, "startedAt").isBlank()
                || number(task, "progress") > 0) return true;
        return maps(task.get("stages")).stream()
                .anyMatch(
                        stage ->
                                !text(stage, "startedAt").isBlank()
                                        || Set.of("running", "passed", "failed", "unknown")
                                                .contains(text(stage, "status")));
    }

    private static boolean hasInterruptCause(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause())
            if (cause instanceof InterruptedException
                    || cause instanceof java.nio.channels.ClosedByInterruptException) return true;
        return false;
    }

    private static boolean uncertainCompletion(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause())
            chain.add(cause);
        boolean sshFailure =
                chain.stream()
                        .anyMatch(
                                cause -> cause.getClass().getName().startsWith("com.jcraft.jsch."));
        for (Throwable cause : chain) {
            if (cause instanceof com.jcraft.jsch.SftpException sftp
                    && (sftp.id == com.jcraft.jsch.ChannelSftp.SSH_FX_NO_CONNECTION
                            || sftp.id == com.jcraft.jsch.ChannelSftp.SSH_FX_CONNECTION_LOST))
                return true;
            if (cause instanceof TimeoutException
                    || cause instanceof InterruptedException
                    || cause instanceof java.io.InterruptedIOException
                    || cause instanceof java.net.SocketException
                    || cause instanceof java.nio.channels.AsynchronousCloseException
                    || cause instanceof CancellationException
                    || sshFailure && cause instanceof java.io.EOFException) return true;
            if (cause instanceof java.sql.SQLException sql
                    && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) return true;
            String type = cause.getClass().getName().toLowerCase(Locale.ROOT);
            if (type.contains("communications") || type.contains("timeout")) return true;
            String message =
                    cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("超时")
                    || message.contains("timed out")
                    || message.contains("timeout")
                    || message.contains("interrupted")
                    || message.contains("connection reset")
                    || message.contains("connection is closed")
                    || message.contains("connection lost")
                    || message.contains("connection is lost")
                    || message.contains("broken pipe")
                    || message.contains("session is down")
                    || message.contains("session is closed")
                    || message.contains("session is not connected")
                    || message.contains("socket is closed")
                    || message.contains("ssh") && (message.contains("断开") || message.contains("中断"))
                    || message.contains("网络中断")
                    || message.contains("连接中断")) return true;
        }
        return false;
    }

    public synchronized Map<String, Object> saveAcceptance(String id, Map<String, Object> input) {
        Map<String, Object> task = store.get("tasks", id);
        requirePending(task);
        Map<String, Object> previous = map(task, "acceptance"),
                acceptance = new LinkedHashMap<>(previous);
        Map<String, Object> manual = new LinkedHashMap<>();
        Map<String, Object> incoming = map(input, "manual");
        for (String key : List.of("login", "dashboard", "connections", "entry"))
            manual.put(key, bool(incoming, key));
        String mode = text(input, "releaseMode");
        if (!Set.of("open", "isolated").contains(mode)) mode = "isolated";
        Map<String, Object> automation = new LinkedHashMap<>(pausedAutomation());
        for (String key : List.of("sync", "reports", "alerts"))
            automation.put(key, bool(map(input, "automation"), key));
        boolean changed =
                !Objects.equals(previous.get("manual"), manual)
                        || !Objects.equals(previous.get("releaseMode"), mode)
                        || !Objects.equals(previous.get("automation"), automation)
                        || !Objects.equals(previous.get("notes"), text(input, "notes"));
        acceptance.put("manual", manual);
        acceptance.put(
                "notes",
                text(input, "notes").substring(0, Math.min(text(input, "notes").length(), 4000)));
        acceptance.put("releaseMode", mode);
        acceptance.put("automation", automation);
        acceptance.put("releaseConfirmed", !changed && bool(input, "releaseConfirmed"));
        task.put("acceptance", acceptance);
        store.put("tasks", id, task);
        return publicRecord(task);
    }

    public Map<String, Object> finishAcceptance(String id) throws Exception {
        Map<String, Object> task, target, acceptance;
        synchronized (this) {
            task = store.get("tasks", id);
            requirePending(task);
            acceptance = map(task, "acceptance");
            if (!bool(acceptance, "automatedPassed") || !bool(acceptance, "releaseConfirmed"))
                throw conflict("必须通过自动检查，并确认完整的验收结果。");
            for (String key : List.of("login", "dashboard", "connections", "entry"))
                if (!bool(map(acceptance, "manual"), key)) throw conflict("请完成全部业务核对项目。");
            if ("rehearsal".equals(text(task, "restorePurpose"))
                    && "open".equals(text(acceptance, "releaseMode")))
                throw conflict("恢复演练必须保持隔离。");
            if (map(acceptance, "automation").values().stream().anyMatch(Boolean.TRUE::equals))
                throw conflict("请先完成验收并开放访问，再从自动任务入口逐项恢复。");
            target = store.get("instances", text(task, "targetId"));
            task.put("state", "accepting");
            store.put("tasks", id, task);
        }
        boolean accessChangeStarted = false;
        try {
            boolean open = "open".equals(text(acceptance, "releaseMode"));
            List<Map<String, Object>> latest =
                    normalizedChecks(acceptanceHealth(task, target)).stream().map(CheckGuidance::apply).toList();
            if (!passed(latest)) throw conflict("实际验收检查已变化，请重新检查。");
            accessChangeStarted = true;
            execution.release(target, secrets(target), open, line -> appendLog(id, line));
            synchronized (this) {
                task = store.get("tasks", id);
                acceptance = new LinkedHashMap<>(map(task, "acceptance"));
                acceptance.put("completedAt", now());
                task.put("acceptance", acceptance);
                task.put("state", "succeeded");
                task.put("status", "成功");
                task.put("endedAt", now());
                task.put("duration", duration(task));
                setInstance(
                        target,
                        obj(
                                "status",
                                open ? "运行中" : "隔离运行",
                                "accessMode",
                                open ? "open" : "isolated",
                                "automation",
                                pausedAutomation(),
                                "health",
                                "正常"));
                store.put("tasks", id, task);
                return publicRecord(task);
            }
        } catch (Exception error) {
            if (accessChangeStarted && uncertainCompletion(error)) {
                markReconciliationRequired(
                        id, error, "release_interrupted", "验收访问策略切换的远端命令未能确认结束，不能并发执行重新隔离或还原。");
                throw conflict("访问策略与远端进程结束状态待确认；请先完成管理员现场核查。");
            }
            String recovery = "实际验收检查未通过，访问策略未变更。";
            if (accessChangeStarted) {
                try {
                    execution.service(
                            target, secrets(target), true, true, line -> appendLog(id, line));
                    setInstance(
                            target,
                            obj(
                                    "status",
                                    "待验收",
                                    "accessMode",
                                    "isolated",
                                    "automation",
                                    pausedAutomation()));
                    recovery = "访问策略切换失败，已重新验证隔离启动；请重新验收。";
                } catch (Exception isolationFailure) {
                    if (uncertainCompletion(isolationFailure)) {
                        markReconciliationRequired(
                                id,
                                isolationFailure,
                                "isolation_interrupted",
                                "重新隔离的远端命令未能确认结束，不能并发发送其他变更命令。");
                        throw conflict("重新隔离的执行状态待确认；请先完成管理员现场核查。");
                    }
                    try {
                        execution.service(
                                target, secrets(target), false, true, line -> appendLog(id, line));
                        setInstance(
                                target,
                                obj(
                                        "status",
                                        "已停止",
                                        "accessMode",
                                        "isolated",
                                        "automation",
                                        pausedAutomation()));
                        recovery = "访问策略切换及重新隔离失败，目标已停止，待验收锁保留；请终止验收后核对环境。";
                    } catch (Exception stopFailure) {
                        if (uncertainCompletion(stopFailure)) {
                            markReconciliationRequired(
                                    id,
                                    stopFailure,
                                    "stop_interrupted",
                                    "失败处理的远端停服命令未能确认结束，实例锁保留。");
                            throw conflict("目标停服及远端进程结束状态待确认；请先完成管理员现场核查。");
                        }
                        setInstance(target, obj("status", "状态待确认", "accessMode", "unknown"));
                        recovery = "访问策略切换失败且目标停服未确认，隔离状态未知；实例仍锁定，请立即核对服务器及业务入口。";
                    }
                }
            }
            synchronized (this) {
                Map<String, Object> current = store.get("tasks", id);
                Map<String, Object> currentAcceptance =
                        new LinkedHashMap<>(map(current, "acceptance"));
                currentAcceptance.put("automatedPassed", false);
                currentAcceptance.put("releaseConfirmed", false);
                current.put("acceptance", currentAcceptance);
                current.putAll(
                        obj("state", "pending_acceptance", "status", "待验收", "error", recovery));
                store.put("tasks", id, current);
            }
            throw conflict(recovery);
        }
    }

    public Map<String, Object> addPackage(InputStream input, String kind, String filename)
            throws Exception {
        return addPackage(input, kind, filename, new char[0]);
    }

    public Map<String, Object> addPackage(InputStream input, String kind, String filename, char[] password)
            throws Exception {
        return addPackage(input, kind, filename, password, null, null);
    }

    public Map<String, Object> addDownloadedPackage(InputStream input, String kind, String filename,
            String reservedId, String sha256) throws Exception {
        if (!Set.of("package", "plugin").contains(kind)) throw bad("下载介质类型无效。");
        var existing = store.find(kind.equals("plugin") ? "plugins" : "packages", reservedId);
        if (existing.isPresent()) {
            if (!sha256.equals(text(existing.get(), "sha256"))) throw conflict("下载入库身份不一致。");
            return publicRecord(media.verify(existing.get()));
        }
        return addPackage(input, kind, filename, new char[0], reservedId, sha256);
    }

    public synchronized void discardDownloadedPackage(String kind, String reservedId) throws IOException {
        if (!Set.of("package", "plugin").contains(kind)) throw bad("下载介质类型无效。");
        if (store.find(kind.equals("plugin") ? "plugins" : "packages", reservedId).isEmpty())
            media.discardDownloadReservation(reservedId);
    }

    private Map<String, Object> addPackage(InputStream input, String kind, String filename, char[] password,
            String reservedId, String expectedSha256) throws Exception {
        if (!Set.of("package", "plugin", "backup").contains(kind)) throw bad("上传类型无效。");
        Map<String, Object> record;
        try {
            record = new LinkedHashMap<>(media.upload(input, kind, filename, reservedId, expectedSha256));
        } catch (Exception error) {
            throw mediaFailure(error);
        }
        Map<String, Object> uploaded = new LinkedHashMap<>(record);
        try {
            if (kind.equals("backup")) record = execution.importBackup(record, password);
            String collection =
                    kind.equals("plugin")
                            ? "plugins"
                            : kind.equals("backup") ? "backups" : "packages";
            synchronized (this) {
                for (Map<String, Object> previous : store.list(collection))
                    if ((!text(record, "sha256").isBlank()
                            && text(previous, "sha256").equals(text(record, "sha256")))
                            || kind.equals("backup") && !text(record, "importSha256").isBlank()
                                && text(previous, "importSha256").equals(text(record, "importSha256"))) {
                        media.delete(record);
                        return publicRecord(previous);
                    }
                String id = text(record, "id").isBlank() ? id(kind) : text(record, "id");
                record.put("id", id);
                record.put("createdAt", now());
                return publicRecord(store.put(collection, id, record));
            }
        } catch (Exception error) {
            try {
                media.delete(uploaded);
            } catch (Exception ignored) {
            }
            throw mediaFailure(error);
        }
    }

    public Map<String, Object> verifyPackage(String id) throws Exception {
        String collection = store.find("packages", id).isPresent() ? "packages" : "plugins";
        Map<String, Object> record = store.get(collection, id);
        Map<String, Object> verified;
        try {
            verified = media.verify(record);
        } catch (Exception error) {
            throw mediaFailure(error);
        }
        synchronized (this) {
            Map<String, Object> current = store.get(collection, id);
            if (number(current, "revision") != number(record, "revision"))
                throw conflict("介质清单已变化，请重新校验。");
            record.putAll(verified);
            return publicRecord(store.put(collection, id, record));
        }
    }

    public Map<String, Object> attachManifest(String id, Map<String, Object> manifest)
            throws Exception {
        String collection = store.find("packages", id).isPresent() ? "packages" : "plugins";
        Map<String, Object> record;
        synchronized (this) {
            assertPackageUnused(id);
            record = store.get(collection, id);
        }
        Map<String, Object> verified;
        try {
            verified = media.attachManifest(record, manifest);
        } catch (Exception error) {
            throw mediaFailure(error);
        }
        synchronized (this) {
            assertPackageUnused(id);
            Map<String, Object> current = store.get(collection, id);
            if (number(current, "revision") != number(record, "revision"))
                throw conflict("介质清单已由其他操作更新，请重新打开。");
            record.putAll(verified);
            return publicRecord(store.put(collection, id, record));
        }
    }

    private void assertPackageUnused(String id) {
        if (store.list("tasks").stream()
                .anyMatch(
                        task ->
                                ACTIVE.contains(text(task, "state"))
                                        && id.equals(text(map(task, "command"), "packageId"))))
            throw conflict("活动任务正在引用此介质，不能变更清单。");
    }

    public synchronized void removePackage(String id) throws Exception {
        String collection = store.find("packages", id).isPresent() ? "packages" : "plugins";
        Map<String, Object> record = store.get(collection, id);
        if (store.list("tasks").stream()
                .anyMatch(
                        task ->
                                ACTIVE.contains(text(task, "state"))
                                        && id.equals(text(map(task, "command"), "packageId"))))
            throw conflict("活动任务正在引用此介质，不能移除。");
        media.delete(record);
        store.remove(collection, id);
    }

    public Path packagePath(String id) throws Exception {
        return media.getPath(store.find("packages", id).orElseGet(() -> store.get("plugins", id)));
    }

    public Path backupPath(String id) throws Exception {
        Map<String, Object> backup = store.get("backups", id);
        execution.verifyBackup(backup);
        return media.getPath(backup);
    }

    public Path exportBackup(String id, char[] password) throws Exception {
        try {
            return execution.exportBackup(store.get("backups", id), password);
        } catch (Exception failure) {
            throw mediaFailure(failure);
        }
    }

    public Map<String, Object> verifyBackup(String id) throws Exception {
        Map<String, Object> backup = store.get("backups", id);
        Map<String, Object> result;
        try {
            result = execution.verifyBackup(backup);
        } catch (Exception error) {
            throw mediaFailure(error);
        }
        backup.putAll(result);
        return publicRecord(store.put("backups", id, backup));
    }

    public synchronized Map<String, Object> savePlan(String id, Map<String, Object> input) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (id != null)
            values.putAll(store.find("plans", id).orElseGet(() -> store.get("healthPlans", id)));
        values.putAll(input);
        String kind = "check".equals(text(values, "kind")) ? "check" : "backup";
        String instanceId = text(values, "instanceId");
        if (instanceId.isBlank()) instanceId = required(values, "sourceId");
        store.get("instances", instanceId);
        String scope = kind.equals("backup") ? text(values, "scope") : "健康检查";
        if (kind.equals("backup") && !SCOPES.contains(scope)) throw bad("备份范围无效。");
        String time = required(values, "time");
        try {
            if (!time.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException();
            LocalTime.parse(time);
        } catch (Exception error) {
            throw bad("计划时间格式须为 HH:mm。");
        }
        String frequency = text(values, "frequency");
        if (frequency.isBlank()) frequency = "daily";
        if (!Set.of("daily", "weekly").contains(frequency)) throw bad("仅支持每日或每周计划。");
        long retention = kind.equals("check") ? 7 : number(values, "retention");
        if (retention < 1 || retention > 365) throw bad("保留份数须为 1–365。");
        long dayOfWeek = number(values, "dayOfWeek");
        if (dayOfWeek < 1 || dayOfWeek > 7)
            dayOfWeek =
                    List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                                    .indexOf(text(values, "weekDay"))
                            + 1;
        if (dayOfWeek < 1) dayOfWeek = 1;
        Map<String, Object> plan =
                obj(
                        "id",
                        id == null ? id("plan") : id,
                        "kind",
                        kind,
                        "instanceId",
                        instanceId,
                        "instanceName",
                        store.get("instances", instanceId).get("name"),
                        "scope",
                        scope,
                        "time",
                        time,
                        "frequency",
                        frequency,
                        "dayOfWeek",
                        dayOfWeek,
                        "weekDay",
                        List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").get((int) dayOfWeek - 1),
                        "retention",
                        retention,
                        "enabled",
                        !values.containsKey("enabled") || bool(values, "enabled"),
                        "consistent",
                        text(values, "consistent").equals("online") ? "online" : "pause",
                        "includeImages",
                        bool(values, "includeImages"),
                        "timezone",
                        PLAN_ZONE.toString());
        if (values.containsKey("lastScheduledFor"))
            plan.put("lastScheduledFor", values.get("lastScheduledFor"));
        return publicRecord(store.put(planCollection(plan), text(plan, "id"), plan));
    }

    public synchronized void removePlan(String id) {
        store.remove(store.find("plans", id).isPresent() ? "plans" : "healthPlans", id);
    }

    private static String planCollection(Map<String, Object> plan) {
        return "check".equals(text(plan, "kind")) ? "healthPlans" : "plans";
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void schedule() {
        ZonedDateTime current = ZonedDateTime.now(PLAN_ZONE);
        String minute =
                current.toLocalDate() + "T" + current.toLocalTime().withSecond(0).withNano(0);
        List<Map<String, Object>> allPlans = new ArrayList<>(store.list("plans"));
        allPlans.addAll(store.list("healthPlans"));
        for (Map<String, Object> plan : allPlans) {
            if (!bool(plan, "enabled")
                    || !text(plan, "time")
                            .equals(current.toLocalTime().withSecond(0).withNano(0).toString()))
                continue;
            if ("weekly".equals(text(plan, "frequency"))
                    && number(plan, "dayOfWeek") != current.getDayOfWeek().getValue()) continue;
            synchronized (this) {
                Map<String, Object> latest = store.get(planCollection(plan), text(plan, "id"));
                if (minute.equals(text(latest, "lastScheduledFor"))) continue;
                latest.put("lastScheduledFor", minute);
                store.put(planCollection(plan), text(plan, "id"), latest);
            }
            try {
                Map<String, Object> command =
                        obj(
                                "kind",
                                text(plan, "kind").equals("check") ? "check" : "backup",
                                "sourceId",
                                plan.get("instanceId"),
                                "scope",
                                plan.get("scope"),
                                "consistent",
                                plan.get("consistent"),
                                "includeImages",
                                plan.get("includeImages"),
                                "planId",
                                plan.get("id"));
                Map<String, Object> check = precheck(command);
                command.put("precheckId", check.get("id"));
                Map<String, Object> task = submit(command);
                synchronized (this) {
                    Map<String, Object> latest = store.get(planCollection(plan), text(plan, "id"));
                    latest.put("lastTaskId", task.get("id"));
                    latest.remove("lastError");
                    store.put(planCollection(plan), text(plan, "id"), latest);
                }
            } catch (Exception error) {
                synchronized (this) {
                    Map<String, Object> latest = store.get(planCollection(plan), text(plan, "id"));
                    latest.put(
                            "lastError",
                            error instanceof OperationsApiException
                                    ? error.getMessage()
                                    : "计划执行失败，请检查实例连接与保护条件。");
                    store.put(planCollection(plan), text(plan, "id"), latest);
                }
            }
        }
        cleanupExpired();
    }

    private synchronized void cleanupExpired() {
        for (String collection : List.of("probes", "prechecks"))
            for (Map<String, Object> record : store.list(collection))
                if (expired(record)) {
                    String reference =
                            collection.equals("probes")
                                    ? text(record, "credentialRef")
                                    : text(map(record, "command"), "credentialRef");
                    boolean inUse =
                            !reference.isBlank()
                                    && store.list("instances").stream()
                                            .anyMatch(
                                                    instance ->
                                                            reference.equals(
                                                                    text(
                                                                            instance,
                                                                            "credentialRef")));
                    if (!inUse && !reference.isBlank()) vault.delete(reference);
                    store.remove(collection, text(record, "id"));
                }
    }

    private synchronized void pruneRetention(String planId) throws Exception {
        if (planId.isBlank()) return;
        Map<String, Object> plan = store.get("plans", planId);
        List<Map<String, Object>> candidates =
                new ArrayList<>(
                        store.list("backups").stream()
                                .filter(
                                        backup ->
                                                planId.equals(text(backup, "planId"))
                                                        && "已校验".equals(text(backup, "status")))
                                .toList());
        Collections.reverse(candidates);
        long retention = number(plan, "retention");
        for (int i = (int) retention; i < candidates.size(); i++) {
            Map<String, Object> backup = candidates.get(i);
            String backupId = text(backup, "id");
            boolean referenced =
                    store.list("tasks").stream()
                            .anyMatch(
                                    task ->
                                            (ACTIVE.contains(text(task, "state"))
                                                            || "accepting"
                                                                    .equals(text(task, "state")))
                                                    && (backupId.equals(text(task, "backupId"))
                                                            || backupId.equals(
                                                                    text(task, "sourceBackupId"))
                                                            || backupId.equals(
                                                                    text(task, "targetBackupId"))
                                                            || backupId.equals(
                                                                    text(
                                                                            map(task, "command"),
                                                                            "backupId"))));
            if (!referenced) {
                Files.deleteIfExists(media.getPath(backup));
                store.remove("backups", backupId);
            }
        }
    }

    private void verifyMigrationProtection(String taskId, Map<String, Object> source,
            Map<String, Object> target) throws Exception {
        Map<String, Object> task = store.get("tasks", taskId);
        for (var endpoint : List.of(Map.entry("sourceBackupId", source), Map.entry("targetBackupId", target))) {
            Map<String, Object> backup = store.get("backups", required(task, endpoint.getKey()));
            Map<String, Object> verified = execution.verifyBackup(backup);
            Map<String, Object> instance = endpoint.getValue();
            if (!taskId.equals(text(backup, "taskId"))
                    || !text(backup, "id").equals(text(verified, "id"))
                    || !text(instance, "id").equals(text(verified, "instanceId"))
                    || !text(instance, "version").equals(text(verified, "version"))
                    || !"整体备份".equals(text(verified, "scope"))
                    || text(instance, "databaseIdentity").isBlank()
                    || !text(instance, "databaseIdentity").equals(text(verified, "databaseIdentity")))
                throw conflict("迁移保护备份与本任务两端实际环境不一致，未执行目标覆盖，请重新预检查。");
        }
    }

    private Map<String, Object> saveBackup(
            Map<String, Object> raw, Map<String, Object> instance, String suffix, String taskId)
            throws Exception {
        Map<String, Object> backup = new LinkedHashMap<>(raw);
        execution.verifyBackup(backup);
        String id = text(backup, "id").isBlank() ? id("backup") : text(backup, "id");
        backup.putAll(
                obj(
                        "id",
                        id,
                        "name",
                        text(instance, "name") + " · " + suffix,
                        "instanceId",
                        instance.get("id"),
                        "instanceName",
                        instance.get("name"),
                        "status",
                        "已校验",
                        "taskId",
                        taskId,
                        "createdAt",
                        now(),
                        "date",
                        now(),
                        "drillStatus",
                        "未演练"));
        Map<String, Object> task = store.get("tasks", taskId);
        String planId = text(map(task, "command"), "planId");
        if (!planId.isBlank()) backup.put("planId", planId);
        return store.put("backups", id, backup);
    }

    private synchronized void pendingAcceptance(
            String taskId, Map<String, Object> original, Map<String, Object> command)
            throws Exception {
        Map<String, Object> task = store.get("tasks", taskId),
                target = new LinkedHashMap<>(original);
        target.remove("protectionRecovery");
        target.remove("metadataDatabasePresent");
        String targetId = text(task, "targetId");
        target.put("id", targetId);
        ExecutionService.Inspection found =
                execution.inspect(target, targetCredentials(command, target));
        target.putAll(cleanConfig(found.instance()));
        connectionSummary(target, found.credentials());
        target.put("id", targetId);
        target.put("name", original.get("name"));
        target.put("environment", original.getOrDefault("environment", "测试"));
        target.put(
                "credentialRef",
                !text(original, "credentialRef").isBlank()
                                && secrets(original).equals(found.credentials())
                        ? original.get("credentialRef")
                        : vault.put(found.credentials()));
        target.put("status", "待验收");
        target.put("accessMode", "isolated");
        target.put("recoveryRequired", false);
        target.put("automation", pausedAutomation());
        target.put("revision", number(original, "revision") + 1);
        target.put("connectionProfile", profile(target, number(target, "revision")));
        target.put(
                "environmentPurpose",
                text(task, "restorePurpose").equals("rehearsal")
                        ? "rehearsal"
                        : original.getOrDefault("environmentPurpose", "production"));
        target.put("environmentConfig", environmentConfig(target));
        store.put("instances", targetId, target);
        task.put("targetInstanceSnapshot", target);
        task.put("version", target.get("version"));
        task.put("state", "pending_acceptance");
        task.put("status", "待验收");
        task.put("progress", 100);
        task.put("executionEndedAt", now());
        task.put("duration", duration(task));
        task.put(
                "acceptance",
                obj(
                        "checks",
                        List.of(),
                        "automatedPassed",
                        false,
                        "manual",
                        Map.of(),
                        "releaseMode",
                        "isolated",
                        "automation",
                        pausedAutomation(),
                        "releaseConfirmed",
                        false));
        store.put("tasks", taskId, task);
    }

    private synchronized void succeed(String id) {
        Map<String, Object> task = store.get("tasks", id);
        task.putAll(
                obj(
                        "state",
                        "succeeded",
                        "status",
                        "成功",
                        "progress",
                        100,
                        "endedAt",
                        now(),
                        "duration",
                        duration(task)));
        store.put("tasks", id, task);
    }

    private synchronized void updateTask(String id, Map<String, Object> changes) {
        Map<String, Object> task = store.get("tasks", id);
        task.putAll(changes);
        store.put("tasks", id, task);
    }

    private synchronized void setInstance(
            Map<String, Object> instance, Map<String, Object> changes) {
        if (text(instance, "id").isBlank()
                || store.find("instances", text(instance, "id")).isEmpty()) return;
        Map<String, Object> current = store.get("instances", text(instance, "id"));
        current.putAll(changes);
        store.put("instances", text(instance, "id"), current);
    }

    private synchronized void appendLog(String id, String line) {
        Map<String, Object> task = store.get("tasks", id);
        List<String> logs = new ArrayList<>(strings(task.get("logs")));
        String cleaned = redact(line, map(task, "command"));
        logs.add("[" + now() + "] " + cleaned);
        if (logs.size() > 5000)
            logs = new ArrayList<>(logs.subList(logs.size() - 5000, logs.size()));
        task.put("logs", logs);
        store.put("tasks", id, task);
    }

    private void stage(String id, String name, int progress, CheckedAction action)
            throws Exception {
        synchronized (this) {
            Map<String, Object> task = store.get("tasks", id);
            List<Map<String, Object>> stages = maps(task.get("stages"));
            Map<String, Object> current =
                    stages.stream()
                            .filter(item -> name.equals(text(item, "title")))
                            .findFirst()
                            .orElse(null);
            if (current == null) {
                current = obj("title", name);
                stages.add(current);
            }
            current.put("status", "running");
            current.put("startedAt", now());
            task.put("stages", stages);
            task.put("currentStage", name);
            task.put("progress", progress);
            store.put("tasks", id, task);
        }
        appendLog(id, name);
        action.run();
        synchronized (this) {
            Map<String, Object> task = store.get("tasks", id);
            List<Map<String, Object>> stages = maps(task.get("stages"));
            for (Map<String, Object> item : stages)
                if (name.equals(text(item, "title"))) {
                    item.put("status", "passed");
                    item.put("endedAt", now());
                }
            task.put("stages", stages);
            store.put("tasks", id, task);
        }
    }

    private static List<Map<String, Object>> plannedStages(String kind) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (kind.equals("migrate"))
            for (String name :
                    List.of(
                            "复核两端连接与执行条件",
                            "暂停自动任务并停止两端应用",
                            "创建并校验源端保护备份",
                            "创建并校验目标保护备份",
                            "复制资源、重建数据库并升级结构",
                            "隔离启动目标环境")) result.add(obj("title", name, "status", "pending"));
        return result;
    }

    private Map<String, Object> normalize(Map<String, Object> request) {
        Map<String, Object> command = new LinkedHashMap<>();
        String kind = required(request, "kind");
        if (!KINDS.contains(kind)) throw bad("操作类型无效。");
        command.put("kind", kind);
        for (String key :
                List.of(
                        "sourceId",
                        "targetId",
                        "backupId",
                        "packageId",
                        "protectionTaskId",
                        "scope",
                        "consistent",
                        "copySyncTaskLogs",
                        "includeImages",
                        "targetMode",
                        "restorePurpose",
                        "purpose",
                        "sourceOffline",
                        "name",
                        "action",
                        "automationSnapshot",
                        "selectedJobs",
                        "planId")) if (request.containsKey(key)) command.put(key, request.get(key));
        if (text(command, "sourceId").isBlank() && !text(request, "instanceId").isBlank())
            command.put("sourceId", request.get("instanceId"));
        if (request.containsKey("config"))
            command.put("config", cleanConfig(map(request, "config")));
        for (String optionalId : List.of("packageId", "backupId", "sourceId", "targetId"))
            if (text(command, optionalId).isBlank()) command.remove(optionalId);
        if (kind.equals("backup") && !SCOPES.contains(text(command, "scope")))
            command.put("scope", "整体备份");
        if (request.containsKey("destination"))
            command.put("destination", request.get("destination"));
        if (request.containsKey("backupPath")) command.put("backupPath", request.get("backupPath"));
        if (request.containsKey("nameBackup")) command.put("name", request.get("nameBackup"));
        if (kind.equals("install") && text(command, "packageId").isBlank())
            throw bad("请选择已校验离线介质。");
        if (kind.equals("migrate") && text(command, "sourceId").isBlank())
            throw bad("请明确选择 V2 源实例。");
        if (Set.of("upgrade", "backup", "check", "service", "plugin", "automation").contains(kind)
                && text(command, "sourceId").isBlank()) throw bad("请明确选择操作实例。");
        if (kind.equals("restore") && text(command, "backupId").isBlank()) throw bad("请选择明确的恢复点。");
        if (Set.of("upgrade", "backup", "check", "service", "plugin", "automation").contains(kind)
                && !text(command, "targetId").isBlank()
                && !text(command, "targetId").equals(text(command, "sourceId")))
            throw bad("该操作只能作用于所选实例，不能指定另一目标环境。");
        if (Set.of("install", "restore").contains(kind) && !text(command, "sourceId").isBlank())
            throw bad("安装和还原仅接受明确的目标环境，不接受迁移源实例。");
        if (kind.equals("service") && !Set.of("start", "stop").contains(text(command, "action")))
            throw bad("服务操作只能为 start 或 stop。");
        if (kind.equals("automation")) {
            String snapshot = required(request, "automationSnapshot");
            if (!snapshot.matches("[a-f0-9]{64}")) throw bad("请先读取实际自动任务状态。");
            Object selected = request.get("selectedJobs");
            if (!(selected instanceof List<?> jobs) || jobs.size() > 1000
                    || jobs.stream().anyMatch(value -> !(value instanceof String id) || !id.matches("[a-f0-9]{64}"))
                    || new HashSet<>(jobs).size() != jobs.size()) throw bad("请选择明确且不重复的自动任务。");
        }
        return command;
    }

    private Map<String, Object> source(Map<String, Object> command) {
        if (text(command, "sourceId").isBlank()) return Map.of();
        return store.get("instances", text(command, "sourceId"));
    }

    private Map<String, Object> target(Map<String, Object> command) {
        String kind = text(command, "kind");
        if (!text(command, "targetId").isBlank())
            return store.get("instances", text(command, "targetId"));
        if (Set.of("backup", "check", "service", "plugin", "upgrade", "automation").contains(kind)) {
            Map<String, Object> target = new LinkedHashMap<>(source(command));
            return target;
        }
        Map<String, Object> target = new LinkedHashMap<>(map(command, "config"));
        if (kind.equals("install") && !text(command, "packageId").isBlank())
            target.put("version", store.get("packages", text(command, "packageId")).get("version"));
        if (kind.equals("restore") && !text(command, "backupId").isBlank()) {
            Map<String, Object> backup = store.get("backups", text(command, "backupId"));
            for (String key : List.of("version", "arch", "dbType"))
                target.putIfAbsent(key, backup.get(key));
        }
        if (!text(command, "newTargetId").isBlank()) target.put("id", command.get("newTargetId"));
        if (!text(command, "targetHostIdentity").isBlank())
            target.put("hostIdentity", command.get("targetHostIdentity"));
        return target;
    }

    private Map<String, String> targetCredentials(
            Map<String, Object> command, Map<String, Object> target) {
        return text(target, "credentialRef").isBlank() ? secrets(command) : secrets(target);
    }

    private Map<String, String> secrets(Map<String, Object> record) {
        String ref = text(record, "credentialRef");
        return ref.isBlank() ? Map.of() : vault.get(ref);
    }

    private synchronized Map<String, Object> requireUnlocked(String id) {
        Map<String, Object> instance = store.get("instances", id);
        if (isLocked(id)) throw conflict("该实例有执行中任务或待验收变更，请先处理。");
        return instance;
    }

    private boolean recoveryRequired(Map<String, Object> instance) {
        if (bool(instance, "recoveryRequired")) return true;
        // Older persisted tasks predate the instance marker. A later completed restore or other
        // accepted change supersedes the failure; a read-only check or backup never clears it.
        List<Map<String, Object>> tasks = store.list("tasks");
        Collections.reverse(tasks);
        for (Map<String, Object> task : tasks)
            if (text(instance, "id").equals(text(task, "targetId"))
                    && Set.of("install", "upgrade", "migrate", "restore", "plugin")
                            .contains(text(task, "kind"))
                    && bool(task, "dataChanged"))
                return "failed".equals(text(task, "state"));
        return false;
    }

    private boolean isLocked(String id) {
        return store.list("tasks").stream()
                .anyMatch(
                        task ->
                                (ACTIVE.contains(text(task, "state"))
                                                || "accepting".equals(text(task, "state")))
                                        && (id.equals(text(task, "instanceId"))
                                                || id.equals(text(task, "targetId"))));
    }

    private Set<String> operationIds(Map<String, Object> command) {
        Set<String> ids = new LinkedHashSet<>();
        for (String key : List.of("sourceId", "targetId"))
            if (!text(command, key).isBlank()) ids.add(text(command, key));
        return ids;
    }

    private String fingerprint(Map<String, Object> command) {
        try {
            Map<String, Object> snapshot = new TreeMap<>(command);
            for (String key : List.of("sourceId", "targetId"))
                if (!text(command, key).isBlank())
                    snapshot.put(key + "Snapshot", store.get("instances", text(command, key)));
            if (!text(command, "packageId").isBlank())
                snapshot.put(
                        "packageSnapshot",
                        store.get(
                                "plugin".equals(text(command, "kind")) ? "plugins" : "packages",
                                text(command, "packageId")));
            if (!text(command, "backupId").isBlank())
                snapshot.put("backupSnapshot", store.get("backups", text(command, "backupId")));
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(mapper.writeValueAsBytes(canonical(snapshot))));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot bind operation snapshot", error);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    public Map<String, Object> publicRecord(Map<String, Object> record) {
        return castMap(scrub(record));
    }

    private Object scrub(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clean = new LinkedHashMap<>();
            map.forEach(
                    (key, item) -> {
                        String name = String.valueOf(key), lower = name.toLowerCase(Locale.ROOT);
                        if (!lower.contains("password")
                                && !lower.contains("passphrase")
                                && !lower.contains("privatekey")
                                && !lower.contains("credential")
                                && !lower.endsWith("token")
                                && !Set.of(
                                                "artifactpath",
                                                "command",
                                                "fingerprint",
                                                "internalpath",
                                                "contentroot",
                                                "payloadpath",
                                                "workingdirectory",
                                                "extractedpath")
                                        .contains(lower)) clean.put(name, scrub(item));
                    });
            return clean;
        }
        if (value instanceof List<?> list) return list.stream().map(this::scrub).toList();
        return value;
    }

    private Map<String, Object> cleanConfig(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> allowed =
                Set.of(
                        "name",
                        "environment",
                        "host",
                        "path",
                        "port",
                        "version",
                        "arch",
                        "connection",
                        "connectionMode",
                        "sshUser",
                        "sshPort",
                        "sshAuth",
                        "dbType",
                        "dbHost",
                        "dbPort",
                        "dbName",
                        "dbUser",
                        "dbSchema",
                        "environmentConfig",
                        "apiPrefix",
                        "deploymentAdapter",
                        "applicationService",
                        "composeFile",
                        "deployment",
                        "dbService",
                        "projectName",
                        "status",
                        "environmentPurpose",
                        "tag",
                        "isolationSupported",
                        "databaseService",
                        "composeProject",
                        "composeFiles",
                        "serviceCount",
                        "image",
                        "hostFingerprint",
                        "databaseIdentity",
                        "databaseRouteVerified",
                        "hostIdentity");
        for (var entry : input.entrySet())
            if (allowed.contains(entry.getKey()))
                result.put(
                        entry.getKey(),
                        entry.getKey().equals("environmentConfig")
                                ? cleanEnvironment(castMap(entry.getValue()))
                                : entry.getValue());
        return result;
    }

    private Map<String, Object> cleanEnvironment(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "deployment",
                        "engineMode",
                        "engineProtection",
                        "engineHost",
                        "engineRef",
                        "engineType",
                        "acceptanceQuery"))
            if (input.containsKey(key)) {
                if (!(input.get(key) instanceof String value)
                        || value.length() > (key.equals("acceptanceQuery") ? 16000 : 512))
                    throw bad("环境组成字段格式无效或过长。");
                result.put(key, value);
            }
        for (var entry :
                Map.of(
                                "deployment",
                                Set.of("single-compose", "cluster", "multi-node"),
                                "engineMode",
                                Set.of("shared", "external", "unknown"),
                                "engineProtection",
                                Set.of("managed", "external", "unknown"))
                        .entrySet())
            if (result.containsKey(entry.getKey())
                    && !entry.getValue().contains(result.get(entry.getKey())))
                throw bad("环境组成选项无效。");
        return result;
    }

    private void rejectDuplicate(Map<String, Object> candidate, String ignoreId) {
        for (Map<String, Object> existing : store.list("instances"))
            if (!Objects.equals(text(existing, "id"), ignoreId)) {
                if (sharesDirectory(existing, candidate))
                    throw conflict("该实际服务器的安装目录已接入，或与已接入目录互相包含，请选择已有实例或独立目录。");
                if (sharesDatabase(existing, candidate))
                    throw conflict("该实际元数据库已被 " + text(existing, "name") + " 接入；首版不允许不同环境共享元数据库。");
            }
    }

    private boolean sharesDatabase(Map<String, Object> a, Map<String, Object> b) {
        String x = text(a, "databaseIdentity"), y = text(b, "databaseIdentity");
        if (!x.isBlank() && !y.isBlank()) return x.equals(y);
        if (text(a, "dbName").isBlank()
                || !text(a, "dbName").equalsIgnoreCase(text(b, "dbName"))
                || !text(a, "dbType").equals(text(b, "dbType"))) return false;
        if (!text(a, "dbSchema").isBlank()
                && !text(b, "dbSchema").isBlank()
                && !text(a, "dbSchema").equals(text(b, "dbSchema"))) return false;
        String ah = text(a, "dbHost").isBlank() ? text(a, "host") : text(a, "dbHost"),
                bh = text(b, "dbHost").isBlank() ? text(b, "host") : text(b, "dbHost");
        String ap = text(a, "dbPort").isBlank() ? "3306" : text(a, "dbPort"),
                bp = text(b, "dbPort").isBlank() ? "3306" : text(b, "dbPort");
        if (!ap.equals(bp)) return false;
        if (sameHost(ah, bh)) return true;
        try {
            Set<java.net.InetAddress> addresses =
                    new HashSet<>(Arrays.asList(java.net.InetAddress.getAllByName(ah)));
            return Arrays.stream(java.net.InetAddress.getAllByName(bh))
                    .anyMatch(addresses::contains);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean sameEndpoint(Map<String, Object> a, Map<String, Object> b) {
        return samePhysicalHost(a, b)
                && !text(a, "path").isBlank()
                && path(text(a, "path")).equals(path(text(b, "path")));
    }

    private boolean samePhysicalHost(Map<String, Object> a, Map<String, Object> b) {
        String x = text(a, "hostIdentity"), y = text(b, "hostIdentity");
        return !x.isBlank() && !y.isBlank()
                ? x.equals(y)
                : sameHost(text(a, "host"), text(b, "host"));
    }

    private boolean sharesDirectory(Map<String, Object> a, Map<String, Object> b) {
        if (!samePhysicalHost(a, b) || text(a, "path").isBlank() || text(b, "path").isBlank())
            return false;
        try {
            Path x = Path.of(path(text(a, "path"))), y = Path.of(path(text(b, "path")));
            return x.startsWith(y) || y.startsWith(x);
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean sameHost(String a, String b) {
        return host(a).equals(host(b));
    }

    private String host(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return Set.of("localhost", "127.0.0.1", "::1").contains(v) ? "loopback" : v;
    }

    private String path(String value) {
        try {
            return Path.of(value).normalize().toString();
        } catch (Exception error) {
            return value;
        }
    }

    private boolean versionMatches(Map<String, Object> expected, Map<String, Object> actual) {
        return text(expected, "version").equals(text(actual, "version"))
                && path(text(expected, "path")).equals(path(text(actual, "path")));
    }

    private boolean coverageReady(Map<String, Object> instance) {
        Map<String, Object> environment = environmentConfig(instance);
        return "MySQL".equals(text(instance, "dbType"))
                && !Set.of("cluster", "multi-node").contains(text(environment, "deployment"))
                && "shared".equals(text(environment, "engineMode"));
    }

    private Map<String, Object> environmentConfig(Map<String, Object> instance) {
        Map<String, Object> e = new LinkedHashMap<>(map(instance, "environmentConfig"));
        e.putIfAbsent("deployment", "single-compose");
        e.putIfAbsent("engineMode", "unknown");
        e.putIfAbsent("engineProtection", "managed");
        return e;
    }

    private Map<String, Object> connectionSummary(
            Map<String, Object> instance, Map<String, String> credentials) {
        for (String key : List.of("sshUser", "sshPort", "dbUser"))
            if (credentials.containsKey(key) && !credentials.get(key).isBlank())
                instance.put(key, credentials.get(key));
        if (!"local".equals(text(instance, "connection")))
            instance.put(
                    "sshAuth",
                    !credentials.getOrDefault("privateKey", "").isBlank() ? "key" : "password");
        if (!credentials.getOrDefault("hostKey", "").isBlank())
            instance.put("hostFingerprint", credentials.get("hostKey"));
        return instance;
    }

    private Map<String, Object> profile(Map<String, Object> instance, long revision) {
        return obj(
                "id",
                "connection-" + text(instance, "id"),
                "revision",
                revision,
                "checkedAt",
                now(),
                "host",
                obj(
                        "mode",
                        text(instance, "connection"),
                        "user",
                        text(instance, "sshUser"),
                        "port",
                        instance.getOrDefault("sshPort", 22),
                        "auth",
                        text(instance, "sshAuth"),
                        "status",
                        "valid"),
                "database",
                obj("user", text(instance, "dbUser"), "status", "valid"));
    }

    private void requirePending(Map<String, Object> task) {
        if (!"pending_acceptance".equals(text(task, "state"))) throw conflict("该任务当前不处于待验收状态。");
    }

    private void requireEnvironment(String value) {
        if (!Set.of("生产", "预发布", "测试").contains(value)) throw bad("请选择生产、预发布或测试环境。");
    }

    private String safeError(Exception error, Map<String, Object> command) {
        String result = error.getMessage();
        if (result == null || result.isBlank()) result = "执行失败，请检查服务端日志和连接状态。";
        return redact(result, command)
                .substring(0, Math.min(redact(result, command).length(), 1500));
    }

    private String redact(String line, Map<String, Object> command) {
        String result = line == null ? "" : line;
        for (Map<String, Object> record : List.of(command, source(command), target(command))) {
            try {
                for (var entry : secrets(record).entrySet())
                    if ((entry.getKey().toLowerCase(Locale.ROOT).contains("password")
                                    || entry.getKey().equals("privateKey")
                                    || entry.getKey().equals("passphrase")
                                    || entry.getKey().endsWith("Token")
                                    || entry.getKey().equals("runtimeDbUrl"))
                            && entry.getValue() != null
                            && !entry.getValue().isBlank())
                        result = result.replace(entry.getValue(), "[已隐藏]");
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static OperationsApiException mediaFailure(Exception error) {
        if (error instanceof OperationsApiException domain) return domain;
        if (error instanceof IllegalArgumentException) {
            String message = error.getMessage() == null ? "介质参数无效。" : error.getMessage();
            return bad(message.substring(0, Math.min(message.length(), 1500)));
        }
        return bad("介质读取或校验失败，请重新上传完整文件并核对清单与摘要。");
    }

    private static String connectionError(Exception error, Map<String, String> credentials) {
        String result = error.getMessage() == null ? "连接失败，请核对地址和认证信息。" : error.getMessage();
        for (var entry : credentials.entrySet())
            if (Set.of("sshPassword", "dbPassword", "privateKey", "passphrase", "dataeaseToken")
                            .contains(entry.getKey())
                    && !entry.getValue().isBlank())
                result = result.replace(entry.getValue(), "[已隐藏]");
        return result.substring(0, Math.min(result.length(), 1500));
    }

    private Map<String, String> credentials(Object input) {
        Map<String, String> result = new LinkedHashMap<>();
        if (input instanceof Map<?, ?> map)
            for (String key :
                    List.of(
                            "sshUser",
                            "sshPort",
                            "sshAuth",
                            "sshPassword",
                            "privateKey",
                            "passphrase",
                            "hostKey",
                            "dbUser",
                            "dbPassword",
                            "dataeaseToken")) {
                Object value = map.get(key);
                if (value != null) result.put(key, String.valueOf(value));
            }
        if (result.containsKey("sshAuth")
                && !Set.of("password", "key").contains(result.get("sshAuth")))
            throw bad("SSH认证方式无效。");
        if ("password".equals(result.get("sshAuth"))) {
            result.remove("privateKey");
            result.remove("passphrase");
        } else if ("key".equals(result.get("sshAuth"))) result.remove("sshPassword");
        return result;
    }

    private String duration(Map<String, Object> task) {
        try {
            return Duration.between(Instant.parse(text(task, "startedAt")), Instant.now())
                            .toSeconds()
                    + " 秒";
        } catch (Exception error) {
            return "—";
        }
    }

    private static String normalizeArch(String value) {
        return Set.of("aarch64", "arm64", "ARM64").contains(value)
                ? "arm64"
                : Set.of("x86_64", "amd64").contains(value) ? "x86_64" : value;
    }

    private static String major(String version) {
        return version.replaceFirst("^[vV]", "").split("\\.")[0];
    }

    private static int compareVersion(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("\\."),
                y = b.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c =
                    Integer.compare(
                            i < x.length ? Integer.parseInt(x[i]) : 0,
                            i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) return c;
        }
        return 0;
    }

    private static boolean passed(List<Map<String, Object>> rows) {
        return !rows.isEmpty()
                && rows.stream()
                        .allMatch(
                                row ->
                                        Set.of("passed", "warning", "通过", "正常", "关注")
                                                .contains(text(row, "status")));
    }

    private static List<Map<String, Object>> normalizedChecks(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            String status = text(copy, "status");
            copy.put(
                    "status",
                    Set.of("passed", "通过", "正常").contains(status)
                            ? "passed"
                            : Set.of("warning", "注意", "关注").contains(status)
                                    ? "warning"
                                    : "blocked");
            result.add(copy);
        }
        return result;
    }

    private static String firstBlocked(List<Map<String, Object>> rows) {
        return rows.stream()
                .filter(row -> !Set.of("passed", "warning").contains(text(row, "status")))
                .map(row -> text(row, "title") + "：" + text(row, "detail"))
                .findFirst()
                .orElse("预检查未通过。");
    }

    private static Map<String, Object> check(String title, String detail, boolean passed) {
        return obj("title", title, "detail", detail, "status", passed ? "passed" : "blocked");
    }

    private static Map<String, Object> pausedAutomation() {
        return obj("sync", false, "reports", false, "alerts", false);
    }

    private static String label(String kind) {
        return Map.of(
                        "install",
                        "安装实例",
                        "upgrade",
                        "离线升级",
                        "migrate",
                        "V2 → V3 迁移",
                        "backup",
                        "创建备份",
                        "restore",
                        "还原备份",
                        "check",
                        "健康检查",
                        "service",
                        "服务操作",
                        "plugin",
                        "插件安装",
                        "automation",
                        "自动任务恢复")
                .getOrDefault(kind, kind);
    }

    private static boolean expired(Map<String, Object> record) {
        try {
            return Instant.parse(text(record, "expiresAt")).isBefore(Instant.now());
        } catch (Exception error) {
            return true;
        }
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String required(Map<String, Object> map, String key) {
        String value = text(map, key);
        if (value.isBlank()) throw bad("缺少参数：" + key);
        return value;
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long number(Map<String, Object> map, String key) {
        try {
            return Long.parseLong(text(map, key));
        } catch (Exception error) {
            return 0;
        }
    }

    private static boolean bool(Map<String, Object> map, String key) {
        return Boolean.TRUE.equals(map.get(key)) || "true".equalsIgnoreCase(text(map, key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static Map<String, Object> map(Map<String, Object> map, String key) {
        return castMap(map.get(key));
    }

    private static List<String> strings(Object value) {
        return value instanceof Iterable<?> items ? iterableStrings(items) : new ArrayList<>();
    }

    private static List<String> iterableStrings(Iterable<?> items) {
        List<String> result = new ArrayList<>();
        items.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof Iterable<?> values)
            values.forEach(item -> result.add(new LinkedHashMap<>(castMap(item))));
        return result;
    }

    private static Map<String, Object> obj(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2)
            result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static OperationsApiException bad(String message) {
        return OperationsApiException.badRequest(message);
    }

    private static OperationsApiException conflict(String message) {
        return OperationsApiException.conflict(message);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
