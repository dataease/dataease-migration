import React, { useEffect, useRef, useState } from "react";
import {
  WizardFrame,
  Field,
  Check,
  Notice,
  Select,
  TimeSelect,
  Button,
  CredentialFields,
  DatabaseFields,
  Endpoint,
  Facts,
  CheckResults,
  backupLocation,
} from "./components";
import { post } from "./api";
import { UploadDialog } from "./Dialogs";
const names = {
  migrate: "V2 → V3 迁移",
  backup: "创建备份",
  restore: "还原备份",
  install: "安装 DataEase",
  upgrade: "离线升级",
  check: "健康检查",
  plugin: "安装插件",
};
const environments = ["生产", "预发布", "测试"];
export default function Workflow({
  flow,
  state,
  onClose,
  onComplete,
  onManageConnection,
  onRefresh,
  onOpenTask,
  onManageEnvironment,
}) {
  const { kind, instance, backup } = flow;
  const sameVersion = (a, b) =>
    String(a || "").replace(/^v/i, "") === String(b || "").replace(/^v/i, "");
  const restoreMismatch = (item) =>
    !sameVersion(item.version, backup?.version)
      ? "版本不匹配"
      : item.dbType !== backup?.dbType
        ? "数据库类型不匹配"
        : item.arch !== backup?.arch
          ? "架构不匹配"
          : "";
  const originalTarget =
    kind === "restore" &&
    state.instances.find(
      (item) => item.id === backup?.instanceId && !restoreMismatch(item),
    );
  const hasRestoreTarget =
    kind === "restore" &&
    state.instances.some((item) => !restoreMismatch(item));
  const [step, setStep] = useState(0),
    [sourceId, setSourceId] = useState(instance?.id || ""),
    [targetId, setTargetId] = useState(
      flow.targetId || originalTarget?.id || "",
    ),
    [targetMode, setTargetMode] = useState(
      kind === "restore" &&
        !flow.targetId &&
        !hasRestoreTarget &&
        backup?.scope === "整体备份"
        ? "manual"
        : "managed",
    ),
    [packageId, setPackageId] = useState(flow.packageId || ""),
    [scope, setScope] = useState("整体备份"),
    [copySyncTaskLogs, setCopyLogs] = useState(false),
    [purpose, setPurpose] = useState(flow.purpose || "disaster"),
    [consistent, setConsistent] = useState("pause"),
    [scheduled, setScheduled] = useState(!!flow.scheduled),
    [time, setTime] = useState("02:00"),
    [retention, setRetention] = useState(7),
    [confirm, setConfirm] = useState(""),
    [precheck, setPrecheck] = useState(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [upload, setUpload] = useState(false);
  const [config, setConfig] = useState({
    name: "",
    environment: "测试",
    host: "",
    connection: "ssh",
    path: "/opt/dataease3.0",
    port: 8100,
    version: "",
    arch: backup?.arch || "x86_64",
    dbType: backup?.dbType || "MySQL",
    dbHost: "",
    dbPort: 3306,
    dbName: backup?.instanceSnapshot?.dbName || "dataease_v3",
    dbUser: "dataease",
    destination: "platform",
    nameBackup: "",
    backupPath: "",
    apiPrefix: "/de2api",
  });
  const [credentials, setCredentials] = useState({
    sshUser: "root",
    sshPort: 22,
    sshAuth: "password",
    sshPassword: "",
    privateKey: "",
    passphrase: "",
    hostKey: "",
    dbPassword: "",
    dataeaseToken: "",
  });
  const epoch = useRef(0);
  const invalidate = () => {
    epoch.current += 1;
    setPrecheck(null);
    setConfirm("");
    setError("");
  };
  const change = (setter, value) => {
    setter(value);
    invalidate();
  };
  const update = (value) => {
    setConfig(value);
    invalidate();
  };
  const source = state.instances.find((item) => item.id === sourceId);
  const target = state.instances.find((item) => item.id === targetId);
  useEffect(() => {
    invalidate();
  }, [source?.id, source?.revision, target?.id, target?.revision]);
  const media = (kind === "plugin" ? state.plugins : state.packages).find(
    (item) => item.id === packageId,
  );
  useEffect(() => {
    invalidate();
  }, [media?.id, media?.revision, media?.sha256]);
  const targetName =
    kind === "migrate" || kind === "restore"
      ? target?.name || config.name
      : kind === "install"
        ? config.name
        : source?.name;
  const command = () => ({
    kind,
    sourceId: sourceId || undefined,
    targetId: targetId || undefined,
    backupId: backup?.id,
    packageId: packageId || undefined,
    protectionTaskId: flow.protectionTaskId,
    scope,
    includeImages: false,
    copySyncTaskLogs,
    restorePurpose: purpose,
    purpose,
    consistent,
    targetMode,
    targetInstanceId: targetId,
    config: {
      ...config,
      version:
        kind === "install"
          ? media?.version || config.version
          : kind === "restore"
            ? backup?.version
            : config.version,
      dbUser: config.dbUser,
    },
    credentials:
      kind === "plugin"
        ? { dataeaseToken: credentials.dataeaseToken }
        : kind === "install" ||
            (kind === "migrate" && targetMode === "manual") ||
            (kind === "restore" && !targetId)
          ? {
              ...credentials,
              dbUser: config.dbUser,
              dbPassword: config.dbPassword || credentials.dbPassword,
            }
          : undefined,
    destination: config.destination,
    backupPath: config.backupPath,
    nameBackup: config.nameBackup || `${source?.name || "实例"} · ${scope}`,
  });
  const runPrecheck = async () => {
    const requestEpoch = epoch.current;
    setBusy(true);
    setError("");
    setPrecheck(null);
    try {
      const result = await post("/prechecks", command());
      if (requestEpoch === epoch.current) setPrecheck(result);
    } catch (error) {
      if (requestEpoch === epoch.current) setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const confirmRequired = [
    "migrate",
    "restore",
    "install",
    "upgrade",
    "plugin",
  ].includes(kind);
  const next = async () => {
    setError("");
    if (step === 0) {
      if (!["install", "restore"].includes(kind) && !source)
        return setError("请选择操作实例。");
      if (kind === "migrate" && targetMode === "managed" && !target)
        return setError("请选择已接入的 V3 目标。");
      if (
        ["upgrade", "plugin"].includes(kind) &&
        (!media || mediaUnavailable(media))
      )
        return setError("请选择软件包库中的已校验介质。");
      if (kind === "install" && (!config.name.trim() || !config.host.trim()))
        return setError("请填写新实例名称和服务器地址。");
    }
    if (step === 1) {
      if (kind === "install" && (!media || mediaUnavailable(media)))
        return setError("请选择离线安装包。");
      if (
        ((kind === "migrate" && targetMode === "manual") ||
          (kind === "restore" && targetMode === "manual" && !targetId)) &&
        (!config.name.trim() ||
          !config.host.trim() ||
          !config.path.startsWith("/"))
      )
        return setError("请补全目标名称、服务器和独立安装目录。");
      if (
        kind === "restore" &&
        targetMode === "manual" &&
        !targetId &&
        (!media || mediaUnavailable(media))
      )
        return setError("新服务器恢复需要与备份匹配的离线包。");
      if (
        kind === "restore" &&
        flow.protectionTaskId &&
        target &&
        backup?.version !== target.version &&
        !media
      )
        return setError("版本回退需要与原保护备份精确版本匹配的离线包。");
      if (
        kind === "migrate" &&
        targetMode === "manual" &&
        !/^v?3\.\d+\.\d+$/.test(config.version)
      )
        return setError(
          "请填写目标已经安装的 V3 实际版本。本向导不会安装 V3。",
        );
    }
    if (kind === "restore" && step === 1 && targetMode === "managed" && !target)
      return setError("请选择与备份版本、数据库及架构匹配的恢复目标。");
    if (step === 2 && !precheck?.passed)
      return setError("请解决阻断项并完成预检查。");
    const lastStep = kind === "check" ? 0 : kind === "backup" ? 2 : 3;
    if (step < lastStep) {
      setStep(step + 1);
      return;
    }
    if (confirmRequired && confirm !== targetName)
      return setError(`请输入“${targetName}”确认目标。`);
    const requestEpoch = epoch.current;
    setBusy(true);
    try {
      let checked = precheck;
      if (kind === "check") {
        checked = await post("/prechecks", command());
        if (requestEpoch !== epoch.current) return;
        setPrecheck(checked);
        if (!checked.passed)
          return setError("检查条件不满足，请按下方结果处理后重试。");
      }
      if (scheduled && ["backup", "check"].includes(kind)) {
        await post("/plans", {
          ...command(),
          instanceId: sourceId,
          frequency: "daily",
          time,
          retention,
          enabled: true,
        });
        onComplete(
          null,
          `计划已保存，将于每天 ${time}（北京时间）执行，本次不会立即运行。`,
        );
        return;
      }
      const task = await post("/tasks", {
        ...command(),
        precheckId: checked.id,
        confirmTargetName: targetName,
      });
      onComplete(task);
    } catch (error) {
      setError(error.message);
    } finally {
      setBusy(false);
    }
  };
  const instanceSelect = (
    id,
    setter,
    label,
    items = state.instances,
    unavailable = () => "",
  ) => (
    <Field label={label}>
      <Select
        aria-label={label}
        value={id}
        onChange={(event) => change(setter, event.target.value)}
      >
        <option value="">请选择实例</option>
        {items.map((item) => (
          <option key={item.id} value={item.id} disabled={!!unavailable(item)}>
            {item.name} · {item.host} · {item.version} · {item.dbType} ·{" "}
            {item.status}
            {unavailable(item) ? ` · ${unavailable(item)}` : ""}
          </option>
        ))}
      </Select>
    </Field>
  );
  const mediaUnavailable = (item) => {
    if (item.status !== "已校验" || item.installSupported === false)
      return "暂不可用于执行";
    if (
      (kind === "install" || (kind === "restore" && !targetId)) &&
      item.dbTypes?.length &&
      !item.dbTypes.includes("MySQL")
    )
      return "当前自动部署仅支持 MySQL";
    if (kind === "restore" && !sameVersion(item.version, backup?.version))
      return "版本不匹配";
    if (kind === "restore" && item.arch !== backup?.arch) return "架构不匹配";
    if (
      kind === "upgrade" &&
      source &&
      !sameVersion(
        String(item.version).split(".")[0],
        String(source.version).split(".")[0],
      )
    )
      return "跨大版本请使用迁移";
    if (kind === "upgrade" && source && item.arch !== source.arch)
      return "架构不匹配";
    if (
      kind === "plugin" &&
      source &&
      item.major &&
      String(item.major).replace(/^v/i, "") !==
        String(source.version).replace(/^v/i, "").split(".")[0]
    )
      return "不适用于该大版本";
    return "";
  };
  const packageSelect = () => {
    const packages = kind === "plugin" ? state.plugins : state.packages;
    return (
      <>
        <Field label={kind === "plugin" ? "插件包" : "库中离线包"}>
          <Select
            aria-label={kind === "plugin" ? "插件包" : "库中离线包"}
            value={packageId}
            onChange={(event) => {
              change(setPackageId, event.target.value);
              const pkg = packages.find(
                (item) => item.id === event.target.value,
              );
              if (pkg?.dbTypes?.length && !pkg.dbTypes.includes(config.dbType))
                setConfig((old) => ({
                  ...old,
                  dbType: pkg.dbTypes.includes("MySQL")
                    ? "MySQL"
                    : pkg.dbTypes[0],
                }));
              if (pkg?.adapter === "dataease-compose-v1" && kind !== "upgrade")
                setConfig((old) => ({ ...old, dbUser: "root" }));
            }}
          >
            <option value="">请选择已校验软件包</option>
            {packages.map((item) => (
              <option
                key={item.id}
                value={item.id}
                disabled={!!mediaUnavailable(item)}
              >
                {item.name || item.fileName} · {item.version} ·{" "}
                {item.arch || "插件"} · {mediaUnavailable(item) || item.status}
              </option>
            ))}
          </Select>
        </Field>
        <div className="button-row">
          <Button small icon="upload" onClick={() => setUpload(true)}>
            {kind === "plugin" ? "上传插件包" : "上传离线包"}
          </Button>
          <small>
            {packages.some((item) => !mediaUnavailable(item))
              ? "仅可选择兼容且已校验的软件包。"
              : "暂无可用软件包，上传后可继续当前操作。"}
          </small>
        </div>
        {media && (
          <Facts
            rows={[
              ["版本", media.version],
              ["架构", media.arch],
              ["文件", media.fileName],
              ["SHA-256", media.sha256],
              ["可用数据库", (media.dbTypes || []).join("、")],
              ...(media.adapter === "dataease-compose-v1"
                ? [["部署方式", "社区版单机 Compose，内置 MySQL"]]
                : []),
            ]}
          />
        )}
      </>
    );
  };
  const targetFields = () => (
    <>
      <div className="form-grid">
        {kind !== "install" && (
          <>
            <Field
              label="目标实例名称"
              value={config.name}
              onChange={(event) =>
                update({ ...config, name: event.target.value })
              }
            />
            <Field label="环境标识">
              <Select
                aria-label="目标环境标识"
                value={config.environment}
                onChange={(event) =>
                  update({ ...config, environment: event.target.value })
                }
              >
                {environments.map((item) => (
                  <option key={item}>{item}</option>
                ))}
              </Select>
            </Field>
            <Field
              label="目标服务器"
              readOnly={config.connection === "local"}
              value={config.host}
              onChange={(event) =>
                update({
                  ...config,
                  host: event.target.value,
                  dbHost: config.dbHost || event.target.value,
                })
              }
            />
            <Field label="连接方式">
              <Select
                aria-label="目标连接方式"
                value={config.connection}
                onChange={(event) =>
                  update({
                    ...config,
                    connection: event.target.value,
                    host: event.target.value === "local" ? "127.0.0.1" : "",
                  })
                }
              >
                <option value="ssh">远程服务器 · SSH</option>
                <option value="local">运维中心所在服务器</option>
              </Select>
            </Field>
          </>
        )}
        <Field
          label="安装目录"
          value={config.path}
          onChange={(event) => update({ ...config, path: event.target.value })}
        />
        {kind !== "migrate" && (
          <>
            <Field
              label="服务端口"
              type="number"
              value={config.port}
              onChange={(event) =>
                update({ ...config, port: event.target.value })
              }
            />
            <Field label="服务器架构">
              <Select
                aria-label="服务器架构"
                value={config.arch}
                onChange={(event) =>
                  update({ ...config, arch: event.target.value })
                }
              >
                <option>x86_64</option>
                <option>ARM64</option>
              </Select>
            </Field>
          </>
        )}
      </div>
      <DatabaseFields
        supportedTypes={["MySQL"]}
        value={config}
        onChange={update}
        types={
          kind === "install"
            ? media?.dbTypes?.length
              ? media.dbTypes
              : ["MySQL"]
            : [backup?.dbType || "MySQL"]
        }
      />
      {media?.adapter === "dataease-compose-v1" && (
        <Notice>
          平台将在目标服务器新建 MySQL；数据库地址填写该服务器可直连
          IP，端口用于管理连接，使用 root 和至少8个字符的密码。
        </Notice>
      )}
      {kind !== "install" && (
        <CredentialFields
          value={credentials}
          onChange={(value) => change(setCredentials, value)}
          local={config.connection === "local"}
        />
      )}
    </>
  );
  const resolveCheck = (action) => {
    const endpoint =
      action.endpoint === "source"
        ? source
        : target ||
          (!["migrate", "restore", "install"].includes(kind) ? source : null);
    if (action.type === "task" && action.taskId)
      return onOpenTask(action.taskId);
    if (action.type === "connection" && endpoint)
      return onManageConnection(endpoint);
    if (action.type === "environment" && endpoint)
      return onManageEnvironment(endpoint);
    const nextStep =
      action.type === "media"
        ? ["install", "restore"].includes(kind)
          ? 1
          : 0
        : action.type === "target"
          ? kind === "restore"
            ? 1
            : 0
          : kind === "check"
            ? 0
            : 1;
    invalidate();
    setStep(nextStep);
  };
  const scheduleFields = () => (
    <>
      <Check
        checked={scheduled}
        onChange={(value) => change(setScheduled, value)}
      >
        每天定时执行
      </Check>
      {scheduled && (
        <>
          <div className="form-grid">
            <Field label="每天执行时间（北京时间）">
              <TimeSelect
                label="计划执行时间"
                value={time}
                onChange={(event) => change(setTime, event.target.value)}
              />
            </Field>
            {kind === "backup" && (
              <Field
                label="保留成功备份数"
                type="number"
                min="1"
                max="365"
                value={retention}
                onChange={(event) =>
                  change(setRetention, Number(event.target.value))
                }
              />
            )}
          </div>
          {kind === "backup" ? (
            <p className="wf-help">
              保存后按计划执行，本次不立即运行；新备份校验通过后才清理旧副本。
            </p>
          ) : (
            <Notice>保存后按计划执行，本次不会立即运行。</Notice>
          )}
        </>
      )}
    </>
  );
  const steps =
    kind === "migrate"
      ? ["选择 V3 目标", "连接与执行计划", "迁移预检查", "确认覆盖"]
      : kind === "backup"
        ? ["备份对象与范围", "备份方式与计划", "检查并创建"]
        : kind === "restore"
          ? ["备份与用途", "目标环境", "预检查与隔离", "确认还原"]
          : kind === "install"
            ? ["目标服务器", "离线包与配置", "安装预检查", "确认安装"]
            : kind === "check"
              ? ["检查设置"]
              : ["目标与离线包", "执行计划", "环境预检查", "确认执行"];
  return (
    <>
      <WizardFrame
        title={names[kind]}
        identity={
          kind === "restore"
            ? `备份：${backup?.name} · ${backup?.version}`
            : kind === "migrate"
              ? `${source?.name || "选择 V2 源实例"} → ${target?.name || (targetMode === "manual" ? config.name || "未接入的 V3" : "选择 V3 目标实例")}`
              : source
                ? `${source.name} · ${source.host} · ${source.version}`
                : config.name || "选择操作环境"
        }
        steps={steps}
        step={step}
        onClose={onClose}
        onBack={() => {
          setStep(step - 1);
          setError("");
        }}
        onNext={next}
        nextDisabled={step === 2 && !precheck?.passed}
        nextLabel={
          step === (kind === "check" ? 0 : kind === "backup" ? 2 : 3)
            ? scheduled
              ? "保存计划"
              : kind === "migrate"
                ? "确认覆盖并迁移"
                : kind === "check"
                  ? "开始检查"
                  : `确认${{ backup: "备份", restore: "还原", install: "安装", upgrade: "升级", check: "检查", plugin: "安装插件" }[kind]}`
            : undefined
        }
        busy={busy}
        error={error}
      >
        {step === 0 && (
          <>
            <h3>{steps[0]}</h3>
            {!["install", "restore"].includes(kind) &&
              instanceSelect(
                sourceId,
                setSourceId,
                kind === "migrate" ? "V2 源实例" : "操作实例",
                kind === "migrate"
                  ? state.instances.filter((item) =>
                      /^v?2\./.test(item.version),
                    )
                  : state.instances,
              )}
            {kind === "migrate" && (
              <>
                <Field label="目标选择方式">
                  <Select
                    aria-label="目标选择方式"
                    value={targetMode}
                    onChange={(event) => {
                      change(setTargetMode, event.target.value);
                      setTargetId("");
                    }}
                  >
                    <option value="managed">已接入的 V3 实例</option>
                    <option value="manual">已安装但未接入的 V3</option>
                  </Select>
                </Field>
                {targetMode === "managed" ? (
                  instanceSelect(
                    targetId,
                    setTargetId,
                    "V3 目标实例",
                    state.instances.filter((item) =>
                      /^v?3\./.test(item.version),
                    ),
                  )
                ) : (
                  <Notice>
                    此目标必须已经安装
                    V3，迁移不会自动安装。配置和连接在下一步填写。
                  </Notice>
                )}
                <Check
                  checked={copySyncTaskLogs}
                  onChange={(value) => change(setCopyLogs, value)}
                >
                  同时迁移数据同步任务的历史日志（可选）
                </Check>
              </>
            )}
            {kind === "check" && (
              <>
                <Notice>
                  检查容器、版本、数据库、存储空间和隔离条件，不修改实例配置。结果可在健康检查中查看和导出。
                </Notice>
                {scheduleFields()}
                {precheck && !precheck.passed && (
                  <CheckResults
                    checks={precheck.checks || []}
                    onAction={resolveCheck}
                    prioritize
                  />
                )}
              </>
            )}
            {kind === "backup" && (
              <>
                <Field label="备份范围">
                  <Select
                    aria-label="备份范围"
                    value={scope}
                    onChange={(event) => change(setScope, event.target.value)}
                  >
                    <option>整体备份</option>
                    <option>数据备份</option>
                    <option>资源备份</option>
                  </Select>
                </Field>
                <Notice>
                  {scope === "整体备份"
                    ? "覆盖元数据库、共库引擎、资源、插件、部署配置和恢复清单。不包含容器镜像，新机恢复需准备匹配的离线介质。"
                    : scope === "数据备份"
                      ? "备份 DataEase 数据库；不包含外部业务数据库的原始数据。"
                      : "备份资源与插件文件；不能单独建立完整环境。"}
                </Notice>
              </>
            )}
            {["upgrade", "plugin"].includes(kind) && packageSelect()}
            {kind === "install" && (
              <>
                <div className="form-grid">
                  <Field
                    label="实例名称"
                    value={config.name}
                    onChange={(event) =>
                      update({ ...config, name: event.target.value })
                    }
                  />
                  <Field label="环境标识">
                    <Select
                      aria-label="安装环境标识"
                      value={config.environment}
                      onChange={(event) =>
                        update({ ...config, environment: event.target.value })
                      }
                    >
                      {environments.map((item) => (
                        <option key={item}>{item}</option>
                      ))}
                    </Select>
                  </Field>
                </div>
                <Field label="连接方式">
                  <Select
                    aria-label="安装连接方式"
                    value={config.connection}
                    onChange={(event) =>
                      update({
                        ...config,
                        connection: event.target.value,
                        host: event.target.value === "local" ? "127.0.0.1" : "",
                      })
                    }
                  >
                    <option value="ssh">远程服务器 · SSH</option>
                    <option value="local">运维中心所在服务器</option>
                  </Select>
                </Field>
                <Field
                  label="服务器地址"
                  value={config.host}
                  readOnly={config.connection === "local"}
                  onChange={(event) =>
                    update({ ...config, host: event.target.value })
                  }
                />
                <CredentialFields
                  value={credentials}
                  onChange={(value) => change(setCredentials, value)}
                  local={config.connection === "local"}
                />
              </>
            )}
            {kind === "restore" && (
              <>
                <Facts
                  rows={[
                    ["备份来源", backup?.instanceName],
                    ["恢复点", backup?.recoveryPoint || backup?.date],
                    ["版本 / 数据库", `${backup?.version} / ${backup?.dbType}`],
                    ["校验", backup?.status],
                    ["副本位置", backupLocation(backup || {})],
                  ]}
                />
                {flow.targetId ? (
                  <Notice>
                    恢复变更前的目标状态，恢复目标已固定。请核对保护备份和目标名称。
                  </Notice>
                ) : (
                  <Field label="恢复用途">
                    <Select
                      aria-label="恢复用途"
                      value={purpose}
                      onChange={(event) => {
                        change(setPurpose, event.target.value);
                        if (
                          event.target.value === "rehearsal" &&
                          target?.id === backup?.instanceId &&
                          target.environmentPurpose !== "rehearsal"
                        )
                          setTargetId("");
                      }}
                    >
                      <option value="disaster">故障恢复（含异机）</option>
                      <option value="rehearsal">隔离恢复演练</option>
                    </Select>
                  </Field>
                )}
              </>
            )}
          </>
        )}
        {step === 1 && (
          <>
            <h3>{steps[1]}</h3>
            {kind === "migrate" && (
              <>
                {targetMode === "managed" ? (
                  <div className="wf-endpoints">
                    <Endpoint
                      instance={source}
                      label="源端"
                      onManage={onManageConnection}
                    />
                    <Endpoint
                      instance={target}
                      label="目标"
                      onManage={onManageConnection}
                    />
                  </div>
                ) : (
                  <>
                    <Endpoint
                      instance={source}
                      label="源端"
                      onManage={onManageConnection}
                    />
                    <Field
                      label="目标已安装版本"
                      value={config.version}
                      onChange={(event) =>
                        update({ ...config, version: event.target.value })
                      }
                      placeholder="例如 v3.0.0"
                    />
                    {targetFields()}
                  </>
                )}
                <Notice>
                  按需停止两端 DataEase 应用，保留
                  MySQL；分别创建并校验两端保护备份后覆盖。源 V2
                  保持停止，目标隔离启动并等待业务验收。
                </Notice>
              </>
            )}
            {kind === "backup" && (
              <>
                <Field
                  label="备份名称"
                  value={config.nameBackup}
                  onChange={(event) =>
                    update({ ...config, nameBackup: event.target.value })
                  }
                  placeholder={`${source?.name} · ${scope}`}
                  hint="保存到运维平台备份库，可下载到其他服务器保管。"
                />
                <Field label="一致性方式">
                  <Select
                    aria-label="备份一致性"
                    value={consistent}
                    onChange={(event) =>
                      change(setConsistent, event.target.value)
                    }
                  >
                    <option value="pause">停止应用后备份（推荐）</option>
                    <option value="online">
                      在线备份（需核对恢复点一致性）
                    </option>
                  </Select>
                </Field>
                <Notice tone={consistent === "pause" ? "blue" : "amber"}>
                  {consistent === "pause"
                    ? "备份期间停止 DataEase 应用，MySQL 保持运行。成功后恢复原运行状态；失败时请查看任务处理。"
                    : "应用继续运行，数据库和资源可能来自不同时刻，不适合作为要求一致恢复点的整体保护备份。"}
                </Notice>
              </>
            )}
            {kind === "backup" && scheduleFields()}
            {kind === "install" && (
              <>
                {packageSelect()}
                {targetFields()}
                <Notice>
                  V2 仅支持 MySQL；V3
                  的可选数据库以离线包实际能力清单为准。执行层不支持的部署组合会阻断。
                </Notice>
              </>
            )}
            {kind === "upgrade" && (
              <>
                <Endpoint
                  instance={source}
                  label="升级目标"
                  onManage={onManageConnection}
                />
                <Notice>
                  新建整体保护备份并校验后更新应用和数据库；普通升级不切换数据库类型。V2
                  → V3 使用独立迁移功能。
                </Notice>
              </>
            )}
            {kind === "plugin" && (
              <>
                <Notice>
                  停服创建整体保护备份后，隔离启动并通过 DataEase
                  接口安装。目标需具有有效企业授权；安装结果进入业务验收。
                </Notice>
                <Field
                  label="DataEase API 前缀"
                  value={config.apiPrefix}
                  onChange={(event) =>
                    update({ ...config, apiPrefix: event.target.value })
                  }
                />
                <Field
                  label="DataEase 管理员会话令牌"
                  type="password"
                  autoComplete="off"
                  value={credentials.dataeaseToken}
                  onChange={(event) =>
                    change(setCredentials, {
                      ...credentials,
                      dataeaseToken: event.target.value,
                    })
                  }
                />
                <Notice>
                  填写目标 DataEase 管理员的
                  X-DE-TOKEN。令牌加密保存于本次任务，不写入实例配置或导出记录；远程目标通过已保存的
                  SSH 连接访问本机接口。
                </Notice>
              </>
            )}
            {kind === "install" && (
              <Notice>
                插件需在实例完成初始化并具有有效授权后，通过软件包管理安装。
              </Notice>
            )}
            {kind === "restore" && (
              <>
                {!flow.targetId && (
                  <Field label="恢复方式">
                    <Select
                      aria-label="恢复方式"
                      value={targetMode === "managed" ? "managed" : "new"}
                      onChange={(event) => {
                        change(setTargetId, "");
                        if (event.target.value === "managed")
                          setTargetMode("managed");
                        else setTargetMode("manual");
                      }}
                    >
                      <option
                        value="new"
                        disabled={backup?.scope !== "整体备份"}
                      >
                        新服务器（需整体备份与匹配离线包）
                      </option>
                      <option value="managed">已接入的兼容实例</option>
                    </Select>
                  </Field>
                )}
                {flow.targetId ? (
                  <Endpoint
                    instance={target}
                    label="恢复目标"
                    onManage={onManageConnection}
                  />
                ) : targetMode === "managed" ? (
                  instanceSelect(
                    targetId,
                    setTargetId,
                    "已有恢复目标",
                    state.instances,
                    (item) =>
                      restoreMismatch(item) ||
                      (purpose === "rehearsal" &&
                      item.id === backup?.instanceId &&
                      item.environmentPurpose !== "rehearsal"
                        ? "演练不能覆盖备份来源实例"
                        : ""),
                  )
                ) : null}
                {!flow.targetId && targetMode === "manual" && (
                  <>
                    {packageSelect()}
                    {targetFields()}
                  </>
                )}
                {flow.protectionTaskId &&
                  target &&
                  backup?.version !== target.version && (
                    <>
                      <Notice>
                        先将应用回退至保护备份版本 {backup?.version}
                        ，再恢复数据与资源。请选择对应版本的完整离线包。
                      </Notice>
                      {packageSelect()}
                    </>
                  )}
                <Notice tone="amber">
                  {backup?.scope === "数据备份"
                    ? "覆盖目标数据库，保留现有资源文件。"
                    : backup?.scope === "资源备份"
                      ? "覆盖目标资源与插件文件，保留现有数据库。"
                      : "覆盖目标数据库、资源与部署配置。"}
                  {backup?.scope !== "整体备份" &&
                    "此备份不能重建完整环境，须选择已有兼容实例。"}
                  完成后隔离运行，业务验收通过后再决定开放访问。
                </Notice>
              </>
            )}
          </>
        )}
        {step === 2 && (
          <>
            <div className="wf-check-head">
              <h3>{precheck ? "预检查结果" : "检查执行条件"}</h3>
              <Button icon="refresh" disabled={busy} onClick={runPrecheck}>
                {busy ? "正在检查…" : precheck ? "重新检查" : "运行预检查"}
              </Button>
            </div>
            {precheck ? (
              <>
                <CheckResults
                  checks={precheck.checks || []}
                  onAction={resolveCheck}
                  prioritize
                />
                {precheck.passed && (
                  <Notice>检查通过。修改配置后需要重新检查。</Notice>
                )}
              </>
            ) : (
              <Notice>
                由服务端检查真实连接、权限、版本、目录、容量、保护覆盖和隔离条件。
              </Notice>
            )}
            {kind === "backup" && (
              <Notice tone="amber">
                {source?.name} · {scope} ·{" "}
                {scheduled
                  ? `每天 ${time}（北京时间），保存后不立即执行，保留 ${retention} 份成功备份。`
                  : "确认后执行。"}
                {consistent === "pause"
                  ? "备份期间应用停止，成功后恢复原运行状态。"
                  : "在线备份不保证数据库与资源处于同一时刻。"}
              </Notice>
            )}
          </>
        )}
        {step === 3 && (
          <>
            <h3>核对本次操作</h3>
            <Facts
              rows={[
                ["操作", names[kind]],
                ...(kind === "migrate"
                  ? [["V2 源实例", source?.name]]
                  : kind === "restore"
                    ? [["备份来源", backup?.instanceName]]
                    : []),
                [
                  ["migrate", "restore", "install"].includes(kind)
                    ? "目标实例"
                    : "操作实例",
                  targetName,
                ],
                ["目标服务器", target?.host || config.host || source?.host],
                [
                  "目标版本",
                  kind === "restore"
                    ? backup?.version
                    : kind === "upgrade" || kind === "install"
                      ? media?.version
                      : target?.version || config.version || source?.version,
                ],
                ["预检查", precheck?.passed ? "通过" : "已失效"],
              ]}
            />
            {kind === "migrate" && (
              <div className="wf-migration-fixed-impact">
                <strong>必须覆盖目标：{targetName}</strong>
                目标数据库将删除并重建，迁移资源覆盖到目标。两端保护备份必须校验通过；源
                V2 原数据保留。发生失败不会自动回滚。
              </div>
            )}
            {["install", "upgrade", "restore"].includes(kind) && (
              <Notice tone="amber">
                目标变更完成后先隔离运行，自动任务保持暂停，完成业务验收后决定开放访问。
              </Notice>
            )}
            {confirmRequired && (
              <Field
                label={`输入“${targetName || "目标名称"}”确认目标`}
                value={confirm}
                onChange={(event) => setConfirm(event.target.value)}
                autoComplete="off"
              />
            )}
          </>
        )}
      </WizardFrame>
      {upload && (
        <UploadDialog
          kind={kind === "plugin" ? "plugin" : "package"}
          onClose={() => setUpload(false)}
          onSaved={async (item) => {
            await onRefresh();
            if (mediaUnavailable(item)) {
              setError(item.validationMessage || mediaUnavailable(item));
              setUpload(false);
              return;
            }
            change(setPackageId, item.id);
            if (item.dbTypes?.length && !item.dbTypes.includes(config.dbType))
              setConfig((old) => ({
                ...old,
                dbType: item.dbTypes.includes("MySQL")
                  ? "MySQL"
                  : item.dbTypes[0],
              }));
            if (item.adapter === "dataease-compose-v1" && kind !== "upgrade")
              setConfig((old) => ({ ...old, dbUser: "root" }));
            setUpload(false);
          }}
        />
      )}
    </>
  );
}
