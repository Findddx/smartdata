async (page) => {
  const check = (value, reason) => {
    if (!value) throw new Error(reason);
  };
  const origin = new URL(page.url()).origin;
  check(/^http:\/\/127\.0\.0\.1:\d+$/.test(origin), "owned frontend only");
  const headers = { "X-Demo-Actor": "admin" };
  const before = await (
    await page.request.get(origin + "/api/v1/workspace", { headers })
  ).json();
  check(
    before.sources.length &&
      before.sources.every((source) => source.provenance === "DEMO"),
    "synthetic review only",
  );
  const graph = await (
    await page.request.get(origin + "/api/v1/workspace/lineage", { headers })
  ).json();
  check(graph.stateVersion === before.stateVersion, "snapshot changed");
  page.setDefaultTimeout(45_000);
  const ready = async (target) => {
    await target.waitForFunction(
      () => document.querySelector(".sd-graph-node"),
      null,
      { timeout: 45_000 },
    );
    await target.waitForFunction(
      () =>
        getComputedStyle(document.querySelector(".sd-page")).opacity === "1",
    );
  };
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(origin + "/#/graph");
  await page.reload();
  await ready(page);
  await page.getByRole("button", { name: "筛选", exact: true }).click();
  await page
    .getByRole("checkbox", { name: "显示来源结构", exact: true })
    .check();
  check(
    (await page.locator('.sd-graph-node[data-node-kind="SOURCE"]').count()) ===
      graph.nodes.filter((node) => node.kind === "SOURCE").length,
    "source expansion lost registrations",
  );
  const actualEdges = new Set(graph.edges.map((edge) => edge.id));
  for (const id of await page
    .locator("[data-edge-id]")
    .evaluateAll((elements) =>
      elements.map((element) => element.dataset.edgeId),
    ))
    check(actualEdges.has(id), "a visual edge was invented");
  await page
    .getByRole("checkbox", { name: "显示来源结构", exact: true })
    .uncheck();
  await page.getByRole("button", { name: "筛选", exact: true }).click();
  const legend = await page
    .getByRole("navigation", { name: "跳到数据层", exact: true })
    .boundingBox();
  const camera = await page.locator(".sd-graph-zoom").boundingBox();
  check(
    legend && camera && legend.x + legend.width < camera.x,
    "floating controls overlap",
  );
  const mobileContext = await page
    .context()
    .browser()
    .newContext({
      viewport: { width: 390, height: 844 },
      isMobile: true,
      hasTouch: true,
      reducedMotion: "reduce",
    });
  try {
    const mobile = await mobileContext.newPage();
    mobile.setDefaultTimeout(45_000);
    await mobile.goto(origin + "/#/graph");
    await ready(mobile);
    await mobile
      .locator('.sd-graph-node[data-node-kind="TABLE"]')
      .first()
      .tap();
    await mobile
      .getByRole("complementary", { name: "图谱上下文", exact: true })
      .waitFor();
    const toolbar = await mobile.locator(".sd-graph-toolbar").boundingBox();
    check(
      toolbar &&
        toolbar.width > 340 &&
        toolbar.x >= 0 &&
        toolbar.x + toolbar.width <= 390,
      "mobile inspector collapsed toolbar width",
    );
    check(
      await mobile
        .getByRole("button", { name: "筛选", exact: true })
        .evaluate((element) => {
          const box = element.getBoundingClientRect();
          return element.contains(
            document.elementFromPoint(
              box.x + box.width / 2,
              box.y + box.height / 2,
            ),
          );
        }),
      "mobile toolbar is visually covered",
    );
    await mobile.getByRole("button", { name: "关闭上下文", exact: true }).tap();
    const scaleBefore = await mobile
      .locator(".sd-graph-zoom > span")
      .innerText();
    await mobile.getByRole("button", { name: "放大图谱", exact: true }).tap();
    await mobile.waitForFunction(
      (previous) =>
        document.querySelector(".sd-graph-zoom > span")?.textContent !==
        previous,
      scaleBefore,
      { timeout: 3000 },
    );
    check(
      (await mobile.locator(".sd-graph-zoom > span").innerText()) !==
        scaleBefore,
      "touch zoom button failed",
    );
    const plane = mobile.locator(".sd-graph-plane");
    const old = await plane.evaluate((element) => element.style.transform);
    const cdp = await mobileContext.newCDPSession(mobile);
    await cdp.send("Input.dispatchTouchEvent", {
      type: "touchStart",
      touchPoints: [{ x: 330, y: 550 }],
    });
    // A paced trajectory models a finger drag, rather than an instantaneous jump.
    for (let step = 1; step <= 4; step++) {
      await mobile.waitForTimeout(30);
      await cdp.send("Input.dispatchTouchEvent", {
        type: "touchMove",
        touchPoints: [{ x: 330 - step * 15, y: 550 - step * 12.5 }],
      });
    }
    await cdp.send("Input.dispatchTouchEvent", {
      type: "touchEnd",
      touchPoints: [],
    });
    await mobile.waitForFunction(
      (previous) =>
        document
          .querySelector(".sd-graph-plane")
          ?.getAttribute("style")
          ?.includes(previous) === false,
      old,
      { timeout: 3000 },
    );
    check(
      (await plane.evaluate((element) => element.style.transform)) !== old,
      "touch drag did not move canvas",
    );
    await cdp.detach();
    const afterDragScale = await mobile
      .locator(".sd-graph-zoom > span")
      .innerText();
    await mobile.getByRole("button", { name: "放大图谱", exact: true }).tap();
    await mobile.waitForFunction(
      (previous) =>
        document.querySelector(".sd-graph-zoom > span")?.textContent !==
        previous,
      afterDragScale,
      { timeout: 3000 },
    );
    check(
      await mobile.evaluate(() => document.body.scrollWidth <= innerWidth),
      "mobile document overflow",
    );
  } finally {
    await mobileContext.close();
  }
  const after = await (
    await page.request.get(origin + "/api/v1/workspace", { headers })
  ).json();
  check(
    after.stateVersion === before.stateVersion,
    "read-only review changed business state",
  );
  return {
    result: "PASS_ISLAND_SOURCE_AND_TOUCH",
    stateVersion: after.stateVersion,
    realEdgesOnly: true,
    mobileToolbarReachable: true,
    actualTouchDrag: true,
    touchZoom: true,
    touchZoomAfterDrag: true,
    noWrites: true,
  };
}
