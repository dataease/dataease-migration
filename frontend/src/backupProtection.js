// Coverage describes the saved, verified recovery point; it does not infer off-site safety.
export function backupProtection(instance, backups) {
  const verified = backups.filter(
    (backup) => backup.instanceId === instance.id && backup.status === "已校验",
  );
  const version = (value) => String(value || "").replace(/^v/i, "");
  const compatible = verified.filter(
    (backup) =>
      version(backup.version) === version(instance.version) &&
      backup.dbType === instance.dbType &&
      backup.arch === instance.arch,
  );
  const complete = compatible.find(
    (backup) => backup.scope === "整体备份" && backup.consistent === "pause",
  );
  if (complete)
    return { protected: true, label: "已校验整体备份", backup: complete };
  const whole = compatible.find((backup) => backup.scope === "整体备份");
  if (whole)
    return {
      protected: false,
      label:
        whole.consistent === "online"
          ? "仅在线整体备份"
          : "整体备份一致性待核对",
      backup: whole,
    };
  if (compatible.length)
    return {
      protected: false,
      label: compatible.every((backup) => backup.scope === "资源备份")
        ? "仅资源备份"
        : compatible.every((backup) => backup.scope === "数据备份")
          ? "仅数据备份"
          : "仅分项备份",
      backup: compatible[0],
    };
  if (verified.length)
    return {
      protected: false,
      label: "仅旧版本或不兼容备份",
      backup: verified[0],
    };
  return { protected: false, label: "暂无已校验备份" };
}
