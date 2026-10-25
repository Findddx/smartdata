async (page) => {
  const origin = new URL(page.url()).origin;
  if (new URL(origin).hostname !== '127.0.0.1') throw new Error('Only the owned loopback preview is allowed');
  const browser = page.context().browser();
  const browserName = browser.browserType().name();
  const opticalMode = browserName === 'chromium' ? 'svg-backdrop' : 'art-refraction';
  const beforePath = `output/playwright/material-${browserName}-optics-strength-1.png`;
  const afterPath = `output/playwright/material-${browserName}-optics-strength-16.png`;
  await page.setViewportSize({ width: 1000, height: 700 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.evaluate(async (ruler) => {
    const host = document.createElement('div');
    host.id = 'smartdata-material-proof';
    Object.assign(host.style, { position: 'fixed', left: '40px', top: '50px', zIndex: '2147483000', background: '#e9eeed', width: '640px', height: '360px' });
    document.body.append(host);
    const { mountMaterialInspection } = await import('/src/brand/material-inspection.tsx');
    window.smartdataMaterialInspection = mountMaterialInspection(host, { ruler });
  }, opticalMode === 'art-refraction');
  try {
  const scene = page.locator('[data-material-inspection="scene"]');
  const ready = () => page.waitForFunction(expectedMode => {
    const material = document.querySelector('#smartdata-material-proof [data-liquid-mode]');
    const lens = material?.querySelector('.sd-liquid-material__lens');
    const filter = expectedMode === 'svg-backdrop' ? lens?.style.backdropFilter : lens?.style.filter;
    return material?.getAttribute('data-liquid-mode') === expectedMode && filter?.includes('url(');
  }, opticalMode, { timeout: 20000 });
  await ready();
  await scene.locator('img').first().evaluate(image => image.decode());
  const foreground = page.locator('[data-material-inspection="foreground"]');
  const beforeBox = await foreground.boundingBox();
  const oldFilter = await scene.locator('.sd-liquid-material__lens').evaluate(el => el.style.backdropFilter || el.style.filter);
  await scene.screenshot({ path: beforePath, animations: 'disabled', scale: 'css' });
  await page.evaluate(() => window.smartdataMaterialInspection.setStrength(16));
  await ready();
  await page.waitForFunction(old => { const lens = document.querySelector('#smartdata-material-proof .sd-liquid-material__lens'); return (lens?.style.backdropFilter || lens?.style.filter) !== old; }, oldFilter);
  await scene.screenshot({ path: afterPath, animations: 'disabled', scale: 'css' });
  const afterBox = await foreground.boundingBox();
  if (JSON.stringify(beforeBox) !== JSON.stringify(afterBox)) throw new Error('Material moved foreground content');
  const filters = await foreground.evaluate(el => { const values = []; for (let n = el; n && n.id !== 'smartdata-material-proof'; n = n.parentElement) values.push(getComputedStyle(n).filter); return values; });
  if (filters.some(value => value !== 'none')) throw new Error('Foreground was passed through an optical filter');
  const pixels = await page.evaluate(async ({ beforeUrl, afterUrl }) => {
    async function load(path) { const image = new Image(); image.src = path; await image.decode(); const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height; const ctx = canvas.getContext('2d'); ctx.drawImage(image, 0, 0); return ctx.getImageData(0, 0, image.width, image.height); }
    const a = await load(beforeUrl);
    const b = await load(afterUrl);
    if (a.width !== b.width || a.height !== b.height) throw new Error('Proof image dimensions differ');
    let rim = 0, rimChanged = 0, center = 0, centerChanged = 0;
    for (let y = 102; y < 248; y++) for (let x = 128; x < 512; x++) {
      const inset = Math.min(x - 126, 514 - x, y - 100, 250 - y);
      const offset = (y * a.width + x) * 4;
      const delta = Math.abs(a.data[offset] - b.data[offset]) + Math.abs(a.data[offset + 1] - b.data[offset + 1]) + Math.abs(a.data[offset + 2] - b.data[offset + 2]);
      if (inset > 4 && inset < 31) { rim++; if (delta > 14) rimChanged++; }
      if (inset > 55) { center++; if (delta > 14) centerChanged++; }
    }
    return { width: a.width, height: a.height, rim, rimChanged, rimFraction: rimChanged / rim, center, centerChanged, centerFraction: centerChanged / center };
  }, { beforeUrl: '/' + beforePath, afterUrl: '/' + afterPath });
  if (!(pixels.rimFraction > 0.04 && pixels.rimFraction > pixels.centerFraction * 1.2)) throw new Error('No convincing shape-local refraction in actual pixels: ' + JSON.stringify(pixels));
  await page.evaluate(() => window.smartdataMaterialInspection.setStrength(0));
  await page.waitForFunction(() => document.querySelector('#smartdata-material-proof [data-liquid-mode]')?.getAttribute('data-liquid-mode') === 'solid');
  return { result: 'PASS_BROWSER_REFRACTION_PIXELS', browser: browserName, browserVersion: browser.version(), mode: opticalMode, strengths: [1, 16], zeroStrengthSolid: true, foregroundUnmovedAndUnfiltered: true, pixels, limitation: 'Small fixture on Windows; not Safari/iPhone hardware or application frame-rate certification.' };
  } finally {
    await page.evaluate(() => { window.smartdataMaterialInspection?.destroy(); delete window.smartdataMaterialInspection; document.getElementById('smartdata-material-proof')?.remove(); });
  }
}
