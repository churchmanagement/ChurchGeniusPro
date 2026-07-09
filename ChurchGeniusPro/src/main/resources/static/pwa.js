/**
 * pwa.js — Progressive Web App helpers for Church Genius Pro
 *
 * • Registers the service worker (if not already done by session.js)
 * • Captures the beforeinstallprompt event and shows a styled install banner
 * • Injects the install banner HTML/CSS once on first load
 */

(function () {
  'use strict';

  /* ── 1. Service worker registration ─────────────────────────────────────────
   * SW registration is fully owned by session.js (which also wires the
   * update-detection / controllerchange reload cycle).  pwa.js intentionally
   * does NOT register here to avoid race conditions and double-registration.
   * ─────────────────────────────────────────────────────────────────────────── */

  /* ── 2. PWA install banner ───────────────────────────────────────────────── */
  var deferredPrompt = null;

  /* Inject banner styles + markup once */
  function injectBanner() {
    if (document.getElementById('pwaBanner')) return; // already injected

    var style = document.createElement('style');
    style.textContent = [
      '#pwaBanner{',
        'position:fixed;bottom:0;left:0;right:0;z-index:9999;',
        'background:#673147;color:#fff;',
        'display:flex;align-items:center;gap:12px;',
        'padding:14px 16px;',
        'box-shadow:0 -2px 12px rgba(0,0,0,.2);',
        'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;',
        'font-size:14px;',
        'padding-bottom:max(14px,env(safe-area-inset-bottom));',
        'animation:slideUp .3s ease;',
      '}',
      '@keyframes slideUp{from{transform:translateY(100%)}to{transform:translateY(0)}}',
      '#pwaBanner .pwa-icon{font-size:26px;flex-shrink:0;}',
      '#pwaBanner .pwa-text{flex:1;line-height:1.4;}',
      '#pwaBanner .pwa-title{font-weight:700;font-size:14px;}',
      '#pwaBanner .pwa-sub{font-size:12px;opacity:.85;margin-top:2px;}',
      '#pwaBanner .pwa-install{',
        'padding:8px 18px;background:#fff;color:#673147;',
        'border:none;border-radius:7px;font-size:13px;font-weight:700;',
        'cursor:pointer;white-space:nowrap;flex-shrink:0;font-family:inherit;',
      '}',
      '#pwaBanner .pwa-install:hover{background:#fdf5f7;}',
      '#pwaBanner .pwa-dismiss{',
        'background:none;border:none;color:rgba(255,255,255,.7);',
        'font-size:20px;cursor:pointer;padding:2px 6px;flex-shrink:0;line-height:1;',
      '}',
      '#pwaBanner .pwa-dismiss:hover{color:#fff;}'
    ].join('');
    document.head.appendChild(style);

    var banner = document.createElement('div');
    banner.id = 'pwaBanner';
    banner.innerHTML = [
      '<span class="pwa-icon">📱</span>',
      '<div class="pwa-text">',
        '<div class="pwa-title">Install Church Genius Pro</div>',
        '<div class="pwa-sub">Add to your home screen for quick access</div>',
      '</div>',
      '<button class="pwa-install" id="pwaInstallBtn">Install</button>',
      '<button class="pwa-dismiss" id="pwaDismissBtn" aria-label="Dismiss">✕</button>'
    ].join('');

    document.body.appendChild(banner);

    document.getElementById('pwaInstallBtn').addEventListener('click', function () {
      if (deferredPrompt) {
        deferredPrompt.prompt();
        deferredPrompt.userChoice.then(function (choice) {
          if (choice.outcome === 'accepted') {
            localStorage.setItem('cgp_pwa_installed', '1');
          }
          hideBanner();
          deferredPrompt = null;
        });
      }
    });

    document.getElementById('pwaDismissBtn').addEventListener('click', function () {
      /* Snooze for 7 days */
      localStorage.setItem('cgp_pwa_snoozed', String(Date.now() + 7 * 24 * 60 * 60 * 1000));
      hideBanner();
    });
  }

  function hideBanner() {
    var b = document.getElementById('pwaBanner');
    if (b) b.remove();
  }

  function shouldShowBanner() {
    /* Don't show if already installed */
    if (localStorage.getItem('cgp_pwa_installed')) return false;
    /* Don't show if snoozed */
    var snooze = parseInt(localStorage.getItem('cgp_pwa_snoozed') || '0', 10);
    if (snooze && Date.now() < snooze) return false;
    /* Don't show if already running as standalone */
    if (window.matchMedia('(display-mode: standalone)').matches) return false;
    if (window.navigator.standalone === true) return false; // iOS
    return true;
  }

  window.addEventListener('beforeinstallprompt', function (e) {
    e.preventDefault();
    deferredPrompt = e;
    if (shouldShowBanner()) {
      /* Wait for DOM to be ready */
      if (document.body) {
        injectBanner();
      } else {
        document.addEventListener('DOMContentLoaded', injectBanner);
      }
    }
  });

  /* iOS Safari: show a manual "Add to Home Screen" tip since beforeinstallprompt
     is not fired on iOS */
  var isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent);
  var isInStandalone = window.navigator.standalone === true;
  if (isIOS && !isInStandalone && shouldShowBanner()) {
    document.addEventListener('DOMContentLoaded', function () {
      var style = document.createElement('style');
      style.textContent = [
        '#iosInstallTip{',
          'position:fixed;bottom:0;left:0;right:0;z-index:9999;',
          'background:#673147;color:#fff;',
          'padding:14px 16px;padding-bottom:max(14px,env(safe-area-inset-bottom));',
          'text-align:center;font-size:13px;line-height:1.5;',
          'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;',
          'box-shadow:0 -2px 12px rgba(0,0,0,.2);',
          'animation:slideUp .3s ease;',
        '}',
        '#iosInstallTip strong{display:block;font-size:14px;margin-bottom:4px;}',
        '#iosInstallTip .ios-dismiss{',
          'position:absolute;top:12px;right:14px;',
          'background:none;border:none;color:rgba(255,255,255,.7);font-size:20px;cursor:pointer;',
        '}'
      ].join('');
      document.head.appendChild(style);

      var tip = document.createElement('div');
      tip.id  = 'iosInstallTip';
      tip.innerHTML = '<button class="ios-dismiss" onclick="this.parentNode.remove();localStorage.setItem(\'cgp_pwa_snoozed\',String(Date.now()+7*24*60*60*1000))">✕</button>'
        + '<strong>Install Church Genius Pro</strong>'
        + 'Tap the Share button <span style="font-size:16px;">⎋</span> then <em>"Add to Home Screen"</em>';
      document.body.appendChild(tip);
    });
  }

})();
