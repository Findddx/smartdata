import type { SourceDraft, WorkspaceState, WorkspaceReport } from "./types";

const ROOT = "/api/v1/workspace";
export class WorkspaceApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "WorkspaceApiError";
  }
}
async function request<T>(path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (import.meta.env.DEV || import.meta.env.VITE_DEMO_AUTH === "true")
    headers["X-Demo-Actor"] =
      localStorage.getItem("smartdata.localActor") ?? "admin";
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    headers["Idempotency-Key"] = crypto.randomUUID();
  }
  const response = await fetch(`${ROOT}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(body === undefined ? 10_000 : 45_000),
    credentials: "same-origin",
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as {
      detail?: string;
    };
    throw new WorkspaceApiError(
      problem.detail ?? `请求未完成（${response.status}）`,
      response.status,
    );
  }
  return response.json() as Promise<T>;
}
export const workspaceClient = {
  get: () => request<WorkspaceState>(""),
  bootstrap: (version: number) =>
    request<WorkspaceState>("/demo/bootstrap", {
      expectedStateVersion: version,
    }),
  resetDemo: (version: number) =>
    request<WorkspaceState>("/demo/reset", { expectedStateVersion: version }),
  addSource: (draft: SourceDraft) => request<WorkspaceState>("/sources", draft),
  replaceSnapshot: (
    id: string,
    version: number,
    rows: Record<string, unknown>[],
    observedAt: string,
  ) =>
    request<WorkspaceState>(`/sources/${encodeURIComponent(id)}/snapshot`, {
      expectedStateVersion: version,
      snapshotRows: rows,
      observedAt,
    }),
  scan: (id: string, version: number) =>
    request<WorkspaceState>(`/sources/${encodeURIComponent(id)}/scan`, {
      expectedStateVersion: version,
    }),
  disconnect: (id: string, version: number) =>
    request<WorkspaceState>(`/sources/${encodeURIComponent(id)}/disconnect`, {
      expectedStateVersion: version,
    }),
  provision: (version: number) =>
    request<WorkspaceState>("/team/provision", {
      expectedStateVersion: version,
    }),
  transition: (
    id: string,
    version: number,
    status: string,
    note: string,
    evidenceRefs: string[],
  ) =>
    request<WorkspaceState>(`/tasks/${encodeURIComponent(id)}/transition`, {
      expectedStateVersion: version,
      status,
      note,
      evidenceRefs,
    }),
  comment: (
    id: string,
    version: number,
    body: string,
    evidenceRefs: string[],
  ) =>
    request<WorkspaceState>(`/tasks/${encodeURIComponent(id)}/comments`, {
      expectedStateVersion: version,
      body,
      evidenceRefs,
    }),
  ask: (id: string, version: number, question: string) =>
    request<WorkspaceState>(`/assistants/${encodeURIComponent(id)}/messages`, {
      expectedStateVersion: version,
      question,
    }),
  report: () => request<WorkspaceReport>("/report"),
};
