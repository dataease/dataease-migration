const marker = /^── (开始|完成|失败|状态待确认) · .+ ──$/;
const checkMarker = /^检查(通过|阻断|关注) · /;
const checkStatus = { "通过": "passed", "阻断": "blocked", "关注": "warning" };
const boundaryLabel = { passed: "完成", completed: "完成", failed: "失败", unknown: "状态待确认", interrupted: "状态待确认" };
const healthStage = "检查应用、数据库与资源";
const acceptanceStages = new Set(["自动核验验收条件", "切换前复核运行条件"]);

export function stageTitle(stage) {
  return stage.title || stage.name || "";
}

function addSavedChecks(task, stages, rows, anchors) {
  const isHealthCheck = task.kind === "check";
  const isCheckStage = (stage) => isHealthCheck ? stageTitle(stage) === healthStage : acceptanceStages.has(stageTitle(stage));
  const liveStage = stages.findIndex((stage) => isCheckStage(stage) && stageTitle(stage) === task.currentStage);
  const live = liveStage >= 0 && task.liveChecks?.length > 0;
  const report = isHealthCheck ? task.report : task.acceptance;
  const checks = live ? task.liveChecks : report?.checks;
  if (!checks?.length) return;
  let index = live ? liveStage : -1;
  if (!live) {
    const checkedAt = Date.parse(report.checkedAt);
    let mostRecent = -Infinity;
    stages.forEach((stage, candidate) => {
      const started = Date.parse(stage.startedAt);
      if (!isCheckStage(stage) || !Number.isFinite(started)
          || (Number.isFinite(checkedAt) ? started > checkedAt : stage.status === "running")
          || started < mostRecent) return;
      index = candidate;
      mostRecent = started;
    });
  }
  const stage = stages[index];
  if (stage?.checkResultsInLog) return;
  const savedRows = checks.map((check, position) => {
    const label = ["passed", "通过", "正常"].includes(check.status) ? "通过"
      : ["warning", "注意", "关注"].includes(check.status) ? "关注" : "阻断";
    const detail = check.detail || check.message;
    const hint = check.remediation?.hint;
    return {
      key: `saved-check-${position}`,
      checkStatus: checkStatus[label],
      text: [`检查${label} · ${[check.title || check.name, detail].filter(Boolean).join(" · ")}`,
        hint && hint !== detail ? hint : null].filter(Boolean).join("\n"),
    };
  });
  const start = rows.findIndex((row) => row.key === anchors.get(index));
  if (start >= 0) {
    const end = rows.findIndex((row, position) => position > start && row.kind);
    rows.splice(end < 0 ? rows.length : end, 0, ...savedRows);
  } else {
    const key = "saved-check-start";
    const title = stage ? stageTitle(stage) : isHealthCheck ? "健康检查结果" : "已记录的核验结果";
    rows.push({ key, kind: "开始", text: `── ${title} ──` }, ...savedRows);
    if (stage) anchors.set(index, key);
  }
}

export function taskLogView(task, stages) {
  const entries = Array.isArray(task.logs) ? task.logs : task.logs ? String(task.logs).split("\n") : [];
  const offset = Number.isInteger(task.logOffset) ? task.logOffset : 0;
  const lines = entries.map((entry) => {
    const raw = typeof entry === "string" ? entry : entry?.message || JSON.stringify(entry) || "";
    const parsed = raw.match(/^\[([^\]]+)\] ([\s\S]*)$/);
    const message = parsed?.[2] || raw;
    const kind = message.split("\n", 1)[0].match(marker)?.[1];
    const outcome = message.match(checkMarker)?.[1];
    return { text: raw, message, time: Date.parse(parsed?.[1]), kind, checkStatus: checkStatus[outcome] };
  });
  const legacyStarts = stages.map((stage) => {
    if (Number.isInteger(stage.logStart) || !stage.startedAt) return -1;
    const start = Date.parse(stage.startedAt);
    return lines.findIndex((line) => line.message === stageTitle(stage) && line.time >= start);
  });
  const before = new Map();
  const anchors = new Map();
  const add = (position, row) => {
    if (!before.has(position)) before.set(position, []);
    before.get(position).push(row);
  };
  stages.forEach((stage, index) => {
    if (Number.isInteger(stage.logStart)) {
      if (stage.logStart >= offset && stage.logStart - offset < lines.length) {
        anchors.set(index, `log-${stage.logStart}`);
      }
      return;
    }
    // Older tasks logged the title alone. Update their presentation using the recorded stage times.
    const start = legacyStarts[index];
    if (start < 0) return;
    const key = `log-${offset + start}`;
    anchors.set(index, key);
    lines[start].text = lines[start].text.replace(stageTitle(stage), `── 开始 · ${stageTitle(stage)} ──`);
    lines[start].kind = "开始";
    const endLabel = boundaryLabel[stage.status];
    if (!endLabel || !stage.endedAt) return;
    const endedAt = Date.parse(stage.endedAt);
    const afterEnd = lines.findIndex((line, position) => position > start && line.time > endedAt);
    const nextStart = Math.min(lines.length, ...legacyStarts.filter((position) => position > start));
    const position = Math.min(nextStart, afterEnd < 0 ? lines.length : afterEnd);
    add(position, { key: `legacy-end-${index}`, kind: endLabel, text: `── ${endLabel} · ${stageTitle(stage)} ──` });
  });
  const rows = lines.flatMap((line, index) => [
    ...(before.get(index) || []),
    { key: `log-${offset + index}`, kind: line.kind, checkStatus: line.checkStatus, text: line.text },
  ]);
  rows.push(...(before.get(lines.length) || []));
  addSavedChecks(task, stages, rows, anchors);
  return { rows, anchors };
}
