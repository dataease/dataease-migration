import React, { useLayoutEffect, useRef } from "react";
import { Icon } from "./ui";
import { stageTitle } from "./taskLog";

const stageStatus = {
  pending: { text: "未执行" },
  running: { text: "执行中" },
  passed: { text: "已完成", icon: "check" },
  completed: { text: "已完成", icon: "check" },
  skipped: { text: "已跳过" },
  failed: { text: "失败", icon: "warning" },
  warning: { text: "注意", icon: "warning" },
  unknown: { text: "状态待确认", icon: "warning" },
  interrupted: { text: "已中断", icon: "warning" },
};

export function taskStageView(task, active) {
  const recorded = Array.isArray(task.stages) ? task.stages : [];
  const current = recorded.findIndex((stage) => stageTitle(stage) === task.currentStage);
  const running = active && recorded[current]?.status === "running" ? current : -1;
  const hasFailure = Boolean(task.error) || task.state === "failed" || ["失败", "已中断"].includes(task.status);
  let failure = -1;
  if (hasFailure) {
    failure = recorded.findIndex((stage) => stageTitle(stage) === task.failureStage);
    if (failure < 0) failure = recorded.findLastIndex((stage) => ["failed", "unknown", "interrupted"].includes(stage.status));
  }
  const stages = recorded.map((stage, index) => ({
    ...stage,
    status: stage.status === "running" && index !== running ? "unknown" : stage.status,
    detail: (index === running && task.currentCheck) || stage.detail || stage.message,
  }));
  return { stages, failure, focused: running >= 0 ? running : failure >= 0 ? failure : current };
}

export default function TaskStageList({
  stages,
  focusedIndex = -1,
  selectedIndex = -1,
  label = "执行阶段列表",
  empty = "暂无阶段记录",
  canSelect,
  onSelect,
  renderDetail,
}) {
  const paneRef = useRef(null);
  const focusedRef = useRef(null);
  const activeTitle = stages[focusedIndex] && stageTitle(stages[focusedIndex]);
  useLayoutEffect(() => {
    const pane = paneRef.current;
    const row = focusedRef.current;
    if (!pane || !row) return;
    const reveal = () => {
      const top = row.offsetTop;
      const height = row.offsetHeight;
      if (top < pane.scrollTop || top + height > pane.scrollTop + pane.clientHeight) {
        pane.scrollTop = top - Math.max(0, (pane.clientHeight - height) / 2);
      }
    };
    reveal();
    const observer = new ResizeObserver(reveal);
    observer.observe(pane);
    observer.observe(row.parentElement);
    return () => observer.disconnect();
  }, [focusedIndex, activeTitle, stages.length]);

  return (
    <div ref={paneRef} className="task-stage-scroll" tabIndex={0} role="region" aria-label={label}>
      {stages.length ? (
        <ol className="task-stage-list">
          {stages.map((stage, index) => {
            const title = stageTitle(stage);
            const { status, detail } = stage;
            const running = status === "running";
            const selected = selectedIndex === index;
            const display = stageStatus[status] || stageStatus.unknown;
            const content = <>
              <strong className="task-stage-title">{title}</strong>
              <span className={`task-stage-state ${status === "completed" ? "passed" : status}`}>
                {running && <span className="task-spinner" aria-hidden="true" />}
                {display.icon && <Icon name={display.icon} size={12} aria-hidden="true" />}
                <span>{stage.statusLabel || display.text}</span>
              </span>
            </>;
            const clickable = canSelect?.(stage, index);
            return (
              <li key={`${title}-${index}`} ref={focusedIndex === index ? focusedRef : undefined}
                className={`task-stage ${running ? "is-current" : ""} ${selected ? "is-selected" : ""}`}
                aria-current={running ? "step" : undefined}>
                {clickable ? <button type="button" className="task-stage-row"
                  title={detail}
                  aria-pressed={renderDetail ? undefined : selected}
                  aria-expanded={renderDetail ? selected : undefined}
                  onClick={() => onSelect(index)}>{content}</button>
                  : <div className="task-stage-row" title={detail}>{content}</div>}
                {selected && renderDetail?.(stage)}
              </li>
            );
          })}
        </ol>
      ) : <p className="task-pane-empty">{empty}</p>}
    </div>
  );
}
