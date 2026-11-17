import React, { useEffect, useId, useRef, useState } from "react";
import { Badge } from "./ui";
import {
  Button,
  Select,
  Field,
  Check,
  Notice,
  Modal,
  OperationFooter,
  CredentialFields,
  DatabaseFields,
  Facts,
  CheckResults,
  Elapsed,
  SubmissionProgress,
  taskInProgress,
} from "./components";
import { api, post, put, uploadFile } from "./api";
import { useTaskSubmission } from "./useTaskSubmission";
import { INSTANCE_ENVIRONMENTS } from "./instanceEnvironment";
import TaskExecution from "./TaskExecution";

export function Login({ session, onLogin }) {
  const [password, setPassword] = useState(""),
    [confirm, setConfirm] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const submit = async (event) => {
    event.preventDefault();
    if (
      session.setupRequired &&
      (password.length < 8 || password !== confirm)
    ) {
      setError("请设置至少 8 个字符的密码，并确认两次输入一致。");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await post(session.setupRequired ? "/session/setup" : "/session/login", {
        username: "admin",
        password,
      });
      await onLogin();
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <main className="admin-login">
      <div className="login-brand">
        <img src="/assets/logo.svg" alt="DataEase" />
        <span>运维中心</span>
      </div>
      <section className="login-card">
        <div className="login-symbol">◈</div>
        <h1>{session.setupRequired ? "初始化管理员" : "管理员登录"}</h1>
        <p>
          {session.setupRequired
            ? "首次使用，请在运维服务器本机设置管理员密码。"
            : "登录后管理实例与运维任务。"}
        </p>
        <form onSubmit={submit}>
          <Field
            label="管理员账号"
            value="admin"
            readOnly
            autoComplete="username"
          />
          <Field
            label={session.setupRequired ? "设置密码" : "密码"}
            type="password"
            autoComplete={
              session.setupRequired ? "new-password" : "current-password"
            }
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            minLength={session.setupRequired ? 8 : undefined}
          />
          {session.setupRequired && (
            <Field
              label="确认密码"
              type="password"
              autoComplete="new-password"
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
            />
          )}{" "}
          {error && (
            <p className="account-error" role="alert">
              {error}
            </p>
          )}
          <Button primary type="submit" disabled={busy || !password}>
            {busy ? "正在处理…" : session.setupRequired ? "设置并登录" : "登录"}
          </Button>
        </form>
      </section>
    </main>
  );
}
export function PasswordDialog({ onClose, onSaved }) {
  const [currentPassword, setCurrent] = useState(""),
    [newPassword, setNext] = useState(""),
    [confirm, setConfirm] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const submit = async () => {
    if (newPassword.length < 8 || newPassword !== confirm) {
      setError("新密码至少 8 个字符，且两次输入应一致。");
      return;
    }
    setBusy(true);
    try {
      await put("/session/password", { currentPassword, newPassword });
      await post("/session/logout");
      onSaved();
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title="修改密码"
      onClose={onClose}
      busy={busy}
      wide={false}
      actions={
        <>
          <Button disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button primary disabled={busy} onClick={submit}>
            保存并重新登录
          </Button>
        </>
      }
    >
      <Field
        label="当前密码"
        type="password"
        autoComplete="current-password"
        value={currentPassword}
        onChange={(event) => setCurrent(event.target.value)}
      />
      <Field
        label="新密码"
        type="password"
        autoComplete="new-password"
        value={newPassword}
        onChange={(event) => setNext(event.target.value)}
      />
      <Field
        label="确认新密码"
        type="password"
        autoComplete="new-password"
        value={confirm}
        onChange={(event) => setConfirm(event.target.value)}
      />
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}
export function ConnectDialog({ instance, registerHost = false, onClose, onSaved }) {
  const hostOnly = registerHost || instance?.status === "未安装";
  const [config, setConfig] = useState(
    instance
      ? { ...instance }
      : {
          name: "",
          environment: "",
          connection: "ssh",
          host: "",
          path: "/opt/dataease3.0",
          dbType: "MySQL",
          dbHost: "",
          dbPort: 3306,
          dbName: "",
          dbUser: "",
        },
  );
  const [credentials, setCredentials] = useState({
      sshUser: instance?.sshUser || "root",
      sshPort: instance?.sshPort || 22,
      sshAuth: instance?.sshAuth || "password",
      sshPassword: "",
      privateKey: "",
      passphrase: "",
      hostKey: instance?.hostKey || "",
      dbUser: instance?.dbUser || "",
      dbPassword: "",
    }),
    [probe, setProbe] = useState(null),
    [advanced, setAdvanced] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const epoch = useRef(0);
  const change = (setter, value) => {
    epoch.current += 1;
    setter(value);
    setProbe(null);
    setError("");
  };
  const inspect = async () => {
    if (
      !config.name.trim() ||
      !INSTANCE_ENVIRONMENTS.includes(config.environment)
    ) {
      setError("请填写实例名称并选择环境标识。");
      return;
    }
    const requestEpoch = epoch.current;
    setBusy(true);
    setError("");
    try {
      const authentication = hostOnly ? credentials : {
        ...credentials,
        dbUser: config.dbUser || credentials.dbUser,
        dbPassword: config.dbPassword || credentials.dbPassword,
      };
      if (instance) {
        const updated = await put(`/instances/${instance.id}/connection`, {
          credentials: authentication,
        });
        await onSaved(updated);
        return;
      }
      const result = await post("/instances/probe", {
        mode: registerHost ? "host" : undefined,
        config: {
          ...config,
          host: config.connection === "local" ? "127.0.0.1" : config.host,
        },
        credentials: authentication,
      });
      if (requestEpoch !== epoch.current) return;
      if (registerHost) await register(result);
      else setProbe(result);
    } catch (error) {
      if (requestEpoch === epoch.current) setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const register = async (result) => {
    await onSaved(await post("/instances", {
      probeId: result.probeId,
      name: config.name.trim(),
      environment: config.environment,
    }));
  };
  const save = async () => {
    setBusy(true);
    try {
      await register(probe);
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const detected = probe?.instance;
  return (
    <Modal
      title={instance ? `连接设置 · ${instance.name}` : registerHost ? "添加新实例" : "接入已有实例"}
      onClose={onClose}
      busy={busy}
      actions={
        <>
          <Button disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button
            primary
            disabled={busy}
            onClick={instance || !probe ? inspect : save}
          >
            {busy
              ? "正在处理…"
              : instance
                ? "验证并保存"
                : registerHost
                  ? "验证并添加"
                  : probe
                  ? "确认接入"
                  : "测试并识别"}
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <Field
          label="实例名称"
          readOnly={!!instance}
          value={config.name}
          onChange={(event) =>
            change(setConfig, { ...config, name: event.target.value })
          }
        />
        <Field label="环境标识">
          <Select
            aria-label="环境标识"
            value={config.environment || ""}
            disabled={!!instance}
            onChange={(event) =>
              change(setConfig, { ...config, environment: event.target.value })
            }
          >
            <option value="">请选择环境</option>
            {INSTANCE_ENVIRONMENTS.map((item) => (
              <option key={item}>{item}</option>
            ))}
          </Select>
        </Field>
      </div>
      {!instance && (
        <Field label="连接方式">
          <Select
            aria-label="连接方式"
            value={config.connection}
            onChange={(event) =>
              change(setConfig, { ...config, connection: event.target.value })
            }
          >
            <option value="ssh">远程服务器 · SSH</option>
            <option value="local">运维中心所在服务器</option>
          </Select>
        </Field>
      )}
      {config.connection === "local" ? (
        <Notice>
          {hostOnly ? "通过运维中心服务运行账户验证本机连接。" : "本机指运维中心服务所在的服务器，由服务运行账户读取安装目录。"}
        </Notice>
      ) : (
        <Field
          label="服务器 IP / 主机名"
          value={config.host}
          readOnly={!!instance}
          onChange={(event) =>
            change(setConfig, { ...config, host: event.target.value })
          }
        />
      )}
      <CredentialFields
        value={credentials}
        onChange={(value) => change(setCredentials, value)}
        local={config.connection === "local"}
        saved={!!instance}
      />
      {!hostOnly && <Field
        label="已有安装目录"
        readOnly={!!instance}
        value={config.path}
        onChange={(event) =>
          change(setConfig, { ...config, path: event.target.value })
        }
      />}
      {!hostOnly && (instance ? (
        <>
          <Field
            label="数据库用户名"
            value={credentials.dbUser}
            onChange={(event) =>
              change(setCredentials, {
                ...credentials,
                dbUser: event.target.value,
              })
            }
          />
          <Field
            label="更新数据库密码"
            type="password"
            value={credentials.dbPassword}
            onChange={(event) =>
              change(setCredentials, {
                ...credentials,
                dbPassword: event.target.value,
              })
            }
            placeholder="留空复用已保存认证"
          />
        </>
      ) : (
        <>
          <Check
            checked={advanced}
            onChange={(value) => {
              change(setAdvanced, value);
              if (!value) {
                setConfig((old) => ({
                  ...old,
                  dbType: "MySQL",
                  dbHost: "",
                  dbPort: 3306,
                  dbName: "",
                  dbUser: "",
                  dbPassword: "",
                }));
                setCredentials((old) => ({
                  ...old,
                  dbUser: "",
                  dbPassword: "",
                }));
              }
            }}
          >
            手动补充数据库连接
          </Check>
          {advanced && (
            <DatabaseFields
              value={config}
              onChange={(value) => change(setConfig, value)}
            />
          )}
        </>
      ))}
      {detected && (
        <section className="discovery-result">
          <strong>实例识别成功</strong>
          <Facts
            rows={[
              ["版本", detected.version],
              [
                "元数据库",
                `${detected.dbType} · ${detected.dbHost}:${detected.dbPort} / ${detected.dbName}`,
              ],
              ["架构", detected.arch],
              ["应用状态", detected.status],
              ["安装目录", detected.path],
            ]}
          />
        </section>
      )}
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}
export function UploadDialog({ kind, onClose, onSaved }) {
  const [file, setFile] = useState(null),
    [password, setPassword] = useState(""),
    [transfer, setTransfer] = useState(null),
    [error, setError] = useState("");
  const busy = !!transfer;
  const submit = async () => {
    if (!file) return;
    setTransfer({ phase: "uploading", percent: null, startedAt: new Date().toISOString() });
    setError("");
    try {
      const form = new FormData();
      form.append("file", file);
      form.append("kind", kind);
      if (kind === "backup") form.append("password", password);
      const result = await uploadFile(
        kind === "backup" ? "/backups/import" : "/packages",
        form,
        (update) => setTransfer((current) => ({ ...current, ...update })),
      );
      await onSaved(result);
    } catch (error) {
      setError(error.message);
    } finally {
      setTransfer(null);
    }
  };
  return (
    <Modal
      title={
        kind === "backup"
          ? "导入备份"
          : kind === "plugin"
            ? "上传插件包"
            : "上传离线包"
      }
      onClose={onClose}
      busy={busy}
      busyLabel={null}
      wide={false}
      actions={
        <>
          <Button disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button primary disabled={!file || busy} onClick={submit}>
            {kind === "backup" ? "导入并校验" : "上传并校验"}
          </Button>
        </>
      }
    >
      <Field label="选择文件">
        <span className="file-picker">
          <span>{file?.name || "选择本地文件"}</span>
          <input
            aria-label="选择上传文件"
            type="file"
            accept={
              kind === "plugin"
                ? ".jar,.zip"
                : kind === "backup"
                  ? ".debackup"
                  : ".tar.gz,.tgz,.zip,.gz"
            }
            onChange={(event) => {
              setFile(event.target.files?.[0] || null);
              setError("");
            }}
          />
        </span>
      </Field>
      {kind === "backup" && (
        <Field
          label="备份密码"
          type="password"
          value={password}
          autoComplete="off"
          maxLength={256}
          onChange={(event) => setPassword(event.target.value)}
          hint="填写导出时设置的密码；原平台直接下载的旧备份留空，且只能回到原平台导入。"
        />
      )}
      <Notice>
        {kind === "backup"
          ? "校验密码、备份清单和全部文件后入库。导入不会修改实例，也不会继承原来的演练或异机保护状态。"
          : "服务端计算 SHA-256、检查归档内容与能力清单；不从文件名推断兼容性。"}
      </Notice>
      {transfer && <div className="wf-check-progress">
        <strong role="status"><span className="task-spinner" aria-hidden="true" />
          {transfer.phase === "uploading" ? "正在上传文件" : "上传完成，等待服务端校验"}
        </strong>
        <Elapsed since={transfer.startedAt} />
        {transfer.phase === "uploading" && transfer.percent != null &&
          <span aria-label="文件上传进度">{transfer.percent}%</span>}
      </div>}
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}
export function ExportBackupDialog({ backup, onClose, onSaved }) {
  const [password, setPassword] = useState(""),
    [confirmation, setConfirmation] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const submit = async () => {
    if (
      password.length < 8 ||
      password.length > 256 ||
      password !== confirmation
    )
      return setError("备份密码须为 8–256 个字符，且两次输入一致。");
    setBusy(true);
    setError("");
    try {
      const result = await post(`/backups/${backup.id}/export`, { password });
      const link = document.createElement("a");
      link.href = result.downloadUrl;
      link.download = "dataease-backup.debackup";
      document.body.append(link);
      link.click();
      link.remove();
      onSaved();
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title="导出备份"
      wide={false}
      busy={busy}
      busyLabel="正在校验并打包备份"
      onClose={onClose}
      actions={
        <>
          <Button disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button
            primary
            disabled={busy || !password || !confirmation}
            onClick={submit}
          >
            导出并下载
          </Button>
        </>
      }
    >
      <Facts
        rows={[
          ["备份", backup.name],
          ["范围 / 版本", `${backup.scope} / ${backup.version}`],
        ]}
      />
      <Notice>
        导出包可在重装后的运维平台导入。请将备份密码单独保管，密码遗失无法恢复；平台不保存此密码。
      </Notice>
      <Field
        label="设置备份密码"
        type="password"
        value={password}
        autoComplete="new-password"
        minLength={8}
        maxLength={256}
        onChange={(event) => setPassword(event.target.value)}
      />
      <Field
        label="确认备份密码"
        type="password"
        value={confirmation}
        autoComplete="new-password"
        maxLength={256}
        onChange={(event) => setConfirmation(event.target.value)}
      />
      <p className="wf-help">
        下载后请存放到其他服务器或存储设备。平台不会将下载操作视为已完成异机保护。
      </p>
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}

export function InstanceAdminDialog({ instance, uninstall, reset, acceptanceTaskId, onClose, onQueued, onChanged }) {
  const [password, setPassword] = useState("");
  const [initialAdmin, setInitialAdmin] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [copyStatus, setCopyStatus] = useState("");
  const formId = useId();
  const passwordInput = useRef(null);
  const resultInput = useRef(null);
  const submitting = useRef(false);
  useEffect(() => {
    if (!uninstall && !busy && error) passwordInput.current?.focus();
  }, [uninstall, busy, error]);

  const confirm = async (event) => {
    event.preventDefault();
    if (!password || submitting.current || initialAdmin) return;
    submitting.current = true;
    setBusy(true);
    setError("");
    try {
      const action = uninstall ? "uninstall" : reset ? "reset-admin" : "initial-admin";
      const result = await post(`/instances/${instance.id}/${action}`, reset
        ? { password, confirmTargetName: instance.name, instanceRevision: instance.revision, acceptanceTaskId }
        : { password });
      if (uninstall) onQueued(result);
      else {
        setInitialAdmin(result);
        if (reset) onChanged?.();
      }
    } catch (failure) {
      setError(failure.message);
    } finally {
      submitting.current = false;
      setPassword("");
      setBusy(false);
    }
  };
  const copy = async () => {
    try {
      if (navigator.clipboard) await navigator.clipboard.writeText(initialAdmin.password);
      else {
        resultInput.current?.select();
        if (!document.execCommand("copy")) throw new Error("copy failed");
      }
      setCopyStatus("已复制");
    } catch {
      resultInput.current?.select();
      setCopyStatus("密码已选中，请手动复制。");
    }
  };
  const passwordField = <Field label="运维中心 admin 密码" type="password" value={password}
    autoComplete="current-password" ref={passwordInput} data-dialog-autofocus={!uninstall || undefined}
    onChange={(event) => setPassword(event.target.value)} />;
  return (
    <Modal title={uninstall ? "卸载 DataEase" : reset ? "重置 DataEase 管理员密码" : "查看 DataEase 管理员密码"} wide={false}
      onClose={onClose} busy={busy}
      busyLabel={uninstall ? "正在处理" : reset ? "正在重置密码并验证登录" : "正在核对实例并验证登录"}
      actions={
        <>
          <Button type="button" onClick={onClose} disabled={busy}>{initialAdmin ? "关闭" : "取消"}</Button>
          {!initialAdmin && (
            <Button primary type={uninstall ? "button" : "submit"} form={uninstall ? undefined : formId}
              disabled={busy || !password} onClick={uninstall ? confirm : undefined}>
              {busy ? "正在确认…" : uninstall ? "确认卸载" : reset ? "重置并验证" : "验证并查看"}
            </Button>
          )}
        </>
      }>
      <Facts rows={[["实例", instance.name], ["服务器", instance.host]]} />
      {uninstall && (
        <Notice tone="amber">移除该实例的容器、专用网络和闲置镜像，归档原数据。实例和连接设置会保留，状态变为「未安装」。Docker、Compose 和共享镜像会保留。</Notice>
      )}
      {reset && !initialAdmin && <Notice tone="amber">此操作会替换目标实例的 DataEase admin 密码。新密码登录验证通过后可查看。</Notice>}
      {initialAdmin ? (
        <>
          <Facts rows={[["用户名", initialAdmin.username]]} />
          <Field label={reset ? "已验证的新密码" : "已验证的密码"}
            value={initialAdmin.password} readOnly autoComplete="off" ref={resultInput} />
          <div className="button-row">
            <Button small onClick={copy}>复制密码</Button>
            {copyStatus && <span role="status">{copyStatus}</span>}
          </div>
        </>
      ) : uninstall ? passwordField : <form id={formId} onSubmit={confirm}>{passwordField}</form>}
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}

export function TaskDialog({
  task,
  state,
  initialStage,
  onClose,
  onRefresh,
  onRestore,
  onAbort,
  onReconcile,
  onAutomation,
  onInstanceAdmin,
}) {
  const [releaseMode, setReleaseMode] = useState(task.acceptance?.releaseMode || "isolated");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const acceptance = task.acceptance || {};
  const acceptanceReady = acceptance.automatedPassed === true;
  const running = taskInProgress(task);
  const uninstalling = state.tasks.some((item) => item.kind === "uninstall" && item.targetId === task.targetId
    && ["queued", "running", "reconciliation_required", "reconciling"].includes(item.state));
  const awaitingDecision = task.status === "待验收" && !uninstalling;
  const registeredTarget = task.currentTarget === false ? null
    : state.instances.find((item) => item.id === task.targetId);
  const target = registeredTarget || task.targetInstanceSnapshot;
  const execute = async (action) => {
    setBusy(true);
    setError("");
    try {
      await post(`/tasks/${task.id}/acceptance/${action}`, action === "complete"
        ? { releaseMode, confirmRelease: true } : {});
      await onRefresh();
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const protectionId = task.targetBackupId || task.protectionBackups?.targetId;
  const protection = state.backups.find((item) => item.id === protectionId);
  const canRecover = registeredTarget?.recoveryRequired && task.status === "失败" && task.dataChanged
    && protection && protection.restoreMode !== "manual"
    && state.tasks.find((item) => item.targetId === task.targetId && item.currentTarget !== false && item.dataChanged)?.targetBackupId === protectionId;
  const requiresReconciliation = task.state === "reconciliation_required";
  const showAdminPassword = ["install", "migrate", "restore"].includes(task.kind)
    && registeredTarget?.canViewDataEaseAdmin;
  const showAdminReset = task.kind === "restore" && awaitingDecision && registeredTarget?.canResetDataEaseAdmin;
  const contextActions = (canRecover || requiresReconciliation || awaitingDecision || showAdminPassword || showAdminReset) ? (
    <>
      {canRecover && (
        <Button onClick={() => onRestore(protection, task.targetId, task.id, task.rollbackPackageId)}>
          还原目标保护备份
        </Button>
      )}
      {requiresReconciliation && (
        <Button onClick={() => onReconcile(task)}>核查执行状态</Button>
      )}
      {awaitingDecision && (
        <Button disabled={busy} onClick={() => onAbort(task)}>
          {task.v2ComponentUpgrade ? "回退并结束验收" : "停止并结束验收"}
        </Button>
      )}
      {showAdminPassword && <Button onClick={() => onInstanceAdmin(registeredTarget, "initial-admin")}>查看管理员密码</Button>}
      {showAdminReset && <Button onClick={() => onInstanceAdmin(registeredTarget, "reset-admin", task.id)}>重置管理员密码</Button>}
    </>
  ) : null;
  const timeLabel = task.startedAt ? "开始时间" : "提交时间";
  const taskTime = task.startedAt || task.createdAt
    ? new Date(task.startedAt || task.createdAt).toLocaleString("zh-CN", { hour12: false })
    : task.date || "—";
  return (
    <>
      <Modal
        title={task.title || task.name || task.kind}
        onClose={onClose}
        busy={busy}
        className="task-dialog"
        footer={
          <OperationFooter contextActions={contextActions} busyLabel={busy ? "正在提交验收" : null}>
            <Button disabled={busy} onClick={onClose}>
              关闭
            </Button>
            {awaitingDecision && (
              <Button primary disabled={busy || !acceptanceReady} onClick={() => execute("complete")}>
                {releaseMode === "open" ? "完成验收并开放访问" : "完成验收，保持隔离"}
              </Button>
            )}
          </OperationFooter>
        }
      >
        <div className="task-dialog-content">
          <div className="task-summary">
            <Badge tone={running ? "blue" : task.status === "失败" ? "red" : task.status === "成功" ? "green" : "amber"}>
              {task.status || "未知"}
            </Badge>
            <dl>
              {(task.kind === "migrate" ? [
                ["源实例", task.instanceName || task.instanceSnapshot?.name],
                ["目标实例", target?.name || task.targetName],
              ] : [["操作实例", target?.name || task.targetName || task.instanceName]]).map(([label, value]) => (
                <div className="task-summary-instance" key={label}>
                  <dt>{label}</dt><dd title={value}>{value || "—"}</dd>
                </div>
              ))}
              <div className="task-summary-time"><dt>{timeLabel}</dt><dd>{taskTime}</dd></div>
              <div className="task-summary-id"><dt>任务编号</dt><dd>{task.id}</dd></div>
            </dl>
          </div>
          <TaskExecution task={task} active={running} initialStage={initialStage} />
          <div className="task-context" tabIndex={0} role="region" aria-label="补充任务信息">
            {task.upgradeRecovered
              ? <Notice>已回退到升级前的版本和数据，实例保持隔离运行。</Notice>
              : task.error && <Notice tone="red">{task.error}</Notice>}
            {((task.status === "失败" && !task.upgradeRecovered && !["release", "backup", "check", "service", "uninstall"].includes(task.kind)) ||
              task.state === "reconciliation_required") && (
              <Notice tone="amber">
                {task.state === "reconciliation_required"
                  ? "执行是否结束尚未确认，请先核查现场状态，暂不重试或恢复。"
                  : task.kind === "automation"
                    ? "自动任务变更未完成。请刷新实时清单核对调度状态，已经开始的任务可能继续执行。"
                  : task.sameHostMigration
                    ? "源 V2 数据保留。请在实例管理核对源应用状态，必要时启动原 V2。若已创建独立 V3，可检查失败日志后卸载该 V3 再重试。"
                  : task.dataChanged
                    ? canRecover
                      ? "目标已发生变更，未自动回滚。可使用本任务保留的目标保护备份恢复变更前状态。"
                      : registeredTarget && !registeredTarget.recoveryRequired
                        ? "目标已完成后续处理。当前状态见实例管理。"
                        : "请核对目标状态与可用恢复点。"
                    : "请核对失败原因和实例状态，再重新发起操作。"}
              </Notice>
            )}
            {task.kind === "release" && task.state === "succeeded" && (
              <Notice>业务入口已开放并通过核验，自动任务保持暂停。</Notice>
            )}
            {task.state === "succeeded" &&
              ["migrate", "restore", "install", "upgrade", "plugin"].includes(
                task.kind,
              ) && (
                <Notice>
                  {task.restorePurpose === "rehearsal" ? "演练已通过，业务入口关闭，自动任务继续暂停。"
                    : task.acceptance?.releaseMode === "open"
                      ? "业务入口已开放，可按需恢复自动任务。"
                      : "已保持隔离运行，业务入口关闭，自动任务继续暂停。"}
                  {task.acceptance?.releaseMode === "open" && onAutomation && (
                    <Button small onClick={() => onAutomation(task.targetId)}>
                      管理自动任务
                    </Button>
                  )}
                </Notice>
              )}
            {task.automationResult && (
              <>
                <Notice>
                  {task.automationResult.schedulingSuspended
                    ? task.automationResult.runningJobs > 0
                      ? `后续自动调度已暂停，核验时仍有 ${task.automationResult.runningJobs} 个已开始的任务在执行。`
                      : "自动调度已暂停。"
                    : "仅所选任务获准运行，应用重启后需要重新授权。"}
                </Notice>
                <CheckResults checks={task.automationResult.jobs
                  .filter((job) => job.category !== "unsupported")
                  .map((job) => ({ title: job.name, status: "passed", detail: job.enabled ? "已恢复调度" : "后续调度已暂停" }))} />
              </>
            )}
            {task.status === "待验收" && uninstalling && <Notice>该实例正在处理卸载，请在任务中心查看卸载任务。</Notice>}
            {awaitingDecision && (
              <section className="lc-acceptance">
                <h3>{task.restorePurpose === "rehearsal" ? "演练验收" : "验收后的访问策略"}</h3>
                {task.kind === "restore" && !registeredTarget?.canViewDataEaseAdmin && (
                  <p>{task.restoredAdminAccount ? "请使用备份时的 DataEase 管理员密码。" : "请使用还原前的 DataEase 管理员密码。"}
                    {showAdminReset && "忘记密码可在此重置。"}</p>
                )}
                {!acceptanceReady && (
                  <div className="button-row">
                    <Button disabled={busy} onClick={() => execute("check")}>重新核验</Button>
                  </div>
                )}
                {task.restorePurpose !== "rehearsal" && <Field label="访问策略">
                  <Select aria-label="验收后的访问策略" value={releaseMode}
                    onChange={(event) => setReleaseMode(event.target.value)}>
                    <option value="isolated">保持隔离</option>
                    <option value="open">开放业务访问</option>
                  </Select>
                </Field>}
                <Notice>
                  {task.restorePurpose === "rehearsal" ? "验收后继续隔离运行，业务入口关闭。" : releaseMode === "open"
                    ? "复核运行条件后切换至正式网络，按部署配置发布业务入口。切换会重建应用及相关容器，并等待入口核验通过。"
                    : "保留当前隔离网络和端口限制，应用继续运行，业务入口保持关闭。"}
                  自动任务继续暂停。
                </Notice>
              </section>
            )}
            {error && <Notice tone="red">{error}</Notice>}
            <details className="task-compact-meta">
              <summary>任务信息</summary>
              <Facts rows={[[timeLabel, taskTime], ["任务编号", task.id]]} />
            </details>
          </div>
        </div>
      </Modal>

    </>
  );
}

const automationLabels = { sync: "数据同步", reports: "定时报告", alerts: "阈值告警", unsupported: "其他任务" };
export function AutomationDialog({ instance, onClose, onQueued }) {
  const loadGeneration = useRef(0);
  const [catalog, setCatalog] = useState(null),
    [selected, setSelected] = useState([]),
    [confirmName, setConfirmName] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const { progress, submit, reset } = useTaskSubmission();
  const load = async () => {
    const generation = ++loadGeneration.current;
    setBusy(true); setError(""); setCatalog(null); setSelected([]); setConfirmName(""); reset();
    try {
      const actual = await api(`/instances/${instance.id}/automation`);
      if (generation !== loadGeneration.current) return;
      setCatalog(actual);
      setSelected(actual.jobs.filter((job) => job.enabled && job.available).map((job) => job.id));
    } catch (failure) {
      if (generation === loadGeneration.current) { setCatalog(null); setError(failure.message); }
    }
    finally { if (generation === loadGeneration.current) setBusy(false); }
  };
  useEffect(() => { load(); return () => { loadGeneration.current++; }; }, [instance.id]);
  const updateSelection = (selection) => {
    setSelected(selection);
    reset();
  };
  const confirm = async () => {
    setBusy(true); setError("");
    try {
      const result = await submit({
        kind: "automation", sourceId: instance.id, automationSnapshot: catalog.snapshot,
        selectedJobs: selected, confirmTargetName: confirmName,
      });
      if (result?.task) onQueued(result.task);
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="管理自动任务" busy={busy} busyLabel={progress ? null : "读取实际调度状态"} onClose={onClose} actions={<>
      <Button disabled={busy} onClick={onClose}>取消</Button>
      <Button disabled={busy} onClick={load}>刷新实际状态</Button>
      <Button primary disabled={busy || !catalog || confirmName !== instance.name} onClick={confirm}>
        {selected.length ? "确认恢复所选任务" : "确认全部暂停"}
      </Button>
    </>}>
      <Facts rows={[["操作实例", instance.name], ["环境", instance.environment || instance.tag || "—"], ["服务器", instance.host || "—"]]} />
      {error && <Notice tone="red">{error}</Notice>}
      {catalog && <>
        <Notice>
          仅恢复已在 DataEase 启用且已注册的计划任务。取消选择会暂停后续调度，不会取消已经开始的任务。
          恢复时跳过积压时点；同步将写入目标数据源，报告与告警可能向原接收人发送消息。
        </Notice>
        <div className="automation-selection-heading">
          <strong>已选择 {selected.length} 项</strong>
          <Button small disabled={!selected.length} onClick={() => updateSelection([])}>全部保持暂停</Button>
        </div>
        {Object.entries(automationLabels).filter(([category]) => category !== "unsupported").map(([category, label]) => {
          const jobs = catalog.jobs.filter((job) => job.category === category);
          return <section className="automation-category" key={category}>
            <h3 className="section-title">{label} <span className="muted">{jobs.length} 项</span></h3>
            {jobs.length ? jobs.map((job) => <div className="automation-job" key={job.id}>
              <Check checked={selected.includes(job.id)} disabled={!job.available}
                onChange={(checked) => updateSelection(checked ? [...selected, job.id] : selected.filter((id) => id !== job.id))}>
                {job.name}
              </Check>
              <Badge tone={job.enabled ? "green" : "gray"}>{job.enabled ? "已恢复调度" : "保持暂停"}</Badge>
              <p className="muted">{job.available ? `任务 ${job.taskId} · 下一计划时点 ${job.nextRunAt ? new Date(job.nextRunAt).toLocaleString("zh-CN", { hour12: false }) : "—"}` : job.reason}</p>
            </div>) : <p className="muted">未发现已注册的{label}计划。</p>}
          </section>;
        })}
        {catalog.jobs.some((job) => job.category === "unsupported") && (
          <p className="muted">另有 {catalog.jobs.filter((job) => job.category === "unsupported").length} 项系统或暂不支持的计划，继续保持暂停。</p>
        )}
        <Notice>应用重启后恢复授权失效，所有自动任务重新暂停；刷新本页可核对实际状态。停用、过期或缺少有效企业授权的任务，请先在 DataEase 处理。</Notice>
        <Field label={`输入“${instance.name}”确认目标`} value={confirmName}
          onChange={(event) => setConfirmName(event.target.value)} autoComplete="off" />
      </>}
      {progress && <SubmissionProgress progress={progress} />}
    </Modal>
  );
}
