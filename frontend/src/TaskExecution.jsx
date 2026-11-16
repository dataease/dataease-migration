import React, { useLayoutEffect, useRef, useState } from "react";
import { Button, Icon, taskActivity } from "./components";

const stageStatus = {
  pending: { text: "未执行" },
  running: { text: "执行中" },
  passed: { text: "已完成", icon: "check" },
  completed: { text: "已完成", icon: "check" },
  skipped: { text: "已跳过" },
  failed: { text: "失败", icon: "warning" },
  unknown: { text: "状态待确认", icon: "warning" },
  interrupted: { text: "已中断", icon: "warning" },
};

export default function TaskExecution({ task, active }) {
  const stagePane = useRef(null);
  const currentRow = useRef(null);
  const logPane = useRef(null);
  const lastLogTop = useRef(0);
  const [followLogs, setFollowLogs] = useState(true);
  const stages = Array.isArray(task.stages) ? task.stages : [];
  const currentIndex = stages.findIndex((stage) => (stage.title || stage.name) === task.currentStage);
  const runningIndex = active && stages[currentIndex]?.status === "running" ? currentIndex : -1;
  const failureIndex = !active && task.error
    ? stages.findIndex((stage) => (stage.title || stage.name) === task.failureStage)
    : -1;
  const focusedIndex = runningIndex >= 0 ? runningIndex : failureIndex >= 0 ? failureIndex : currentIndex;
  const activity = active ? taskActivity(task) : null;
  const runningDetail = task.currentCheck ? activity?.title : activity?.detail;
  const logs = Array.isArray(task.logs) ? task.logs.map(
    (entry) => typeof entry === "string" ? entry : entry?.message || JSON.stringify(entry),
  ).join("\n") : task.logs || "";

  useLayoutEffect(() => {
    const pane = stagePane.current;
    const row = currentRow.current;
    if (!pane || !row) return;
    const reveal = () => {
      const top = row.offsetTop;
      const height = row.offsetHeight;
      const bottom = pane.scrollTop + pane.clientHeight;
      if (top < pane.scrollTop || top + height > bottom) {
        pane.scrollTop = top - Math.max(0, (pane.clientHeight - height) / 2);
      }
    };
    reveal();
    const observer = new ResizeObserver(reveal);
    observer.observe(pane);
    observer.observe(row.parentElement);
    return () => observer.disconnect();
  }, [focusedIndex, runningIndex, task.currentStage, task.failureStage, stages.length]);

  useLayoutEffect(() => {
    const pane = logPane.current;
    if (!followLogs || !pane) return;
    const follow = () => {
      pane.scrollTop = pane.scrollHeight;
      lastLogTop.current = pane.scrollTop;
    };
    follow();
    const observer = new ResizeObserver(follow);
    observer.observe(pane);
    return () => observer.disconnect();
  }, [logs, followLogs]);

  return (
    <div className="task-execution">
      <section className="task-pane">
        <header className="task-pane-head"><h3>执行阶段</h3></header>
        <div ref={stagePane} className="task-stage-scroll" tabIndex={0}
          role="region" aria-label="执行阶段列表">
          {stages.length ? (
            <ol className="task-stage-list">
              {stages.map((stage, index) => {
                const title = stage.title || stage.name;
                const isCurrent = runningIndex === index;
                const status = stage.status === "running" && !isCurrent ? "unknown" : stage.status;
                const display = stageStatus[status] || stageStatus.unknown;
                const detail = (isCurrent && task.currentCheck) || stage.detail || stage.message;
                return (
                  <li key={`${title}-${index}`} ref={focusedIndex === index ? currentRow : undefined}
                    className={`task-stage ${isCurrent ? "is-current" : ""}`}
                    aria-current={isCurrent ? "step" : undefined}
                    title={detail ? `${title}\n${detail}` : title}>
                    <strong className="task-stage-title">{title}</strong>
                    <div className={`task-stage-state ${status === "completed" ? "passed" : status}`}>
                      {isCurrent && <span className="task-spinner" aria-hidden="true" />}
                      {display.icon && <Icon name={display.icon} size={12} aria-hidden="true" />}
                      <span>{display.text}</span>
                    </div>
                  </li>
                );
              })}
            </ol>
          ) : <p className="task-pane-empty">{active ? "等待执行阶段" : "暂无阶段记录"}</p>}
        </div>
      </section>
      <section className="task-pane task-log-pane">
        <header className="task-pane-head">
          <h3>执行日志</h3>
          <Button small onClick={() => setFollowLogs(!followLogs)}>
            {followLogs ? "暂停跟随" : "跟随最新日志"}
          </Button>
        </header>
        {runningDetail && <p className="task-log-activity" title={runningDetail} aria-live="polite">{runningDetail}</p>}
        <div ref={logPane} className="task-log-scroll" tabIndex={0}
          role="region" aria-label="执行日志内容" onScroll={(event) => {
            const pane = event.currentTarget;
            if (followLogs && pane.scrollTop + 1 < lastLogTop.current &&
                pane.scrollHeight - pane.scrollTop - pane.clientHeight > 1) {
              setFollowLogs(false);
            }
            lastLogTop.current = pane.scrollTop;
          }}>
          {logs ? <pre>{logs}</pre> : <p className="task-pane-empty">{active ? "等待日志输出" : "暂无日志记录"}</p>}
        </div>
      </section>
    </div>
  );
}
