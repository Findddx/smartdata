async (page) => {
  const check = (value, reason) => {
    if (!value) throw new Error(reason);
  };
  const origin = new URL(page.url()).origin;
  check(/^http:\/\/127\.0\.0\.1:\d+$/.test(origin), "local acceptance only");
  const headers = { "X-Demo-Actor": "admin" };
  const response = await page.request.get(origin + "/api/v1/workspace", {
    headers,
  });
  check(response.ok(), "workspace unavailable");
  const before = await response.json();
  check(
    before.sources.length > 0 &&
      before.sources.every((source) => source.provenance === "DEMO"),
    "requires explicit synthetic rehearsal sources",
  );
  const graphResponse = await page.request.get(
    origin + "/api/v1/workspace/lineage",
    { headers },
  );
  check(
    graphResponse.ok(),
    "real lineage API must be ready; this script never intercepts responses",
  );
  const graph = await graphResponse.json();
  check(
    graph.stateVersion === before.stateVersion,
    "workspace changed during acceptance setup",
  );
  const table = graph.nodes.find((node) => node.kind === "TABLE");
  check(table, "requires at least one scoped, successfully observed table");
  const errors = [];
  let readRetries = 0;
  page.setDefaultTimeout(45_000);
  page.on("pageerror", (error) => errors.push(error.message));
  await page.evaluate(() => {
    localStorage.setItem("smartdata.localActor", "admin");
    localStorage.setItem("smartdata.navCollapsed", "false");
  });
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto(origin + "/#/graph");
  await page.reload();
  await page.waitForFunction(() => document.querySelector(".sd-graph-workbench") || document.querySelector(".sd-disconnected"), { timeout: 45_000 });
  if (await page.getByRole("heading", { name: "无法连接工作区服务", exact: true }).count()) {
    await page.screenshot({ path: "output/playwright/lineage-workspace-read-failure.png" });
    await page.getByRole("button", { name: "重新连接", exact: true }).click(); readRetries++;
  }
  await page.waitForFunction(() => document.querySelector(".sd-graph-node") || [...document.querySelectorAll("h2")].some((element) => element.textContent === "图谱暂时不可用"), { timeout: 45_000 });
  if (await page.getByRole("heading", { name: "图谱暂时不可用", exact: true }).count()) {
    await page.screenshot({ path: "output/playwright/lineage-read-failure.png" });
    await page.getByRole("button", { name: "重新读取图谱", exact: true }).click(); readRetries++;
    await page.locator(".sd-graph-node").first().waitFor();
  }
  await page.getByRole("heading", { name: "数据图谱", exact: true }).waitFor();
  const selectedTable = page
    .locator(".sd-graph-node")
    .filter({ hasText: table.label })
    .and(page.locator('[data-node-kind="TABLE"]'))
    .first();
  await selectedTable.click();
  await page.getByText("本工作区元数据可见", { exact: true }).waitFor();
  check(
    await page.getByText("敏感级别未确认", { exact: true }).isVisible(),
    "sensitivity was guessed",
  );
  const coordinates = await page
    .locator(".sd-graph-node")
    .evaluateAll((nodes) =>
      nodes.map((node) => ({
        id: node.dataset.nodeId,
        layer: node.dataset.layer,
        kind: node.dataset.nodeKind,
        left: Number.parseFloat(node.style.left),
      })),
    );
  for (const item of coordinates)
    check(
      graph.nodes.some((node) => node.id === item.id && node.layer === item.layer && node.kind === item.kind) && Number.isFinite(item.left),
      "diagram object lost its true server type/layer",
    );
  if (graph.coverage.partial)
    check(
      await page.getByText("当前仅展示部分依赖", { exact: true }).isVisible(),
      "partial graph passed as complete",
    );
  const pageResponse = page.waitForResponse(
    (result) =>
      result.request().method() === "GET" &&
      result.url().includes("/lineage/sources/") &&
      result.url().includes("/fields?"),
  );
  await page
    .getByRole("button", { name: "在图上展开本表字段", exact: true })
    .click();
  const fields = await (await pageResponse).json();
  check(
    fields.stateVersion === before.stateVersion &&
      fields.assetId === table.ref.assetId &&
      fields.sourceId === table.ref.sourceId,
    "field page not bound to this snapshot/table",
  );
  await page.getByRole("button", { name: "返回主图", exact: true }).click();
  await page.getByRole("button", { name: "关闭上下文", exact: true }).click();
  const canvas = page.locator(".sd-graph-canvas");
  await canvas.focus();
  const oldTransform = await page
    .locator(".sd-graph-plane")
    .evaluate((element) => element.style.transform);
  await page.keyboard.press("ArrowRight");
  check(
    (await page
      .locator(".sd-graph-plane")
      .evaluate((element) => element.style.transform)) !== oldTransform,
    "keyboard camera movement failed",
  );
  await page.getByRole("button", { name: "适应画布", exact: true }).click();
  await page.getByRole("button", { name: "显示节点列表", exact: true }).click();
  await page
    .getByRole("textbox", { name: "搜索图谱节点", exact: true })
    .fill(table.label);
  check(
    (await page
      .getByLabel("节点列表", { exact: true })
      .getByText(table.label, { exact: true })
      .count()) > 0,
    "list lost the shared object",
  );
  await page.getByRole("button", { name: "清空图谱搜索", exact: true }).click();
  await page.getByRole("button", { name: "显示图谱", exact: true }).click();
  await page.getByRole("button", { name: "收起导航", exact: true }).click();
  await page.waitForFunction(() => Math.round(document.querySelector(".sd-sidebar").getBoundingClientRect().width) === 52);
  check(
    await page
      .getByRole("button", { name: "空间设置", exact: true })
      .isVisible(),
    "collapsed utility control has no accessible name",
  );
  await page.getByRole("button", { name: "展开导航", exact: true }).click();
  const themes = [
    ["石墨 · 钛银", "titanium"],
    ["水碧 · 月白", "muse"],
    ["矿白 · 普鲁士蓝", "mineral"],
  ];
  for (const [label, key] of themes) {
    await page.getByRole("button", { name: "空间设置", exact: true }).click();
    await page.getByRole("button", { name: new RegExp(label) }).click();
    await page.getByRole("button", { name: "数据图谱", exact: true }).click();
    await page.locator(".sd-graph-canvas").waitFor();
    await page.screenshot({
      path: `output/playwright/lineage-${key}-desktop.png`,
    });
  }
  await page
    .getByRole("combobox", { name: "本地演示身份", exact: true })
    .selectOption("auditor");
  check(
    await page
      .getByRole("button", { name: "管理映射", exact: true })
      .isDisabled(),
    "auditor offered mapping writes",
  );
  await page
    .getByRole("combobox", { name: "本地演示身份", exact: true })
    .selectOption("admin");
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("navigation", { name: "跳到数据层", exact: true })
    .getByRole("button", { name: /L2/ })
    .click();
  check(
    await page.evaluate(() => document.body.scrollWidth <= innerWidth),
    "mobile document overflow",
  );
  check(
    await page
      .locator(".sd-graph-plane")
      .evaluate(
        (element) => getComputedStyle(element).transitionDuration === "0s",
      ),
    "reduced motion ignored",
  );
  await page.screenshot({ path: "output/playwright/lineage-mobile.png" });
  await page.keyboard.press("Control+k");
  check(
    await page
      .getByRole("combobox", { name: "搜索", exact: true })
      .evaluate((element) => element === document.activeElement),
    "command palette did not focus input",
  );
  await page
    .getByRole("combobox", { name: "搜索", exact: true })
    .fill("数据图谱");
  await page.keyboard.press("Enter");
  check(
    (await page.getByRole("dialog").count()) === 0,
    "command palette did not close",
  );
  const after = await (
    await page.request.get(origin + "/api/v1/workspace", { headers })
  ).json();
  check(
    after.stateVersion === before.stateVersion,
    "read-only visual acceptance changed business data",
  );
  check(errors.length === 0, "browser errors: " + errors.join("; "));
  return {
    result: "PASS_LINEAGE_READONLY_BROWSER",
    stateVersion: after.stateVersion,
    themes: 3,
    mobile: "390x844",
    fieldPage: fields.nodes.length,
    fieldsTotal: fields.totalFields,
    keyboardCamera: true,
    metadataPermission: true,
    partial: graph.coverage.partial,
    noWrites: true,
    readRetries,
  };
}
