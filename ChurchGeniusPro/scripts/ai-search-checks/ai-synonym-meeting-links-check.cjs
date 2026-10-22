// Live check: synonyms (API + bar), the four-question meeting conversation with the link, public links.
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1280, height: 900 } }); const page = await ctx.newPage();
  page.on('pageerror', e => console.log('PAGE ERROR:', e.message));
  let fail = 0; const ok = (label, cond, extra) => { console.log((cond ? '✓ ' : '✗ ') + label + (extra ? ' — ' + String(extra).slice(0, 170) : '')); if (!cond) fail++; };
  await page.goto('http://localhost:8080/login');
  await page.evaluate(async () => { await fetch('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'grace.pastor_799791', password: 'd4Au6JsxN4' }) }); await fetch('/api/demo/trial-agreement/accept', { method: 'POST' }); });
  const api = q => page.evaluate(q => fetch('/api/ai-search/data', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ query: q }) }).then(r => r.json()), q);

  // ── 1. Synonyms: one person, every way of asking (API) ──
  for (const q of ["What is Mark's contribution this month?", 'How much has Mark donated this month?', 'How much did Mark give this month?',
                   "Show Mark's donations this month.", "Mark Brooks' offerings this month", 'Has Mark given anything this month?']) {
    const d = await api(q);
    ok('API person: ' + q, d.intent === 'contributions' && /Mark Brooks gave \$23\.58 this month/.test(d.answer), d.answer);
  }
  let d = await api("Mark's offerings in September");
  ok('API person + other period → that person, not the church total', d.intent === 'contributions' && /Mark Brooks/.test(d.answer) && /September/.test(d.answer), d.answer);
  d = await api('How much did Zebulon give this month?');
  ok('API unknown person → said so, no church total', /could not find an active member named “Zebulon”/.test(d.answer), d.answer);
  // church-wide synonyms
  const church = [['What were the offerings this month?', 'income'], ['Who are the donors this month?', 'contributors'], ['What was our revenue this month?', 'income'],
                  ['How much did we spend last month?', 'expenses'], ['Show the ledger for this month', 'transactions'], ['Any meetups coming up?', 'meetings'],
                  ['Upcoming programs', 'events'], ['Who is on duty this week?', 'volunteers']];
  for (const [q, intent] of church) { d = await api(q); ok('API category: ' + q + ' → ' + intent, d.intent === intent, d.intent + ' | ' + d.answer); }

  // ── 2. Same questions typed in the bar (where they used to become navigation) ──
  for (const q of ['How much did Mark give this month?', "Show Mark's donations this month.", 'Has Mark given anything this month?']) {
    await page.goto('http://localhost:8080/home'); await page.waitForTimeout(1800);
    await page.fill('#aiSearchInput', q); await page.click('#aiSearchBtn'); await page.waitForTimeout(2500);
    const ans = await page.locator('#aiResultsWrap .ai-answer').last().textContent().catch(() => '');
    ok('BAR: ' + q, page.url().endsWith('/home') && /Mark Brooks gave/.test(ans), page.url() + ' | ' + ans);
  }
  await page.goto('http://localhost:8080/home'); await page.waitForTimeout(1500);
  await page.fill('#aiSearchInput', 'Go to Events'); await page.click('#aiSearchBtn'); await page.waitForTimeout(2000);
  ok('BAR: a plain page command still navigates', /\/events/.test(page.url()), page.url());

  // ── 3. Meeting conversation in the bar: category → date → location → time → confirm → link ──
  const before = await page.evaluate(() => fetch('/api/meetings').then(r => r.json()));
  await page.goto('http://localhost:8080/home'); await page.waitForTimeout(1800);
  const say = async (t) => { await page.fill('#aiSearchInput', t); await page.click('#aiSearchBtn'); await page.waitForTimeout(1800); return (await page.locator('#aiResultsWrap .ai-answer').last().textContent()) || ''; };
  let a = await say('Create a meeting'); ok('asks category first', /Which category is this meeting\?/.test(a), a);
  a = await say('Bible Study');       ok('then date', /What date is the Bible Study meeting\?/.test(a), a);
  a = await say('April 4 2027');      ok('then location', /Where will it be held\?/.test(a), a);
  a = await say("at Jordan Hale's house"); ok('then time', /What time does it start\?/.test(a), a);
  a = await say('7 to 8:30 pm');      ok('then confirmation with all four', /Ready to create a one-time Bible Study meeting on Sunday, April 4, 2027 at 7:00 PM – 8:30 PM at Jordan Hale’s home/.test(a), a);
  const cells = await page.locator('#aiResultsWrap .aa-sum').allTextContents();
  ok('summary shows Location', cells.some(c => /Location.*Jordan Hale/.test(c)), cells.join(' | '));
  await page.locator('.aa-confirm').first().click(); await page.waitForTimeout(2500);
  a = (await page.locator('#aiResultsWrap .ai-answer').last().textContent()) || '';
  ok('created message', /^Created a one-time Bible Study meeting/.test(a), a);
  const linkBtn = page.locator('#aiResultsWrap .aa-open[data-url^="/meetings?meetingId="]').first();
  const href = await linkBtn.getAttribute('data-url').catch(() => null);
  ok('link to that meeting shown', !!href && /^\/meetings\?meetingId=\d+$/.test(href), href);
  const after = await page.evaluate(() => fetch('/api/meetings').then(r => r.json()));
  const made = after.find(m => m.meetingDate === '2027-04-04');
  ok('exactly one meeting created, with Jordan Hale as location', after.length === before.length + 1 && made && made.locationMemberId === 31, made && JSON.stringify({ id: made.id, loc: made.locationMemberId, start: made.startTime, end: made.endTime }));
  ok('link id = created id', href === '/meetings?meetingId=' + (made && made.id));
  await linkBtn.click(); await page.waitForTimeout(3000);
  ok('link opens the Meetings page with that meeting in the edit form', /\/meetings\?meetingId=\d+$/.test(page.url())
       && (await page.textContent('#formTitle')) === 'Edit Meeting' && (await page.inputValue('#startTime')) === '19:00'
       && /Jordan Hale/.test(await page.inputValue('#locationSearch')), page.url() + ' | ' + (await page.textContent('#formTitle')) + ' | ' + (await page.inputValue('#locationSearch')));
  // bad id → the page's own "no longer available" message, list still loads
  await page.goto('http://localhost:8080/meetings?meetingId=999999'); await page.waitForTimeout(2500);
  ok('unknown meetingId → no edit form, page still works', (await page.textContent('#formTitle')) !== 'Edit Meeting', await page.textContent('#formTitle'));
  if (made) await page.evaluate(id => fetch('/api/meetings/' + id, { method: 'DELETE' }), made.id);

  // ── 4. Public links: names as links to real public addresses ──
  d = await api('Show the public links');
  ok('API: public links carry label/description/url, no internal route in the text', Array.isArray(d.publicLinks) && d.publicLinks.length >= 3 && !/\/connect|\/upcomingEvents/.test(d.answer),
     JSON.stringify(d.publicLinks && d.publicLinks.map(l => [l.label, l.url.replace(/=.*/, '=…'), l.description])));
  await page.goto('http://localhost:8080/home'); await page.waitForTimeout(1800);
  await page.fill('#aiSearchInput', 'Show the public links'); await page.click('#aiSearchBtn'); await page.waitForTimeout(2500);
  const anchors = await page.$$eval('#aiResultsWrap .aa-publinks a', as => as.map(a => ({ text: a.textContent, href: a.getAttribute('href'), target: a.target })));
  ok('BAR: each screen name is a link', anchors.length >= 3 && anchors.every(x => x.href.startsWith('/') && !x.text.startsWith('/') && x.target === '_blank'), JSON.stringify(anchors.map(x => x.text)));
  const panelText = await page.textContent('#aiResultsWrap');
  ok('BAR: no internal route or token visible as text', !/\/viewEventCalendar|\/connect|\/upcomingEvents|\?c=|\?cid=/.test(panelText));
  const connect = anchors.find(x => x.text === 'Connect With Us');
  if (connect) {
    const [pop] = await Promise.all([page.waitForEvent('popup', { timeout: 10000 }), page.click('#aiResultsWrap .aa-publinks a >> text="' + connect.text + '"')]);
    await pop.waitForLoadState('domcontentloaded'); await pop.waitForTimeout(1500);
    const st = await pop.evaluate(() => document.title + ' | ' + location.pathname);
    ok('clicking a name opens that public screen', /\/connect/.test(pop.url()) && !/login/i.test(pop.url()), st);
    await pop.close();
  }
  // chat page carries the links too
  await page.goto('http://localhost:8080/ai-assistant'); await page.waitForTimeout(2500);
  await page.fill('#aiSearchInput', 'Show the public links'); await page.keyboard.press('Enter'); await page.waitForTimeout(2500);
  ok('chat page: public links rendered as links', (await page.locator('.aa-publinks a').count()) >= 3);
  console.log('failures:', fail); await browser.close(); process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
