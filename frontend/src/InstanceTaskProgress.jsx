import React from "react";
import { Badge } from "./ui";
import { Button, taskInProgress, tone } from "./components";
import TaskStageList, { taskStageView } from "./TaskStageList";

export default function InstanceTaskProgress({ task, name, onOpen }) {
  const active = taskInProgress(task);
  const { stages, failure, focused } = taskStageView(task, active);
  const detail = active ? task.currentCheck || task.stageActivity
    : stages[failure]?.detail || (task.upgradeRecovered ? "已回退到升级前的版本和数据。" : task.error);
  const openLabel = task.status === "待验收" ? "继续验收"
    : task.state === "reconciliation_required" ? "查看核查要求" : "查看任务详情";
  return (
    <section className="instance-task-progress" aria-label="实例操作进度">
      <header className="task-pane-head">
        <div className="instance-task-title">
          <h3 title={name}>{name}</h3>
          <Badge tone={tone(task.status)}>{task.status}</Badge>
        </div>
        <Button small onClick={() => onOpen()}>{openLabel}</Button>
      </header>
      <TaskStageList stages={stages} focusedIndex={focused}
        label="实例操作执行阶段"
        empty={active ? "等待执行阶段" : "暂无阶段记录"}
        canSelect={(stage) => Boolean(stage.startedAt || stage.status === "failed")}
        onSelect={onOpen} />
      {detail && (
        <p className={`instance-task-note ${active ? "" : "is-failed"}`} title={detail}>
          {detail}
        </p>
      )}
    </section>
  );
}
