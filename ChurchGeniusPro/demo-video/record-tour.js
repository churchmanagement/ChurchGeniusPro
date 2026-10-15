/**
 * ChurchGeniusPro — 5:46 feature tour recorder
 * ---------------------------------------------------------------------------
 * Drives the REAL application in Chromium and records it. Unlike record-demo.js
 * (a slow walkthrough of one workflow) this is a fast tour: it navigates to each
 * feature, highlights what matters and narrates it. Nothing is simulated — if a
 * page or element does not appear, the segment says so on screen and in the log.
 *
 * Segments 0, 14 and 20 are PRESENTER segments: the recorder renders a branded
 * hold card for the exact duration so the timeline is correct, and the Google
 * Flow presenter clip is composited over it afterwards.
 *
 * Run:  npm run tour
 * Env:  CGP_URL  CGP_USER  CGP_PASS  CGP_PACE
 *
 * All data comes from a DEMO- tenant (fictional sample data).
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const CFG = {
  baseUrl: process.env.CGP_URL || 'http://localhost:8080',
  user: process.env.CGP_USER || '',
  pass: process.env.CGP_PASS || '',
  pace: Number(process.env.CGP_PACE || 1),
  width: 1440, height: 900,
  outDir: path.join(__dirname, 'output'),
  flyer: path.join(__dirname, 'assets', 'churchgeniuspro-flyer.png'),
};

/* ── the tour ────────────────────────────────────────────────────────────────
 * secs  : segment length, must total 346 (5:46)
 * route : path to visit ('' = stay put).  kind: 'page' | 'presenter' | 'panel' | 'flyer'
 * caps  : [{ sel?, t, d }] — sel is ringed if present; caption always shows
 * say   : the narration line (written to the narration file for Piper)
 */

/* ── page interaction helpers ───────────────────────────────────────────── */

