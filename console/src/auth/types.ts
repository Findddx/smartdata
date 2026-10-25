export type BusinessRole =
  | "ADMIN"
  | "ORDER_SPECIALIST"
  | "WAREHOUSE_APPROVER"
  | "SALES_OWNER"
  | "AUDITOR";
export const ROLE_LABELS: Record<BusinessRole, string> = {
  ADMIN: "企业管理员",
  ORDER_SPECIALIST: "业务处理人",
  WAREHOUSE_APPROVER: "仓库审批人",
  SALES_OWNER: "业务负责人",
  AUDITOR: "审计查看",
};
export interface InvitationView {
  id: string;
  accountId: string;
  roles: BusinessRole[];
  status: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
  stateVersion: number;
  createdBy: string;
  expiresAt: string;
}
export interface MemberView {
  accountId: string;
  displayName: string | null;
  roles: BusinessRole[];
  status: "ACTIVE" | "SUSPENDED";
  stateVersion: number;
  updatedAt: string;
}
export interface MemberDirectory {
  stateVersion: number;
  members: MemberView[];
  invitations: InvitationView[];
}
export interface MeView {
  mode: "LOCAL_DEMO" | "ENTERPRISE";
  authenticated: boolean;
  accountId: string | null;
  displayName: string | null;
  tenant: { id: string; displayName: string } | null;
  membership: { status: "ACTIVE" | "SUSPENDED"; stateVersion: number } | null;
  roles: BusinessRole[];
  capabilities: {
    canUseWorkspace: boolean;
    canManageMembers: boolean;
    localRoleSwitch: boolean;
  };
  loginUrl: string | null;
  logoutUrl: string | null;
  csrf: { headerName: string; token: string } | null;
  invitations: InvitationView[];
}
export function identityScope(me: MeView) {
  return JSON.stringify([
    me.mode,
    me.authenticated,
    me.accountId,
    me.tenant?.id,
    me.membership?.status,
    me.membership?.stateVersion,
    [...me.roles].sort(),
    me.capabilities.canUseWorkspace,
    me.capabilities.canManageMembers,
    me.capabilities.localRoleSwitch,
  ]);
}
export function workspacePermissions(me: MeView) {
  const use = me.authenticated && me.capabilities.canUseWorkspace;
  return {
    edit: use && me.roles.some((role) => role !== "AUDITOR"),
    administer: use && me.roles.includes("ADMIN"),
    review:
      use &&
      me.roles.some((role) => role === "ADMIN" || role === "SALES_OWNER"),
    manageMembers:
      use &&
      me.mode === "ENTERPRISE" &&
      me.capabilities.canManageMembers &&
      me.roles.includes("ADMIN"),
    localSwitch: me.mode === "LOCAL_DEMO" && me.capabilities.localRoleSwitch,
  };
}

const object = (x: unknown): x is Record<string, unknown> =>
  !!x && typeof x === "object" && !Array.isArray(x);
const text = (x: unknown): x is string => typeof x === "string" && x.length > 0;
const version = (x: unknown): x is number =>
  typeof x === "number" && Number.isSafeInteger(x) && x >= 0;
function roles(x: unknown): x is BusinessRole[] {
  return (
    Array.isArray(x) &&
    x.every(
      (role) => typeof role === "string" && Object.hasOwn(ROLE_LABELS, role),
    ) &&
    new Set(x).size === x.length
  );
}
function invitation(x: unknown): x is InvitationView {
  return (
    object(x) &&
    text(x.id) &&
    text(x.accountId) &&
    roles(x.roles) &&
    ["PENDING", "ACCEPTED", "REVOKED", "EXPIRED"].includes(String(x.status)) &&
    version(x.stateVersion) &&
    text(x.createdBy) &&
    text(x.expiresAt)
  );
}
export function parseMe(input: unknown): MeView {
  if (
    !object(input) ||
    !["LOCAL_DEMO", "ENTERPRISE"].includes(String(input.mode)) ||
    typeof input.authenticated !== "boolean" ||
    !roles(input.roles) ||
    !object(input.capabilities) ||
    !["canUseWorkspace", "canManageMembers", "localRoleSwitch"].every(
      (key) =>
        typeof (input.capabilities as Record<string, unknown>)[key] ===
        "boolean",
    ) ||
    !Array.isArray(input.invitations) ||
    !input.invitations.every(invitation) ||
    !(input.accountId === null || text(input.accountId)) ||
    !(input.displayName === null || typeof input.displayName === "string") ||
    !(
      input.tenant === null ||
      (object(input.tenant) &&
        text(input.tenant.id) &&
        text(input.tenant.displayName))
    ) ||
    !(
      input.membership === null ||
      (object(input.membership) &&
        ["ACTIVE", "SUSPENDED"].includes(String(input.membership.status)) &&
        version(input.membership.stateVersion))
    ) ||
    !(
      input.loginUrl === null ||
      input.loginUrl === "/oauth2/authorization/enterprise"
    ) ||
    !(input.logoutUrl === null || input.logoutUrl === "/logout") ||
    !(
      input.csrf === null ||
      (object(input.csrf) &&
        ["X-CSRF-TOKEN", "X-XSRF-TOKEN"].includes(
          String(input.csrf.headerName),
        ) &&
        text(input.csrf.token))
    )
  )
    throw new Error("身份服务返回的格式不受支持，请联系部署负责人。");
  const me = input as unknown as MeView;
  if (
    (me.authenticated && !me.accountId) ||
    (!me.authenticated &&
      (me.accountId !== null ||
        me.membership !== null ||
        me.roles.length > 0 ||
        me.capabilities.canUseWorkspace ||
        me.capabilities.canManageMembers)) ||
    (me.mode === "ENTERPRISE" &&
      me.accountId !== null &&
      !/^acct_[0-9a-f]{64}$/.test(me.accountId)) ||
    (me.mode === "ENTERPRISE" && me.capabilities.localRoleSwitch) ||
    (me.mode === "ENTERPRISE" &&
      me.capabilities.canUseWorkspace &&
      (!me.tenant || me.membership?.status !== "ACTIVE" || !me.roles.length)) ||
    (me.capabilities.canManageMembers &&
      (!me.capabilities.canUseWorkspace || !me.roles.includes("ADMIN"))) ||
    me.invitations.some((invite) => invite.accountId !== me.accountId)
  )
    throw new Error("身份与企业权限信息不一致，已停止载入工作区。");
  return me;
}
export function parseDirectory(input: unknown): MemberDirectory {
  if (
    !object(input) ||
    !version(input.stateVersion) ||
    !Array.isArray(input.members) ||
    !Array.isArray(input.invitations) ||
    !input.invitations.every(invitation) ||
    !input.members.every(
      (member) =>
        object(member) &&
        text(member.accountId) &&
        (member.displayName === null ||
          typeof member.displayName === "string") &&
        roles(member.roles) &&
        ["ACTIVE", "SUSPENDED"].includes(String(member.status)) &&
        version(member.stateVersion) &&
        text(member.updatedAt),
    )
  )
    throw new Error("成员目录格式不受支持，请刷新后联系负责人。");
  return input as unknown as MemberDirectory;
}
