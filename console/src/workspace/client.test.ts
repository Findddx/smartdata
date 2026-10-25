import { afterEach, expect, it, vi } from "vitest";
import { workspaceClient } from "./client";

afterEach(() => vi.unstubAllGlobals());
it("explains a real read timeout in Chinese without silently retrying", async () => {
  const fetch = vi
    .fn()
    .mockRejectedValue(new DOMException("signal timed out", "TimeoutError"));
  vi.stubGlobal("fetch", fetch);
  await expect(workspaceClient.lineage()).rejects.toMatchObject({
    status: 0,
    message: "读取工作区超时。请检查服务与网络连接，再点击重试。",
  });
  expect(fetch).toHaveBeenCalledTimes(1);
});
it("keeps a timed-out command outcome unknown and asks for reconciliation before resubmission", async () => {
  const fetch = vi
    .fn()
    .mockRejectedValue(new DOMException("signal timed out", "TimeoutError"));
  vi.stubGlobal("fetch", fetch);
  await expect(
    workspaceClient.saveLineageMappings({
      expectedStateVersion: 1,
      expectedMappingVersion: 0,
      entities: [],
      metrics: [],
      decisions: [],
    }),
  ).rejects.toMatchObject({
    status: 0,
    message: "请求等待超时，结果尚不能确认。请先刷新记录核对，再决定是否重试。",
  });
  expect(fetch).toHaveBeenCalledTimes(1);
});