async function doFill(page, f) {
  try {
    if (f.pick === 'first') {
      await page.waitForFunction(
        (sel) => { const el = document.querySelector(sel);
                   return !!(el && el.options && [...el.options].some(o => o.value)); },
        f.sel, { timeout: 8000 });
    }
    await page.evaluate(({ sel, pick, value }) => {
      const el = document.querySelector(sel);
      if (!el) return;
      if (pick === 'first') {
        const opt = [...el.options].find(o => o.value);
        if (opt) el.value = opt.value;
      } else if (value === 'yearAgo') {
        const d = new Date(); d.setFullYear(d.getFullYear() - 1);
        el.value = d.toISOString().slice(0, 10);
      } else if (value === 'nextSunday') {
        const d = new Date(); d.setDate(d.getDate() + ((7 - d.getDay()) % 7 || 7));
        el.value = d.toISOString().slice(0, 10);
      } else {
        el.value = value;
      }
      el.dispatchEvent(new Event('input',  { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    }, f);
    await sleep(500);
  } catch (_) { console.warn('    ! fill failed:', f.sel); }
}

async function doClick(page, text) {
  try {
    const btn = page.locator(
      `button:has-text("${text}"), a:has-text("${text}"), input[type=submit][value*="${text}" i]`
    ).first();
    if (!(await btn.count())) { console.warn('    ! click target not found:', text); return; }
    await btn.click({ timeout: 6000 });
    await sleep(2200);
  } catch (_) { console.warn('    ! click failed (hidden or blocked):', text); }
}

const TOUR = [
  { n:0,  kind:'presenter', secs:14, title:'ChurchGeniusPro', sub:'The All-in-One Platform Churches Will Love',
    say:"Running a church means running a small organisation — people, money, events, volunteers. ChurchGeniusPro puts all of it in one place, with AI built in throughout. Here's a five-minute look." },

  { n:1,  kind:'page', secs:10, route:'/home.html', scene:['Dashboard','Built around how you work'],
    say:"This is your dashboard. Every section can be dragged where you want it, so each staff member sees what matters to them first.",
    caps:[{ t:'Dashboard', d:'Drag any section to reorder it — the layout is saved per user.' }] },

  { n:2,  kind:'page', secs:10, route:'/members.html', scene:['Families & Members','Everyone, in households'],
    probe:'/api/members',
    say:"Membership starts with families, not just individuals — so parents, children and contact details stay together.",
    caps:[{ t:'Families', d:'Add a family once; every member, address and contact stays linked.' }] },

  { n:3,  kind:'page', secs:7, route:'/attendance', scene:['Attendance','Two ways to take it'],
    fill:[{ sel:'#rFrom', value:'yearAgo' }],
    click:'Records',
    say:"Take attendance for a service in seconds, or let people check themselves in on a tablet at the door.",
    caps:[{ t:'Attendance', d:'Mark a whole service from one screen.' },
          { t:'Self check-in', d:'Or let people check themselves in at the door.' }] },

  { n:4,  kind:'page', secs:9, route:'/kidsMinistry.html', scene:['Kids Ministry','Check-in built for safety'],
    say:"Kids ministry has its own check-in, with matching guardian and child labels so no child leaves with the wrong adult.",
    caps:[{ t:'Kids check-in', d:'Guardian and child labels are matched at pick-up.' }] },

  { n:5,  kind:'page', secs:12, route:'/events.html', scene:['Events','Create once, publish everywhere'],
    say:"Create an event once and it publishes its own registration page. Returning guests are recognised automatically — their details fill themselves in.",
    caps:[{ t:'Create an event', d:'Dates, capacity, fees, food and accommodation.' },
          { t:'Public registration', d:'Every event gets its own page to share.' }] },

  { n:8,  kind:'page', secs:9, route:'/financial-report.html', scene:['Reports','The numbers your board asks for'],
    say:"Income, expense, transaction, tax and full financial reports are built in — the numbers your treasurer and your board actually ask for.",
    click:'Generate',
    caps:[{ t:'Financial reports', d:'Income, expense, transaction, tax and full financials.' }] },

  { n:9,  kind:'page', secs:9, route:'/payroll', scene:['Payroll','Staff pay in the same books'],
    say:"Payroll for your staff runs inside the same system, so salaries land in the same books as everything else.",
    caps:[{ t:'Payroll', d:'Pay runs post straight to your accounts.' }] },

  { n:10,  kind:'page', secs:7, route:'/memberWorship.html', scene:['Worship Planning','Automatic or by hand'],
    steps:[{ click:'Assignments' }, { click:'Automatic Assignment' },
           { fill:{ sel:'#autoGroup', pick:'first' } },
           { fill:{ sel:'#autoStartDate', value:'nextSunday' } },
           { click:'Generate' }],
    say:"Plan worship weeks ahead. Assign people automatically on a rotation, or place them by hand — drag a name where you want it.",
    caps:[{ t:'Worship assignments', d:'Rotate automatically, or drag a name into place.' }] },

  { n:11,  kind:'page', secs:11, route:'/reminders.html', scene:['Reminders & Email','Reaching everyone'],
    say:"Reminders go out by email and text — for an event, on a schedule, or as a one-off. And you can write the whole church a message from here.",
    caps:[{ t:'Reminders', d:'Event, periodic and one-time — by email and SMS.' }] },

  { n:12,  kind:'page', secs:14, route:'/ai-assistant.html', scene:['AI Assistant','Help, everywhere in the app'],
    say:"The AI assistant works throughout the application. Ask it in plain language — it understands your church's own data, and helps you fill forms by voice, by photo, or just by describing what you want.",
    caps:[{ t:'AI Assistant', d:'Ask in plain language — it knows your church data.' },
          { t:'Voice and photo', d:'Fill forms by speaking, or from a photo of a paper form.' }] },

  { n:13,  kind:'page', secs:11, route:'/ai-assistant.html', scene:['AI Search','Ask your data a question'],
    // Ask about a month the demo tenant has income for (the "March" chip answers "no income").
    steps:[{ fill:{ sel:'#aiSearchInput', value:'What was the income for September?' } }, { click:'Send' }],
    say:"And AI Search answers real questions about your church. Ask who gave last month, or which families haven't attended since Easter, and it reads your data to tell you.",
    caps:[{ t:'AI Search', d:'"Who gave last month?" — answered from your own records.' }] },

  { n:14,  kind:'presenter', secs:8, title:"And there's more", sub:'Everything else waiting inside',
    say:"That's the heart of it — but it is nowhere near all of it. Here's what else is waiting for you inside." },

  { n:15,  kind:'panel', secs:12, title:'Money & giving',
    items:['Income','Bank Import','Bank Sync','Donations & Giving','Pledges'],
    say:"On the money side: income tracking, bank import for statements, bank synchronisation, donations and giving, and pledges for your fundraising campaigns." },

  { n:16,  kind:'panel', secs:11, title:'People & ministry',
    items:['Groups','Meetings','Volunteers','Prayer Ministry','Follow-ups','Certificates','Songbook'],
    say:"For your people: Groups, Meetings, Volunteers, Prayer Ministry, Follow-ups, Certificates — and a Songbook for your worship team." },

  { n:17,  kind:'panel', secs:15, title:'Your church online',
    items:['Event Calendar','Upcoming Events','Prayer Request Form','Kids Check-in','Member Sign-up','Membership Form','Donation Form','NTag Landing Page'],
    say:"Seven public pages your church can share: an event calendar, upcoming events, prayer requests, kids check-in, member sign-up, a membership form and a donation form — plus your own NTag landing page." },

  { n:18,  kind:'panel', secs:13, title:'Running the system',
    items:['Role Assignment','Add User','Temporary Access','Private Page Access','Link Accounts','Church Settings','Favorites'],
    say:"Behind the scenes: role assignment, adding users, temporary access, private page access, linked accounts, church settings — and Favourites, so your team's most-used screens are one click away." },

  { n:19,  kind:'panel', secs:9, title:'Help when you need it',
    items:['Help Center','Ticketing','Free migration assistance'],
    say:"And if you get stuck, there's a built-in Help Center and ticketing — plus free migration assistance when you move your data across." },

  { n:20,  kind:'flyer', secs:18,
    say:"Free for small churches. Twenty dollars a month for Standard, forty for Pro with advanced AI. Start with two months free — every Pro feature included, no credit card required. Visit churchgeniuspro.com." },
];

/* ── overlay (extends record-demo.js's proven overlay with panel + flyer) ─── */
function installOverlay() {
  if (window.__tourInstalled) return;
  window.__tourInstalled = true;
  const CSS = `
  #__t-root,#__t-root *{box-sizing:border-box}
  #__t-root{position:fixed;inset:0;z-index:2147483647;pointer-events:none;
    font-family:'Segoe UI',system-ui,-apple-system,sans-serif}
  #__t-ring{position:fixed;border:2px solid #f5a524;border-radius:9px;opacity:0;
    box-shadow:0 0 0 4px rgba(245,165,36,.18);transition:all .28s cubic-bezier(.4,0,.2,1)}
  #__t-cap{position:fixed;left:34px;bottom:34px;max-width:580px;padding:16px 20px;
    background:rgba(58,15,45,.96);border-left:4px solid #f5a524;border-radius:10px;
    box-shadow:0 14px 40px rgba(0,0,0,.34);opacity:0;transform:translateY(12px);
    transition:opacity .3s ease,transform .3s ease}
  #__t-cap.on{opacity:1;transform:translateY(0)}
  #__t-cap .t{color:#f5a524;font-size:13px;font-weight:700;letter-spacing:.08em;
    text-transform:uppercase;margin-bottom:5px}
  #__t-cap .d{color:#fff;font-size:17px;line-height:1.45}
  #__t-full{position:fixed;inset:0;background:linear-gradient(135deg,#3a0f2d,#6b1b46);
    display:flex;flex-direction:column;align-items:center;justify-content:center;
    opacity:0;transition:opacity .45s ease}
  #__t-full.on{opacity:1}
  #__t-full .n{color:#f5a524;font-size:17px;font-weight:700;letter-spacing:.26em;
    text-transform:uppercase;margin-bottom:14px}
  #__t-full .s{color:#fff;font-size:46px;font-weight:700;text-align:center;padding:0 40px}
  #__t-full .sub{color:rgba(255,255,255,.72);font-size:18px;margin-top:16px}
  #__t-chips{display:flex;flex-wrap:wrap;gap:14px;justify-content:center;max-width:1040px;margin-top:34px}
  #__t-chips span{opacity:0;transform:translateY(10px);transition:all .4s ease;
    background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.22);
    color:#fff;font-size:23px;padding:13px 24px;border-radius:999px}
  #__t-chips span.on{opacity:1;transform:translateY(0)}
  #__t-img{position:fixed;inset:0;background:#4a2233;opacity:0;transition:opacity .5s ease}
  #__t-img.on{opacity:1}
  #__t-img img{width:100%;height:100%;object-fit:contain}
  #__t-warn{position:fixed;left:34px;top:26px;padding:10px 16px;border-radius:8px;
    background:#7f1d1d;color:#fff;font-size:15px;opacity:0;transition:opacity .3s}
  #__t-warn.on{opacity:1}
  /* Never film live credentials. The DEMO tenant dashboard prints working
     usernames and plaintext passwords for the staff/member/kids portals in
     #portalCredsPanel; on YouTube that is a working login to a live tenant. */
  #portalCredsPanel{display:none !important}
  /* trial feature-tip cards pop up mid-take and cover the UI */
  #cgpTrialTip,#cgpTrialTips,#cgpTrialNotice{display:none !important}
  `;
  const build = () => {
    const st = document.createElement('style'); st.textContent = CSS; document.head.appendChild(st);
    const r = document.createElement('div'); r.id = '__t-root';
    r.innerHTML =
      '<div id="__t-ring"></div>' +
      '<div id="__t-cap"><div class="t"></div><div class="d"></div></div>' +
      '<div id="__t-warn"></div>' +
      '<div id="__t-full"><div class="n"></div><div class="s"></div><div class="sub"></div><div id="__t-chips"></div></div>' +
      '<div id="__t-img"></div>';
    document.documentElement.appendChild(r);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build); else build();

  const $ = id => document.getElementById(id);
  window.__tour = {
    cap(t, d) { const c = $('__t-cap'); if (!c) return;
      c.querySelector('.t').textContent = t; c.querySelector('.d').textContent = d; c.classList.add('on'); },
    hideCap() { const c = $('__t-cap'); if (c) c.classList.remove('on'); },
    ring(x, y, w, h) { const r = $('__t-ring'); if (!r) return;
      r.style.left = (x-5)+'px'; r.style.top = (y-5)+'px';
      r.style.width = (w+10)+'px'; r.style.height = (h+10)+'px'; r.style.opacity = 1; },
    hideRing() { const r = $('__t-ring'); if (r) r.style.opacity = 0; },
    warn(msg) { const w = $('__t-warn'); if (!w) return; w.textContent = msg; w.classList.add('on'); },
    hideWarn() { const w = $('__t-warn'); if (w) w.classList.remove('on'); },
    full(n, s, sub) { const e = $('__t-full'); if (!e) return;
      e.querySelector('.n').textContent = n || ''; e.querySelector('.s').textContent = s || '';
      e.querySelector('.sub').textContent = sub || ''; $('__t-chips').innerHTML = ''; e.classList.add('on'); },
    chips(items) { const c = $('__t-chips'); if (!c) return;
      c.innerHTML = ''; items.forEach((it, i) => { const s = document.createElement('span');
        s.textContent = it; c.appendChild(s); setTimeout(() => s.classList.add('on'), 220 + i*180); }); },
    hideFull() { const e = $('__t-full'); if (e) e.classList.remove('on'); },
    img(src) { const e = $('__t-img'); if (!e) return;
      e.innerHTML = '<img src="' + src + '">'; e.classList.add('on'); },
    hideImg() { const e = $('__t-img'); if (e) e.classList.remove('on'); },
  };
}

/* ── driving ─────────────────────────────────────────────────────────────── */
const narration = [];
let t0 = Date.now();
const sleep = ms => new Promise(r => setTimeout(r, Math.max(0, ms * CFG.pace)));
const stamp = () => { const s = Math.round((Date.now() - t0) / 1000);
  return String(Math.floor(s/60)).padStart(2,'0') + ':' + String(s%60).padStart(2,'0'); };
const ui = async (page, fn, ...a) => { try { await page.evaluate(fn, ...a); } catch (_) {} };

async function ringSel(page, sel) {
  try {
    const el = await page.$(sel); if (!el) return false;
    const b = await el.boundingBox(); if (!b) return false;
    await ui(page, ([x,y,w,h]) => window.__tour && window.__tour.ring(x,y,w,h), [b.x,b.y,b.width,b.height]);
    return true;
  } catch (_) { return false; }
}

/** Narration is stamped when the segment's content is actually ON SCREEN (not when
 *  the script starts loading it), on the video's own clock, so a voice-over placed at
 *  these times lands with the picture. */
function narrate(seg) {
  const shown = stamp();
  narration.push(`[${shown}] (${seg.secs}s) ${seg.say}`);
}

async function segment(page, seg) {
  console.log(`  ${stamp()}  #${seg.n} ${seg.kind.padEnd(9)} ${seg.scene ? seg.scene[0] : (seg.title || 'flyer')}`);

  if (seg.kind === 'presenter') {
    await ui(page, ([n,s]) => window.__tour && window.__tour.full('ChurchGeniusPro', n, s), [seg.title, seg.sub]);
    narrate(seg);
    await sleep(seg.secs * 1000);
    await ui(page, () => window.__tour && window.__tour.hideFull());
    return;
  }

  if (seg.kind === 'panel') {
    await ui(page, ([t]) => window.__tour && window.__tour.full('Also included', t, ''), [seg.title]);
    narrate(seg);
    await sleep(600);
    await ui(page, (items) => window.__tour && window.__tour.chips(items), seg.items);
    await sleep(seg.secs * 1000 - 600);
    await ui(page, () => window.__tour && window.__tour.hideFull());
    return;
  }

  if (seg.kind === 'flyer') {
    let src = '';
    try { src = 'data:image/png;base64,' + fs.readFileSync(CFG.flyer).toString('base64'); }
    catch (_) { console.warn('    ! flyer image missing:', CFG.flyer); }
    if (src) await ui(page, (s) => window.__tour && window.__tour.img(s), src);
    else await ui(page, () => window.__tour && window.__tour.full('', 'churchgeniuspro.com', 'Two months free — no credit card required'));
    narrate(seg);
    await sleep(seg.secs * 1000);
    return;
  }

  /* kind === 'page' */
  await ui(page, ([n,s,sub]) => window.__tour && window.__tour.full(n, s, sub),
           ['Feature', seg.scene[0], seg.scene[1] || '']);
  await sleep(1600);
  let ok = true;
  try { await page.goto(CFG.baseUrl + seg.route, { waitUntil: 'domcontentloaded', timeout: 20000 }); }
  catch (_) { ok = false; }
  await sleep(1200);
  await ui(page, () => window.__tour && window.__tour.hideFull());
  narrate(seg);                                   // the page is on screen from here

  if (!ok) {
    await ui(page, ([m]) => window.__tour && window.__tour.warn(m), ['Page did not load: ' + seg.route]);
    console.warn('    ! page did not load:', seg.route);
  }

  // Ordered sequence, for a screen that needs tabs opened before its controls exist.
  if (seg.steps) {
    for (const st of seg.steps) {
      if (st.click) await doClick(page, st.click);
      if (st.fill)  await doFill(page, st.fill);
    }
  }

  // Optional: set a control or two first, so a form-driven screen has real input
  // to work with (auto-assign needs a group and a start date before it will run).
  if (seg.fill) { for (const f of seg.fill) await doFill(page, f); }

  // Optional: report what an endpoint actually returned, so an empty-looking
  // screen can be told apart from a screen that loaded nothing.
  if (seg.probe) {
    try {
      const r = await page.evaluate(async (u) => {
        const res = await fetch(u);
        let n = '?';
        try { const j = await res.json(); n = Array.isArray(j) ? j.length : typeof j; } catch (_) {}
        return { status: res.status, n };
      }, seg.probe);
      console.log(`    probe ${seg.probe} -> HTTP ${r.status}, ${r.n} rows`);
    } catch (e) { console.warn('    ! probe failed:', seg.probe, e.message); }
  }

  // Optional: press one button so the page shows a real result rather than an
  // empty form (Reports renders nothing until "Generate Report" is pressed).
  if (seg.click) await doClick(page, seg.click);

  const caps = seg.caps || [];
  const per = Math.max(1200, (seg.secs * 1000 - 2800) / Math.max(1, caps.length));
  for (const c of caps) {
    if (c.sel) { const found = await ringSel(page, c.sel);
      if (!found) console.warn('    ! selector not found (captioned anyway):', c.sel); }
    await ui(page, ([t,d]) => window.__tour && window.__tour.cap(t,d), [c.t, c.d]);
    await sleep(per);
    await ui(page, () => { window.__tour && window.__tour.hideRing(); });
  }
  await ui(page, () => { window.__tour && window.__tour.hideCap(); window.__tour && window.__tour.hideWarn(); });
}

/** Refuse to film anything but a sample-data tenant. DEMO-/TRIAL- clients are
 *  seeded by TestDataService.provisionTenant and contain no real church data.
 *  Without this guard a mistyped login could put a real congregation's members
 *  and giving records into a public marketing video. */
async function assertSampleTenant(page) {
  let sess = null;
  try { sess = await page.evaluate(async () => (await fetch('/api/session')).json()); }
  catch (_) { throw new Error('could not read /api/session — refusing to record'); }

  // Distinguish "not signed in" from "signed in as the wrong tenant" — they need
  // different fixes and the tenant message is misleading for a failed login.
  if (!sess || sess.authenticated !== true) {
    throw new Error(
      'login failed — not signed in, so nothing was recorded.\n' +
      `  URL  : ${CFG.baseUrl}\n` +
      `  User : ${CFG.user || '(none set)'}\n` +
      '  Check CGP_USER and CGP_PASS. If CGP_PASS still reads like placeholder text,\n' +
      '  that is the problem. Leave CGP_USER unset to sign in by hand instead.');
  }

  const id = (sess && sess.appClientId) || '';
  if (!/^(DEMO-|TRIAL-)/.test(id)) {
    throw new Error(
      `refusing to record: signed in as "${id || 'unknown'}" (${sess && sess.churchName || '?'}).\n` +
      '  Only DEMO- or TRIAL- tenants may be filmed — they hold fictional sample data.\n' +
      '  A real church tenant would put live member and giving records into the video.');
  }
  console.log(`  tenant ${id} — ${sess.churchName} (sample data, safe to film)`);
}

async function login(page) {
  await page.goto(CFG.baseUrl + '/login', { waitUntil: 'domcontentloaded' });
  if (!CFG.user) { console.log('  no CGP_USER — sign in by hand, the tour continues once /home loads');
    await page.waitForURL(/home|dashboard/i, { timeout: 300000 }); return; }
  await page.fill('#username', CFG.user).catch(() => {});
  await page.fill('#password', CFG.pass).catch(() => {});
  await Promise.all([ page.waitForLoadState('domcontentloaded'), page.click('button[type=submit]').catch(() => {}) ]);
  await sleep(1500);
}

(async () => {
  fs.mkdirSync(CFG.outDir, { recursive: true });
  const total = TOUR.reduce((a, s) => a + s.secs, 0);
  console.log(`ChurchGeniusPro tour — ${TOUR.length} segments, ${Math.floor(total/60)}:${String(total%60).padStart(2,'0')} at pace ${CFG.pace}`);
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: CFG.width, height: CFG.height },
    recordVideo: { dir: CFG.outDir, size: { width: CFG.width, height: CFG.height } },
  });
  t0 = Date.now();                                // the recording starts with the context: this is the video's clock
  await context.addInitScript(installOverlay);
  const page = await context.newPage();

  // Surface page-side failures — a screen that renders empty because its script
  // threw looks identical to one that simply has no data.
  page.on('pageerror', e => console.warn('    ! page error:', String(e).slice(0, 160)));
  page.on('console', m => { if (m.type() === 'error') console.warn('    ! console:', m.text().slice(0, 160)); });
  try {
    await login(page);
    await assertSampleTenant(page);
    for (const seg of TOUR) await segment(page, seg);
  } finally {
    const ts = new Date().toISOString().slice(0,16).replace(/[:T]/g,'-');
    await context.close(); await browser.close();
    fs.writeFileSync(path.join(CFG.outDir, `narration-tour-${ts}.txt`), narration.join('\n') + '\n');
    console.log(`\nnarration -> output/narration-tour-${ts}.txt`);
    console.log('video     -> output/*.webm (newest file)');
  }
})();
