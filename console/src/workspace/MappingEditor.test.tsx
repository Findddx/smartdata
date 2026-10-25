import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { MappingEditor } from "./MappingEditor";
import { workspaceClient } from "./client";
import type {
  LineageMappings,
  WorkspaceLineage,
  WorkspaceState,
} from "./types";

vi.mock("./client", () => ({ workspaceClient: { lineageMappings: vi.fn() } }));
const old: LineageMappings = {
  version: 3,
  entities: [
    {
      id: "customer",
      name: "客户",
      entityType: "客户主档",
      inputFields: [{ sourceId: "s", assetId: "a", fieldName: "customer_id" }],
      transformationDescription: "由客户编号识别，尚未物化",
    },
  ],
  metrics: [
    {
      metricId: "sales",
      definitionVersion: 2,
      inputEntityIds: ["customer"],
      inputFields: [],
      transformationDescription: "人工登记收入分析依赖",
    },
  ],
  decisions: [
    {
      goalId: "check",
      inputMetricIds: ["sales"],
      decisionQuestion: "检查收入定义",
    },
  ],
};
const graph: WorkspaceLineage = {
  stateVersion: 10,
  mappingVersion: 3,
  generatedAt: "2026-10-25T08:00:00Z",
  scope: {
    briefVersion: 2,
    sourceIds: ["s"],
    excludedAuthorizedSourceCount: 0,
  },
  nodes: [],
  edges: [],
  notices: [],
};
const workspace = {
  stateVersion: 10,
  sources: [],
  scans: [],
  assets: [],
  businessBrief: { metrics: [], goals: [] },
} as unknown as WorkspaceState;
function setup() {
  const onSave = vi.fn().mockResolvedValue(undefined);
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MappingEditor
        workspace={workspace}
        graph={graph}
        pending={false}
        onClose={vi.fn()}
        onSave={onSave}
      />
    </QueryClientProvider>,
  );
  return onSave;
}
beforeEach(() => {
  vi.mocked(workspaceClient.lineageMappings)
    .mockReset()
    .mockResolvedValue(structuredClone(old));
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});
it("preserves other mapping layers and both version fences while showing old out-of-scope fields", async () => {
  const save = setup();
  const input = await screen.findByRole("textbox", { name: "实体名称" });
  expect(
    screen.getByText("不在本轮有效范围；取消勾选可移除此旧引用"),
  ).toBeInTheDocument();
  fireEvent.change(input, { target: { value: "客户主实体" } });
  fireEvent.click(screen.getByRole("button", { name: "保存声明" }));
  await waitFor(() => expect(save).toHaveBeenCalled());
  const command = save.mock.calls[0]![0];
  expect(command).toMatchObject({
    expectedStateVersion: 10,
    expectedMappingVersion: 3,
    metrics: old.metrics,
    decisions: old.decisions,
  });
  expect(command.entities[0].inputFields).toEqual(old.entities[0]!.inputFields);
  expect(command.entities[0].name).toBe("客户主实体");
  expect(await screen.findByRole("alert")).toHaveTextContent("草稿仍保留");
  expect(input).toHaveValue("客户主实体");
});
it("does not offer an empty overwrite when the saved mappings cannot be loaded", async () => {
  vi.mocked(workspaceClient.lineageMappings).mockRejectedValue(
    new Error("授权服务不可用"),
  );
  const save = setup();
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "不能用空配置覆盖已有声明",
  );
  expect(
    screen.queryByRole("button", { name: "保存声明" }),
  ).not.toBeInTheDocument();
  expect(save).not.toHaveBeenCalled();
});
it("keeps a conflicted draft pinned to its original mapping version until the user explicitly reloads", async () => {
  const updated = { ...structuredClone(old), version: 4, entities: [{ ...old.entities[0]!, name: "另一位业务人员的新定义" }] };
  vi.mocked(workspaceClient.lineageMappings).mockReset().mockResolvedValueOnce(structuredClone(old)).mockResolvedValue(updated);
  const save = setup();
  const name = await screen.findByRole("textbox", { name: "实体名称" });
  fireEvent.change(name, { target: { value: "我的未保存定义" } });
  fireEvent.click(screen.getByRole("button", { name: "保存声明" }));
  await screen.findByText(/已有更新的映射版本/);
  expect(name).toHaveValue("我的未保存定义");
  expect(screen.getByRole("button", { name: "保存声明" })).toBeDisabled();
  fireEvent.submit(screen.getByRole("tabpanel"));
  expect(save).toHaveBeenCalledTimes(1);
  expect(save.mock.calls[0]![0].expectedMappingVersion).toBe(3);
  fireEvent.click(screen.getByRole("button", { name: "载入最新映射（替换草稿）" }));
  expect(name).toHaveValue("另一位业务人员的新定义");
  fireEvent.change(name, { target: { value: "复核后重新登记" } });
  fireEvent.click(screen.getByRole("button", { name: "保存声明" }));
  await waitFor(() => expect(save).toHaveBeenCalledTimes(2));
  expect(save.mock.calls[1]![0].expectedMappingVersion).toBe(4);
});
