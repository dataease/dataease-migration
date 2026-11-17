import React, { useEffect, useMemo, useRef, useState } from "react";
import { Badge, Empty } from "./ui";
import {
  Button,
  Icon,
  Select,
  TimeSelect,
  Field,
  Check,
  Notice,
  Modal,
  OperationFooter,
  Facts,
  CheckResults,
  tone,
  backupLocation,
  Elapsed,
  SubmissionProgress,
  taskActivity,
  taskInProgress,
} from "./components";
import {
  PageHeading,
  ModuleSummary,
  ListHeading,
  ListFooter,
  TableRegion,
} from "./ModuleLayout";
import {
  Login,
  PasswordDialog,
  ConnectDialog,
  UploadDialog,
  ExportBackupDialog,
  TaskDialog,
  InstanceAdminDialog,
  AutomationDialog,
} from "./Dialogs";
import Workflow from "./Workflow";
import { OnlinePackageDialog, DownloadRows, DownloadDetailDialog } from "./PackageDownloads";
import MigrationOverview from "./MigrationOverview";
import InstanceTaskProgress from "./InstanceTaskProgress";
import { api, post, put, exportJson } from "./api";
import { useTaskSubmission } from "./useTaskSubmission";
import { instanceEnvironment } from "./instanceEnvironment";
import { backupProtection, canRebuildFromBackup } from "./backupProtection";
const uninstalled = (item) => item?.status === "未安装";
const taskForInstance = (task, id) => task.instanceId === id || task.targetId === id;
const openTaskStates = new Set(["queued", "running", "pending_acceptance", "checking_acceptance",
  "accepting", "aborting", "reconciliation_required", "reconciling"]);
const finishedTaskStatuses = new Set(["成功", "失败", "已中断", "已取消"]);
const wholeRecoveryPoint = (backup, instance) => canRebuildFromBackup(backup) && backup.arch === instance.arch
  && (backup.restoreMode !== "original-enterprise"
    || instance.connection === "ssh" && instance.host !== backup.instanceSnapshot?.host);
const canRestoreBackup = (backup, instances) => backup.status === "已校验" && (
  !backup.restoreMode || canRebuildFromBackup(backup) || (backup.restoreMode === "original-enterprise" &&
    backup.currentInstanceDeployment === true && instances.some((instance) =>
      instance.id === backup.instanceId && instance.status !== "未安装" &&
      instance.version === backup.version && instance.arch === backup.arch &&
      instance.dbType === backup.dbType))
);
const menus = [
  ["overview", "运维概览", "overview"],
  ["instances", "实例管理", "server"],
  ["migration", "版本迁移", "migrate"],
  ["backups", "备份与还原", "database"],
  ["health", "健康检查", "shield"],
  ["packages", "软件包管理", "folder"],
  ["tasks", "任务中心", "tasks"],
];
const empty = {
  instances: [],
  packages: [],
  plugins: [],
  backups: [],
  tasks: [],
  plans: [],
  healthPlans: [],
  downloads: [],
};
const date = (value) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
const operationName = (kind) =>
  ({
    install: "安装实例",
    upgrade: "离线升级",
    migrate: "版本迁移",
    backup: "创建备份",
    restore: "还原备份",
    check: "健康检查",
    plugin: "安装插件",
    service: "服务操作",
    automation: "自动任务恢复",
    release: "开放业务访问",
    uninstall: "卸载 DataEase",
  })[kind] || kind;

function InstanceStatus({ instance, currentTask, lastResult, onTask, onRelease }) {
  const task = currentTask || lastResult;
  const finished = Boolean(task && !currentTask);
  const status = instance.archivedByMigrationTo ? "已迁移" : instance.status || "未知";
  const running = currentTask && taskInProgress(currentTask);
  const service = task?.kind === "service"
    ? { "启动应用": "启动实例", "停止应用": "停止实例" }[task.stages?.[0]?.title]
    : null;
  const operation = task && (service || operationName(task.kind));
  const caption = task && `${operation} · ${running ? taskActivity(task).title : task.status}`;
  return <>
    <Badge tone={tone(instance.archivedByMigrationTo ? "已停止" : status)}>{status}</Badge>
    <small className={`instance-status-note${task ? " has-task" : ""}`}>
      <span className="instance-status-fallback" aria-hidden={task ? true : undefined}>
        {onRelease ? (
          <button type="button" className="link" disabled={Boolean(task)} onClick={onRelease}>开放业务访问</button>
        ) : instance.connection === "local" ? "本机管理" : "SSH 管理"}
      </span>
      {task && (
        <span className="instance-task-actions">
          <button type="button" className="link instance-task-link" title={caption} onClick={() => onTask(task)}>
            {running && <span className="task-spinner" aria-hidden="true" />}
            <span className="instance-task-caption">{finished ? operation : caption}</span>
            {finished && <span className={`instance-task-outcome ${tone(task.status)}`}> · {task.status}</span>}
          </button>
          {finished && onRelease && (
            <button type="button" className="link instance-task-release"
              aria-label="开放业务访问" title="开放业务访问" onClick={onRelease}>开放访问</button>
          )}
        </span>
      )}
    </small>
  </>;
}

