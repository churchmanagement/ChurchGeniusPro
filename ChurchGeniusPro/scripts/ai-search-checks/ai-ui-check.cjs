const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1280, height: 900 } }); const page = await ctx.newPage();
  page.on('pageerror', e => console.log('PAGE ERROR:', e.message));
  await page.goto('http://localhost:8080/login');
  await page.fill('#username', 'grace.pastor_799791'); await page.fill('#password', 'd4Au6JsxN4');
  await page.click('button[type=submit], #loginBtn'); await page.waitForURL(/home|dashboard/i, { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(2500);
  // ARIA on the home page bar
  console.log('home input aria-label:', await page.getAttribute('#aiSearchInput', 'aria-label'), '| results role:', await page.getAttribute('#aiResultsWrap', 'role'), '| toolbar:', await page.locator('[role=toolbar][aria-label="Assistant modes"]').count());
  // 1. Enter blocked while busy: slow down the data endpoint, press Enter twice.
  let dataCalls = 0;
  await page.route('**/api/ai-search/data', async route => { dataCalls++; await new Promise(r => setTimeout(r, 2500)); await route.continue(); });
  await page.fill('#aiSearchInput', 'What meetings are coming up?'); await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  console.log('in flight after Enter:', await page.evaluate(() => window.CGP_ASSISTANT._inFlight()), '| aria-busy:', await page.getAttribute('#aiResultsWrap', 'aria-busy'));
  await page.fill('#aiSearchInput', 'How many members do we have?'); await page.keyboard.press('Enter'); await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  console.log('hint after 2nd Enter:', (await page.textContent('#aaHint') || '').trim(), '| data calls so far:', dataCalls);
  await page.waitForTimeout(3500);
  console.log('answer:', (await page.textContent('#aiResultsWrap .ai-answer')).slice(0, 80), '| in flight:', await page.evaluate(() => window.CGP_ASSISTANT._inFlight()), '| data calls total:', dataCalls, '(expected 1)');
  await page.unroute('**/api/ai-search/data');
  // 2. Timeout: hold the request longer than the (shortened) watchdog.
  await page.evaluate(() => window.CGP_ASSISTANT._setTimeoutMs(1500));
  await page.route('**/api/ai-search/data', async route => { await new Promise(r => setTimeout(r, 6000)); await route.abort().catch(() => {}); });
  await page.fill('#aiSearchInput', 'What meetings are coming up?'); await page.keyboard.press('Enter');
  await page.waitForTimeout(2300);
  console.log('after watchdog:', (await page.textContent('#aiResultsWrap .ai-answer')).slice(0, 90), '| in flight:', await page.evaluate(() => window.CGP_ASSISTANT._inFlight()), '| search btn disabled:', await page.isDisabled('#aiSearchBtn'));
  await page.unroute('**/api/ai-search/data');
  await page.evaluate(() => window.CGP_ASSISTANT._setTimeoutMs(30000));
  // bar usable again after the timeout
  await page.fill('#aiSearchInput', 'How many members do we have?'); await page.keyboard.press('Enter'); await page.waitForTimeout(2500);
  console.log('next answer after timeout:', (await page.textContent('#aiResultsWrap .ai-answer')).slice(0, 80));
  // 3. Scrollable panel + table ARIA
  await page.fill('#aiSearchInput', 'Who are the top contributors this year?'); await page.keyboard.press('Enter'); await page.waitForTimeout(3000);
  console.log('panel overflow:', await page.evaluate(() => getComputedStyle(document.querySelector('#aiResultsWrap .ai-results-panel')).overflowY), '| th scope col:', await page.locator('#aiResultsWrap th[scope=col]').count(), '| close aria-label:', await page.getAttribute('#aiResultsWrap .ai-results-close', 'aria-label'));
  // 4. Mobile layout on an injected-bar page
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto('http://localhost:8080/income'); await page.waitForTimeout(2500);
  const w = await page.evaluate(() => { const i = document.getElementById('aiSearchInput'), b = i && i.closest('.aa-searchbar'); return i && b ? { input: i.getBoundingClientRect().width, bar: b.getBoundingClientRect().width, bodyScroll: document.documentElement.scrollWidth, vw: innerWidth } : null; });
  console.log('mobile:', JSON.stringify(w), '(input ≈ bar width, no horizontal scroll)');
  // 5. Help modal
  await page.click('#aaHelpBtn'); await page.waitForTimeout(500);
  const help = await page.textContent('.aa-modal');
  console.log('help modal mentions:', ['Church data', 'Reports for a period', 'Create a meeting', 'within 30 seconds'].map(k => k + '=' + help.includes(k)).join(' '), '| dialog role:', await page.locator('.aa-modal[role=dialog]').count());
  await browser.close();
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
