import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { beforeEach, expect, it, vi } from "vitest";
import { LineageFieldPicker } from "./LineageFieldPicker";
import { workspaceClient } from "./client";
import type {
  LineageFieldPage,
  PhysicalFieldRef,
  WorkspaceLineage,
  WorkspaceState,
} from "./types";

vi.mock("./client", () => ({ workspaceClient: { lineageFields: vi.fn() } }));
const workspace = {
  stateVersion: 8,
  sources: [{ id: "s", name: "订单来源", status: "SCANNED" }],
  assets: [{ id: "a", sourceId: "s", name: "客户字段表" }],
  scans: [{ sourceId: "s", status: "SUCCEEDED", assetIds: ["a"] }],
} as unknown as WorkspaceState;
const graph = { scope: { sourceIds: ["s"] } } as WorkspaceLineage;
function page(offset: number): LineageFieldPage {
  return {
    stateVersion: 8,
    sourceId: "s",
    assetId: "a",
    totalFields: 129,
    offset,
    limit: 128,
    hasMore: offset === 0,
    edges: [],
    nodes: Array.from({ length: offset === 0 ? 128 : 1 }, (_, index) => ({
      id: `field-${offset + index}`,
      label: `field_${offset + index}`,
      summary: "字段元数据",
      layer: "L0",
      kind: "FIELD",
      shape: "FIELD_PILL",
      ref: {
        type: "FIELD",
        id: `field-${offset + index}`,
        sourceId: "s",
        assetId: "a",
        fieldName: `field_${offset + index}`,
      },
      evidenceNature: "OBSERVED",
      materialization: "METADATA_ONLY",
      provenance: "USER",
      sourceIds: ["s"],
      evidenceRefs: [],
      sensitivity: "UNKNOWN",
      freshness: "CURRENT",
      authorization: "METADATA_VISIBLE",
      scopeStatus: "IN_SCOPE",
      notices: [],
    })),
  };
}
function setup() {
  function Harness() {
    const [selected, change] = useState<PhysicalFieldRef[]>([]);
    return (
      <LineageFieldPicker
        workspace={workspace}
        graph={graph}
        selected={selected}
        onChange={change}
        label="客户实体输入"
      />
    );
  }
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <Harness />
    </QueryClientProvider>,
  );
}
beforeEach(() =>
  vi
    .mocked(workspaceClient.lineageFields)
    .mockReset()
    .mockImplementation(async (_sourceId, _assetId, offset) =>
      page(offset ?? 0),
    ),
);
it("reads beyond the main drawing budget and preserves explicit field selection across pages", async () => {
  setup();
  fireEvent.click(await screen.findByRole("checkbox", { name: "field_127" }));
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  fireEvent.click(await screen.findByRole("checkbox", { name: "field_128" }));
  expect(screen.getByText("已选 2 个字段（跨页保留）")).toBeInTheDocument();
  expect(screen.getByText("字段 129–129 / 129")).toBeInTheDocument();
  await waitFor(() =>
    expect(workspaceClient.lineageFields).toHaveBeenCalledWith("s", "a", 128),
  );
  expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
});
it("refuses metadata pages bound to another asset or workspace revision", async () => {
  vi.mocked(workspaceClient.lineageFields).mockResolvedValue({
    ...page(0),
    assetId: "another-asset",
    stateVersion: 9,
  });
  setup();
  expect(await screen.findByRole("alert")).toHaveTextContent("版本已变化");
  expect(
    screen.queryByRole("checkbox", { name: "field_0" }),
  ).not.toBeInTheDocument();
});
