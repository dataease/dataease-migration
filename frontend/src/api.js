let csrfToken = "";
async function request(path, options = {}) {
  const headers = { ...options.headers };
  if (options.body && !(options.body instanceof FormData))
    headers["Content-Type"] = "application/json";
  if (options.method && !["GET", "HEAD"].includes(options.method))
    headers["X-CSRF-Token"] = csrfToken;
  const response = await fetch("/api/ops" + path, {
    ...options,
    headers,
    credentials: "same-origin",
    body:
      options.body instanceof FormData
        ? options.body
        : options.body === undefined
          ? undefined
          : JSON.stringify(options.body),
  });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") || "";
    const value = contentType.includes("application/json")
      ? await response.json()
      : await response.text();
    if (response.status === 401)
      window.dispatchEvent(new Event("session-expired"));
    throw new Error(value?.message || `请求失败（${response.status}）`);
  }
  return response;
}
export async function api(path, options = {}) {
  const response = await request(path, options);
  const contentType = response.headers.get("content-type") || "";
  const value = contentType.includes("application/json")
    ? await response.json()
    : await response.text();
  if (value?.csrfToken !== undefined) csrfToken = value.csrfToken;
  return value;
}
export const put = (path, body) => api(path, { method: "PUT", body });
export const post = (path, body = {}) => api(path, { method: "POST", body });
export function uploadFile(path, body, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/ops" + path);
    xhr.responseType = "json";
    xhr.setRequestHeader("X-CSRF-Token", csrfToken);
    xhr.upload.onprogress = (event) => {
      onProgress({
        phase: "uploading",
        percent: event.lengthComputable && event.total > 0
          ? Math.min(100, Math.floor(event.loaded * 100 / event.total)) : null,
      });
    };
    xhr.upload.onload = () => onProgress({ phase: "awaiting-validation" });
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300 && xhr.response) resolve(xhr.response);
      else {
        if (xhr.status === 401) window.dispatchEvent(new Event("session-expired"));
        reject(new Error(xhr.response?.message || `请求失败（${xhr.status}）`));
      }
    };
    xhr.onerror = () => reject(new Error("上传连接中断，请核对软件包或备份列表。"));
    xhr.send(body);
  });
}
export async function pollTaskSubmission(body, onUpdate, signal) {
  const connectionLost = () => new Error("暂时无法确认提交结果，请先在任务中心核对。");
  let snapshot;
  try {
    snapshot = await api("/task-submissions", { method: "POST", body, signal });
  } catch (error) {
    throw error instanceof TypeError ? connectionLost() : error;
  }
  const path = `/task-submissions/${encodeURIComponent(snapshot.id)}`;
  let failures = 0;
  for (;;) {
    onUpdate(snapshot);
    if (snapshot.state === "completed") return snapshot.submission;
    if (snapshot.state === "failed") throw new Error(snapshot.error);
    await new Promise((resolve) => setTimeout(resolve, 500));
    if (signal.aborted) return null;
    try {
      snapshot = await api(path, { cache: "no-store", signal });
      failures = 0;
    } catch (error) {
      if (signal.aborted) return null;
      if (!(error instanceof TypeError)) throw error;
      if (++failures >= 5) throw connectionLost();
      snapshot = { ...snapshot, state: "reconnecting" };
    }
  }
}
export function exportJson(filename, value) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
export async function readPrivateKey(file) {
  if (!file) return "";
  if (file.size > 128 * 1024) throw new Error("私钥文件不能超过 128 KB");
  return file.text();
}
