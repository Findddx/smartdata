import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { enterpriseMe, localMe } from "../test/identity-fixtures";
import type { MeView } from "../auth/types";
import { configureSession } from "../auth/transport";
import { Workspace } from "./Workspace";
import { workspaceClient } from "./client";
import type { WorkspaceState } from "./types";

vi.mock("./client", () => ({
  workspaceClient: {
    get: vi.fn(),
    bootstrap: vi.fn(),
    addSource: vi.fn(),
    scan: vi.fn(),
    provision: vi.fn(),
    transition: vi.fn(),
    comment: vi.fn(),
    ask: vi.fn(),
    report: vi.fn(),
    resetDemo: vi.fn(),
    replaceSnapshot: vi.fn(),
    saveBusinessBrief: vi.fn(),
    confirmMetric: vi.fn(),
    businessBriefHistory: vi.fn().mockResolvedValue([]),
  },
}));

function fixture(): WorkspaceState {
  return {
    stateVersion: 7,
    mode: "LOCAL_PERSISTENT",
    sources: [
      {
        id: "source-orders",
        name: "商城订单",
        kind: "JSON_SNAPSHOT",
        provenance: "USER",
        status: "SCANNED",
        assetCount: 1,
        credentialConfigured: false,
        stateVersion: 2,
        lastScannedAt: "2026-10-25T08:00:00Z",
      },
    ],
    assets: [
      {
        id: "asset-orders",
        sourceId: "source-orders",
        name: "商城订单",
        provenance: "USER",
        rowCount: 3,
        totalRows: 3,
        sampleLimit: 1000,
        sampled: false,
        columns: [
          {
            name: "order_id",
            inferredType: "STRING",
            missingCount: 0,
            distinctCount: 2,
          },
        ],
        observedAt: "2026-10-25T08:00:00Z",
        evidenceSha256: "a".repeat(64),
        sampling: "UPLOADED_SNAPSHOT_COMPLETE",
      },
    ],
    scans: [
      {
        id: "scan-1",
        sourceId: "source-orders",
        status: "SUCCEEDED",
        startedAt: "2026-10-25T08:00:00Z",
        completedAt: "2026-10-25T08:00:00Z",
        assetIds: ["asset-orders"],
        findingIds: ["finding-1"],
      },
    ],
    findings: [
      {
        id: "finding-1",
        assetId: "asset-orders",
        sourceId: "source-orders",
        scanId: "scan-1",
        column: "order_id",
        active: true,
        ruleId: "DUPLICATE_PRIMARY_KEY",
        title: "订单标识重复",
        severity: "HIGH",
        observed: "3 条记录出现 1 个重复订单标识",
        expected: "order_id 应当唯一",
        suggestion: "核对原始订单与同步任务去重逻辑",
        evidence: {
          sha256: "a".repeat(64),
          sampledRows: 3,
          totalRows: 3,
          sampleLimit: 1000,
          observedAt: "2026-10-25T08:00:00Z",
          sampling: "UPLOADED_SNAPSHOT_COMPLETE",
        },
      },
      {
        id: "finding-archived",
        assetId: "asset-orders",
        sourceId: "source-orders",
        scanId: "scan-0",
        active: false,
        ruleId: "MISSING_VALUE",
        title: "历史缺失记录",
        severity: "MEDIUM",
        observed: "旧样本缺失",
        expected: "字段存在",
        suggestion: "核对",
        evidence: {
          sha256: "b".repeat(64),
          sampledRows: 3,
          totalRows: 3,
          sampleLimit: 1000,
          observedAt: "2026-10-24T08:00:00Z",
          sampling: "UPLOADED_SNAPSHOT_COMPLETE",
        },
      },
    ],
    assistants: [
      {
        id: "assistant-analysis",
        role: "ANALYSIS",
        name: "数据分析助手",
        responsibility: "核对数据质量",
        status: "READY",
        method: "DETERMINISTIC_RULES",
        findingIds: ["finding-1"],
        taskIds: ["task-1"],
      },
      {
        id: "assistant-decision",
        role: "DECISION",
        name: "项目决策助手",
        responsibility: "梳理高优先问题",
        status: "READY",
        method: "DETERMINISTIC_RULES",
        findingIds: ["finding-1"],
        taskIds: ["task-1"],
      },
    ],
    tasks: [
      {
        id: "task-1",
        assistantId: "assistant-analysis",
        findingId: "finding-1",
        title: "核实重复订单",
        description: "检查订单来源及同步去重逻辑",
        status: "PROPOSED",
        evidenceRefs: ["finding-1"],
        createdAt: "2026-10-25T08:00:00Z",
        updatedAt: "2026-10-25T08:00:00Z",
        stateVersion: 1,
      },
    ],
    comments: [],
    messages: [],
    audit: [],
    capabilities: {},
  };
}
function renderWorkspace(identity: MeView = localMe()) {
  configureSession(identity);
  const query = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  const result = render(
    <QueryClientProvider client={query}>
      <Workspace
        identity={identity}
        onLocalActorChange={vi.fn()}
        onIdentityRefresh={vi.fn()}
        onLogout={vi.fn()}
      />
    </QueryClientProvider>,
  );
  return { ...result, queryClient: query };
}
beforeEach(() => {
  vi.unstubAllEnvs();
  localStorage.clear();
  sessionStorage.clear();
  window.location.hash = "/overview";
  vi.mocked(workspaceClient.get).mockReset().mockResolvedValue(fixture());
  vi.mocked(workspaceClient.ask).mockReset();
  if (!HTMLDialogElement.prototype.showModal)
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
    };
  if (!HTMLDialogElement.prototype.close)
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
});

