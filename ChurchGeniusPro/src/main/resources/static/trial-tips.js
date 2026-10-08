/**
 * trial-tips.js — Feature-discovery tips for TRIAL accounts (loaded by shell.js on every
 * app page, like product-tour.js). Self-activating: it asks GET /api/trial-tips/state and
 * does nothing unless the server says this login is a staff user of a trial tenant — Free,
 * Standard, Pro, Church-portal, member, temporary and NTAG sessions never see a card.
 *
 * One small card at a time (bottom-right on desktop, bottom-centre on phones), fixed
 * position, no backdrop, no focus trap, no scroll lock: the page stays fully usable.
 * Rules: at most one card per page view; nothing while the trial agreement, the product
 * tour or the trial notice card is on screen; nothing within 10 minutes of the last tip
 * or after 3 tips in a day; a tip is shown at most twice (the second time only on a later
 * day) unless the user presses Got it / X, which retires it for good. A tip is never
 * offered for a feature the user's permissions or plan do not allow. The server keeps
 * only tip keys and timestamps per login (trial_tip_state).
 *
 * To add a tip: append an object to TIPS. {key (unique, [a-z0-9-]), title, text,
 * link?:{href,label}, pages:[path prefixes] or ['*'] for anywhere, perm?: permission key
 * (as in session.js NAV_PERM), feature?: subscription feature key (CGP_hasFeature),
 * roles?: ['SuperAdmin','Admin','Accountant','User']}.
 */
