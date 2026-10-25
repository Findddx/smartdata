async (page) => {
  const check = (value, label) => { if (!value) throw new Error(label); };
  const origin = new URL(page.url()).origin;
  check(/^http:\/\/127\.0\.0\.1:\d+$/.test(origin), 'local acceptance only');
  const response = await page.request.get(origin + '/api/v1/workspace', {headers:{'X-Demo-Actor':'admin'}});
  const state = await response.json();
  check(state.sources.length === 3 && state.sources.every(source => source.provenance === 'DEMO'), 'synthetic sources required');
  check(state.businessBrief?.enterpriseName === '示例商贸 · 业务协作演练' && state.businessBrief?.version === 2, 'requires the owned business-brief acceptance fixture');
  const settle = async () => {
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.sd-page')).opacity === '1');
    await page.evaluate(async () => {await document.fonts.ready; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));});
  };
  await page.setViewportSize({width:1440,height:1000});
  const themes = [['石墨 · 钛银','titanium'],['水碧 · 月白','muse'],['矿白 · 普鲁士蓝','linen']];
  for (const [label,id] of themes) {
    await page.goto(origin + '/#/settings');
    await page.getByRole('button',{name:new RegExp(label)}).click();
    await page.getByRole('button',{name:'业务约定',exact:true}).click();
    await page.getByRole('textbox',{name:'企业或业务团队名称',exact:true}).waitFor();
    await settle();
    await page.screenshot({path:`output/playwright/business-brief-${id}-desktop.png`});
  }
  await page.getByRole('combobox',{name:'本地演示身份',exact:true}).selectOption('auditor');
  check(await page.getByRole('textbox',{name:'企业或业务团队名称',exact:true}).isDisabled(), 'audit identity could edit the business form');
  check(await page.getByRole('button',{name:'版本记录',exact:true}).isEnabled(), 'audit identity could not inspect history');
  await page.getByRole('combobox',{name:'本地演示身份',exact:true}).selectOption('admin');
  await page.locator('.sd-toast').waitFor({state:'hidden'});
  await page.setViewportSize({width:390,height:844});
  await settle();
  check(await page.evaluate(() => document.body.scrollWidth <= innerWidth), 'mobile horizontal overflow');
  await page.screenshot({path:'output/playwright/business-brief-linen-mobile.png'});
  await page.getByRole('heading',{name:'同一个数字，同一种理解',exact:true}).scrollIntoViewIfNeeded();
  await settle();
  check(await page.getByText('口径已确认',{exact:true}).isVisible(), 'metric is unreachable on a mobile screen');
  await page.screenshot({path:'output/playwright/business-brief-metric-mobile.png'});
  await page.getByRole('button',{name:'编辑',exact:true}).click();
  await page.getByRole('dialog').waitFor();
  check(await page.getByRole('dialog').getByRole('textbox',{name:'业务定义',exact:true}).inputValue() === state.businessBrief.metrics[0].businessDefinition, 'metric editor lost its saved definition');
  await page.keyboard.press('Shift+Tab');
  check(await page.evaluate(() => !!document.activeElement.closest('dialog')), 'focus escaped metric editor');
  await page.keyboard.press('Escape');
  const afterResponse = await page.request.get(origin + '/api/v1/workspace', {headers:{'X-Demo-Actor':'admin'}});
  check((await afterResponse.json()).stateVersion === state.stateVersion, 'read-only visual acceptance mutated business data');
  return {result:'PASS_BUSINESS_VISUAL_REVIEW',themes:3,mobile:'390x844',reachableMetric:true,auditorReadOnly:true,metricDialogFocus:true,stateUnchanged:true};
}
