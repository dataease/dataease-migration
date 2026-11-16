import React, { useEffect, useState } from "react";
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
  SubmissionProgress,
  backupLocation,
} from "./components";
import { post, put } from "./api";
import { useTaskSubmission } from "./useTaskSubmission";
import { UploadDialog } from "./Dialogs";
import { canRebuildFromBackup } from "./backupProtection";
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
const builtinAdapter = "dataease-compose-v1";
const vendorEnterprise = "dataease-enterprise-vendor-compose-v1";
const isV2 = (version) => /^v?2\./i.test(version || "");
const versionParts = (value) => /^v?[23]\.\d{1,8}\.\d{1,8}$/.test(value || "")
  ? value.replace(/^v/, "").split(".").map(Number) : null;
const compareVersions = (first, second) => {
  const a = versionParts(first), b = versionParts(second);
  if (!a || !b) return null;
  return a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
};
const canDeclareUpgrade = (item) => item?.adapter === builtinAdapter && item.status === "已校验";
const installDefaults = (media) => ({
  path: isV2(media?.version) ? "/opt/dataease2.0" : "/opt/dataease3.0",
  dbName: media?.edition === "enterprise" ? media.defaultDatabaseName || "dataease"
    : isV2(media?.version) ? "dataease_v2" : "dataease_v3",
  port: media?.edition === "enterprise" ? media.defaultServicePort || 9080 : 8100,
});
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
  const emptyHost = (item) => item?.status === "未安装";
  const fixedTarget = state.instances.find((item) => item.id === flow.targetId);
  const originalEnterprise = backup?.restoreMode === "original-enterprise";
  const allowsEmptyRestore = canRebuildFromBackup(backup);
  const fixedRestore = kind === "restore" && !emptyHost(fixedTarget)
    && (Boolean(flow.protectionTaskId) || (originalEnterprise && !allowsEmptyRestore));
  const emptyHostMismatch = (item) => backup?.arch !== item.arch ? "服务器架构与备份不匹配"
    : originalEnterprise && (item.connection !== "ssh" || item.host === backup?.instanceSnapshot?.host)
      ? "请选择另一台 SSH 服务器" : "";
  const vendorWholeBackup = (item, selectedScope) =>
    item?.deploymentAdapter === vendorEnterprise && selectedScope === "整体备份";
  const vacantHosts = state.instances.filter(emptyHost);
  const hasVacantRestoreTarget = vacantHosts.some((item) => !emptyHostMismatch(item));
  const initialPackage = state.packages.find((item) => item.id === flow.packageId);
  const defaults = installDefaults(initialPackage);
  const sameVersion = (a, b) =>
    String(a || "").replace(/^v/i, "") === String(b || "").replace(/^v/i, "");
  const restorePackages = kind === "restore" && allowsEmptyRestore
    ? state.packages.filter((item) => sameVersion(item.version, backup.version)
      && item.arch === backup.arch && originalEnterprise === (item.edition === "enterprise"))
    : [];
  const supportsRestore = (item) => originalEnterprise
    ? item.purposes?.includes("restore") : !item.purposes?.length || item.purposes.includes("restore");
  const usableRestorePackages = restorePackages.filter((item) => item.status === "已校验"
    && item.installSupported !== false && (!item.dbTypes?.length || item.dbTypes.includes("MySQL"))
    && supportsRestore(item));
  const restoreMismatch = (item) =>
    originalEnterprise && (item.id !== backup.instanceId || backup.currentInstanceDeployment === false)
      ? "此备份仅可还原到原部署"
      : !sameVersion(item.version, backup?.version)
      ? "版本不匹配"
      : item.dbType !== backup?.dbType
        ? "数据库类型不匹配"
        : item.arch !== backup?.arch
          ? "架构不匹配"
          : "";
  const originalTarget =
    kind === "restore" &&
    state.instances.find(
      (item) => backup?.currentInstanceDeployment !== false
        && !emptyHost(item) && item.id === backup?.instanceId && !restoreMismatch(item),
    );
  const hasRestoreTarget =
    kind === "restore" &&
    state.instances.some((item) => !emptyHost(item) && !restoreMismatch(item));
  const [step, setStep] = useState(0),
    [sourceId, setSourceId] = useState(instance?.id || ""),
    [targetId, setTargetId] = useState(flow.targetId || (flow.purpose === "rehearsal" ? "" : originalTarget?.id || "")),
    [targetMode, setTargetMode] = useState(
      emptyHost(fixedTarget) ? "vacant" :
        kind === "restore" && !flow.targetId && (!hasRestoreTarget || flow.purpose === "rehearsal") && allowsEmptyRestore
          ? hasVacantRestoreTarget ? "vacant" : "manual"
          : "managed",
    ),
    [packageId, setPackageId] = useState(flow.packageId || (usableRestorePackages.length === 1 ? usableRestorePackages[0].id : "")),
    [scope, setScope] = useState("整体备份"),
    [copySyncTaskLogs, setCopyLogs] = useState(false),
    [purpose, setPurpose] = useState(flow.purpose || "disaster"),
    [consistent, setConsistent] = useState("pause"),
    [scheduled, setScheduled] = useState(!!flow.scheduled),
    [time, setTime] = useState("02:00"),
    [retention, setRetention] = useState(7),
    [confirm, setConfirm] = useState(""),
    [busy, setBusy] = useState(false),
    [minimum, setMinimum] = useState(null),
    [error, setError] = useState(""),
    [upload, setUpload] = useState(false);
  const existingMigration = kind === "migrate" && targetMode === "managed";
  const [config, setConfig] = useState({
    name: "",
    environment: "测试",
    host: "",
    connection: "ssh",
    path: kind === "restore" ? installDefaults(backup).path : defaults.path,
    port: kind === "install" ? defaults.port : kind === "migrate" || originalEnterprise ? 9080 : 8100,
    version: "",
    arch: backup?.arch || initialPackage?.arch || "x86_64",
    dbType: backup?.dbType || "MySQL",
    dbMode: kind === "install" && initialPackage?.adapter === builtinAdapter ? "builtin" : "",
    dbHost: "",
    dbPort: 3306,
    dbName: backup?.instanceSnapshot?.dbName || defaults.dbName,
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
    dataeaseAdminPassword: "",
  });
  const { progress, submit, reset } = useTaskSubmission();
  const invalidate = () => {
    reset();
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
  const reuseVacant = emptyHost(target) && (kind === "install" || kind === "restore" && targetMode === "vacant");
  const freshRestore = kind === "restore" && targetMode !== "managed";
  const sameHostMigration = kind === "migrate" && targetMode === "same-host";
  useEffect(() => {
    invalidate();
  }, [source?.id, source?.revision, target?.id, target?.revision]);
  const media = (kind === "plugin" ? state.plugins : state.packages).find(
    (item) => item.id === packageId,
  );
  const builtinInstall = kind === "install" && config.dbMode === "builtin";
  const builtinRestore = freshRestore && media?.adapter === builtinAdapter && (reuseVacant || originalEnterprise);
  const selectMedia = (item) => {
    setMinimum(null);
    change(setPackageId, item?.id || "");
    if (kind !== "install") {
      if (item?.adapter === builtinAdapter && kind === "restore")
        setConfig((old) => ({ ...old, dbUser: "root" }));
      return;
    }
    const previous = installDefaults(media);
    const next = installDefaults(item);
    setConfig((old) => ({
      ...old,
      path: !old.path || old.path === previous.path ? next.path : old.path,
      dbName: !old.dbName || old.dbName === previous.dbName ? next.dbName : old.dbName,
      port: old.port === "" || old.port === previous.port ? next.port : old.port,
      arch: item?.arch || old.arch,
      dbType: isV2(item?.version) ? "MySQL" :
        item?.dbTypes?.includes(old.dbType) ? old.dbType : item?.dbTypes?.[0] || "MySQL",
      dbMode: item?.adapter === builtinAdapter ? old.dbMode || "builtin" : "",
    }));
  };
  useEffect(() => {
    invalidate();
  }, [media?.id, media?.revision, media?.sha256]);
  const targetName =
    kind === "migrate" || kind === "restore"
      ? target?.name || config.name
      : kind === "install"
        ? target?.name || config.name
        : source?.name;
  const command = () => ({
    kind,
    sourceId: sourceId || undefined,
    targetId: reuseVacant ? undefined : targetId || undefined,
    vacantTargetId: reuseVacant ? target.id : undefined,
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
      dbMode: builtinRestore ? "builtin" : config.dbMode,
      version:
        kind === "install"
          ? media?.version || config.version
          : kind === "restore"
            ? backup?.version
            : config.version,
      dbType: builtinInstall || kind === "install" && isV2(media?.version) ? "MySQL" : config.dbType,
      dbPassword: undefined,
    },
    credentials:
      kind === "plugin"
        ? { dataeaseAdminPassword: credentials.dataeaseAdminPassword }
        : reuseVacant
          ? builtinInstall || builtinRestore ? undefined : {
              dbUser: config.dbUser,
              dbPassword: config.dbPassword || credentials.dbPassword,
            }
        : kind === "install" ||
            (kind === "migrate" && targetMode === "manual") ||
            (kind === "restore" && !targetId)
          ? {
              ...credentials,
              dbUser: builtinInstall || builtinRestore ? undefined : config.dbUser,
              dbPassword: builtinInstall || builtinRestore ? undefined : config.dbPassword || credentials.dbPassword,
            }
          : undefined,
    destination: config.destination,
    backupPath: config.backupPath,
    nameBackup: config.nameBackup || `${source?.name || "实例"} · ${scope}`,
  });
  const confirmRequired = [
    "migrate",
    "restore",
    "install",
    "upgrade",
    "plugin",
  ].includes(kind);
  const selectedMinimum = minimum ?? media?.minimumUpgradeSource ?? "";
  const saveUpgradeCompatibility = async () => {
    const value = selectedMinimum.trim();
    if (!versionParts(value)) throw new Error("请输入完整版本号，例如 v3.0.0。");
    if (versionParts(value)[0] !== versionParts(media.version)?.[0] || compareVersions(value, media.version) >= 0)
      throw new Error("最低来源版本须与升级包主版本相同，并低于升级包版本。");
    if (compareVersions(source.version, value) < 0)
      throw new Error(`当前实例 ${source.version} 低于声明的最低来源版本。`);
    if (compareVersions(value, media.minimumUpgradeSource) === 0) return;
    await put(`/packages/${media.id}/upgrade-compatibility`, {
      minimumUpgradeSource: value, sha256: media.sha256, revision: media.revision ?? 0,
    });
    await onRefresh();
    setMinimum(null);
  };
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
      if (kind === "upgrade" && canDeclareUpgrade(media)) {
        setBusy(true);
        try {
          await saveUpgradeCompatibility();
        } catch (failure) {
          return setError(failure.message);
        } finally {
          setBusy(false);
        }
      }
      if (kind === "install" && !reuseVacant)
        return setError("安装目标须处于未安装状态，请刷新实例列表。");
    }
    if (step === 1 || (step === 0 && fixedRestore)) {
      if (kind === "install" && (!media || mediaUnavailable(media)))
        return setError("请选择离线安装包。");
      if (kind === "restore" && targetMode === "vacant" && (!reuseVacant || !allowsEmptyRestore || emptyHostMismatch(target)))
        return setError("请选择与整体备份架构匹配的未安装实例。");
      if (sameHostMigration && (!media || mediaUnavailable(media)))
        return setError("请选择与源服务器架构匹配的 V3 企业版离线安装包。");
      if (sameHostMigration && (!config.name.trim() || !config.path.startsWith("/") ||
          !Number.isInteger(Number(config.port)) || Number(config.port) < 1 || Number(config.port) > 65535))
        return setError("请填写 V3 实例名称、独立安装目录和业务端口。");
      if (
        ((kind === "migrate" && targetMode === "manual") ||
          (kind === "restore" && targetMode === "manual" && !targetId)) &&
        (!config.name.trim() ||
          !config.host.trim() ||
          !config.path.startsWith("/"))
      )
        return setError("请补全目标名称、服务器和独立安装目录。");
      if (
        freshRestore &&
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
    if (kind === "restore" && (step === 1 || fixedRestore) && targetMode === "managed" && !target)
      return setError("请选择与备份版本、数据库及架构匹配的恢复目标。");
    if (step < steps.length - 1) {
      setStep(step + 1);
      return;
    }
    if (confirmRequired && confirm !== targetName)
      return setError(`请输入“${targetName}”确认目标。`);
    setBusy(true);
    try {
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
      const result = await submit({
        ...command(),
        confirmTargetName: confirmRequired ? confirm : undefined,
      });
      if (result?.task) onComplete(result.task);
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
    items = state.instances.filter((item) => !emptyHost(item)),
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
            {[item.name, item.host, item.version, item.dbType].filter(Boolean).join(" · ")} ·{" "}
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
    if (sameHostMigration && (!/^v?3\.\d+\.\d+$/.test(item.version) || item.edition !== "enterprise" ||
        item.adapter !== builtinAdapter))
      return "需要 V3 企业版安装包";
    if (sameHostMigration && source?.arch && item.arch !== source.arch)
      return "架构不匹配";
    if (
      (kind === "install" || freshRestore) &&
      item.dbTypes?.length &&
      !item.dbTypes.includes("MySQL")
    )
      return "当前自动部署仅支持 MySQL";
    if (kind === "restore" && !sameVersion(item.version, backup?.version))
      return "版本不匹配";
    if (kind === "restore" && item.arch !== backup?.arch) return "架构不匹配";
    if (freshRestore && originalEnterprise !== (item.edition === "enterprise"))
      return originalEnterprise ? "需要企业版离线包" : "需要社区版离线包";
    if (freshRestore && !supportsRestore(item))
      return "不支持还原部署";
    if (reuseVacant && target.arch !== item.arch) return "服务器架构不匹配";
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
    if (kind === "upgrade" && source) {
      if ((source.deploymentAdapter === vendorEnterprise) !== (item.edition === "enterprise"))
        return "版本类型不匹配";
      if (compareVersions(item.version, source.version) <= 0)
        return "须高于当前版本";
    }
    if (kind === "upgrade" && !canDeclareUpgrade(item) && !item.purposes?.includes("upgrade"))
      return "此包未支持离线升级";
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
    const packages = kind === "plugin" ? state.plugins
      : sameHostMigration ? state.packages.filter((item) => /^v?3\./.test(item.version) && item.edition === "enterprise")
      : freshRestore ? restorePackages : state.packages;
    return (
      <>
        <Field label={kind === "plugin" ? "插件包" : "库中离线包"}>
          <Select
            aria-label={kind === "plugin" ? "插件包" : "库中离线包"}
            value={packageId}
            onChange={(event) =>
              selectMedia(packages.find((item) => item.id === event.target.value))
            }
          >
            <option value="">请选择已校验软件包</option>
            {packages.map((item) => (
              <option
                key={item.id}
                value={item.id}
                disabled={!!mediaUnavailable(item)}
              >
                {item.name || item.fileName} · {item.version} ·{" "}
                {item.arch || "插件"} · {mediaUnavailable(item) ||
                  (kind === "upgrade" && canDeclareUpgrade(item) && !item.minimumUpgradeSource ? "待补兼容声明" : item.status)}
              </option>
            ))}
          </Select>
        </Field>
        <div className="button-row">
          <Button small icon="upload" onClick={() => setUpload(true)}>
            {kind === "plugin" ? "上传插件包" : "上传离线包"}
          </Button>
          {(!freshRestore || !packages.some((item) => !mediaUnavailable(item))) && <small>
            {packages.some((item) => !mediaUnavailable(item))
              ? "仅可选择兼容且已校验的软件包。"
              : freshRestore ? `请上传 ${backup.version} · ${backup.arch} 的匹配离线包。`
                : "暂无可用软件包，上传后可继续当前操作。"}
          </small>}
        </div>
        {kind === "upgrade" && canDeclareUpgrade(media) && (
          <Field label="最低可升级来源版本" placeholder="例如 v3.0.0"
            value={selectedMinimum}
            onChange={(event) => change(setMinimum, event.target.value)}
            hint="填写介质提供方已验证的版本。点击下一步将保存到软件包库。"
            autoComplete="off" />
        )}
        {media && !freshRestore && (
          <Facts
            rows={[
              ["版本", media.version],
              ["架构", media.arch],
              ["文件", media.fileName],
              ["SHA-256", media.sha256],
              ["可用数据库", (media.dbTypes || []).join("、")],
              ...(media.adapter === builtinAdapter
                ? [["部署方式", media.edition === "enterprise"
                    ? "企业版单机 Docker Compose"
                    : media.edition === "community" ? "社区版单机 Docker Compose" : "单机 Docker Compose"]]
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
        {kind !== "install" && !reuseVacant && (
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
                  dbHost: !config.dbHost || config.dbHost === config.host
                    ? event.target.value : config.dbHost,
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
                {!originalEnterprise && <option value="local">运维中心所在服务器</option>}
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
            {reuseVacant ? <Field label="服务器架构" value={target.arch} readOnly /> : <Field label="服务器架构">
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
            </Field>}
          </>
        )}
      </div>
      {kind === "install" && media?.adapter === builtinAdapter && (
        <Field label="数据库部署">
          <Select aria-label="数据库部署" value={config.dbMode}
            onChange={(event) => update({
              ...config,
              dbMode: event.target.value,
              dbType: event.target.value === "builtin" ? "MySQL" : config.dbType,
            })}
          >
            <option value="builtin">内置 MySQL</option>
            <option value="external">外部数据库</option>
          </Select>
        </Field>
      )}
      {freshRestore && !media ? null : builtinInstall || builtinRestore ? (
        <Facts rows={[["数据库名称", config.dbName], ["数据库密码", "由平台生成并保存"],
          ...(media?.edition === "enterprise" ? [["数据库访问", "通过 SSH 访问，宿主机不发布 3306"]] : [])]} />
      ) : <DatabaseFields
        fixedType={kind === "install" && isV2(media?.version)}
        supportedTypes={kind === "install" && config.dbMode === "external" ? undefined : ["MySQL"]}
        value={config}
        onChange={update}
        types={
          kind === "install"
            ? media?.dbTypes?.length
              ? media.dbTypes
              : ["MySQL"]
            : [backup?.dbType || "MySQL"]
        }
      />}
      {kind === "restore" && !builtinRestore && media?.adapter === builtinAdapter && (
        <Notice>
          平台将在目标服务器新建 MySQL；数据库地址填写该服务器可直连
          IP，端口用于管理连接，使用 root 和至少8个字符的密码。
        </Notice>
      )}
      {kind !== "install" && !reuseVacant && (
        <CredentialFields
          value={credentials}
          onChange={(value) => change(setCredentials, value)}
          local={config.connection === "local"}
        />
      )}
    </>
  );
  const resolveCheck = (action) => {
    if (action.type === "backup-resources" && kind === "backup") {
      change(setScope, "资源备份");
      setStep(0);
      return;
    }
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
        ? ["install", "restore"].includes(kind) || sameHostMigration
          ? 1
          : 0
        : action.type === "target"
          ? kind === "restore"
            ? 1
            : 0
          : ["check", "backup"].includes(kind)
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
      ? existingMigration ? ["选择 V3 目标", "确认迁移"] : ["选择 V3 目标", "目标配置", "确认迁移"]
      : kind === "backup"
        ? ["备份设置"]
        : kind === "restore"
          ? fixedRestore ? ["备份与目标", "确认还原"] : ["备份与用途", "目标环境", "确认还原"]
          : kind === "install"
            ? ["目标服务器", "离线包与配置", "确认安装"]
            : kind === "check"
              ? ["检查设置"]
              : kind === "upgrade" ? ["目标与软件包", "确认升级"] : ["目标与软件包", "执行设置", "确认执行"];
  return (
    <>
      <WizardFrame
        title={names[kind]}
        identity={
          kind === "restore"
            ? `备份：${backup?.name} · ${backup?.version}`
            : kind === "migrate"
              ? `${source?.name || "选择 V2 源实例"} → ${target?.name || (sameHostMigration ? config.name || "同机新建 V3" : targetMode === "manual" ? config.name || "未接入的 V3" : "选择 V3 目标实例")}`
              : source
                ? `${source.name} · ${source.host} · ${source.version}`
                : target?.name || config.name || (kind === "install" ? "填写目标服务器" : "选择操作环境")
        }
        steps={steps}
        step={step}
        onClose={onClose}
        onBack={() => {
          setStep(step - 1);
          invalidate();
        }}
        onNext={next}
        nextDisabled={step === steps.length - 1 && confirmRequired && (!targetName || confirm !== targetName)}
        nextLabel={
          step === steps.length - 1
            ? scheduled
              ? "保存计划"
              : kind === "migrate"
                ? sameHostMigration ? "确认同机迁移" : "确认覆盖并迁移"
                : kind === "check"
                  ? "开始检查"
                  : `确认${{ backup: "备份", restore: "还原", install: "安装", upgrade: "升级", check: "检查", plugin: "安装插件" }[kind]}`
            : undefined
        }
        busy={busy}
        busyLabel={progress ? null : kind === "upgrade" && step === 0 ? "正在保存升级兼容声明"
          : scheduled ? "正在保存计划" : "正在确认操作"}
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
                      /^v?2\./.test(item.version) && !item.archivedByMigrationTo,
                    )
                  : state.instances.filter((item) => !emptyHost(item)),
              )}
            {kind === "migrate" && (
              <>
                <Field label="目标选择方式">
                  <Select
                    aria-label="目标选择方式"
                    value={targetMode}
                    onChange={(event) => {
                      const mode = event.target.value;
                      change(setTargetMode, mode);
                      setTargetId("");
                      setPackageId("");
                      setConfig((current) => ({
                        ...current,
                        name: "",
                        path: "/opt/dataease3.0",
                        port: mode === "same-host" ? (Number(source?.port) === 19080 ? 19081 : 19080) : 9080,
                      }));
                    }}
                  >
                    <option value="managed">已接入的 V3 实例</option>
                    <option value="manual">已安装但未接入的 V3</option>
                    <option value="same-host">在 V2 服务器上新建 V3</option>
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
                ) : sameHostMigration ? (
                  <Notice>沿用源实例的 SSH 连接，在同一台服务器安装独立的 V3 企业版。需要 V3 离线包和空闲业务端口。</Notice>
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
                    <option value="整体备份">整体备份（数据库与资源）</option>
                    <option value="数据备份">数据备份（元数据库与共库引擎）</option>
                    <option value="资源备份">资源备份（上传文件、字体、插件等）</option>
                  </Select>
                </Field>
                <Notice>
                  {scope === "整体备份"
                    ? source?.deploymentAdapter === vendorEnterprise
                      ? "暂停应用写入后备份数据库、资源和网关状态。支持原部署还原和异机重建。"
                      : "归档元数据库、共库引擎、资源、插件和部署配置。外部数据源及容器镜像需单独保留。"
                    : scope === "数据备份"
                      ? "备份 DataEase 数据库；不包含外部业务数据库的原始数据。"
                    : "保存资源与插件文件。适合数据库或引擎需要单独备份的实例。"}
                </Notice>
              </>
            )}
            {["upgrade", "plugin"].includes(kind) && packageSelect()}
            {kind === "install" && (reuseVacant
              ? <Endpoint instance={target} label="安装目标" onManage={onManageConnection} />
              : <Notice tone="amber">该实例的安装状态已变化，请返回实例管理刷新。</Notice>)}
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
                {flow.protectionTaskId ? (
                  <Notice>
                    恢复变更前的目标状态，恢复目标已固定。请核对保护备份和目标名称。
                  </Notice>
                ) : (!originalEnterprise || allowsEmptyRestore) && !fixedRestore && (
                  <Field label="恢复用途">
                    <Select
                      aria-label="恢复用途"
                      value={purpose}
                      onChange={(event) => {
                        change(setPurpose, event.target.value);
                        if (event.target.value === "rehearsal" && !flow.targetId && targetMode === "managed") {
                          if (originalEnterprise) setTargetMode(hasVacantRestoreTarget ? "vacant" : "manual");
                          setTargetId("");
                        }
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
        {((step === 1 && !fixedRestore && !existingMigration && kind !== "upgrade") || (step === 0 && fixedRestore) || kind === "backup") && (
          <>
            {step === 1 && <h3>{steps[1]}</h3>}
            {kind === "migrate" && (
              <>
                {sameHostMigration ? (
                  <>
                    <Endpoint instance={source} label="源端与部署主机" onManage={onManageConnection} />
                    <div className="form-grid">
                      <Field label="V3 实例名称" value={config.name}
                        onChange={(event) => update({ ...config, name: event.target.value })} />
                      <Field label="环境标识">
                        <Select aria-label="目标环境标识" value={config.environment}
                          onChange={(event) => update({ ...config, environment: event.target.value })}>
                          {["生产", "预发布", "测试"].map((value) => <option key={value}>{value}</option>)}
                        </Select>
                      </Field>
                      <Field label="V3 安装目录" value={config.path}
                        onChange={(event) => update({ ...config, path: event.target.value })} />
                      <Field label="V3 业务端口" type="number" min={1} max={65535} value={config.port}
                        onChange={(event) => update({ ...config, port: event.target.value })} />
                    </div>
                    {packageSelect()}
                  </>
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
                  {sameHostMigration
                    ? "迁移开始时停止 V2 应用和配套服务，仅保留 MySQL。校验 V2 备份后安装 V3，完成 V3 验收后停止原 MySQL。"
                    : "停止两端 DataEase 应用并保留 MySQL，校验两端保护备份后覆盖目标。目标隔离启动并等待业务验收。"}
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
                      {vendorWholeBackup(source, scope) ? "在线归档（仅供导出）" : "在线备份（需核对恢复点一致性）"}
                    </option>
                  </Select>
                </Field>
                <Notice tone={consistent === "pause" ? "blue" : "amber"}>
                  {consistent === "pause"
                    ? "备份期间停止 DataEase 应用，MySQL 保持运行。成功后恢复原运行状态；失败时请查看任务处理。"
                    : vendorWholeBackup(source, scope)
                      ? "应用继续运行。此归档不含网关状态，不能用作整体还原。"
                      : scope === "整体备份"
                      ? "应用继续运行，数据库和资源可能来自不同时刻。"
                      : scope === "资源备份" ? "应用继续运行，归档期间资源文件仍可能变化。"
                        : "应用继续运行，备份元数据库。"}
                </Notice>
              </>
            )}
            {kind === "backup" && scheduleFields()}
            {kind === "install" && (
              <>
                {packageSelect()}
                {targetFields()}
                {config.dbMode === "external" && (
                  <Notice tone="amber">当前安装后的隔离验收只支持内置 MySQL。使用外部数据库时，请先完成部署，再接入实例。</Notice>
                )}
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
                  label="目标 DataEase 的 admin 密码"
                  type="password"
                  autoComplete="off"
                  value={credentials.dataeaseAdminPassword}
                  onChange={(event) =>
                    change(setCredentials, {
                      ...credentials,
                      dataeaseAdminPassword: event.target.value,
                    })
                  }
                />
                <Notice>
                  密码仅用于本次任务。实例进入隔离后，运维中心会重新登录 DataEase 执行安装。
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
                {!flow.targetId && !fixedRestore && (
                  <Field label="恢复方式">
                    <Select
                      aria-label="恢复方式"
                      value={targetMode}
                      onChange={(event) => {
                        const mode = event.target.value;
                        change(setTargetId, mode === "managed" && purpose !== "rehearsal" ? originalTarget?.id || "" : "");
                        setTargetMode(mode);
                      }}
                    >
                      <option
                        value="manual"
                        disabled={!allowsEmptyRestore}
                      >
                        新服务器（需整体备份与匹配离线包）
                      </option>
                      <option value="managed" disabled={originalEnterprise && (!originalTarget || purpose === "rehearsal")}>
                        {originalEnterprise ? "还原到原部署" : "已安装的兼容实例"}
                      </option>
                      <option value="vacant" disabled={!allowsEmptyRestore || !hasVacantRestoreTarget}>已添加的未安装实例</option>
                    </Select>
                  </Field>
                )}
                {(flow.targetId || fixedRestore) && target ? (
                  <Endpoint
                    instance={target}
                    label="恢复目标"
                    onManage={onManageConnection}
                  />
                ) : targetMode === "vacant" ? (
                  instanceSelect(targetId, setTargetId, "未安装的恢复目标", vacantHosts, emptyHostMismatch)
                ) : targetMode === "managed" ? (
                  instanceSelect(
                    targetId,
                    setTargetId,
                    "已有恢复目标",
                    state.instances.filter((item) => !emptyHost(item)),
                    (item) =>
                      restoreMismatch(item) ||
                      (purpose === "rehearsal" &&
                      item.id === backup?.instanceId &&
                      item.environmentPurpose !== "rehearsal"
                        ? "演练不能覆盖备份来源实例"
                        : ""),
                  )
                ) : null}
                {freshRestore && (targetMode === "manual" || reuseVacant) && (
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
                  {freshRestore ? originalEnterprise
                    ? "在目标服务器部署同版本企业版，再还原数据库和资源。网关使用新部署的原厂路由，自定义路由和证书需重新配置。"
                    : "在目标服务器部署匹配版本，再还原整体备份中的数据和资源。" : backup?.scope === "数据备份"
                    ? "覆盖目标数据库，保留现有资源文件。"
                    : backup?.scope === "资源备份"
                      ? "覆盖目标资源与插件文件，保留现有数据库。"
                      : originalEnterprise
                        ? "覆盖原部署的数据库、资源与网关状态，保留端口、连接与 Compose 配置。"
                        : "覆盖目标数据库、资源与部署配置。"}
                  {!originalEnterprise && backup?.scope !== "整体备份" &&
                    "此备份不能重建完整环境，须选择已有兼容实例。"}
                  {purpose === "rehearsal" ? "完成后保持隔离。" : "完成后隔离运行，业务验收通过后可开放访问。"}
                </Notice>
              </>
            )}
          </>
        )}
        {(step === 2 || (step === 1 && (fixedRestore || existingMigration || kind === "upgrade"))) && (
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
                ...(kind === "upgrade" ? [["当前版本", source?.version], ["最低来源版本", media?.minimumUpgradeSource]] : []),
                [
                  "目标版本",
                  kind === "restore"
                    ? backup?.version
                    : kind === "upgrade" || kind === "install" || sameHostMigration
                      ? media?.version
                      : target?.version || config.version || source?.version,
                ],
              ]}
            />
            {kind === "migrate" && (
              <div className="wf-migration-fixed-impact">
                <strong>{sameHostMigration ? "迁入新建 V3" : "必须覆盖目标"}：{targetName}</strong>
                {sameHostMigration
                  ? "迁移期间 V2 停服，保留 MySQL 用于备份和迁移。V3 验收后停止原 MySQL，保留原数据。"
                  : "两端停服并校验保护备份后，重建 V3 数据库并替换目标资源。若写入后失败，可在任务详情还原目标保护备份。"}
                <div>本地 Excel/CSV 引擎数据随数据库迁移。验收时通过 V3 应用核对工作表能否查询。</div>
              </div>
            )}
            {kind === "install" && media?.adapter === builtinAdapter && (
              <Notice>目标已有可用 Docker 与 Compose 时直接复用。预检查确认可离线部署时，将使用包内组件安装缺失的环境。</Notice>
            )}
            {["install", "upgrade", "restore"].includes(kind) && (
              <Notice tone="amber">
                {kind === "upgrade"
                  ? "停服前核查磁盘空间，随后建立整体保护备份。升级后先隔离运行，自动任务保持暂停，验收后可开放访问。"
                  : kind === "restore" && purpose === "rehearsal"
                  ? "验收后仍保持隔离，业务入口关闭，自动任务暂停。"
                  : "目标变更完成后先隔离运行，自动任务保持暂停，完成业务验收后可开放访问。"}
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
        {progress && <SubmissionProgress progress={progress} onAction={resolveCheck} />}
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
            selectMedia(item);
            setUpload(false);
          }}
        />
      )}
    </>
  );
}