(function () {
  'use strict';
  if (window.__CGP_TRIAL_TIPS__) return;
  window.__CGP_TRIAL_TIPS__ = true;

  var CARD_ID = 'cgpTrialTip';
  var MIN_GAP_MS = 10 * 60 * 1000;      // between two tips
  var MAX_PER_DAY = 3;
  var MAX_SHOWS = 2;                    // ignored cards may come back once, on a later day
  var AUTO_HIDE_MS = 25000;
  var CACHE_KEY = 'cgpTrialTips.state'; // sessionStorage copy of the server state
  var ADMIN = ['SuperAdmin', 'Admin'];

  var TIPS = [
    { key: 'dashboard-layout', title: 'Make the dashboard yours', pages: ['/home'],
      text: 'You can add, remove and rearrange the dashboard sections. Press and hold a section, then drag it up or down to where you want it.' },
    { key: 'favorites', title: 'Keep your favourite pages one click away', pages: ['*'],
      text: 'Add the pages you use most to Favorites — look for the ⭐ next to a page in the left menu or on a listing page.' },
    { key: 'membership-form-scan', title: 'Fill in a family from a scanned form', pages: ['/viewfamily', '/family', '/membershipRequests'], perm: 'admin.family', roles: ADMIN,
      text: 'Print the Membership Form from a family page, have it filled in, then use “Upload Scanned Form” (photo or PDF) — the details are read from the form and filled in for you.',
      link: { href: '/family', label: 'Open Add Family' } },
    { key: 'membership-requests', title: 'Let families apply online', pages: ['/viewfamily', '/membershipRequests', '/publicScreens'], perm: 'admin.membership', roles: ADMIN,
      text: 'Share the public Membership Request page and new applications arrive under Membership Requests, where you can review every detail before adding them as members.',
      link: { href: '/membershipRequests', label: 'Open Membership Requests' } },
    { key: 'private-pages', title: 'Lock sensitive pages to your church network', pages: ['/private-access-settings', '/ntagAccess', '/kidsMinistry'], feature: 'privatePages', roles: ADMIN,
      text: 'On Private Page Access you can restrict Kids Ministry, Event Check-in and Temporary Login so they only work on your church’s own Wi-Fi/network.',
      link: { href: '/private-access-settings', label: 'Open Private Page Access' } },
    { key: 'recurring-transactions', title: 'Set up recurring income and expenses', pages: ['/income', '/expense'], perm: 'accounting.income', feature: 'accounting',
      text: 'Mark an income or expense as 🔁 Recurring and it shows up on the dashboard, ready to add again with just a date or amount change.' },
    { key: 'bank-import', title: 'Add transactions straight from a bank statement', pages: ['/bank-import', '/income', '/expense'], perm: 'accounting.bankimport', feature: 'bankImport',
      text: 'Bank Import reads a downloaded statement file so you can review many income and expense lines at once and add them in one go.',
      link: { href: '/bank-import', label: 'Open Bank Import' } },
    { key: 'bank-sync', title: 'Connect your bank with Bank Sync', pages: ['/bankSync', '/bank-import'], perm: 'accounting.bankimport', feature: 'bankSync',
      text: 'Bank Sync links one or more bank accounts and pulls in new transactions for you to review and add with their details. On a trial you connect a sandbox (test) bank.',
      link: { href: '/bankSync', label: 'Open Bank Sync' } },
    { key: 'payroll', title: 'Run payroll here too', pages: ['/payroll', '/expense'], perm: 'accounting.payroll', feature: 'payroll',
      text: 'Payroll handles employee pay runs including deductions, taxes and withholding — no separate payroll tool needed.',
      link: { href: '/payroll', label: 'Open Payroll' } },
    { key: 'ai-fill', title: 'Let the AI fill in the form', pages: ['/income', '/expense', '/meetings', '/meeting'], perm: 'more.aiassistant',
      text: 'On Income, Expense and Meeting screens you can speak, converse or type a sentence like “Tithe from Anson Mathew, 100 dollars” and the AI fills in the fields.' },
    { key: 'help-dots', title: 'See a ? icon? Tap it', pages: ['*'],
      text: 'The small ? icons next to fields and sections explain what that feature or screen does — a quick answer without leaving the page.' },
    { key: 'meeting-cleanup', title: 'Meeting history tidies itself', pages: ['/meetings', '/meeting'], perm: 'general.meetings',
      text: 'Past meetings are removed automatically after 90 days. Turn on “Don’t auto-delete” on a meeting you want to keep.' },
    { key: 'worship-auto-assign', title: 'Generate worship assignments automatically', pages: ['/worshipPlanning', '/memberWorship'], feature: 'worship',
      text: 'Worship Planning can generate assignments for a whole season. Skip the dates you don’t need — the order numbers keep counting correctly for the dates that remain.' },
    { key: 'ai-search', title: 'AI Search answers questions too', pages: ['*'], perm: 'more.aiassistant',
      text: 'The ✨ AI Search bar is more than navigation. Ask things like “What was the income for March?” or “How do I add a family?” and get an answer from your own records and the help guides.' },
    { key: 'reminders', title: 'Let reminders go out on their own', pages: ['/events', '/event', '/reminders', '/eventReminders', '/autoReminders', '/oneReminders'], perm: 'general.reminders', feature: 'reminders',
      text: 'Set up Reminders once and event, periodic and one-time notifications are sent to the right people automatically.',
      link: { href: '/reminders', label: 'Open Reminders' } },
    { key: 'public-screen-links', title: 'Share secure public links', pages: ['/publicScreens', '/events', '/kidsMinistry'], perm: 'more.publicscreens', feature: 'publicScreens',
      text: 'Public Screens creates encrypted links unique to your church — for the event calendar, prayer requests, kids check-in, membership form and more.',
      link: { href: '/publicScreens', label: 'Open Public Screens' } },
    { key: 'song-book', title: 'Publish a public Song Book', pages: ['/songbook', '/admin/songbook-access', '/worshipPlanning'], feature: 'songbook',
      text: 'Upload songs, finalise the list, and share it as your church’s public Song Book. Song Book Access controls who can edit it.',
      link: { href: '/songbook', label: 'Open Song Book' } },
    { key: 'ntag-landing', title: 'Customise your NTag landing page', pages: ['/ntagAccess', '/ntagLandingAdmin', '/private-access-settings'], feature: 'ntag', roles: ADMIN,
      text: 'Add public links to your NTag landing page and choose whether each appears as a main link or in the footer. Live Preview shows exactly what visitors will see.',
      link: { href: '/ntagLandingAdmin', label: 'Open NTag Landing' } },
    { key: 'advanced-help', title: 'There’s more in Advanced Help', pages: ['/helpCenter', '/help'],
      text: 'The Advanced Help screens cover the less obvious ChurchGeniusPro features step by step.',
      link: { href: '/help', label: 'Open Advanced Help' } },
    { key: 'portals', title: 'Four portals, one app', pages: ['/home', '/viewusers'],
      text: 'ChurchGeniusPro has a Church Portal, a Staff Portal, a Member Portal and a Kids Portal, each with the features that type of user needs.' },
    { key: 'stripe', title: 'Take online giving with Stripe', pages: ['/stripeIntegration', '/donation-review', '/income'], perm: 'accounting.donation', roles: ADMIN,
      text: 'Connect your church’s Stripe account to accept online donations and member contributions, and review them under Donation/Give.',
      link: { href: '/stripeIntegration', label: 'Open Stripe Integration' } },
    { key: 'linked-accounts', title: 'Switch between church accounts', pages: ['/viewusers', '/home'], roles: ['SuperAdmin'],
      text: 'If you look after more than one church, link the accounts on the Users page and switch between them without signing out.' },
    { key: 'temporary-access', title: 'Temporary logins and printable badges', pages: ['/temporaryAccess', '/ntagAccess', '/viewusers'], feature: 'ntag', roles: ADMIN,
      text: 'Give helpers temporary access with NTag or a barcode badge — generate the access card and print it from Temporary Access.',
      link: { href: '/temporaryAccess', label: 'Open Temporary Access' } }
  ];

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function pathNow() { return (location.pathname || '/').replace(/\.html$/, ''); }
  function matchesPage(tip) {
    var p = pathNow();
    return tip.pages.some(function (x) { return x !== '*' && (p === x || p.indexOf(x + '/') === 0 || p.indexOf(x + '?') === 0); });
  }
  function anywhere(tip) { return tip.pages.indexOf('*') !== -1; }
  function allowed(tip, role) {
    if (tip.roles && tip.roles.indexOf(role) === -1) return false;
    if (tip.perm && window.CGP_PERMS && window.CGP_PERMS[tip.perm] === false) return false;   // null = full access
    if (tip.feature && typeof window.CGP_hasFeature === 'function' && !window.CGP_hasFeature(tip.feature)) return false;
    return true;
  }
  function blocked() {
    return !!(document.getElementById('cgpDemoTrialOverlay') || document.getElementById('cgpTourOverlay')
           || document.getElementById('cgpTrialNotice') || document.getElementById(CARD_ID));
  }
  function parseTs(s) { var t = s ? Date.parse(s) : NaN; return isNaN(t) ? 0 : t; }
  function sameDay(a, b) { var x = new Date(a), y = new Date(b); return x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate(); }

  /* ── choose one tip for this page view, or null ── */
  function pick(state) {
    var now = parseTs(state.now) || Date.now();
    if (state.lastShownAt && now - parseTs(state.lastShownAt) < MIN_GAP_MS) return null;
    if ((state.shownToday || 0) >= MAX_PER_DAY) return null;
    var seen = state.tips || {};
    function ok(tip) {
      var s = seen[tip.key];
      if (s && s.dismissed) return false;
      if (s && s.shown >= MAX_SHOWS) return false;
      if (s && s.shown > 0 && s.lastShownAt && sameDay(parseTs(s.lastShownAt), now)) return false;
      return allowed(tip, state.role || '');
    }
    var unseen = function (t) { return !seen[t.key] || !seen[t.key].shown; };
    var ctx = TIPS.filter(function (t) { return matchesPage(t) && ok(t); });
    var gen = TIPS.filter(function (t) { return anywhere(t) && ok(t); });
    return ctx.filter(unseen)[0] || gen.filter(unseen)[0] || ctx[0] || gen[0] || null;
  }

  /* ── state cache (session only; refreshed after every shown / dismissed) ── */
  function cached() { try { return JSON.parse(sessionStorage.getItem(CACHE_KEY) || 'null'); } catch (e) { return null; } }
  function cache(s) { try { sessionStorage.setItem(CACHE_KEY, JSON.stringify(s)); } catch (e) {} }
  function post(key, what) {
    return fetch('/api/trial-tips/' + encodeURIComponent(key) + '/' + what, { method: 'POST', credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
  }
  function loadState() {
    var c = cached();
    if (c && c.eligible === false) return Promise.resolve(c);           // not a trial login: stays quiet for the session
    return fetch('/api/trial-tips/state', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (s) { if (s) cache(s); return s; })
      .catch(function () { return null; });
  }

  /* ── the card ── */
  function injectStyle() {
    if (document.getElementById('cgpTrialTipStyle')) return;
    var st = document.createElement('style'); st.id = 'cgpTrialTipStyle';
    st.textContent =
      '#' + CARD_ID + '{box-sizing:border-box;position:fixed;right:16px;bottom:16px;z-index:2147482000;width:340px;max-width:calc(100vw - 32px);' +
      'background:#fff;border:1px solid #e3d5dc;border-left:4px solid #673147;border-radius:12px;box-shadow:0 10px 30px rgba(40,20,30,.18);' +
      'padding:12px 14px 12px 16px;font:13.5px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#2b2b36;' +
      'animation:cgpTipIn .25s ease-out;pointer-events:auto;}' +
      '@keyframes cgpTipIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}' +
      '#' + CARD_ID + ' .tt-head{display:flex;align-items:center;gap:8px;margin-bottom:4px;}' +
      '#' + CARD_ID + ' .tt-tag{font-size:10.5px;font-weight:700;letter-spacing:.4px;text-transform:uppercase;color:#8a5a73;background:#f6edf2;border-radius:6px;padding:2px 7px;}' +
      '#' + CARD_ID + ' .tt-x{margin-left:auto;border:0;background:none;cursor:pointer;color:#9a8f95;font-size:18px;line-height:1;padding:2px 4px;}' +
      '#' + CARD_ID + ' .tt-x:hover{color:#673147;}' +
      '#' + CARD_ID + ' .tt-title{font-weight:700;color:#1e2139;margin-bottom:3px;}' +
      '#' + CARD_ID + ' .tt-text{color:#444;}' +
      '#' + CARD_ID + ' .tt-actions{display:flex;gap:8px;align-items:center;margin-top:9px;flex-wrap:wrap;}' +
      '#' + CARD_ID + ' .tt-ok{border:0;background:#673147;color:#fff;font-weight:600;font-size:12.5px;border-radius:7px;padding:6px 14px;cursor:pointer;}' +
      '#' + CARD_ID + ' .tt-link{color:#673147;font-weight:600;font-size:12.5px;text-decoration:none;}' +
      '#' + CARD_ID + ' .tt-link:hover{text-decoration:underline;}' +
      '@media (max-width:640px){#' + CARD_ID + '{right:8px;left:8px;bottom:calc(8px + env(safe-area-inset-bottom,0px));width:auto;max-width:none;max-height:35vh;overflow:auto;padding:10px 12px 10px 14px;font-size:13px;}}';
    document.head.appendChild(st);
  }

  function show(tip, state) {
    if (blocked()) return;
    injectStyle();
    var card = document.createElement('div');
    card.id = CARD_ID; card.setAttribute('role', 'note'); card.setAttribute('aria-label', 'Tip: ' + tip.title);
    card.setAttribute('data-tip', tip.key);
    card.innerHTML =
      '<div class="tt-head"><span aria-hidden="true">💡</span><span class="tt-tag">Tip</span>' +
      '<button type="button" class="tt-x" aria-label="Close tip">&times;</button></div>' +
      '<div class="tt-title">' + esc(tip.title) + '</div>' +
      '<div class="tt-text">' + esc(tip.text) + '</div>' +
      '<div class="tt-actions"><button type="button" class="tt-ok">Got it</button>' +
      (tip.link ? '<a class="tt-link" href="' + esc(tip.link.href) + '">' + esc(tip.link.label) + ' →</a>' : '') + '</div>';
    var timer = null;
    function remove() { if (timer) clearTimeout(timer); if (card.parentNode) card.parentNode.removeChild(card); document.removeEventListener('keydown', onKey); }
    function dismiss() { remove(); post(tip.key, 'dismissed').then(function () { try { sessionStorage.removeItem(CACHE_KEY); } catch (e) {} }); }
    function onKey(e) { if (e.key === 'Escape' && card.contains(document.activeElement)) dismiss(); }
    card.querySelector('.tt-x').addEventListener('click', dismiss);
    card.querySelector('.tt-ok').addEventListener('click', dismiss);
    if (tip.link) card.querySelector('.tt-link').addEventListener('click', function () { post(tip.key, 'dismissed'); try { sessionStorage.removeItem(CACHE_KEY); } catch (e) {} });
    document.addEventListener('keydown', onKey);
    (document.body || document.documentElement).appendChild(card);          // no focus() — never steals the user's cursor
    timer = setTimeout(remove, AUTO_HIDE_MS);                                 // ignored: hides quietly, may return once on a later day
    post(tip.key, 'shown').then(function () { try { sessionStorage.removeItem(CACHE_KEY); } catch (e) {} });
  }

  function init() {
    loadState().then(function (state) {
      if (!state || !state.eligible) return;
      if (blocked()) return;
      var tip = pick(state);
      if (tip) show(tip, state);
    });
  }

  // Exposed for tests only.
  window.CGP_TRIAL_TIPS = { tips: TIPS, pick: pick, allowed: allowed, matchesPage: matchesPage };

  // Let the page, session.js (permissions/features) and the tour/agreement scripts settle first.
  function start() { setTimeout(init, 2500); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