describe("企业数据工作空间", () => {
  it("focuses the command input and waits for Chinese composition before opening a result", async () => {
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const input = screen.getByRole("combobox", { name: "搜索" });
    expect(input).toHaveFocus();
    fireEvent.change(input, { target: { value: "数据连接" } });
    fireEvent.keyDown(input, { key: "Enter", isComposing: true, keyCode: 229 });
    expect(
      screen.getByRole("dialog", { name: "搜索工作空间" }),
    ).toBeInTheDocument();
    fireEvent.keyDown(input, { key: "Enter" });
    expect(
      await screen.findByRole("heading", { name: "数据连接" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("retains an unsaved business brief while the user checks another workspace page", async () => {
    const initial = fixture();
    initial.businessBrief = null;
    vi.mocked(workspaceClient.get).mockResolvedValue(initial);
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "业务约定" }));
    await screen.findByRole("heading", { name: "业务约定" });
    fireEvent.change(screen.getByLabelText("企业或业务团队名称"), {
      target: { value: "尚未保存的业务团队" },
    });
    fireEvent.click(screen.getByRole("button", { name: "服务团队" }));
    await screen.findByRole("heading", { name: "服务团队" });
    fireEvent.click(screen.getByRole("button", { name: "业务约定" }));
    expect(await screen.findByLabelText("企业或业务团队名称")).toHaveValue(
      "尚未保存的业务团队",
    );
    expect(workspaceClient.saveBusinessBrief).not.toHaveBeenCalled();
  }, 15_000);
  it("uses the current server flag instead of browser build flags for role rehearsal", async () => {
    vi.stubEnv("DEV", false);
    vi.stubEnv("VITE_DEMO_AUTH", "false");
    localStorage.setItem("smartdata.localActor", "admin");
    renderWorkspace(
      localMe({
        accountId: "order-specialist",
        displayName: "本地订单专员",
        roles: ["ORDER_SPECIALIST"],
        capabilities: {
          canUseWorkspace: true,
          canManageMembers: false,
          localRoleSwitch: false,
        },
      }),
    );
    await screen.findByRole("heading", { name: "工作清单" });
    expect(
      screen.queryByRole("combobox", { name: "本地演示身份" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("本地订单专员")).toBeInTheDocument();
  });
  it("uses enterprise roles even when the browser prefers local admin and hides the rehearsal tool", async () => {
    localStorage.setItem("smartdata.localActor", "admin");
    renderWorkspace(
      enterpriseMe({
        roles: ["AUDITOR"],
        capabilities: {
          canUseWorkspace: true,
          canManageMembers: false,
          localRoleSwitch: false,
        },
      }),
    );
    await screen.findByRole("heading", { name: "工作清单" });
    expect(
      screen.queryByRole("combobox", { name: "本地演示身份" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "履约异常处理" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "企业成员" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "连接数据" })).toBeDisabled();
    expect(screen.getByText("陈负责人")).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    fireEvent.change(screen.getByRole("combobox", { name: "搜索" }), {
      target: { value: "企业成员" },
    });
    expect(
      within(screen.getByRole("dialog", { name: "搜索工作空间" })).queryByText(
        "企业成员",
        { exact: true },
      ),
    ).not.toBeInTheDocument();
    fireEvent.click(
      within(screen.getByRole("dialog", { name: "搜索工作空间" })).getByRole(
        "button",
        { name: "关闭对话框" },
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "数据连接" }));
    expect(
      await screen.findByRole("button", { name: "添加数据来源" }),
    ).toBeDisabled();
    expect(screen.getByRole("button", { name: "重新盘点" })).toBeDisabled();
  });
  it("offers only the enabled enterprise snapshot connector instead of unsupported remote source setup", async () => {
    renderWorkspace(enterpriseMe());
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "连接数据" }));
    const dialog = screen.getByRole("dialog", { name: "连接一个数据来源" });
    expect(
      within(dialog).getByRole("button", { name: /数据文件/ }),
    ).toBeInTheDocument();
    expect(
      within(dialog).queryByRole("button", { name: /业务 API/ }),
    ).not.toBeInTheDocument();
    expect(
      within(dialog).queryByRole("button", { name: /PostgreSQL/ }),
    ).not.toBeInTheDocument();
  });
  it("does not offer self verification just because the same real account has a reviewer role", async () => {
    const me = enterpriseMe();
    const state = fixture();
    state.tasks[0] = {
      ...state.tasks[0]!,
      status: "COMPLETED",
      ownerId: me.accountId!,
    };
    vi.mocked(workspaceClient.get).mockResolvedValue(state);
    renderWorkspace(me);
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "协作任务" }));
    fireEvent.click(
      await screen.findByRole("button", { name: /核实重复订单/ }),
    );
    const dialog = screen.getByRole("dialog", { name: "核实重复订单" });
    fireEvent.change(
      within(dialog).getByPlaceholderText(/例如：已核对原始订单/),
      { target: { value: "希望提交复核" } },
    );
    expect(
      within(dialog).getByRole("button", { name: "核验最新观测" }),
    ).toBeDisabled();
    expect(
      within(dialog).getByText(/同一账号切换角色不能代替第二个人/),
    ).toBeInTheDocument();
    expect(
      within(dialog).queryByRole("combobox", { name: "本地复核身份" }),
    ).not.toBeInTheDocument();
  });
  it("does not put a late command result back into a new account's workspace cache", async () => {
    let finish!: (state: WorkspaceState) => void;
    vi.mocked(workspaceClient.ask).mockImplementation(
      () =>
        new Promise<WorkspaceState>((resolve) => {
          finish = resolve;
        }),
    );
    const view = renderWorkspace(enterpriseMe());
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "服务团队" }));
    fireEvent.click(await screen.findByRole("tab", { name: /分析助手/ }));
    fireEvent.change(await screen.findByLabelText("向服务助手提问"), {
      target: { value: "请核对当前证据" },
    });
    fireEvent.click(screen.getByRole("button", { name: "发送问题" }));
    await waitFor(() => expect(workspaceClient.ask).toHaveBeenCalled());
    view.unmount();
    configureSession(enterpriseMe({ accountId: `acct_${"2".repeat(64)}` }));
    const newAccountView = { ...fixture(), stateVersion: 20 };
    view.queryClient.setQueryData(["workspace"], newAccountView);
    await act(async () => finish({ ...fixture(), stateVersion: 8 }));
    expect(view.queryClient.getQueryData(["workspace"])).toEqual(
      newAccountView,
    );
  });
  it("does not send an old workspace command through a replacement session before unmount", async () => {
    renderWorkspace(enterpriseMe());
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "服务团队" }));
    fireEvent.click(await screen.findByRole("tab", { name: /分析助手/ }));
    fireEvent.change(await screen.findByLabelText("向服务助手提问"), {
      target: { value: "来自旧页面的提问" },
    });
    configureSession(enterpriseMe({ accountId: `acct_${"2".repeat(64)}` }));
    fireEvent.click(screen.getByRole("button", { name: "发送问题" }));
    expect(workspaceClient.ask).not.toHaveBeenCalled();
  });
  it("keeps failed connections explicit without silently substituting demonstration data", async () => {
    vi.mocked(workspaceClient.get).mockRejectedValue(new Error("服务不可用"));
    renderWorkspace();
    expect(
      await screen.findByRole("heading", { name: "无法连接工作区服务" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "重新连接" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("先体验电商示例")).not.toBeInTheDocument();
    expect(screen.queryByText("商城订单")).not.toBeInTheDocument();
  });

  it("shows only active findings and exposes their exact observations and evidence bounds", async () => {
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    expect(screen.queryByText("历史缺失记录")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /订单标识重复/ }));
    const dialog = await screen.findByRole("dialog", { name: "订单标识重复" });
    expect(
      within(dialog).getByText("3 条记录出现 1 个重复订单标识"),
    ).toBeInTheDocument();
    expect(within(dialog).getByText("order_id 应当唯一")).toBeInTheDocument();
    expect(within(dialog).getByText("a".repeat(64))).toBeInTheDocument();
    expect(within(dialog).getByText(/指纹证明输入版本/)).toBeInTheDocument();
  });

  it("links the decision assistant to tasks owned by another specialist rather than showing an empty list", async () => {
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "服务团队" }));
    await screen.findByRole("heading", { name: "服务团队" });
    fireEvent.click(screen.getByRole("tab", { name: /决策助手/ }));
    expect(await screen.findByText("负责：数据分析助手")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /核实重复订单/ }),
    ).toBeInTheDocument();
  });

  it("requires an explicit collection time and submits real rows with the current workspace version", async () => {
    const initial = fixture();
    const saved = {
      ...initial,
      stateVersion: 8,
      sources: [
        ...initial.sources,
        {
          id: "source-new",
          name: "修复订单",
          kind: "JSON_SNAPSHOT" as const,
          provenance: "USER" as const,
          status: "CONFIGURED",
          assetCount: 0,
          credentialConfigured: false,
          stateVersion: 1,
        },
      ],
    };
    vi.mocked(workspaceClient.addSource).mockResolvedValue(saved);
    vi.mocked(workspaceClient.scan).mockResolvedValue({
      ...saved,
      stateVersion: 9,
    });
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "连接数据" }));
    const dialog = await screen.findByRole("dialog", {
      name: "连接一个数据来源",
    });
    fireEvent.change(within(dialog).getByLabelText("来源名称"), {
      target: { value: "修复订单" },
    });
    fireEvent.change(within(dialog).getByLabelText("JSON 记录"), {
      target: { value: '[{"order_id":"A-1"}]' },
    });
    const collected = within(dialog).getByLabelText(/这份数据的实际采集时间/);
    expect(collected).toHaveValue("");
    fireEvent.change(collected, { target: { value: "2026-10-25T08:00" } });
    fireEvent.click(
      within(dialog).getByRole("button", { name: "保存并开始盘点" }),
    );
    await waitFor(() =>
      expect(workspaceClient.addSource).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedStateVersion: 7,
          name: "修复订单",
          kind: "JSON_SNAPSHOT",
          snapshotRows: [{ order_id: "A-1" }],
          observedAt: expect.stringContaining("2026-10-25T"),
        }),
      ),
    );
    await waitFor(() =>
      expect(workspaceClient.scan).toHaveBeenCalledWith("source-new", 8),
    );
  });

  it("keeps a failed scan marked failed and does not label old evidence as newly observed", async () => {
    const state = fixture();
    state.sources[0]!.status = "FAILED";
    state.sources[0]!.lastError = "来源返回 403，未获取新数据";
    vi.mocked(workspaceClient.get).mockResolvedValue(state);
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "数据连接" }));
    expect(
      await screen.findByText("来源返回 403，未获取新数据"),
    ).toBeInTheDocument();
    expect(screen.getByText(/最近盘点尝试/)).toBeInTheDocument();
    expect(screen.getByText("读取失败")).toBeInTheDocument();
  });

  it("persists restrained themes and does not offer a nonfunctional production token login", async () => {
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "空间设置" }));
    await screen.findByRole("heading", { name: "空间设置" });
    fireEvent.click(screen.getByRole("button", { name: /石墨 · 钛银/ }));
    expect(localStorage.getItem("smartdata.theme")).toBe("titanium");
    expect(screen.queryByLabelText("临时访问令牌")).not.toBeInTheDocument();
    expect(screen.getByText(/本地角色切换用于功能演练/)).toBeInTheDocument();
  });

  it("labels model reasoning and opens the actual historical evidence cited in its answer", async () => {
    const state = fixture();
    state.capabilities.assistantMethod = "SPRING_AI_EVIDENCE_V1";
    state.messages = [
      {
        id: "message-model",
        assistantId: "assistant-analysis",
        actorId: "demo:admin",
        question: "依据是什么？",
        answer: "应先核对旧样本。",
        createdAt: "2026-10-25T09:00:00Z",
        method: "SPRING_AI_EVIDENCE_V1",
        status: "SUCCEEDED",
        uncertainty: ["尚未取得实时订单"],
        evidenceRefs: ["finding-archived"],
        trace: {
          providerProfile: "PRIVATE_COMPATIBLE",
          modelId: "private-test",
          modelVersion: "frozen-1",
          promptVersion: "evidence-1",
          contextSha256: "c".repeat(64),
        },
      },
    ];
    vi.mocked(workspaceClient.get).mockResolvedValue(state);
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "服务团队" }));
    fireEvent.click(await screen.findByRole("tab", { name: /分析助手/ }));
    expect(screen.getByText("模型分析 · 可追溯")).toBeInTheDocument();
    expect(
      screen.getByText(/提问与获准摘要会发送到已配置模型/),
    ).toBeInTheDocument();
    expect(screen.getByText("尚未取得实时订单")).toBeInTheDocument();
    expect(screen.getByText("frozen-1")).toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: /历史缺失记录 · 历史观测/ }),
    );
    const dialog = await screen.findByRole("dialog", { name: "历史缺失记录" });
    expect(within(dialog).getByText("旧样本缺失")).toBeInTheDocument();
    expect(within(dialog).getByText("b".repeat(64))).toBeInTheDocument();
  });

  it("retains the question and displays a recorded model failure without claiming success", async () => {
    const state = fixture();
    state.capabilities.assistantMethod = "SPRING_AI_EVIDENCE_V1";
    vi.mocked(workspaceClient.get).mockResolvedValue(state);
    vi.mocked(workspaceClient.ask).mockResolvedValue({
      ...state,
      stateVersion: 8,
      messages: [
        {
          id: "message-failed",
          assistantId: "assistant-analysis",
          actorId: "demo:admin",
          question: "先检查什么？",
          answer: "模型服务未完成本次答复。",
          evidenceRefs: [],
          createdAt: "2026-10-25T09:00:00Z",
          method: "SPRING_AI_EVIDENCE_V1",
          status: "FAILED",
          uncertainty: [],
          errorCode: "MODEL_TIMEOUT",
        },
      ],
    });
    renderWorkspace();
    await screen.findByRole("heading", { name: "工作清单" });
    fireEvent.click(screen.getByRole("button", { name: "服务团队" }));
    fireEvent.click(await screen.findByRole("tab", { name: /分析助手/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "向服务助手提问" }), {
      target: { value: "先检查什么？" },
    });
    fireEvent.click(screen.getByRole("button", { name: "发送问题" }));
    expect(
      await screen.findByText("失败记录：MODEL_TIMEOUT"),
    ).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "向服务助手提问" })).toHaveValue(
      "先检查什么？",
    );
    expect(screen.getByText("答复未完成，已保存原因")).toBeInTheDocument();
    expect(
      screen.queryByText("整理有据可查的答复已完成"),
    ).not.toBeInTheDocument();
  });
});
