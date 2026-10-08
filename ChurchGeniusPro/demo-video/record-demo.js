/**
 * ChurchGeniusPro — automated product-demo recorder
 * ---------------------------------------------------------------------------
 * Drives the REAL application in Chromium and records it to video:
 *   Scene 1  Create the "Christmas in the Park" event (every field explained)
 *   Scene 2  Save it
 *   Scene 3  Set the event reminder
 *   Scene 4  Public registration page + Auto-Populate
 *
 * Run:  npm install && npm run demo
 * Env:  CGP_URL (default http://localhost:8080)  CGP_USER  CGP_PASS
 *       CGP_PACE (1 = normal, 1.4 = slower, 0.8 = faster)
 *
 * All data below is fictional test data.
 */
const { chromium } = require('playwright');
const fs   = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

/* ── configuration ───────────────────────────────────────────────────────── */
const CFG = {
  baseUrl: process.env.CGP_URL  || 'http://localhost:8080',
  user:    process.env.CGP_USER || '',
  pass:    process.env.CGP_PASS || '',
  pace:    Number(process.env.CGP_PACE || 1),
  width:   1440,
  height:  900,
  outDir:  path.join(__dirname, 'output'),
  flyer:   path.join(__dirname, 'assets', 'christmas-in-the-park-flyer.png'),
  presenter: 'Sarah  ·  Events Coordinator',
};

const EVENT = {
  name:        'Christmas in the Park',
  type:        'One Day',
  date:        '2026-12-24',           // 12/24/2026
  startTime:   '18:00',                // 6:00 PM
  endTime:     '22:00',                // 10:00 PM
  regEndDate:  '2026-12-20',           // 12/20/2026
  fee:         '$30',
  maxCapacity: '500',
  showRegistrants: true,
  allowMaybe:      true,
  generateRegId:   true,
  generateQr:      false,
  selfCheckin:     false,
  address1: '1234 Example Street',
  city:     'Sharine',
  stateValue: '32',                    // NY – New York
  country:  'USA',
  pinCode:  '123456',
  hostName:  'City Christian Holy Church',
  hostPhone: '1234567890',
  hostEmail: 'info@cchc.com',
  hostNote:  '',                       // intentionally blank
  foodAvailable: true,
  foodOptions: 'Veg, Pizza, Burger',   // choices registrants pick from
  accommodationAvailable: false,
  note: 'Please come 15 mins earlier for the registration and collecting the Christmas gifts. Seats are assigned for the Christmas shows and dinner.',
};

const REMINDER = {
  sameDay: true,
  beforeDays: 2,
  // placeholders are the ones eventReminders.html actually substitutes
  sameDayTemplate:
    'Thank you for registering for {eventName}. The event will take place today.\n' +
    'Date: {date}\n' +
    'Time: {time}\n' +
    'Location: {location}\n' +
    'Direction: {map}\n' +
    'Add to calendar: {calendar}',
};
const REGISTRANT_EMAIL = 'thisisatest@gmail.com';

