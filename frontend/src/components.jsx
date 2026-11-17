import React, { useId, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Icon, Button, Select, useDialogFocus } from "./ui";
import { readPrivateKey } from "./api";
import TaskStageList from "./TaskStageList";
export { Icon, Button, Select, TimeSelect } from "./ui";
export const tone = (value) =>
  ["失败", "异常", "校验失败", "blocked", "error", "fail"].includes(value)
    ? "red"
    : ["待验收", "关注", "待校验", "状态待确认", "warning"].includes(value)
      ? "amber"
      : ["执行中", "排队中", "终止中", "核查中", "running"].includes(value)
        ? "blue"
        : ["已停止", "未安装", "未知", "未检查", "待检查", "interrupted"].includes(value)
          ? "gray"
          : "green";
export const backupLocation = (backup) =>
  backup.storageHost === "operations-platform"
    ? "运维平台备份库"
    : backup.storageLabel || backup.destination || "副本位置未记录";

export const taskInProgress = (task) =>
  ["排队中", "执行中", "终止中", "核查中"].includes(task.status);

export function taskActivity(task) {
  if (task.state === "queued") return { title: "等待执行", since: task.createdAt, waiting: true };
  if (task.state === "aborting") return {
    title: task.currentStage || "停止目标并结束验收", since: task.activityStartedAt,
  };
  if (task.state === "reconciling") return { title: "核查目标运行状态", since: task.reconciliation?.confirmedAt };
  const stage = task.stages?.find((item) => item.title === task.currentStage);
  if (task.currentCheck) return {
    title: `检查 ${task.currentCheck}`,
    since: task.currentCheckStartedAt || stage?.startedAt,
  };
  if (stage?.status === "running") return {
    title: stage.title,
    since: stage.startedAt,
    detail: task.stageActivity || stage.detail,
  };
  if (stage?.status === "passed") return { title: `已完成 ${stage.title}` };
  return { title: "准备执行", since: task.startedAt || task.createdAt };
}

