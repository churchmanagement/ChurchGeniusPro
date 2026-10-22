const { chromium } = require('playwright');
const fs = require('fs');
const S_DIR = require('path').join(__dirname, '..', '..', 'src', 'main', 'resources', 'static') + '/';
const QUESTIONS = [
  'Who are the contributors this month?', 'List the contributors this month.', 'How much is the total income this month?',
  'How much is the expense this month?', 'List the expenses this month', 'Show all transactions this month.',
  'What are the different campaigns, and how much is the amount for each?', 'List the donations for the last two months through yesterday.',
  'How much tithe did we receive over the last ten years?', 'List the current meetings.', 'List the pending prayer requests.',
  'List follow-up tasks that are not completed.', 'List the assigned volunteers.', 'List future events.', 'List event reminders.',
  'How many members are in the church now? Show males and females.', 'List the kids/children.', 'List the kids ministry children',
  'List the children in family records', 'List the generated public links.', 'Who are the volunteers?', "When is Anson's birthday?"
];
(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1280, height: 900 } }); const page = await ctx.newPage();
  await page.route('**/ai-assistant.js', r => r.fulfill({ contentType: 'application/javascript', body: fs.readFileSync(S_DIR + 'ai-assistant.js', 'utf8') }));
  page.on('pageerror', e => console.log('PAGE ERROR:', e.message));
  await page.goto('http://localhost:8080/login');
  await page.fill('#username', 'grace.pastor_799791'); await page.fill('#password', 'd4Au6JsxN4');
  await page.click('button[type=submit], #loginBtn'); await page.waitForURL(/home|dashboard/i, { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(2000);
  for (const q of QUESTIONS) {
    const d = await page.evaluate(q => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q, clientId: 'CHR-OTHER' }) }).then(r => r.json()), q);
    const rows = Array.isArray(d.rows) ? d.rows.length : '-';
    console.log('\n> ' + q + '\n  [' + (d.intent || '?') + (d.denied ? ' DENIED' : '') + '] ' + String(d.answer || '').slice(0, 170) + (rows !== '-' ? ' | rows=' + rows + (d.total != null ? ' total=' + d.total : '') + (d.truncated ? ' truncated' : '') : '') + (d.summary ? ' | summary=' + JSON.stringify(d.summary) : ''));
    if (Array.isArray(d.rows) && d.rows.length) console.log('  first row: ' + JSON.stringify(d.rows[0]));
    if (JSON.stringify(d).match(/token|SECRET|stack|SQLException|Exception/i)) console.log('  !!! suspicious content in response');
  }
  // UI render check: one table in the Home bar
  await page.fill('#aiSearchInput', 'Show all transactions this month.'); await page.click('#aiSearchBtn'); await page.waitForTimeout(2500);
  console.log('\nUI: table rows rendered =', await page.evaluate(() => document.querySelectorAll('#aiResultsWrap .aa-table tbody tr').length), '| summary tiles =', await page.evaluate(() => document.querySelectorAll('#aiResultsWrap .aa-sum').length), '| open link =', await page.evaluate(() => (document.querySelector('#aiResultsWrap .aa-open') || {}).getAttribute && document.querySelector('#aiResultsWrap .aa-open').getAttribute('data-url')));
  await page.screenshot({ path: require('path').join(__dirname, 'ai-data-table.png'), fullPage: false });
  await browser.close();
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
