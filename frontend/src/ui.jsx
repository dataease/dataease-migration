import React, {
  useState,
  useRef,
  useLayoutEffect,
  useId,
} from "react";
import { createPortal } from "react-dom";
import {
  SquaresFour,
  Database,
  Clock,
  DownloadSimple,
  UploadSimple,
  ArrowsClockwise,
  Copy,
  Folder,
  Gear,
  DesktopTower,
  ListChecks,
  Terminal,
  X,
  Plus,
  MagnifyingGlass,
  CaretRight,
  Warning,
  Info,
  ArrowsLeftRight,
  Check,
  ShieldCheck,
} from "@phosphor-icons/react";
const icons = {
  overview: SquaresFour,
  database: Database,
  clock: Clock,
  download: DownloadSimple,
  upload: UploadSimple,
  refresh: ArrowsClockwise,
  copy: Copy,
  folder: Folder,
  settings: Gear,
  server: DesktopTower,
  tasks: ListChecks,
  logs: Terminal,
  close: X,
  add: Plus,
  search: MagnifyingGlass,
  arrow: CaretRight,
  warning: Warning,
  info: Info,
  migrate: ArrowsLeftRight,
  check: Check,
  shield: ShieldCheck,
};
export function Icon({ name = "info", size = 18, ...props }) {
  const Component = icons[name] || Info;
  return (
    <Component
      aria-hidden="true"
      className="icon"
      size={size}
      weight="regular"
      {...props}
    />
  );
}
export function Badge({ children, tone = "green" }) {
  return (
    <span className={`badge ${tone}`}>
      <i />
      {children}
    </span>
  );
}
export function Button({
  children,
  icon,
  onClick,
  primary = false,
  small = false,
  ...props
}) {
  return (
    <button
      onClick={onClick}
      className={`button ${primary ? "primary" : ""} ${small ? "small" : ""}`}
      {...props}
    >
      {icon && <Icon name={icon} size={16} />} {children}
    </button>
  );
}
export function Empty({ text = "没有符合条件的记录" }) {
  return (
    <div className="empty">
      <Icon name="folder" size={36} />
      <strong>{text}</strong>
    </div>
  );
}
const dialogStack = [];
let pendingDialogReturnTarget = null;
const dialogFocusable =
  'a[href],button,input:not([type="hidden"]),textarea,select,[tabindex]';
const visibleFocusable = (element) =>
  !element.disabled &&
  element.tabIndex >= 0 &&
  !element.closest("[hidden],[inert]") &&
  element.getClientRects().length > 0;
