const { chromium } = require('playwright');
const fs = require('fs');
const S_DIR = require('path').join(__dirname, '..', '..', 'src', 'main', 'resources', 'static') + '/';
(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1280, height: 900 } }); const page = await ctx.newPage();
  page.on('pageerror', e => console.log('PAGE ERROR:', e.message));
  await page.goto('http://localhost:8080/login');
  await page.fill('#username', 'grace.pastor_799791'); await page.fill('#password', 'd4Au6JsxN4');
  await page.click('button[type=submit], #loginBtn'); await page.waitForURL(/home|dashboard/i, { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(2500);
  const nav = await page.evaluate(() => fetch('/api/ai-search/nav').then(r => r.json()));
  const avail = {}; nav.pages.forEach(p => avail[p.url] = p.available);
  console.log('nav pages:', nav.pages.length, '| SuperAdmin can open:', ['/ntagLandingAdmin', '/private-access-settings', '/fund', '/purpose', '/transactiontype', '/notifyEmail#emailSettings', '/dailyVerseSetup', '/financial-report', '/viewusers', '/memberHome?tab=directory'].map(u => u + '=' + avail[u]).join(' '));
  for (const q of ['Go to the NTag page.', 'Go to private page access', 'I need to add a new fund.', 'I need to add a new expense.', 'I need to add a new method.', 'add a new payment method', 'How do I set the email display name?', 'How do I set the daily Bible verse?', 'How does the AI Search screen work?', 'open the financial report']) {
    const d = await page.evaluate(q => fetch('/api/ai-search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(r => r.json()), q);
    console.log('> ' + q + '\n  [' + d.intent + '] ' + String(d.answer || '').slice(0, 120) + ' | links=' + (d.links || []).map(l => l.url).join(', '));
  }
  // Type-mode direct navigation in the bar: "Go to the NTag page" should navigate to /ntagLandingAdmin
  await page.fill('#aiSearchInput', 'Go to the NTag page'); await page.click('#aiSearchBtn'); await page.waitForTimeout(1500);
  console.log('bar "Go to the NTag page" →', page.url());
  await page.goto('http://localhost:8080/home'); await page.waitForTimeout(2000);
  await page.fill('#aiSearchInput', 'Open the financial report'); await page.click('#aiSearchBtn'); await page.waitForTimeout(1500);
  console.log('bar "Open the financial report" →', page.url());
  await page.goto('http://localhost:8080/notifyEmail#emailSettings'); await page.waitForTimeout(2000);
  console.log('email settings panel visible:', await page.evaluate(() => { const p = document.getElementById('emailSettingsPanel'); return !!p && p.style.display !== 'none'; }));
  await browser.close();
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