export function Elapsed({ since, label = "已用" }) {
  const [now, setNow] = useState(Date.now);
  const start = Date.parse(since);
  useEffect(() => {
    if (!Number.isFinite(start)) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [start]);
  if (!Number.isFinite(start)) return null;
  const seconds = Math.max(0, Math.floor((now - start) / 1000));
  const minutes = Math.floor(seconds / 60);
  const clock = minutes >= 60
    ? [Math.floor(minutes / 60), minutes % 60, seconds % 60]
    : [minutes, seconds % 60];
  return (
    <span className="activity-elapsed" aria-live="off">
      {label} {clock.map((part) => String(part).padStart(2, "0")).join(":")}
    </span>
  );
}

export function SubmissionProgress({ progress, onAction }) {
  const region = useRef(null);
  const [expanded, setExpanded] = useState(null);
  const state = progress?.state;
  const active = ["requesting", "queued", "running", "submitting", "reconnecting"].includes(state);
  useLayoutEffect(() => {
    const panel = region.current;
    const pane = panel?.closest(".wf-content, .ap-dialog-body");
    if (!pane) return;
    const reveal = () => {
      const bottom = panel.getBoundingClientRect().bottom - pane.getBoundingClientRect().bottom;
      if (bottom > 0) pane.scrollTop += bottom + 8;
    };
    reveal();
    if (!active) return;
    const observer = new ResizeObserver(reveal);
    observer.observe(panel);
    return () => observer.disconnect();
  }, [active]);
  if (!progress) return null;
  const { current, submission, startedAt } = progress;
  const checks = submission?.checks ?? progress.checks ?? [];
  const stages = checks.map((check) => ({
    title: check.title || check.name,
    status: check.status === "blocked" ? "failed" : check.status,
    statusLabel: check.status === "blocked" ? "阻断" : check.status === "passed" ? "通过" : undefined,
    detail: check.detail || check.message || check.remediation?.hint,
    remediation: check.remediation,
  }));
  if (state === "running" && current) stages.push({ title: current, status: "running" });
  const firstFailure = stages.findIndex((stage) => stage.status === "failed");
  const selected = active ? -1 : expanded && expanded.id === progress.id
    ? expanded.index : firstFailure;
  const label = {
    requesting: "正在提交请求",
    queued: "等待检查",
    running: "正在检查执行条件",
    submitting: "检查通过，正在创建任务",
    reconnecting: "正在重新获取任务状态",
    completed: submission?.passed ? "任务已创建" : "检查未通过，操作未执行",
    failed: "提交未完成",
  }[state];
  return (
    <section ref={region} className="submission-progress" aria-label="执行条件检查">
      <div className="wf-check-progress">
        <strong role="status">
          {active && (!current || state !== "running") && <span className="task-spinner" aria-hidden="true" />}
          {label}
        </strong>
        {active && <Elapsed since={startedAt} />}
      </div>
      {stages.length > 0 && (
        <TaskStageList stages={stages} label="检查步骤"
          focusedIndex={active ? stages.length - 1 : selected}
          selectedIndex={selected}
          canSelect={(stage) => !active && Boolean(stage.detail || stage.remediation?.label)}
          onSelect={(index) => setExpanded({ id: progress.id, index: selected === index ? -1 : index })}
          renderDetail={(stage) => (
            <div className="submission-step-detail">
              {stage.detail && <p>{stage.detail}</p>}
              {stage.remediation?.hint && stage.remediation.hint !== stage.detail && <p>{stage.remediation.hint}</p>}
              {onAction && stage.remediation?.label && stage.remediation.type !== "manual" && (
                <Button small onClick={() => onAction(stage.remediation)}>{stage.remediation.label}</Button>
              )}
            </div>
          )} />
      )}
    </section>
  );
}

function DialogActivity({ children }) {
  const [started] = useState(() => new Date().toISOString());
  return (
    <span className="dialog-activity">
      <span role="status"><span className="task-spinner" aria-hidden="true" />{children}</span>
      <Elapsed since={started} />
    </span>
  );
}

export function OperationFooter({ information, busyLabel, contextActions, children }) {
  return (
    <footer className="operation-footer">
      {(busyLabel || information || contextActions) && (
        <div className="operation-footer-context">
          {busyLabel ? <DialogActivity>{busyLabel}</DialogActivity> : information}
          {contextActions}
        </div>
      )}
      <div className="operation-footer-actions">{children}</div>
    </footer>
  );
}

export function Field({ label, children, hint, ...props }) {
  const hintId = useId();
  const accessibility = {
    "aria-label": label,
    "aria-describedby": hint ? hintId : undefined,
  };
  const control =
    children &&
    React.isValidElement(children) &&
    ["input", "textarea"].includes(children.type)
      ? React.cloneElement(children, accessibility)
      : children || <input {...props} {...accessibility} />;
  return (
    <label className="field">
      <span>{label}</span>
      {control}
      {hint && <small id={hintId}>{hint}</small>}
    </label>
  );
}
export function Check({ children, checked, onChange, disabled }) {
  return (
    <label className="lc-check">
      <input
        type="checkbox"
        checked={!!checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span>
        <strong>{children}</strong>
      </span>
    </label>
  );
}
export function Notice({ children, tone = "blue" }) {
  return (
    <div
      className={`notice ${tone}`}
      role={tone === "red" ? "alert" : undefined}
    >
      <Icon name={tone === "red" ? "warning" : "info"} size={16} />
      <span>{children}</span>
    </div>
  );
}

function revealFocusedControl({ currentTarget: pane, target: control }) {
  if (!control.matches("input, textarea, button, summary, a[href]")) return;
  const bounds = control.getBoundingClientRect();
  const top = pane.getBoundingClientRect().top + 8;
  const bottom = top + pane.clientHeight - 16;
  if (bounds.height > bottom - top) return;
  if (bounds.top < top) pane.scrollTop += bounds.top - top;
  else if (bounds.bottom > bottom) pane.scrollTop += bounds.bottom - bottom;
}

export function Modal({
  title,
  children,
  onClose,
  actions,
  footer,
  wide = true,
  busy = false,
  busyLabel = "正在处理",
  className = "",
}) {
  const close = () => {
    if (!busy) onClose();
  };
  const ref = useDialogFocus({ onClose: close });
  const titleId = useId();
  return (
    <div
      className="ap-overlay live-dialog-overlay"
      onMouseDown={(event) => event.target === event.currentTarget && close()}
    >
      <section
        className={`ap-dialog ${wide ? "wide" : ""} ${className}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        ref={ref}
      >
        <header className="ap-dialog-head">
          <h2 id={titleId}>{title}</h2>
          <button
            className="icon-button"
            aria-label="关闭弹窗"
            disabled={busy}
            onClick={close}
          >
            <Icon name="close" />
          </button>
        </header>
        <div className="ap-dialog-body" onFocus={revealFocusedControl}>
          <fieldset className="dialog-fields" disabled={busy}>
            {children}
          </fieldset>
        </div>
        {footer ?? <footer className="ap-dialog-footer">
          {busy && busyLabel && <DialogActivity>{busyLabel}</DialogActivity>}
          {actions || <Button onClick={onClose}>关闭</Button>}
        </footer>}
      </section>
    </div>
  );
}
export function WizardFrame({
  title,
  identity,
  steps,
  step,
  children,
  onClose,
  onBack,
  onNext,
  nextLabel,
  nextDisabled,
  busy,
  busyLabel = "正在处理",
  error,
}) {
  const close = () => {
    if (!busy) onClose();
  };
  const ref = useDialogFocus({ onClose: close });
  const titleId = useId();
  const content = useRef(null);
  useEffect(() => {
    content.current?.scrollTo(0, 0);
    const heading = content.current?.querySelector("h3");
    if (heading) {
      heading.tabIndex = -1;
      heading.focus({ preventScroll: true });
    }
  }, [step]);
  useEffect(() => {
    if (error)
      content.current
        ?.querySelector('[role="alert"]')
        ?.scrollIntoView({ block: "nearest" });
  }, [error]);
  return (
    <div className="wf-overlay">
      <section
        className="wf-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        ref={ref}
        tabIndex={-1}
      >
        <header className="wf-header">
          <h2 id={titleId}>{title}</h2>
          <button
            className="wf-close"
            aria-label="关闭操作向导"
            disabled={busy}
            onClick={close}
          >
            <Icon name="close" size={20} />
          </button>
        </header>
        <div className="wf-object-bar">
          <Icon name="server" size={15} />
          <strong title={identity || "请选择操作目标"}>
            {identity || "请选择操作目标"}
          </strong>
        </div>
        <div className="wf-body">
          {steps.length > 1 && <aside className="wf-steps">
            {steps.map((label, index) => (
              <div
                key={label}
                className={`wf-step ${index === step ? "active" : ""} ${index < step ? "done" : ""}`}
              >
                <span>
                  {index < step ? <Icon name="check" size={14} /> : index + 1}
                </span>
                <div>
                  <strong>{label}</strong>
                </div>
              </div>
            ))}
          </aside>}
          <div className="wf-content" ref={content} onFocus={revealFocusedControl}>
            <fieldset className="dialog-fields" disabled={busy}>
              {children}
            </fieldset>
            {error && <Notice tone="red">{error}</Notice>}
          </div>
        </div>
        <OperationFooter busyLabel={busy && busyLabel}
          information={!busy && steps.length > 1 ? `第 ${step + 1} 步，共 ${steps.length} 步` : null}>
          <Button disabled={busy} onClick={step ? onBack : onClose}>
            {step ? "上一步" : "取消"}
          </Button>
          <Button primary disabled={busy || nextDisabled} onClick={onNext}>
            {nextLabel || "下一步"}
          </Button>
        </OperationFooter>
      </section>
    </div>
  );
}
export function CredentialFields({
  value,
  onChange,
  local = false,
  saved = false,
}) {
  const change = (key, item) => onChange({ ...value, [key]: item });
  const [error, setError] = useState("");
  return (
    <>
      {!local && (
        <>
          <div className="form-grid">
            <Field
              label="SSH 用户名"
              value={value.sshUser || ""}
              onChange={(event) => change("sshUser", event.target.value)}
            />
            <Field
              label="SSH 端口"
              type="number"
              value={value.sshPort || 22}
              onChange={(event) => change("sshPort", event.target.value)}
            />
          </div>
          <Field label="SSH 认证方式">
            <Select
              aria-label="SSH 认证方式"
              value={value.sshAuth || "password"}
              onChange={(event) =>
                onChange({
                  ...value,
                  sshAuth: event.target.value,
                  sshPassword: "",
                  privateKey: "",
                  keyName: "",
                  passphrase: "",
                })
              }
            >
              <option value="password">密码认证</option>
              <option value="key">私钥认证</option>
            </Select>
          </Field>
          {value.sshAuth === "key" ? (
            <>
              <Field label="SSH 私钥文件">
                <span className="file-picker">
                  <Icon name="upload" size={16} />
                  <span>
                    {value.keyName ||
                      (saved
                        ? "留空复用已保存私钥"
                        : "选择 PEM / OpenSSH 私钥")}
                  </span>
                  <input
                    type="file"
                    aria-label="SSH 私钥文件"
                    onChange={async (event) => {
                      try {
                        const file = event.target.files?.[0];
                        onChange({
                          ...value,
                          privateKey: await readPrivateKey(file),
                          keyName: file?.name || "",
                        });
                        setError("");
                      } catch (error) {
                        setError(error.message || "无法读取私钥文件");
                      }
                    }}
                  />
                </span>
              </Field>
              <Field
                label="私钥口令"
                type="password"
                value={value.passphrase || ""}
                onChange={(event) => change("passphrase", event.target.value)}
                autoComplete="off"
              />
            </>
          ) : (
            <Field
              label="SSH 密码"
              type="password"
              autoComplete="off"
              value={value.sshPassword || ""}
              onChange={(event) => change("sshPassword", event.target.value)}
              placeholder={
                saved ? "留空复用已保存密码" : "请输入服务器登录密码"
              }
            />
          )}
          <Field
            label="SSH 主机指纹"
            value={value.hostKey || ""}
            onChange={(event) => change("hostKey", event.target.value)}
            hint={value.sshAuth === "key" ? "选填。留空时记录首次成功连接的主机指纹，后续自动核对。" : "首次使用密码连接时请核对主机指纹。"}
          />
        </>
      )}
      {error && <Notice tone="red">{error}</Notice>}
    </>
  );
}
export function DatabaseFields({
  value,
  onChange,
  types = ["MySQL"],
  supportedTypes,
  password = true,
  fixedType = false,
}) {
  const set = (key, item) => onChange({ ...value, [key]: item });
  return (
    <div className="form-grid">
      {fixedType ? (
        <Field label="数据库类型" value="MySQL" readOnly />
      ) : (
        <Field label="数据库类型">
          <Select
            aria-label="数据库类型"
            value={value.dbType || "MySQL"}
            onChange={(event) => set("dbType", event.target.value)}
          >
            {types.map((type) => (
              <option
                key={type}
                value={type}
                disabled={supportedTypes && !supportedTypes.includes(type)}
              >
                {type}
                {supportedTypes && !supportedTypes.includes(type)
                  ? "（暂不支持自动部署）"
                  : ""}
              </option>
            ))}
          </Select>
        </Field>
      )}
      <Field
        label="数据库名称"
        value={value.dbName || ""}
        onChange={(event) => set("dbName", event.target.value)}
      />
      <Field
        label="数据库 IP / 主机名"
        value={value.dbHost || ""}
        onChange={(event) => set("dbHost", event.target.value)}
      />
      <Field
        label="数据库端口"
        type="number"
        value={value.dbPort || 3306}
        onChange={(event) => set("dbPort", event.target.value)}
      />
      <Field
        label="数据库用户名"
        value={value.dbUser || ""}
        onChange={(event) => set("dbUser", event.target.value)}
      />
      {password && (
        <Field
          label="数据库密码"
          type="password"
          autoComplete="off"
          value={value.dbPassword || ""}
          onChange={(event) => set("dbPassword", event.target.value)}
        />
      )}
    </div>
  );
}
export function Endpoint({ instance, label, onManage }) {
  const uninstalled = instance?.status === "未安装";
  return (
    <section className="migration-connection-card">
      <div className="migration-connection-heading">
        <strong>
          {label} · {instance?.name}
        </strong>
        <span className="live-reuse">复用已接入连接</span>
      </div>
      <dl>
        <dt>服务器</dt>
        <dd>{instance?.host}</dd>
        {uninstalled ? <>
          <dt>架构</dt>
          <dd>{instance.arch}</dd>
          <dt>状态</dt>
          <dd>{instance.status}</dd>
        </> : <>
          <dt>版本</dt>
          <dd>{instance?.version}</dd>
          <dt>安装目录</dt>
          <dd>{instance?.path}</dd>
          <dt>元数据库</dt>
          <dd>
            {instance?.dbType} · {instance?.dbHost}:{instance?.dbPort} /{" "}
            {instance?.dbName}
          </dd>
        </>}
      </dl>
      {onManage && (
        <div className="migration-connection-footer">
          <span>预检查验证连接与执行权限</span>
          <button
            className="link"
            aria-label={`维护${label}连接`}
            onClick={() => onManage(instance)}
          >
            维护连接
          </button>
        </div>
      )}
    </section>
  );
}
export function Facts({ rows, className = "" }) {
  return (
    <dl className={`detail-list ${className}`}>
      {rows.map(([label, value]) => (
        <React.Fragment key={label}>
          <dt>{label}</dt>
          <dd>{value ?? "—"}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
}
export function CheckResults({
  checks = [],
  onAction,
  prioritize = false,
  actionTypes,
}) {
  const [showAll, setShowAll] = useState(false);
  const passed = (check) =>
    ["passed", "pass", "正常", "通过", "success", "ok"].includes(
      check.status || check.result,
    ) || check.ok === true;
  const problems = checks.filter((check) => !passed(check));
  const blocked = problems.filter(
    (check) =>
      !["warning", "unknown", "pending", "running", "queued"].includes(
        check.status || check.result,
      ),
  );
  const visible = prioritize && !showAll ? problems : checks;
  return (
    <div className="live-checks">
      {prioritize && checks.length > 0 && (
        <div className="check-summary">
          <strong>
            {blocked.length
              ? `${blocked.length} 项需处理`
              : problems.length
                ? `${problems.length} 项需关注`
                : `全部 ${checks.length} 项通过`}
          </strong>
          <span>共 {checks.length} 项检查</span>
          <Button small onClick={() => setShowAll(!showAll)}>
            {problems.length
              ? showAll
                ? "仅看需处理项"
                : "查看全部检查"
              : showAll
                ? "收起检查明细"
                : "查看检查明细"}
          </Button>
        </div>
      )}
      {visible.map((check, index) => {
        const status = check.status || check.result;
        const success =
          ["passed", "pass", "正常", "通过", "success", "ok"].includes(
            status,
          ) || check.ok === true;
        const pending = ["pending", "running", "queued"].includes(status);
        return (
          <div
            className="live-check"
            key={`${check.code || check.title || "check"}-${index}`}
          >
            <Icon
              name={success ? "check" : pending ? "clock" : "warning"}
              size={17}
            />
            <div>
              <strong>{check.title || check.name}</strong>
              {(check.detail || check.message || check.remediation?.hint) &&
                <p>{check.detail || check.message || check.remediation?.hint}</p>}
              {check.remediation?.hint && (check.detail || check.message) && (
                <details className="check-technical">
                  <summary>处理建议</summary>
                  <p>{check.remediation.hint}</p>
                </details>
              )}
              {onAction &&
                check.remediation?.label &&
                check.remediation.type !== "manual" &&
                (!actionTypes ||
                  actionTypes.includes(check.remediation.type)) && (
                  <Button small onClick={() => onAction(check.remediation)}>
                    {check.remediation.label}
                  </Button>
                )}
            </div>
            <span
              className={`live-result ${success ? "pass" : pending ? "pending" : ["warning", "unknown"].includes(status) ? "warning" : "fail"}`}
            >
              {success
                ? "通过"
                : status === "running"
                  ? "执行中"
                  : pending
                    ? "未执行"
                    : status === "unknown"
                      ? "状态待确认"
                      : status === "warning"
                        ? "注意"
                        : status === "failed"
                          ? "失败"
                          : "阻断"}
            </span>
          </div>
        );
      })}
    </div>
  );
}
