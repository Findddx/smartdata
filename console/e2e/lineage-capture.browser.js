async (page) => {
  const origin = new URL(page.url()).origin;
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin))
    throw new Error("local frontend only");
  page.setDefaultTimeout(45_000);
  const before = await (
    await page.request.get(origin + "/api/v1/workspace", {
      headers: { "X-Demo-Actor": "admin" },
    })
  ).json();
  if (
    !before.sources.length ||
    !before.sources.every((source) => source.provenance === "DEMO")
  )
    throw new Error("requires explicit synthetic rehearsal");
  const settle = async (target) => {
    await target.waitForFunction(
      () =>
        document.querySelector(".sd-graph-node") ||
        [...document.querySelectorAll("h2")].some(
          (node) => node.textContent === "图谱暂时不可用",
        ),
      null,
      { timeout: 45_000 },
    );
    if (
      await target
        .getByRole("heading", { name: "图谱暂时不可用", exact: true })
        .count()
    ) {
      await target.screenshot({
        path: "output/playwright/lineage-capture-read-failure.png",
        animations: "disabled",
      });
      await target
        .getByRole("button", { name: "重新读取图谱", exact: true })
        .click();
      await target.locator(".sd-graph-node").first().waitFor();
    }
    await target.waitForFunction(
      () =>
        getComputedStyle(document.querySelector(".sd-page")).opacity === "1",
    );
    await target.locator(".sd-graph-plane").evaluate(async (element) => {
      await Promise.all(
        element
          .getAnimations()
          .map((animation) => animation.finished.catch(() => {})),
      );
    });
    await target.locator(".sd-toast").waitFor({ state: "hidden" });
    await target.evaluate(() => document.fonts.ready);
  };
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.goto(origin + "/#/graph");
  await page.reload();
  for (const [label, key] of [
    ["矿白 · 普鲁士蓝", "mineral"],
    ["石墨 · 钛银", "titanium"],
    ["朱砂 · 素纸", "cinnabar"],
  ]) {
    await page.getByRole("button", { name: "空间设置", exact: true }).click();
    await page.getByRole("button", { name: new RegExp(label) }).click();
    await page.getByRole("button", { name: "数据图谱", exact: true }).click();
    await settle(page);
    await page.screenshot({
      path: `output/playwright/islands-${key}-overview-desktop.png`,
      animations: "disabled",
    });
    const entity = page
      .locator('.sd-graph-node[data-node-kind="BUSINESS_ENTITY"]')
      .first();
    await entity.click();
    await page
      .getByRole("complementary", { name: "图谱上下文", exact: true })
      .waitFor();
    await settle(page);
    await page.screenshot({
      path: `output/playwright/islands-${key}-inspector-desktop.png`,
      animations: "disabled",
    });
  }
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
    await settle(mobile);
    await mobile
      .getByRole("navigation", { name: "跳到数据层", exact: true })
      .getByRole("button", { name: /L1/ })
      .tap();
    await settle(mobile);
    await mobile.screenshot({
      path: "output/playwright/islands-mobile-stable.png",
      animations: "disabled",
    });
    await mobile
      .locator('.sd-graph-node[data-node-kind="BUSINESS_ENTITY"]')
      .first()
      .tap();
    await mobile
      .getByRole("complementary", { name: "图谱上下文", exact: true })
      .waitFor();
    await settle(mobile);
    await mobile.screenshot({
      path: "output/playwright/islands-mobile-inspector.png",
      animations: "disabled",
    });
    if (await mobile.evaluate(() => document.body.scrollWidth > innerWidth))
      throw new Error("mobile overflow");
  } finally {
    await mobileContext.close();
  }
  await page.getByRole("button", { name: "关闭上下文", exact: true }).click();
  await page.getByRole("button", { name: "空间设置", exact: true }).click();
  await page.getByRole("button", { name: /矿白 · 普鲁士蓝/ }).click();
  await page.getByRole("button", { name: "数据图谱", exact: true }).click();
  await settle(page);
  const after = await (
    await page.request.get(origin + "/api/v1/workspace", {
      headers: { "X-Demo-Actor": "admin" },
    })
  ).json();
  if (after.stateVersion !== before.stateVersion)
    throw new Error("capture changed business state");
  return {
    result: "PASS_STABLE_ISLAND_LINEAGE_CAPTURE",
    stateVersion: after.stateVersion,
    desktop: "1920x1080",
    mobile: "390x844",
    realTouch: true,
    noWrites: true,
  };
}
