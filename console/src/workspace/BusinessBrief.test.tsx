import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { useRef, useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  BusinessBriefPage,
  type BusinessEditorSnapshot,
} from "./BusinessBrief";
import type {
  BusinessBrief,
  BusinessBriefCommand,
  WorkspaceState,
} from "./types";

vi.mock("./client", () => ({
  workspaceClient: { businessBriefHistory: vi.fn().mockResolvedValue([]) },
}));
function state(brief: BusinessBrief | null = null): WorkspaceState {
  return {
    stateVersion: 4,
    mode: "LOCAL_PERSISTENT",
    sources: ["orders", "stock"].map((id) => ({
      id,
      name: id === "orders" ? "商城订单" : "库存台账",
      kind: "JSON_SNAPSHOT",
      provenance: "USER",
      status: "SCANNED",
      assetCount: 1,
      credentialConfigured: false,
      stateVersion: 1,
    })),
    assets: [],
    scans: [],
    findings: [],
    assistants: [],
    tasks: [],
    comments: [],
    messages: [],
    audit: [],
    capabilities: {},
    businessBrief: brief,
  };
}
function brief(): BusinessBrief {
  return {
    version: 2,
    enterpriseName: "业务团队",
    businessDescription: "按日核对订单与库存",
    includedSourceIds: ["orders", "stock"],
    goals: [
      {
        id: "goal-orders",
        title: "核对订单口径",
        type: "ANALYTICS_TRUST",
        expectedOutcome: "先核对重复记录",
        priority: "HIGH",
        sourceIds: ["orders"],
      },
    ],
    metrics: [],
    updatedAt: "2026-10-25T10:00:00Z",
    updatedBy: "owner",
    modelContextExportEnabled: false,
  };
}
function setup(initial = state(), reviewer = true, canEdit = true) {
  const saved = vi.fn<(body: BusinessBriefCommand) => void>();
  const confirm = vi.fn();
  function Harness() {
    const [current, setCurrent] = useState(initial);
    const editor = useRef<BusinessEditorSnapshot | null>(null);
    return (
      <BusinessBriefPage
        workspace={current}
        pending={false}
        canConfirm={reviewer}
        canEdit={canEdit}
        editor={editor}
        onConfirm={confirm}
        onSave={async (body) => {
          saved(body);
          const next = {
            ...current,
            stateVersion: current.stateVersion + 1,
            businessBrief: {
              ...body,
              version: body.expectedBriefVersion + 1,
              metrics: body.metrics.map((item) => ({
                ...item,
                status: "DRAFT" as const,
                stateVersion: 1,
                calculationStatus: "NOT_COMPUTED" as const,
              })),
              updatedAt: "2026-10-25T11:00:00Z",
              updatedBy: "owner",
              modelContextExportEnabled: false as const,
            },
          };
          setCurrent(next);
          return next;
        }}
      />
    );
  }
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <Harness />
    </QueryClientProvider>,
  );
  return { saved, confirm };
}
beforeEach(() => {
  if (!HTMLDialogElement.prototype.showModal)
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
    };
  if (!HTMLDialogElement.prototype.close)
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
});
describe("业务约定", () => {
  it("shows auditors the saved definition and history while disabling edits", () => {
    setup(state(brief()), false, false);
    expect(screen.getByLabelText("企业或业务团队名称")).toBeDisabled();
    expect(screen.getByRole("button", { name: "登记指标口径" })).toBeDisabled();
    for (const save of screen.getAllByRole("button", { name: "保存业务约定" })) {
      expect(save).toBeDisabled();
    }
    expect(screen.getByRole("button", { name: "版本记录" })).toBeEnabled();
    expect(screen.getByText(/当前为只读审计身份/)).toBeInTheDocument();
  });
  it("records the user's own goal and explicit scope with both expected versions", async () => {
    const { saved } = setup();
    fireEvent.change(screen.getByLabelText("企业或业务团队名称"), {
      target: { value: "电商经营团队" },
    });
    fireEvent.click(screen.getByRole("button", { name: "让分析结果可信" }));
    fireEvent.click(screen.getByRole("checkbox", { name: /商城订单/ }));
    fireEvent.click(
      screen.getAllByRole("button", { name: "保存业务约定" })[0]!,
    );
    await waitFor(() =>
      expect(saved).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedStateVersion: 4,
          expectedBriefVersion: 0,
          enterpriseName: "电商经营团队",
          includedSourceIds: ["orders"],
          goals: [
            expect.objectContaining({
              type: "ANALYTICS_TRUST",
              priority: "MEDIUM",
              sourceIds: [],
            }),
          ],
        }),
      ),
    );
    expect(await screen.findByText("已保存 · 约定 v1")).toBeInTheDocument();
    expect(screen.getByText(/业务原文保留在本机/)).toBeInTheDocument();
  });
  it("does not expand a goal when its last specifically selected source is removed", async () => {
    const { saved } = setup(state(brief()));
    const scope = within(screen.getByRole("region", { name: "本轮看哪些来源" }));
    const orders = scope.getByRole("checkbox", { name: /商城订单/ });
    fireEvent.click(orders);
    expect(orders).toBeChecked();
    expect(screen.getByRole("alert")).toHaveTextContent("唯一指定来源");
    expect(saved).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("限定到特定来源"));
    fireEvent.click(screen.getByRole("button", { name: "使用本轮全部范围" }));
    fireEvent.click(scope.getByRole("checkbox", { name: /商城订单/ }));
    fireEvent.click(
      screen.getAllByRole("button", { name: "保存业务约定" })[0]!,
    );
    await waitFor(() =>
      expect(saved).toHaveBeenCalledWith(
        expect.objectContaining({
          includedSourceIds: ["stock"],
          goals: [expect.objectContaining({ sourceIds: [] })],
        }),
      ),
    );
  });
  it("keeps metric text as a draft and never submits a forged confirmation or computed value", async () => {
    const initial = brief();
    initial.goals = [];
    const { saved, confirm } = setup(state(initial));
    fireEvent.click(screen.getByRole("button", { name: "登记指标口径" }));
    const dialog = await screen.findByRole("dialog", {
      name: "给一个指标定口径",
    });
    fireEvent.change(within(dialog).getByLabelText("指标名称"), {
      target: { value: "实付销售额" },
    });
    fireEvent.change(within(dialog).getByLabelText("计算口径说明"), {
      target: { value: "按支付日汇总，扣除已退款金额" },
    });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /商城订单/ }));
    fireEvent.click(
      within(dialog).getByRole("button", { name: "加入待保存约定" }),
    );
    expect(screen.getByText("口径草稿")).toBeInTheDocument();
    expect(screen.getByText(/尚未计算/)).toBeInTheDocument();
    fireEvent.click(
      screen.getAllByRole("button", { name: "保存业务约定" })[0]!,
    );
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const metric = saved.mock.calls[0]![0].metrics[0]!;
    expect(metric).toMatchObject({
      name: "实付销售额",
      formulaDescription: "按支付日汇总，扣除已退款金额",
      sourceIds: ["orders"],
    });
    expect(metric).not.toHaveProperty("status");
    expect(metric).not.toHaveProperty("calculationStatus");
    expect(confirm).not.toHaveBeenCalled();
  });
});
