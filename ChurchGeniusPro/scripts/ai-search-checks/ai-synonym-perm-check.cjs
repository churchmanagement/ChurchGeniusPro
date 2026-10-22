const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  let fail = 0;
  for (const [who, u, p] of [['User', 'sam.wilson_799791', 'c7KnpQbnYM'], ['Member+AI', 'member_john_5', 'jeqAF6pgvQ'], ['Accountant−Income', 'ruth.treasurer_799791', 'TynrfqVSdW']]) {
    const ctx = await browser.newContext({ serviceWorkers: 'block' }); const page = await ctx.newPage(); await page.goto('http://localhost:8080/login');
    await page.evaluate(async ([u, p]) => { await fetch('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: p }) }); await fetch('/api/demo/trial-agreement/accept', { method: 'POST' }); }, [u, p]);
    const qs = who === 'Accountant−Income'
      ? ['How much did Mark give this month?', "Show Mark's donations this month.", 'How much did Zebulon donate this month?']
      : ['How much did Mark give this month?', "Show Mark's donations this month.", 'What was our revenue this month?', 'How much did we spend last month?', 'Show the ledger for this month', 'Who are the donors this month?', 'Create a Bible Study meeting tomorrow at 7pm in the hall', 'Show the public links'];
    for (const q of qs) {
      const r = await page.evaluate(q => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(r => r.json()), q);
      // User may create meetings and see public links (their pages); everything financial must be denied.
      const allowed = who === 'User' && (/Create a Bible/.test(q) || /public links/.test(q));
      const good = allowed ? (r.handled && !r.denied) : (r.denied === true);
      if (!good) fail++;
      console.log((good ? '✓ ' : '✗ ') + who + ': ' + q + ' → ' + (r.denied ? 'denied' : r.intent) + (r.answer && !r.denied ? ' | ' + String(r.answer).slice(0, 70) : ''));
      if (r.intent === 'meeting.ask' || r.intent === 'meeting.confirm') await page.evaluate(() => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: 'cancel' }) }));
    }
    await ctx.close();
  }
  console.log('failures:', fail); await browser.close(); process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
