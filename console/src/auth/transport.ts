import { identityScope, type MeView } from "./types";

let current: MeView | null = null;
let generation = 0;
export const AUTHORITY_CHANGED = "smartdata:authority-changed";
export function configureSession(me: MeView | null) {
  if (
    (current === null) !== (me === null) ||
    (current && me && identityScope(current) !== identityScope(me))
  )
    generation++;
  current = me;
}
export function sessionGeneration() {
  return generation;
}
export function currentSession() {
  return current;
}
export function notifyAuthorityChanged(status: number) {
  if (status === 401 || status === 403)
    window.dispatchEvent(new CustomEvent(AUTHORITY_CHANGED));
}
export function sessionHeaders(command = false): Record<string, string> {
  const headers: Record<string, string> = {};
  if (
    current?.mode === "LOCAL_DEMO" &&
    current.capabilities.localRoleSwitch &&
    current.accountId
  )
    headers["X-Demo-Actor"] = current.accountId;
  if (command && current?.mode === "ENTERPRISE") {
    if (!current.csrf)
      throw new Error("当前会话缺少安全校验信息，请重新确认登录状态。");
    headers[current.csrf.headerName] = current.csrf.token;
  }
  return headers;
}
