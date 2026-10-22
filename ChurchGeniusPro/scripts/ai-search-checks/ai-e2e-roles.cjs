// Phase G end-to-end: the AI endpoints for every login type on the demo tenant.
// Expectations encode the approved rules (page rules, Member decisions, Kids = nothing).
const { chromium } = require('playwright');
const LOGINS = {
  SuperAdmin: ['grace.pastor_799791', 'd4Au6JsxN4'],
  Admin:      ['daniel.manager_799791', 'vJGZShr6Gc'],
  Accountant: ['ruth.treasurer_799791', 'TynrfqVSdW'],
  User:       ['sam.wilson_799791', 'c7KnpQbnYM'],
  Church:     ['church_799791', 'jVVtmSAYJd'],
  Member:     ['member_john_5', 'jeqAF6pgvQ'],
  Kids:       ['child_lily_27', 'YQdFjDKrng'],
};
const DATA_Q = {
  income:       'What was the income this year?',
  contributors: 'Who are the top contributors this year?',
  expensesList: 'List the expenses this year',
  expensesTot:  'Total expenses this year',
  report:       'Income report for last month',
  members:      'How many members do we have?',
  birthdays:    'Whose birthday is this month?',
  volunteers:   'Who are the volunteers?',
  assigned:     'Upcoming volunteer assignments',
  meetings:     'What meetings are coming up?',
  prayer:       'Pending prayer requests',
  followups:    'Open follow-ups',
  events:       'Upcoming events',
  reminders:    'Active reminders',
  campaigns:    'Campaign totals',
  kids:         'Kids Ministry children',
  famKids:      'Children in family records',
  links:        'Public links',
  createMtg:    'Create a Bible Study meeting on 2027-05-05 at 7pm',
};
// expected: 'ok' (handled, not denied), 'denied', or '403' (no AI at all)
const EXPECT = {
  SuperAdmin: { all: 'ok' },
  Admin:      { all: 'ok' },
  Accountant: { all: 'ok', volunteers: 'denied', assigned: 'denied', prayer: 'denied', followups: 'denied', reminders: 'denied', kids: 'denied', links: 'denied' },
  User:       { all: 'denied', volunteers: 'ok', assigned: 'ok', meetings: 'ok', prayer: 'ok', followups: 'ok', events: 'ok', reminders: 'ok', kids: 'ok', links: 'ok', createMtg: 'ok' },
  Church:     { all: 'denied' },
  Member:     { all: '403' },     // no explicit more.aiassistant=true on the demo member → no AI at all
  Kids:       { all: '403' },
};
(async () => {
  const browser = await chromium.launch({ headless: true });
  let failures = 0, checks = 0;
  for (const [who, [u, p]] of Object.entries(LOGINS)) {
    const ctx = await browser.newContext({ serviceWorkers: 'block' }); const page = await ctx.newPage();
    const login = await page.evaluate(async ([u, p]) => { const r = await fetch('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: p }) }); return r.status; }, [u, p]).catch(() => 'n/a');
    if (login === 'n/a') { await page.goto('http://localhost:8080/login'); }
    const st = await page.evaluate(async ([u, p]) => { const r = await fetch('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: p }) }); return r.status; }, [u, p]);
    // Demo tenant: every login must accept the trial agreement once (a filter returns 403 until then).
    await page.evaluate(() => fetch('/api/demo/trial-agreement/accept', { method: 'POST' }).catch(() => {}));
    const sess = await page.evaluate(() => fetch('/api/session').then(r => r.json()).catch(() => ({})));
    const line = [who + ' (login ' + st + ', role ' + (sess.role || '?') + ', aiAssistant=' + sess.aiAssistant + ')'];
    const nav = await page.evaluate(() => fetch('/api/ai-search/nav').then(r => r.status));
    const exp = EXPECT[who];
    for (const [k, q] of Object.entries(DATA_Q)) {
      const want = exp[k] || exp.all;
      const r = await page.evaluate(q => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(async r => ({ s: r.status, j: r.status === 200 ? await r.json() : null })), q);
      let got = r.s === 403 ? '403' : r.s !== 200 ? 'http' + r.s : !r.j.handled ? 'unhandled' : r.j.denied ? 'denied' : 'ok';
      // "ok" tolerates clarify/narrow (still not denied); the create path reports confirm
      checks++;
      const pass = got === want;
      if (!pass) { failures++; line.push('  ✗ ' + k + ': expected ' + want + ' got ' + got + ' [' + (r.j ? r.j.intent : '') + '] ' + String(r.j && r.j.answer || '').slice(0, 90)); }
      if (k === 'createMtg' && r.j && r.j.intent === 'meeting.confirm') { await page.evaluate(() => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'cancel' }) })); }
    }
    // navigation / how-to engine: Kids/Member-without-AI → 403; others → answers, links only to openable pages
    const navq = await page.evaluate(() => fetch('/api/ai-search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'Go to the income page' }) }).then(async r => ({ s: r.status, j: r.status === 200 ? await r.json() : null })));
    const wantNav = exp.all === '403' ? 403 : 200;
    checks++; if (navq.s !== wantNav) { failures++; line.push('  ✗ nav engine status ' + navq.s + ' (expected ' + wantNav + ')'); }
    if (navq.j) { const links = (navq.j.links || []).map(l => l.url); const hasIncome = links.includes('/income'); const shouldOpen = ['SuperAdmin', 'Admin', 'Accountant'].includes(who); checks++; if (hasIncome !== shouldOpen) { failures++; line.push('  ✗ /income link offered=' + hasIncome + ' expected ' + shouldOpen + ' (' + links.join(',') + ')'); } }
    checks++; if ((nav === 403) !== (exp.all === '403')) { failures++; line.push('  ✗ /api/ai-search/nav status ' + nav); }
    const help = await page.evaluate(() => fetch('/api/help/search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'how do I add a family' }) }).then(r => r.status));
    checks++; if ((help === 403) !== (exp.all === '403')) { failures++; line.push('  ✗ /api/help/search status ' + help); }
    console.log(line.join('\n'));
    await ctx.close();
  }
  console.log('checks:', checks, 'failures:', failures);
  await browser.close();
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
