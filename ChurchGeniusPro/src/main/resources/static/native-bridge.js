/**
 * native-bridge.js — the ONE place the web application knows it may be running
 * inside the ChurchGeniusPro mobile app (Capacitor shell, Mobile Phase 4).
 *
 * Loaded on every page (shell.js injects it; memberHome.html and login.html carry
 * an explicit tag). In a normal browser `window.Capacitor` is undefined and this
 * file does NOTHING — every branch below is behind `isNative`.
 *
 * Inside the app it fixes the four things a WebView cannot do like a browser:
 *   1. DOWNLOADS  — jsPDF `doc.save()`, SheetJS `XLSX.writeFile()`, any
 *                   `<a download href="blob:|data:">` → the file is written to the
 *                   app cache and the native Share sheet opens (Save to Files /
 *                   Drive / Print / Mail). One capture-phase click listener.
 *   2. NEW WINDOWS — `window.open(url)` → same-site PDFs/reports and other
 *                   origins (YouTube, Stripe, Plaid, maps) open in the system
 *                   browser (@capacitor/browser); `window.open('')` + document.write
 *                   (the print-preview pattern) → a stand-in window whose
 *                   `.print()` hands the HTML to the native Printer plugin.
 *   3. PRINT      — `window.print()` → native print dialog (Printer plugin).
 *   4. WEB PWA    — no service worker, install banner or Web Push inside the app.
 *   5. PUSH       — (Phase 5) the OS push token (@capacitor/push-notifications,
 *                   FCM on both platforms) is registered at
 *                   POST /api/push/register-native for the signed-in user, handed
 *                   to the existing logout so a shared phone stops getting the
 *                   previous user's notifications, and a tapped notification
 *                   opens its `url`. session.js / memberHome call
 *                   window.CGP_NATIVE.registerPush() where they would have
 *                   subscribed to Web Push.
 *   9. UPDATES    — when the app returns to the foreground after a deploy, the
 *                   page reloads (or, if the user has typed something, offers a
 *                   "Reload" bar) so the WebView never keeps running old scripts.
 *
 * Nothing here touches sessions, permissions or data: those stay the server's.
 */
