// Phase G pass 2: Member WITH the AI feature + Directory tab; Kids WITH the AI flag (must still be refused);
// User whose general.meetings page permission was switched off (must lose meeting answers and creation).
const { chromium } = require('playwright');
const q = (page, text) => page.evaluate(q => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(async r => ({ s: r.status, j: r.status === 200 ? await r.json() : null })), text);
const kind = r => r.s === 403 ? '403' : r.s !== 200 ? 'http' + r.s : !r.j.handled ? 'unhandled' : r.j.denied ? 'denied' : 'ok';
(async () => {
  const browser = await chromium.launch({ headless: true });
  let fail = 0, n = 0;
  const check = (label, got, want, extra) => { n++; if (got !== want) { fail++; console.log('  ✗ ' + label + ': expected ' + want + ' got ' + got + (extra ? ' ' + extra : '')); } };
  async function session(u, p) {
    const ctx = await browser.newContext({ serviceWorkers: 'block' }); const page = await ctx.newPage(); await page.goto('http://localhost:8080/login');
    await page.evaluate(async ([u, p]) => { await fetch('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: p }) }); await fetch('/api/demo/trial-agreement/accept', { method: 'POST' }); }, [u, p]);
    return [ctx, page];
  }
  // Member with AI + Directory
  let [ctx, page] = await session('member_john_5', 'jeqAF6pgvQ');
  let s = await page.evaluate(() => fetch('/api/session').then(r => r.json()));
  console.log('Member+AI (role ' + s.role + ', aiAssistant=' + s.aiAssistant + ')');
  check('member: aiAssistant flag', String(s.aiAssistant), 'true');
  let r = await q(page, 'How many members do we have?'); check('member: member count', kind(r), 'ok', r.j && r.j.answer);
  r = await q(page, 'Whose birthday is this month?'); check('member: birthdays', kind(r), 'ok');
  for (const t of ['What was the income this year?', 'What meetings are coming up?', 'Who are the volunteers?', 'Pending prayer requests', 'Create a Bible Study meeting tomorrow at 7pm', 'Campaign totals', 'Kids Ministry children'])
    { r = await q(page, t); check('member: ' + t, kind(r), 'denied', r.j && r.j.intent); }
  let nav = await page.evaluate(() => fetch('/api/ai-search/nav').then(r => r.json()));
  const avail = nav.pages.filter(p => p.available).map(p => p.url);
  check('member: nav offers only portal tabs + ai-assistant', avail.every(u => u.startsWith('/memberHome?tab=') || u === '/ai-assistant') && avail.includes('/memberHome?tab=directory') && !avail.includes('/memberHome?tab=give'), true, avail.join(','));
  let g = await page.evaluate(() => fetch('/api/ai-search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'Go to the income page' }) }).then(r => r.json()));
  check('member: no /income link from navigation', (g.links || []).some(l => l.url === '/income'), false, JSON.stringify(g.links));
  await ctx.close();
  // Kids with the AI flag set on the child record
  [ctx, page] = await session('child_lily_27', 'YQdFjDKrng');
  s = await page.evaluate(() => fetch('/api/session').then(r => r.json()));
  console.log('Kids+flag (role ' + s.role + ', memberRole ' + s.memberRole + ', aiAssistant=' + s.aiAssistant + ')');
  check('kids: aiAssistant flag', String(s.aiAssistant), 'false');
  for (const t of ['How many members do we have?', 'What meetings are coming up?', 'Go to the income page']) { r = await q(page, t); check('kids: ' + t, kind(r), '403'); }
  check('kids: nav', await page.evaluate(() => fetch('/api/ai-search/nav').then(r => r.status)), 403);
  check('kids: help search', await page.evaluate(() => fetch('/api/help/search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'how do I add a family' }) }).then(r => r.status)), 403);
  check('kids: confirm endpoint', await page.evaluate(() => fetch('/api/ai-search/confirm', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: 'x' }) }).then(r => r.status)), 403);
  await ctx.close();
  // User with general.meetings switched off
  [ctx, page] = await session('sam.wilson_799791', 'c7KnpQbnYM');
  s = await page.evaluate(() => fetch('/api/session').then(r => r.json()));
  console.log('User−meetings (role ' + s.role + ', privileges ' + String(s.privileges || '').slice(0, 60) + ')');
  r = await q(page, 'What meetings are coming up?'); check('user-: meetings', kind(r), 'denied', r.j && r.j.intent);
  r = await q(page, 'Create a Bible Study meeting tomorrow at 7pm'); check('user-: create meeting', kind(r), 'denied', r.j && r.j.intent);
  r = await q(page, 'Upcoming events'); check('user-: events still ok', kind(r), 'ok');
  nav = await page.evaluate(() => fetch('/api/ai-search/nav').then(r => r.json()));
  check('user-: /meetings not offered', nav.pages.find(p => p.url === '/meetings').available, false);
  check('user-: /events offered', nav.pages.find(p => p.url === '/events').available, true);
  g = await page.evaluate(() => fetch('/api/ai-search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'Go to the meetings page' }) }).then(r => r.json()));
  check('user-: navigation describes meetings but gives no link', (g.links || []).some(l => l.url === '/meetings'), false, String(g.answer).slice(0, 120));
  await ctx.close();
  console.log('checks:', n, 'failures:', fail);
  await browser.close(); process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
