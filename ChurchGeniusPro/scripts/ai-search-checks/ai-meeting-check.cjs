const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1280, height: 900 } }); const page = await ctx.newPage();
  page.on('pageerror', e => console.log('PAGE ERROR:', e.message));
  await page.goto('http://localhost:8080/login');
  await page.fill('#username', 'grace.pastor_799791'); await page.fill('#password', 'd4Au6JsxN4');
  await page.click('button[type=submit], #loginBtn'); await page.waitForURL(/home|dashboard/i, { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(2500);
  const types = await page.evaluate(() => fetch('/api/meeting-types').then(r => r.json()));
  console.log('meeting types:', types.map(t => t.typeName).join(' | '));
  const before = await page.evaluate(() => fetch('/api/meetings').then(r => r.json()));
  console.log('meetings before:', before.length);
  const ask = q => page.evaluate(q => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(r => r.json()), q);
  const show = (q, d) => console.log('> ' + q + '\n  [' + d.intent + '] ' + String(d.answer || '').slice(0, 160) + (d.confirm ? ' | token=' + String(d.confirm.token).slice(0, 8) + '…' : '') + (d.link ? ' | link=' + d.link.url : ''));
  const typeName = types[0].typeName;
  // 1. questions are not creation
  let d = await ask('What meetings are coming up?'); show('What meetings are coming up?', d);
  // 2. recurring refused
  d = await ask('Create a ' + typeName + ' meeting every Tuesday at 7pm'); show('recurring', d);
  // 3. missing category → ask → reply
  d = await ask('Create a meeting on 2027-01-20 at 7pm'); show('missing category', d);
  d = await ask(typeName); show('reply category (→ location asked)', d);
  d = await ask('the fellowship hall'); show('reply location', d);
  const token = d.confirm && d.confirm.token;
  // 4. button confirm (API) → created; second use → expired
  d = await page.evaluate(t => fetch('/api/ai-search/confirm', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: t }) }).then(r => r.json()), token); show('confirm #1', d);
  d = await page.evaluate(t => fetch('/api/ai-search/confirm', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: t }) }).then(r => r.json()), token); show('confirm #2 (same token)', d);
  // 5. duplicate detection through spoken yes
  d = await ask('Create a ' + typeName + ' meeting on January 20 2027 at 7 pm in the fellowship hall'); show('same slot again', d);
  d = await ask('yes'); show('yes', d);
  // 6. ambiguous hour
  d = await ask('Schedule a ' + typeName + ' meeting tomorrow at 7 in the hall'); show('ambiguous hour', d);
  d = await ask('cancel'); show('cancel', d);
  const after = await page.evaluate(() => fetch('/api/meetings').then(r => r.json()));
  console.log('meetings after:', after.length, '(expected +1)');
  const created = after.find(m => m.meetingDate === '2027-01-20');
  console.log('created row:', created ? JSON.stringify({ type: created.meetingTypeName, date: created.meetingDate, start: created.startTime, occ: created.occurrence }) : 'NOT FOUND');
  // 7. UI: bar flow on Home with the Create button
  await page.goto('http://localhost:8080/home'); await page.waitForTimeout(2000);
  await page.fill('#aiSearchInput', 'Create a ' + typeName + ' meeting on 2027-02-10 at 6:30pm in the fellowship hall'); await page.click('#aiSearchBtn'); await page.waitForTimeout(2500);
  console.log('bar shows Create button:', await page.locator('.aa-confirm').count(), '| summary cells:', await page.locator('#aiResultsWrap .aa-sum').count());
  await page.locator('.aa-confirm').first().click(); await page.waitForTimeout(2500);
  console.log('bar after click:', (await page.locator('#aiResultsWrap .ai-answer').last().textContent()).slice(0, 140));
  const after2 = await page.evaluate(() => fetch('/api/meetings').then(r => r.json()));
  console.log('meetings after UI:', after2.length, '(expected +2 total)');
  // 8. chat page flow
  await page.goto('http://localhost:8080/ai-assistant'); await page.waitForTimeout(2500);
  await page.fill('#aiSearchInput', 'Create a ' + typeName + ' meeting on 2027-03-15 at 9am in the chapel'); await page.keyboard.press('Enter'); await page.waitForTimeout(2500);
  console.log('chat shows Create button:', await page.locator('.aa-confirm').count());
  await page.locator('.aa-cancel').first().click(); await page.waitForTimeout(1500);
  console.log('chat after cancel, buttons left:', await page.locator('.aa-confirm').count());
  const after3 = await page.evaluate(() => fetch('/api/meetings').then(r => r.json()));
  console.log('meetings after chat cancel:', after3.length, '(expected unchanged)');
  // cleanup: delete the two test meetings
  for (const m of after3.filter(m => ['2027-01-20', '2027-02-10'].includes(m.meetingDate))) {
    await page.evaluate(id => fetch('/api/meetings/' + id, { method: 'DELETE' }), m.id);
  }
  const final = await page.evaluate(() => fetch('/api/meetings').then(r => r.json()));
  console.log('cleanup → meetings:', final.length);
  await browser.close();
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