export function useDialogFocus({
  onClose,
  enabled = true,
  initialFocusRef,
} = {}) {
  const dialogRef = useRef(null),
    closeRef = useRef(onClose),
    initialRef = useRef(initialFocusRef),
    openerRef = useRef(null);
  closeRef.current = onClose;
  initialRef.current = initialFocusRef;
  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!enabled || !dialog) return;
    const activeBeforeOpen = document.activeElement;
    if (
      activeBeforeOpen &&
      activeBeforeOpen !== document.body &&
      !dialog.contains(activeBeforeOpen)
    )
      openerRef.current = activeBeforeOpen;
    // StrictMode replays layout effects after the first focus has entered the dialog.
    // Keep the external opener instead of capturing that dialog input on the replay.
    const previous = dialog.contains(activeBeforeOpen)
      ? openerRef.current || pendingDialogReturnTarget
      : (!activeBeforeOpen || activeBeforeOpen === document.body) &&
          pendingDialogReturnTarget?.isConnected
        ? pendingDialogReturnTarget
        : activeBeforeOpen;
    const focusable = () =>
      Array.from(dialog.querySelectorAll(dialogFocusable)).filter(
        visibleFocusable,
      );
    const focusFirst = () => {
      const initial =
        initialRef.current?.current ||
        dialog.querySelector("[data-dialog-autofocus]");
      const target =
        initial && visibleFocusable(initial)
          ? initial
          : focusable()[0] || dialog;
      target.focus({ preventScroll: true });
    };
    const entry = { dialog, focusFirst };
    dialogStack.push(entry);
    const isTop = () => dialogStack[dialogStack.length - 1] === entry;
    const key = (e) => {
      if (!isTop() || e.defaultPrevented) return;
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        closeRef.current?.();
        return;
      }
      if (e.key !== "Tab") return;
      const items = focusable(),
        first = items[0],
        last = items[items.length - 1],
        active = document.activeElement;
      if (!items.length) {
        e.preventDefault();
        dialog.focus({ preventScroll: true });
        return;
      }
      if (
        !dialog.contains(active) ||
        active === dialog ||
        (e.shiftKey && active === first) ||
        (!e.shiftKey && active === last)
      ) {
        e.preventDefault();
        (e.shiftKey ? last : first).focus({ preventScroll: true });
      }
    };
    const keepInside = (e) => {
      if (isTop() && !dialog.contains(e.target)) focusFirst();
    };
    document.addEventListener("keydown", key);
    document.addEventListener("focusin", keepInside);
    focusFirst();
    return () => {
      document.removeEventListener("keydown", key);
      document.removeEventListener("focusin", keepInside);
      const index = dialogStack.indexOf(entry);
      if (index >= 0) dialogStack.splice(index, 1);
      pendingDialogReturnTarget = previous;
      queueMicrotask(() => {
        const top = dialogStack[dialogStack.length - 1];
        if (
          previous?.isConnected &&
          !previous.disabled &&
          (!top || top.dialog.contains(previous))
        )
          previous.focus({ preventScroll: true });
        else if (top && !top.dialog.contains(document.activeElement))
          top.focusFirst();
        if (pendingDialogReturnTarget === previous)
          pendingDialogReturnTarget = null;
      });
    };
  }, [enabled]);
  return dialogRef;
}
export function Select({
  children,
  value,
  defaultValue,
  onChange,
  disabled = false,
  className = "",
  id,
  ...props
}) {
  const options = React.Children.toArray(children)
    .filter(React.isValidElement)
    .map((c) => ({
      value: String(c.props.value ?? c.props.children),
      label: c.props.children,
      disabled: c.props.disabled,
    }));
  const [internal, setInternal] = useState(
      String(defaultValue ?? options[0]?.value ?? ""),
    ),
    [open, setOpen] = useState(false),
    [focus, setFocus] = useState(0),
    [position, setPosition] = useState({});
  const current = String(value ?? internal),
    selected = options.find((o) => o.value === current),
    button = useRef(null),
    popup = useRef(null),
    uid = useId();
  const choose = (o) => {
    if (!o || o.disabled) return;
    setInternal(o.value);
    onChange?.({
      target: { value: o.value },
      currentTarget: { value: o.value },
    });
    setOpen(false);
    button.current?.focus();
  };
  const show = () => {
    if (disabled) return;
    setFocus(
      Math.max(
        0,
        options.findIndex((o) => o.value === current),
      ),
    );
    setOpen(true);
  };
  useLayoutEffect(() => {
    if (!open) return;
    const update = () => {
      const trigger = button.current;
      if (!trigger) return;
      const r = trigger.getBoundingClientRect();
      const gutter = 8;
      const gap = 6;
      const width = Math.min(Math.max(r.width, 180), window.innerWidth - 2 * gutter);
      const below = window.innerHeight - r.bottom - gap - gutter;
      const above = r.top - gap - gutter;
      const openBelow = below >= 180 || below >= above;
      setPosition({
        left: Math.max(gutter, Math.min(r.left, window.innerWidth - width - gutter)),
        width,
        maxHeight: Math.min(270, Math.max(0, openBelow ? below : above)),
        ...(openBelow
          ? { top: r.bottom + gap }
          : { bottom: window.innerHeight - r.top + gap }),
      });
    };
    update();
    const outside = (e) => {
      if (
        !button.current?.contains(e.target) &&
        !popup.current?.contains(e.target)
      )
        setOpen(false);
    };
    document.addEventListener("mousedown", outside);
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    return () => {
      document.removeEventListener("mousedown", outside);
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
    };
  }, [open]);
  const key = (e) => {
    if (e.key === "Escape" && open) {
      e.preventDefault();
      e.stopPropagation();
      setOpen(false);
    } else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(e.key)) {
      e.preventDefault();
      if (!open) {
        show();
        return;
      }
      let next =
        e.key === "Home"
          ? 0
          : e.key === "End"
            ? options.length - 1
            : (focus + (e.key === "ArrowDown" ? 1 : -1) + options.length) %
              options.length;
      setFocus(next);
      popup.current?.children[next]?.scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      open ? choose(options[focus]) : show();
    } else if (e.key === "Tab") {
      setOpen(false);
    }
  };
  return (
    <>
      <button
        {...props}
        id={id}
        type="button"
        ref={button}
        disabled={disabled}
        className={`custom-select ${open ? "is-open" : ""} ${className}`}
        role="combobox"
        aria-expanded={open}
        aria-controls={open ? uid : undefined}
        aria-haspopup="listbox"
        aria-activedescendant={open ? `${uid}-${focus}` : undefined}
        onClick={() => (open ? setOpen(false) : show())}
        onKeyDown={key}
      >
        <span>{selected?.label ?? "请选择"}</span>
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path
            d="m3 4.5 3 3 3-3"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.4"
          />
        </svg>
      </button>
      {open &&
        createPortal(
          <div
            id={uid}
            role="listbox"
            aria-label={props["aria-label"]}
            ref={popup}
            className="custom-options"
            style={position}
          >
            {options.map((o, i) => (
              <button
                type="button"
                role="option"
                tabIndex={-1}
                id={`${uid}-${i}`}
                key={`${o.value}-${i}`}
                aria-selected={o.value === current}
                aria-disabled={!!o.disabled}
                className={`${o.value === current ? "selected" : ""} ${focus === i ? "focused" : ""}`}
                onMouseEnter={() => setFocus(i)}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => choose(o)}
              >
                {o.label}
                {o.value === current && <Icon name="check" size={14} />}
              </button>
            ))}
          </div>,
          document.body,
        )}
    </>
  );
}

export function TimeSelect({ value = "02:00", onChange, label = "执行时间" }) {
  const [hour, minute] = value.split(":");
  return (
    <span className="time-select">
      <Select
        aria-label={`${label}小时`}
        value={hour}
        onChange={(e) =>
          onChange({ target: { value: `${e.target.value}:${minute}` } })
        }
      >
        {Array.from({ length: 24 }, (_, i) => (
          <option key={i}>{String(i).padStart(2, "0")}</option>
        ))}
      </Select>
      <span>:</span>
      <Select
        aria-label={`${label}分钟`}
        value={minute}
        onChange={(e) =>
          onChange({ target: { value: `${hour}:${e.target.value}` } })
        }
      >
        {Array.from({ length: 60 }, (_, i) => (
          <option key={i}>{String(i).padStart(2, "0")}</option>
        ))}
      </Select>
    </span>
  );
}
