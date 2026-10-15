/* devices.js — "Signed-in devices" (Mobile Phase 6)
 *
 * One widget, two homes: a modal opened from the staff notification bell
 * (session.js) and a card inside the member portal's Settings dialog
 * (memberHome.html). Lists the remembered browsers and app installs of the
 * signed-in login (remember_me_token rows) and lets the person sign one out
 * or "sign out of all other devices". A revoked device has to sign in with
 * its password next time; its current session is not cut short.
 *
 * Browser and app share this file. In the app shell, native-bridge.js exposes
 * the device secret so this device can be marked "this device" and kept by
 * "sign out of others"; in a browser the remember cookie does that server-side.
 */
(function () {
  'use strict';
  if (window.CGP_DEVICES) return;

  var CSS = '.cgp-dev-list{margin:0;padding:0;list-style:none;}' +
    '.cgp-dev-row{display:flex;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid #f0e4e8;font-size:13px;}' +
    '.cgp-dev-row:last-child{border-bottom:0;}' +
    '.cgp-dev-ico{width:30px;height:30px;border-radius:8px;background:#f6edf0;display:flex;align-items:center;justify-content:center;font-size:16px;flex-shrink:0;}' +
    '.cgp-dev-main{flex:1;min-width:0;}' +
    '.cgp-dev-label{font-weight:600;color:#333;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}' +
    '.cgp-dev-meta{font-size:11.5px;color:#888;margin-top:2px;}' +
    '.cgp-dev-this{display:inline-block;margin-left:6px;padding:1px 7px;border-radius:999px;background:#e8f5e9;color:#2e7d32;font-size:10.5px;font-weight:700;vertical-align:1px;}' +
    '.cgp-dev-btn{background:none;border:1px solid #e3cbd4;color:#673147;border-radius:8px;padding:5px 10px;font-size:12px;font-weight:600;cursor:pointer;white-space:nowrap;}' +
    '.cgp-dev-btn:hover{background:#fdf5f7;}.cgp-dev-btn:disabled{opacity:.5;cursor:default;}' +
    '.cgp-dev-others{margin-top:10px;width:100%;border-color:#f3c0c0;color:#b71c1c;}' +
    '.cgp-dev-empty{padding:14px 0;font-size:13px;color:#888;}' +
    '.cgp-dev-overlay{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9500;display:flex;align-items:center;justify-content:center;padding:16px;}' +
    '.cgp-dev-modal{background:#fff;border-radius:14px;width:100%;max-width:440px;max-height:85vh;overflow:auto;box-shadow:0 24px 60px rgba(0,0,0,.3);font-family:inherit;}' +
    '.cgp-dev-hdr{display:flex;align-items:center;justify-content:space-between;padding:16px 20px 10px;font-weight:700;color:#673147;font-size:15px;}' +
    '.cgp-dev-x{background:none;border:0;font-size:18px;cursor:pointer;color:#888;}' +
    '.cgp-dev-body{padding:0 20px 18px;}' +
    '.cgp-dev-intro{font-size:12px;color:#888;line-height:1.5;margin-bottom:6px;}';

  function injectCss() {
    if (document.getElementById('cgp-devices-css')) return;
    var s = document.createElement('style'); s.id = 'cgp-devices-css'; s.textContent = CSS;
    document.head.appendChild(s);
  }

  /** The app's device secret (null in a browser). Never persisted by this file. */
  function deviceToken() {
    try {
      if (window.CGP_NATIVE && typeof window.CGP_NATIVE.getDeviceToken === 'function') return window.CGP_NATIVE.getDeviceToken();
    } catch (_) {}
    return Promise.resolve(null);
  }

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }

  function icon(platform) { return platform === 'ios' ? '📱' : platform === 'android' ? '🤖' : '💻'; }

  function when(iso) {
    if (!iso) return '';
    var d = new Date(iso); if (isNaN(d)) return '';
    var mins = Math.round((Date.now() - d.getTime()) / 60000);
    if (mins < 2) return 'just now';
    if (mins < 60) return mins + ' min ago';
    var h = Math.round(mins / 60); if (h < 24) return h + ' h ago';
    var days = Math.round(h / 24); if (days < 30) return days + ' day' + (days === 1 ? '' : 's') + ' ago';
    return d.toLocaleDateString();
  }

  function fetchJson(url, opts) {
    return fetch(url, opts).then(function (r) { return r.json().then(function (j) { j.__status = r.status; return j; }); });
  }

  /** Renders the list into `container`; re-renders after each action. */
  function render(container) {
    injectCss();
    container.innerHTML = '<div class="cgp-dev-empty">Loading…</div>';
    return deviceToken().then(function (token) {
      var q = token ? '?deviceToken=' + encodeURIComponent(token) : '';
      return fetchJson('/api/auth/devices' + q).then(function (j) {
        if (j.__status === 401) { container.innerHTML = '<div class="cgp-dev-empty">Please sign in again.</div>'; return; }
        var rows = (j && j.devices) || [];
        if (!rows.length) {
          container.innerHTML = '<div class="cgp-dev-empty">No remembered devices. Tick "Remember me" when signing in to add this one.</div>';
          return;
        }
        var html = '<ul class="cgp-dev-list">';
        var others = 0;
        rows.forEach(function (r) {
          if (!r.current) others++;
          html += '<li class="cgp-dev-row" data-id="' + esc(r.id) + '">' +
            '<div class="cgp-dev-ico">' + icon(r.platform) + '</div>' +
            '<div class="cgp-dev-main"><div class="cgp-dev-label">' + esc(r.label) +
            (r.current ? '<span class="cgp-dev-this">This device</span>' : '') + '</div>' +
            '<div class="cgp-dev-meta">' + (r.platform === 'web' ? 'Browser' : 'App') +
            (r.lastUsedAt ? ' · last used ' + esc(when(r.lastUsedAt)) : '') +
            (r.createdAt ? ' · added ' + esc(when(r.createdAt)) : '') + '</div></div>' +
            (r.current ? '' : '<button type="button" class="cgp-dev-btn" data-revoke="' + esc(r.id) + '">Sign out</button>') +
            '</li>';
        });
        html += '</ul>';
        if (others > 0) html += '<button type="button" class="cgp-dev-btn cgp-dev-others" data-revoke-others="1">Sign out of all other devices (' + others + ')</button>';
        container.innerHTML = html;

        container.querySelectorAll('[data-revoke]').forEach(function (b) {
          b.addEventListener('click', function () {
            b.disabled = true;
            fetchJson('/api/auth/devices/' + encodeURIComponent(b.getAttribute('data-revoke')), { method: 'DELETE' })
              .then(function () { render(container); })
              .catch(function () { b.disabled = false; });
          });
        });
        var all = container.querySelector('[data-revoke-others]');
        if (all) all.addEventListener('click', function () {
          if (!window.confirm('Sign out of all other devices? They will need the password next time.')) return;
          all.disabled = true;
          fetchJson('/api/auth/devices/revoke-others', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(token ? { deviceToken: token } : {})
          }).then(function () { render(container); }).catch(function () { all.disabled = false; });
        });
      });
    }).catch(function () {
      container.innerHTML = '<div class="cgp-dev-empty">Could not load devices. Please try again.</div>';
    });
  }

  /** Staff: a self-contained modal (opened from the notification bell). */
  function openModal() {
    injectCss();
    var old = document.getElementById('cgpDevicesOverlay'); if (old) old.remove();
    var ov = document.createElement('div'); ov.className = 'cgp-dev-overlay'; ov.id = 'cgpDevicesOverlay';
    ov.innerHTML = '<div class="cgp-dev-modal" role="dialog" aria-label="Signed-in devices">' +
      '<div class="cgp-dev-hdr"><span>Signed-in devices</span><button type="button" class="cgp-dev-x" aria-label="Close">✕</button></div>' +
      '<div class="cgp-dev-body"><div class="cgp-dev-intro">Browsers and phones that stay signed in with "Remember me". Signing one out means it needs the password next time.</div>' +
      '<div id="cgpDevicesBody"></div></div></div>';
    document.body.appendChild(ov);
    var close = function () { ov.remove(); };
    ov.querySelector('.cgp-dev-x').addEventListener('click', close);
    ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
    render(ov.querySelector('#cgpDevicesBody'));
  }

  window.CGP_DEVICES = { render: render, openModal: openModal };
})();
