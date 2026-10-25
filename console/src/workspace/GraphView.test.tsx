import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { GraphView } from "./GraphView";
import { workspaceClient } from "./client";
import type { LineageNode, WorkspaceLineage, WorkspaceState } from "./types";

vi.mock("./client", () => ({
  workspaceClient: {
    lineage: vi.fn(),
    lineageMappings: vi.fn(),
    lineageFields: vi.fn(),
  },
}));
const node = (patch: Partial<LineageNode>): LineageNode => ({
  id: "table-orders",
  label: "订单记录",
  summary: "成功盘点的表元数据",
  layer: "L0",
  kind: "TABLE",
  shape: "TABLE_GRID",
  ref: {
    type: "asset",
    id: "asset-1",
    sourceId: "source-1",
    assetId: "asset-1",
  },
  evidenceNature: "OBSERVED",
  materialization: "METADATA_ONLY",
  provenance: "USER",
  sourceIds: ["source-1"],
  evidenceRefs: [
    {
      sourceId: "source-1",
      assetId: "asset-1",
      scanId: "scan-1",
      sha256: "a".repeat(64),
      observedAt: "2026-10-25T08:00:00Z",
    },
  ],
  observedAt: "2026-10-25T08:00:00Z",
  sensitivity: "UNKNOWN",
  freshness: "CURRENT",
  authorization: "METADATA_VISIBLE",
  scopeStatus: "IN_SCOPE",
  notices: [],
  ...patch,
});
function fixture(): WorkspaceLineage {
  return {
    stateVersion: 7,
    mappingVersion: 2,
    generatedAt: "2026-10-25T10:00:00Z",
    scope: {
      briefVersion: 1,
      sourceIds: ["source-1"],
      excludedAuthorizedSourceCount: 1,
    },
    nodes: [
      node({}),
      node({
        id: "field-customer",
        label: "customer_id",
        kind: "FIELD",
        shape: "FIELD_PILL",
        ref: {
          type: "field",
          id: "field-customer",
          sourceId: "source-1",
          assetId: "asset-1",
          fieldName: "customer_id",
        },
      }),
      node({
        id: "entity-customer",
        label: "客户实体",
        summary: "业务人员登记的客户定义",
        kind: "BUSINESS_ENTITY",
        layer: "L1",
        shape: "ENTITY_HEXAGON",
        ref: { type: "entity", id: "customer" },
        evidenceNature: "DECLARED",
        materialization: "NOT_MATERIALIZED",
        freshness: "UNKNOWN",
        evidenceRefs: [],
        notices: ["实体映射尚未执行，不代表已经生成客户画像。"],
      }),
      node({
        id: "metric-sales",
        label: "净销售额",
        kind: "METRIC",
        layer: "L2",
        shape: "METRIC_CIRCLE",
        ref: { type: "metric", id: "sales", definitionVersion: 1 },
        evidenceNature: "DECLARED",
        materialization: "NOT_MATERIALIZED",
        evidenceRefs: [],
        freshness: "UNKNOWN",
      }),
      node({
        id: "plan",
        label: "核实销售口径",
        kind: "DECISION_PLAN",
        layer: "L3",
        shape: "DECISION_DIAMOND",
        ref: { type: "goal", id: "goal-1" },
        evidenceNature: "PLANNED",
        materialization: "NOT_MATERIALIZED",
        evidenceRefs: [],
        freshness: "UNKNOWN",
      }),
    ],
    edges: [
      {
        id: "table-field",
        from: "table-orders",
        to: "field-customer",
        kind: "CONTAINS",
        evidenceNature: "OBSERVED",
        lineStyle: "SOLID",
        transformationStatus: "NOT_APPLICABLE",
        description: "字段属于该次观察的数据表",
        evidenceRefs: [],
      },
      {
        id: "table-entity",
        from: "table-orders",
        to: "entity-customer",
        kind: "MAPPED_TO",
        evidenceNature: "DECLARED",
        lineStyle: "DASHED",
        transformationStatus: "UNKNOWN",
        description: "人工登记的业务依赖，不等于计算已执行",
        evidenceRefs: [],
      },
      {
        id: "entity-metric",
        from: "entity-customer",
        to: "metric-sales",
        kind: "FEEDS_INTO",
        evidenceNature: "DECLARED",
        lineStyle: "DASHED",
        transformationStatus: "DECLARED_ONLY",
        description: "声明的统计输入",
        evidenceRefs: [],
      },
      {
        id: "metric-plan",
        from: "metric-sales",
        to: "plan",
        kind: "INFORMS",
        evidenceNature: "PLANNED",
        lineStyle: "DOTTED",
        transformationStatus: "DECLARED_ONLY",
        description: "指标服务于规划目标",
        evidenceRefs: [],
      },
    ],
    notices: ["只展示当前授权范围，不推测全貌。"],
  };
}
const workspace: WorkspaceState = {
  stateVersion: 7,
  mode: "LOCAL_PERSISTENT",
  sources: [
    {
      id: "source-1",
      name: "订单来源",
      kind: "JSON_SNAPSHOT",
      provenance: "USER",
      status: "SCANNED",
      assetCount: 1,
      credentialConfigured: false,
      stateVersion: 2,
    },
  ],
  assets: [
    {
      id: "asset-1",
      sourceId: "source-1",
      name: "订单记录",
      provenance: "USER",
      rowCount: 3,
      columns: [],
      sampleLimit: 1000,
      sampled: false,
      observedAt: "2026-10-25T08:00:00Z",
      evidenceSha256: "a".repeat(64),
    },
  ],
  scans: [],
  findings: [],
  assistants: [],
  tasks: [],
  messages: [],
  comments: [],
  audit: [],
  capabilities: {},
};
function setup(canEdit = true) {
  const onAsset = vi.fn(),
    navigate = vi.fn(),
    onSave = vi.fn();
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <GraphView
        workspace={workspace}
        canEdit={canEdit}
        pending={false}
        onAsset={onAsset}
        navigate={navigate}
        onConnect={vi.fn()}
        onDemo={vi.fn()}
        onSave={onSave}
      />
    </QueryClientProvider>,
  );
  return { onAsset, navigate, onSave, queryClient };
}
beforeEach(() => {
  window.history.replaceState(null, "", "#/graph");
  vi.mocked(workspaceClient.lineage).mockReset().mockResolvedValue(fixture());
  vi.mocked(workspaceClient.lineageMappings).mockReset();
});
describe("语义数据图谱", () => {
  it("makes an incomplete projection explicit and limits upstream claims to the visible graph", async () => {
    const partial = fixture();
    partial.coverage = {
      partial: true,
      totalSources: 1,
      shownSources: 1,
      totalTables: 1,
      shownTables: 1,
      totalFields: 10000,
      shownFields: 1,
      omittedDerivedDefinitions: 2,
      nodeLimit: 3000,
      edgeLimit: 6000,
      fieldLimit: 256,
    };
    vi.mocked(workspaceClient.lineage).mockResolvedValue(partial);
    setup();
    expect(await screen.findByText("当前仅展示部分依赖")).toBeInTheDocument();
    expect(screen.getByText(/不能据此判断完整影响/)).toHaveTextContent(
      "字段 1/10000",
    );
    fireEvent.click(
      screen.getByRole("button", { name: "客户实体，L1 业务实体，人工声明" }),
    );
    expect(
      screen.getByText("只沿本视图已显示的关系聚焦，不补全未知或未覆盖依赖。"),
    ).toBeInTheDocument();
  });
  it("keeps observations, declarations and plans distinct and exposes independent state", async () => {
    setup();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "客户实体，L1 业务实体，人工声明",
      }),
    );
    const inspector = screen.getByRole("complementary", { name: "图谱上下文" });
    expect(within(inspector).getByText("尚未生成")).toBeInTheDocument();
    expect(within(inspector).getByText("观察时效未知")).toBeInTheDocument();
    expect(within(inspector).getByText("敏感级别未确认")).toBeInTheDocument();
    expect(
      within(inspector).getByText("本工作区元数据可见"),
    ).toBeInTheDocument();
    expect(within(inspector).getByText(/实体映射尚未执行/)).toBeInTheDocument();
    expect(window.location.hash).toContain("node=entity-customer");
    expect(
      document.querySelector('path[stroke-dasharray="7 5"]'),
    ).toBeInTheDocument();
    expect(
      document.querySelector('path[stroke-dasharray="2 5"]'),
    ).toBeInTheDocument();
  });
  it("focuses only registered upstream relations and keeps table evidence reachable", async () => {
    const { onAsset } = setup();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "客户实体，L1 业务实体，人工声明",
      }),
    );
    fireEvent.click(screen.getByRole("button", { name: "上游" }));
    expect(
      screen.queryByRole("button", { name: "净销售额，L2 指标定义，人工声明" }),
    ).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "订单记录，L0 数据表，实际观察" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "查看字段与证据" }));
    expect(onAsset).toHaveBeenCalledWith(workspace.assets[0]);
  });
  it("shows unknown transformations instead of implying that a visual link is a calculation", async () => {
    setup();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "订单记录 → 客户实体，人工声明",
      }),
    );
    expect(
      screen.getByText("字段级转换未采集，不能据连线推断计算公式。"),
    ).toBeInTheDocument();
  });
  it("shares filtering with an accessible node list and makes fields an explicit expansion", async () => {
    setup(false);
    await screen.findByRole("button", {
      name: "订单记录，L0 数据表，实际观察",
    });
    expect(
      screen.queryByRole("button", { name: /customer_id，L0/ }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "筛选" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "展开其余字段" }));
    expect(
      screen.getByRole("button", { name: /customer_id，L0/ }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "显示节点列表" }));
    fireEvent.change(screen.getByRole("textbox", { name: "搜索图谱节点" }), {
      target: { value: "净销售额" },
    });
    const list = screen.getByLabelText("节点列表");
    expect(within(list).getByText("净销售额")).toBeInTheDocument();
    expect(within(list).queryByText("客户实体")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "管理映射" })).toBeDisabled();
  });
  it("never substitutes a fabricated graph when the lineage endpoint fails", async () => {
    vi.mocked(workspaceClient.lineage).mockRejectedValue(
      new Error("图谱接口尚未就绪"),
    );
    setup();
    expect(
      await screen.findByRole("heading", { name: "图谱暂时不可用" }),
    ).toBeInTheDocument();
    expect(screen.getByText("图谱接口尚未就绪")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /客户实体，L1/ }),
    ).not.toBeInTheDocument();
  });
  it("does not leave a cached inspector looking current after its refresh fails", async () => {
    const { queryClient } = setup();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "客户实体，L1 业务实体，人工声明",
      }),
    );
    expect(
      screen.getByRole("complementary", { name: "图谱上下文" }),
    ).toBeInTheDocument();
    vi.mocked(workspaceClient.lineage).mockRejectedValueOnce(
      new Error("连接已经中断"),
    );
    await act(async () => {
      await queryClient.refetchQueries({
        queryKey: ["workspace-lineage", workspace.stateVersion],
      });
    });
    expect(
      await screen.findByRole("heading", { name: "图谱暂时不可用" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("complementary", { name: "图谱上下文" }),
    ).not.toBeInTheDocument();
    expect(document.querySelector(".sd-graph-node")).not.toBeInTheDocument();
  });
  it("does not mix a newer graph with an older source and evidence snapshot", async () => {
    vi.mocked(workspaceClient.lineage).mockResolvedValue({
      ...fixture(),
      stateVersion: 8,
    });
    setup();
    expect(
      await screen.findByRole("heading", { name: "工作区观察已经更新" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "客户实体，L1 业务实体，人工声明" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "刷新工作区" }),
    ).toBeInTheDocument();
  });
});
