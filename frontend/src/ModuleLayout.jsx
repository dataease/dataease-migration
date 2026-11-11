import React from "react";
import "./module-layout.css";
export function PageHeading({ title, actions }) {
  return (
    <div className="page-heading">
      <h1>{title}</h1>
      {actions && <div className="page-heading-actions">{actions}</div>}
    </div>
  );
}
export function ModuleSummary({ items }) {
  return (
    <div className="module-summary">
      {items.map(({ label, value, tone }) => (
        <div
          className={"module-summary-item " + (tone ? "tone-" + tone : "")}
          key={label}
        >
          <span>{label}</span>
          <strong>{value}</strong>
        </div>
      ))}
    </div>
  );
}
export function ListHeading({ title, count }) {
  return (
    <div className="module-list-heading">
      <h2>{title}</h2>
      {count !== undefined && <span>{count}</span>}
    </div>
  );
}
export function TableRegion({ label, children }) {
  return (
    <div className="table-wrap" role="region" aria-label={label} tabIndex={0}>
      {children}
    </div>
  );
}
export function ListFooter({ count, unit = "条记录", children }) {
  return (
    <div className="table-footer">
      <span>
        共 {count} {unit}
      </span>
      {children && <span>{children}</span>}
    </div>
  );
}
