/* ============================================================================
 * demo-trial.js — First-login trial-account agreement popup.
 *
 * Loaded on every authenticated page (via shell.js) but does NOTHING unless the
 * current session belongs to a demo/trial login that has not yet accepted the
 * agreement. For those it raises a modal that cannot be dismissed by clicking
 * away, pressing Escape or tabbing past it: the only exit is the OK button.
 *
 * The server is the source of truth. DemoTrialAgreementFilter refuses this
 * session's data APIs with 403 DEMO_AGREEMENT_REQUIRED until the acceptance is
 * recorded, so removing this overlay by hand yields empty pages, not access.
 * ========================================================================== */
(function () {
  'use strict';
  if (window.__CGP_DEMO_TRIAL__) return; window.__CGP_DEMO_TRIAL__ = true;

  var OVERLAY_ID = 'cgpDemoTrialOverlay';

  function escHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* "2026-10-07" -> "October 7, 2026". Falls back to the raw value for anything
     that is not a plain ISO date, so an unexpected format is still readable. */
  function prettyDate(iso) {
    if (!iso) return '';
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso));
    if (!m) return String(iso);
    var months = ['January', 'February', 'March', 'April', 'May', 'June',
                  'July', 'August', 'September', 'October', 'November', 'December'];
    var mi = parseInt(m[2], 10) - 1;
    if (mi < 0 || mi > 11) return String(iso);
    return months[mi] + ' ' + parseInt(m[3], 10) + ', ' + m[1];
  }

  /* Keeps focus inside the dialog: without this, Tab walks onto the page behind
     the overlay and the "cannot continue" guarantee is only visual. */
  function trapFocus(e) {
    var ov = document.getElementById(OVERLAY_ID);
    if (!ov) return;
    if (e.key === 'Escape' || e.key === 'Esc') { e.preventDefault(); e.stopPropagation(); return; }
    if (e.key !== 'Tab') return;
    var btn = document.getElementById('cgpDemoTrialOk');
    if (btn) { e.preventDefault(); btn.focus(); }
  }

  function close() {
    var ov = document.getElementById(OVERLAY_ID);
    if (ov && ov.parentNode) ov.parentNode.removeChild(ov);
    document.removeEventListener('keydown', trapFocus, true);
    document.body.style.overflow = '';
  }

  function accept(btn) {
    btn.disabled = true;
    btn.textContent = 'Please wait…';
    fetch('/api/demo/trial-agreement/accept', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
      .then(function () {
        close();
        // The page loaded while its data APIs were being refused, so most of it is
        // empty. Reload now that the gate is lifted rather than leaving blank tables.
        window.location.reload();
      })
      .catch(function () {
        btn.disabled = false;
        btn.textContent = 'OK';
        var err = document.getElementById('cgpDemoTrialErr');
        if (err) {
          err.textContent = 'Could not record your acknowledgement. Please try again.';
          err.style.display = 'block';
        }
      });
  }

  function show(message, endDate, kind, trial) {
    if (document.getElementById(OVERLAY_ID)) return;

    var ov = document.createElement('div');
    ov.id = OVERLAY_ID;
    ov.setAttribute('role', 'dialog');
    ov.setAttribute('aria-modal', 'true');
    ov.setAttribute('aria-labelledby', 'cgpDemoTrialTitle');
    ov.style.cssText =
      'position:fixed;inset:0;z-index:2147483600;display:flex;align-items:center;' +
      'justify-content:center;padding:20px;background:rgba(15,23,42,0.72);' +
      '-webkit-backdrop-filter:blur(2px);backdrop-filter:blur(2px);';

    // A demo tenant is sample data, not a trial: it has no trial period, and
    // saying it does started renewal conversations nobody meant to start.
    var isDemo   = kind === 'demo';
    var dateLabel = isDemo ? 'This demo account is available until:'
                           : 'Your trial period will end on:';
    var dateLine = (!isDemo && trial && trial.endDate)
      ? '<div style="margin-top:16px;padding:12px 14px;border-radius:8px;background:#fff7ed;' +
        'border:1px solid #fed7aa;color:#7c2d12;font-size:15px;line-height:1.7;">' +
          (trial.days != null ? '<strong>Your Trial Account is active for ' + escHtml(String(trial.days)) + ' days.</strong><br>' : '') +
          (trial.startDate ? 'Trial Start Date: ' + escHtml(prettyDate(trial.startDate)) + '<br>' : '') +
          'Trial End Date: ' + escHtml(prettyDate(trial.endDate)) +
          ' <span style="font-size:13px;">(last day of access: ' + escHtml(prettyDate(trial.lastDay)) + ')</span><br>' +
          'Days remaining: ' + escHtml(String(trial.daysRemaining)) +
        '</div>'
      : endDate
      ? '<div style="margin-top:16px;padding:12px 14px;border-radius:8px;background:#fff7ed;' +
        'border:1px solid #fed7aa;color:#7c2d12;font-size:15px;">' +
          '<strong>' + dateLabel + '</strong> ' + escHtml(prettyDate(endDate)) +
        '</div>'
      : '';

    ov.innerHTML =
      '<div style="max-width:560px;width:100%;background:#fff;border-radius:14px;' +
           'box-shadow:0 24px 60px rgba(0,0,0,0.35);overflow:hidden;' +
           'font-family:system-ui,-apple-system,\'Segoe UI\',Roboto,sans-serif;">' +
        '<div style="padding:20px 24px;background:#1e3a8a;color:#fff;">' +
          '<div id="cgpDemoTrialTitle" style="font-size:19px;font-weight:600;">' +
            (isDemo ? 'Demonstration Account' : 'Trial Account') +
          '</div>' +
        '</div>' +
        '<div style="padding:24px;color:#1f2937;font-size:15px;line-height:1.6;">' +
          '<div>' + escHtml(message) + '</div>' +
          dateLine +
          '<div id="cgpDemoTrialErr" style="display:none;margin-top:14px;color:#b91c1c;font-size:14px;"></div>' +
        '</div>' +
        '<div style="padding:0 24px 24px;text-align:right;">' +
          '<button id="cgpDemoTrialOk" type="button" style="min-width:120px;padding:11px 26px;' +
            'border:0;border-radius:8px;background:#1e3a8a;color:#fff;font-size:15px;' +
            'font-weight:600;cursor:pointer;">OK</button>' +
        '</div>' +
      '</div>';

    document.body.appendChild(ov);
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', trapFocus, true);

    var btn = document.getElementById('cgpDemoTrialOk');
    btn.addEventListener('click', function () { accept(btn); });
    btn.focus();
  }

  function init() {
    fetch('/api/demo/trial-agreement')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.demo || d.accepted) return;    // not a trial login, or already agreed
        show(d.message || '', d.endDate || '', d.kind || 'trial', d.trial || null);
      })
      .catch(function () { /* never block a normal page on this check */ });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

})();