/* ── on-screen overlay (cursor, captions, scene cards) ───────────────────── */
function installOverlay() {
  if (window.__demoInstalled) return;
  window.__demoInstalled = true;

  const CSS = `
  #__demo-root,#__demo-root *{box-sizing:border-box}
  #__demo-root{position:fixed;inset:0;z-index:2147483647;pointer-events:none;
    font-family:'Segoe UI',system-ui,-apple-system,sans-serif}
  #__demo-cursor{position:fixed;left:-100px;top:-100px;width:26px;height:26px;
    margin:-2px 0 0 -2px;transition:none;filter:drop-shadow(0 2px 3px rgba(0,0,0,.45))}
  .__demo-ripple{position:fixed;width:14px;height:14px;margin:-7px 0 0 -7px;border-radius:50%;
    border:2px solid #f5a524;animation:__dr .55s ease-out forwards}
  @keyframes __dr{from{transform:scale(.4);opacity:.95}to{transform:scale(3.6);opacity:0}}
  #__demo-ring{position:fixed;border:2px solid #f5a524;border-radius:9px;opacity:0;
    box-shadow:0 0 0 4px rgba(245,165,36,.18);transition:all .28s cubic-bezier(.4,0,.2,1)}
  #__demo-caption{position:fixed;left:34px;bottom:34px;max-width:560px;padding:16px 20px;
    transition:opacity .3s ease,transform .3s ease;
    background:rgba(58,15,45,.96);border-left:4px solid #f5a524;border-radius:10px;
    box-shadow:0 14px 40px rgba(0,0,0,.34);opacity:0;transform:translateY(12px);
    transition:opacity .3s ease,transform .3s ease}
  #__demo-caption.on{opacity:1;transform:translateY(0)}
  #__demo-caption.top{bottom:auto;top:34px}
  #__demo-caption.right{left:auto;right:34px}
  #__demo-caption .t{color:#f5a524;font-size:13px;font-weight:700;letter-spacing:.08em;
    text-transform:uppercase;margin-bottom:5px}
  #__demo-caption .d{color:#fff;font-size:17px;line-height:1.45;font-weight:400}
  #__demo-scene{position:fixed;inset:0;background:linear-gradient(135deg,#3a0f2d,#6b1b46);
    display:flex;flex-direction:column;align-items:center;justify-content:center;
    opacity:0;transition:opacity .45s ease}
  #__demo-scene.on{opacity:1}
  #__demo-scene .n{color:#f5a524;font-size:17px;font-weight:700;letter-spacing:.26em;
    text-transform:uppercase;margin-bottom:14px}
  #__demo-scene .s{color:#fff;font-size:46px;font-weight:700;letter-spacing:-.01em;text-align:center;padding:0 40px}
  #__demo-scene .sub{color:rgba(255,255,255,.72);font-size:17px;margin-top:16px}
  #__demo-badge{position:fixed;right:26px;top:22px;padding:8px 14px;border-radius:999px;
    background:rgba(58,15,45,.9);color:#fff;font-size:12px;letter-spacing:.05em}
  `;

  const CURSOR = '<svg viewBox="0 0 24 24" width="26" height="26"><path d="M5 2l14 8.6-6.1 1.3 3.2 6.6-2.7 1.3-3.2-6.6L5 18z" fill="#fff" stroke="#222" stroke-width="1.3" stroke-linejoin="round"/></svg>';

  const build = () => {
    const st = document.createElement('style'); st.textContent = CSS;
    document.head.appendChild(st);
    const r = document.createElement('div');
    r.id = '__demo-root';
    r.innerHTML =
      '<div id="__demo-cursor">' + CURSOR + '</div>' +
      '<div id="__demo-ring"></div>' +
      '<div id="__demo-caption"><div class="t"></div><div class="d"></div></div>' +
      '<div id="__demo-badge"></div>' +
      '<div id="__demo-scene"><div class="n"></div><div class="s"></div><div class="sub"></div></div>';
    document.documentElement.appendChild(r);
    const c = r.querySelector('#__demo-cursor');
    if (window.__demoPos) { c.style.left = window.__demoPos.x + 'px'; c.style.top = window.__demoPos.y + 'px'; }
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build);
  else build();

  document.addEventListener('mousemove', e => {
    window.__demoPos = { x: e.clientX, y: e.clientY };
    const c = document.getElementById('__demo-cursor');
    if (c) { c.style.left = e.clientX + 'px'; c.style.top = e.clientY + 'px'; }
  }, true);

  document.addEventListener('mousedown', e => {
    const rt = document.getElementById('__demo-root'); if (!rt) return;
    const d = document.createElement('div');
    d.className = '__demo-ripple'; d.style.left = e.clientX + 'px'; d.style.top = e.clientY + 'px';
    rt.appendChild(d); setTimeout(() => d.remove(), 600);
  }, true);

  const $ = id => document.getElementById(id);
  window.__demo = {
    caption(t, d, pos) { const c = $('__demo-caption'); if (!c) return;
      c.classList.toggle('top',   pos === 'tl' || pos === 'tr');
      c.classList.toggle('right', pos === 'tr' || pos === 'br');
      c.querySelector('.t').textContent = t; c.querySelector('.d').textContent = d; c.classList.add('on'); },
    hideCaption() { const c = $('__demo-caption'); if (c) c.classList.remove('on'); },
    ring(x, y, w, h) { const r = $('__demo-ring'); if (!r) return;
      r.style.left = (x - 5) + 'px'; r.style.top = (y - 5) + 'px';
      r.style.width = (w + 10) + 'px'; r.style.height = (h + 10) + 'px'; r.style.opacity = 1; },
    hideRing() { const r = $('__demo-ring'); if (r) r.style.opacity = 0; },
    badge(txt) { const b = $('__demo-badge'); if (b) b.textContent = txt; },
    scene(n, s, sub) { const e = $('__demo-scene'); if (!e) return;
      e.querySelector('.n').textContent = n; e.querySelector('.s').textContent = s;
      e.querySelector('.sub').textContent = sub || ''; e.classList.add('on'); },
    hideScene() { const e = $('__demo-scene'); if (e) e.classList.remove('on'); },
  };
}

