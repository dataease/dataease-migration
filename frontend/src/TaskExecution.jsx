import React, { useLayoutEffect, useMemo, useRef, useState } from "react";
import { Button, taskActivity } from "./components";
import TaskStageList, { taskStageView } from "./TaskStageList";
import { stageTitle, taskLogView } from "./taskLog";

const markerClass = { "完成": "is-complete", "失败": "is-failed", "状态待确认": "is-unknown" };

export default function TaskExecution({ task, active, initialStage }) {
  const logPane = useRef(null);
  const lastLogTop = useRef(0);
  const [logView, setLogView] = useState(() => Number.isInteger(initialStage) ? { stage: initialStage } : null);
  const { stages, failure: failureIndex, focused: focusedIndex } = taskStageView(task, active);
  const selectedIndex = logView?.stage ?? (logView === null && !active ? failureIndex : -1);
  const selected = stages[selectedIndex];
  const unlocatedError = !active && failureIndex < 0 ? task.error : "";
  const followLogs = selectedIndex < 0 && (logView?.follow ?? !unlocatedError);
  const { rows, anchors } = useMemo(() => taskLogView(task, task.stages || []), [task]);
  const selectedAnchor = anchors.get(selectedIndex);
  const selectedError = selected && (
    (["failed", "unknown", "interrupted"].includes(selected.status) && (selected.detail || selected.message))
    || (selectedIndex === failureIndex ? task.error : "")
  );
  const activity = active ? taskActivity(task) : null;
  const runningDetail = task.currentCheck ? activity?.title : activity?.detail;

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
  }, [rows, followLogs]);

  useLayoutEffect(() => {
    const pane = logPane.current;
    if (!pane || (selectedIndex < 0 && (!unlocatedError || logView))) return;
    const row = selectedAnchor && pane.querySelector(`[data-log-anchor="${selectedAnchor}"]`);
    const reveal = () => {
      pane.scrollTop = row ? row.offsetTop : 0;
      lastLogTop.current = pane.scrollTop;
    };
    reveal();
    const observer = new ResizeObserver(reveal);
    observer.observe(pane);
    return () => observer.disconnect();
  }, [selectedIndex, selectedAnchor, unlocatedError, logView]);

  return (
    <div className="task-execution">
      <section className="task-pane">
        <header className="task-pane-head"><h3>执行阶段</h3></header>
        <TaskStageList stages={stages}
          focusedIndex={focusedIndex}
          selectedIndex={selectedIndex}
          empty={active ? "等待执行阶段" : "暂无阶段记录"}
          canSelect={(stage, index) => Boolean(stage.startedAt || anchors.has(index) || stage.status === "failed")}
          onSelect={(index) => setLogView({ stage: index })} />
      </section>
      <section className="task-pane task-log-pane">
        <header className="task-pane-head">
          <h3>执行日志</h3>
          <Button small onClick={() => setLogView({ follow: !followLogs })}>
            {followLogs ? "暂停跟随" : "跟随最新日志"}
          </Button>
        </header>
        {selected ? <p className="task-log-activity" title={stageTitle(selected)}>{stageTitle(selected)}</p>
          : runningDetail && <p className="task-log-activity" title={runningDetail} aria-live="polite">{runningDetail}</p>}
        <div ref={logPane} className={`task-log-scroll ${selectedAnchor ? "has-anchor" : ""}`} tabIndex={0}
          role="region" aria-label="执行日志内容" onScroll={(event) => {
            const pane = event.currentTarget;
            if (followLogs && pane.scrollTop + 1 < lastLogTop.current &&
                pane.scrollHeight - pane.scrollTop - pane.clientHeight > 1) {
              setLogView({ follow: false });
            }
            lastLogTop.current = pane.scrollTop;
          }}>
          {unlocatedError && <p className="task-log-missing">
            <span className="task-log-error">失败原因 · {unlocatedError}</span>
          </p>}
          {selected && !selectedAnchor && <p className="task-log-missing">
            {selectedError && <span className="task-log-error">失败原因 · {selectedError}</span>}
            该阶段的起始日志不在保留记录中。
          </p>}
          {rows.length ? <pre>{rows.map((row) => <React.Fragment key={row.key}>
            <span data-log-anchor={row.key}
              className={`task-log-line ${row.kind ? `task-log-marker ${markerClass[row.kind] || ""}` : ""}${row.checkStatus ? ` task-log-check-${row.checkStatus}` : ""}`}>
              {row.text}
            </span>
            {row.key === selectedAnchor && selectedError &&
              <span className="task-log-line task-log-error">失败原因 · {selectedError}</span>}
          </React.Fragment>)}</pre> : <p className="task-pane-empty">{active ? "等待日志输出" : "暂无日志记录"}</p>}
        </div>
      </section>
    </div>
  );
}
