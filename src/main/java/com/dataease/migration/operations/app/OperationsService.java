package com.dataease.migration.operations.app;

import com.dataease.migration.operations.core.CredentialVault;
import com.dataease.migration.operations.core.OperationsApiException;
import com.dataease.migration.operations.core.OperationsProperties;
import com.dataease.migration.operations.core.OperationsStore;
import com.dataease.migration.operations.exec.BackupService;
import com.dataease.migration.operations.exec.ComposeManager;
import com.dataease.migration.operations.exec.ExecutionService;
import com.dataease.migration.operations.media.DataEaseOfflinePackage;
import com.dataease.migration.operations.media.MediaService;
import com.dataease.migration.service.MigrationService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

import static com.dataease.migration.operations.exec.HostCommands.uncertainCompletion;

/** Single-node durable application coordinator. All mutation eligibility is enforced here. */
@Service
@EnableScheduling
public final class OperationsService {
    private static final Logger LOG = LoggerFactory.getLogger(OperationsService.class);
    private static final Set<String> MUTATIONS =
            Set.of("install", "upgrade", "migrate", "backup", "restore", "plugin", "service", "automation", "uninstall", "release");
    private static final Set<String> ACCEPTANCE_KINDS = Set.of("install", "upgrade", "migrate", "restore", "plugin");
    private static final String RELEASE_IDENTITY_STAGE = "核对实例身份";
    private static final String RELEASE_CHECK_STAGE = "切换前复核运行条件";
    private static final String RELEASE_SWITCH_STAGE = "切换业务入口并核验服务";
    private static final String ACCEPTANCE_DECISION_STAGE = "确认验收与访问策略";
    private static final String REGISTER_TARGET_STAGE = "登记并复核目标身份";
    private static final String AUTOMATED_ACCEPTANCE_STAGE = "自动核验验收条件";
    private static final String RETAIN_ISOLATION_STAGE = "核验并保留隔离环境";
    private static final String VERIFY_MIGRATED_EXCEL_STAGE = "复核 V3 的 Excel / CSV 查询";
    private static final String STOP_SOURCE_MYSQL_STAGE = "停止原 V2 内置 MySQL";
    private static final String ABORT_ACCEPTANCE_STAGE = "停止目标并结束验收";
    private static final String RECONCILE_STAGE = "核查目标运行状态";
    private static final Set<String> KINDS =
            Set.of(
                    "install", "upgrade", "migrate", "backup", "restore", "check", "plugin",
                    "service", "automation");
    private static final Set<String> ACTIVE =
            Set.of(
                    "queued",
                    "running",
                    "pending_acceptance",
                    "checking_acceptance",
                    "accepting",
                    "aborting",
                    "reconciliation_required",
                    "reconciling");
    private static final Set<String> SCOPES = Set.of("整体备份", "数据备份", "资源备份");
    private static final String NOT_INSTALLED = "未安装";
    private static final List<String> HOST_FIELDS =
            List.of("host", "connection", "arch", "sshUser", "sshPort", "sshAuth", "hostFingerprint", "machineIdentity");
    private static final List<String> SSH_FIELDS =
            List.of("sshUser", "sshPort", "sshAuth", "sshPassword", "privateKey", "passphrase", "hostKey");
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
            if ("checking_acceptance".equals(state)) {
                String detail = "运维服务重启时中断了运行核验，请重新核验后完成验收。";
                markStageFailed(task, detail);
                var acceptance = new LinkedHashMap<>(map(task, "acceptance"));
                acceptance.putAll(obj("checks", List.of(), "automatedPassed", false, "releaseConfirmed", false));
                acceptance.remove("checkedAt");
                task.putAll(obj("state", "pending_acceptance", "status", "待验收", "progress", 100,
                        "error", detail, "currentCheck", "", "liveChecks", List.of(), "acceptance", acceptance));
                task.remove("currentCheckStartedAt");
                task.remove("stageActivity");
                appendLog(task, detail);
                store.put("tasks", text(task, "id"), task);
                continue;
            }
            if (!Set.of("queued", "running", "aborting", "accepting", "reconciling")
                    .contains(state)) continue;
            boolean neverStarted = "queued".equals(state) && !hasExecutionEvidence(task);
            if (MUTATIONS.contains(text(task, "kind")) && !neverStarted && !upgradeReadOnlyStage(task)) {
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
            if (neverStarted && !map(map(task, "command"), "registeredHostSnapshot").isEmpty())
                store.saveInstanceAndTask(map(map(task, "command"), "registeredHostSnapshot"), task);
            else store.put("tasks", text(task, "id"), task);
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
        var tasks = store.list("tasks");
        Collections.reverse(tasks);
        var instances = store.list("instances");
        Map<String, Map<String, Object>> instancesById = new HashMap<>();
        for (var instance : instances) instancesById.put(text(instance, "id"), instance);
        Map<String, Map<String, Object>> completedDrills = new HashMap<>();
        for (var task : tasks)
            if ("restore".equals(text(task, "kind")) && "rehearsal".equals(text(task, "restorePurpose"))
                    && accepted(task, "isolated") && !text(task, "backupId").isBlank())
                completedDrills.putIfAbsent(text(task, "backupId"), task);
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
            List<Map<String, Object>> records = switch (collection) {
                case "tasks" -> tasks;
                case "instances" -> instances;
                default -> store.list(collection);
            };
            if (collection.equals("instances")) {
                for (var task : tasks) {
                    String targetId = text(task, "targetId");
                    var destination = instancesById.get(targetId);
                    if (destination == null || vacant(destination) || !currentDeployment(destination, task)
                            || !isAcceptedSameHostMigration(task)) continue;
                    var source = instancesById.get(text(task, "instanceId"));
                    if (source != null && text(source, "deploymentId").equals(text(map(task, "instanceSnapshot"), "deploymentId"))
                            && "已停止".equals(text(source, "status")) && bool(source, "migrationDatabaseStopped"))
                        source.putIfAbsent("archivedByMigrationTo", targetId);
                }
                for (var instance : records) {
                    if (vacant(instance)) continue;
                    preserveIsolatedStatus(instance);
                    var change = latestAcceptanceChange(instance, tasks);
                    if (releaseCandidate(instance) && acceptedInIsolation(change))
                        instance.put("canOpenAccess", true);
                    var accounts = latestAccountChange(instance, tasks);
                    if (Set.of("migrate", "restore").contains(text(accounts, "kind")))
                        instance.remove("hasInitialAdmin");
                    if ("migrate".equals(text(accounts, "kind"))) {
                        if (!migrationInitialAdminReference(accounts).isBlank())
                            instance.put("hasMigratedInitialAdmin", true);
                    }
                }
            } else if (!collection.equals("tasks")) Collections.reverse(records);
            result.put(collection, records.stream().map(record -> switch (collection) {
                case "downloads" -> com.dataease.migration.operations.media.PackageDownloadService.publicView(record);
                case "packages" -> publicRecord(media.packageDisplay(record));
                case "tasks" -> taskView(record, instancesById.get(text(record, "targetId")));
                case "backups" -> {
                    var backup = publicRecord(record);
                    var instance = instancesById.get(text(record, "instanceId"));
                    if (instance != null)
                        backup.put("currentInstanceDeployment", !vacant(instance)
                                && text(instance, "deploymentId").equals(text(record, "deploymentId")));
                    var drill = completedDrills.get(text(record, "id"));
                    if (drill != null)
                        backup.put("lastDrillAt", text(map(drill, "acceptance"), "completedAt"));
                    yield backup;
                }
                default -> publicRecord(record);
            }).toList());
        }
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshLegacyPackages() {
        var legacy = store.list("packages").stream().filter(media::needsPackageVerification).toList();
        if (legacy.isEmpty()) return;
        executor.execute(() -> {
            for (var record : legacy) {
                if (Thread.currentThread().isInterrupted()) return;
                String id = text(record, "id");
                try {
                    if (store.find("packages", id).filter(media::needsPackageVerification).isPresent())
                        verifyPackage(id);
                } catch (Exception failure) {
                    LOG.warn("Could not refresh verification of offline package {}", id);
                }
            }
        });
    }

