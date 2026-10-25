import {
  currentSession,
  notifyAuthorityChanged,
  sessionHeaders,
  sessionGeneration,
} from "./transport";
import {
  parseDirectory,
  parseMe,
  type BusinessRole,
  type MeView,
} from "./types";

export class IdentityApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "IdentityApiError";
  }
}
async function request(
  path: string,
  body?: unknown,
  extraHeaders?: Record<string, string>,
  logout = false,
  cancellation?: AbortSignal,
): Promise<unknown> {
  const command = body !== undefined || logout;
  const generation = sessionGeneration();
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...sessionHeaders(command),
    ...extraHeaders,
  };
  if (command && !logout) {
    headers["Content-Type"] = "application/json";
    headers["Idempotency-Key"] = crypto.randomUUID();
  }
  try {
    const response = await fetch(path, {
      method: command ? "POST" : "GET",
      credentials: "same-origin",
      headers,
      signal: cancellation
        ? AbortSignal.any([cancellation, AbortSignal.timeout(10_000)])
        : AbortSignal.timeout(10_000),
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (cancellation?.aborted)
      throw new DOMException("Identity check cancelled", "AbortError");
    if (path !== "/api/v1/me" && !logout && generation !== sessionGeneration())
      throw new IdentityApiError(
        "登录状态已变化，已忽略之前请求的页面结果。请重新读取当前记录。",
        409,
      );
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as {
        detail?: string;
      };
      if (path !== "/api/v1/me") notifyAuthorityChanged(response.status);
      throw new IdentityApiError(
        problem.detail ??
          (response.status === 401
            ? "请先登录企业账号。"
            : response.status === 403
              ? "当前账号没有执行此操作的权限，请重新检查身份。"
              : `身份请求未完成（${response.status}）`),
        response.status,
      );
    }
    const result =
      response.status === 204 || logout ? undefined : await response.json();
    if (cancellation?.aborted)
      throw new DOMException("Identity check cancelled", "AbortError");
    if (path !== "/api/v1/me" && !logout && generation !== sessionGeneration())
      throw new IdentityApiError(
        "登录状态已变化，已忽略之前请求的页面结果。请重新读取当前记录。",
        409,
      );
    return result;
  } catch (error) {
    if (error instanceof IdentityApiError) throw error;
    throw new IdentityApiError(
      command
        ? "请求结果尚不能确认。请先刷新成员或邀请记录，再决定是否重试。"
        : "暂时无法读取身份服务，请检查连接后重试。",
      0,
    );
  }
}
export const identityClient = {
  async me(localActor?: string, cancellation?: AbortSignal): Promise<MeView> {
    const first = parseMe(
      await request("/api/v1/me", undefined, undefined, false, cancellation),
    );
    const desired = localActor ?? localStorage.getItem("smartdata.localActor");
    // A browser preference is considered only after this server explicitly allows rehearsal.
    if (
      first.mode === "LOCAL_DEMO" &&
      first.capabilities.localRoleSwitch &&
      desired &&
      desired !== first.accountId
    )
      return parseMe(
        await request(
          "/api/v1/me",
          undefined,
          { "X-Demo-Actor": desired },
          false,
          cancellation,
        ),
      );
    return first;
  },
  members: async () =>
    parseDirectory(await request("/api/v1/enterprise/members")),
  invite: async (
    expectedStateVersion: number,
    accountId: string,
    roles: BusinessRole[],
  ) =>
    parseDirectory(
      await request("/api/v1/enterprise/invitations", {
        expectedStateVersion,
        accountId,
        roles,
      }),
    ),
  setRoles: async (
    accountId: string,
    expectedStateVersion: number,
    expectedMembershipVersion: number,
    roles: BusinessRole[],
  ) =>
    parseDirectory(
      await request(
        `/api/v1/enterprise/members/${encodeURIComponent(accountId)}/roles`,
        { expectedStateVersion, expectedMembershipVersion, roles },
      ),
    ),
  memberStatus: async (
    accountId: string,
    status: "suspend" | "activate",
    expectedStateVersion: number,
    expectedMembershipVersion: number,
  ) =>
    parseDirectory(
      await request(
        `/api/v1/enterprise/members/${encodeURIComponent(accountId)}/${status}`,
        { expectedStateVersion, expectedMembershipVersion },
      ),
    ),
  revoke: async (id: string, expectedStateVersion: number) =>
    parseDirectory(
      await request(
        `/api/v1/enterprise/invitations/${encodeURIComponent(id)}/revoke`,
        { expectedStateVersion },
      ),
    ),
  accept: async (id: string, expectedStateVersion: number) =>
    parseMe(
      await request(
        `/api/v1/enterprise/invitations/${encodeURIComponent(id)}/accept`,
        { expectedStateVersion },
      ),
    ),
  async logout(me = currentSession()) {
    if (me?.mode !== "ENTERPRISE" || me.logoutUrl !== "/logout")
      throw new Error("当前没有可退出的企业会话。");
    if (!me.csrf)
      throw new Error("当前会话缺少安全校验信息，请重新检查登录状态。");
    await request(
      me.logoutUrl,
      undefined,
      { [me.csrf.headerName]: me.csrf.token },
      true,
    );
  },
};
export function startEnterpriseLogin(me: MeView) {
  if (
    me.mode !== "ENTERPRISE" ||
    me.loginUrl !== "/oauth2/authorization/enterprise"
  )
    throw new Error("企业登录入口未配置，请联系部署负责人。");
  window.location.assign(me.loginUrl);
}
