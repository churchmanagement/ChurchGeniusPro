const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ serviceWorkers: 'block' }); const page = await ctx.newPage(); await page.goto('http://localhost:8080/login');
  await page.evaluate(async () => { await fetch('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'grace.pastor_799791', password: 'd4Au6JsxN4' }) }); await fetch('/api/demo/trial-agreement/accept', { method: 'POST' }); });
  const qs = ['What was the income this year?', 'Who are the top contributors this year?', 'List the expenses this year', 'Transactions last 90 days', 'Income report for last month', 'Expense report for last quarter', 'Financial report for 2026', 'How many members do we have?', 'Whose birthday is this month?', 'Who are the volunteers?', 'Upcoming volunteer assignments', 'What meetings are coming up?', 'Pending prayer requests', 'Open follow-ups', 'Upcoming events', 'Active reminders', 'Campaign totals', 'Kids Ministry children', 'Children in family records', 'Public links', 'Create a Bible Study meeting on 2027-06-06 at 7pm', 'cancel'];
  const navq = ['Go to the NTag page', 'How do I set the email display name?', 'I need to add a new expense', 'Where is the financial report?'];
  let worst = 0;
  for (const q of qs) { const times = []; for (let i = 0; i < 3; i++) { const t = await page.evaluate(q => { const t0 = performance.now(); return fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(r => r.json()).then(j => ({ ms: Math.round(performance.now() - t0), intent: j.intent, rows: (j.rows || []).length })); }, q); times.push(t); } const med = times.map(t => t.ms).sort((a, b) => a - b)[1]; worst = Math.max(worst, med); console.log(String(med).padStart(5) + ' ms  [' + times[0].intent + ', rows ' + times[0].rows + ']  ' + q); }
  for (const q of navq) { const t = await page.evaluate(q => { const t0 = performance.now(); return fetch('/api/ai-search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(r => r.json()).then(j => ({ ms: Math.round(performance.now() - t0), intent: j.intent })); }, q); worst = Math.max(worst, t.ms); console.log(String(t.ms).padStart(5) + ' ms  [' + t.intent + ']  ' + q); }
  const nav = await page.evaluate(() => { const t0 = performance.now(); return fetch('/api/ai-search/nav').then(r => r.json()).then(() => Math.round(performance.now() - t0)); });
  console.log(String(nav).padStart(5) + ' ms  GET /api/ai-search/nav'); console.log('worst median:', Math.max(worst, nav), 'ms');
  const sz = await page.evaluate(() => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'Who are the top contributors this year?' }) }).then(r => r.text()).then(t => t.length));
  console.log('largest list response bytes:', sz);
  await browser.close();
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
