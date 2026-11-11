import React, { useEffect, useRef, useState } from "react";
import { Button, Field, Select, Facts, Modal, Notice } from "./components";
import { Badge } from "./ui";
import { ListFooter, TableRegion } from "./ModuleLayout";
import { api, post } from "./api";
import "./package-downloads.css";

const labels = { queued: "排队中", checking: "检查下载源", downloading: "下载中", pausing: "暂停中", paused: "已暂停", cancelling: "取消中", cancelled: "已取消", verifying: "校验入库", completed: "已入库", failed: "失败" };
const active = (item) => ["queued", "checking", "downloading", "pausing", "cancelling", "verifying"].includes(item.state);
const kindName = (kind) => kind === "plugin" ? "插件包" : "安装 / 升级包";
const bytes = (value) => {
  if (!Number.isFinite(Number(value)) || Number(value) < 0) return "未知大小";
  let n = Number(value), unit = 0;
  while (n >= 1024 && unit < 4) { n /= 1024; unit++; }
  return `${unit ? n.toFixed(1) : Math.round(n)} ${["B", "KB", "MB", "GB", "TB"][unit]}`;
};
const speed = (item) => item.state === "downloading" ? `${bytes(item.speedBytesPerSecond || 0)}/s` : "—";
const remaining = (item) => {
  if (item.state !== "downloading" || item.totalBytes <= 0 || item.speedBytesPerSecond <= 0) return "—";
  const s = Math.max(0, Math.ceil((item.totalBytes - item.receivedBytes) / item.speedBytesPerSecond));
  return s >= 3600 ? `约 ${Math.ceil(s / 3600)} 小时` : s >= 60 ? `约 ${Math.ceil(s / 60)} 分钟` : `约 ${s} 秒`;
};
function Progress({ item }) {
  const percent = item.totalBytes > 0 ? Math.min(100, Math.floor((item.receivedBytes || 0) * 100 / item.totalBytes)) : null;
  return <div className="pd-progress">
    <div className="pd-progress-label"><span>{bytes(item.receivedBytes || 0)} / {bytes(item.totalBytes)}</span><span>{percent === null ? "" : `${percent}%`}</span></div>
    {percent !== null && <div className="pd-progress-track" role="progressbar" aria-label="已下载字节比例" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}><span style={{ width: `${percent}%` }} /></div>}
  </div>;
}
export function DownloadRows({ items, onOpen, query, filter }) {
  const rows = items.filter((item) => (!query || [item.fileName, item.sourceLabel, item.sourceHost, item.id].some((v) => String(v || "").toLowerCase().includes(query.toLowerCase()))) && (filter === "全部" || labels[item.state] === filter));
  return <>
    <TableRegion label="下载任务"><table className="pd-table"><thead><tr><th>下载文件 / 来源</th><th>类型</th><th>下载进度</th><th>状态</th><th>速度 / 剩余时间</th><th>操作</th></tr></thead><tbody>
      {rows.map((item) => <tr key={item.id}>
        <td><strong>{item.fileName || (item.sourceLabel !== item.sourceHost ? item.sourceLabel : "未识别文件")}</strong><small>{item.sourceHost}</small></td>
        <td>{kindName(item.kind)}</td><td><Progress item={item} /></td>
        <td><Badge tone={item.state === "completed" ? "green" : item.state === "failed" ? "red" : active(item) ? "blue" : "gray"}>{labels[item.state]}</Badge><small>{item.usedThreads ? `${item.usedThreads} 线程` : "等待检查"}</small></td>
        <td>{speed(item)}<small>{remaining(item)}</small></td>
        <td><button className="link" onClick={() => onOpen(item)}>查看详情</button></td>
      </tr>)}
    </tbody></table>{!rows.length && <div className="pd-empty">暂无下载任务</div>}</TableRegion>
    <ListFooter count={rows.length}>关闭页面不影响后台下载</ListFooter>
  </>;
}
export function OnlinePackageDialog({ defaultKind, onClose, onCreated }) {
  const [source, setSource] = useState(defaultKind === "plugin" ? "market" : "url"), [kind, setKind] = useState(defaultKind),
    [url, setUrl] = useState(""), [fileName, setFileName] = useState(""), [sha256, setSha] = useState(""), [threads, setThreads] = useState("4"),
    [catalogue, setCatalogue] = useState(null), [loading, setLoading] = useState(false), [search, setSearch] = useState(""),
    [appId, setAppId] = useState(""), [versionId, setVersionId] = useState(""), [error, setError] = useState(""), [submitting, setSubmitting] = useState(false);
  const generation = useRef(0);
  const load = async () => {
    const seq = ++generation.current; setLoading(true); setError("");
    try { const list = await api("/marketplace/plugins"); if (seq === generation.current) setCatalogue(list); }
    catch (failure) { if (seq === generation.current) setError(failure.message); }
    finally { if (seq === generation.current) setLoading(false); }
  };
  useEffect(() => { if (source === "market" && catalogue === null) load(); return () => { generation.current++; }; }, [source]);
  const selected = catalogue?.find((item) => item.id === appId), version = selected?.versions.find((item) => item.id === versionId);
  const note = version ? version.notes : selected?.notes;
  const notes = typeof note === "string" ? note.trim() : "";
  const submit = async () => {
    setSubmitting(true); setError("");
    try { await onCreated(await post("/downloads", source === "market" ? { source, appId, versionId, threads: Number(threads) } : { source, kind, url, fileName, sha256, threads: Number(threads) })); }
    catch (failure) { setError(failure.message); }
    finally { setSubmitting(false); }
  };
  return <Modal title="在线获取软件包" busy={submitting} onClose={onClose} actions={<><Button disabled={submitting} onClick={onClose}>取消</Button><Button primary disabled={submitting || (source === "market" ? !version : !url.trim())} onClick={submit}>{submitting ? "创建中…" : "加入下载任务"}</Button></>}>
    <div className="tabs pd-source-tabs">{[["url", "下载地址"], ["market", "应用市场插件"]].map(([value, label]) => <button key={value} className={source === value ? "selected" : ""} onClick={() => { setSource(value); setError(""); }}>{label}</button>)}</div>
    {error && <Notice tone="red">{error}</Notice>}
    {source === "url" ? <>
      <Field label="软件包类型"><Select aria-label="软件包类型" value={kind} onChange={(e) => setKind(e.target.value)}><option value="package">安装 / 升级包</option><option value="plugin">插件包</option></Select></Field>
      <Field label="下载地址" hint="填写文件的直接下载 URL，支持授权后的临时链接。" type="url" value={url} autoComplete="off" spellCheck={false} onChange={(e) => setUrl(e.target.value)} placeholder="https://…/dataease-offline.tar.gz" />
      <Field label="文件名（可选）" hint="默认从下载源识别；没有文件名的链接请手工填写。" value={fileName} onChange={(e) => setFileName(e.target.value)} autoComplete="off" />
      <details className="pd-options"><summary>校验与下载设置</summary>
        <Field label="SHA-256（可选）" hint="填写提供方给出的摘要，下载后将额外核对文件完整性。" value={sha256} maxLength={64} onChange={(e) => setSha(e.target.value)} autoComplete="off" />
        <Field label="下载线程"><Select aria-label="下载线程" value={threads} onChange={(e) => setThreads(e.target.value)}>{[1, 2, 4, 8].map((n) => <option key={n} value={String(n)}>{n} 线程</option>)}</Select></Field>
      </details>
      <Notice>服务器支持时使用多线程和断点续传，否则单线程下载。下载完成后校验、去重入库，不自动安装。</Notice>
    </> : <>
      <div className="pd-market-toolbar"><Field label="搜索插件" value={search} onChange={(e) => { setSearch(e.target.value); setAppId(""); setVersionId(""); }} placeholder="搜索名称或说明" /><Button disabled={loading} onClick={load}>{loading ? "读取中…" : "刷新市场"}</Button></div>
      <Field label="选择插件"><Select aria-label="选择插件" value={appId} onChange={(e) => { const id = e.target.value; setAppId(id); setVersionId(catalogue?.find((v) => v.id === id)?.versions[0]?.id || ""); }}>
        <option value="">{loading ? "正在读取应用市场" : "请选择插件"}</option>
        {(catalogue || []).filter((item) => `${item.name} ${item.description}`.toLowerCase().includes(search.toLowerCase())).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
      </Select></Field>
      <Field label="插件版本"><Select aria-label="插件版本" value={versionId} onChange={(e) => setVersionId(e.target.value)} disabled={!selected}><option value="">请选择版本</option>{selected?.versions.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}</Select></Field>
      {notes && notes.toLowerCase() !== "null" && <p className="pd-market-notes">{notes}</p>}
      <Notice>来源：apps.fit2cloud.com。实际版本与兼容性以入库校验为准；企业插件的安装和使用仍需有效授权。</Notice>
    </>}
  </Modal>;
}
export function DownloadDetailDialog({ item, onClose, onRefresh, onMedia }) {
  const [waiting, setWaiting] = useState(false), [error, setError] = useState(""), [updating, setUpdating] = useState(false), [url, setUrl] = useState(""), [confirm, setConfirm] = useState("");
  const action = async (name) => {
    setWaiting(true); setError("");
    try {
      if (name === "remove") { await api(`/downloads/${item.id}`, { method: "DELETE" }); await onRefresh(); onClose(); }
      else { await post(`/downloads/${item.id}/actions`, { action: name, ...(updating && url.trim() ? { url } : {}) }); setUpdating(false); setUrl(""); setConfirm(""); await onRefresh(); }
    } catch (failure) { setError(failure.message); }
    finally { setWaiting(false); }
  };
  const stoppable = ["queued", "checking", "downloading"].includes(item.state), resumable = ["paused", "failed"].includes(item.state);
  const confirmation = { cancel: ["取消下载并删除临时文件？", "确认取消"], restart: ["重新下载将清除当前断点，从头获取文件。", "确认重新下载"], remove: ["移除下载记录及断点文件？已入库的软件包会保留。", "确认移除"] }[confirm];
  return <Modal title="下载任务详情" busy={waiting} onClose={onClose} actions={confirmation ? <><Button disabled={waiting} onClick={() => setConfirm("")}>返回</Button><Button primary disabled={waiting} onClick={() => action(confirm)}>{confirmation[1]}</Button></> : <>
    <Button disabled={waiting} onClick={onClose}>关闭</Button>
    {stoppable && <><Button disabled={waiting} onClick={() => action("pause")}>暂停下载</Button><Button disabled={waiting} onClick={() => setConfirm("cancel")}>取消下载</Button></>}
    {resumable && <><Button disabled={waiting} onClick={() => setUpdating(!updating)}>更新下载地址</Button>{!item.restartRequired && <Button primary disabled={waiting || (updating && !url.trim())} onClick={() => action("resume")}>{item.downloadComplete ? "重试校验入库" : "继续下载"}</Button>}</>}
    {!active(item) && item.state !== "completed" && <Button disabled={waiting} onClick={() => setConfirm("restart")}>重新下载</Button>}
    {!active(item) && <Button disabled={waiting} onClick={() => setConfirm("remove")}>移除记录</Button>}
    {item.state === "completed" && <Button primary onClick={() => onMedia(item.mediaId)}>查看软件包</Button>}
  </>}>
    {confirmation && <Notice tone="amber">{confirmation[0]}</Notice>}
    <Facts rows={[["文件", item.fileName || "尚未识别"], ["类型", kindName(item.kind)], ["来源", item.sourceHost], ["状态", labels[item.state]], ["下载线程", item.usedThreads ? `${item.usedThreads} / 请求 ${item.threads}` : "等待检查"]]} />
    <Progress item={item} />
    <Facts rows={[["当前速度", speed(item)], ["预计剩余", remaining(item)], ["SHA-256", item.sha256 || item.expectedSha256 || "校验完成后显示"]]} />
    {item.transferNote && <Notice>{item.transferNote}</Notice>}
    {item.state === "completed" && <Notice>已完成下载并入库。软件包状态：{item.mediaStatus}。</Notice>}
    {item.error && <Notice tone="red">{item.error}</Notice>}
    {item.cleanupPending && <Notice tone="amber">临时文件未完全清理，可移除下载记录后重试清理。</Notice>}
    {error && <Notice tone="red">{error}</Notice>}
    {updating && <Field label="新的下载地址" type="url" autoFocus autoComplete="off" value={url} onChange={(e) => setUrl(e.target.value)} hint="继续时会重新核对文件身份；源文件变化后须重新下载。" />}
  </Modal>;
}
