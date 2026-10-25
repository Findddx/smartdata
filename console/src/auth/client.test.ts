import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enterpriseMe, localMe } from "../test/identity-fixtures";
import { workspaceClient } from "../workspace/client";
import { identityClient } from "./client";
import { configureSession } from "./transport";
import { parseMe } from "./types";
beforeEach(() => {
  localStorage.clear();
  configureSession(null);
});
afterEach(() => {
  configureSession(null);
  vi.unstubAllGlobals();
});
const response = (body: unknown) => ({
  ok: true,
  status: 200,
  json: async () => body,
});
describe("server identity contract consumer", () => {
  it("never treats a browser admin preference or DEV build as enterprise authority", async () => {
    localStorage.setItem("smartdata.localActor", "admin");
    const me = enterpriseMe({
      roles: ["AUDITOR"],
      capabilities: {
        canUseWorkspace: true,
        canManageMembers: false,
        localRoleSwitch: false,
      },
    });
    const fetcher = vi.fn().mockResolvedValue(response(me));
    vi.stubGlobal("fetch", fetcher);
    expect((await identityClient.me()).roles).toEqual(["AUDITOR"]);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]![1].headers).not.toHaveProperty(
      "X-Demo-Actor",
    );
  });
  it("checks the server mode before requesting a chosen local rehearsal identity", async () => {
    localStorage.setItem("smartdata.localActor", "admin");
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(
          localMe({
            accountId: "order-specialist",
            roles: ["ORDER_SPECIALIST"],
          }),
        ),
      )
      .mockResolvedValueOnce(response(localMe()));
    vi.stubGlobal("fetch", fetcher);
    expect((await identityClient.me()).roles).toEqual(["ADMIN"]);
    expect(fetcher.mock.calls[0]![1].headers).not.toHaveProperty(
      "X-Demo-Actor",
    );
    expect(fetcher.mock.calls[1]![1].headers).toHaveProperty(
      "X-Demo-Actor",
      "admin",
    );
  });
  it("sends enterprise commands with the server CSRF token and both membership versions", async () => {
    configureSession(enterpriseMe());
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        response({ stateVersion: 4, members: [], invitations: [] }),
      );
    vi.stubGlobal("fetch", fetcher);
    await identityClient.setRoles(`acct_${"2".repeat(64)}`, 3, 7, ["AUDITOR"]);
    const [url, options] = fetcher.mock.calls[0]!;
    expect(url).toContain("/roles");
    expect(JSON.parse(options.body)).toEqual({
      expectedStateVersion: 3,
      expectedMembershipVersion: 7,
      roles: ["AUDITOR"],
    });
    expect(options.headers).toMatchObject({
      "X-CSRF-TOKEN": "synthetic-csrf-fixture",
      "Idempotency-Key": expect.any(String),
    });
    expect(options.headers).not.toHaveProperty("X-Demo-Actor");
    expect(options.credentials).toBe("same-origin");
  });
  it("also attaches enterprise CSRF to existing workspace mutations without inventing an actor", async () => {
    configureSession(enterpriseMe());
    const fetcher = vi.fn().mockResolvedValue(response({ stateVersion: 8 }));
    vi.stubGlobal("fetch", fetcher);
    await workspaceClient.scan("source-orders", 7);
    expect(fetcher.mock.calls[0]![1].headers).toMatchObject({
      "X-CSRF-TOKEN": "synthetic-csrf-fixture",
      "Idempotency-Key": expect.any(String),
    });
    expect(fetcher.mock.calls[0]![1].headers).not.toHaveProperty(
      "X-Demo-Actor",
    );
  });
  it("fails closed on contradictory modes, foreign invitations and inherited role names", () => {
    expect(() =>
      parseMe({ ...enterpriseMe(), mode: "UNRECOGNIZED" }),
    ).toThrow();
    expect(() =>
      parseMe(
        enterpriseMe({
          capabilities: {
            canUseWorkspace: true,
            canManageMembers: true,
            localRoleSwitch: true,
          },
        }),
      ),
    ).toThrow();
    expect(() => parseMe({ ...enterpriseMe(), roles: ["toString"] })).toThrow();
    expect(() => parseMe(enterpriseMe({ authenticated: false }))).toThrow();
    expect(() =>
      parseMe(
        enterpriseMe({
          invitations: [
            {
              id: "foreign",
              accountId: `acct_${"2".repeat(64)}`,
              roles: ["AUDITOR"],
              status: "PENDING",
              stateVersion: 1,
              createdBy: "owner",
              expiresAt: "2026-11-01T10:00:00Z",
            },
          ],
        }),
      ),
    ).toThrow();
  });
  it("rejects a late workspace response after the active identity changed", async () => {
    configureSession(enterpriseMe());
    let finish!: (response: unknown) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      ),
    );
    const pending = workspaceClient.scan("source-1", 7);
    configureSession(enterpriseMe({ accountId: `acct_${"2".repeat(64)}` }));
    finish(response({ stateVersion: 8 }));
    await expect(pending).rejects.toMatchObject({
      status: 409,
      message: expect.stringContaining("登录状态已变化"),
    });
  });
  it("rejects a late member command before it can refill a cleared directory cache", async () => {
    configureSession(enterpriseMe());
    let finish!: (response: unknown) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      ),
    );
    const pending = identityClient.memberStatus(
      `acct_${"2".repeat(64)}`,
      "suspend",
      3,
      7,
    );
    configureSession(null);
    finish(response({ stateVersion: 4, members: [], invitations: [] }));
    await expect(pending).rejects.toMatchObject({ status: 409 });
  });
});
