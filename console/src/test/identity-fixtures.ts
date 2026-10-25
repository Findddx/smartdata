import type { MeView } from "../auth/types";
export function localMe(patch: Partial<MeView> = {}): MeView {
  return {
    mode: "LOCAL_DEMO",
    authenticated: true,
    accountId: "admin",
    displayName: "本地管理员",
    tenant: { id: "demo-cn-001", displayName: "本机演示工作区" },
    membership: null,
    roles: ["ADMIN"],
    capabilities: {
      canUseWorkspace: true,
      canManageMembers: false,
      localRoleSwitch: true,
    },
    loginUrl: null,
    logoutUrl: null,
    csrf: null,
    invitations: [],
    ...patch,
  };
}
export function enterpriseMe(patch: Partial<MeView> = {}): MeView {
  return {
    mode: "ENTERPRISE",
    authenticated: true,
    accountId: `acct_${"1".repeat(64)}`,
    displayName: "陈负责人",
    tenant: { id: "company-example", displayName: "示例企业" },
    membership: { status: "ACTIVE", stateVersion: 2 },
    roles: ["ADMIN"],
    capabilities: {
      canUseWorkspace: true,
      canManageMembers: true,
      localRoleSwitch: false,
    },
    loginUrl: "/oauth2/authorization/enterprise",
    logoutUrl: "/logout",
    csrf: { headerName: "X-CSRF-TOKEN", token: "synthetic-csrf-fixture" },
    invitations: [],
    ...patch,
  };
}
