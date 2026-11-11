import React, { useId, useRef, useState } from "react";
import { Button, Icon } from "./ui";
import { PageHeading } from "./ModuleLayout";
import "./migration-overview.css";

const sections = ["迁移范围", "开始前确认"];
const scope = [
  ["database", "元数据库与业务配置", "表结构、数据和权限配置"],
  ["folder", "文件与资源", "上传文件、地图、字体和外观"],
  ["settings", "插件与同步管理", "转换支持的插件与同步任务，历史日志可选"],
];
const prerequisites = [
  "选择已安装的 V3 目标实例",
  "核对数据库、目录和容器归属",
  "确认两端停服影响与目标覆盖范围",
  "平台先停服和保护备份，再执行迁移",
];

export default function MigrationOverview({ onStart }) {
  const [active, setActive] = useState(0);
  const id = useId();
  const tabs = useRef([]);
  const changeTab = (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const next =
      event.key === "Home" ? 0 : event.key === "End" ? 1 : 1 - active;
    setActive(next);
    tabs.current[next]?.focus();
  };
  return (
    <>
      <PageHeading
        title="版本迁移"
        actions={
          <Button primary icon="migrate" onClick={onStart}>
            新建迁移任务
          </Button>
        }
      />
      <section className="panel migration-workbench">
        <div className="migration-route">
          <div className="migration-endpoint">
            <span className="migration-version">V2</span>
            <div>
              <strong>源实例</strong>
              <small>v2.10.26 及以上 · MySQL</small>
            </div>
          </div>
          <div className="migration-direction">
            <Icon name="migrate" size={24} />
            <span>同机 / 跨服务器</span>
          </div>
          <div className="migration-endpoint target">
            <span className="migration-version">V3</span>
            <div>
              <strong>目标实例</strong>
              <small>独立目录与数据库</small>
            </div>
          </div>
        </div>
        <div
          className="migration-mobile-tabs"
          role="tablist"
          aria-label="迁移说明"
        >
          {sections.map((title, index) => (
            <button
              key={title}
              ref={(element) => {
                tabs.current[index] = element;
              }}
              id={`${id}-tab-${index}`}
              type="button"
              role="tab"
              aria-controls={`${id}-panel-${index}`}
              aria-selected={active === index}
              tabIndex={active === index ? 0 : -1}
              className={active === index ? "selected" : ""}
              onClick={() => setActive(index)}
              onKeyDown={changeTab}
            >
              {title}
            </button>
          ))}
        </div>
        <div className="migration-detail-grid">
          <section
            className="migration-detail"
            role="tabpanel"
            tabIndex={0}
            data-active={active === 0}
            id={`${id}-panel-0`}
            aria-labelledby={`${id}-title-0`}
          >
            <h2 id={`${id}-title-0`}>迁移范围</h2>
            <div className="migration-scope">
              {scope.map(([icon, title, detail]) => (
                <div key={title}>
                  <span className="feature-icon">
                    <Icon name={icon} />
                  </span>
                  <div>
                    <h3>{title}</h3>
                    <p>{detail}</p>
                  </div>
                </div>
              ))}
            </div>
          </section>
          <section
            className="migration-detail"
            role="tabpanel"
            tabIndex={0}
            data-active={active === 1}
            id={`${id}-panel-1`}
            aria-labelledby={`${id}-title-1`}
          >
            <h2 id={`${id}-title-1`}>开始前确认</h2>
            <ol className="migration-prerequisites">
              {prerequisites.map((text, index) => (
                <li key={text}>
                  <span>{index + 1}</span>
                  {text}
                </li>
              ))}
            </ol>
          </section>
        </div>
        <div className="notice amber migration-impact">
          <Icon name="warning" size={17} />
          <span>目标数据库将被删除并重建，失败不会自动回滚。</span>
        </div>
      </section>
    </>
  );
}