/* ── driving helpers ─────────────────────────────────────────────────────── */
const script = [];
let t0 = Date.now();
const sleep = ms => new Promise(r => setTimeout(r, Math.max(0, ms * CFG.pace)));
const stamp = () => {
  const s = Math.round((Date.now() - t0) / 1000);
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
};
const ui = async (page, fn, ...a) => { try { await page.evaluate(fn, ...a); } catch (_) {} };

async function say(page, title, text, hold = 2000, pos = 'bl') {
  script.push(`[${stamp()}]  ${title}\n           ${text}`);
  console.log(`  ${stamp()}  ${title} — ${text}`);
  await ui(page, ([t, d, p]) => window.__demo && window.__demo.caption(t, d, p), [title, text, pos]);
  await sleep(hold);
}

/**
 * Show the thing, THEN talk about it: scroll it into view, ring it, and put the
 * caption on whichever side of the screen the element is not occupying.
 */
async function announce(page, sel, title, text, hold = 2600) {
  let b = null;
  try {
    await page.locator(sel).first().waitFor({ state: 'visible', timeout: 4000 });
    await scrollTo(page, sel);
    b = await ring(page, sel);
  } catch (e) {
    // A step whose element is absent (permission, feature flag, layout change)
    // is skipped rather than killing the run. It simply gets no caption, and
    // the voice-over build skips it too, because it never reaches the script.
    console.log(`  [skipped] ${title} — no element for ${sel}`);
    return;
  }
  const vh = page.viewportSize().height, vw = page.viewportSize().width;
  let pos = 'bl';
  if (b) {
    const low   = (b.y + b.height / 2) > vh * 0.52;      // element low  -> caption top
    const left  = (b.x + b.width  / 2) < vw * 0.55;      // element left -> caption right
    pos = (low ? 't' : 'b') + (left ? 'r' : 'l');
  }
  await sleep(280);
  await say(page, title, text, hold, pos);
}
async function clearCaption(page) { await ui(page, () => window.__demo && window.__demo.hideCaption()); }

async function scrollTo(page, sel) {
  await page.locator(sel).first().evaluate(el =>
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })).catch(() => {});
  await sleep(650);
}
async function ring(page, sel) {
  const b = await page.locator(sel).first().boundingBox();
  if (b) await ui(page, ([x, y, w, h]) => window.__demo && window.__demo.ring(x, y, w, h),
                  [b.x, b.y, b.width, b.height]);
  return b;
}
async function moveTo(page, sel, opts = {}) {
  await scrollTo(page, sel);
  const b = await ring(page, sel);
  if (!b) return null;
  await page.mouse.move(b.x + Math.min(b.width / 2, 260), b.y + b.height / 2,
                        { steps: opts.steps || 26 });
  await sleep(220);
  return b;
}
async function click(page, sel, hold = 420) {
  await moveTo(page, sel);
  await page.locator(sel).first().click({ timeout: 15000 });
  await sleep(hold);
}
async function typeIn(page, sel, value, delay = 62) {
  await moveTo(page, sel);
  const el = page.locator(sel).first();
  await el.click();
  await sleep(160);
  // A field that already holds a value (the reminder page suggests "3" days)
  // must be selected first, or the new digits are appended: 3 + 2 = "32".
  const existing = await el.inputValue().catch(() => '');
  if (existing) {
    await page.keyboard.press('ControlOrMeta+A');
    await sleep(340);                       // let the viewer see the selection
  }
  await el.pressSequentially(String(value), { delay });
  await sleep(360);
  const got = await el.inputValue().catch(() => String(value));
  if (got !== String(value)) { await el.fill(String(value)); await sleep(250); }
}
/** explain a field, then fill it */
async function field(page, sel, value, title, why, opts = {}) {
  await say(page, title, why, opts.hold || 2100);
  if (value !== null && value !== undefined && value !== '') await typeIn(page, sel, value, opts.delay);
  else { await moveTo(page, sel); await sleep(500); }
}
/** date / time inputs: type it like a person, verify, repair if the segments misread */
async function typeDate(page, sel, iso, digits) {
  await moveTo(page, sel);
  // click the FIRST segment (month / hour). A centred click lands on the year
  // segment or the picker button, and Chromium then ignores the typed digits.
  await page.locator(sel).first().click({ position: { x: 10, y: 10 } });
  await sleep(150);
  await page.locator(sel).first().pressSequentially(digits, { delay: 150 });
  await sleep(400);
  const got = await page.locator(sel).first().inputValue();
  if (got !== iso) { await page.locator(sel).first().fill(iso); await sleep(300); }
}
async function setCheckbox(page, sel, want) {
  const el = page.locator(sel).first();
  const is = await el.isChecked();
  if (is !== want) { await moveTo(page, sel); await el.click(); await sleep(420); }
  else { await moveTo(page, sel); await sleep(420); }
}
/**
 * Close a modal and PROVE it closed. A modal left open dims the page and blocks
 * every later click -- that is what stranded the Registrants list over the
 * volunteers / check-in / invite explanations and then killed the run.
 */
