import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { enterpriseMe } from "../test/identity-fixtures";
import { MembersPage } from "./MembersPage";
import { identityClient, IdentityApiError } from "./client";
import { configureSession } from "./transport";
import type { MemberDirectory, MeView } from "./types";
vi.mock("./client", () => ({
  identityClient: {
    members: vi.fn(),
    invite: vi.fn(),
    setRoles: vi.fn(),
    memberStatus: vi.fn(),
    revoke: vi.fn(),
  },
  IdentityApiError: class extends Error {
    constructor(
      message: string,
      public status: number,
    ) {
      super(message);
    }
  },
}));
const other = `acct_${"2".repeat(64)}`;
function directory(): MemberDirectory {
  return {
    stateVersion: 3,
    members: [
      {
        accountId: other,
        displayName: "林同事",
        roles: ["ORDER_SPECIALIST"],
        status: "ACTIVE",
        stateVersion: 7,
        updatedAt: "2026-10-25T08:00:00Z",
      },
    ],
    invitations: [],
  };
}
function setup(identity: MeView = enterpriseMe()) {
  configureSession(identity);
  const onIdentityRefresh = vi.fn().mockResolvedValue(undefined);
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MembersPage identity={identity} onIdentityRefresh={onIdentityRefresh} />
    </QueryClientProvider>,
  );
  return { onIdentityRefresh };
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(identityClient.members).mockResolvedValue(directory());
  if (!HTMLDialogElement.prototype.showModal)
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
    };
  if (!HTMLDialogElement.prototype.close)
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
});
it("does not request the member directory when server capabilities deny management", async () => {
  setup(
    enterpriseMe({
      roles: ["AUDITOR"],
      capabilities: {
        canUseWorkspace: true,
        canManageMembers: false,
        localRoleSwitch: false,
      },
    }),
  );
  expect(screen.getByText(/当前账号没有管理成员的权限/)).toBeInTheDocument();
  expect(identityClient.members).not.toHaveBeenCalled();
});
it("invites only an exact account identifier and preserves the global directory version", async () => {
  vi.mocked(identityClient.invite).mockResolvedValue({
    ...directory(),
    stateVersion: 4,
  });
  setup();
  const input = await screen.findByLabelText("邀请账号编号");
  fireEvent.change(input, { target: { value: "林同事@example.invalid" } });
  expect(screen.getByRole("button", { name: "登记指定邀请" })).toBeDisabled();
  fireEvent.change(input, { target: { value: other } });
  fireEvent.click(screen.getByRole("button", { name: "登记指定邀请" }));
  await waitFor(() =>
    expect(identityClient.invite).toHaveBeenCalledWith(3, other, [
      "ORDER_SPECIALIST",
    ]),
  );
  expect(await screen.findByText(/邀请已登记/)).toBeInTheDocument();
});
it("does not send a directory command from a page belonging to the previous session", async () => {
  setup();
  const input = await screen.findByLabelText("邀请账号编号");
  fireEvent.change(input, { target: { value: other } });
  configureSession(enterpriseMe({ accountId: other }));
  fireEvent.click(screen.getByRole("button", { name: "登记指定邀请" }));
  expect(identityClient.invite).not.toHaveBeenCalled();
});
it("keeps a conflicting role draft but requires explicit reload before using new member versions", async () => {
  const next: MemberDirectory = {
    ...directory(),
    stateVersion: 4,
    members: [
      { ...directory().members[0]!, roles: ["AUDITOR"], stateVersion: 8 },
    ],
  };
  vi.mocked(identityClient.setRoles)
    .mockRejectedValueOnce(new IdentityApiError("成员记录已变化", 409))
    .mockResolvedValueOnce({ ...next, stateVersion: 5 });
  setup();
  fireEvent.click(await screen.findByRole("button", { name: "设置角色" }));
  const dialog = screen.getByRole("dialog", { name: "设置成员角色" });
  fireEvent.click(within(dialog).getByRole("checkbox", { name: "业务负责人" }));
  vi.mocked(identityClient.members).mockResolvedValue(next);
  fireEvent.click(within(dialog).getByRole("button", { name: "确认变更" }));
  expect(await within(dialog).findByText("成员记录已变化")).toBeInTheDocument();
  expect(
    within(dialog).getByRole("checkbox", { name: "业务负责人" }),
  ).toBeChecked();
  expect(
    within(dialog).getByRole("button", { name: "确认变更" }),
  ).toBeDisabled();
  expect(identityClient.setRoles).toHaveBeenCalledWith(other, 3, 7, [
    "ORDER_SPECIALIST",
    "SALES_OWNER",
  ]);
  fireEvent.click(
    within(dialog).getByRole("button", {
      name: "重新载入当前记录（替换草稿）",
    }),
  );
  expect(
    within(dialog).getByRole("checkbox", { name: "审计查看" }),
  ).toBeChecked();
  expect(
    within(dialog).getByRole("checkbox", { name: "业务负责人" }),
  ).not.toBeChecked();
  fireEvent.click(within(dialog).getByRole("button", { name: "确认变更" }));
  await waitFor(() =>
    expect(identityClient.setRoles).toHaveBeenLastCalledWith(other, 4, 8, [
      "AUDITOR",
    ]),
  );
});
it("uses both current directory and target member versions to restore a suspended member", async () => {
  vi.mocked(identityClient.members).mockResolvedValue({
    ...directory(),
    members: [{ ...directory().members[0]!, status: "SUSPENDED" }],
  });
  vi.mocked(identityClient.memberStatus).mockResolvedValue({
    ...directory(),
    stateVersion: 4,
  });
  setup();
  fireEvent.click(await screen.findByRole("button", { name: "恢复" }));
  const dialog = screen.getByRole("dialog", { name: "恢复成员访问" });
  expect(
    within(dialog).getByText(/不恢复历史邀请或先前的批准/),
  ).toBeInTheDocument();
  fireEvent.click(within(dialog).getByRole("button", { name: "确认变更" }));
  await waitFor(() =>
    expect(identityClient.memberStatus).toHaveBeenCalledWith(
      other,
      "activate",
      3,
      7,
    ),
  );
});
