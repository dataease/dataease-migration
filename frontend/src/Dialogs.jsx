import React, { useEffect, useRef, useState } from "react";
import { Badge } from "./ui";
import {
  Button,
  Select,
  Field,
  Check,
  Notice,
  Modal,
  CredentialFields,
  DatabaseFields,
  Facts,
  CheckResults,
} from "./components";
import { api, post, put, exportJson } from "./api";
import { INSTANCE_ENVIRONMENTS } from "./instanceEnvironment";

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
export function ConnectDialog({ instance, onClose, onSaved }) {
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
      if (instance) {
        const updated = await put(`/instances/${instance.id}/connection`, {
          credentials: {
            ...credentials,
            dbUser: config.dbUser || credentials.dbUser,
            dbPassword: config.dbPassword || credentials.dbPassword,
          },
        });
        await onSaved(updated);
        return;
      }
      const result = await post("/instances/probe", {
        config: {
          ...config,
          host: config.connection === "local" ? "127.0.0.1" : config.host,
        },
        credentials: {
          ...credentials,
          dbUser: config.dbUser || credentials.dbUser,
          dbPassword: config.dbPassword || credentials.dbPassword,
        },
      });
      if (requestEpoch === epoch.current) setProbe(result);
    } catch (error) {
      if (requestEpoch === epoch.current) setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const save = async () => {
    setBusy(true);
    try {
      await onSaved(
        await post("/instances", {
          probeId: probe.probeId,
          name: config.name.trim(),
          environment: config.environment,
        }),
      );
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const detected = probe?.instance;
  return (
    <Modal
      title={instance ? `连接设置 · ${instance.name}` : "接入已有实例"}
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
            <option value="local">本机 · 本地目录</option>
          </Select>
        </Field>
      )}
      {config.connection === "local" ? (
        <Notice>
          本机指运维中心服务所在的服务器，由服务运行账户读取安装目录。
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
      <Field
        label="已有安装目录"
        readOnly={!!instance}
        value={config.path}
        onChange={(event) =>
          change(setConfig, { ...config, path: event.target.value })
        }
      />
      {instance ? (
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
      )}
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
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const submit = async () => {
    if (!file) return;
    setBusy(true);
    setError("");
    try {
      const form = new FormData();
      form.append("file", file);
      form.append("kind", kind);
      if (kind === "backup") form.append("password", password);
      const result = await post(
        kind === "backup" ? "/backups/import" : "/packages",
        form,
      );
      await onSaved(result);
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
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
      wide={false}
      actions={
        <>
          <Button disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button primary disabled={!file || busy} onClick={submit}>
            {busy ? "正在上传与校验…" : "上传并校验"}
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
            {busy ? "正在校验与打包…" : "导出并下载"}
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

const manualItems = [
  ["login", "登录与权限"],
  ["dashboard", "代表性仪表板与大屏"],
  ["connections", "数据源与写入目标"],
  ["entry", "实际业务入口"],
];
export function TaskDialog({
  task,
  state,
  onClose,
  onRefresh,
  onRestore,
  onAbort,
  onReconcile,
  onAutomation,
}) {
  const [draft, setDraft] = useState(
      task.acceptance || {
        manual: {},
        notes: "",
        releaseMode: "isolated",
        releaseConfirmed: false,
        automation: { sync: false, reports: false, alerts: false },
      },
    ),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [queryDialog, setQueryDialog] = useState(false),
    [query, setQuery] = useState("");
  const change = (key, value) =>
    setDraft((old) => ({
      ...old,
      [key]: value,
      ...(key !== "releaseConfirmed" ? { releaseConfirmed: false } : {}),
    }));
  const execute = async (action) => {
    setBusy(true);
    setError("");
    try {
      if (action === "check") await post(`/tasks/${task.id}/acceptance/check`);
      else {
        await put(`/tasks/${task.id}/acceptance`, {
          ...draft,
          releaseConfirmed: false,
        });
        await put(`/tasks/${task.id}/acceptance`, draft);
        await post(`/tasks/${task.id}/acceptance/complete`);
      }
      await onRefresh();
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const acceptance = task.acceptance || {};
  const acceptanceReady =
    acceptance.automatedPassed === true &&
    manualItems.every(([key]) => draft.manual?.[key]);
  const target =
    task.targetInstanceSnapshot ||
    state.instances.find((item) => item.id === task.targetId);
  const protectionId = task.targetBackupId || task.protectionBackups?.targetId;
  const protection = state.backups.find((item) => item.id === protectionId);
  const stages = Array.isArray(task.stages) ? task.stages : [];
  const logs = Array.isArray(task.logs)
    ? task.logs
        .map((item) =>
          typeof item === "string"
            ? item
            : item.message || JSON.stringify(item),
        )
        .join("\n")
    : task.logs || "";
  const editQuery = async () => {
    setBusy(true);
    setError("");
    try {
      await put(`/instances/${task.targetId}/environment`, {
        environmentConfig: { acceptanceQuery: query },
      });
      setDraft((old) => ({ ...old, releaseConfirmed: false }));
      await post(`/tasks/${task.id}/acceptance/check`);
      await onRefresh();
      setQueryDialog(false);
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <Modal
        title={task.title || task.name || task.kind}
        onClose={onClose}
        busy={busy}
        actions={
          <>
            <Button disabled={busy} onClick={onClose}>
              关闭
            </Button>
            {task.status === "失败" && task.dataChanged && protection && (
              <Button
                onClick={() => onRestore(protection, task.targetId, task.id)}
              >
                还原目标保护备份
              </Button>
            )}
            {task.state === "reconciliation_required" && (
              <Button onClick={() => onReconcile(task)}>核查执行状态</Button>
            )}
            {task.status === "待验收" && (
              <Button disabled={busy} onClick={() => onAbort(task)}>
                停止并结束验收
              </Button>
            )}
            {task.status === "待验收" && (
              <Button
                primary
                disabled={busy || !acceptanceReady || !draft.releaseConfirmed}
                onClick={() => execute("complete")}
              >
                {draft.releaseMode === "open"
                  ? "完成验收并开放访问"
                  : "完成验收，保持隔离"}
              </Button>
            )}
          </>
        }
      >
        <Facts
          rows={[
            ["任务编号", task.id],
            ["状态", task.status],
            ...(task.kind === "migrate"
              ? [
                  ["源实例", task.instanceName || task.instanceSnapshot?.name],
                  ["目标实例", target?.name || task.targetName],
                ]
              : [
                  [
                    "操作实例",
                    target?.name || task.targetName || task.instanceName,
                  ],
                ]),
            [
              "开始时间",
              task.createdAt
                ? new Date(task.createdAt).toLocaleString("zh-CN", {
                    hour12: false,
                  })
                : task.date,
            ],
            ...(task.failureStage ? [["失败阶段", task.failureStage]] : []),
          ]}
        />
        {task.error && <Notice tone="red">{task.error}</Notice>}
        {(task.status === "失败" ||
          task.state === "reconciliation_required") && (
          <Notice tone="amber">
            {task.state === "reconciliation_required"
              ? "执行是否结束尚未确认，请先核查现场状态，暂不重试或恢复。"
              : task.kind === "automation"
                ? "自动任务变更未完成。请刷新实时清单核对调度状态，已经开始的任务可能继续执行。"
              : task.dataChanged
                ? protection
                  ? "目标已发生变更，未自动回滚。可使用本任务保留的目标保护备份恢复变更前状态。"
                  : "目标可能已发生变更，未自动回滚。请先核对目标状态与可用恢复点。"
                : "任务尚未进入数据覆盖阶段，应用仍可能已停止。请先核对失败原因和实例状态，再重新发起操作。"}
          </Notice>
        )}
        {task.state === "succeeded" &&
          ["migrate", "restore", "install", "upgrade", "plugin"].includes(
            task.kind,
          ) && (
            <Notice>
              {task.acceptance?.releaseMode === "open"
                ? "本次验收选择开放业务访问。"
                : "本次验收选择保持环境隔离。"}
              自动任务需要在开放验收后单独选择恢复，实际状态请从自动任务清单核对。
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
              已回读实际调度状态。仅所选任务获准运行，未选择和未知任务继续拦截；应用重启后授权失效，需要重新核验。
            </Notice>
            <CheckResults checks={task.automationResult.jobs
              .filter((job) => job.category !== "unsupported")
              .map((job) => ({ title: job.name, status: "passed", detail: job.enabled ? "已恢复调度" : "保持暂停" }))} />
          </>
        )}
        {task.status === "执行中" && (
          <div className="live-progress">
            <div
              style={{ width: `${Math.min(100, Number(task.progress) || 0)}%` }}
            />
          </div>
        )}
        {stages.length > 0 && (
          <details open={task.status !== "待验收"}>
            <summary>执行阶段与处理记录</summary>
            <CheckResults
              checks={stages.map((stage) => ({
                ...stage,
                title: stage.title || stage.name,
                detail: stage.detail || stage.message,
                status: stage.status === "completed" ? "passed" : stage.status,
              }))}
            />
          </details>
        )}
        {task.status === "待验收" && (
          <section className="lc-acceptance">
            <h3>业务验收与访问控制</h3>
            <Notice>
              目标隔离运行，业务访问未开放。请完成自动检查及业务核对，再选择访问策略。自动任务继续暂停，开放验收后可单独选择恢复。
            </Notice>
            <div className="button-row">
              <Button disabled={busy} onClick={() => execute("check")}>
                {busy ? "正在处理…" : "运行验收检查"}
              </Button>
              <Button
                disabled={busy}
                onClick={() => {
                  setQuery(
                    state.instances.find((item) => item.id === task.targetId)
                      ?.environmentConfig?.acceptanceQuery || "",
                  );
                  setError("");
                  setQueryDialog(true);
                }}
              >
                维护验收查询
              </Button>
            </div>
            <CheckResults
              prioritize
              actionTypes={["query"]}
              checks={acceptance.checks || acceptance.automatedChecks || []}
              onAction={(action) => {
                if (action.type === "query") {
                  setQuery(
                    state.instances.find((item) => item.id === task.targetId)
                      ?.environmentConfig?.acceptanceQuery || "",
                  );
                  setError("");
                  setQueryDialog(true);
                }
              }}
            />
            {manualItems.map(([key, label]) => (
              <Check
                key={key}
                checked={draft.manual?.[key]}
                onChange={(value) =>
                  change("manual", { ...draft.manual, [key]: value })
                }
              >
                {label}
              </Check>
            ))}
            <Field label="验收说明 / 不适用项">
              <textarea
                rows="3"
                maxLength={4000}
                value={draft.notes || ""}
                onChange={(event) => change("notes", event.target.value)}
              />
            </Field>
            <Field label="验收后的访问策略">
              <Select
                aria-label="验收后的访问策略"
                value={draft.releaseMode || "isolated"}
                onChange={(event) => change("releaseMode", event.target.value)}
              >
                <option value="isolated">通过后保持隔离</option>
                <option
                  value="open"
                  disabled={task.restorePurpose === "rehearsal"}
                >
                  通过后开放业务访问
                </option>
              </Select>
            </Field>
            {!acceptanceReady && (
              <Notice>
                请先通过自动检查，并完成全部业务核对，再确认验收结果。
              </Notice>
            )}
            <Check
              checked={draft.releaseConfirmed}
              disabled={!acceptanceReady}
              onChange={(value) => change("releaseConfirmed", value)}
            >
              已核对目标与验收结果，确认
              {draft.releaseMode === "open"
                ? "开放本环境业务访问"
                : "保持本环境隔离"}
            </Check>
          </section>
        )}
        {task.kind === "check" && task.report?.checks && (
          <>
            <h3>检查结果</h3>
            <CheckResults checks={task.report.checks} />
          </>
        )}
        {error && <Notice tone="red">{error}</Notice>}
        <div className="live-log-head">
          <h3>执行日志</h3>
          <Button
            small
            icon="download"
            onClick={() => exportJson(`task-${task.id}.json`, task)}
          >
            导出任务记录
          </Button>
        </div>
        <pre className="live-logs">{logs || "尚无执行日志"}</pre>
      </Modal>
      {queryDialog && (
        <Modal
          title="业务验收查询"
          wide={false}
          busy={busy}
          onClose={() => setQueryDialog(false)}
          actions={
            <>
              <Button disabled={busy} onClick={() => setQueryDialog(false)}>
                取消
              </Button>
              <Button
                primary
                disabled={busy || !query.trim()}
                onClick={editQuery}
              >
                {busy ? "正在检查…" : "保存并重新检查"}
              </Button>
            </>
          }
        >
          <Notice>
            填写能够返回业务样例的只读
            SELECT。保存后重新检查，原验收确认失效；不代替仪表板和权限的人工核对。
          </Notice>
          <Field label="业务验收查询（只读 SQL）">
            <textarea
              rows="6"
              maxLength={16000}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </Field>
          {error && <Notice tone="red">{error}</Notice>}
        </Modal>
      )}
    </>
  );
}

const automationLabels = { sync: "数据同步", reports: "定时报告", alerts: "阈值告警", unsupported: "其他任务" };
export function AutomationDialog({ instance, onClose, onQueued }) {
  const loadGeneration = useRef(0);
  const [catalog, setCatalog] = useState(null),
    [selected, setSelected] = useState([]),
    [check, setCheck] = useState(null),
    [confirmName, setConfirmName] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const load = async () => {
    const generation = ++loadGeneration.current;
    setBusy(true); setError(""); setCatalog(null); setSelected([]); setCheck(null); setConfirmed(false); setConfirmName("");
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
  const command = { kind: "automation", sourceId: instance.id, automationSnapshot: catalog?.snapshot, selectedJobs: selected };
  const inspect = async () => {
    setBusy(true); setError(""); setConfirmed(false); setConfirmName("");
    try { setCheck(await post("/prechecks", command)); }
    catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  };
  const submit = async () => {
    setBusy(true); setError("");
    try {
      const task = await post("/tasks", { ...command, precheckId: check.id, confirmTargetName: confirmName });
      await onQueued(task);
    } catch (failure) { setError(failure.message); setCheck(null); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="管理自动任务" busy={busy} onClose={onClose} actions={<>
      <Button disabled={busy} onClick={onClose}>取消</Button>
      {check ? <>
        <Button disabled={busy} onClick={() => { setCheck(null); setConfirmed(false); }}>返回选择</Button>
        <Button primary disabled={busy || !check.passed || !confirmed || confirmName !== instance.name} onClick={submit}>
          {busy ? "提交中…" : selected.length ? "确认恢复所选任务" : "确认全部暂停"}
        </Button>
      </> : <>
        <Button disabled={busy} onClick={load}>刷新实际状态</Button>
        <Button primary disabled={busy || !catalog} onClick={inspect}>{busy ? "检查中…" : "检查并确认"}</Button>
      </>}
    </>}>
      <Facts rows={[["操作实例", instance.name], ["环境", instance.environment || instance.tag || "—"], ["服务器", instance.host || "—"]]} />
      {error && <Notice tone="red">{error}</Notice>}
      {busy && !catalog && <Notice>正在读取实际调度状态…</Notice>}
      {!check && catalog && <>
        <Notice>
          仅恢复已在 DataEase 启用且已注册的计划任务。取消选择会暂停后续调度，不会取消已经开始的任务。
          恢复时跳过积压时点；同步将写入目标数据源，报告与告警可能向原接收人发送消息。
        </Notice>
        <div className="automation-selection-heading">
          <strong>已选择 {selected.length} 项</strong>
          <Button small onClick={() => setSelected([])}>全部保持暂停</Button>
        </div>
        {Object.entries(automationLabels).filter(([category]) => category !== "unsupported").map(([category, label]) => {
          const jobs = catalog.jobs.filter((job) => job.category === category);
          return <section className="automation-category" key={category}>
            <h3 className="section-title">{label} <span className="muted">{jobs.length} 项</span></h3>
            {jobs.length ? jobs.map((job) => <div className="automation-job" key={job.id}>
              <Check checked={selected.includes(job.id)} disabled={!job.available} onChange={() => setSelected((old) => old.includes(job.id) ? old.filter((id) => id !== job.id) : [...old, job.id])}>
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
      </>}
      {check && <>
        <CheckResults checks={check.checks || []} prioritize />
        <h3 className="section-title">{selected.length ? `本次将恢复 ${selected.length} 项任务` : "本次将暂停全部后续调度"}</h3>
        {selected.length ? catalog.jobs.filter((job) => selected.includes(job.id)).map((job) => <p key={job.id}>{automationLabels[job.category]} · {job.name}</p>) : <Notice>全部任务保持暂停，已开始的任务不会被强行取消。</Notice>}
        {selected.length > 0 && <Notice tone="amber">未选择的任务继续暂停。恢复后可能写入业务数据或发送消息，请核对目标与原有任务配置。</Notice>}
        <Field label="确认实例名称" value={confirmName} onChange={(event) => setConfirmName(event.target.value)} placeholder={instance.name} autoComplete="off" />
        <Check checked={confirmed} onChange={() => setConfirmed(!confirmed)}>{selected.length ? "已核对任务的数据目标、接收人和执行计划，确认本次调度范围。" : "确认暂停全部后续调度，已经开始的任务可能继续执行。"}</Check>
      </>}
    </Modal>
  );
}