    public Map<String, Object> probe(Map<String, Object> request) throws Exception {
        Map<String, Object> config = cleanConfig(map(request, "config"));
        requireEnvironment(text(config, "environment"));
        Map<String, String> input = credentials(request.get("credentials"));
        ExecutionService.Inspection found;
        try {
            found = "host".equals(text(request, "mode"))
                    ? inspectVacantHost(config, input)
                    : execution.inspect(config, input);
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

    private ExecutionService.Inspection inspectVacantHost(Map<String, Object> config, Map<String, String> input)
            throws Exception {
        var connection = required(config, "connection");
        if (!Set.of("local", "ssh").contains(connection)) throw bad("请选择连接方式。");
        var host = new LinkedHashMap<String, Object>();
        host.put("connection", connection);
        host.put("host", connection.equals("local") ? "127.0.0.1" : required(config, "host").strip());
        if (!text(host, "host").matches("[A-Za-z0-9_.:\\[\\]-]{1,253}"))
            throw bad("请填写有效的服务器 IP 或主机名。");
        Map<String, String> ssh = connection.equals("local") ? Map.of() : sshCredentials(input);
        if (connection.equals("ssh")) {
            if (ssh.getOrDefault("sshUser", "").isBlank()) throw bad("请输入 SSH 用户名。");
            String port = ssh.getOrDefault("sshPort", "22");
            if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
                throw bad("SSH 端口须为 1–65535。");
            boolean key = "key".equals(ssh.get("sshAuth"));
            if (ssh.getOrDefault(key ? "privateKey" : "sshPassword", "").isBlank())
                throw bad(key ? "请选择 SSH 私钥。" : "请输入 SSH 密码。");
        }
        host.putAll(execution.inspectHost(host, ssh));
        host.put("status", NOT_INSTALLED);
        return new ExecutionService.Inspection(host, ssh);
    }

    private static boolean vacant(Map<String, Object> instance) {
        return NOT_INSTALLED.equals(text(instance, "status"));
    }

    private static Map<String, String> sshCredentials(Map<String, String> input) {
        var result = new LinkedHashMap<String, String>();
        for (String key : SSH_FIELDS)
            if (input.containsKey(key)) result.put(key, input.get(key));
        return result;
    }

    private static void copyHostFields(Map<String, Object> from, Map<String, Object> to) {
        for (String key : HOST_FIELDS)
            if (from.containsKey(key)) to.put(key, from.get(key));
    }

    private void verifyRegisteredHost(Map<String, Object> target, Map<String, String> ssh) throws Exception {
        String identity = text(target, "machineIdentity");
        if (identity.isBlank()) return;
        var observed = execution.inspectHost(target, ssh);
        if (!identity.equals(observed.get("machineIdentity")) || !text(target, "arch").equals(observed.get("arch")))
            throw conflict("服务器身份或架构与登记时不一致，请核对连接目标。");
    }

    private void verifyRegisteredDeployment(Map<String, Object> command, Map<String, Object> target,
            Map<String, String> credentials, Map<String, Object> pkg) throws Exception {
        verifyRegisteredHost(target, credentials);
        var observed = new LinkedHashMap<>(target);
        var checks = new ArrayList<Map<String, Object>>();
        execution.precheckTarget(observed, credentials, media.verifiedPackage(pkg), title -> {}, checks::add);
        if (!passed(checks)) throw conflict(firstBlocked(checks));
        if (!text(command, "targetHostIdentity").equals(text(observed, "hostIdentity"))
                || bool(command, "runtimeBootstrap") != bool(observed, "runtimeBootstrap"))
            throw conflict("服务器 Docker 环境在预检查后已变化，请重新确认。");
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
        if (!vacant(instance)) instance.put("environmentConfig", environmentConfig(instance));
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
            if (vacant(previous)) {
                found = inspectVacantHost(previous, updated);
                if (!text(previous, "machineIdentity").equals(text(found.instance(), "machineIdentity")))
                    throw conflict("服务器身份已变化，请核对连接目标。");
            } else found = execution.inspectConnection(previous, updated);
        } catch (Exception error) {
            throw bad("连接更新失败：" + connectionError(error, updated));
        }
        synchronized (this) {
            Map<String, Object> current = requireUnlocked(id);
            if (number(current, "revision") != number(previous, "revision"))
                throw conflict("连接配置已变化，请重新打开维护。");
            String old = text(current, "credentialRef");
            current.putAll(cleanConfig(found.instance()));
            preserveIsolatedStatus(current);
            if (vacant(current)) copyHostFields(found.instance(), current);
            connectionSummary(current, found.credentials());
            current.put("id", id);
            current.put("name", previous.get("name"));
            current.put("environment", previous.get("environment"));
            if (!vacant(current)) rejectDuplicate(current, id);
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
        Map<String, Object> instance = requireUnlocked(id);
        if (request.containsKey("name")) instance.put("name", required(request, "name"));
        if (request.containsKey("environment")) {
            requireEnvironment(text(request, "environment"));
            instance.put("environment", request.get("environment"));
        }
        if (!vacant(instance) && request.containsKey("environmentConfig"))
            instance.put("environmentConfig", cleanEnvironment(map(request, "environmentConfig")));
        instance.put("revision", number(instance, "revision") + 1);
        return publicRecord(store.put("instances", id, instance));
    }

    public Map<String, Object> precheck(Map<String, Object> request) throws Exception {
        return precheck(request, event -> {});
    }

    public Map<String, Object> precheck(
            Map<String, Object> request, Consumer<Map<String, Object>> progress) throws Exception {
        Map<String, Object> command = normalize(request);
        prepareCredentials(command, request);
        List<Map<String, Object>> checks = checks(command, progress);
        String id = id("precheck");
        Map<String, Object> record = obj(
                "id", id, "command", command, "fingerprint", fingerprint(command),
                "checks", checks, "passed", passed(checks),
                "expiresAt", Instant.now().plusSeconds(600).toString(),
                "sourceInstance", publicRecord(source(command)),
                "targetInstance", publicRecord(target(command)),
                "targetName", text(target(command), "name"), "targetId", text(target(command), "id"));
        store.put("prechecks", id, record);
        return publicRecord(record);
    }

    private void prepareCredentials(Map<String, Object> command, Map<String, Object> request) {
        Map<String, String> input = credentials(request.get("credentials"));
        if (!text(command, "vacantTargetId").isBlank()) {
            if (SSH_FIELDS.stream().anyMatch(key -> !input.getOrDefault(key, "").isBlank()))
                throw bad("该操作沿用实例连接，请在连接设置中维护 SSH。");
            SSH_FIELDS.forEach(input::remove);
            input.putAll(sshCredentials(secrets(vacantTarget(command))));
        }
        if (sameHostMigration(command)) {
            if (!input.isEmpty()) throw bad("同机迁移沿用源实例连接，请在实例管理中维护 SSH。");
            var saved = secrets(source(command));
            input.putAll(sshCredentials(saved));
        }
        if (sameHostMigration(command) || text(command, "targetId").isBlank()
                && Set.of("install", "restore").contains(text(command, "kind"))
                && "builtin".equals(text(target(command), "dbMode"))) {
            input.put("dbUser", "root");
            input.put("dbPassword", UUID.randomUUID().toString());
        }
        if (!input.isEmpty()) command.put("credentialRef", vault.put(input));
    }

    public Map<String, Object> checkAndSubmit(Map<String, Object> request,
            Consumer<Map<String, Object>> progress) throws Exception {
        Map<String, Object> command = normalize(request);
        Map<String, Object> snapshot;
        String before;
        boolean reserving = false;
        try {
            synchronized (this) {
                requireTargetConfirmation(command, request);
                prepareCredentials(command, request);
                snapshot = new LinkedHashMap<>(command);
                before = fingerprint(snapshot);
            }
            List<Map<String, Object>> results = checks(command, progress);
            if (!passed(results)) return publicRecord(obj("checks", results, "passed", false));
            progress.accept(obj("type", "submitting"));
            synchronized (this) {
                if (!before.equals(fingerprint(snapshot)))
                    throw conflict("检查期间配置或环境已变化，请核对后重新确认。");
                // Once reservation begins the vault entry may be referenced by a task or instance.
                reserving = true;
                return publicRecord(obj("checks", results, "passed", true,
                        "task", queueChecked(command, request, results)));
            }
        } finally {
            String reference = text(command, "credentialRef");
            if (!reserving && !reference.isBlank()) {
                try { vault.delete(reference); }
                catch (IllegalStateException error) { LOG.warn("Could not remove unused submission credentials"); }
            }
        }
    }

    private Map<String, Object> submitAfterCheck(Map<String, Object> command) throws Exception {
        var result = checkAndSubmit(command, event -> {});
        if (!bool(result, "passed")) throw conflict(firstBlocked(maps(result.get("checks"))));
        return map(result, "task");
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
        if (command.containsKey("targetMachineIdentity"))
            candidate.put("targetMachineIdentity", command.get("targetMachineIdentity"));
        if (command.containsKey("vendorRollbackPackageId"))
            candidate.put("vendorRollbackPackageId", command.get("vendorRollbackPackageId"));
        if (command.containsKey("runtimeBootstrap"))
            candidate.put("runtimeBootstrap", command.get("runtimeBootstrap"));
        if (!fingerprint(candidate).equals(text(stored, "fingerprint")))
            throw conflict("操作配置或环境已变化，请重新预检查并确认目标。");
        Map<String, String> provided = credentials(request.get("credentials"));
        if (sameHostMigration(command) && !provided.isEmpty())
            throw bad("同机迁移沿用源实例连接，请在实例管理中维护 SSH。");
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
            Map<String, Object> task = queueChecked(command, request, checks);
            store.remove("prechecks", precheckId);
            return publicRecord(task);
        }
    }

    private void requireTargetConfirmation(Map<String, Object> command, Map<String, Object> request) {
        if (Set.of("migrate", "restore", "install", "upgrade", "plugin", "automation").contains(text(command, "kind"))) {
            String name = text(target(command), "name");
            if (name.isBlank() || !name.equals(text(request, "confirmTargetName")))
                throw bad("请输入完整目标实例名称以确认本次操作。");
        }
    }

    private Map<String, Object> queueChecked(Map<String, Object> command, Map<String, Object> request,
            List<Map<String, Object>> checks) throws Exception {
        requireTargetConfirmation(command, request);
        Map<String, Object> target = target(command);
        String kind = text(command, "kind");
        for (String instanceId : operationIds(command)) requireUnlocked(instanceId);
        if (Set.of("install", "restore").contains(kind) && text(command, "targetId").isBlank())
            for (Map<String, Object> existing : store.list("instances"))
                if (!text(existing, "id").equals(text(command, "vacantTargetId"))
                        && (samePhysicalHost(existing, target) || sharesDatabase(existing, target))
                        && !(kind.equals("install") && canRetryInstall(existing, target)))
                    throw conflict("目标服务器或元数据库已被已接入环境使用，请选择空服务器与独立数据库。");
        if (kind.equals("migrate") && text(command, "targetId").isBlank() && !sameHostMigration(command)) {
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
        return queue(command, checks);
    }

    private Map<String, Object> queue(
            Map<String, Object> command, List<Map<String, Object>> checks) {
        String kind = text(command, "kind"), id = id("task");
        Map<String, Object> source = source(command), target = target(command);
        Map<String, Object> registration = vacantTarget(command);
        Map<String, Object> pending = null;
        if ((Set.of("install", "restore").contains(kind) || sameHostMigration(command)) && text(command, "targetId").isBlank()) {
            Map<String, Object> requestedTarget = target;
            String retryId = !registration.isEmpty() ? text(registration, "id") : kind.equals("install")
                    ? store.list("instances").stream().filter(instance -> canRetryInstall(instance, requestedTarget))
                            .map(instance -> text(instance, "id")).findFirst().orElse("")
                    : "";
            target = new LinkedHashMap<>(target);
            target.put("id", retryId.isBlank() ? id("instance") : retryId);
            command.put("newTargetId", target.get("id"));
            if (!registration.isEmpty()) {
                command.put("registeredHostSnapshot", registration);
                command.put("deploymentId", id);
                target.put("deploymentId", id);
            }
            if (sameHostMigration(command)) {
                command.put("sameHostTargetProject", "deops-v3-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                target.put("composeProject", command.get("sameHostTargetProject"));
            }
            pending = new LinkedHashMap<>(target);
            pending.put("status", kind.equals("install") || sameHostMigration(command) ? "待安装" : "恢复准备中");
            pending.put("revision", number(registration, "revision") + 1);
            pending.put("credentialRef", command.get("credentialRef"));
            pending.put("createdAt", registration.getOrDefault("createdAt", now()));
        }
        String targetId = text(target, "id"),
                instanceId = source.isEmpty() ? targetId : text(source, "id");
        Map<String, Object> task =
                obj(
                        "id",
                        id,
                        "kind",
                        kind,
                        "sameHostMigration",
                        sameHostMigration(command),
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
                        plannedStages(command),
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
        if (kind.equals("release")) {
            task.put("acceptanceTaskId", command.get("acceptanceTaskId"));
            task.put("acceptance", obj("releaseMode", "open", "automation", pausedAutomation()));
        }
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
        if (!text(command, "vendorRollbackPackageId").isBlank())
            task.put("rollbackPackageId", command.get("vendorRollbackPackageId"));
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
        if (pending != null) store.saveInstanceAndTask(pending, task);
        else store.put("tasks", id, task);
        try {
            executor.submit(() -> execute(id));
        } catch (RejectedExecutionException busy) {
            task.put("state", "failed");
            task.put("status", "失败");
            task.put("error", "任务队列已满或服务正在停止，未执行环境变更。");
            task.put("endedAt", now());
            if (registration.isEmpty()) store.put("tasks", id, task);
            else store.saveInstanceAndTask(registration, task);
            throw conflict("任务队列已满，请稍后重新预检查。");
        }
        return task;
    }

    private void execute(String taskId) {
        Map<String, Object> task = store.get("tasks", taskId), command = map(task, "command");
        String kind = text(task, "kind");
        Consumer<String> log = line -> appendLog(taskId, line);
        List<String> sourceRunningBeforeMigration = null;
        List<String> targetRunningBeforeMigration = null;
        try {
            updateTask(taskId, obj("state", "running", "status", "执行中", "startedAt", now()));
            Map<String, Object> source = source(command), target = target(command);
            Map<String, String> sourceCredentials = secrets(source),
                    targetCredentials = targetCredentials(command, target);
            if (kind.equals("release")) {
                executeAcceptance(taskId, target);
            } else if (kind.equals("migrate")) {
                boolean sameHost = sameHostMigration(command);
                if (sameHost) {
                    stage(taskId, "复核同机部署与源端身份", 3, () -> {
                        var actual = execution.inspect(source, sourceCredentials);
                        execution.validateMigrationIdentity(source, actual);
                        execution.requireBackupScope(actual.instance(), "整体备份");
                        execution.validateDatabaseAutomation(source, sourceCredentials);
                        execution.validateMigrationExcelTables(source, sourceCredentials);
                        var mediaRecord = store.get("packages", required(command, "packageId"));
                        var verified = media.verifiedPackage(mediaRecord);
                        validateSameHostMigration(source, target, verified, command);
                        var observed = new ArrayList<Map<String, Object>>();
                        execution.precheckTarget(target, targetCredentials, verified, title -> {}, observed::add);
                        if (!passed(observed)) throw conflict(firstBlocked(observed));
                        if (!text(source, "hostIdentity").equals(text(target, "hostIdentity"))
                                || bool(target, "runtimeBootstrap"))
                            throw conflict("目标 Docker 主机身份已变化，请重新预检查。");
                    });
                    sourceRunningBeforeMigration = execution.runningMigrationServices(source, sourceCredentials);
                    stage(taskId, "停止 V2 应用与配套服务", 6, "V2 仅保留内置 MySQL 运行。", () -> {
                        execution.service(source, sourceCredentials, false, false, log);
                        execution.validateMigrationIdentity(source, execution.inspect(source, sourceCredentials));
                        setInstance(source, obj("status", "已停止", "automation", pausedAutomation()));
                    });
                    stage(taskId, "创建并校验源端保护备份", 12,
                            () -> createMigrationProtection(taskId, source, sourceCredentials, true, true, log));
                    stage(taskId, "在同一主机准备独立 V3", 22, () -> {
                        execution.install(store.get("packages", required(command, "packageId")), target,
                                targetCredentials, false, log, new ExecutionService.InstallProgress() {
                                    @Override public void detail(String message) {
                                        updateCurrentStage(taskId, message, false);
                                        log.accept(message);
                                    }
                                    @Override public void start(String title, int milestone) { detail(title); }
                                    @Override public void complete(String result) { detail(result); }
                                });
                        var found = execution.inspect(target, targetCredentials);
                        target.putAll(cleanConfig(found.instance()));
                        targetCredentials.putAll(found.credentials());
                        target.put("credentialRef", vault.put(found.credentials()));
                        connectionSummary(target, found.credentials());
                        target.put("connectionProfile", profile(target, number(target, "revision")));
                        target.put("environmentConfig", environmentConfig(target));
                        setInstance(target, target);
                        updateTask(taskId, obj("targetInstanceSnapshot", target));
                        log.accept("隔离启动 V3，初始化全新元数据库与企业版权限表。");
                        execution.service(target, targetCredentials, true, true, line -> {
                            updateCurrentStage(taskId, line, false);
                            log.accept(line);
                        });
                    });
                }
                stage(
                        taskId,
                        "复核两端连接与执行条件",
                        sameHost ? 32 : 5,
                        () -> {
                            execution.validateMigration(source, target);
                            execution.validateMigrationEndpoints(source, sourceCredentials, target, targetCredentials);
                            if (!sameHost) execution.validateMigrationExcelTables(source, sourceCredentials);
                            execution.validateMigrationSchemas(source, sourceCredentials, target, targetCredentials);
                        });
                if (!sameHost) {
                    sourceRunningBeforeMigration = execution.runningMigrationServices(source, sourceCredentials);
                    targetRunningBeforeMigration = execution.runningMigrationServices(target, targetCredentials);
                }
                stage(
                        taskId,
                        sameHost ? "停止 V3 应用，保留两端 MySQL" : "暂停自动任务并停止两端应用",
                        sameHost ? 38 : 15,
                        () -> {
                            if (sameHost) execution.verifyServicesStopped(source, sourceCredentials);
                            else {
                                execution.service(source, sourceCredentials, false, false, log);
                                setInstance(source, obj("status", "已停止", "automation", pausedAutomation()));
                            }
                            execution.service(target, targetCredentials, false, false, log);
                            setInstance(
                                    target, obj("status", "已停止", "automation", pausedAutomation()));
                        });
                if (!sameHost) stage(taskId, "创建并校验源端保护备份", 28,
                        () -> createMigrationProtection(taskId, source, sourceCredentials, false, true, log));
                stage(
                        taskId,
                        "创建并校验目标保护备份",
                        43,
                        () -> createMigrationProtection(taskId, target, targetCredentials, sameHost, false, log));
                beginStage(taskId, "复核两端保护备份", 52);
                verifyMigrationProtection(taskId, source, target);
                execution.migrate(source, sourceCredentials, target, targetCredentials,
                        bool(command, "copySyncTaskLogs"), log, phase -> {
                            updateCurrentStage(taskId, "", true);
                            beginStage(taskId, phase.title(), phase.milestone());
                        }, () -> updateTask(taskId, obj("dataChanged", true)));
                updateCurrentStage(taskId, "", true);
                stage(
                        taskId,
                        "隔离启动目标环境",
                        90,
                        () -> execution.service(target, targetCredentials, true, true, log));
                updateTask(taskId, obj("migrationExcelRuntimeCheck", true));
                pendingAcceptance(taskId, target, command);
            } else if (kind.equals("backup")) {
                String scope = text(command, "scope");
                var originalStatus = new String[1];
                stage(taskId, "核对实际备份范围", 5, () -> {
                    var observed = execution.inspectBackupSource(source, sourceCredentials, scope).instance();
                    if (!versionMatches(source, observed) || !text(source, "hostIdentity").equals(text(observed, "hostIdentity")))
                        throw conflict("实际主机、实例版本或安装目录已变化，请重新验证连接。");
                    execution.requireBackupScope(observed, scope);
                    preserveIsolatedStatus(observed);
                    originalStatus[0] = text(observed, "status");
                });
                boolean pause = !"online".equals(text(command, "consistent"));
                boolean running = !"已停止".equals(originalStatus[0]);
                if (pause && running) {
                    stage(
                            taskId,
                            "暂停应用写入",
                            10,
                            () -> {
                                execution.service(source, sourceCredentials, false, false, log);
                                setInstance(source, obj("status", "已停止"));
                            });
                } else {
                    skipStage(taskId, "暂停应用写入", "应用已停止或本次使用在线方式。");
                    skipStage(taskId, "恢复备份前应用状态", "本次未停止应用。");
                }
                stage(
                        taskId,
                        backupStage(scope),
                        30,
                        "备份已校验并保存到备份库。",
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
                                execution.service(source, sourceCredentials, true,
                                        "isolated".equals(originalStatus[0]), log);
                                setInstance(source, obj("status", originalStatus[0]));
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
                stage(taskId, "复核备份与恢复目标", 5, () -> {
                    if (!text(command, "vacantTargetId").isBlank())
                        verifyRegisteredDeployment(command, target, targetCredentials,
                                store.get("packages", required(command, "packageId")));
                    else if (bool(target, "freshRestoreTarget") && ComposeManager.isVendorEnterprise(target))
                        verifyRegisteredHost(target, targetCredentials);
                    if (bool(target, "freshRestoreTarget") && !"整体备份".equals(text(backup, "scope")))
                        throw bad("未安装的实例只能从整体备份重建。");
                    if (protectionRecovery) {
                        requireProtectionRestore(command, backup, target, taskId);
                        execution.verifyBackup(backup);
                        ExecutionService.Inspection actual = execution.inspectRecoveryTarget(target, targetCredentials);
                        target.putAll(actual.instance());
                        target.put("protectionRecovery", true);
                        targetCredentials.putAll(actual.credentials());
                        validateVendorProtectionRollback(command, backup, target, targetCredentials);
                    } else if (!text(command, "targetId").isBlank()) {
                        var actual = execution.inspect(target, targetCredentials);
                        execution.validateRestoreTarget(target, actual);
                        target.putAll(actual.instance());
                        targetCredentials.putAll(actual.credentials());
                    }
                    execution.validateRestore(backup, target, targetCredentials);
                });
                var recoveryStages = new ArrayList<String>();
                if (protectionRecovery && Boolean.FALSE.equals(target.get("metadataDatabasePresent")))
                    recoveryStages.add("重建缺失的元数据库");
                if (protectionRecovery && !text(backup, "version").equals(text(target, "version")))
                    recoveryStages.add("回退应用至保护备份版本");
                if (!recoveryStages.isEmpty()) {
                    var stages = new ArrayList<>(maps(store.get("tasks", taskId).get("stages")));
                    int beforeRestore = 0;
                    while (beforeRestore < stages.size()
                            && !"还原所选备份内容".equals(text(stages.get(beforeRestore), "title"))) beforeRestore++;
                    stages.addAll(beforeRestore, pendingStages(recoveryStages));
                    updateTask(taskId, obj("stages", stages));
                }
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
                                    requireProtectionRestore(command, backup, target, taskId);
                                    execution.verifyBackup(backup);
                                    updateTask(taskId, obj("targetBackupId", backup.get("id"),
                                            "inheritedProtectionTaskId", command.get("protectionTaskId")));
                                    Map<String, Object> resources = saveBackup(
                                            execution.backupRecoveryResources(target, targetCredentials, log),
                                            target, "保护恢复前资源副本", taskId);
                                    updateTask(taskId, obj("targetResourceBackupId", resources.get("id")));
                                    log.accept("已保存目标现有资源副本，数据库与网关将使用原任务的整体保护备份。");
                                    return;
                                }
                                Map<String, Object> protection =
                                        saveBackup(
                                                execution.protectionBackup(target, targetCredentials, log),
                                                target,
                                                "还原前保护备份",
                                                taskId);
                                updateTask(taskId, obj("targetBackupId", protection.get("id")));
                            });
                else {
                    target.put("operationPurpose", "restore");
                    updateTask(taskId, obj("deploymentStarted", true));
                    execution.install(store.get("packages", text(command, "packageId")), target,
                            targetCredentials, false, log, installProgress(taskId, 5, 40));
                }
                if (protectionRecovery && Boolean.FALSE.equals(target.get("metadataDatabasePresent")))
                    stage(taskId, "重建缺失的元数据库", 30, () -> {
                        // Identity, original backup and resource bounds have been checked before
                        // this first database write. Recheck at the destructive boundary.
                        requireProtectionRestore(command, backup, target, taskId);
                        updateTask(taskId, obj("dataChanged", true));
                        execution.createMissingProtectionDatabase(target, targetCredentials, backup);
                    });
                if (!text(command, "protectionTaskId").isBlank()
                        && !text(backup, "version").equals(text(target, "version"))) {
                    requireProtectionRestore(command, backup, target, taskId);
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
                Map<String, Object> installationMedia = store.get("packages", text(command, "packageId"));
                if (!text(command, "vacantTargetId").isBlank())
                    stage(taskId, "复核已登记服务器", 2,
                            () -> verifyRegisteredDeployment(command, target, targetCredentials, installationMedia));
                if ("install".equals(kind) && DataEaseOfflinePackage.ADAPTER.equals(installationMedia.get("adapter"))) {
                    // DataEase password changes allow at most 20 characters.
                    byte[] random = new byte[12];
                    new java.security.SecureRandom().nextBytes(random);
                    String password = "De9!" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                    String reference = vault.put(Map.of("password", password));
                    target.put("initialAdminCredentialRef", reference);
                    setInstance(target, obj("initialAdminCredentialRef", reference));
                    targetCredentials.put("initialAdminPassword", password);
                }
                if (kind.equals("upgrade")) {
                    stage(taskId, "复核升级磁盘空间", 5, () -> {
                        var actual = execution.inspect(source, sourceCredentials);
                        execution.validateRestoreTarget(source, actual);
                        if (ComposeManager.isVendorEnterprise(source))
                            execution.validateVendorRelease(store.get("packages", required(command, "vendorRollbackPackageId")),
                                    installationMedia, actual.instance(), actual.credentials(), false);
                        String capacity = execution.validateUpgradeSpace(installationMedia, actual.instance(), actual.credentials());
                        updateCurrentStage(taskId, capacity, false);
                        log.accept(capacity);
                    });
                    stage(
                            taskId,
                            "停服并建立升级保护备份",
                            10,
                            () -> {
                                var running = execution.runningMigrationServices(source, sourceCredentials);
                                execution.service(source, sourceCredentials, false, false, log);
                                setInstance(source, obj("status", "已停止"));
                                try {
                                    Map<String, Object> backup = saveBackup(
                                            execution.protectionBackup(source, sourceCredentials, log),
                                            source, "升级保护备份", taskId);
                                    updateTask(taskId, obj("targetBackupId", backup.get("id")));
                                } catch (Exception failure) {
                                    if (!uncertainCompletion(failure)) try {
                                        execution.restoreMigrationSource(source, sourceCredentials, running, log);
                                        updateTask(taskId, obj("upgradeServiceRecovered", true));
                                        setInstance(source, obj("status", source.get("status")));
                                    } catch (Exception recoveryFailure) { failure.addSuppressed(recoveryFailure); }
                                    throw failure;
                                }
                            });
                }
                updateTask(taskId, obj("dataChanged", true));
                execution.install(installationMedia,
                        target, targetCredentials, kind.equals("upgrade"), log, installProgress(taskId));
                stage(
                        taskId,
                        "隔离启动并核对环境",
                        90,
                        "应用已在隔离环境启动并通过运行核验。",
                        () -> execution.service(target, targetCredentials, true, true, line -> {
                            updateCurrentStage(taskId, line, false);
                            log.accept(line);
                        }));
                if (targetCredentials.containsKey("initialAdminPassword")) {
                    stage(taskId, "初始化并核验 DataEase 管理员", 91, "已通过 DataEase 接口核实安装时生成的密码。",
                            () -> execution.initializeAdmin(target, targetCredentials, targetCredentials.get("initialAdminPassword")));
                    target.put("hasInitialAdmin", true);
                    setInstance(target, obj("hasInitialAdmin", true));
                }
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
                                            execution.protectionBackup(source, sourceCredentials, log),
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
                            Map<String, Object> result =
                                    execution.installPlugin(
                                            store.get("plugins", text(command, "packageId")),
                                            target,
                                            targetCredentials,
                                            secrets(command),
                                            text(map(command, "config"), "apiPrefix"),
                                            () -> updateTask(taskId, obj("dataChanged", true)),
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
                                    normalizedChecks(execution.health(source, sourceCredentials, taskChecks(taskId)));
                            Map<String, Object> report =
                                    obj(
                                            "instanceId",
                                            source.get("id"),
                                            "checkedAt",
                                            now(),
                                            "checks",
                                            results);
                            preserveIsolatedStatus(source);
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
                            updateTask(taskId, obj("report", report, "currentCheck", "", "liveChecks", List.of()));
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
                    var selected = strings(command.get("selectedJobs"));
                    Map<String, Object> result = execution.restoreAutomation(source, sourceCredentials,
                            text(command, "automationSnapshot"), selected);
                    Map<String, Object> flags = new LinkedHashMap<>(pausedAutomation());
                    for (var row : maps(result.get("jobs")))
                        if (bool(row, "enabled") && flags.containsKey(text(row, "category"))) flags.put(text(row, "category"), true);
                    setInstance(source, obj("automation", flags, "automationCheckedAt", now()));
                    updateTask(taskId, obj("automationResult", result));
                    if (selected.isEmpty()) {
                        int running = result.get("runningJobs") instanceof Number count ? count.intValue() : 0;
                        log.accept(running == 0 ? "已回读确认：自动调度已暂停。"
                                : "已回读确认：后续自动调度已暂停，仍有 " + running + " 个已开始的任务在执行。");
                    } else {
                        log.accept("已回读确认：仅所选任务获准运行。应用重启后授权失效，需重新核验。");
                    }
                });
                succeed(taskId);
            } else if (kind.equals("uninstall")) {
                var retained = new LinkedHashMap<String, Object>();
                var ssh = sshCredentials(targetCredentials);
                stage(taskId, "核对卸载目标与安装归属", 10, "服务器、安装目录及 Compose 项目归属已核对。",
                        () -> {
                            execution.validateUninstall(target, targetCredentials);
                            verifyRegisteredHost(target, ssh);
                            copyHostFields(target, retained);
                            if (!retained.containsKey("machineIdentity"))
                                retained.putAll(execution.inspectHost(target, ssh));
                            retained.putAll(obj("id", target.get("id"), "name", target.get("name"),
                                    "environment", target.get("environment"), "status", NOT_INSTALLED,
                                    "createdAt", target.getOrDefault("createdAt", now()),
                                    "revision", number(target, "revision") + 1,
                                    "credentialRef", vault.put(ssh)));
                            retained.put("connectionProfile", profile(retained, number(retained, "revision")));
                        });
                stage(taskId, "移除实例容器与闲置镜像，归档数据", 45, "实例数据已归档，Docker 与 Compose 仍可用。",
                        () -> {
                            updateTask(taskId, obj("dataChanged", true));
                            execution.uninstall(target, targetCredentials, log);
                        });
                synchronized (this) {
                    var finished = store.get("tasks", taskId);
                    finished.putAll(obj("state", "succeeded", "status", "成功", "progress", 100,
                            "endedAt", now(), "duration", duration(finished)));
                    String pendingId = text(command, "pendingInstallId");
                    Map<String, Object> pending = pendingId.isBlank() ? null : store.get("tasks", pendingId);
                    if (pending != null)
                        pending.putAll(obj("state", "failed", "status", "失败", "endedAt", now(),
                                "failureStage", "目标已卸载", "error", "目标实例已卸载，安装验收终止。"));
                    store.finishUninstall(retained, finished, pending);
                }
            } else if (kind.equals("service")) {
                boolean start = "start".equals(text(command, "action"));
                stage(
                        taskId,
                        start ? "启动应用" : "停止应用",
                        20,
                        () -> {
                            Map<String, Object> runtime = source;
                            if (start) {
                                var observed = execution.inspectForService(source, sourceCredentials);
                                if (!versionMatches(source, observed.instance()))
                                    throw conflict("实际版本或安装目录已变化，请重新检查。");
                                runtime = new LinkedHashMap<>(source);
                                runtime.put("migrationDatabaseStopped", bool(observed.instance(), "migrationDatabaseStopped"));
                            }
                            execution.service(runtime, sourceCredentials, start,
                                    "isolated".equals(text(source, "accessMode")), log);
                        });
                setInstance(
                        source,
                        obj(
                                "status",
                                start
                                        ? ("isolated".equals(text(source, "accessMode"))
                                                ? "隔离运行"
                                                : "运行中")
                                        : "已停止", "automation", pausedAutomation(),
                                "migrationDatabaseStopped", !start && bool(source, "migrationDatabaseStopped")));
                succeed(taskId);
            } else throw bad("该操作尚无经过验证的执行适配器，未执行任何变更。");
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted() || hasInterruptCause(failure);
            try {
                String sourceRecovery = "";
                boolean changed = bool(store.get("tasks", taskId), "dataChanged");
                boolean targetRestored = false;
                if (sourceRunningBeforeMigration != null && !sourceRunningBeforeMigration.isEmpty()
                        && !uncertainCompletion(failure)) {
                    Map<String, Object> originalSource = source(command);
                    try {
                        appendLog(taskId, "迁移中止，正在恢复迁移前运行的 V2 服务。");
                        if (sameHostMigration(command)) {
                            var failedTarget = store.get("instances", text(store.get("tasks", taskId), "targetId"));
                            if (!text(failedTarget, "databaseIdentity").isBlank())
                                execution.stopFailedMigrationTarget(failedTarget, secrets(failedTarget), log);
                        }
                        execution.restoreMigrationSource(originalSource, secrets(originalSource), sourceRunningBeforeMigration, log);
                        if (sourceRunningBeforeMigration.contains(text(originalSource, "applicationService")))
                            setInstance(originalSource, obj("status",
                                    "isolated".equals(text(originalSource, "accessMode")) ? "隔离运行" : "运行中",
                                    "automationCheckedAt", ""));
                        sourceRecovery = " 已恢复迁移前运行的 V2 服务。";
                    } catch (Exception recoveryFailure) {
                        failure.addSuppressed(recoveryFailure);
                        interrupted |= hasInterruptCause(recoveryFailure);
                        setInstance(originalSource, obj("status", "待检查"));
                        sourceRecovery = " 未能确认 V2 恢复，请检查源服务。";
                        appendLog(taskId, "未能确认 V2 服务恢复：" + safeError(recoveryFailure, command));
                    }
                }
                if (targetRunningBeforeMigration != null && !changed && !uncertainCompletion(failure)) {
                    Map<String, Object> originalTarget = map(task, "targetInstanceSnapshot");
                    try {
                        execution.restoreMigrationSource(originalTarget, secrets(originalTarget), targetRunningBeforeMigration, log);
                        setInstance(originalTarget, obj("status", originalTarget.get("status"),
                                "automation", pausedAutomation(), "automationCheckedAt", ""));
                        targetRestored = true;
                    } catch (Exception recoveryFailure) {
                        interrupted |= hasInterruptCause(recoveryFailure);
                        appendLog(taskId, "未能确认 V3 恢复：" + safeError(recoveryFailure, command));
                    }
                }
                if (MUTATIONS.contains(kind) && uncertainCompletion(failure)
                        && !upgradeReadOnlyStage(store.get("tasks", taskId))) {
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
                                        + (kind.equals("install")
                                                ? " 请按任务日志核对本次新建的容器和目录。清理遗留部署后可对同一目标重新预检查。"
                                                : kind.equals("uninstall")
                                                ? " 请核对目标机容器和归档状态，实例登记已保留。"
                                                : kind.equals("automation")
                                                ? " 自动任务状态请以实时清单为准；执行结果未确认时请先现场核查。"
                                                : kind.equals("backup")
                                                ? text(latest, "backupId").isBlank()
                                                        ? " 请核对备份阶段和源实例状态。"
                                                        : " 备份已校验入库，请核对应用是否已恢复运行。"
                                                : kind.equals("check") || kind.equals("service")
                                                ? " 请核对失败阶段和实例状态。"
                                                : kind.equals("migrate") && !sourceRecovery.isBlank()
                                                ? sourceRecovery + (changed ? " 请在任务详情还原 V3 目标保护备份。"
                                                        : targetRestored ? " 已恢复 V3 迁移前状态。" : " 请核对 V3 状态后重试。")
                                                : sameHostMigration(command)
                                                ? " 源 V2 数据保留。" + (sourceRecovery.isBlank()
                                                        ? " 请核对 V2 服务状态和 V3 部署进度。" : sourceRecovery)
                                                : bool(latest, "deploymentStarted")
                                                ? " 请核对重建目标的部署及还原进度，原备份已保留。"
                                                : bool(latest, "dataChanged")
                                                ? " 目标可能已部分写入，请使用本任务目标保护备份恢复。若应用版本发生变化，请先核对并更新连接识别。"
                                                : " 已在目标写入前中止，请核对应用状态后重新预检查。"));
                        List<Map<String, Object>> stages = maps(latest.get("stages"));
                        for (Map<String, Object> stage : stages)
                            if (text(stage, "title").equals(text(latest, "currentStage"))) {
                                stage.put("status", "failed");
                                stage.put("detail", reason);
                                stage.put("endedAt", now());
                            }
                        latest.put("stages", stages);
                        var registration = map(command, "registeredHostSnapshot");
                        if (!registration.isEmpty() && !bool(latest, "dataChanged") && !bool(latest, "deploymentStarted")) {
                            store.saveInstanceAndTask(registration, latest);
                        } else {
                            var affected = MUTATIONS.contains(kind) && !kind.equals("automation") && !targetRestored
                                    && !bool(latest, "upgradeServiceRecovered")
                                    && !upgradeReadOnlyStage(latest)
                                    ? store.find("instances", text(latest, "targetId")) : Optional.<Map<String, Object>>empty();
                            if (affected.isPresent()) {
                                var instance = affected.get();
                                instance.putAll(obj("status", "待检查", "health", "未检查", "accessMode", "unknown",
                                        "recoveryRequired", bool(instance, "recoveryRequired")
                                                || bool(latest, "dataChanged") || bool(latest, "deploymentStarted")));
                                store.saveInstanceAndTask(instance, latest);
                            } else store.put("tasks", taskId, latest);
                        }
                    }
                    appendLog(taskId, "操作失败：" + safeError(failure, command));
                }
            } finally {
                if (kind.equals("plugin") && !text(command, "credentialRef").isBlank()) {
                    try { vault.delete(text(command, "credentialRef")); }
                    catch (IllegalStateException error) { LOG.warn("Could not remove completed plugin credentials"); }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void createMigrationProtection(String taskId, Map<String, Object> instance,
            Map<String, String> credentials, boolean sameHost, boolean source, Consumer<String> log) throws Exception {
        var backup = saveBackup(migrationBackup(instance, credentials, sameHost, log), instance,
                source ? "迁移源端保护备份" : "迁移目标保护备份", taskId);
        updateTask(taskId, obj(source ? "sourceBackupId" : "targetBackupId", backup.get("id")));
    }

    private List<Map<String, Object>> checks(Map<String, Object> command) throws Exception {
        return checks(command, event -> {});
    }

    private static final class PrecheckChecks {
        private final List<Map<String, Object>> results = new ArrayList<>();
        private final Consumer<Map<String, Object>> progress;

        private PrecheckChecks(Consumer<Map<String, Object>> progress) {
            this.progress = progress;
        }

        void start(String title) {
            progress.accept(Map.of("type", "running", "title", title));
        }

        void add(Map<String, Object> check) {
            Map<String, Object> result = CheckGuidance.apply(check);
            results.add(result);
            progress.accept(Map.of("type", "check", "check", result));
        }
    }

    private List<Map<String, Object>> checks(
            Map<String, Object> command, Consumer<Map<String, Object>> progress) throws Exception {
        PrecheckChecks checks = new PrecheckChecks(progress);
        String kind = text(command, "kind");
        boolean sameHost = sameHostMigration(command);
        Map<String, Object> source = source(command), target = target(command);
        boolean freshRestore = kind.equals("restore") && text(command, "targetId").isBlank();
        boolean protectionRestore = kind.equals("restore") && !text(command, "protectionTaskId").isBlank();
        if (protectionRestore) {
            checks.start("原任务保护恢复");
            try {
                requireProtectionRestore(command, store.get("backups", required(command, "backupId")), target, "");
            } catch (Exception error) {
                checks.add(check("原任务保护恢复", safeError(error, command), false));
                return checks.results;
            }
        }
        Map<String, Object> recoveryPoint = freshRestore
                ? store.get("backups", required(command, "backupId")) : Map.of();
        if (freshRestore && !BackupService.canRebuild(recoveryPoint)) {
            checks.add(check("可用恢复点", "重建服务器需要可还原的整体备份。企业版请使用暂停写入后创建的整体备份。", false));
            return checks.results;
        }
        Map<String, Object> verifiedInstall = null;
        if (kind.equals("install") || sameHost || freshRestore) {
            checks.start("离线包入库状态");
            try {
                verifiedInstall = media.verifiedPackage(store.get("packages", required(command, "packageId")));
                if (freshRestore) {
                    boolean enterpriseBackup = BackupService.ORIGINAL_ENTERPRISE.equals(recoveryPoint.get("restoreMode"));
                    if (enterpriseBackup != "enterprise".equals(verifiedInstall.get("edition"))
                            || enterpriseBackup && !DataEaseOfflinePackage.ADAPTER.equals(verifiedInstall.get("adapter"))
                            || !Objects.equals(verifiedInstall.get("version"), recoveryPoint.get("version"))
                            || !Objects.equals(verifiedInstall.get("arch"), recoveryPoint.get("arch")))
                        throw bad("请选择与备份版本、架构及版本类型匹配的完整离线包。");
                    DataEaseOfflinePackage.requireManagedDeployment(verifiedInstall, kind);
                }
                checks.add(check("离线包入库状态", text(verifiedInstall, "version") + " · "
                        + text(verifiedInstall, "arch") + "，已通过入库校验。", true));
            } catch (Exception error) {
                checks.add(check("离线包入库状态", safeError(error, command), false));
                return checks.results;
            }
        }
        for (String instanceId : operationIds(command)) {
            boolean busy = isLocked(instanceId);
            Map<String, Object> lock = check("实例互斥", busy ? "该实例有执行中任务或待验收变更" : "没有互斥任务", !busy);
            if (busy) store.list("tasks").stream().filter(task -> ACTIVE.contains(text(task, "state"))
                    && (instanceId.equals(text(task, "instanceId")) || instanceId.equals(text(task, "targetId"))))
                .findFirst().ifPresent(task -> lock.put("remediation", obj("type", "task", "taskId", task.get("id"),
                        "label", "查看占用任务", "hint", "该实例被执行中或待验收任务占用。请先处理该任务，平台不会并发覆盖实例。")));
            checks.add(lock);
        }
        if (!checks.results.isEmpty() && !passed(checks.results)) return checks.results;
        if (vacant(source) || vacant(target)) {
            checks.add(check("实例安装状态", "该实例尚未安装 DataEase，请先安装或从整体备份还原。", false));
            return checks.results;
        }
        if (!source.isEmpty()) {
            checks.start("源环境识别");
            boolean matches = false;
            try {
                ExecutionService.Inspection found = "service".equals(kind)
                        ? execution.inspectForService(source, secrets(source))
                        : "backup".equals(kind)
                                ? execution.inspectBackupSource(source, secrets(source), text(command, "scope"))
                                : execution.inspect(source, secrets(source));
                matches = versionMatches(source, found.instance());
                source.putAll(cleanConfig(found.instance()));
                preserveIsolatedStatus(source);
                if (!text(source, "id").isBlank() && text(source, "id").equals(text(target, "id")))
                    target.putAll(source);
                checks.add(
                        check(
                                "源环境识别",
                                matches
                                        ? text(found.instance(), "version")
                                                + " · "
                                                + text(source, "status")
                                        : "实际版本或安装目录已变化，请重新接入或维护连接",
                                matches));
            } catch (ComposeManager.InstallationFilesUnavailable error) {
                checks.add(check("源环境安装文件", error.getMessage(), false));
                return checks.results;
            } catch (Exception error) {
                checks.add(check("源环境连接", safeError(error, command), false));
            }
            if ("backup".equals(kind)) {
                if (!matches) return checks.results;
                checks.start("备份范围");
                try {
                    execution.requireBackupScope(source, text(command, "scope"));
                    String detail = ComposeManager.isManagedEnterprise(source) && !ComposeManager.isVendorEnterprise(source)
                            ? "此部署的备份仅供校验和导出。"
                            : "已核对所选范围，可以创建备份。";
                    checks.add(check("备份范围", detail, true));
                } catch (Exception error) {
                    var blocked = check("备份范围", safeError(error, command), false);
                    blocked.put("remediation", obj("type", "backup-resources", "label", "改为资源备份",
                            "hint", "资源备份会保存安装目录中的资源和插件文件。数据库和外部引擎请分别备份。"));
                    checks.add(blocked);
                    return checks.results;
                }
            }
        }
        if (kind.equals("migrate") && passed(checks.results)) {
            checks.start("源 V2 的 Excel 已导入数据");
            try {
                int sources = execution.validateMigrationExcelTables(source, secrets(source));
                checks.add(check("源 V2 的 Excel 已导入数据", sources > 0
                        ? "已核对 " + sources + " 个本地 Excel 数据源的引擎表，随数据库迁移。"
                        : "未发现本地 Excel 数据源。", true));
            } catch (Exception error) {
                checks.add(check("源 V2 的 Excel 已导入数据", safeError(error, command), false));
                return checks.results;
            }
        }
        if (sameHost) {
            if (!passed(checks.results)) return checks.results;
            checks.start("同机迁移条件");
            try {
                validateSameHostMigration(source, target, verifiedInstall, command);
                execution.requireBackupScope(source, "整体备份");
                execution.validateDatabaseAutomation(source, secrets(source));
                checks.add(check("同机迁移条件", "已核对 V2 的部署和整体备份范围。新 V3 初始化后再核验权限表、同步任务和数据库权限。", true));
            } catch (Exception error) {
                checks.add(check("同机迁移条件", safeError(error, command), false));
                return checks.results;
            }
        }
        if (sameHost || Set.of("install", "restore").contains(kind) && text(command, "targetId").isBlank()) {
            checks.start("连接目标服务器");
            try {
                var credentials = targetCredentials(command, target);
                verifyRegisteredHost(target, credentials);
                if (bool(target, "freshRestoreTarget") && ComposeManager.isVendorEnterprise(target)
                        && text(command, "vacantTargetId").isBlank()) {
                    var observed = execution.inspectHost(target, credentials);
                    String machineIdentity = text(observed, "machineIdentity");
                    String plannedIdentity = text(command, "targetMachineIdentity");
                    if (!plannedIdentity.isBlank() && !plannedIdentity.equals(machineIdentity))
                        throw conflict("目标服务器身份已变化，请重新检查。");
                    target.put("machineIdentity", machineIdentity);
                    command.put("targetMachineIdentity", machineIdentity);
                }
                execution.precheckTarget(target, credentials,
                        verifiedInstall == null ? Map.of() : verifiedInstall, checks::start, checks::add);
                String identity = text(target, "hostIdentity");
                if (identity.isBlank())
                    checks.add(check("目标主机身份", "无法确认实际Docker主机身份，不能预留新部署目标。", false));
                else if (sameHost && (!identity.equals(text(source, "hostIdentity"))
                        || bool(target, "runtimeBootstrap")))
                    checks.add(check("目标主机身份", "目标须使用 V2 当前所在的 Docker 主机。", false));
                else command.put("targetHostIdentity", identity);
                command.put("runtimeBootstrap", Boolean.TRUE.equals(target.get("runtimeBootstrap")));
            } catch (Exception error) {
                checks.add(check("新目标运行环境", safeError(error, command), false));
            }
            for (Map<String, Object> existing : store.list("instances"))
                if (!text(existing, "id").equals(text(command, "vacantTargetId"))
                        && samePhysicalHost(existing, target)
                        && !(sameHost && text(existing, "id").equals(text(source, "id")))
                        && !(kind.equals("install") && canRetryInstall(existing, target)))
                    checks.add(check("目标主机隔离", sameHost
                            ? "源主机上还有其他已接入的 DataEase 实例，请使用单独的 V3 目标。"
                            : "安装需要没有 DataEase 部署的主机，请选择空服务器。", false));
        } else if (!target.isEmpty() && !Objects.equals(text(source, "id"), text(target, "id"))) {
            checks.start("目标环境识别");
            try {
                boolean protectionRecovery = kind.equals("restore")
                        && !text(command, "protectionTaskId").isBlank();
                if (protectionRecovery) {
                    Map<String, Object> backup = store.get("backups", required(command, "backupId"));
                    requireProtectionRestore(command, backup, target, "");
                    execution.verifyBackup(backup);
                }
                ExecutionService.Inspection found =
                        protectionRecovery
                                ? execution.inspectRecoveryTarget(target, targetCredentials(command, target))
                                : execution.inspect(target, targetCredentials(command, target));
                boolean matches = versionMatches(target, found.instance());
                target.putAll(cleanConfig(found.instance()));
                preserveIsolatedStatus(target);
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
                                                + text(target, "status")
                                        : "目标实际版本或安装目录已变化",
                                matches));
            } catch (ComposeManager.InstallationFilesUnavailable error) {
                checks.add(check("目标环境安装文件", error.getMessage(), false));
                return checks.results;
            } catch (Exception error) {
                checks.add(check("目标连接", safeError(error, command), false));
            }
        }
        if (kind.equals("automation")) {
            checks.start("验收与自动任务恢复范围");
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
        if (kind.equals("backup") && text(command, "destination").equals("local"))
            checks.add(check("备份存储位置", "当前执行层仅支持运维平台受管备份库，请选择平台备份库。", false));
        if (kind.equals("backup") && text(command, "consistent").equals("online")
                && "整体备份".equals(text(command, "scope")))
            checks.add(
                    obj(
                            "title",
                            "在线一致性限制",
                            "detail",
                            ComposeManager.isVendorEnterprise(source)
                                    ? "在线归档不含网关状态，仅供校验和导出。要用于整体还原，请选择停止应用后备份。"
                                    : "在线备份无法保证元数据库与资源同一时刻，恢复后必须核对资源匹配。",
                            "status",
                            "warning"));
        if (Set.of("migrate", "restore", "install").contains(kind)
                && text(target, "name").isBlank()) checks.add(check("目标身份", "请填写完整目标实例名称。", false));
        if (kind.equals("migrate") && !sameHost) {
            checks.start("迁移版本、目录与数据库隔离");
            try {
                execution.validateMigration(source, target);
                checks.add(check("迁移版本、目录与数据库隔离", "V2/MySQL → V3/MySQL；源与目标目录、数据库独立", true));
                checks.start("核心表、权限类型与同步任务");
                execution.validateMigrationSchemas(source, secrets(source), target, targetCredentials(command, target));
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
            checks.start("插件身份、兼容性与应用授权");
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
                    (sameHost || Set.of("install", "restore").contains(kind))
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
        if (!sameHost && Set.of("migrate", "upgrade", "plugin").contains(kind)) {
            boolean coverage = coverageReady(source);
            checks.add(
                    check(
                            "源端备份覆盖",
                            coverage ? "元数据库、受管引擎、资源、插件和配置可保护" : protectionCoverageBlock(source),
                            coverage));
        }
        if ((Set.of("upgrade", "plugin").contains(kind)
                || kind.equals("backup") && !"资源备份".equals(text(command, "scope")))
                && "MySQL".equals(text(source, "dbType"))) {
            checks.start("数据库自动任务");
            try {
                execution.validateDatabaseAutomation(source, secrets(source));
                checks.add(check("数据库自动任务", "未发现启用的MySQL事件任务", true));
            } catch (Exception error) {
                checks.add(check("数据库自动任务", safeError(error, command), false));
            }
        }
        if (kind.equals("migrate") && !sameHost || kind.equals("upgrade")) {
            boolean coverage = coverageReady(target);
            checks.add(check("目标保护覆盖", coverage ? "目标可创建整体保护备份" : protectionCoverageBlock(target), coverage));
        }
        if ((kind.equals("install") && verifiedInstall != null) || kind.equals("upgrade")
                || (kind.equals("restore") && text(command, "targetId").isBlank())) {
            checks.start("离线包与目标配置");
            try {
                Map<String, Object> pkg = store.get("packages", required(command, "packageId"));
                validatePackage(pkg, target, kind, source, command, verifiedInstall);
                if (kind.equals("upgrade") && ComposeManager.isVendorEnterprise(target)) {
                    var candidates = store.list("packages").stream().filter(item -> "已校验".equals(text(item, "status"))
                            && "enterprise".equals(text(item, "edition")) && text(item, "version").equals(text(target, "version"))
                            && text(item, "arch").equals(text(target, "arch"))).toList();
                    if (candidates.size() != 1) throw bad("请在软件包库保留唯一一份与当前实例版本、架构匹配的企业版完整离线包，用于回退。");
                    var rollback = candidates.get(0);
                    execution.validateVendorRelease(rollback, pkg, target, targetCredentials(command, target), false);
                    command.put("vendorRollbackPackageId", rollback.get("id"));
                }
                checks.add(
                        check("离线包与目标配置", text(pkg, "version") + " · " + text(pkg, "arch"), true));
                if (kind.equals("upgrade")) {
                    checks.start("升级磁盘空间");
                    try {
                        checks.add(check("升级磁盘空间",
                                execution.validateUpgradeSpace(pkg, target, targetCredentials(command, target)), true));
                    } catch (Exception error) {
                        checks.add(check("升级磁盘空间", safeError(error, command), false));
                    }
                }
            } catch (Exception error) {
                checks.add(check("离线包与目标配置", safeError(error, command), false));
            }
        }
        if (kind.equals("restore")) {
            checks.start("恢复点与目标兼容性");
            try {
                Map<String, Object> backup = store.get("backups", required(command, "backupId"));
                if ("manual".equals(text(backup, "restoreMode")))
                    throw bad("此备份仅供校验和导出。请暂停原厂企业版实例后重新创建整体备份。");
                if (!text(command, "targetId").isBlank()
                        && text(command, "protectionTaskId").isBlank() && !coverageReady(target))
                    throw bad(protectionCoverageBlock(target));
                target.put(
                        "restorePurpose",
                        text(command, "restorePurpose").isBlank()
                                ? text(command, "purpose")
                                : text(command, "restorePurpose"));
                validateVendorProtectionRollback(command, backup, target, targetCredentials(command, target));
                var verified = execution.validateRestore(backup, target, targetCredentials(command, target));
                if (!text(command, "targetId").isBlank()
                        && !"资源备份".equals(text(verified, "scope"))) {
                    checks.start("目标数据库重建授权");
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
                                && text(verified, "version").equals(text(target, "version"))
                                && text(verified, "dbType").equals(text(target, "dbType"))
                                && text(verified, "arch").equals(text(target, "arch"));
                if (!text(command, "protectionTaskId").isBlank()) {
                    requireProtectionRestore(command, backup, target, "");
                    if (!text(backup, "version").equals(text(target, "version"))) {
                        checks.start("保护恢复版本回退");
                        execution.validateRollbackPackage(
                                store.get("packages", required(command, "packageId")),
                                backup,
                                target);
                        valid =
                                "已校验".equals(text(backup, "status"))
                                        && text(verified, "dbType").equals(text(target, "dbType"))
                                        && text(verified, "arch").equals(text(target, "arch"));
                        checks.add(check("保护恢复版本回退", "先建立当前目标保护备份，再离线回退应用版本并还原原保护数据", valid));
                    }
                }
                checks.add(
                        check(
                                "恢复点与目标兼容性",
                                valid ? "备份摘要、精确版本、数据库及架构匹配" : "恢复目标须匹配备份精确版本、架构及数据库；新环境仅接受整体备份",
                                valid));
                if ("rehearsal".equals(text(command, "restorePurpose"))
                        && !text(command, "targetId").isBlank()
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
        boolean isolatedBuiltinTarget = verifiedInstall != null
                && DataEaseOfflinePackage.ADAPTER.equals(verifiedInstall.get("adapter"))
                && "builtin".equals(text(target, "dbMode"));
        if (Set.of("migrate", "restore", "upgrade", "install").contains(kind)) {
            Set<String> excluded = operationIds(command);
            if (kind.equals("restore"))
                excluded.add(
                        text(store.get("backups", required(command, "backupId")), "instanceId"));
            for (Map<String, Object> other : store.list("instances"))
                if (!excluded.contains(text(other, "id"))) {
                    if (kind.equals("install") && canRetryInstall(other, target)) continue;
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
                    if (sameName && !isolatedBuiltinTarget
                            && text(other, "databaseIdentity").isBlank()
                            && "MySQL".equals(text(other, "dbType"))) {
                        checks.start("共享元数据库识别");
                        try {
                            observed = execution.inspect(other, secrets(other)).instance();
                            checks.add(check("共享元数据库识别", "已核对其他环境的数据库身份。", true));
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
        if (checks.results.isEmpty())
            checks.add(check("操作目标", "已确认操作对象", !source.isEmpty() || !target.isEmpty()));
        return checks.results;
    }

    private void validatePackage(
            Map<String, Object> pkg,
            Map<String, Object> target,
            String kind,
            Map<String, Object> source,
            Map<String, Object> command,
            Map<String, Object> verifiedInstall)
            throws Exception {
        Map<String, Object> verified = verifiedInstall == null ? media.verifiedPackage(pkg) : verifiedInstall;
        if (!Set.of("verified", "已校验").contains(text(verified, "status")))
            throw bad(text(verified, "validationMessage").isBlank() ? "离线包尚未通过校验。" : text(verified, "validationMessage"));
        if (kind.equals("upgrade")) {
            if (!major(text(pkg, "version")).equals(major(text(source, "version"))))
                throw bad("普通升级必须在相同主版本内，V2 → V3 请使用迁移。");
            if (ComposeManager.isManagedEnterprise(target) != "enterprise".equals(verified.get("edition"))
                    || ComposeManager.isManagedEnterprise(target) && !ComposeManager.isVendorEnterprise(target))
                throw bad("升级包须与当前部署的版本类型一致，且企业版须由运维中心使用原厂离线包部署。");
        }
        if (kind.equals("upgrade") && compareVersion(text(pkg, "version"), text(source, "version")) <= 0)
            throw bad("升级目标版本必须高于当前版本。");
        DataEaseOfflinePackage.requireManagedDeployment(verified, kind);
        String dbType = text(target, "dbType");
        if ("external".equals(text(target, "dbMode")))
            throw bad("当前无法验证外部 " + dbType + " 的隔离启动。请选择内置 MySQL，或完成部署后接入实例。");
        if (!"MySQL".equals(dbType))
            throw bad("当前自动部署支持 MySQL，所选数据库为 " + dbType + "。");
        if (!normalizeArch(text(verified, "arch")).equals(normalizeArch(text(target, "arch"))))
            throw bad("离线包架构为 " + text(verified, "arch") + "，目标配置为 " + text(target, "arch") + "。");
        List<String> dbTypes = strings(verified.get("dbTypes"));
        if (!dbTypes.contains(dbType))
            throw bad("所选数据库为 " + dbType + "，离线包支持 " + String.join("、", dbTypes) + "。");
        if (DataEaseOfflinePackage.ADAPTER.equals(verified.get("adapter")) && !kind.equals("upgrade")) {
            Map<String, String> credentials = targetCredentials(command, target);
            if (!credentials.getOrDefault("dbUser", text(target, "dbUser")).equals("root")
                    || credentials.getOrDefault("dbPassword", "").length() < 8)
                throw bad("内置MySQL安装需root管理账号和至少8个字符的密码。");
            if (!text(target, "dbName").matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                throw bad("新数据库名称仅支持字母、数字与下划线。");
            if ("builtin".equals(text(target, "dbMode")))
                execution.validateBuiltinDatabaseAddress(target, credentials);
        } else if (!DataEaseOfflinePackage.ADAPTER.equals(verified.get("adapter"))
                && "builtin".equals(text(target, "dbMode"))) {
            throw bad("该介质未提供平台支持的内置 MySQL 部署配置。");
        }
        if (kind.equals("upgrade")) {
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
        if (!strings(verified.get("purposes")).isEmpty() && !strings(verified.get("purposes")).contains(kind))
            throw bad("所选离线包未声明允许本次用途。");
    }

    private void validateSameHostMigration(Map<String, Object> source, Map<String, Object> target,
            Map<String, Object> pkg, Map<String, Object> command) throws Exception {
        if (!ComposeManager.isManagedEnterprise(source) || !"ssh".equals(text(source, "connection"))
                || !"MySQL".equals(text(source, "dbType")) || text(source, "composeProject").isBlank()
                || text(source, "databaseService").isBlank()
                || text(source, "hostIdentity").isBlank() || text(source, "databaseIdentity").isBlank())
            throw bad("同机迁移需要已接入的单机企业版 V2、内置 MySQL 和可用的 SSH 连接。");
        if (!text(source, "version").matches("v?2\\.\\d+\\.\\d+")
                || compareVersion(text(source, "version"), "v2.10.26") < 0)
            throw bad("同机迁移支持 v2.10.26 及以上的 V2 实例。");
        if (!ComposeManager.isVendorEnterprise(target) || !text(pkg, "version").matches("v?3\\.\\d+\\.\\d+"))
            throw bad("请选择 V3 企业版完整离线安装包。");
        requireEnvironment(text(target, "environment"));
        if (text(target, "name").isBlank() || text(target, "name").equals(text(source, "name")))
            throw bad("请为新 V3 填写与源实例不同的名称。");
        if (text(target, "port").equals(text(source, "port")))
            throw bad("新 V3 的业务端口须与 V2 不同，以便保留原实例。");
        validatePackage(pkg, target, "install", source, command, pkg);
    }

    private void requireProtectionRestore(
            Map<String, Object> command, Map<String, Object> backup, Map<String, Object> target, String currentTaskId) {
        Map<String, Object> previous = store.get("tasks", required(command, "protectionTaskId"));
        if (!"failed".equals(text(previous, "state"))
                || !currentDeployment(target, previous)
                || !bool(previous, "dataChanged")
                || !text(previous, "targetId").equals(text(target, "id"))
                || !text(previous, "targetBackupId").equals(text(backup, "id"))
                || !text(backup, "instanceId").equals(text(target, "id"))
                || !"整体备份".equals(text(backup, "scope"))
                || !Set.of("upgrade", "migrate", "restore", "plugin")
                        .contains(text(previous, "kind")))
            throw bad("保护恢复必须绑定已失败变更任务、原目标实例及该任务的整体保护备份。");
        if (!currentRecoveryTask(target, previous, currentTaskId))
            throw conflict("这项失败任务已不是目标实例当前待处理的变更。请从实例管理或最新任务查看恢复入口。");
    }

    private void validateVendorProtectionRollback(Map<String, Object> command, Map<String, Object> backup,
            Map<String, Object> target, Map<String, String> credentials) throws Exception {
        if (!ComposeManager.isVendorEnterprise(target) || text(command, "protectionTaskId").isBlank()
                || text(backup, "version").equals(text(target, "version"))) return;
        var previous = store.get("tasks", text(command, "protectionTaskId"));
        var previousCommand = map(previous, "command");
        if (!"upgrade".equals(text(previous, "kind")) || !required(command, "packageId").equals(text(previousCommand, "vendorRollbackPackageId")))
            throw bad("请选择原升级任务核验的回退离线包。");
        var rollback = store.get("packages", text(command, "packageId"));
        execution.validateRollbackPackage(rollback, backup, target);
        execution.validateVendorRelease(store.get("packages", required(previousCommand, "packageId")), rollback, target, credentials, true);
        target.put("vendorProtectionRollback", true);
    }

    private boolean currentRecoveryTask(Map<String, Object> target, Map<String, Object> failed, String currentTaskId) {
        if (!bool(target, "recoveryRequired")) return false;
        String id = text(failed, "id");
        var tasks = store.list("tasks");
        Collections.reverse(tasks);
        for (var task : tasks) {
            if (currentTaskId.equals(text(task, "id")) || !text(target, "id").equals(text(task, "targetId"))
                    || !currentDeployment(target, task)
                    || !bool(task, "dataChanged")) continue;
            return id.equals(text(task, "id"))
                    || ("restore".equals(text(task, "kind")) && "failed".equals(text(task, "state"))
                            && id.equals(text(task, "inheritedProtectionTaskId"))
                            && text(failed, "targetBackupId").equals(text(task, "targetBackupId")));
        }
        return false;
    }

    public Map<String, Object> task(String id) {
        var task = store.get("tasks", id);
        return taskView(task, store.find("instances", text(task, "targetId")).orElse(null));
    }

    private Map<String, Object> taskView(Map<String, Object> task, Map<String, Object> target) {
        var result = publicRecord(task);
        if (!text(task, "targetId").isBlank())
            result.put("currentTarget", target != null && !vacant(target) && currentDeployment(target, task));
        return result;
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
        var latest = latestAcceptanceChange(instance, history);
        if (accepted(latest, "open")) return;
        if (acceptedInIsolation(latest) && history.stream().anyMatch(task -> "release".equals(text(task, "kind"))
                && text(instance, "id").equals(text(task, "targetId"))
                && text(latest, "id").equals(text(task, "acceptanceTaskId"))
                && accepted(task, "open"))) return;
        throw conflict("最近一次环境变更尚未完成开放验收，请先处理该任务。");
    }

    private static Map<String, Object> latestAcceptanceChange(
            Map<String, Object> instance, List<Map<String, Object>> newestFirst) {
        return newestFirst.stream()
                .filter(task -> text(instance, "id").equals(text(task, "targetId"))
                        && currentDeployment(instance, task)
                        && ACCEPTANCE_KINDS.contains(text(task, "kind")))
                .findFirst().orElse(Map.of());
    }

    private static boolean accepted(Map<String, Object> task, String mode) {
        Map<String, Object> acceptance = map(task, "acceptance");
        return "succeeded".equals(text(task, "state"))
                && mode.equals(text(acceptance, "releaseMode"))
                && !text(acceptance, "completedAt").isBlank();
    }

    private static boolean acceptedInIsolation(Map<String, Object> task) {
        return accepted(task, "isolated") && !"rehearsal".equals(text(task, "restorePurpose"));
    }

    private static boolean isAcceptedSameHostMigration(Map<String, Object> task) {
        return "migrate".equals(text(task, "kind")) && bool(task, "sameHostMigration")
                && (accepted(task, "open") || acceptedInIsolation(task));
    }

    private static Map<String, Object> latestAccountChange(Map<String, Object> instance,
            List<Map<String, Object>> newestFirst) {
        return newestFirst.stream()
                .filter(item -> text(instance, "id").equals(text(item, "targetId"))
                        && currentDeployment(instance, item)
                        && canReplaceAccounts(item))
                .findFirst().orElse(Map.of());
    }

    private static boolean currentDeployment(Map<String, Object> instance, Map<String, Object> task) {
        return text(instance, "deploymentId").equals(text(map(task, "targetInstanceSnapshot"), "deploymentId"));
    }

    private static boolean canReplaceAccounts(Map<String, Object> task) {
        return bool(task, "dataChanged") && switch (text(task, "kind")) {
            case "install", "migrate" -> true;
            case "restore" -> !"资源备份".equals(text(task, "scope"));
            default -> false;
        };
    }

    private static String migrationInitialAdminReference(Map<String, Object> task) {
        if (!"migrate".equals(text(task, "kind")) || !(accepted(task, "open") || acceptedInIsolation(task)))
            return "";
        var source = map(task, "instanceSnapshot");
        return bool(source, "hasInitialAdmin") ? text(source, "initialAdminCredentialRef") : "";
    }

    private static boolean releaseCandidate(Map<String, Object> instance) {
        return "isolated".equals(text(instance, "accessMode"))
                && Set.of("隔离运行", "运行中").contains(text(instance, "status"))
                && "MySQL".equals(text(instance, "dbType"))
                && "single-compose".equals(text(map(instance, "environmentConfig"), "deployment"))
                && bool(instance, "isolationSupported") && !bool(instance, "recoveryRequired")
                && !"rehearsal".equals(text(instance, "environmentPurpose")) && !"演练".equals(text(instance, "tag"));
    }

    private static void preserveIsolatedStatus(Map<String, Object> instance) {
        if ("isolated".equals(text(instance, "accessMode")) && "运行中".equals(text(instance, "status")))
            instance.put("status", "隔离运行");
    }

    private Map<String, Object> requireReleaseAcceptance(Map<String, Object> instance) {
        List<Map<String, Object>> history = store.list("tasks");
        Collections.reverse(history);
        var latest = latestAcceptanceChange(instance, history);
        if (!releaseCandidate(instance) || !acceptedInIsolation(latest))
            throw conflict("仅支持开放已完成验收、正在隔离运行且无需恢复的正式实例。");
        return latest;
    }

    public synchronized Map<String, Object> openAccess(String id, Map<String, Object> request) {
        var instance = requireUnlocked(id);
        if (!text(instance, "name").equals(required(request, "confirmTargetName"))
                || !request.containsKey("instanceRevision")
                || number(request, "instanceRevision") != number(instance, "revision"))
            throw conflict("目标实例或配置已变化，请重新打开并确认实例名称。");
        var basis = requireReleaseAcceptance(instance);
        return publicRecord(queue(obj("kind", "release", "sourceId", id, "targetId", id,
                "acceptanceTaskId", text(basis, "id"), "instanceRevision", number(instance, "revision")), List.of()));
    }

    private Map<String, Object> verifyReleaseIdentity(String id, Map<String, Object> target) throws Exception {
        beginStage(id, RELEASE_IDENTITY_STAGE, 5);
        var task = store.get("tasks", id);
        var basis = requireReleaseAcceptance(target);
        if (!text(basis, "id").equals(text(task, "acceptanceTaskId"))
                || number(map(task, "command"), "instanceRevision") != number(target, "revision"))
            throw conflict("实例或验收记录已变化，请重新确认。");
        var actual = execution.inspect(target, secrets(target)).instance();
        if (!versionMatches(target, actual)
                || List.of("arch", "hostIdentity", "databaseIdentity", "composeProject", "dbName", "port").stream()
                        .anyMatch(key -> text(target, key).isBlank() || !text(target, key).equals(text(actual, key))))
            throw conflict("实例的服务器、数据库或 Compose 项目身份已变化，已停止开放访问。");
        updateCurrentStage(id, "已核对服务器、Compose 项目、版本和元数据库身份。", true);
        return basis;
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
        return submitAfterCheck(command);
    }

    private List<Map<String, Object>> acceptanceHealth(Map<String, Object> task,
            Map<String, Object> target, ExecutionService.CheckProgress progress) throws Exception {
        String kind = text(task, "kind");
        if (acceptedInIsolation(task)) {
            // The accepted scope is retained even after its original protection backup expires.
            boolean registryExpected = maps(map(task, "acceptance").get("checks")).stream()
                    .filter(check -> "plugins".equals(text(check, "code")))
                    .findFirst()
                    .map(check -> check.containsKey("registryPresent") ? bool(check, "registryPresent")
                            : !ExecutionService.NO_PLUGIN_REGISTRY_DETAIL.equals(text(check, "detail")))
                    .orElse(!"install".equals(kind));
            return execution.acceptanceHealth(target, secrets(target), registryExpected, progress);
        }
        Optional<Boolean> expected = Optional.empty();
        if ("restore".equals(kind)) {
            String backup = text(map(task, "command"), "backupId");
            expected = execution.backupPluginRegistry(store.get("backups", backup));
        }
        if (expected.isEmpty() && !text(task, "targetBackupId").isBlank())
            expected = execution.backupPluginRegistry(store.get("backups", text(task, "targetBackupId")));
        boolean required = "migrate".equals(kind) || "plugin".equals(kind)
                || expected.orElse(!"install".equals(kind));
        return execution.acceptanceHealth(target, secrets(target), required, progress);
    }

    private List<Map<String, Object>> runAcceptanceChecks(String id, Map<String, Object> target,
            String title, int milestone) throws Exception {
        return runAcceptanceChecks(id, target, store.get("tasks", id), title, milestone);
    }

    private List<Map<String, Object>> runAcceptanceChecks(String id, Map<String, Object> target,
            Map<String, Object> basis, String title, int milestone) throws Exception {
        beginStage(id, title, milestone);
        updateTask(id, obj("liveChecks", List.of(), "currentCheck", ""));
        var progress = taskChecks(id);
        var observed = new ArrayList<>(acceptanceHealth(basis, new LinkedHashMap<>(target), progress));
        if ("migrate".equals(text(basis, "kind"))
                && (basis.containsKey("migrationExcelRuntimeCheck") || !acceptedInIsolation(basis))) {
            progress.start("Excel / CSV 引擎查询");
            var excel = execution.migrationExcelHealth(target, secrets(target));
            observed.add(excel);
            progress.result(excel);
        }
        List<Map<String, Object>> results = normalizedChecks(observed)
                .stream().map(CheckGuidance::apply).toList();
        if (passed(results)) updateCurrentStage(id, "运行核验通过。", true);
        else markStageFailed(id, "请核对未通过的检查项。");
        updateTask(id, obj("currentCheck", ""));
        return results;
    }

    private Map<String, Object> checkedAcceptance(Map<String, Object> task, List<Map<String, Object>> checks) {
        Map<String, Object> acceptance = new LinkedHashMap<>(map(task, "acceptance"));
        acceptance.putAll(obj("checks", checks, "automatedPassed", passed(checks),
                "checkedAt", now(), "releaseConfirmed", false));
        return acceptance;
    }

    public synchronized Map<String, Object> acceptanceCheck(String id) {
        Map<String, Object> task = store.get("tasks", id);
        requirePending(task);
        Map<String, Object> target = store.get("instances", text(task, "targetId"));
        task.putAll(obj("state", "checking_acceptance", "status", "执行中", "error", "",
                "currentStage", "", "currentCheck", "", "progress", 0, "acceptance", checkedAcceptance(task, List.of())));
        store.put("tasks", id, task);
        try {
            executor.submit(() -> {
                try {
                    var checks = runAcceptanceChecks(id, target, AUTOMATED_ACCEPTANCE_STAGE, 95);
                    updateTask(id, obj("state", "pending_acceptance", "status", "待验收", "progress", 100,
                            "liveChecks", List.of(), "acceptance", checkedAcceptance(task, checks)));
                } catch (Exception failure) {
                    String reason = safeError(failure, map(task, "command"));
                    markStageFailed(id, reason);
                    updateTask(id, obj("state", "pending_acceptance", "status", "待验收",
                            "currentCheck", "", "error", reason));
                }
            });
        } catch (RejectedExecutionException busy) {
            updateTask(id, obj("state", "pending_acceptance", "status", "待验收"));
            throw conflict("任务队列已满，请稍后重试。");
        }
        return publicRecord(task);
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
            task.put("activityStartedAt", now());
            store.put("tasks", id, task);
            beginStage(id, ABORT_ACCEPTANCE_STAGE, 95);
        }
        try {
            execution.service(target, secrets(target), false, true, line -> appendLog(id, line));
            updateCurrentStage(id, "已停止目标应用。", true);
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
                        + (bool(task, "sameHostMigration") ? "目标保护备份可导出。请核对新 V3，或卸载后重试。"
                            : !text(task, "targetBackupId").isBlank()
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
            markStageFailed(id, "目标停服未确认。");
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
                            "管理员已在现场核对遗留进程和 SQL；平台将核查相关应用的停止状态。"));
            store.put("tasks", id, task);
            beginStage(id, RECONCILE_STAGE, 95);
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        boolean release = "release".equals(text(task, "kind"));
        try {
            for (Map<String, Object> endpoint : endpoints) {
                ExecutionService.Inspection inspected = bool(endpoint, "migrationDatabaseStopped")
                        ? execution.verifyStoppedMigrationSource(endpoint, secrets(endpoint))
                        : text(endpoint, "databaseIdentity").isBlank()
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
                if (release) execution.verifyServicesStopped(actual, secrets(endpoint));
            }
            synchronized (this) {
                task = store.get("tasks", id);
                if (!"reconciling".equals(text(task, "state"))) throw conflict("核查状态已变化，未解除实例锁。");
                updateCurrentStage(id, "已核对相关实例的应用停止状态。", true);
                task = store.get("tasks", id);
                Map<String, Object> reconciliation =
                        new LinkedHashMap<>(map(task, "reconciliation"));
                reconciliation.put("status", "resolved");
                reconciliation.put("applicationChecks", observations);
                reconciliation.put("completedAt", now());
                task.put("reconciliation", reconciliation);
                task.put("state", "failed");
                task.put("status", "失败");
                task.put("endedAt", now());
                task.put("error", "现场核查完成，相关应用已停止，实例锁已解除。"
                        + (!text(task, "targetBackupId").isBlank() && bool(task, "dataChanged")
                                ? "可从本任务的目标保护备份还原。"
                                : "需要继续操作时，请重新提交任务。"));
                task.put("duration", duration(task));
                var stoppedInstances = new ArrayList<Map<String, Object>>();
                if (release) {
                    var instance = store.get("instances", text(task, "targetId"));
                    instance.putAll(obj("status", "已停止", "accessMode", "isolated", "automation", pausedAutomation()));
                    stoppedInstances.add(instance);
                } else {
                    for (var endpoint : endpoints) {
                        var instance = store.get("instances", text(endpoint, "id"));
                        instance.put("status", "已停止");
                        var before = map(task, "instanceSnapshot");
                        String mode = text(before, "accessMode");
                        // Backup and service only use the existing policy. An isolated start
                        // must reapply and verify it when the application is started again.
                        if (Set.of("backup", "service").contains(text(task, "kind"))
                                && !bool(task, "dataChanged")
                                && text(instance, "id").equals(text(before, "id"))
                                && text(instance, "deploymentId").equals(text(before, "deploymentId"))
                                && Set.of("isolated", "open").contains(mode)) {
                            instance.putAll(obj("accessMode", mode, "automation", pausedAutomation(), "automationCheckedAt", ""));
                        }
                        stoppedInstances.add(instance);
                    }
                }
                store.saveInstancesAndTask(stoppedInstances, task);
                return publicRecord(task);
            }
        } catch (Exception error) {
            boolean interrupted = Thread.interrupted() || hasInterruptCause(error);
            try {
                synchronized (this) {
                    markStageFailed(id, "现场核查未通过，实例锁保留。");
                    task = store.get("tasks", id);
                    Map<String, Object> reconciliation =
                            new LinkedHashMap<>(map(task, "reconciliation"));
                    reconciliation.put("status", "blocked");
                    reconciliation.put("applicationChecks", observations);
                    reconciliation.put("lastCheckedAt", now());
                    task.put("reconciliation", reconciliation);
                    task.put("state", "reconciliation_required");
                    task.put("status", "状态待确认");
                    task.put("error", "尚未确认相关应用均已停止，实例仍锁定。请核对现场进程、SQL 和连接后重试。");
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
                            + " 实例仍锁定。请在现场核对并终止遗留进程及 SQL，再通过核查入口确认相关应用已停止。");
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
            Set<String> ids = operationIds(map(task, "command"));
            for (String key : List.of("instanceId", "targetId"))
                if (!text(task, key).isBlank()) ids.add(text(task, key));
            var affected = new ArrayList<Map<String, Object>>();
            for (String instanceId : ids)
                store.find("instances", instanceId).ifPresent(instance -> {
                    instance.putAll(obj("status", "状态待确认", "accessMode", "unknown",
                            "recoveryRequired", bool(instance, "recoveryRequired")
                                    || instanceId.equals(text(task, "targetId")) && bool(task, "dataChanged")));
                    affected.add(instance);
                });
            store.saveInstancesAndTask(affected, task);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean upgradeReadOnlyStage(Map<String, Object> task) {
        return "upgrade".equals(text(task, "kind")) && "复核升级磁盘空间".equals(text(task, "currentStage"));
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

    public synchronized Map<String, Object> finishAcceptance(String id, Map<String, Object> input) {
        Map<String, Object> task = store.get("tasks", id);
        requirePending(task);
        Map<String, Object> acceptance = new LinkedHashMap<>(map(task, "acceptance"));
        if (input != null) {
            String mode = text(input, "releaseMode");
            if (!Set.of("open", "isolated").contains(mode) || !bool(input, "confirmRelease"))
                throw bad("请明确确认验收后的访问策略。");
            acceptance.putAll(obj("releaseMode", mode, "releaseConfirmed", true));
        }
        if (!bool(acceptance, "automatedPassed") || !bool(acceptance, "releaseConfirmed"))
            throw conflict("请先通过运行核验，并确认访问策略。");
        if ("rehearsal".equals(text(task, "restorePurpose")) && "open".equals(text(acceptance, "releaseMode")))
            throw conflict("恢复演练必须保持隔离。");
        if (map(acceptance, "automation").values().stream().anyMatch(Boolean.TRUE::equals))
            throw conflict("请先完成验收并开放访问，再从自动任务入口逐项恢复。");
        Map<String, Object> target = store.get("instances", text(task, "targetId"));
        var pendingTask = new LinkedHashMap<>(task);
        boolean open = "open".equals(text(acceptance, "releaseMode"));
        var stages = new ArrayList<Map<String, Object>>();
        for (var stage : maps(task.get("stages"))) stages.add(new LinkedHashMap<>(stage));
        var acceptanceTitles = acceptanceStageTitles(open, "migrate".equals(text(task, "kind")),
                bool(task, "sameHostMigration"));
        // A retry can choose a different access strategy. Drop only steps from the previous plan that never started.
        var alternativeTitles = acceptanceStageTitles(!open, "migrate".equals(text(task, "kind")),
                bool(task, "sameHostMigration"));
        stages.removeIf(stage -> alternativeTitles.contains(text(stage, "title"))
                && !acceptanceTitles.contains(text(stage, "title")) && "pending".equals(text(stage, "status")));
        stages.stream().filter(stage -> ACCEPTANCE_DECISION_STAGE.equals(text(stage, "title")))
                .forEach(stage -> stage.putAll(obj("status", "passed", "endedAt", now())));
        for (String title : acceptanceTitles)
            if (stages.stream().noneMatch(stage -> title.equals(text(stage, "title"))))
                stages.add(obj("title", title, "status", "pending"));
        task.put("stages", stages);
        task.putAll(obj("state", "accepting", "status", "执行中", "error", "", "currentStage", "",
                "currentCheck", "", "progress", 0, "acceptance", acceptance));
        store.put("tasks", id, task);
        try {
            executor.submit(() -> executeAcceptance(id, target));
        } catch (RejectedExecutionException busy) {
            store.put("tasks", id, pendingTask);
            throw conflict("任务队列已满，请稍后重试。");
        }
        return publicRecord(task);
    }

    private void executeAcceptance(String id, Map<String, Object> target) {
        Map<String, Object> task = store.get("tasks", id);
        Map<String, Object> acceptance = map(task, "acceptance");
        boolean standalone = "release".equals(text(task, "kind"));
        boolean accessChangeStarted = false;
        try {
            boolean open = "open".equals(text(acceptance, "releaseMode"));
            var basis = standalone ? verifyReleaseIdentity(id, target) : task;
            var latest = runAcceptanceChecks(id, target, basis, RELEASE_CHECK_STAGE, 10);
            var checked = checkedAcceptance(task, latest);
            checked.put("releaseConfirmed", passed(latest));
            updateTask(id, obj("acceptance", checked, "liveChecks", List.of()));
            if (!passed(latest)) throw conflict("运行条件已变化，请检查未通过项。");
            accessChangeStarted = open;
            stage(id, open ? RELEASE_SWITCH_STAGE : RETAIN_ISOLATION_STAGE, 70,
                    open ? "业务入口已开放，自动任务继续待机。" : "已确认业务入口保持关闭。",
                    () -> execution.release(target, secrets(target), open, line -> {
                        updateCurrentStage(id, line, false);
                        appendLog(id, line);
                    }));
            if (open && "migrate".equals(text(basis, "kind"))) {
                stage(id, VERIFY_MIGRATED_EXCEL_STAGE, 90, () -> {
                    var excel = execution.migrationExcelHealth(target, secrets(target));
                    if (!passed(List.of(excel))) throw conflict(text(excel, "detail"));
                    appendLog(id, text(excel, "detail"));
                });
            }
            if (bool(task, "sameHostMigration")) {
                stage(id, STOP_SOURCE_MYSQL_STAGE, 96, "V2 应用和内置 MySQL 均已停止，原数据保留。", () -> {
                    var source = store.get("instances", text(basis, "instanceId"));
                    setInstance(source, obj("migrationDatabaseStopped", true));
                    execution.stopMigrationSourceDatabase(source, secrets(source));
                    setInstance(source, obj("status", "已停止", "automation", pausedAutomation()));
                });
            }
            synchronized (this) {
                task = store.get("tasks", id);
                acceptance = new LinkedHashMap<>(map(task, "acceptance"));
                acceptance.put("completedAt", now());
                task.put("acceptance", acceptance);
                task.put("state", "succeeded");
                task.put("status", "成功");
                task.put("endedAt", now());
                task.put("duration", duration(task));
                task.put("progress", 100);
                task.put("accessMode", open ? "open" : "isolated");
                var instance = store.get("instances", text(target, "id"));
                instance.putAll(obj(
                                "status",
                                open ? "运行中" : "隔离运行",
                                "accessMode",
                                open ? "open" : "isolated",
                                "automation",
                                pausedAutomation(),
                                "health",
                                "正常"));
                store.saveInstanceAndTask(instance, task);
                return;
            }
        } catch (Exception error) {
            String failedStage = text(store.get("tasks", id), "currentStage");
            markStageFailed(id, standalone ? safeError(error, map(task, "command")) : "运行核验或访问切换未完成。");
            updateTask(id, obj("currentCheck", ""));
            if (accessChangeStarted && uncertainCompletion(error)) {
                markReconciliationRequired(
                        id, error, "release_interrupted", "验收访问策略切换的远端命令未能确认结束，不能并发执行重新隔离或还原。");
                return;
            }
            String recovery = standalone ? "开放前核验未通过，访问策略未变更。" : "实际验收检查未通过，访问策略未变更。";
            if (accessChangeStarted) {
                try {
                    stage(id, "重新隔离并核验运行状态", 85, "已恢复隔离环境。",
                            () -> execution.service(target, secrets(target), true, true, line -> {
                                updateCurrentStage(id, line, false);
                                appendLog(id, line);
                            }));
                    setInstance(
                            target,
                            obj(
                                    "status",
                                    standalone ? "隔离运行" : "待验收",
                                    "accessMode",
                                    "isolated",
                                    "automation",
                                    pausedAutomation()));
                    recovery = standalone ? "开放访问失败，已恢复并核验隔离运行。"
                            : "访问策略切换失败，已重新验证隔离启动；请重新验收。";
                } catch (Exception isolationFailure) {
                    markStageFailed(id, "重新隔离未完成。");
                    if (uncertainCompletion(isolationFailure)) {
                        markReconciliationRequired(
                                id,
                                isolationFailure,
                                "isolation_interrupted",
                                "重新隔离的远端命令未能确认结束，不能并发发送其他变更命令。");
                        return;
                    }
                    try {
                        stage(id, "停止目标并核验停服", 95, "目标服务已停止。",
                                () -> execution.service(target, secrets(target), false, true,
                                        line -> appendLog(id, line)));
                        setInstance(
                                target,
                                obj(
                                        "status",
                                        "已停止",
                                        "accessMode",
                                        "isolated",
                                        "automation",
                                        pausedAutomation()));
                        recovery = standalone ? "开放访问及重新隔离失败，目标服务已停止。"
                                : "访问策略切换及重新隔离失败，目标已停止，待验收锁保留；请终止验收后核对环境。";
                    } catch (Exception stopFailure) {
                        markStageFailed(id, "停服状态未确认。");
                        if (standalone || uncertainCompletion(stopFailure)) {
                            markReconciliationRequired(
                                    id,
                                    stopFailure,
                                    "stop_interrupted",
                                    "失败处理的远端停服命令未能确认结束，实例锁保留。");
                            return;
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
                if (standalone) {
                    current.putAll(obj("state", "failed", "status", "失败", "error", recovery,
                            "failureStage", failedStage, "endedAt", now(), "duration", duration(current)));
                } else {
                    current.putAll(obj("state", "pending_acceptance", "status", "待验收", "error", recovery));
                }
                store.put("tasks", id, current);
            }

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
            return publicRecord(store.put(collection, id, verified));
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
            return publicRecord(store.put(collection, id, verified));
        }
    }

    public synchronized Map<String, Object> declareUpgradeCompatibility(String id, Map<String, Object> input)
            throws Exception {
        var record = store.get("packages", id);
        if (!text(record, "sha256").equals(text(input, "sha256"))
                || number(record, "revision") != number(input, "revision"))
            throw conflict("离线包或兼容声明已变化，请刷新软件包后重试。");
        assertPackageUnused(id);
        try {
            return publicRecord(store.put("packages", id, media.declareUpgradeCompatibility(
                    record, input.get("minimumUpgradeSource"))));
        } catch (Exception error) {
            throw mediaFailure(error);
        }
    }

    private void assertPackageUnused(String id) {
        if (store.list("tasks").stream()
                .anyMatch(
                        task -> {
                            var command = map(task, "command");
                            String rollback = text(command, "vendorRollbackPackageId");
                            if (!id.equals(text(command, "packageId")) && !id.equals(rollback)) return false;
                            if (ACTIVE.contains(text(task, "state"))) return true;
                            if (rollback.isBlank() || !"failed".equals(text(task, "state"))) return false;
                            return store.find("instances", text(task, "targetId")).filter(instance -> currentDeployment(instance, task)
                                    && currentRecoveryTask(instance, task, "")).isPresent();
                        }))
            throw conflict("活动任务或待恢复的升级正在引用此介质，不能变更。");
    }

    public synchronized void removePackage(String id) throws Exception {
        String collection = store.find("packages", id).isPresent() ? "packages" : "plugins";
        Map<String, Object> record = store.get(collection, id);
        assertPackageUnused(id);
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
        if (vacant(store.get("instances", instanceId))) throw bad("该实例尚未安装 DataEase，不能创建计划。");
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
                Map<String, Object> task = submitAfterCheck(command);
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

    private Map<String, Object> migrationBackup(Map<String, Object> instance, Map<String, String> credentials,
            boolean freshTarget, Consumer<String> log) throws Exception {
        return freshTarget ? execution.backup(instance, credentials, "整体备份", false, log)
                : execution.protectionBackup(instance, credentials, log);
    }

    private void verifyMigrationProtection(String taskId, Map<String, Object> source,
            Map<String, Object> target) throws Exception {
        Map<String, Object> task = store.get("tasks", taskId);
        var command = map(task, "command");
        boolean freshTarget = sameHostMigration(command)
                && text(target, "id").equals(text(command, "newTargetId"));
        for (var endpoint : List.of(Map.entry("sourceBackupId", source), Map.entry("targetBackupId", target))) {
            Map<String, Object> backup = store.get("backups", required(task, endpoint.getKey()));
            Map<String, Object> verified = execution.verifyBackup(backup);
            Map<String, Object> instance = endpoint.getValue();
            if (!taskId.equals(text(backup, "taskId"))
                    || !text(backup, "id").equals(text(verified, "id"))
                    || !text(instance, "id").equals(text(verified, "instanceId"))
                    || !text(instance, "version").equals(text(verified, "version"))
                    || !"整体备份".equals(text(verified, "scope"))
                    || "manual".equals(text(verified, "restoreMode")) && !freshTarget
                    || !"pause".equals(text(verified, "consistent"))
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
                        "deploymentId",
                        instance.get("deploymentId"),
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

    private void pendingAcceptance(
            String taskId, Map<String, Object> original, Map<String, Object> command)
            throws Exception {
        Map<String, Object> task = store.get("tasks", taskId),
                target = new LinkedHashMap<>(original);
        target.remove("protectionRecovery");
        target.remove("vendorProtectionRollback");
        target.remove("metadataDatabasePresent");
        target.remove("freshRestoreTarget");
        String targetId = text(task, "targetId");
        target.put("id", targetId);
        beginStage(taskId, REGISTER_TARGET_STAGE, 92);
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
        updateTask(taskId, obj("targetInstanceSnapshot", target, "version", target.get("version")));
        updateCurrentStage(taskId, "已复核安装版本和目标身份。", true);
        var checks = runAcceptanceChecks(taskId, target, AUTOMATED_ACCEPTANCE_STAGE, 95);
        updateTask(taskId, obj("state", "pending_acceptance", "status", "待验收", "progress", 100,
                "executionEndedAt", now(), "duration", duration(store.get("tasks", taskId)), "liveChecks", List.of(),
                "acceptance", checkedAcceptance(obj("acceptance",
                        obj("releaseMode", "isolated", "automation", pausedAutomation())), checks)));
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
        String cleaned = redact(line, map(task, "command"));
        appendLog(task, cleaned);
        if (maps(task.get("stages")).stream().anyMatch(stage ->
                "running".equals(text(stage, "status")) && text(task, "currentStage").equals(text(stage, "title"))))
            task.put("stageActivity", cleaned);
        store.put("tasks", id, task);
    }

    private void appendLog(Map<String, Object> task, String cleaned) {
        List<String> logs = new ArrayList<>(strings(task.get("logs")));
        logs.add("[" + now() + "] " + cleaned);
        if (logs.size() > 5000)
            logs = new ArrayList<>(logs.subList(logs.size() - 5000, logs.size()));
        task.put("logs", logs);
    }

    private void stage(String id, String name, int progress, CheckedAction action)
            throws Exception {
        stage(id, name, progress, "", action);
    }

    private synchronized void skipStage(String id, String name, String reason) {
        var task = store.get("tasks", id);
        var stages = maps(task.get("stages"));
        for (var item : stages)
            if (name.equals(text(item, "title")) && "pending".equals(text(item, "status")))
                item.putAll(obj("status", "skipped", "detail", reason));
        task.put("stages", stages);
        store.put("tasks", id, task);
    }

    private void stage(String id, String name, int progress, String result, CheckedAction action)
            throws Exception {
        beginStage(id, name, progress);
        action.run();
        updateCurrentStage(id, result, true);
    }

    private ExecutionService.InstallProgress installProgress(String id) {
        return installProgress(id, 0, 100);
    }

    private ExecutionService.InstallProgress installProgress(String id, int from, int to) {
        return new ExecutionService.InstallProgress() {
            @Override public void start(String title, int milestone) {
                beginStage(id, title, from + (to - from) * milestone / 100);
            }

            @Override public void detail(String result) {
                updateCurrentStage(id, result, false);
            }

            @Override public void complete(String result) {
                updateCurrentStage(id, result, true);
            }
        };
    }

    private ExecutionService.CheckProgress taskChecks(String id) {
        return new ExecutionService.CheckProgress() {
            @Override public void start(String title) {
                updateTask(id, obj("currentCheck", title, "currentCheckStartedAt", now()));
            }

            @Override public void result(Map<String, Object> check) {
                synchronized (OperationsService.this) {
                    Map<String, Object> task = store.get("tasks", id);
                    var results = new ArrayList<>(maps(task.get("liveChecks")));
                    results.add(check);
                    task.put("liveChecks", results);
                    task.put("currentCheck", "");
                    task.remove("currentCheckStartedAt");
                    store.put("tasks", id, task);
                }
            }
        };
    }

    private synchronized void markStageFailed(String id, String detail) {
        Map<String, Object> task = store.get("tasks", id);
        markStageFailed(task, detail);
        store.put("tasks", id, task);
    }

    private void markStageFailed(Map<String, Object> task, String detail) {
        var stages = maps(task.get("stages"));
        for (var stage : stages)
            if (text(stage, "title").equals(text(task, "currentStage")))
                stage.putAll(obj("status", "failed", "detail", detail, "endedAt", now()));
        task.put("stages", stages);
    }

    private void beginStage(String id, String name, int progress) {
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
            current.remove("endedAt");
            current.remove("detail");
            task.put("stages", stages);
            task.put("currentStage", name);
            task.remove("stageActivity");
            task.remove("currentCheckStartedAt");
            task.put("currentCheck", "");
            task.put("progress", progress);
            appendLog(task, name);
            store.put("tasks", id, task);
        }
    }

    private void updateCurrentStage(String id, String result, boolean completed) {
        synchronized (this) {
            Map<String, Object> task = store.get("tasks", id);
            String name = text(task, "currentStage");
            List<Map<String, Object>> stages = maps(task.get("stages"));
            for (Map<String, Object> item : stages)
                if (name.equals(text(item, "title"))) {
                    if (!result.isBlank()) item.put("detail", result);
                    if (completed) {
                        item.put("status", "passed");
                        item.put("endedAt", now());
                        task.remove("stageActivity");
                    }
                }
            task.put("stages", stages);
            store.put("tasks", id, task);
        }
    }

    private List<Map<String, Object>> plannedStages(Map<String, Object> command) {
        String kind = text(command, "kind");
        List<String> titles = new ArrayList<>();
        switch (kind) {
            case "release" -> {
                titles.add(RELEASE_IDENTITY_STAGE);
                titles.addAll(acceptanceStageTitles(true,
                        "migrate".equals(text(store.get("tasks", required(command, "acceptanceTaskId")), "kind")), false));
            }
            case "migrate" -> {
                boolean sameHost = sameHostMigration(command);
                if (sameHost) titles.addAll(List.of("复核同机部署与源端身份", "停止 V2 应用与配套服务",
                        "创建并校验源端保护备份", "在同一主机准备独立 V3"));
                titles.add("复核两端连接与执行条件");
                titles.add(sameHost ? "停止 V3 应用，保留两端 MySQL" : "暂停自动任务并停止两端应用");
                if (!sameHost) titles.add("创建并校验源端保护备份");
                titles.addAll(List.of("创建并校验目标保护备份", "复核两端保护备份"));
                for (var phase : MigrationService.Phase.values()) titles.add(phase.title());
                titles.add("隔离启动目标环境");
            }
            case "backup" -> {
                boolean pause = !"online".equals(text(command, "consistent"));
                titles.add("核对实际备份范围");
                if (pause) titles.add("暂停应用写入");
                titles.add(backupStage(text(command, "scope")));
                if (pause) titles.add("恢复备份前应用状态");
            }
            case "restore" -> {
                titles.add("复核备份与恢复目标");
                if (text(command, "targetId").isBlank()) {
                    boolean offlineCompose = DataEaseOfflinePackage.ADAPTER.equals(
                            store.get("packages", required(command, "packageId")).get("adapter"));
                    titles.addAll(ExecutionService.installStageTitles(offlineCompose, false));
                } else titles.add("保护现有目标并停止应用");
                titles.addAll(List.of("还原所选备份内容", "隔离启动恢复目标"));
            }
            case "install", "upgrade" -> {
                boolean upgrade = "upgrade".equals(kind);
                boolean offlineCompose = DataEaseOfflinePackage.ADAPTER.equals(
                        store.get("packages", required(command, "packageId")).get("adapter"));
                if (upgrade) titles.addAll(List.of("复核升级磁盘空间", "停服并建立升级保护备份"));
                if (!text(command, "vacantTargetId").isBlank()) titles.add("复核已登记服务器");
                titles.addAll(ExecutionService.installStageTitles(offlineCompose, upgrade));
                titles.add("隔离启动并核对环境");
                if (!upgrade && offlineCompose) titles.add("初始化并核验 DataEase 管理员");
            }
            case "plugin" -> titles.addAll(List.of("停服并建立插件安装保护备份",
                    "隔离启动并暂停自动任务", "调用DataEase安装接口并核验登记"));
            case "check" -> titles.add("检查应用、数据库与资源");
            case "automation" -> titles.addAll(List.of("复核验收、目标身份与调度清单", "更新授权清单并回读实际调度状态"));
            case "uninstall" -> titles.addAll(List.of("核对卸载目标与安装归属", "移除实例容器与闲置镜像，归档数据"));
            case "service" -> titles.add("start".equals(text(command, "action")) ? "启动应用" : "停止应用");
            default -> { }
        }
        if (ACCEPTANCE_KINDS.contains(kind))
            titles.addAll(List.of(REGISTER_TARGET_STAGE, AUTOMATED_ACCEPTANCE_STAGE, ACCEPTANCE_DECISION_STAGE));
        return pendingStages(titles);
    }

    private static String backupStage(String scope) {
        return switch (scope) {
            case "数据备份" -> "备份元数据库";
            case "资源备份" -> "归档资源文件";
            default -> "备份数据库与资源";
        };
    }

    private static List<String> acceptanceStageTitles(boolean open, boolean migration, boolean sameHost) {
        var titles = new ArrayList<>(List.of(RELEASE_CHECK_STAGE, open ? RELEASE_SWITCH_STAGE : RETAIN_ISOLATION_STAGE));
        if (open && migration) titles.add(VERIFY_MIGRATED_EXCEL_STAGE);
        if (sameHost) titles.add(STOP_SOURCE_MYSQL_STAGE);
        return titles;
    }

    private static List<Map<String, Object>> pendingStages(List<String> titles) {
        return titles.stream().map(title -> obj("title", title, "status", "pending")).toList();
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
                        "vacantTargetId",
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
        for (String optionalId : List.of("packageId", "backupId", "sourceId", "targetId", "vacantTargetId"))
            if (text(command, optionalId).isBlank()) command.remove(optionalId);
        if (kind.equals("backup") && !SCOPES.contains(text(command, "scope")))
            command.put("scope", "整体备份");
        if (request.containsKey("destination"))
            command.put("destination", request.get("destination"));
        if (request.containsKey("backupPath")) command.put("backupPath", request.get("backupPath"));
        if (request.containsKey("nameBackup")) command.put("name", request.get("nameBackup"));
        if (kind.equals("install") && text(command, "packageId").isBlank())
            throw bad("请选择已校验离线介质。");
        if (kind.equals("install") && !text(command, "targetId").isBlank())
            throw bad("安装只接受未安装的实例或空服务器。");
        if (!text(command, "vacantTargetId").isBlank()) {
            if (!Set.of("install", "restore").contains(kind) || !text(command, "targetId").isBlank()
                    || !text(command, "protectionTaskId").isBlank())
                throw bad("未安装实例只支持新安装或从整体备份重建。");
            var supplied = map(command, "config");
            var settings = new LinkedHashMap<String, Object>();
            for (String key : List.of("path", "port", "dbType", "dbMode", "dbHost", "dbPort", "dbName", "dbUser"))
                if (supplied.containsKey(key)) settings.put(key, supplied.get(key));
            command.put("config", settings);
            vacantTarget(command);
        }
        if (kind.equals("migrate") && text(command, "sourceId").isBlank())
            throw bad("请明确选择 V2 源实例。");
        if (sameHostMigration(command)) {
            if (!text(command, "targetId").isBlank() || text(command, "packageId").isBlank())
                throw bad("同机新建 V3 需要选择离线安装包。");
            var requested = map(command, "config");
            var config = new LinkedHashMap<String, Object>();
            for (String key : List.of("name", "environment", "path", "port"))
                if (requested.containsKey(key)) config.put(key, requested.get(key));
            command.put("config", config);
        }
        if (Set.of("upgrade", "backup", "check", "service", "plugin", "automation").contains(kind)
                && text(command, "sourceId").isBlank()) throw bad("请明确选择操作实例。");
        if (kind.equals("restore")) {
            if (text(command, "backupId").isBlank()) throw bad("请选择明确的恢复点。");
            command.put("scope", store.get("backups", text(command, "backupId")).get("scope"));
            if (!text(command, "protectionTaskId").isBlank() && text(command, "packageId").isBlank()) {
                var previous = store.get("tasks", text(command, "protectionTaskId"));
                String rollback = text(map(previous, "command"), "vendorRollbackPackageId");
                if (!rollback.isBlank()) command.put("packageId", rollback);
            }
        }
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

    private static boolean sameHostMigration(Map<String, Object> command) {
        return "migrate".equals(text(command, "kind")) && "same-host".equals(text(command, "targetMode"));
    }

    private Map<String, Object> vacantTarget(Map<String, Object> command) {
        String id = text(command, "vacantTargetId");
        if (id.isBlank()) return Map.of();
        var snapshot = map(command, "registeredHostSnapshot");
        if (!snapshot.isEmpty()) return snapshot;
        var instance = store.get("instances", id);
        if (!vacant(instance)) throw conflict("该实例已安装或正在准备部署，请刷新实例状态。");
        return instance;
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
        var registration = vacantTarget(command);
        if (!registration.isEmpty()) {
            copyHostFields(registration, target);
            target.put("name", registration.get("name"));
            target.put("environment", registration.get("environment"));
            target.put("revision", number(registration, "revision") + 1);
            target.put("createdAt", registration.get("createdAt"));
            if (command.containsKey("deploymentId")) target.put("deploymentId", command.get("deploymentId"));
        }
        boolean sameHost = sameHostMigration(command);
        if (sameHost) {
            var source = source(command);
            for (String key : List.of("host", "connection", "arch", "sshUser", "sshPort", "sshAuth"))
                if (source.containsKey(key)) target.put(key, source.get(key));
            target.put("sameHostSourceProject", text(source, "composeProject"));
            target.put("sameHostSourcePath", text(source, "path"));
            target.put("dbMode", "builtin");
            target.put("dbName", "dataease_v3");
            target.putIfAbsent("port", 19080);
            if (command.containsKey("sameHostTargetProject"))
                target.put("composeProject", command.get("sameHostTargetProject"));
        }
        if ((kind.equals("install") || sameHost) && !text(command, "packageId").isBlank()) {
            Map<String, Object> pkg = media.packageDisplay(store.get("packages", text(command, "packageId")));
            String version = text(pkg, "version");
            target.put("version", version);
            boolean v2 = version.startsWith("v2.");
            boolean vendorEnterprise = DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter"))
                    && "enterprise".equals(pkg.get("edition"));
            target.putIfAbsent("path", v2 ? "/opt/dataease2.0" : "/opt/dataease3.0");
            target.putIfAbsent("dbName", vendorEnterprise ? pkg.getOrDefault("defaultDatabaseName", "dataease")
                    : v2 ? "dataease_v2" : "dataease_v3");
            target.putIfAbsent("port", vendorEnterprise ? pkg.getOrDefault("defaultServicePort", 9080) : 8100);
            if (vendorEnterprise) {
                target.put("deploymentAdapter", ComposeManager.VENDOR_ENTERPRISE_ADAPTER);
                if (v2) target.put("vendorApplicationPort", pkg.getOrDefault("defaultApplicationPort", 8100));
            }
            if ("builtin".equals(text(target, "dbMode"))) {
                target.put("dbType", "MySQL");
                target.put("dbUser", "root");
                target.put("dbHost", vendorEnterprise ? pkg.getOrDefault("defaultDatabaseHost", "mysql-de")
                        : text(target, "host"));
                target.put("dbPort", 3306);
            } else if (v2) {
                target.put("dbType", "MySQL");
            }
        }
        if (kind.equals("restore") && !text(command, "backupId").isBlank()) {
            Map<String, Object> backup = store.get("backups", text(command, "backupId"));
            target.put("version", backup.get("version"));
            target.put("dbType", backup.get("dbType"));
            target.putIfAbsent("arch", backup.get("arch"));
            target.put("freshRestoreTarget", true);
            if (!text(command, "packageId").isBlank()) {
                var pkg = media.packageDisplay(store.get("packages", text(command, "packageId")));
                boolean vendor = DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter"))
                        && "enterprise".equals(pkg.get("edition"))
                        && BackupService.ORIGINAL_ENTERPRISE.equals(backup.get("restoreMode"));
                if (vendor) {
                    boolean v2 = text(backup, "version").startsWith("v2.");
                    target.put("deploymentAdapter", ComposeManager.VENDOR_ENTERPRISE_ADAPTER);
                    target.putIfAbsent("path", v2 ? "/opt/dataease2.0" : "/opt/dataease3.0");
                    target.putIfAbsent("port", pkg.getOrDefault("defaultServicePort", 9080));
                    target.putIfAbsent("dbName", pkg.getOrDefault("defaultDatabaseName", "dataease"));
                    if (v2) target.put("vendorApplicationPort", pkg.getOrDefault("defaultApplicationPort", 8100));
                }
                if (DataEaseOfflinePackage.ADAPTER.equals(pkg.get("adapter")) && (vendor
                        || !registration.isEmpty() && !"enterprise".equals(pkg.get("edition")))) {
                    target.put("dbMode", "builtin");
                    target.put("dbUser", "root");
                    target.put("dbHost", vendor ? pkg.getOrDefault("defaultDatabaseHost", "mysql-de") : target.get("host"));
                    if (vendor) target.put("dbPort", 3306);
                    else target.putIfAbsent("dbPort", 3306);
                }
            }
        }
        if (!text(command, "newTargetId").isBlank()) target.put("id", command.get("newTargetId"));
        if (!text(command, "targetHostIdentity").isBlank())
            target.put("hostIdentity", command.get("targetHostIdentity"));
        if (!text(command, "targetMachineIdentity").isBlank())
            target.put("machineIdentity", command.get("targetMachineIdentity"));
        if (command.containsKey("runtimeBootstrap"))
            target.put("runtimeBootstrap", command.get("runtimeBootstrap"));
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

    public Map<String, String> initialAdmin(String id) throws Exception {
        Map<String, Object> instance;
        String reference;
        synchronized (this) {
            instance = store.get("instances", id);
            if (vacant(instance)) throw OperationsApiException.notFound("该实例尚未安装 DataEase。");
            var tasks = store.list("tasks");
            Collections.reverse(tasks);
            var accounts = latestAccountChange(instance, tasks);
            if ("restore".equals(text(accounts, "kind")))
                throw OperationsApiException.notFound("该实例的账号数据已还原，请使用备份对应的密码。");
            if (!"migrate".equals(text(accounts, "kind"))) {
                reference = text(instance, "initialAdminCredentialRef");
                if (bool(instance, "hasInitialAdmin") && !reference.isBlank())
                    return Map.of("username", "admin", "password", vault.get(reference).get("password"));
                throw OperationsApiException.notFound("该实例没有保存安装初始密码。");
            }
            requireUnlocked(id);
            reference = migrationInitialAdminReference(accounts);
            if (reference.isBlank())
                throw OperationsApiException.notFound("该实例没有可核验的初始密码。");
        }
        String password = vault.get(reference).get("password");
        if (password == null || password.isBlank())
            throw OperationsApiException.notFound("原 V2 实例没有保存初始密码。");
        try {
            var credentials = secrets(instance);
            execution.validateMigrationIdentity(instance, execution.inspect(instance, credentials));
            execution.verifyAdminLogin(instance, credentials, password);
        } catch (Exception unavailable) {
            if (hasInterruptCause(unavailable)) Thread.currentThread().interrupt();
            throw conflict("无法验证原 V2 初始密码可登录 V3。请确认 V3 正常运行，或使用后来设置的密码。");
        }
        synchronized (this) {
            requireUnlocked(id);
            if (!store.get("instances", id).equals(instance))
                throw conflict("实例配置已变化，请重新验证。");
            var tasks = store.list("tasks");
            Collections.reverse(tasks);
            if (!reference.equals(migrationInitialAdminReference(latestAccountChange(instance, tasks))))
                throw conflict("实例账号来源已变化，请重新验证。");
        }
        return Map.of("username", "admin", "password", password);
    }

    public synchronized Map<String, Object> uninstall(String id) {
        Map<String, Object> instance = store.get("instances", id);
        if (!ComposeManager.isVendorEnterprise(instance))
            throw bad("当前支持卸载由运维中心部署的企业版单机实例。");
        List<Map<String, Object>> related = store.list("tasks").stream()
                .filter(task -> ACTIVE.contains(text(task, "state"))
                        && (id.equals(text(task, "targetId")) || id.equals(text(task, "instanceId")))).toList();
        var command = obj("kind", "uninstall", "sourceId", id, "targetId", id);
        if (!related.isEmpty()) {
            var task = related.get(0);
            if (related.size() != 1 || !"pending_acceptance".equals(text(task, "state"))
                    || !"install".equals(text(task, "kind")) || !id.equals(text(task, "targetId")))
                throw conflict("该实例存在尚未完成的操作，请处理后再卸载。");
            command.put("pendingInstallId", task.get("id"));
        }
        return publicRecord(queue(command, List.of()));
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
                    && currentDeployment(instance, task)
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
        for (String key : List.of("sourceId", "targetId", "vacantTargetId"))
            if (!text(command, key).isBlank()) ids.add(text(command, key));
        return ids;
    }

    private String fingerprint(Map<String, Object> command) {
        try {
            Map<String, Object> snapshot = new TreeMap<>(command);
            for (String key : List.of("sourceId", "targetId", "vacantTargetId"))
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
            if (!text(command, "vendorRollbackPackageId").isBlank())
                snapshot.put("vendorRollbackPackageSnapshot", store.get("packages", text(command, "vendorRollbackPackageId")));
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
                                                "machineidentity",
                                                "deploymentid",
                                                "internalpath",
                                                "contentroot",
                                                "packagefilestate",
                                                "upgradecompatibility",
                                                "gatewaybinding",
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
                        "dbMode",
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
                        "engineType"))
            if (input.containsKey(key)) {
                if (!(input.get(key) instanceof String value)
                        || value.length() > 512)
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
                if ((vacant(existing) || vacant(candidate)) && samePhysicalHost(existing, candidate))
                    throw conflict("该服务器已登记为实例，请在实例管理中使用已有记录。");
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
        // The vendor service name is scoped to its Docker host, unlike a published DB endpoint.
        if (ComposeManager.isVendorEnterprise(a) && ComposeManager.isVendorEnterprise(b))
            return samePhysicalHost(a, b);
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

    private boolean canRetryInstall(Map<String, Object> instance, Map<String, Object> target) {
        String id = text(instance, "id");
        return !id.isBlank() && sameEndpoint(instance, target)
                && "待检查".equals(text(instance, "status"))
                && map(instance, "connectionProfile").isEmpty() && !isLocked(id)
                && store.list("tasks").stream().anyMatch(task -> id.equals(text(task, "targetId"))
                        && "install".equals(text(task, "kind")) && "failed".equals(text(task, "state"))
                        && text(task, "executionEndedAt").isBlank());
    }

    private boolean samePhysicalHost(Map<String, Object> a, Map<String, Object> b) {
        String ma = text(a, "machineIdentity"), mb = text(b, "machineIdentity");
        if (!ma.isBlank() && ma.equals(mb)) return true;
        String x = text(a, "hostIdentity"), y = text(b, "hostIdentity");
        if (x.startsWith("machine:") || y.startsWith("machine:"))
            return x.equals(y) || sameHost(text(a, "host"), text(b, "host"));
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
                && (!ComposeManager.isManagedEnterprise(instance) || ComposeManager.isVendorEnterprise(instance))
                && !Set.of("cluster", "multi-node").contains(text(environment, "deployment"))
                && "shared".equals(text(environment, "engineMode"));
    }

    private String protectionCoverageBlock(Map<String, Object> instance) {
        if (ComposeManager.isManagedEnterprise(instance) && !ComposeManager.isVendorEnterprise(instance))
            return "此企业版部署无法验证网关保护范围。请选择由平台部署的原厂企业版实例。";
        return "无法核验当前数据库、数据引擎或各节点的完整保护范围。请在实例详情核对实际部署。";
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
        var result = obj(
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
                        "valid"));
        if (!vacant(instance))
            result.put("database", obj("user", text(instance, "dbUser"), "status", "valid"));
        return result;
    }

    private void requirePending(Map<String, Object> task) {
        if (!"pending_acceptance".equals(text(task, "state"))) throw conflict("该任务当前不处于待验收状态。");
        if (store.list("tasks").stream().anyMatch(item -> "uninstall".equals(text(item, "kind"))
                && ACTIVE.contains(text(item, "state")) && text(task, "targetId").equals(text(item, "targetId"))))
            throw conflict("该实例正在处理卸载任务。");
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
            if (Set.of("sshPassword", "dbPassword", "privateKey", "passphrase", "dataeaseAdminPassword")
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
                            "dataeaseAdminPassword")) {
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
        if (kind.equals("release")) return "开放业务访问";
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
                        "自动任务恢复",
                        "uninstall",
                        "卸载 DataEase")
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
