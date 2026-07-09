/* ============================================================================
 * ntag-session.js — NTAG restricted-access sidebar.
 *
 * Loaded on every authenticated page (via shell.js) but does NOTHING unless the
 * current session is an NTAG-login session. For an NTAG session it REPLACES the
 * sidebar with a single "Permitted Pages" section listing only the pages chosen
 * in the tag's "Allowed Access Pages" — so no unselected module ever appears in
 * the navigation. (Direct-URL access to unselected pages is blocked server-side
 * by NtagAccessFilter, which returns the Access Denied page.)
 *
 * Mirrors temp-session.js's nav builder; the server is the source of truth via
 * /api/ntag-login/session.
 * ========================================================================== */
(function () {
  'use strict';
  if (window.__CGP_NTAG_SESSION__) return; window.__CGP_NTAG_SESSION__ = true;

  var state = { pages: null, holder: '', active: false };

  function escHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* Replace the sidebar with ONLY the permitted pages (correct label + real route). */
  function buildNtagNav(pages) {
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
      '<div class="nav-item" data-ntag-nav="1">' +
        '<button class="nav-item-btn active" onclick="' +
          'var s=this.parentNode.querySelector(\'.nav-submenu\');' +
          'var a=this.querySelector(\'.nav-arrow\');' +
          'if(s)s.classList.toggle(\'open\');if(a)a.classList.toggle(\'open\');">' +
          '<span class="nav-icon">&#x1F510;</span>' +
          '<span class="nav-label">Permitted Pages</span>' +
          '<span class="nav-arrow open" id="arrow-ntag-pages">&#x276F;</span>' +
        '</button>' +
        '<div class="nav-submenu open" id="sub-ntag-pages">' + subs + '</div>' +
      '</div>';
  }

  function applyNavFilter() {
    if (!state.pages) return;
    buildNtagNav(state.pages);
    setTimeout(function () { buildNtagNav(state.pages); }, 700);   // catch late/async nav render
    setTimeout(function () { buildNtagNav(state.pages); }, 1800);
  }

  /* Hide quick-access surfaces that could surface non-permitted pages:
     favorites stars, the global AI assistant launcher, and any landing-page
     "quick link" cards. The permitted-pages sidebar is the only navigation. */
  function hideExtraSurfaces() {
    var css = document.getElementById('ntag-restrict-css');
    if (css) return;
    css = document.createElement('style');
    css.id = 'ntag-restrict-css';
    css.textContent =
      '.cgp-fav,.cgp-inline-fav,#cgp-fav-section,[data-fav-toggle]{display:none !important;}' +
      '#aiAssistantLauncher,#aa-launcher,.ai-assistant-launcher{display:none !important;}';
    document.head.appendChild(css);
    document.documentElement.classList.add('cgp-ntag-restricted');
  }

  function banner(holder) {
    if (document.getElementById('ntagSessionBanner')) return;
    var b = document.createElement('div');
    b.id = 'ntagSessionBanner';
    b.style.cssText =
      'position:fixed;top:0;left:0;right:0;z-index:99999;display:flex;align-items:center;' +
      'justify-content:center;gap:14px;padding:7px 14px;font:600 13.5px/1.3 Arial,sans-serif;' +
      'background:#4d2435;color:#fff;box-shadow:0 1px 4px rgba(0,0,0,.2);';
    b.innerHTML =
      '<span>&#x1F510; Restricted access' + (holder ? (' — ' + escHtml(holder)) : '') + '</span>' +
      '<button id="ntagBannerOut" type="button" style="border:1px solid rgba(255,255,255,.6);background:transparent;' +
      'color:#fff;border-radius:6px;padding:3px 10px;font-size:12px;cursor:pointer;">Log out</button>';
    document.body.appendChild(b);
    document.body.style.paddingTop =
      ((parseInt(getComputedStyle(document.body).paddingTop, 10) || 0) + 36) + 'px';
    document.getElementById('ntagBannerOut').addEventListener('click', function () {
      fetch('/api/logout', { method: 'POST' }).catch(function () {})
        .then(function () { window.location.href = '/private-access'; });
    });
  }

  function sync() {
    fetch('/api/ntag-login/session', { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.ntag) return;                 // not an NTAG session → stay dormant
        state.active = true;
        state.holder = d.holderName || '';
        state.pages  = d.permittedPages || state.pages;
        hideExtraSurfaces();
        banner(state.holder);
        applyNavFilter();                          // rebuild the sidebar to the permitted pages
      })
      .catch(function () {});
  }

  function start() { sync(); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