async function closeModal(page, sel, closerSel, pageFn) {
  try {
    await click(page, closerSel, 700);
    await page.locator(sel).waitFor({ state: 'hidden', timeout: 6000 });
  } catch (e) {
    await page.evaluate((f) => { try { window[f] && window[f](); } catch (_) {} }, pageFn);
    await sleep(600);
  }
  const stuck = await page.locator(sel).isVisible().catch(() => false);
  if (stuck) console.log(`  [warn] ${sel} would not close`);
  await sleep(500);
}

async function sceneCard(page, n, title, sub, hold = 2400) {
  script.push(`\n[${stamp()}]  ══ ${n} — ${title} ══\n`);
  console.log(`\n${stamp()}  ══ ${n} — ${title}`);
  await clearCaption(page);
  await ui(page, ([n, t, s]) => window.__demo && window.__demo.scene(n, t, s), [n, title, sub || '']);
  await sleep(hold);
  await ui(page, () => window.__demo && window.__demo.hideScene());
  await sleep(700);
}

/* ── the demo ────────────────────────────────────────────────────────────── */
(async () => {
  fs.mkdirSync(CFG.outDir, { recursive: true });
  if (!fs.existsSync(CFG.flyer)) { console.error('Missing flyer image: ' + CFG.flyer); process.exit(1); }

  // Headless by DEFAULT. A headed Chromium window shorter than the recording
  // height makes Playwright record the page into part of the canvas and pad the
  // rest with flat grey (128,128,128) -- that grey is what hid the Save Event and
  // Set Event Reminder buttons. CGP_HEADED=1 forces a window, sized to suit.
  const headed = process.env.CGP_HEADED === '1';
  const browser = await chromium.launch({
    headless: !headed,
    args: headed ? [`--window-size=${CFG.width + 16},${CFG.height + 120}`] : [],
  });
  const context = await browser.newContext({
    viewport: { width: CFG.width, height: CFG.height },
    locale: 'en-US',
    timezoneId: 'America/Chicago',
    recordVideo: { dir: CFG.outDir, size: { width: CFG.width, height: CFG.height } },
  });
  // overlay on every document; keep "Register Page" in the same tab so it lands in one video
  await context.addInitScript(installOverlay);
  await context.addInitScript(() => { window.open = (u) => { if (u) location.href = u; return null; }; });

  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  t0 = Date.now();

  try {
    /* ── login ─────────────────────────────────────────────────────────── */
    await page.goto(CFG.baseUrl + '/login', { waitUntil: 'domcontentloaded' });
    await sleep(1200);
    await ui(page, (b) => window.__demo && window.__demo.badge(b), CFG.presenter);

    if (CFG.user && CFG.pass) {
      await say(page, 'Sign in', 'Signing in to the ChurchGeniusPro admin portal.', 1500);
      await typeIn(page, '#username', CFG.user, 80);
      await typeIn(page, '#password', CFG.pass, 80);
      await click(page, '#loginBtn');
    } else {
      console.log('\n  >> No CGP_USER / CGP_PASS set — sign in manually in the window. Waiting…\n');
      await say(page, 'Sign in', 'Signing in to the ChurchGeniusPro admin portal.', 1000);
    }
    await page.waitForURL(u => !String(u).includes('/login'), { timeout: 180000 });
    await sleep(1600);
    await clearCaption(page);

    /* ── Scene 1 ───────────────────────────────────────────────────────── */
    await sceneCard(page, 'Scene 1', 'Create an Event',
                    'Christmas in the Park  ·  every field explained');

    await page.goto(CFG.baseUrl + '/events', { waitUntil: 'domcontentloaded' });
    await sleep(1500);
    await say(page, 'Event Management',
      'Every event the church runs lives here — upcoming, past, registrations and check-in.', 2600);
    await click(page, 'button:has-text("New Event")');
    await page.waitForURL(/\/event(\?|$)/, { timeout: 20000 });
    await sleep(1400);
    await say(page, 'Create Event',
      'The event form is grouped into Details, Schedule, Registration, Location, Host and Additional Details.', 2800);

    /* Event details */
    await field(page, '#eventName', EVENT.name, 'Event Name',
      'The public name of the event — it appears on the registration page, reminders and check-in screens.');

    await say(page, 'Event ID / Code',
      'An optional short code used to search for this event. Click Generate and the app creates a guaranteed-unique one.', 2600);
    await click(page, 'button[onclick="generateCode()"]');
    const code = await page.locator('#eventCode').inputValue();
    await say(page, 'Event ID / Code', 'Generated: ' + code, 1800);

    await say(page, 'Event Type',
      'One Day for a single-date event; Multiple Days opens a per-day schedule instead. This one is One Day.', 2500);
    await click(page, 'input[name="eventType"][value="One Day"]');

    /* Schedule */
    await say(page, 'Date', 'The day the event takes place — 12/24/2026, Christmas Eve.', 2200);
    await typeDate(page, '#eventDate', EVENT.date, '12242026');
    await say(page, 'Start Time', 'When the programme begins — 6:00 PM. It is shown on the invitation and reminders.', 2200);
    await typeDate(page, '#startTime', EVENT.startTime, '0600P');
    await say(page, 'End Time', 'When the event closes — 10:00 PM.', 1900);
    await typeDate(page, '#endTime', EVENT.endTime, '1000P');

    /* Registration */
    await say(page, 'Registration End Date',
      'Registration closes automatically after this date — 12/20/2026, four days before the event.', 2600);
    await typeDate(page, '#registrationEndDate', EVENT.regEndDate, '12202026');
    await field(page, '#fee', EVENT.fee, 'Registration Fee',
      'What each registrant pays. Leave it as Free for a no-cost event; this one is $30.');
    await field(page, '#maxCapacity', EVENT.maxCapacity, 'Max Capacity',
      'The seat limit. Registration stops once 500 people have signed up; blank means unlimited.');

    await say(page, 'Who else is attending?',
      'When enabled, registrants can see who else is coming on the public page. Leaving it on.', 2600);
    await setCheckbox(page, '#showRegistrants', EVENT.showRegistrants);
    await say(page, 'Allow "Maybe"',
      'Adds a Maybe option beside Yes and No, so undecided families still appear in the count. Turning it on.', 2600);
    await setCheckbox(page, '#allowMaybeRsvp', EVENT.allowMaybe);
    await say(page, 'Unique Registration ID',
      'Gives every attendee an ID that volunteers can look up and print at the door. Turning it on.', 2600);
    await setCheckbox(page, '#generateRegistrationId', EVENT.generateRegId);
    await say(page, 'Unique QR Code',
      'A scannable code for check-in. This event uses printed ID lookup instead, so it stays off.', 2600);
    await setCheckbox(page, '#generateQrCode', EVENT.generateQr);
    await say(page, 'Self Check-In',
      'Lets guests check themselves in from their phone. Volunteers will handle the desk here, so it stays off.', 2600);
    await setCheckbox(page, '#selfCheckinEnabled', EVENT.selfCheckin);

    /* Location — fictional test address */
    await say(page, 'Location', 'The venue address. Everything used here is fictional test data.', 2400);
    await field(page, '#address1', EVENT.address1, 'Address 1',
      'Street address of the venue — it drives the map link in reminders. Test address only.');
    await field(page, '#city', EVENT.city, 'City', 'The city the venue is in.');
    await say(page, 'State', 'The state, picked from the list — New York.', 2000);
    await moveTo(page, '#state');
    await page.selectOption('#state', EVENT.stateValue);
    await sleep(700);
    await say(page, 'Country', 'The country — USA is already selected by default.', 2000);
    await moveTo(page, '#country');
    await page.selectOption('#country', EVENT.country);
    await sleep(600);
    await field(page, '#pinCode', EVENT.pinCode, 'PIN / Zip Code',
      'Postal code for the venue, used for directions.');

    /* Host */
    await say(page, 'Host Information', 'Who guests should contact about this event.', 2200);
    await field(page, '#hostName', EVENT.hostName, 'Host Name',
      'The organising church or contact person shown to registrants.');
    await field(page, '#hostPhone', EVENT.hostPhone, 'Phone Number',
      'A contact number printed on the registration page and reminders.');
    await field(page, '#hostEmail', EVENT.hostEmail, 'Email',
      'Replies and registration questions go to this address.');
    await say(page, 'Note',
      'Free-text host notes — internal only. Leaving it blank for this event.', 2400);
    await moveTo(page, '#hostNote');

    /* Additional details */
    await say(page, 'Is Food Available?',
      'Turning this on adds a dietary-preference question to the registration form so catering can plan.', 2800);
    await setCheckbox(page, '#foodAvailable', EVENT.foodAvailable);
    if (EVENT.foodAvailable && EVENT.foodOptions) {
      await say(page, 'Food Options',
        'The choices registrants pick from, separated by commas — Veg, Pizza, Burger.', 2800);
      await typeIn(page, '#foodOptionsInput', EVENT.foodOptions, 58);
    }
    await say(page, 'Is Accommodation Available?',
      'For multi-day events with lodging. Not needed for a one-evening event, so it stays off.', 2600);
    await setCheckbox(page, '#accommodationAvailable', EVENT.accommodationAvailable);

    await say(page, 'Event Note',
      'The message every registrant sees on the public page — arrival time, seating, what to bring.', 2800);
    await moveTo(page, '#noteEditor');
    await page.locator('#noteEditor').click();
    await sleep(200);
    await page.keyboard.type(EVENT.note, { delay: 22 });
    await sleep(700);

    await announce(page, '#eventImage', 'Event Image',
      'The event flyer is added here. It appears on the event card and at the top of the registration page.', 3000);
    await page.setInputFiles('#eventImage', CFG.flyer);
    // the preview is drawn by a FileReader, so wait for the <img> to actually paint
    await page.locator('#imgPreview').waitFor({ state: 'visible', timeout: 6000 }).catch(() => {});
    await page.waitForFunction(() => { const p = document.getElementById('imgPreview');
      return p && p.complete && p.naturalWidth > 0; }, null, { timeout: 4000 }).catch(() => {});
    await sleep(900);
    await announce(page, '#imgPreview', 'Event Image',
      'The uploaded flyer previews right here, so you can see it before saving.', 3200);
    await ui(page, () => window.__demo && window.__demo.hideRing());

    /* ── Scene 2 ───────────────────────────────────────────────────────── */
    await sceneCard(page, 'Scene 2', 'Review & Save');
    await say(page, 'Review', 'A quick pass back over the form before saving.', 1800);
    for (const sel of ['#eventName', '#eventDate', '#fee', '#address1', '#hostName', '#noteEditor']) {
      await scrollTo(page, sel); await ring(page, sel); await sleep(900);
    }
    await ui(page, () => window.__demo && window.__demo.hideRing());
    await announce(page, '#saveBtn', 'Save Event',
      'Everything is filled in, so we save the event with the Save Event button.', 2600);
    await click(page, '#saveBtn', 1200);
    await page.locator('#reminderPrompt').waitFor({ state: 'visible', timeout: 30000 });
    await sleep(700);
    await announce(page, '#reminderPrompt', 'Event saved',
      'Christmas in the Park is live. Reminders are a separate, deliberate step — saving an event never quietly messages anyone.', 3600);
    await announce(page, '#reminderPromptLink', 'Set Event Reminder',
      'This button opens the Event Reminders page for the event we just created.', 2800);

    /* ── Scene 3 ───────────────────────────────────────────────────────── */
    await sceneCard(page, 'Scene 3', 'Event Reminder');
    await click(page, '#reminderPromptLink');
    await page.waitForURL(/eventReminders/, { timeout: 20000 });
    await sleep(1600);
    await say(page, 'Event Reminders',
      'The event is already selected — reminders always belong to one specific event.', 2800);
    await moveTo(page, '#eventSelect');
    const selected = await page.locator('#eventSelect option:checked').textContent().catch(() => '');
    await say(page, 'Event', (selected || EVENT.name).trim(), 2000);

    await say(page, 'Suggested schedule',
      'For a new event the app suggests a schedule already — 3 days before, on the day, and 1 day after.', 3200);
    await say(page, 'Same Day',
      'The same-day reminder goes out on the morning of the event.', 2600);
    await setCheckbox(page, '#sameDay', REMINDER.sameDay);
    await say(page, 'Before Days',
      'Changing the advance reminder from 3 days to ' + REMINDER.beforeDays +
      ' — close enough to matter, with time left to register.', 3000);
    await setCheckbox(page, '#beforeDaysCheck', true);
    await typeIn(page, '#beforeDays', String(REMINDER.beforeDays), 160);
    await say(page, 'Delivery Channels',
      'Email and SMS are both enabled, so every registrant is reached the way they prefer.', 2800);
    await moveTo(page, '#chEmail');
    await sleep(600);
    await announce(page, '#sameDayTemplate', 'Same Day Template',
      'This is the message that goes out on the day. Placeholders in braces are replaced with the real event details.', 3400);
    await page.locator('#sameDayTemplate').click();
    await page.locator('#sameDayTemplate').pressSequentially(REMINDER.sameDayTemplate, { delay: 16 });
    await sleep(1200);

    await announce(page, 'button[onclick="tplTogglePreview(\'sameDayTemplate\')"]', 'Review',
      'You can review the reminder using the Review button.', 2800);
    await click(page, 'button[onclick="tplTogglePreview(\'sameDayTemplate\')"]', 1000);
    await sleep(2600);
    await say(page, 'Optional',
      'This reminder template is optional. If you do not set a custom template, a default template is used to send the reminders.', 4000, 'tl');
    await click(page, 'button[onclick="tplTogglePreview(\'sameDayTemplate\')"]', 800);

    await ui(page, () => window.__demo && window.__demo.hideRing());
    await announce(page, '#saveBtn', 'Save', 'Saving the reminder for Christmas in the Park.', 2200);
    await click(page, '#saveBtn', 2000);
    await sleep(2400);
    await announce(page, 'h3:has-text("Event Reminders List")', 'Event Reminders List',
      'Every reminder you have set up is listed here, with its schedule, channels and status.', 3000);
    await scrollTo(page, '#remindersBody');
    await sleep(2600);

    /* ── Scene 4: what else the event card can do ──────────────────────── */
    await sceneCard(page, 'Scene 4', 'Managing the Event',
                    'Registrants, volunteers, check-in and invitations');
    await page.goto(CFG.baseUrl + '/events', { waitUntil: 'domcontentloaded' });
    await sleep(1500);
    await say(page, 'Find the event', 'Searching the events list for Christmas in the Park.', 2200);
    await typeIn(page, '#searchInput', EVENT.name, 55);
    await sleep(1400);

    // Registrants — this one opens a modal, so it is safe to actually open
    await announce(page, 'button:has-text("Registrants")', 'Registrants',
      'Once the event is saved, everyone who has signed up can be viewed on the Registrants page.', 3000);
    await click(page, 'button:has-text("Registrants")', 1400);
    // events.html calls this modal #regModal2 (#regModal is the one in event.html)
    await page.locator('#regModal2').waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    await sleep(2200);
    await say(page, 'Registrants', 'Names, party size, attendance and comments — all in one list.', 3200, 'tl');
    await ui(page, () => window.__demo && window.__demo.hideRing());
    await clearCaption(page);
    // back to the events list BEFORE anything else is described
    await closeModal(page, '#regModal2', '#regModal2 button:has-text("Close")', 'closeRegModal2');

    // The rest navigate away, so they are pointed out but not opened.
    await announce(page, 'button:has-text("Volunteers")', 'Assign Volunteers',
      'Volunteers can also be assigned using the Assign Volunteers button. These volunteer-management functions will be described in a separate video.', 4600);
    await announce(page, 'button:has-text("Check-In")', 'Event Check-In',
      'Registrants can be checked in using the QR code. If someone is not registered, a walk-in check-in can also be completed from the Event Check-in page, and registration can be completed on a mobile device where that is enabled. These will be described in a separate video.', 6800);
    await announce(page, 'button:has-text("Invite")', 'Invite',
      'Using the Invite button you can manually add people and send them reminders to register for the event. These invitation functions will be described in a separate video.', 5200);
    await ui(page, () => window.__demo && window.__demo.hideRing());

    /* ── Scene 5 ───────────────────────────────────────────────────────── */
    await sceneCard(page, 'Scene 5', 'Event Registration',
                    'What a guest sees — and Auto-Populate');
    // if any overlay is somehow still up, drop it rather than clicking into it
    await page.evaluate(() => document.querySelectorAll(
      '.reg-modal-overlay.open, .modal-overlay.open, .rr-modal-overlay.open'
    ).forEach(el => el.classList.remove('open'))).catch(() => {});
    await sleep(400);
    await announce(page, 'button:has-text("Register Page")', 'Register Page',
      'Every event has a public registration link that can be shared by email, SMS or QR code.', 3000);
    await click(page, 'button:has-text("Register Page")');
    await page.waitForURL(/event-register/, { timeout: 20000 });
    await sleep(2200);

    await say(page, 'Public registration page',
      'This is what a guest sees: the flyer, the date and time, the venue and the event note.', 3200, 'br');
    await sleep(1200);
    // walk the detail card so each item is actually legible on screen
    await announce(page, '.ev-notes', 'Notes',
      'The event note we typed earlier appears here, so guests know when to arrive.', 3400)
      .catch(() => {});
    await announce(page, 'a:has-text("View on Google Maps")', 'View on Google Maps',
      'The venue address becomes a map link, so guests can get directions in one tap.', 3400);
    await announce(page, '.host-block', 'Host Contact',
      'And the host contact details we entered are shown here for questions.', 3200);
    await ui(page, () => window.__demo && window.__demo.hideRing());

    await announce(page, '#rsvpSlideBar', 'Will you be attending?',
      'The guest answers first — Yes, Maybe or No. Selecting Yes.', 3000);
    await click(page, '#slideOptYes', 1400);
    await page.locator('#step2').waitFor({ state: 'visible', timeout: 15000 });
    await sleep(1400);
    await say(page, 'Fill Information', 'The RSVP form opens for the guest’s details.', 2400);

    await say(page, 'Email',
      'The email identifies the guest. On leaving the field the app looks them up automatically.', 2800);
    await typeIn(page, '#email', REGISTRANT_EMAIL, 75);
    await say(page, 'Auto-Populate',
      'Looking up an earlier registration or member record for this church…', 2200);
    await page.keyboard.press('Tab');
    await sleep(2600);

    const filled = {
      firstName: await page.locator('#firstName').inputValue(),
      lastName:  await page.locator('#lastName').inputValue(),
      phone:     await page.locator('#phone').inputValue(),
      adults:    await page.locator('#adults').inputValue(),
      kids:      await page.locator('#kids').inputValue(),
    };
    if (filled.firstName || filled.lastName || filled.phone) {
      await ring(page, '#firstName'); await sleep(900);
      await ring(page, '#lastName');  await sleep(900);
      await ring(page, '#phone');     await sleep(900);
      await ui(page, () => window.__demo && window.__demo.hideRing());
      await say(page, 'Auto-Populate',
        `Found — name, phone and party size filled in automatically for ${filled.firstName} ${filled.lastName}.`.trim(), 3600);
    } else {
      await say(page, 'Auto-Populate',
        'No earlier record exists for this email yet, so the guest fills the form in themselves.', 3400);
      console.log('\n  NOTE: no record found for ' + REGISTRANT_EMAIL +
                  ' — register that email once (or add it as a family member) to film the populated version.\n');
    }
    await sleep(1200);
    await ui(page, () => window.__demo && window.__demo.hideRing());

    // the rest of the RSVP form, each item framed on its own
    await announce(page, '#dietPrefText, input[name="dietPref"]', 'Dietary Preferences',
      'Because food is available, guests are asked for a dietary preference — the choices we entered on the event.', 3800)
      .catch(() => {});
    await announce(page, '#regNote', 'Comment (optional)',
      'An optional comment box for special requests.', 2800);
    await announce(page, '#submitBtn', 'RSVP',
      'And the RSVP button submits the registration.', 2800);
    await announce(page, '#whoCard', 'Who else is attending?',
      'Because we enabled it on the event, guests can also see who else is coming.', 3400)
      .catch(() => {});
    await ui(page, () => window.__demo && window.__demo.hideRing());
    await sleep(800);

    await sceneCard(page, 'ChurchGeniusPro', 'Events, reminders and registration — in one place',
                    'Demonstration uses fictional test data', 3600);

  } catch (err) {
    console.error('\n  Demo stopped: ' + err.message + '\n');
    try { await page.screenshot({ path: path.join(CFG.outDir, 'error.png') }); } catch (_) {}
  }

  /* ── finish: save video + narration script ─────────────────────────────── */
  const vid = await page.video();
  const raw = vid ? await vid.path() : null;
  await context.close();
  await browser.close();

  const stampName = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
  const webm = path.join(CFG.outDir, `churchgeniuspro-demo-${stampName}.webm`);
  if (raw && fs.existsSync(raw)) fs.renameSync(raw, webm);
  fs.writeFileSync(path.join(CFG.outDir, `narration-${stampName}.txt`),
    'ChurchGeniusPro demo — narration script (timings from the recording)\n' +
    '='.repeat(70) + '\n\n' + script.join('\n\n') + '\n');

  let out = webm;
  const ff = spawnSync('ffmpeg', ['-version'], { encoding: 'utf8' });
  if (!ff.error && fs.existsSync(webm)) {
    const mp4 = webm.replace(/\.webm$/, '.mp4');
    const r = spawnSync('ffmpeg', ['-y', '-i', webm, '-c:v', 'libx264', '-preset', 'slow',
      '-crf', '20', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', mp4],
      { stdio: 'ignore' });
    if (!r.error && fs.existsSync(mp4) && fs.statSync(mp4).size > 50000) out = mp4;
    else if (fs.existsSync(mp4)) fs.unlinkSync(mp4);   // conversion failed — keep the webm
  }
  console.log('\n  Video:     ' + out);
  console.log('  Narration: ' + path.join(CFG.outDir, `narration-${stampName}.txt`) + '\n');
})();
