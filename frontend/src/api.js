let csrfToken = "";
export async function api(path, options = {}) {
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
  const contentType = response.headers.get("content-type") || "";
  const value = contentType.includes("application/json")
    ? await response.json()
    : await response.text();
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("session-expired"));
    throw new Error(value?.message || `请求失败（${response.status}）`);
  }
  if (value?.csrfToken !== undefined) csrfToken = value.csrfToken;
  return value;
}
export const put = (path, body) => api(path, { method: "PUT", body });
export const post = (path, body = {}) => api(path, { method: "POST", body });
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