(function () {
  'use strict';

  var cap = window.Capacitor;
  var isNative = !!(cap && typeof cap.isNativePlatform === 'function' ? cap.isNativePlatform() : (cap && cap.isNative));
  // Public, read-only facts for pages that want to adapt (e.g. hide "Install app").
  window.CGP_NATIVE = {
    isNative: isNative,
    platform: isNative && typeof cap.getPlatform === 'function' ? cap.getPlatform() : 'web'
  };
  if (!isNative) return;

  var P = cap.Plugins || {};
  var log = function () { try { console.log.apply(console, ['[native-bridge]'].concat([].slice.call(arguments))); } catch (_) {} };
  var toast = function (msg) {
    try {
      var t = document.createElement('div');
      t.textContent = msg;
      t.style.cssText = 'position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#333;color:#fff;padding:10px 16px;border-radius:10px;font-size:14px;z-index:100000;max-width:90vw;text-align:center;';
      document.body.appendChild(t);
      setTimeout(function () { t.remove(); }, 2600);
    } catch (_) {}
  };

  /* ── helpers ─────────────────────────────────────────────────────────── */

  function sameOrigin(url) {
    try { return new URL(url, location.href).origin === location.origin; } catch (_) { return false; }
  }

  function safeName(name, fallback) {
    var n = String(name || fallback || 'download').replace(/[\\/:*?"<>|]+/g, '-').trim();
    return n || (fallback || 'download');
  }

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { resolve(String(r.result).split(',')[1] || ''); };
      r.onerror = function () { reject(r.error); };
      r.readAsDataURL(blob);
    });
  }

  /**
   * Writes a blob to the app cache and opens the native Share sheet — the
   * mobile equivalent of a browser download. Resolves true when handled.
   */
  function shareBlob(blob, filename) {
    if (!P.Filesystem || !P.Share) return Promise.resolve(false);
    var name = safeName(filename, 'download');
    return blobToBase64(blob).then(function (b64) {
      return P.Filesystem.writeFile({ path: 'cgp-exports/' + name, data: b64, directory: 'CACHE', recursive: true });
    }).then(function (res) {
      return P.Share.share({ title: name, files: [res.uri], dialogTitle: 'Save or share ' + name });
    }).then(function () { return true; }).catch(function (err) {
      // User cancelled the sheet or the write failed: cancellation is normal, anything else is reported.
      if (err && /cancel/i.test(String(err.message || err))) return true;
      log('share failed', err);
      toast('Could not save the file on this device.');
      return true;
    });
  }
  window.CGP_shareBlob = shareBlob;   // pages may call this directly

  function urlToBlob(url) {
    return fetch(url).then(function (r) { return r.blob(); });
  }

  /* ── 1. downloads ────────────────────────────────────────────────────── */

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (!/^(blob:|data:)/i.test(href) && !(sameOrigin(href) && a.hasAttribute('download'))) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    var name = a.getAttribute('download') || (href.split('/').pop() || 'download').split('?')[0];
    urlToBlob(href).then(function (blob) { return shareBlob(blob, name); })
      .catch(function (err) { log('download failed', err); toast('Could not prepare the file.'); });
  }, true);

  /* ── 2. window.open ──────────────────────────────────────────────────── */

  function openExternal(url) {
    if (P.Browser && /^https?:/i.test(url)) {
      P.Browser.open({ url: url, presentationStyle: 'popover' }).catch(function () { location.href = url; });
    } else if (P.App && typeof P.App.openUrl === 'function') {
      P.App.openUrl({ url: url }).catch(function () {});
    } else {
      location.href = url;
    }
  }

  /** Stand-in for `w = window.open(''); w.document.write(html); w.print()`. */
  function virtualWindow() {
    var html = '';
    var closed = false;
    var vdoc = {
      write: function (s) { html += String(s); },
      writeln: function (s) { html += String(s) + '\n'; },
      open: function () { html = ''; },
      close: function () {},
      get title() { var m = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html); return m ? m[1] : ''; },
      set title(v) {},
      body: { innerHTML: '' },
      head: { innerHTML: '' }
    };
    var w = {
      document: vdoc,
      closed: false,
      location: { href: 'about:blank' },
      focus: function () {},
      blur: function () {},
      close: function () { closed = true; w.closed = true; },
      print: function () { printHtml(html || '<html><body></body></html>'); },
      addEventListener: function (type, fn) { if (type === 'load') setTimeout(fn, 0); },
      onload: null
    };
    // Pages that write a full document and rely on the document's own onload to
    // print will have called w.print() inside that HTML; honour it once.
    setTimeout(function () {
      if (!closed && /window\.print\s*\(/.test(html) && !w.__printed) { w.__printed = true; w.print(); }
    }, 300);
    return w;
  }

  var nativeOpen = window.open;
  window.open = function (url, target, features) {
    var u = url == null ? '' : String(url).trim();
    if (!u || u === 'about:blank') return virtualWindow();
    if (/^(blob:|data:)/i.test(u)) {
      // A generated PDF/image shown in a new tab on the web → share sheet here.
      var n = 'document' + (/pdf/i.test(u) ? '.pdf' : '');
      urlToBlob(u).then(function (b) { return shareBlob(b, n); });
      return virtualWindow();
    }
    var abs; try { abs = new URL(u, location.href).href; } catch (_) { abs = u; }
    if (sameOrigin(abs) && !/\.(pdf|csv|xlsx?|docx?)(\?|$)/i.test(abs) && target !== '_blank') {
      // Same-site page in the same window: ordinary navigation.
      location.href = abs;
      return virtualWindow();
    }
    openExternal(abs);
    return virtualWindow();
  };
  window.__cgpNativeOpen = nativeOpen;

  // Links that leave the site: opened by the system browser rather than inside
  // the WebView (Capacitor would block them; mailto:/tel:/sms: are handled natively).
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a || a.hasAttribute('download')) return;
    var href = a.getAttribute('href') || '';
    if (/^(#|javascript:|mailto:|tel:|sms:|geo:)/i.test(href)) return;
    var abs; try { abs = new URL(href, location.href).href; } catch (_) { return; }
    if (!/^https?:/i.test(abs)) return;
    if (sameOrigin(abs)) {
      if (a.target === '_blank' && /\.(pdf|csv|xlsx?|docx?)(\?|$)/i.test(abs)) {
        e.preventDefault(); urlToBlob(abs).then(function (b) { return shareBlob(b, abs.split('/').pop().split('?')[0]); });
      }
      return;
    }
    e.preventDefault();
    openExternal(abs);
  }, true);

  /* ── 3. print ────────────────────────────────────────────────────────── */

  function printHtml(html) {
    if (P.Printer && typeof P.Printer.printHtml === 'function') {
      P.Printer.printHtml({ html: html, name: document.title || 'ChurchGeniusPro' }).catch(function (err) { log('printHtml failed', err); toast('Printing is not available on this device.'); });
    } else {
      // No printer plugin: share the HTML as a file so it can still be printed elsewhere.
      shareBlob(new Blob([html], { type: 'text/html' }), (document.title || 'page') + '.html');
    }
  }
  window.print = function () {
    if (P.Printer && typeof P.Printer.print === 'function') {
      P.Printer.print({ name: document.title || 'ChurchGeniusPro' }).catch(function (err) { log('print failed', err); toast('Printing is not available on this device.'); });
    } else {
      printHtml('<!doctype html><html><head><meta charset="utf-8"><title>' + (document.title || '') + '</title></head><body>' + document.body.innerHTML + '</body></html>');
    }
  };
  window.CGP_printHtml = printHtml;

  /* ── 4. no web-PWA machinery inside the app ──────────────────────────── */

  try {
    if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
      navigator.serviceWorker.getRegistrations().then(function (regs) { regs.forEach(function (r) { r.unregister(); }); }).catch(function () {});
    }
  } catch (_) {}
  // session.js / pwa.js check this flag before registering the SW, the install
  // banner or Web Push.
  window.CGP_SKIP_WEB_PWA = true;
  document.documentElement.classList.add('cgp-native', 'cgp-native-' + window.CGP_NATIVE.platform);

  /* ── 5. native push (Mobile Phase 5) ─────────────────────────────────── */

  var PUSH_TOKEN_KEY = 'cgp_native_push_token';     // sessionStorage: survives page loads, not app restarts
  var PUSH_SYNCED_KEY = 'cgp_native_push_synced';    // sessionStorage: user the current token was posted for
  var pushToken = null;
  try { pushToken = sessionStorage.getItem(PUSH_TOKEN_KEY) || null; } catch (_) {}
  var pushListenersAttached = false;

  function currentUserKey() { try { return localStorage.getItem('clientId') || ''; } catch (_) { return ''; } }

  /** POSTs the token for the signed-in user — once per user per token. */
  function postPushToken(token) {
    if (!token) return Promise.resolve(false);
    var who = currentUserKey();
    var synced = null;
    try { synced = sessionStorage.getItem(PUSH_SYNCED_KEY); } catch (_) {}
    if (synced === who + '|' + token) return Promise.resolve(true);
    return fetch('/api/push/register-native', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ token: token, platform: window.CGP_NATIVE.platform, deviceName: deviceLabel() })
    }).then(function (r) {
      if (r.ok) { try { sessionStorage.setItem(PUSH_SYNCED_KEY, who + '|' + token); } catch (_) {} }
      return r.ok;
    }).catch(function () { return false; });
  }

  function deviceLabel() {
    var ua = navigator.userAgent || '';
    var m = /Android [\d.]+; ([^;)]+)/.exec(ua);
    if (m) return m[1].replace(/\s+Build\/.*$/i, '').trim();   // "Pixel 8 Build/UQ1A" → "Pixel 8"
    if (/iPad/.test(ua)) return 'iPad';
    if (/iPhone/.test(ua)) return 'iPhone';
    return window.CGP_NATIVE.platform;
  }

  function attachPushListeners() {
    if (pushListenersAttached || !P.PushNotifications) return;
    pushListenersAttached = true;
    P.PushNotifications.addListener('registration', function (t) {
      pushToken = t && t.value ? t.value : null;
      try { if (pushToken) sessionStorage.setItem(PUSH_TOKEN_KEY, pushToken); } catch (_) {}
      postPushToken(pushToken);
    });
    P.PushNotifications.addListener('registrationError', function (err) { log('push registration error', err); });
    // Foreground arrival: refresh the bell/badge if the page has one, don't interrupt.
    P.PushNotifications.addListener('pushNotificationReceived', function () {
      try { if (typeof window.pollUnreadCount === 'function') window.pollUnreadCount(); } catch (_) {}
      try { if (typeof window.refreshNotificationBadge === 'function') window.refreshNotificationBadge(); } catch (_) {}
    });
    // Tap: open the page the notification points at (same-site only).
    P.PushNotifications.addListener('pushNotificationActionPerformed', function (ev) {
      var data = (ev && ev.notification && ev.notification.data) || {};
      var url = data.url || '/';
      if (sameOrigin(url) || /^\//.test(url)) location.href = url;
    });
  }

  /**
   * Called by session.js / memberHome once the user is signed in. Asks for the OS
   * permission (only once; the OS remembers), registers with FCM/APNs and posts
   * the token. Resolves true when a token was posted (or was already current).
   */
  function registerPush() {
    if (!P.PushNotifications) { log('push plugin missing'); return Promise.resolve(false); }
    attachPushListeners();
    return P.PushNotifications.checkPermissions().then(function (st) {
      if (st && st.receive === 'granted') return st;
      if (st && st.receive === 'denied') return st;
      return P.PushNotifications.requestPermissions();
    }).then(function (st) {
      if (!st || st.receive !== 'granted') { log('push permission', st && st.receive); return false; }
      // Token already in hand (same app session, new page): just make sure it is keyed to this user.
      if (pushToken) return postPushToken(pushToken);
      return P.PushNotifications.register().then(function () { return true; });
    }).catch(function (err) { log('registerPush failed', err); return false; });
  }

  /** Token for logout — the server deactivates its row like a browser endpoint. */
  function getPushToken() { return pushToken; }

  function forgetPushSync() { try { sessionStorage.removeItem(PUSH_SYNCED_KEY); } catch (_) {} }

  window.CGP_NATIVE.registerPush   = registerPush;
  window.CGP_NATIVE.getPushToken   = getPushToken;
  window.CGP_NATIVE.forgetPushSync = forgetPushSync;

  /* ── 6. small ergonomics ─────────────────────────────────────────────── */

  // Android hardware back: go back in history, or ask before leaving the app
  // from the first page. Capacitor's default would exit silently.
  if (P.App && typeof P.App.addListener === 'function') {
    try {
      P.App.addListener('backButton', function (ev) {
        if (ev && ev.canGoBack) { history.back(); }
        else if (/\/(login\.html)?$|\/memberHome|\/home$/.test(location.pathname) && P.App.exitApp) { P.App.exitApp(); }
        else { history.back(); }
      });
    } catch (_) {}
  }

  /* ── 7. status-bar icons ─────────────────────────────────────────────── */
  // The WebView is edge-to-edge (viewport-fit=cover): mobile.css draws a fixed
  // brand-plum strip (body::before) under the status bar, the same bar the
  // installed PWA gets from theme-color. Plum needs light icons, on every page.
  // A plain browser has no StatusBar plugin and skips this.
  function syncStatusBarStyle() {
    if (!P.StatusBar || typeof P.StatusBar.setStyle !== 'function') return;
    // Capacitor: Style.Dark = light icons for a dark background.
    try { P.StatusBar.setStyle({ style: 'DARK' }); } catch (_) {}
  }
  syncStatusBarStyle();
  window.CGP_NATIVE.syncStatusBarStyle = syncStatusBarStyle;

  /* ── 9. pick up a new deployment when the app comes back to the foreground ──
   *
   * In a browser the service worker polls /api/app-version and reloads open tabs
   * after a deploy (sw.js + session.js). The app has no service worker (section 4),
   * and Android keeps the WebView alive in the background, so a page opened before
   * a deploy kept running the old scripts until the next navigation.
   *
   * Here: the version the server reported when this page loaded is remembered; when
   * the app returns to the foreground (App 'resume', or the page becoming visible)
   * the version is asked again — at most once a minute — and if it changed:
   *   - nothing typed on this page yet  → the page reloads (HTML is no-store and the
   *     scripts are no-cache, so the reload fetches the new code);
   *   - something typed or chosen       → nothing is lost: a small bar offers "Reload".
   * At most one reload per version (remembered in sessionStorage), so a server answering
   * with two different versions (two instances during a slot swap) can never loop.
   * Signed-out pages (the endpoint answers 401) and network errors do nothing.
   */
  var VERSION_RELOADED_KEY = 'cgp_native_reloaded_for';
  var VERSION_CHECK_MIN_MS = 60 * 1000;
  var pageVersion = null, lastVersionCheck = 0, pageDirty = false, updateBarShown = false;

  // XMLHttpRequest on purpose: session.js wraps window.fetch so that any 401 from
  // /api/* logs the user out. A version check must never do that by itself — an
  // expired session is handled by the page's own next request, exactly as before.
  function fetchAppVersion() {
    return new Promise(function (resolve) {
      try {
        var x = new XMLHttpRequest();
        x.open('GET', '/api/app-version?t=' + Date.now(), true);
        x.setRequestHeader('Accept', 'application/json');
        x.timeout = 8000;
        x.onload = function () {
          if (x.status !== 200) return resolve(null);
          try { var j = JSON.parse(x.responseText); resolve(j && j.version ? String(j.version) : null); }
          catch (_) { resolve(null); }
        };
        x.onerror = x.ontimeout = function () { resolve(null); };
        x.send();
      } catch (_) { resolve(null); }
    });
  }
  // Anything the user typed or picked on this page makes an automatic reload unsafe.
  document.addEventListener('input', function () { pageDirty = true; }, true);
  document.addEventListener('change', function () { pageDirty = true; }, true);

  function showUpdateBar() {
    if (updateBarShown || !document.body) return;
    updateBarShown = true;
    var bar = document.createElement('div');
    bar.id = 'cgpUpdateBar';
    bar.setAttribute('role', 'status');
    bar.style.cssText = 'position:fixed;left:12px;right:12px;bottom:calc(12px + env(safe-area-inset-bottom,0px));z-index:2147483646;'
      + 'display:flex;align-items:center;gap:10px;background:#673147;color:#fff;border-radius:12px;padding:10px 12px;'
      + 'box-shadow:0 6px 20px rgba(0,0,0,.25);font:14px/1.35 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;';
    var text = document.createElement('span');
    text.style.flex = '1';
    text.textContent = 'A new version of ChurchGeniusPro is available.';
    var reload = document.createElement('button');
    reload.type = 'button';
    reload.textContent = 'Reload';
    reload.style.cssText = 'border:none;background:#fff;color:#673147;font-weight:700;border-radius:8px;padding:7px 14px;';
    reload.addEventListener('click', function () { location.reload(); });
    var later = document.createElement('button');
    later.type = 'button';
    later.setAttribute('aria-label', 'Dismiss');
    later.textContent = '✕';
    later.style.cssText = 'border:none;background:transparent;color:#fff;font-size:16px;padding:4px 6px;';
    later.addEventListener('click', function () { bar.remove(); });
    bar.appendChild(text); bar.appendChild(reload); bar.appendChild(later);
    document.body.appendChild(bar);
  }

  function checkForNewVersion() {
    var now = Date.now();
    if (pageVersion == null || now - lastVersionCheck < VERSION_CHECK_MIN_MS) return;
    lastVersionCheck = now;
    fetchAppVersion().then(function (v) {
      if (!v || v === pageVersion) return;
      // Every version this app session has already reloaded for (newest last, at most 5):
      // two instances answering alternately can each cause one reload, never a loop.
      var seen = [];
      try { seen = JSON.parse(sessionStorage.getItem(VERSION_RELOADED_KEY) || '[]'); } catch (_) { seen = []; }
      if (!Array.isArray(seen)) seen = [];
      if (seen.indexOf(v) >= 0) return;
      seen.push(v); if (seen.length > 5) seen = seen.slice(-5);
      try { sessionStorage.setItem(VERSION_RELOADED_KEY, JSON.stringify(seen)); } catch (_) {}
      log('new deployment', pageVersion, '→', v, pageDirty ? '(offering reload)' : '(reloading)');
      if (pageDirty) showUpdateBar(); else location.reload();
    });
  }

  fetchAppVersion().then(function (v) { pageVersion = v; lastVersionCheck = Date.now(); });
  if (P.App && typeof P.App.addListener === 'function') {
    try { P.App.addListener('resume', checkForNewVersion); } catch (_) {}
  }
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') checkForNewVersion();
  });
  window.CGP_NATIVE.checkForNewVersion = function () { lastVersionCheck = 0; checkForNewVersion(); };

  /* ── 8. biometric sign-in with a device-bound secret (Mobile Phase 6) ─── */
  // The app never stores a password. When Remember me is ticked in the app,
  // /login answers with a one-time `deviceToken` instead of a cookie; it lives
  // in the OS secure store (iOS Keychain, kSecAttrAccessibleWhenUnlockedThisDeviceOnly;
  // Android Keystore-encrypted preferences) and is only read after the OS
  // biometric / device-credential prompt succeeds. /api/auth/device-login
  // rotates it on every use and refuses revoked ones, in which case the copy
  // here is deleted. Plugins: @aparajita/capacitor-secure-storage (native
  // name "SecureStorage") and @aparajita/capacitor-biometric-auth
  // ("BiometricAuthNative"), both reached through Capacitor.Plugins like push.
  var DEVICE_TOKEN_KEY = 'cgp_device_token';
  var KEYCHAIN_WHEN_UNLOCKED_THIS_DEVICE_ONLY = 1;   // KeychainAccess enum of the plugin

  function hasSecureStorage() { return !!(P.SecureStorage && typeof P.SecureStorage.internalGetItem === 'function'); }
  function hasBiometry()      { return !!(P.BiometricAuthNative && typeof P.BiometricAuthNative.internalAuthenticate === 'function'); }

  function readDeviceToken() {
    if (!hasSecureStorage()) return Promise.resolve(null);
    return P.SecureStorage.internalGetItem({ prefixedKey: DEVICE_TOKEN_KEY, sync: false })
      .then(function (r) { return r && typeof r.data === 'string' && r.data ? r.data : null; })
      .catch(function () { return null; });
  }
  function writeDeviceToken(token) {
    if (!hasSecureStorage() || !token) return Promise.resolve(false);
    return P.SecureStorage.internalSetItem({ prefixedKey: DEVICE_TOKEN_KEY, data: token, sync: false,
                                             access: KEYCHAIN_WHEN_UNLOCKED_THIS_DEVICE_ONLY })
      .then(function () { return true; }).catch(function (e) { log('secure store write failed', e && e.message); return false; });
  }
  function clearDeviceToken() {
    if (!hasSecureStorage()) return Promise.resolve();
    return P.SecureStorage.internalRemoveItem({ prefixedKey: DEVICE_TOKEN_KEY, sync: false }).catch(function () {});
  }

  /** Can this device protect a secret? (biometrics enrolled, or at least a passcode/PIN). */
  function deviceCanUnlock() {
    if (!hasBiometry()) return Promise.resolve(false);
    return P.BiometricAuthNative.checkBiometry()
      .then(function (b) { return !!(b && (b.isAvailable || b.deviceIsSecure)); })
      .catch(function () { return false; });
  }

  /**
   * login.html calls this with the deviceToken from /login. Stored only when
   * the OS can gate it; otherwise the user simply signs in with the password
   * next time (nothing is kept).
   */
  function storeDeviceToken(token) {
    return deviceCanUnlock().then(function (ok) {
      if (!ok) { log('device has no biometrics/passcode — not storing a device token'); return false; }
      return writeDeviceToken(token);
    });
  }

  /** Prompts the OS (biometrics, or device PIN/pattern as fallback). Resolves true/false; never throws. */
  function promptUnlock(reason) {
    if (!hasBiometry()) return Promise.resolve(false);
    return P.BiometricAuthNative.internalAuthenticate({
      reason: reason || 'Sign in to ChurchGeniusPro',
      cancelTitle: 'Use password',
      allowDeviceCredential: true,
      androidTitle: 'ChurchGeniusPro',
      androidSubtitle: 'Unlock to sign in',
      androidConfirmationRequired: false,
      iosFallbackTitle: 'Use passcode'
    }).then(function () { return true; }).catch(function (e) { log('unlock cancelled/failed', e && (e.code || e.message)); return false; });
  }

  /**
   * The whole flow for login.html: unlock → device-login → store the rotated
   * token. Resolves the login payload on success, or null (the page then
   * shows the password form). A token the server no longer accepts is deleted.
   */
  function biometricLogin() {
    return readDeviceToken().then(function (token) {
      if (!token) return null;
      return promptUnlock().then(function (ok) {
        if (!ok) return null;
        return fetch('/api/auth/device-login', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ token: token })
        }).then(function (r) {
          // A throttled (429) or failed answer is not a verdict on the token: keep it.
          return r.ok ? r.json() : null;
        }).then(function (data) {
          if (data && data.status === 'success') {
            return (data.deviceToken ? writeDeviceToken(data.deviceToken) : Promise.resolve()).then(function () { return data; });
          }
          if (data && data.status === 'none') clearDeviceToken();   // revoked / expired / refused
          return null;
        }).catch(function () { return null; });   // offline: keep the token, show the form
      });
    });
  }

  window.CGP_NATIVE.storeDeviceToken  = storeDeviceToken;
  window.CGP_NATIVE.getDeviceToken    = readDeviceToken;      // devices.js, logout bodies
  window.CGP_NATIVE.hasDeviceToken    = function () { return readDeviceToken().then(function (t) { return !!t; }); };
  window.CGP_NATIVE.clearDeviceToken  = clearDeviceToken;
  window.CGP_NATIVE.biometricLogin    = biometricLogin;
  window.CGP_NATIVE.deviceName        = deviceLabel();        // /login body → remember_me_token.device_label
  // Only an app build that ships the two plugins AND a device that can gate the
  // secret may receive a device token; otherwise /login falls back to the Phase 1
  // remember cookie (what the 1.0 app without these plugins relies on).
  window.CGP_NATIVE.deviceStore       = false;
  if (hasSecureStorage()) deviceCanUnlock().then(function (ok) { window.CGP_NATIVE.deviceStore = !!ok; });

  // login.html's hidden #biometricBtn dispatches this; the page handles the payload.
  document.addEventListener('cgp:native-biometric-login', function () {
    biometricLogin().then(function (data) {
      document.dispatchEvent(new CustomEvent('cgp:native-biometric-result', { detail: data }));
    });
  });

  log('active on', window.CGP_NATIVE.platform);
  // session.js may have run before this file (shell.js injects it deferred): let it know.
  try { document.dispatchEvent(new CustomEvent('cgp:native-ready')); } catch (_) {}
})();
