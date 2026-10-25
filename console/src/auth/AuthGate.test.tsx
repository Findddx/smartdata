import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { enterpriseMe, localMe } from "../test/identity-fixtures";
import { AuthGate } from "./AuthGate";
import { identityClient, startEnterpriseLogin } from "./client";
import {
  AUTHORITY_CHANGED,
  configureSession,
  currentSession,
} from "./transport";
import type { MeView } from "./types";
vi.mock("./client", () => ({
  identityClient: { me: vi.fn(), accept: vi.fn(), logout: vi.fn() },
  startEnterpriseLogin: vi.fn(),
}));
vi.mock("../workspace/Workspace", () => ({
  Workspace: ({
    identity,
    onLogout,
  }: {
    identity: MeView;
    onLogout: () => void;
  }) => (
    <section>
      <h1>已进入：{identity.tenant?.displayName}</h1>
      <p>{identity.accountId}</p>
      <button onClick={onLogout}>退出企业账号</button>
    </section>
  ),
}));
function anonymous() {
  return enterpriseMe({
    authenticated: false,
    accountId: null,
    displayName: null,
    membership: null,
    roles: [],
    capabilities: {
      canUseWorkspace: false,
      canManageMembers: false,
      localRoleSwitch: false,
    },
  });
}
function renderGate() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  render(
    <QueryClientProvider client={client}>
      <AuthGate />
    </QueryClientProvider>,
  );
  return client;
}
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  configureSession(null);
});
it("does not mount or reuse private workspace data while the server identity is unresolved", async () => {
  let resolve!: (me: MeView) => void;
  vi.mocked(identityClient.me).mockImplementation(
    () =>
      new Promise<MeView>((done) => {
        resolve = done;
      }),
  );
  const client = renderGate();
  client.setQueryData(["workspace"], { secret: "previous-account-data" });
  expect(
    screen.getByRole("heading", { name: "正在确认身份" }),
  ).toBeInTheDocument();
  await act(async () => resolve(anonymous()));
  expect(
    await screen.findByRole("button", { name: /使用企业账号登录/ }),
  ).toBeInTheDocument();
  expect(client.getQueryData(["workspace"])).toBeUndefined();
  expect(screen.queryByText(/已进入/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: /使用企业账号登录/ }));
  expect(startEnterpriseLogin).toHaveBeenCalledWith(
    expect.objectContaining({ authenticated: false, mode: "ENTERPRISE" }),
  );
});
it("shows only a specified invitation and accepts with its own version before opening the workspace", async () => {
  const me = enterpriseMe({
    membership: null,
    roles: [],
    capabilities: {
      canUseWorkspace: false,
      canManageMembers: false,
      localRoleSwitch: false,
    },
    invitations: [
      {
        id: "invitation-for-me",
        accountId: `acct_${"1".repeat(64)}`,
        roles: ["ORDER_SPECIALIST"],
        status: "PENDING",
        stateVersion: 5,
        createdBy: "owner",
        expiresAt: "2026-11-01T10:00:00Z",
      },
    ],
  });
  vi.mocked(identityClient.me).mockResolvedValue(me);
  vi.mocked(identityClient.accept).mockResolvedValue(enterpriseMe());
  renderGate();
  expect(await screen.findByLabelText("我的账号编号")).toHaveValue(
    me.accountId,
  );
  expect(screen.queryByText(/已进入/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "接受指定邀请" }));
  expect(
    await screen.findByRole("heading", { name: "已进入：示例企业" }),
  ).toBeInTheDocument();
  expect(identityClient.accept).toHaveBeenCalledWith("invitation-for-me", 5);
});
it("does not let a suspended member use an invitation to restore access", async () => {
  vi.mocked(identityClient.me).mockResolvedValue(
    enterpriseMe({
      membership: { status: "SUSPENDED", stateVersion: 3 },
      roles: [],
      capabilities: {
        canUseWorkspace: false,
        canManageMembers: false,
        localRoleSwitch: false,
      },
      invitations: [
        {
          id: "old",
          accountId: `acct_${"1".repeat(64)}`,
          roles: ["ADMIN"],
          status: "PENDING",
          stateVersion: 1,
          createdBy: "owner",
          expiresAt: "2026-11-01T10:00:00Z",
        },
      ],
    }),
  );
  renderGate();
  expect(
    await screen.findByRole("heading", { name: "企业访问已暂停" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "接受指定邀请" }),
  ).not.toBeInTheDocument();
  expect(identityClient.accept).not.toHaveBeenCalled();
});
it("clears private cached data and removes the workspace after authorization is withdrawn", async () => {
  vi.mocked(identityClient.me)
    .mockResolvedValueOnce(enterpriseMe())
    .mockResolvedValue(
      enterpriseMe({
        membership: { status: "SUSPENDED", stateVersion: 3 },
        roles: [],
        capabilities: {
          canUseWorkspace: false,
          canManageMembers: false,
          localRoleSwitch: false,
        },
      }),
    );
  const client = renderGate();
  await screen.findByText("已进入：示例企业");
  client.setQueryData(["workspace-lineage", 9], { old: true });
  act(() => window.dispatchEvent(new Event(AUTHORITY_CHANGED)));
  expect(
    await screen.findByRole("heading", { name: "企业访问已暂停" }),
  ).toBeInTheDocument();
  expect(client.getQueryData(["workspace-lineage", 9])).toBeUndefined();
  expect(screen.queryByText("已进入：示例企业")).not.toBeInTheDocument();
});
it("keeps a failed session refresh closed instead of reusing an old local admin", async () => {
  vi.mocked(identityClient.me)
    .mockResolvedValueOnce(localMe())
    .mockRejectedValue(new Error("身份服务不可用"));
  const client = renderGate();
  await screen.findByText("已进入：本机演示工作区");
  client.setQueryData(["workspace"], { old: true });
  act(() => window.dispatchEvent(new Event(AUTHORITY_CHANGED)));
  expect(
    await screen.findByRole("heading", { name: "暂时无法确认身份" }),
  ).toBeInTheDocument();
  expect(client.getQueryData(["workspace"])).toBeUndefined();
  expect(screen.queryByText("已进入：本机演示工作区")).not.toBeInTheDocument();
});
it("logs out through the server and clears private state before returning to login", async () => {
  vi.mocked(identityClient.me)
    .mockResolvedValueOnce(enterpriseMe())
    .mockResolvedValue(anonymous());
  vi.mocked(identityClient.logout).mockResolvedValue();
  const client = renderGate();
  await screen.findByText("已进入：示例企业");
  client.setQueryData(["workspace"], { old: true });
  fireEvent.click(screen.getByRole("button", { name: "退出企业账号" }));
  expect(
    await screen.findByRole("button", { name: /使用企业账号登录/ }),
  ).toBeInTheDocument();
  await waitFor(() => expect(identityClient.logout).toHaveBeenCalledTimes(1));
  expect(client.getQueryData(["workspace"])).toBeUndefined();
});
it("does not reuse an unresolved nonmember session after logout fails", async () => {
  vi.mocked(identityClient.me).mockResolvedValue(
    enterpriseMe({
      membership: null,
      roles: [],
      capabilities: {
        canUseWorkspace: false,
        canManageMembers: false,
        localRoleSwitch: false,
      },
    }),
  );
  vi.mocked(identityClient.logout).mockRejectedValue(
    new Error("退出结果尚不能确认"),
  );
  renderGate();
  await screen.findByLabelText("我的账号编号");
  fireEvent.click(screen.getByRole("button", { name: "退出账号" }));
  expect(
    await screen.findByRole("heading", { name: "暂时无法确认身份" }),
  ).toBeInTheDocument();
  expect(screen.queryByLabelText("我的账号编号")).not.toBeInTheDocument();
});
it("does not let a cancelled me response restore old authority or clear the new account's cache", async () => {
  let finishOld!: (me: MeView) => void;
  const suspended = enterpriseMe({
    accountId: `acct_${"2".repeat(64)}`,
    membership: { status: "SUSPENDED", stateVersion: 4 },
    roles: [],
    capabilities: {
      canUseWorkspace: false,
      canManageMembers: false,
      localRoleSwitch: false,
    },
  });
  vi.mocked(identityClient.me)
    .mockResolvedValueOnce(enterpriseMe())
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve;
        }),
    )
    .mockResolvedValue(suspended);
  const client = renderGate();
  await screen.findByText("已进入：示例企业");
  act(() => window.dispatchEvent(new Event(AUTHORITY_CHANGED)));
  await waitFor(() => expect(identityClient.me).toHaveBeenCalledTimes(2));
  act(() => window.dispatchEvent(new Event(AUTHORITY_CHANGED)));
  expect(
    await screen.findByRole("heading", { name: "企业访问已暂停" }),
  ).toBeInTheDocument();
  client.setQueryData(["workspace"], { ownedByNewAccount: true });
  await act(async () => finishOld(enterpriseMe()));
  expect(currentSession()?.accountId).toBe(suspended.accountId);
  expect(client.getQueryData(["workspace"])).toEqual({
    ownedByNewAccount: true,
  });
  expect(screen.queryByText("已进入：示例企业")).not.toBeInTheDocument();
});
