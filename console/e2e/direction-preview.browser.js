async (page) => {
  const origin = new URL(page.url()).origin;
  if (new URL(origin).hostname !== '127.0.0.1') throw new Error('Loopback preview only');
  const headers = { 'X-Demo-Actor': 'admin' };
  const before = await (await page.request.get(origin + '/api/v1/workspace', { headers })).json();
  if (!before.sources.length || before.sources.some(source => source.provenance !== 'DEMO')) throw new Error('Requires explicit synthetic demo sources; no user data is changed');
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(origin + '/#/graph');
  await page.reload();
  await page.locator('.sd-edge-hit').first().waitFor();
  const edge = page.locator('[data-edge-id]').first();
  const path = await edge.locator('.sd-edge-hit').getAttribute('d');
  const id = await edge.getAttribute('data-edge-id');
  await edge.locator('.sd-edge-hit').click();
  await page.getByText('方向示意', { exact: true }).waitFor();
  await page.locator('[data-direction-preview]').waitFor({ state: 'attached' });
  const trace = page.locator('[data-direction-preview] animateMotion');
  if (await trace.getAttribute('path') !== path || await trace.getAttribute('repeatCount') !== '2') throw new Error('Direction preview does not follow the selected real edge');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.waitForFunction(() => !document.querySelector('[data-direction-preview]'));
  const after = await (await page.request.get(origin + '/api/v1/workspace', { headers })).json();
  if (before.stateVersion !== after.stateVersion) throw new Error('View interaction changed business state');
  return { result: 'PASS_SELECTED_REAL_EDGE_DIRECTION', stateVersion: after.stateVersion, realEdgeId: id, finiteCycles: 2, reducedMotionUnmounts: true, noBusinessWrites: true };
}
