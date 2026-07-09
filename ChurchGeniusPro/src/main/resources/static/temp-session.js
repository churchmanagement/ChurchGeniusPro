/* ============================================================================
 * temp-session.js — Temporary Access countdown banner.
 *
 * Loaded on every authenticated page (via shell.js) but does NOTHING unless the
 * current session is a temporary-access session. For a temp session it shows a
 * fixed banner with the remaining time, turns amber and warns once at 15 minutes
 * remaining, and redirects to the login screen the moment access ends.
 *
 * The server is the source of truth for the deadline (so an admin "extend" is
 * reflected automatically): we poll /api/temp-access/session periodically and the
 * local clock just animates the seconds in between.
 * ========================================================================== */
(function () {
  'use strict';
  if (window.__CGP_TEMP_SESSION__) return; window.__CGP_TEMP_SESSION__ = true;

  var WARN_DEFAULT = 15 * 60;     // warn at 15 minutes remaining
  var POLL_MS      = 30000;       // re-sync with the server every 30s
  var state = { remaining: 0, warnAt: WARN_DEFAULT, warned: false, holder: '', active: false, ticker: null, routes: null, pages: null };

  function escHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* ── Build the temp user's sidebar from the EXACT permitted pages ──
     The standard menu uses different hrefs/aliases and omits several of these
     pages, so filtering it can only ever surface a subset. Instead we replace the
     sidebar nav with a single "Permitted Pages" section listing every granted page
     (correct label + real route), so all selected pages are visible & clickable.
     Re-applied on each sync and a couple of delayed passes so it survives the
     async role-based re-render from session.js. */
  function buildTempNav(pages) {
    var nav = document.getElementById('sidebarNav');
    if (!nav || !pages || !pages.length) return;

    var here = window.location.pathname;
    var subs = pages.map(function (p) {
      var route = p.route || '';
      var active = (route && (here === route || here.indexOf(route) === 0)) ? ' active' : '';
      return '<button class="nav-sub-btn' + active + '" onclick="window.location.href=\'' + escHtml(route) + '\'">' +
               '<span class="sub-icon">&#x2022;</span>' + escHtml(p.label) +
             '</button>';
    }).join('');

    nav.innerHTML =
      '<div class="nav-item" data-temp-nav="1">' +
        '<button class="nav-item-btn active" onclick="' +
          'var s=this.parentNode.querySelector(\'.nav-submenu\');' +
          'var a=this.querySelector(\'.nav-arrow\');' +
          'if(s)s.classList.toggle(\'open\');if(a)a.classList.toggle(\'open\');">' +
          '<span class="nav-icon">&#x1F510;</span>' +
          '<span class="nav-label">Permitted Pages</span>' +
          '<span class="nav-arrow open" id="arrow-temp-pages">&#x276F;</span>' +
        '</button>' +
        '<div class="nav-submenu open" id="sub-temp-pages">' + subs + '</div>' +
      '</div>';
  }
  function applyNavFilter() {
    if (!state.pages) return;
    buildTempNav(state.pages);
    setTimeout(function () { buildTempNav(state.pages); }, 700);   // catch late/async nav render
    setTimeout(function () { buildTempNav(state.pages); }, 1800);
  }

  function fmt(s) {
    s = Math.max(0, Math.floor(s));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return (h > 0 ? (h + ':' + p(m)) : ('' + m)) + ':' + p(sec);
  }

  function ensureBanner() {
    var b = document.getElementById('tempSessionBanner');
    if (b) return b;
    b = document.createElement('div');
    b.id = 'tempSessionBanner';
    b.style.cssText =
      'position:fixed;top:0;left:0;right:0;z-index:99999;display:flex;align-items:center;' +
      'justify-content:center;gap:14px;padding:7px 14px;font:600 13.5px/1.3 Arial,sans-serif;' +
      'background:#673147;color:#fff;box-shadow:0 1px 4px rgba(0,0,0,.2);';
    b.innerHTML =
      '<span id="tempBannerText">Temporary access</span>' +
      '<span id="tempBannerTime" style="font-variant-numeric:tabular-nums;background:rgba(255,255,255,.16);' +
      'border-radius:6px;padding:2px 10px;">--:--</span>' +
      '<button id="tempBannerOut" type="button" style="border:1px solid rgba(255,255,255,.6);background:transparent;' +
      'color:#fff;border-radius:6px;padding:3px 10px;font-size:12px;cursor:pointer;">End session</button>';
    document.body.appendChild(b);
    // Nudge the page content down so the fixed banner doesn't cover it.
    document.body.style.paddingTop =
      ((parseInt(getComputedStyle(document.body).paddingTop, 10) || 0) + 36) + 'px';
    document.getElementById('tempBannerOut').addEventListener('click', endSession);
    return b;
  }

  function paint() {
    var b = ensureBanner();
    var t = document.getElementById('tempBannerTime');
    var txt = document.getElementById('tempBannerText');
    if (t) t.textContent = fmt(state.remaining);
    if (txt) txt.textContent = 'Temporary access' + (state.holder ? (' — ' + state.holder) : '');
    var warning = state.remaining <= state.warnAt;
    b.style.background = warning ? '#b26a00' : '#673147';
    if (warning && !state.warned) {
      state.warned = true;
      try { toastWarn('Less than ' + Math.ceil(state.warnAt / 60) + ' minutes of access remaining.'); } catch (e) {}
    }
    if (state.remaining > state.warnAt) state.warned = false;   // reset if an admin extended
  }

  function toastWarn(msg) {
    var el = document.createElement('div');
    el.textContent = msg;
    el.style.cssText =
      'position:fixed;top:44px;left:50%;transform:translateX(-50%);z-index:99999;background:#b26a00;' +
      'color:#fff;padding:9px 16px;border-radius:8px;font:600 13px Arial;box-shadow:0 2px 8px rgba(0,0,0,.25);';
    document.body.appendChild(el);
    setTimeout(function () { el.remove(); }, 6000);
  }

  function expire() {
    if (state.ticker) clearInterval(state.ticker);
    window.location.href = '/private-access?reason=expired';
  }

  function endSession() {
    fetch('/api/temp-access/logout', { method: 'POST' })
      .catch(function () {})
      .then(function () { window.location.href = '/private-access'; });
  }

  function tick() {
    state.remaining -= 1;
    if (state.remaining <= 0) { expire(); return; }
    paint();
  }

  function sync() {
    fetch('/api/temp-access/session', { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.temporary) { return; }                 // not a temp session → stay dormant
        if (!d.active) { expire(); return; }
        state.active = true;
        state.remaining = d.remainingSeconds || 0;
        state.warnAt    = d.warnSeconds || WARN_DEFAULT;
        state.holder    = d.holderName || '';
        state.routes    = d.permittedRoutes || state.routes;
        state.pages     = d.permittedPages || state.pages;
        paint();
        applyNavFilter();           // rebuild the sidebar to the permitted pages
        if (!state.ticker) state.ticker = setInterval(tick, 1000);
      })
      .catch(function () {});
  }

  function start() { sync(); setInterval(sync, POLL_MS); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
