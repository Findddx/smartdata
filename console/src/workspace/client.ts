import type {
  SourceDraft,
  WorkspaceState,
  WorkspaceReport,
  BusinessBriefCommand,
  BusinessBrief,
  WorkspaceLineage,
  LineageMappings,
  SaveLineageMappingsCommand,
  LineageFieldPage,
} from "./types";
import { localRolesEnabled } from "./localRoles";

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
  if (localRolesEnabled())
    headers["X-Demo-Actor"] =
      localStorage.getItem("smartdata.localActor") ?? "admin";
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    headers["Idempotency-Key"] = crypto.randomUUID();
  }
  const signal = AbortSignal.timeout(body === undefined ? 10_000 : 45_000);
  try {
    const response = await fetch(`${ROOT}${path}`, {
      method: body === undefined ? "GET" : "POST",
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
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
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof WorkspaceApiError) throw error;
    const timedOut =
      signal.aborted ||
      (typeof error === "object" && error !== null && "name" in error && error.name === "TimeoutError");
    throw new WorkspaceApiError(
      timedOut
        ? body === undefined
          ? "读取工作区超时。请检查服务与网络连接，再点击重试。"
          : "请求等待超时，结果尚不能确认。请先刷新记录核对，再决定是否重试。"
        : "无法读取工作区服务。请检查连接后重试；如果刚提交了修改，请先刷新记录确认结果。",
      0,
    );
  }
}
export const workspaceClient = {
  lineage: () => request<WorkspaceLineage>("/lineage"),
  lineageFields: (sourceId: string, assetId: string, offset = 0, limit = 128) =>
    request<LineageFieldPage>(
      `/lineage/sources/${encodeURIComponent(sourceId)}/assets/${encodeURIComponent(assetId)}/fields?offset=${offset}&limit=${limit}`,
    ),
  lineageMappings: () => request<LineageMappings>("/lineage/mappings"),
  saveLineageMappings: (body: SaveLineageMappingsCommand) =>
    request<WorkspaceState>("/lineage/mappings", body),
  saveBusinessBrief: (body: BusinessBriefCommand) =>
    request<WorkspaceState>("/business-brief", body),
  confirmMetric: (
    id: string,
    expectedStateVersion: number,
    expectedBriefVersion: number,
    expectedMetricVersion: number,
  ) =>
    request<WorkspaceState>(
      `/business-brief/metrics/${encodeURIComponent(id)}/confirm`,
      { expectedStateVersion, expectedBriefVersion, expectedMetricVersion },
    ),
  businessBriefHistory: () =>
    request<BusinessBrief[]>("/business-brief/history"),
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