export default function App() {
  const [session, setSession] = useState(null),
    [state, setState] = useState(empty),
    [page, setPage] = useState(
      menus.some((item) => item[0] === location.hash.slice(1))
        ? location.hash.slice(1)
        : "overview",
    ),
    [query, setQuery] = useState(""),
    [filter, setFilter] = useState("全部"),
    [tab, setTab] = useState("备份记录"),
    [mediaTab, setMediaTab] = useState("package"),
    [dialog, setDialog] = useState(null),
    [flow, setFlow] = useState(null),
    [error, setError] = useState(""),
    [toast, setToast] = useState(""),
    [refreshing, setRefreshing] = useState(false),
    [accountOpen, setAccountOpen] = useState(false);
  const notify = (message) => {
    setToast(message);
    setTimeout(() => setToast(""), 4500);
  };
  const refreshSequence = useRef(0);
  const appliedRefresh = useRef(0);
  const refresh = async () => {
    const sequence = ++refreshSequence.current;
    const next = await api("/state");
    if (sequence > appliedRefresh.current) {
      appliedRefresh.current = sequence;
      setState({ ...empty, ...next });
    }
    return next;
  };
  const loadSession = async () => {
    setError("");
    try {
      const value = await api("/session");
      setSession(value);
      if (value.authenticated) await refresh();
    } catch (error) {
      setError(error.message);
      setSession({ authenticated: false });
    }
  };
  useEffect(() => {
    loadSession();
    const expire = () => {
      appliedRefresh.current = refreshSequence.current;
      setSession({ authenticated: false });
      setState(empty);
      setDialog(null);
      setFlow(null);
    };
    window.addEventListener("session-expired", expire);
    return () => window.removeEventListener("session-expired", expire);
  }, []);
  const activeWork = state.tasks.some(taskInProgress) ||
    state.downloads.some((item) => ["queued", "checking", "downloading", "pausing", "cancelling", "verifying"].includes(item.state));
  useEffect(() => {
    if (!session?.authenticated) return;
    const timer = setInterval(
      () => refresh().catch((error) => setError(error.message)),
      activeWork ? 2000 : 10000,
    );
    return () => clearInterval(timer);
  }, [session?.authenticated, activeWork]);
  const { taskByInstance, lastResultByInstance } = useMemo(() => {
    const active = new Map(), finished = new Map();
    // The state API returns tasks newest first, including tasks associated with two instances.
    for (const task of state.tasks) {
      const open = openTaskStates.has(task.state);
      if (!open && !finishedTaskStatuses.has(task.status)) continue;
      for (const id of [task.instanceId, task.targetId]) {
        if (!id) continue;
        if (open) {
          const current = active.get(id);
          if (!current || (taskInProgress(task) && !taskInProgress(current))) active.set(id, task);
        } else if (!finished.has(id)) {
          finished.set(id, task);
        }
      }
    }
    return { taskByInstance: active, lastResultByInstance: finished };
  }, [state.tasks]);
  const instanceTask = dialog?.type === "instance"
    ? state.tasks.find((task) => task.id === dialog.taskId)
      || taskByInstance.get(dialog.id)
    : null;
  useEffect(() => {
    if (!instanceTask || dialog.taskId) return;
    setDialog((current) => current === dialog ? { ...current, taskId: instanceTask.id } : current);
  }, [dialog, instanceTask?.id]);
  useEffect(() => {
    const change = () => {
      const id = location.hash.slice(1);
      if (menus.some((item) => item[0] === id)) {
        setPage(id);
        setQuery("");
        setFilter("全部");
      }
    };
    window.addEventListener("hashchange", change);
    return () => window.removeEventListener("hashchange", change);
  }, []);
  useEffect(() => {
    if (!accountOpen) return;
    const close = (event) => {
      if (!event.target.closest(".account-control")) setAccountOpen(false);
    };
    const key = (event) => {
      if (event.key === "Escape") setAccountOpen(false);
    };
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("pointerdown", close);
      document.removeEventListener("keydown", key);
    };
  }, [accountOpen]);
  const go = (id) => {
    location.hash = id;
    setPage(id);
    setQuery("");
    setFilter("全部");
  };
  const invoke = async (action) => {
    try {
      setError("");
      await action();
      await refresh();
    } catch (error) {
      setError(error.message);
    }
  };
  const active = (instance) => taskByInstance.has(instance.id);
  const showInstance = (id, taskId) => setDialog({ type: "instance", id, taskId });
  const start = (
    kind,
    instance,
    backup,
    targetId,
    packageId,
    protectionTaskId,
    options = {},
  ) => {
    setDialog(null);
    setFlow({
      kind,
      instance,
      backup,
      targetId,
      packageId,
      protectionTaskId,
      ...options,
      key: Date.now(),
    });
  };
  const complete = (task, message, { returnInstanceId, askToViewTask } = {}) => {
    setFlow(null);
    const showInInstance = returnInstanceId && (!task || taskForInstance(task, returnInstanceId));
    if (task?.id) {
      setState((current) => ({
        ...current,
        tasks: current.tasks.some((item) => item.id === task.id)
          ? current.tasks : [task, ...current.tasks],
      }));
      if (askToViewTask) {
        setDialog({ type: "task-submitted", task });
      } else if (!showInInstance) {
        if (["install", "uninstall", "release", "migrate"].includes(task.kind)) go("tasks");
        setDialog({ type: "task", id: task.id, task });
      }
    }
    if (showInInstance && !askToViewTask) showInstance(returnInstanceId, task?.id);
    refresh().catch((error) => setError(error.message));
    if (!askToViewTask || !task?.id) notify(message || "任务已提交，可查看实际执行阶段与日志。");
  };
  const saved = async () => {
    setDialog(null);
    await refresh();
    notify("已保存");
  };
  const saveInstanceSettings = async (id) => {
    await refresh();
    showInstance(id);
    notify("已保存");
  };
  const download = (id) => {
    setDialog({ type: "export-backup", id });
  };
  const matches = (item) =>
    [
      item.name,
      item.title,
      item.id,
      item.host,
      item.instanceName,
      item.fileName,
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase()
      .includes(query.toLowerCase()) &&
    (filter === "全部" ||
      [item.status, item.health, item.scope].includes(filter) ||
      (filter === "执行中" && taskInProgress(item)));
  const toolbar = (label, options = ["全部"]) => (
    <div className="toolbar inner">
      <div className="search">
        <Icon name="search" />
        <input
          aria-label={label}
          placeholder={label}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>
      <Select
        aria-label="列表筛选"
        value={filter}
        onChange={(event) => setFilter(event.target.value)}
      >
        {options.map((option) => (
          <option key={option}>{option}</option>
        ))}
      </Select>
      <span className="toolbar-spacer" />
    </div>
  );
  const deploymentByTarget = new Map(state.tasks
    .filter((task) => task.kind === "install" && (taskInProgress(task) || task.status === "待验收"))
    .map((task) => [task.targetId, task]));
  const startFromInstanceRow = (kind, instance) => {
    const options = { askToViewTask: page === "instances" };
    if (kind === "install") start(kind, null, null, instance.id, undefined, undefined, options);
    else start(kind, instance, undefined, undefined, undefined, undefined, options);
  };
  const instanceRows = (rows, compact = false) => (
    <>
      <TableRegion label="实例列表">
        <table className="instance-table">
          <thead>
            <tr>
              <th>实例 / 主机</th>
              <th>版本 / 元数据库</th>
              <th>运行状态</th>
              <th>数据保护</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((instance) => (
              <tr key={instance.id}>
                <td>
                  <button
                    className="instance-name-link"
                    onClick={() =>
                      setDialog({ type: "instance", id: instance.id })
                    }
                  >
                    <span className="instance-mark">
                      <Icon name="server" />
                    </span>
                    <span>
                      {instance.name}
                      <small>
                        {instance.host}{" "}
                        <span className="env-tag">
                          {instanceEnvironment(instance)}
                        </span>
                      </small>
                    </span>
                  </button>
                </td>
                <td>
                  {instance.version || "—"}
                  {instance.dbType && <small>
                    {instance.dbType} · {instance.dbName || instance.db}
                  </small>}
                </td>
                <td>
                  <InstanceStatus instance={instance} currentTask={taskByInstance.get(instance.id)}
                    lastResult={lastResultByInstance.get(instance.id)}
                    onTask={(task) => taskInProgress(task) ? showInstance(instance.id, task.id)
                      : setDialog({ type: "task", id: task.id, task })}
                    onRelease={page === "instances" && instance.canOpenAccess
                      ? () => setDialog({ type: "release", item: instance, askToViewTask: true }) : undefined} />
                </td>
                <td>
                  <span
                    className={
                      backupProtection(instance, state.backups).protected
                        ? "protected"
                        : "protection-incomplete"
                    }
                  >
                    {backupProtection(instance, state.backups).label}
                  </span>
                  {backupProtection(instance, state.backups).backup && (
                    <small>
                      恢复点{" "}
                      {date(
                        backupProtection(instance, state.backups).backup
                          .recoveryPoint ||
                          backupProtection(instance, state.backups).backup
                            .createdAt,
                      )}
                    </small>
                  )}
                </td>
                <td>
                  <div className="table-actions">
                    {uninstalled(instance) ? (
                      <button className="link" disabled={active(instance)} onClick={() => startFromInstanceRow("install", instance)}>
                        安装
                      </button>
                    ) : <>
                    <button
                      className="link"
                      disabled={active(instance)}
                      onClick={() => startFromInstanceRow("backup", instance)}
                    >
                      备份
                    </button>
                    <button
                      className="link"
                      onClick={() => startFromInstanceRow("check", instance)}
                    >
                      检查
                    </button>
                    </>}
                    <button
                      className="link"
                      onClick={() =>
                        setDialog({ type: "instance", id: instance.id })
                      }
                    >
                      管理
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && <Empty text="暂无实例" />}
      </TableRegion>
      {!compact && <ListFooter count={rows.length} unit="个实例" />}
    </>
  );
  const taskRows = (rows, compact = false) => (
    <>
      <TableRegion label="任务记录">
        <table>
          <thead>
            <tr>
              <th>任务名称</th>
              <th>操作实例</th>
              <th>状态</th>
              <th>提交时间</th>
              <th>当前阶段 / 耗时</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((task) => {
              const activity = taskInProgress(task) ? taskActivity(task) : null;
              return (
                <tr key={task.id}>
                  <td>
                    <div className="table-title">
                      <Icon name="tasks" />
                      {task.title || operationName(task.kind)}
                    </div>
                    <small>{task.id}</small>
                  </td>
                  <td>
                    {task.instanceName ||
                      task.instanceSnapshot?.name ||
                      task.targetName}
                    <small>
                      {task.targetName && task.targetName !== task.instanceName
                        ? `目标：${task.targetName}`
                        : operationName(task.kind)}
                    </small>
                  </td>
                  <td>
                    <Badge tone={tone(task.status)}>
                      {taskInProgress(task) && <span className="task-spinner" aria-hidden="true" />}
                      {task.status}
                    </Badge>
                  </td>
                  <td>{date(task.createdAt || task.startedAt)}</td>
                  <td className="task-progress-cell">
                    {activity ? (
                      <>
                        <div>{activity.title}</div>
                        <Elapsed since={activity.since} label={activity.waiting ? "已等待" : "已用"} />
                      </>
                    ) : task.duration || "—"}
                  </td>
                  <td>
                    <button
                      className="link"
                      onClick={() => setDialog({ type: "task", id: task.id })}
                    >
                      {taskInProgress(task) ? task.kind === "install" ? "查看部署进度" : "查看进度" : "查看详情"}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {!rows.length && <Empty text="暂无任务记录" />}
      </TableRegion>
      {!compact && <ListFooter count={rows.length} />}
    </>
  );
  if (!session)
    return (
      <div className="live-loading">
        <Icon name="refresh" />
        正在连接运维中心…{error && <p>{error}</p>}
      </div>
    );
  if (!session.authenticated)
    return (
      <>
        <Login session={session} onLogin={loadSession} />
        {error && (
          <div className="live-login-error">
            {error}
            <Button onClick={loadSession}>重试连接</Button>
          </div>
        )}
      </>
    );
  const current = menus.find((item) => item[0] === page) || menus[0];
  const currentInstances = state.instances.filter((item) => !item.archivedByMigrationTo);
  const deployedInstances = currentInstances.filter((item) => !uninstalled(item));
  const instances = page === "instances"
    ? state.instances.filter((item) => filter === "已迁移"
        ? item.archivedByMigrationTo && matches({ ...item, status: "已迁移" })
        : !item.archivedByMigrationTo && matches(item))
    : deployedInstances.filter(matches);
  const tasks = state.tasks.filter(matches);
  const backups = state.backups.filter(matches);
  const media = (
    mediaTab === "package" ? state.packages : state.plugins
  ).filter(matches);
  const selectedInstance =
    dialog?.type === "instance"
      ? state.instances.find((item) => item.id === dialog.id)
      : null;
  const selectedTask =
    dialog?.type === "task"
      ? state.tasks.find((item) => item.id === dialog.id) || dialog.task
      : null;
  const selectedBackup =
    dialog?.type === "backup"
      ? state.backups.find((item) => item.id === dialog.id)
      : null;
  const selectedMedia =
    dialog?.type === "media"
      ? [...state.packages, ...state.plugins].find(
          (item) => item.id === dialog.id,
        )
      : null;
  return (
    <>
      <header className="topbar">
        <div className="brand">
          <img src="/assets/logo.svg" alt="DataEase" />
          <span className="brand-divider" />
          <strong>运维中心</strong>
        </div>
        <div className="top-right">
          <div className="account-control">
            <button
              className="account-trigger"
              aria-label="管理员菜单"
              aria-haspopup="menu"
              aria-expanded={accountOpen}
              onClick={() => setAccountOpen(!accountOpen)}
            >
              <span className="avatar">A</span>
              <span>管理员</span>
              <span>⌄</span>
            </button>
            {accountOpen && (
              <div className="account-popover live-account" role="menu">
                <div className="account-identity">
                  <strong>admin</strong>
                  <span>系统管理员</span>
                </div>
                <button
                  role="menuitem"
                  onClick={() => {
                    setAccountOpen(false);
                    setDialog({ type: "password" });
                  }}
                >
                  修改密码
                </button>
                <button
                  role="menuitem"
                  onClick={async () => {
                    try {
                      await post("/session/logout");
                      setSession({ authenticated: false });
                      setState(empty);
                      setAccountOpen(false);
                      setFlow(null);
                      setDialog(null);
                    } catch (error) {
                      setError(error.message);
                    }
                  }}
                >
                  退出登录
                </button>
              </div>
            )}
          </div>
        </div>
      </header>
      <aside className="sidebar">
        <div className="side-section nav-caption">运维管理</div>
        <nav>
          {menus.map(([id, label, icon]) => (
            <button
              key={id}
              className={`nav-item ${id === page ? "active" : ""}`}
              aria-label={label}
              onClick={() => go(id)}
            >
              <Icon name={icon} />
              <span>{label}</span>
            </button>
          ))}
        </nav>
        <div className="sidebar-separator" />
        <div className="side-section">快捷入口</div>
        <button
          className="nav-item"
          aria-label="快捷创建备份"
          onClick={() => start("backup")}
        >
          <Icon name="download" />
          <span>创建备份</span>
        </button>
      </aside>
      <main className="main">
        <div className="contextbar">
          <div className="breadcrumb">
            运维中心<span>/</span>
            {current[1]}
          </div>
          <div className="context-tools">
            <span className="muted">
              全局管理 · {currentInstances.length} 个实例
            </span>
            <button
              className="icon-button"
              aria-label="刷新记录"
              disabled={refreshing}
              onClick={async () => {
                setRefreshing(true);
                await invoke(refresh);
                setRefreshing(false);
              }}
            >
              <Icon name="refresh" />
            </button>
          </div>
        </div>
        <div className="page-content module-page" data-page={page}>
          {page === "overview" && (
            <>
              <PageHeading
                title="运维概览"
                actions={
                  <Button
                    icon="refresh"
                    disabled={refreshing}
                    onClick={() => invoke(refresh)}
                  >
                    刷新概览
                  </Button>
                }
              />
              <div className="metrics">
                {[
                  [
                    "纳管实例",
                    currentInstances.length,
                    `${currentInstances.filter((item) => item.status === "运行中").length} 个运行`,
                  ],
                  [
                    "需要关注",
                    deployedInstances.filter((item) => item.health !== "正常")
                      .length,
                    "异常或尚未检查",
                  ],
                  [
                    "整体备份覆盖",
                    deployedInstances.filter(
                      (item) => backupProtection(item, state.backups).protected,
                    ).length,
                    "当前版本 · 已校验的一致整体备份",
                  ],
                  [
                    "进行中的任务",
                    state.tasks.filter((item) =>
                      ["执行中", "待验收", "排队中"].includes(item.status),
                    ).length,
                    "执行中及等待验收",
                  ],
                ].map(([label, value, detail]) => (
                  <div className="metric" key={label}>
                    <div>
                      <span>{label}</span>
                      <div className="metric-number">{value}</div>
                      <p>{detail}</p>
                    </div>
                  </div>
                ))}
              </div>
              <div className="global-attention">
                <Icon name="info" />
                <span>
                  {deployedInstances.length
                    ? deployedInstances.some(
                        (item) =>
                          !backupProtection(item, state.backups).protected,
                      )
                      ? `${deployedInstances.filter((item) => !backupProtection(item, state.backups).protected).length} 个实例缺少当前版本的一致整体备份，分项或在线备份不计入完整保护。`
                      : "所有实例均有当前版本的已校验一致整体备份，请定期核对恢复点并进行恢复演练。"
                    : "暂无已部署的 DataEase 实例。"}
                </span>
                <button className="link" onClick={() => go("instances")}>
                  实例管理
                </button>
              </div>
              <div className="overview-panels">
                <section className="panel">
                  <div className="panel-heading">
                    <h2>实例运行概况</h2>
                    <button className="link" onClick={() => go("instances")}>
                      全部实例
                    </button>
                  </div>
                  {instanceRows(currentInstances.slice(0, 5), true)}
                </section>
                <section className="quick-actions">
                  {[
                    ["upgrade", "离线升级", "upload"],
                    ["backup", "创建备份", "database"],
                    ["restore", "还原备份", "refresh"],
                    ["check", "健康检查", "shield"],
                  ].map(([kind, label, icon]) => (
                    <button
                      key={kind}
                      onClick={() =>
                        kind === "restore" ? go("backups") : start(kind)
                      }
                    >
                      <Icon name={icon} />
                      <strong>{label}</strong>
                      <Icon name="arrow" size={14} />
                    </button>
                  ))}
                </section>
                <section className="panel">
                  <div className="panel-heading">
                    <h2>最近任务</h2>
                    <button className="link" onClick={() => go("tasks")}>
                      全部任务
                    </button>
                  </div>
                  {taskRows(state.tasks.slice(0, 5), true)}
                </section>
              </div>
            </>
          )}
          {page === "instances" && (
            <>
              <PageHeading
                title="实例管理"
                actions={
                  <>
                    <Button
                      icon="server"
                      onClick={() => setDialog({ type: "connect" })}
                    >
                      接入已有实例
                    </Button>
                    <Button primary icon="add" onClick={() => setDialog({ type: "register-host" })}>
                      添加新实例
                    </Button>
                  </>
                }
              />
              <ModuleSummary
                items={[
                  { label: "当前实例", value: currentInstances.length },
                  {
                    label: "运行中",
                    value: currentInstances.filter(
                      (item) => item.status === "运行中",
                    ).length,
                    tone: "green",
                  },
                  {
                    label: "未安装",
                    value: currentInstances.filter(
                      uninstalled,
                    ).length,
                  },
                  {
                    label: "待验收",
                    value: currentInstances.filter(
                      (item) => item.status === "待验收",
                    ).length,
                    tone: "amber",
                  },
                ]}
              />
              <section className="panel">
                <ListHeading title="实例列表" count={instances.length} />
                {toolbar("搜索名称、IP 或版本", [
                  "全部",
                  "运行中",
                  "已停止",
                  "未安装",
                  "待验收",
                  "隔离运行",
                  "未知",
                  "已迁移",
                ])}
                {instanceRows(instances)}
              </section>
            </>
          )}
          {page === "migration" && (
            <MigrationOverview onStart={() => start("migrate")} />
          )}
          {page === "backups" && (
            <>
              <PageHeading
                title="备份与还原"
                actions={
                  <>
                    <Button
                      icon="upload"
                      onClick={() =>
                        setDialog({ type: "upload", kind: "backup" })
                      }
                    >
                      导入备份
                    </Button>
                    <Button primary icon="add" onClick={() => start("backup")}>
                      创建备份
                    </Button>
                  </>
                }
              />
              <ModuleSummary
                items={[
                  { label: "备份总数", value: state.backups.length },
                  {
                    label: "已校验",
                    value: state.backups.filter(
                      (item) => item.status === "已校验",
                    ).length,
                    tone: "green",
                  },
                  {
                    label: "整体备份",
                    value: state.backups.filter(
                      (item) => item.scope === "整体备份",
                    ).length,
                  },
                  {
                    label: "异机保存",
                    value: state.backups.filter((item) => item.offsite).length,
                  },
                ]}
              />
              <section className="panel">
                <div className="tabs">
                  {["备份记录", "备份计划", "还原记录"].map((item) => (
                    <button
                      key={item}
                      className={item === tab ? "selected" : ""}
                      onClick={() => {
                        setTab(item);
                        setQuery("");
                        setFilter("全部");
                      }}
                    >
                      {item}
                    </button>
                  ))}
                </div>
                {tab === "备份记录" ? (
                  <>
                    {toolbar("搜索备份名称或来源", [
                      "全部",
                      "整体备份",
                      "数据备份",
                      "资源备份",
                      "已校验",
                      "校验失败",
                    ])}
                    <TableRegion label="备份记录">
                      <table>
                        <thead>
                          <tr>
                            <th>备份名称</th>
                            <th>来源实例</th>
                            <th>范围 / 版本</th>
                            <th>校验与副本</th>
                            <th>恢复点</th>
                            <th>操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          {backups.map((backup) => (
                            <tr key={backup.id}>
                              <td>
                                {backup.name}
                                <small>{backup.id}</small>
                              </td>
                              <td>
                                {backup.instanceName}
                                <small>{backup.dbType}</small>
                              </td>
                              <td>
                                {backup.scope}
                                <small>{backup.version}{backup.restoreMode === "manual" ? " · 可导出归档" : backup.restoreMode === "original-enterprise" ? canRebuildFromBackup(backup) ? " · 可异机重建" : " · 仅限原部署还原" : ""}</small>
                              </td>
                              <td>
                                <Badge tone={tone(backup.status)}>
                                  {backup.status}
                                </Badge>
                                <small>
                                  {backup.offsite === true
                                    ? "异机保存"
                                    : backup.offsite === false
                                      ? "同机保存"
                                      : "是否异机待核对"}
                                </small>
                              </td>
                              <td>
                                {date(backup.recoveryPoint || backup.createdAt)}
                              </td>
                              <td>
                                <div className="table-actions">
                                  <button
                                    className="link"
                                    onClick={() =>
                                      setDialog({
                                        type: "backup",
                                        id: backup.id,
                                      })
                                    }
                                  >
                                    详情
                                  </button>
                                  {canRestoreBackup(backup, state.instances) && <button
                                    className="link"
                                    onClick={() =>
                                      start("restore", null, backup)
                                    }
                                  >
                                    还原
                                  </button>}
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      {!backups.length && <Empty text="暂无备份记录" />}
                    </TableRegion>
                    <ListFooter count={backups.length} />
                  </>
                ) : tab === "还原记录" ? (
                  <>
                    {toolbar("搜索还原任务", [
                      "全部",
                      "执行中",
                      "待验收",
                      "成功",
                      "失败",
                    ])}
                    {taskRows(tasks.filter((item) => item.kind === "restore"))}
                  </>
                ) : (
                  <>
                    <div className="toolbar inner">
                      <span className="muted">
                        每天按 Asia/Shanghai 时区执行
                      </span>
                      <span className="toolbar-spacer" />
                      <Button
                        small
                        onClick={() =>
                          start("backup", null, null, null, null, null, {
                            scheduled: true,
                          })
                        }
                      >
                        新增备份计划
                      </Button>
                    </div>
                    <TableRegion label="备份计划">
                      <table>
                        <thead>
                          <tr>
                            <th>实例</th>
                            <th>备份范围</th>
                            <th>计划</th>
                            <th>状态</th>
                            <th>最近执行</th>
                            <th>操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          {state.plans.map((plan) => (
                            <tr key={plan.id}>
                              <td>
                                {plan.instanceName ||
                                  state.instances.find(
                                    (item) =>
                                      item.id ===
                                      (plan.instanceId || plan.sourceId),
                                  )?.name}
                              </td>
                              <td>{plan.scope || plan.kind}</td>
                              <td>每天 {plan.time}</td>
                              <td>
                                <Badge tone={plan.enabled ? "green" : "gray"}>
                                  {plan.enabled ? "已启用" : "已暂停"}
                                </Badge>
                              </td>
                              <td>{date(plan.lastRunAt)}</td>
                              <td>
                                <div className="table-actions">
                                  <button
                                    className="link"
                                    onClick={() =>
                                      invoke(() =>
                                        put(`/plans/${plan.id}`, {
                                          ...plan,
                                          enabled: !plan.enabled,
                                        }),
                                      )
                                    }
                                  >
                                    {plan.enabled ? "暂停" : "启用"}
                                  </button>
                                  <button
                                    className="link"
                                    onClick={() =>
                                      setDialog({ type: "plan", item: plan })
                                    }
                                  >
                                    修改
                                  </button>
                                  <button
                                    className="link"
                                    onClick={() =>
                                      setDialog({
                                        type: "delete-plan",
                                        item: plan,
                                      })
                                    }
                                  >
                                    删除
                                  </button>
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      {!state.plans.length && <Empty text="暂无备份计划" />}
                    </TableRegion>
                    <ListFooter count={state.plans.length} />
                  </>
                )}
              </section>
            </>
          )}
          {page === "health" && (
            <>
              <PageHeading
                title="健康检查"
                actions={
                  <>
                    <Button
                      icon="download"
                      onClick={() =>
                        exportJson(
                          "DataEase-健康报告.json",
                          deployedInstances.map((item) => ({
                            name: item.name,
                            host: item.host,
                            health: item.health,
                            checkedAt: item.lastCheck,
                            checks:
                              item.healthReport?.checks ||
                              item.healthReport ||
                              [],
                          })),
                        )
                      }
                    >
                      导出汇总报告
                    </Button>
                    <Button
                      icon="clock"
                      onClick={() => setDialog({ type: "health-plans" })}
                    >
                      巡检计划
                    </Button>
                    <Button
                      primary
                      icon="shield"
                      onClick={() => start("check")}
                    >
                      开始检查
                    </Button>
                  </>
                }
              />
              <ModuleSummary
                items={[
                  { label: "全部实例", value: deployedInstances.length },
                  {
                    label: "检查正常",
                    value: deployedInstances.filter(
                      (item) => item.health === "正常",
                    ).length,
                    tone: "green",
                  },
                  {
                    label: "建议关注",
                    value: deployedInstances.filter(
                      (item) =>
                        item.health === "关注" || item.health === "异常",
                    ).length,
                    tone: "amber",
                  },
                  { label: "定时巡检", value: state.healthPlans.length },
                ]}
              />
              <section className="panel">
                <ListHeading
                  title="实例检查记录"
                  count={deployedInstances.length}
                />
                {toolbar("搜索检查实例", [
                  "全部",
                  "正常",
                  "关注",
                  "异常",
                  "未检查",
                ])}
                <TableRegion label="实例检查记录">
                  <table>
                    <thead>
                      <tr>
                        <th>实例</th>
                        <th>应用状态</th>
                        <th>检查结论</th>
                        <th>最近检查</th>
                        <th>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {instances.map((instance) => (
                        <tr key={instance.id}>
                          <td>
                            {instance.name}
                            <small>{instance.host}</small>
                          </td>
                          <td>
                            <Badge tone={tone(instance.status)}>
                              {instance.status}
                            </Badge>
                          </td>
                          <td>
                            <Badge tone={tone(instance.health || "未检查")}>
                              {instance.health || "未检查"}
                            </Badge>
                          </td>
                          <td>{date(instance.lastCheck)}</td>
                          <td>
                            <div className="table-actions">
                              <button
                                className="link"
                                onClick={() =>
                                  setDialog({ type: "health", item: instance })
                                }
                              >
                                结果
                              </button>
                              <button
                                className="link"
                                onClick={() => start("check", instance)}
                              >
                                检查
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {!instances.length && <Empty text="暂无检查实例" />}
                </TableRegion>
                <ListFooter count={instances.length} unit="个实例" />
              </section>
            </>
          )}
          {page === "packages" && (
            <>
              <PageHeading
                title="软件包管理"
                actions={
                  <>
                    <Button icon="download" onClick={() => setDialog({ type: "online-package", kind: mediaTab === "plugin" ? "plugin" : "package" })}>在线获取</Button>
                    <Button primary icon="upload" onClick={() => setDialog({ type: "upload", kind: mediaTab === "plugin" ? "plugin" : "package" })}>
                      {mediaTab === "plugin" ? "上传插件包" : "上传离线包"}
                    </Button>
                  </>
                }
              />
              <ModuleSummary
                items={[
                  { label: "离线包", value: state.packages.length },
                  {
                    label: "已校验离线包",
                    value: state.packages.filter(
                      (item) => item.status === "已校验",
                    ).length,
                    tone: "green",
                  },
                  { label: "插件包", value: state.plugins.length },
                  {
                    label: "待处理介质",
                    value: [...state.packages, ...state.plugins].filter(
                      (item) => item.status !== "已校验",
                    ).length,
                    tone: "amber",
                  },
                ]}
              />
              <section className="panel">
                <div className="tabs">
                  {[
                    ["package", "安装 / 升级包"],
                    ["plugin", "插件包"],
                    ["downloads", "下载任务"],
                  ].map(([value, label]) => (
                    <button
                      key={value}
                      className={mediaTab === value ? "selected" : ""}
                      onClick={() => {
                        setMediaTab(value);
                        setQuery("");
                        setFilter("全部");
                      }}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                {mediaTab === "downloads" ? <>
                  {toolbar("搜索文件名或来源", ["全部", "排队中", "检查下载源", "下载中", "已暂停", "校验入库", "已入库", "失败", "已取消"])}
                  <DownloadRows items={state.downloads} query={query} filter={filter} onOpen={(item) => setDialog({ type: "download-detail", id: item.id })} />
                </> : <>
                {toolbar("搜索包名称、版本或编号", [
                  "全部",
                  "已校验",
                  "待校验",
                  "待补充清单",
                  "校验失败",
                ])}
                <TableRegion label={mediaTab === "plugin" ? "插件包" : "安装和升级包"}>
                  <table className="package-library-table">
                    <thead>
                      <tr>
                        <th>软件包</th>
                        <th>版本 / 架构</th>
                        <th>适用范围</th>
                        <th>状态</th>
                        <th>上传时间</th>
                        <th>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {media.map((item) => (
                        <tr key={item.id}>
                          <td>
                            <div className="table-title">
                              <Icon name="folder" />
                              {item.name || item.fileName}
                            </div>
                            <small>
                              {item.size || `${item.sizeBytes || 0} B`}
                            </small>
                          </td>
                          <td>
                            {item.version || "待解析"}
                            <small>{item.arch || item.major || "—"}</small>
                          </td>
                          <td>
                            {(item.purposes || [])
                              .map(
                                (value) =>
                                  ({
                                    install: "安装",
                                    upgrade: "升级",
                                    restore: "恢复",
                                  })[value] || value,
                              )
                              .join(" / ")}
                            <small>{(item.dbTypes || []).join("、")}</small>
                          </td>
                          <td>
                            <Badge tone={tone(item.status)}>
                              {item.status}
                            </Badge>
                          </td>
                          <td>{date(item.uploadedAt)}</td>
                          <td>
                            <div className="table-actions">
                              <button
                                className="link"
                                onClick={() =>
                                  setDialog({ type: "media", id: item.id })
                                }
                              >
                                详情
                              </button>
                              {mediaTab === "plugin" && (
                                <button
                                  className="link"
                                  disabled={item.installSupported === false}
                                  title={
                                    item.installSupported === false
                                      ? "缺少受支持的原生插件描述或兼容声明"
                                      : undefined
                                  }
                                  onClick={() =>
                                    start(
                                      "plugin",
                                      undefined,
                                      undefined,
                                      undefined,
                                      item.id,
                                    )
                                  }
                                >
                                  安装
                                </button>
                              )}
                              <button
                                className="link"
                                onClick={() =>
                                  setDialog({ type: "delete-media", item })
                                }
                              >
                                移除
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {!media.length && <Empty text="暂无软件包" />}
                </TableRegion>
                <ListFooter count={media.length}>
                  上传一次，按版本与环境复用
                </ListFooter>
                </>}
              </section>
            </>
          )}
          {page === "tasks" && (
            <>
              <PageHeading title="任务中心" />
              <ModuleSummary
                items={[
                  { label: "全部任务", value: state.tasks.length },
                  {
                    label: "执行中",
                    value: state.tasks.filter(taskInProgress).length,
                    tone: "green",
                  },
                  {
                    label: "待验收",
                    value: state.tasks.filter(
                      (item) => item.status === "待验收",
                    ).length,
                    tone: "amber",
                  },
                  {
                    label: "失败 / 待核查",
                    value: state.tasks.filter((item) =>
                      ["失败", "已中断", "状态待确认"].includes(item.status),
                    ).length,
                  },
                ]}
              />
              <section className="panel">
                <ListHeading title="任务记录" count={state.tasks.length} />
                {toolbar("搜索任务、编号或实例", [
                  "全部",
                  "执行中",
                  "待验收",
                  "成功",
                  "失败",
                  "已中断",
                  "状态待确认",
                ])}
                {taskRows(tasks)}
              </section>
            </>
          )}
        </div>
      </main>
      {error && (
        <div className="live-error-bar" role="alert">
          <span>{error}</span>
          <button aria-label="关闭错误提示" onClick={() => setError("")}>
            ×
          </button>
        </div>
      )}
      {toast && <div className="toast">{toast}</div>}
      {flow && (
        <Workflow
          key={flow.key}
          flow={flow}
          state={state}
          onClose={() => {
            setFlow(null);
            if (flow.returnInstanceId) showInstance(flow.returnInstanceId);
          }}
          onComplete={(task, message) => complete(task, message, flow)}
          onRefresh={refresh}
          onOpenTask={(id) => setDialog({ type: "task", id })}
          onManageEnvironment={(instance) =>
            setDialog({ type: "environment", item: instance })
          }
          onManageConnection={(instance) =>
            setDialog({ type: "connection", id: instance.id })
          }
        />
      )}
      {dialog?.type === "password" && (
        <PasswordDialog
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            setSession({ authenticated: false });
            setState(empty);
            notify("密码已修改，请重新登录。");
          }}
        />
      )}
      {["connect", "connection", "register-host"].includes(dialog?.type) && (
        <ConnectDialog
          registerHost={dialog.type === "register-host"}
          instance={
            dialog.type === "connection"
              ? state.instances.find((item) => item.id === dialog.id)
              : null
          }
          onClose={() => dialog.returnInstanceId ? showInstance(dialog.returnInstanceId) : setDialog(null)}
          onSaved={dialog.type === "register-host" ? async (item) => {
            await refresh();
            setDialog({ type: "instance", id: item.id });
            notify("实例已添加");
          } : dialog.returnInstanceId ? () => saveInstanceSettings(dialog.returnInstanceId) : saved}
        />
      )}
      {dialog?.type === "online-package" && <OnlinePackageDialog defaultKind={dialog.kind} onClose={() => setDialog(null)} onCreated={async (task) => { await refresh(); setMediaTab("downloads"); setQuery(""); setFilter("全部"); setDialog({ type: "download-detail", id: task.id }); }} />}
      {dialog?.type === "download-detail" && state.downloads.find((item) => item.id === dialog.id) && <DownloadDetailDialog item={state.downloads.find((item) => item.id === dialog.id)} onClose={() => setDialog(null)} onRefresh={refresh} onMedia={(id) => { if ([...state.packages, ...state.plugins].some((item) => item.id === id)) setDialog({ type: "media", id }); else notify("该软件包已从库中移除，可重新获取。"); }} />}
      {dialog?.type === "upload" && (
        <UploadDialog
          kind={dialog.kind}
          onClose={() => setDialog(null)}
          onSaved={saved}
        />
      )}
      {dialog?.type === "task-submitted" && (
        <Modal title="任务已提交" wide={false} className="task-submitted-dialog"
          onClose={() => setDialog(null)}
          actions={<>
            <Button onClick={() => setDialog(null)}>留在列表</Button>
            <Button primary onClick={() => setDialog({ type: "task", id: dialog.task.id, task: dialog.task })}>
              查看执行详情
            </Button>
          </>}>
          <p>是否查看「{dialog.task.title || operationName(dialog.task.kind)}」的执行详情？</p>
        </Modal>
      )}
      {selectedTask && (
        <TaskDialog
          key={selectedTask.id}
          task={selectedTask}
          initialStage={dialog.stageIndex}
          state={state}
          onClose={() => dialog.originInstanceId
            ? showInstance(dialog.originInstanceId, selectedTask.id) : setDialog(null)}
          onRefresh={refresh}
          onRestore={(backup, targetId, protectionTaskId, packageId) =>
            start(
              "restore",
              null,
              backup,
              targetId,
              packageId,
              protectionTaskId,
            )
          }
          onAutomation={(id) => {
            const item = state.instances.find((instance) => instance.id === id);
            if (item) setDialog({ type: "automation", item });
          }}
          onAbort={(task) => setDialog({ type: "abort", item: task, originInstanceId: dialog.originInstanceId })}
          onReconcile={(task) => setDialog({ type: "reconcile", item: task, originInstanceId: dialog.originInstanceId })}
          onInstanceAdmin={(item, type, acceptanceTaskId) => setDialog({
            type, item, acceptanceTaskId, returnTaskId: selectedTask.id, originInstanceId: dialog.originInstanceId,
          })}
        />
      )}
      {["initial-admin", "reset-admin", "uninstall"].includes(dialog?.type) && (
        <InstanceAdminDialog
          key={`${dialog.type}-${dialog.item.id}`}
          instance={dialog.item}
          uninstall={dialog.type === "uninstall"}
          reset={dialog.type === "reset-admin"}
          acceptanceTaskId={dialog.acceptanceTaskId}
          onClose={() => dialog.returnTaskId
            ? setDialog({ type: "task", id: dialog.returnTaskId, originInstanceId: dialog.originInstanceId })
            : showInstance(dialog.item.id)}
          onQueued={(task) => complete(task, undefined, { returnInstanceId: dialog.item.id })}
          onChanged={() => { void refresh().catch(() => {}); }}
        />
      )}
      {dialog?.type === "automation" && (
        <AutomationDialog instance={dialog.item} onClose={() => dialog.returnInstanceId
          ? showInstance(dialog.returnInstanceId) : setDialog(null)}
          onQueued={(task) => complete(task, undefined, dialog)} />
      )}
      {selectedInstance && (() => {
        const item = selectedInstance;
        const { id } = item;
        const vacant = uninstalled(item);
        const locked = active(item);
        const executing = instanceTask && taskInProgress(instanceTask);
        const options = { returnInstanceId: id };
        const close = () => setDialog(null);
        const edit = (type, extra = {}) => setDialog({ type, id, item, ...options, ...extra });
        const engine = { shared: "与元数据库共库", external: "独立数据引擎" };
        const access = { isolated: "隔离访问", open: "业务入口" };
        const rows = vacant ? [
          ["环境标识", instanceEnvironment(item)],
          ["服务器", item.host],
          ["架构", item.arch],
        ] : [
          ["环境标识", instanceEnvironment(item)],
          ["应用", [item.version, item.arch, "Docker Compose"].filter(Boolean).join(" · ")],
          ["服务器 / 目录", [item.host, item.path].filter(Boolean).join(" · ")],
          ["元数据库", [item.dbType, [
            [item.dbHost, item.dbPort].filter(Boolean).join(":"), item.dbName,
          ].filter(Boolean).join(" / ")].filter(Boolean).join(" · ")],
          ["数据引擎", engine[item.environmentConfig?.engineMode] || "待核对"],
          ["资源与配置", item.path ? `${item.path}/data` : null],
          ["访问策略", access[item.accessMode] || "待核对"],
          ["保护边界", "不包含外部业务数据库的原始数据"],
        ];
        return (
          <Modal title={item.name} className="instance-detail-dialog" onClose={close}
            footer={<OperationFooter><Button onClick={close}>关闭</Button></OperationFooter>}>
            <div className="instance-connection-actions">
              <Badge tone={tone(item.status)}>{item.status}</Badge>
              <div className="instance-detail-shortcuts">
                <Button small disabled={locked} onClick={() => edit("connection")}>连接设置</Button>
                {item.canViewDataEaseAdmin && <Button small onClick={() => edit("initial-admin")}>查看管理员密码</Button>}
                {item.canResetDataEaseAdmin && <Button small onClick={() => edit("reset-admin")}>重置管理员密码</Button>}
              </div>
            </div>
            {item.archivedByMigrationTo && (
              <Notice>同机迁移已验收，原 V2 已停止，原数据保留。</Notice>
            )}
            <section className="instance-detail-section">
              <div className="instance-detail-head">
                <h3>{vacant ? "服务器信息" : "环境组成"}</h3>
                {!vacant && <Button small disabled={locked} onClick={() => edit("environment")}>维护环境组成</Button>}
              </div>
              <Facts className="instance-detail-facts" rows={rows} />
            </section>
            <section className="instance-detail-section">
              <div className="instance-detail-head"><h3>实例操作</h3></div>
              {(!executing || item.archivedByMigrationTo) && <div className="instance-operation-actions">
                {vacant ? <div className="instance-action-row">
                  <Button small primary icon="add" disabled={locked} onClick={() => start("install", null, null, id, undefined, undefined, options)}>
                    安装 DataEase
                  </Button>
                  <Button small icon="database" disabled={locked} onClick={() => edit("host-restore", { backupId: "" })}>从备份还原</Button>
                </div> : <>
                  <div className="instance-action-row" role="group" aria-label="日常操作">
                    <Button small icon="database" disabled={locked}
                      onClick={() => start("backup", item, undefined, undefined, undefined, undefined, options)}>创建备份</Button>
                    <Button small icon="shield" disabled={locked}
                      onClick={() => start("check", item, undefined, undefined, undefined, undefined, options)}>健康检查</Button>
                    <Button small icon="clock" disabled={locked} onClick={() => edit("automation")}>管理自动任务</Button>
                    {item.canOpenAccess && (
                      <Button small icon="unlock" disabled={locked} onClick={() => edit("release")}>开放业务访问</Button>
                    )}
                  </div>
                  <div className="instance-action-row instance-maintenance-actions" role="group" aria-label="版本与服务">
                    <Button small icon="upgrade" disabled={locked}
                      onClick={() => start("upgrade", item, undefined, undefined, undefined, undefined, options)}>离线升级</Button>
                    {item.archivedByMigrationTo ? (
                      <Button small icon="arrow" onClick={() => showInstance(item.archivedByMigrationTo)}>查看迁入的 V3</Button>
                    ) : /^v?2\./.test(item.version) && (
                      <Button small icon="migrate" disabled={locked}
                        onClick={() => start("migrate", item, undefined, undefined, undefined, undefined, options)}>迁移到 V3</Button>
                    )}
                    <Button small icon="power" disabled={locked}
                      onClick={() => edit("service", { action: item.status === "已停止" ? "start" : "stop" })}>
                      {item.status === "已停止" ? "启动实例" : "停止实例"}
                    </Button>
                    {item.deploymentAdapter === "dataease-enterprise-vendor-compose-v1" && (
                      <Button className="button small instance-uninstall-action" icon="trash"
                        disabled={state.tasks.some((task) => task.kind === "uninstall"
                          && openTaskStates.has(task.state) && taskForInstance(task, id))
                          || (locked && deploymentByTarget.get(id)?.status !== "待验收")}
                        onClick={() => edit("uninstall")}
                      >卸载 DataEase</Button>
                    )}
                  </div>
                </>}
              </div>}
              {instanceTask && (
                <InstanceTaskProgress task={instanceTask} name={instanceTask.title || operationName(instanceTask.kind)}
                  onOpen={(stageIndex) => setDialog({ type: "task", id: instanceTask.id, stageIndex, originInstanceId: id })} />
              )}
            </section>
          </Modal>
        );
      })()}
      {dialog?.type === "host-restore" && (() => {
        const host = state.instances.find((item) => item.id === dialog.id && uninstalled(item));
        if (!host) return null;
        const eligible = state.backups.filter((backup) => wholeRecoveryPoint(backup, host));
        const backup = eligible.find((item) => item.id === dialog.backupId);
        return (
          <Modal title={`从备份还原 · ${host.name}`}
            onClose={() => setDialog({ type: "instance", id: host.id })}
            actions={<>
              <Button onClick={() => setDialog({ type: "instance", id: host.id })}>返回</Button>
              <Button primary disabled={!backup || active(host)}
                onClick={() => start("restore", null, backup, host.id, undefined, undefined, { returnInstanceId: host.id })}>下一步</Button>
            </>}>
            {eligible.length ? (
              <Field label="整体备份">
                <Select aria-label="整体备份" value={dialog.backupId}
                  onChange={(event) => setDialog({ ...dialog, backupId: event.target.value })}>
                  <option value="">请选择已校验整体备份</option>
                  {eligible.map((item) => <option key={item.id} value={item.id}>
                    {item.name} · {item.version} · {date(item.recoveryPoint || item.createdAt)}
                  </option>)}
                </Select>
              </Field>
            ) : <Notice>暂无与此服务器匹配的整体备份。企业版异机重建需要暂停写入的整体备份和另一台 SSH 服务器。</Notice>}
          </Modal>
        );
      })()}
      {selectedBackup && (
        <Modal
          title={selectedBackup.name}
          onClose={() => setDialog(null)}
          actions={
            <>
              <Button onClick={() => download(selectedBackup.id)}>
                导出备份
              </Button>
              <Button
                onClick={() =>
                  invoke(async () => {
                    await post(`/backups/${selectedBackup.id}/verify`);
                    notify("备份校验已完成");
                  })
                }
              >
                重新校验
              </Button>
              {canRestoreBackup(selectedBackup, state.instances) && <Button
                primary
                onClick={() => start("restore", null, selectedBackup)}
              >
                还原备份
              </Button>}
            </>
          }
        >
          <Facts
            rows={[
              ["来源实例", selectedBackup.instanceName],
              ["范围", selectedBackup.scope],
              [
                "版本 / 数据库",
                `${selectedBackup.version} / ${selectedBackup.dbType}`,
              ],
              ["校验状态", selectedBackup.status],
              [
                "恢复点",
                date(selectedBackup.recoveryPoint || selectedBackup.createdAt),
              ],
              ["SHA-256", selectedBackup.sha256],
              ["副本位置", backupLocation(selectedBackup)],
              [
                "异机保护",
                selectedBackup.offsite === true
                  ? "已确认异机保存"
                  : selectedBackup.offsite === false
                    ? "同机保存，不能抵御原服务器故障"
                    : "尚未确认是否与来源实例位于不同物理服务器",
              ],
              ["恢复演练", selectedBackup.lastDrillAt ? `已通过 · ${date(selectedBackup.lastDrillAt)}` : "未演练"],
            ]}
          />
          {selectedBackup.restoreMode === "manual" && (
            <Notice>此备份可校验和导出。要恢复原厂企业版部署，请暂停应用写入后创建新的整体备份。</Notice>
          )}
          {selectedBackup.restoreMode === "original-enterprise" && !canRestoreBackup(selectedBackup, state.instances) && (
            <Notice>仅可还原到创建此备份的原部署。当前列表中没有匹配的恢复目标。</Notice>
          )}
          <h3>备份覆盖清单</h3>
          <CheckResults
            checks={(selectedBackup.coverage || []).map((item) => ({
              ...item,
              title: item.name || item.title,
              detail: item.detail || item.location,
              status:
                item.mode === "included" || item.included
                  ? "passed"
                  : item.required
                    ? "blocked"
                    : "warning",
            }))}
          />
        </Modal>
      )}
      {dialog?.type === "export-backup" && (
        <ExportBackupDialog
          backup={state.backups.find((item) => item.id === dialog.id)}
          onClose={() => setDialog({ type: "backup", id: dialog.id })}
          onSaved={() => {
            setDialog(null);
            notify("备份包已生成并开始下载，请将文件和密码分开妥善保管。");
          }}
        />
      )}
      {selectedMedia && (
        <MediaDetail
          item={selectedMedia}
          onClose={() => setDialog(null)}
          onSaved={saved}
        />
      )}
      {dialog?.type === "health" && (
        <Modal
          title={`检查结果 · ${dialog.item.name}`}
          onClose={() => setDialog(null)}
          actions={
            <>
              <Button
                onClick={() =>
                  exportJson(
                    `健康检查-${dialog.item.name}.json`,
                    dialog.item.healthReport?.checks ||
                      dialog.item.healthReport ||
                      [],
                  )
                }
              >
                导出报告
              </Button>
              <Button primary onClick={() => start("check", dialog.item)}>
                重新检查
              </Button>
            </>
          }
        >
          <CheckResults
            checks={
              dialog.item.healthReport?.checks || dialog.item.healthReport || []
            }
          />
          {!(dialog.item.healthReport?.checks || dialog.item.healthReport)
            ?.length && <Empty text="尚无检查结果" />}
        </Modal>
      )}
      {dialog?.type === "health-plans" && (
        <Modal
          title="定时巡检计划"
          onClose={() => setDialog(null)}
          actions={
            <>
              <Button onClick={() => setDialog(null)}>关闭</Button>
              <Button
                primary
                onClick={() =>
                  start("check", null, null, null, null, null, {
                    scheduled: true,
                  })
                }
              >
                新增巡检计划
              </Button>
            </>
          }
        >
          {state.healthPlans.length ? (
            state.healthPlans.map((plan) => (
              <div className="live-plan-row" key={plan.id}>
                <div>
                  <strong>{plan.instanceName}</strong>
                  <p>
                    每天 {plan.time} · {plan.enabled ? "已启用" : "已暂停"}
                  </p>
                  {plan.lastError && (
                    <Notice tone="red">{plan.lastError}</Notice>
                  )}
                </div>
                <Button
                  small
                  onClick={() =>
                    invoke(() =>
                      put(`/plans/${plan.id}`, {
                        ...plan,
                        enabled: !plan.enabled,
                      }),
                    )
                  }
                >
                  {plan.enabled ? "暂停" : "启用"}
                </Button>
                <Button
                  small
                  onClick={() => setDialog({ type: "plan", item: plan })}
                >
                  修改
                </Button>
                <Button
                  small
                  onClick={() => setDialog({ type: "delete-plan", item: plan })}
                >
                  删除
                </Button>
              </div>
            ))
          ) : (
            <Empty text="暂无巡检计划" />
          )}
        </Modal>
      )}
      {dialog?.type === "environment" && (
        <EnvironmentDialog
          instance={dialog.item}
          onClose={() => dialog.returnInstanceId ? showInstance(dialog.returnInstanceId) : setDialog(null)}
          onSaved={dialog.returnInstanceId ? () => saveInstanceSettings(dialog.returnInstanceId) : saved}
        />
      )}
      {dialog?.type === "plan" && (
        <PlanDialog
          plan={dialog.item}
          onClose={() => setDialog(null)}
          onSaved={saved}
        />
      )}
      {dialog?.type === "service" && (
        <ConfirmDialog
          title={dialog.action === "start" ? "启动实例" : "停止实例"}
          onClose={() => showInstance(dialog.item.id)}
          command={{ kind: "service", sourceId: dialog.item.id, action: dialog.action }}
          action={(task) => complete(task, undefined, dialog)}
        >
          <Facts
            rows={[
              ["实例", dialog.item.name],
              ["服务器", dialog.item.host],
              ["应用服务", dialog.item.appService || "按 Compose 识别"],
            ]}
          />
          <Notice tone="amber">
            {dialog.action === "stop"
              ? "停止 DataEase 应用，MySQL 保持运行。"
              : "平台按已保存的访问策略启动；隔离实例继续保持隔离。"}
          </Notice>
        </ConfirmDialog>
      )}
      {dialog?.type === "release" && (
        <ConfirmDialog
          title="开放业务访问"
          confirmName={dialog.item.name}
          confirmLabel="确认开放"
          onClose={() => dialog.askToViewTask ? setDialog(null) : showInstance(dialog.item.id)}
          action={async (confirmation) => {
            const task = await post(`/instances/${dialog.item.id}/open-access`, {
              confirmTargetName: confirmation,
              instanceRevision: dialog.item.revision,
            });
            complete(task, undefined, dialog);
          }}
        >
          <Facts rows={[
            ["实例", dialog.item.name],
            ["服务器", dialog.item.host],
            ["业务端口", dialog.item.port],
          ]} />
          <Notice tone="amber">
            平台会先复核运行条件，通过后按部署配置开放业务入口。切换期间会重建相关容器，自动任务保持暂停。
          </Notice>
        </ConfirmDialog>
      )}
      {dialog?.type === "reconcile" && (
        <ConfirmDialog
          title="确认现场核查结果"
          confirmName={dialog.item.targetName}
          confirmCheckLabel="已在服务器现场核对并终止本任务遗留的进程及数据库语句"
          onClose={() => setDialog({ type: "task", id: dialog.item.id, originInstanceId: dialog.originInstanceId })}
          action={async () => {
            await post(`/tasks/${dialog.item.id}/reconcile`, {
              confirmTargetName: dialog.item.targetName,
              remoteProcessesStopped: true,
            });
            await refresh();
            setDialog({ type: "task", id: dialog.item.id, originInstanceId: dialog.originInstanceId });
          }}
        >
          <Notice tone="amber">
            请先在服务器核对本次任务，再提交确认。平台核验相关应用均已停止后解除实例锁。
          </Notice>
        </ConfirmDialog>
      )}
      {dialog?.type === "abort" && (
        <ConfirmDialog
          title={dialog.item.v2ComponentUpgrade ? "回退升级" : "终止验收并停止目标"}
          confirmName={dialog.item.targetName}
          onClose={() => setDialog({ type: "task", id: dialog.item.id, originInstanceId: dialog.originInstanceId })}
          action={async () => {
            await post(`/tasks/${dialog.item.id}/abort`, {
              confirmTargetName: dialog.item.targetName,
            });
            await refresh();
            setDialog({ type: "task", id: dialog.item.id, originInstanceId: dialog.originInstanceId });
          }}
        >
          <Notice tone="amber">
            {dialog.item.v2ComponentUpgrade
              ? "结束本次验收，恢复旧版 DataEase、内置 MySQL 和升级前的整体备份。回退完成后保持隔离运行。"
              : `停止目标 ${dialog.item.targetName}，结束本次验收并保留保护备份。随后可使用目标保护备份还原。`}
          </Notice>
        </ConfirmDialog>
      )}
      {dialog?.type === "delete-media" && (
        <ConfirmDialog
          title="移除库中介质"
          onClose={() => setDialog(null)}
          action={async () => {
            await api(`/packages/${dialog.item.id}`, { method: "DELETE" });
            await saved();
          }}
        >
          <Notice tone="amber">
            移除 {dialog.item.name || dialog.item.fileName}
            。不会卸载已安装实例；活动任务引用的介质不能移除。
          </Notice>
        </ConfirmDialog>
      )}
      {dialog?.type === "delete-plan" && (
        <ConfirmDialog
          title="删除计划"
          onClose={() => setDialog(null)}
          action={async () => {
            await api(`/plans/${dialog.item.id}`, { method: "DELETE" });
            await saved();
          }}
        >
          <Notice tone="amber">删除定时计划，保留已有备份与任务记录。</Notice>
        </ConfirmDialog>
      )}
    </>
  );
}
function ConfirmDialog({
  title,
  children,
  onClose,
  action,
  command,
  confirmName,
  confirmCheckLabel,
  confirmLabel = "确认",
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [confirmation, setConfirmation] = useState(""),
    [checked, setChecked] = useState(false);
  const { progress, submit } = useTaskSubmission();
  return (
    <Modal
      title={title}
      onClose={onClose}
      busy={busy}
      busyLabel={command && progress ? null : undefined}
      wide={false}
      actions={
        <>
          <Button disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button
            primary
            disabled={
              busy ||
              (!!confirmName && confirmation !== confirmName) ||
              (!!confirmCheckLabel && !checked)
            }
            onClick={async () => {
              setBusy(true);
              setError("");
              try {
                if (command) {
                  const result = await submit(command);
                  if (result?.task) await action(result.task);
                } else await action(confirmation);
              } catch (error) {
                setError(error.message);
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? "正在处理…" : confirmLabel}
          </Button>
        </>
      }
    >
      {children}
      {confirmName && (
        <Field
          label={`输入“${confirmName}”确认目标`}
          value={confirmation}
          onChange={(event) => setConfirmation(event.target.value)}
        />
      )}{" "}
      {confirmCheckLabel && (
        <Check checked={checked} onChange={setChecked}>
          {confirmCheckLabel}
        </Check>
      )}
      {progress && <SubmissionProgress progress={progress} />}
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}
function EnvironmentDialog({ instance, onClose, onSaved }) {
  const [value, setValue] = useState({
      ...instance.environmentConfig,
      engineMode: instance.environmentConfig?.engineMode || "shared",
    }),
    [error, setError] = useState("");
  return (
    <Modal
      title={`环境组成 · ${instance.name}`}
      onClose={onClose}
      actions={
        <>
          <Button onClick={onClose}>取消</Button>
          <Button
            primary
            onClick={async () => {
              try {
                await put(`/instances/${instance.id}/environment`, {
                  environmentConfig: value,
                });
                await onSaved();
              } catch (error) {
                setError(error.message);
              }
            }}
          >
            保存环境组成
          </Button>
        </>
      }
    >
      <Field label="部署方式">
        <Select
          aria-label="部署方式"
          value={value.deployment || "single-compose"}
          onChange={(event) =>
            setValue({ ...value, deployment: event.target.value })
          }
        >
          <option value="single-compose">单机 Docker Compose</option>
          <option value="cluster">多节点 / 集群（仅识别）</option>
        </Select>
      </Field>
      <Field label="数据引擎">
        <Select
          aria-label="数据引擎"
          value={value.engineMode}
          onChange={(event) =>
            setValue({ ...value, engineMode: event.target.value })
          }
        >
          <option value="shared">与元数据库共库</option>
          <option value="external">独立数据引擎</option>
        </Select>
      </Field>
      {value.engineMode === "external" && (
        <>
          <Field
            label="引擎地址"
            value={value.engineHost || ""}
            onChange={(event) =>
              setValue({ ...value, engineHost: event.target.value })
            }
          />
          <Field
            label="外部备份记录编号"
            value={value.engineRef || ""}
            onChange={(event) =>
              setValue({ ...value, engineRef: event.target.value })
            }
          />
        </>
      )}
      <Notice>
        外部引擎未纳入一致性保护时，自动整体备份、迁移和环境重建会阻断。
      </Notice>
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}
function PlanDialog({ plan, onClose, onSaved }) {
  const [value, setValue] = useState(plan),
    [error, setError] = useState("");
  return (
    <Modal
      title={plan.kind === "check" ? "修改巡检计划" : "修改备份计划"}
      onClose={onClose}
      wide={false}
      actions={
        <>
          <Button onClick={onClose}>取消</Button>
          <Button
            primary
            onClick={async () => {
              try {
                await put(`/plans/${plan.id}`, value);
                await onSaved();
              } catch (error) {
                setError(error.message);
              }
            }}
          >
            保存计划
          </Button>
        </>
      }
    >
      <Field label="每天执行时间">
        <TimeSelect
          label="计划执行时间"
          value={value.time || "02:00"}
          onChange={(event) => setValue({ ...value, time: event.target.value })}
        />
      </Field>
      {plan.kind !== "check" && (
        <Field
          label="保留最近成功备份数"
          type="number"
          min="1"
          max="365"
          value={value.retention || 7}
          onChange={(event) =>
            setValue({ ...value, retention: Number(event.target.value) })
          }
        />
      )}
      <Check
        checked={value.enabled}
        onChange={(enabled) => setValue({ ...value, enabled })}
      >
        启用计划
      </Check>
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}
function MediaDetail({ item, onClose, onSaved }) {
  const [manifest, setManifest] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <Modal
      title="软件包详情"
      onClose={onClose}
      busy={busy}
      busyLabel="正在校验软件包"
      actions={
        <>
          <Button disabled={busy} onClick={onClose}>关闭</Button>
          {item.status === "待校验" && (
            <Button disabled={busy} onClick={async () => {
              setBusy(true);
              setError("");
              try {
                await post(`/packages/${item.id}/verify`);
                await onSaved();
              } catch (error) {
                setError(error.message);
              } finally {
                setBusy(false);
              }
            }}>校验软件包</Button>
          )}
          {item.status !== "已校验" && (
            <Button
              primary
              disabled={busy || !manifest}
              onClick={async () => {
                setBusy(true);
                try {
                  await put(
                    `/packages/${item.id}/manifest`,
                    JSON.parse(manifest),
                  );
                  await onSaved();
                } catch (error) {
                  setError(error.message);
                } finally {
                  setBusy(false);
                }
              }}
            >
              保存并校验清单
            </Button>
          )}
        </>
      }
    >
      <Facts
        rows={[
          ...(item.name && item.name !== item.fileName ? [["软件包名称", item.name]] : []),
          ["文件名", item.fileName],
          ["版本", item.version],
          ["CPU 架构", item.arch],
          ["适用数据库", (item.dbTypes || []).join("、")],
          ...(item.minimumUpgradeSource ? [["最低升级来源", item.minimumUpgradeSource]] : []),
          ["SHA-256", item.sha256],
          ["状态", item.status],
        ]}
      />
      {item.validationMessage && (
        <Notice tone={item.status === "已校验" ? "blue" : "amber"}>
          {item.validationMessage}
        </Notice>
      )}
      {item.status !== "已校验" && (
        <Field label="介质能力清单（JSON）">
          <textarea
            rows="12"
            value={manifest}
            onChange={(event) => setManifest(event.target.value)}
            placeholder="由介质提供方声明版本、架构、用途、离线入口和逐文件 SHA-256。"
          />
        </Field>
      )}
      {item.status === "已校验" && (
        <details>
          <summary>查看校验清单</summary>
          <pre className="live-json">
            {JSON.stringify(
              item.manifest || {
                version: item.version,
                arch: item.arch,
                dbTypes: item.dbTypes,
                purposes: item.purposes,
              },
              null,
              2,
            )}
          </pre>
        </details>
      )}
      {error && <Notice tone="red">{error}</Notice>}
    </Modal>
  );
}
