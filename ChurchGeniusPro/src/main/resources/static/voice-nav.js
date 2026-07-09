/*
 * voice-nav.js — Voice navigation for the Home dashboard (OpenAI-powered).
 *
 * Click the 🎤 button and speak a command ("go to Income"). The audio is sent
 * to OpenAI for transcription + command normalization, then matched to a page
 * and navigated to — after verifying the user's permission against the sidebar
 * session.js already filtered. Recording auto-stops after 1 minute; press the
 * button again to start a new session. Usage is metered at the church level and
 * the button is disabled once the church's voice limit is reached (or a Service
 * Admin turns voice off).
 *
 * Only initialises on the Home dashboard.
 */
(function () {
  'use strict';

  if (!/^\/home(\b|\/|$)/.test(window.location.pathname)) return;

  /* ── Destination table: spoken keywords → page href ──────────────────────── */
  var ROUTES = [
    { href: '/home',                label: 'Home',                 keys: ['home', 'dashboard', 'main page'] },
    { href: '/income',              label: 'Income',               keys: ['income', 'incomes'] },
    { href: '/expense',             label: 'Expense',              keys: ['expense', 'expenses', 'spending'] },
    { href: '/bank-import',         label: 'Bank Import',          keys: ['bank import', 'import bank', 'bank statement'] },
    { href: '/pledges',             label: 'Pledges',              keys: ['pledge', 'pledges'] },
    { href: '/accountingReports',   label: 'Accounting Reports',   keys: ['accounting report', 'accounting reports', 'financial report', 'financial reports'] },
    { href: '/donation-review',     label: 'Donations',            keys: ['donation', 'donations', 'donation review'] },
    { href: '/payroll',             label: 'Payroll',              keys: ['payroll', 'pay roll'] },
    { href: '/meetings',            label: 'Meetings',             keys: ['meeting', 'meetings'] },
    { href: '/events',              label: 'Events',               keys: ['event', 'events'] },
    { href: '/ministry',            label: 'Ministry',             keys: ['kids ministry', 'children ministry', 'kids', 'ministry'] },
    { href: '/viewPrayerRequest',   label: 'Prayer Ministry',      keys: ['prayer ministry', 'prayer requests', 'prayer request', 'prayer'] },
    { href: '/notifyEmail',         label: 'Compose Email',        keys: ['compose email', 'notify email', 'send email', 'email'] },
    { href: '/reminders',           label: 'Reminders',            keys: ['reminder', 'reminders'] },
    { href: '/viewusers',           label: 'Users',                keys: ['users', 'user management', 'manage users'] },
    { href: '/viewfamily',          label: 'Families',             keys: ['families', 'family', 'view family', 'view families'] },
    { href: '/groups',              label: 'Groups',               keys: ['groups', 'group'] },
    { href: '/membershipRequests',  label: 'Membership Requests',  keys: ['membership requests', 'membership request'] },
    { href: '/unsubscribed-list',   label: 'Unsubscribed List',    keys: ['unsubscribed list', 'unsubscribed'] },
    { href: '/certificates',        label: 'Certificates',         keys: ['certificate', 'certificates'] },
    { href: '/publicScreens',       label: 'Public Screens',       keys: ['public screens', 'public screen'] },
    { href: '/followups',           label: 'Follow-Ups',           keys: ['follow ups', 'follow-ups', 'followups', 'follow up'] },
    { href: '/helpCenter',          label: 'Help Center',          keys: ['help center', 'help centre', 'help'] },
    { href: '/stripeIntegration',   label: 'Stripe Integration',   keys: ['stripe integration', 'stripe'] },
    { href: '/whatsappIntegration', label: 'WhatsApp Integration', keys: ['whatsapp integration', 'whatsapp', 'whats app'] }
  ];

  var LEAD_RE = /^(?:please\s+)?(?:can you\s+|could you\s+)?(?:go to|goto|navigate to|take me to|open up|open|show me|show|launch|visit|switch to|move to)\s+/i;

  var navigating = false;

  function esc(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  function matchRoute(text) {
    var t = ' ' + text.toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim() + ' ';
    var best = null, bestLen = 0;
    ROUTES.forEach(function (r) {
      r.keys.forEach(function (k) {
        if (k.length > bestLen) {
          var re = new RegExp('(^| )' + esc(k) + '( |$)');
          if (re.test(t)) { best = r; bestLen = k.length; }
        }
      });
    });
    return best;
  }

  function permissionFor(href) {
    var btns = document.querySelectorAll('#sidebarNav .nav-sub-btn, #sidebarNav .nav-item-btn');
    var foundHidden = false;
    for (var i = 0; i < btns.length; i++) {
      var oc = btns[i].getAttribute('onclick') || '';
      if (oc.indexOf("'" + href + "'") === -1) continue;
      var navItem = btns[i].closest ? btns[i].closest('.nav-item') : null;
      var hidden = btns[i].style.display === 'none' ||
                   (navItem && navItem.style.display === 'none');
      if (!hidden) return true;
      foundHidden = true;
    }
    return foundHidden ? false : 'unknown';
  }

  function processCommand(raw) {
    var text = (raw || '').replace(/[,]/g, ' ').trim();
    if (!text) { toast('Say a page name, e.g. “go to Income”.', 'info'); return; }
    var cleaned = text.replace(LEAD_RE, '').trim() || text;

    var route = matchRoute(cleaned) || matchRoute(text);
    if (!route) { toast('Sorry, I couldn’t find a page for “' + text + '”.', 'warn'); return; }

    var perm = permissionFor(route.href);
    if (perm === false) { toast('You do not have permission to access this page.', 'error'); return; }

    navigating = true;
    toast('Opening ' + route.label + '…', 'ok');
    setTimeout(function () { window.location.href = route.href; }, 450);
  }

  /* ════════════════════════ UI ════════════════════════ */

  var micBtn = null, toastEl = null, recorder = null;

  function injectStyles() {
    if (document.getElementById('voice-nav-style')) return;
    var s = document.createElement('style');
    s.id = 'voice-nav-style';
    s.textContent = [
      '.vn-mic{flex-shrink:0;width:40px;height:40px;border-radius:50%;border:1.5px solid #d9c2ce;background:#fff;',
        'color:#673147;font-size:17px;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;transition:all .15s;}',
      '.vn-mic:hover{background:#f6eef2;}',
      '.vn-mic.active{background:#673147;color:#fff;border-color:#673147;box-shadow:0 0 0 4px rgba(103,49,71,.18);animation:vnpulse 1.3s infinite;}',
      '.vn-mic:disabled{opacity:.45;cursor:not-allowed;}',
      '@keyframes vnpulse{0%{box-shadow:0 0 0 0 rgba(103,49,71,.35);}70%{box-shadow:0 0 0 9px rgba(103,49,71,0);}100%{box-shadow:0 0 0 0 rgba(103,49,71,0);}}',
      '.vn-row{display:flex;align-items:center;gap:10px;margin-top:8px;flex-wrap:wrap;}',
      '.vn-hint{font-size:12px;color:#9a8a93;}',
      '.vn-toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%) translateY(12px);opacity:0;',
        'background:#323232;color:#fff;padding:11px 18px;border-radius:9px;font-size:13.5px;z-index:99999;',
        'box-shadow:0 8px 28px rgba(0,0,0,.28);transition:opacity .2s,transform .2s;max-width:88vw;text-align:center;pointer-events:none;}',
      '.vn-toast.show{opacity:1;transform:translateX(-50%) translateY(0);}',
      '.vn-toast.error{background:#c62828;}.vn-toast.warn{background:#a06b00;}.vn-toast.ok{background:#2e7d46;}'
    ].join('');
    document.head.appendChild(s);
  }

  function toast(msg, kind) {
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.className = 'vn-toast';
      document.body.appendChild(toastEl);
    }
    toastEl.className = 'vn-toast ' + (kind || '');
    toastEl.textContent = msg;
    void toastEl.offsetWidth;
    toastEl.classList.add('show');
    clearTimeout(toastEl._t);
    toastEl._t = setTimeout(function () { toastEl.classList.remove('show'); }, 3200);
  }

  function setActive(on) { if (micBtn) micBtn.classList.toggle('active', !!on); }

  function disableMic(msg) {
    if (micBtn) { micBtn.disabled = true; micBtn.title = msg || 'Voice unavailable'; }
    if (msg) toast(msg, 'warn');
  }

  function injectUI() {
    var bar = document.querySelector('.ai-search-bar');
    var wrap = document.querySelector('.ai-search-wrap');
    if (!bar || !wrap) return false;

    injectStyles();

    recorder = (window.VoiceOpenAI) ? window.VoiceOpenAI.createRecorder({
      context:    'home',
      onState:    setActive,
      onStatus:   function (m, k) { toast(m, k === 'error' ? 'error' : (k === 'listening' ? 'ok' : 'info')); },
      onCommand:  function (cmd) { if (!navigating) processCommand(cmd); },
      onDisabled: function (st) { disableMic(st && st.voiceEnabled === false
                    ? 'Voice is disabled by your administrator.'
                    : 'Voice limit reached. Please contact your administrator.'); }
    }) : null;

    micBtn = document.createElement('button');
    micBtn.type = 'button';
    micBtn.className = 'vn-mic';
    micBtn.setAttribute('aria-label', 'Voice navigation');
    micBtn.innerHTML = '&#x1F3A4;';
    if (!recorder || !recorder.supported) {
      micBtn.disabled = true;
      micBtn.title = 'Voice navigation needs a recent Chrome, Edge, or Safari.';
    } else {
      micBtn.title = 'Click and speak a command (e.g. “go to Income”)';
      micBtn.addEventListener('click', function () { if (recorder) recorder.toggle(); });
    }
    bar.appendChild(micBtn);

    var row = document.createElement('div');
    row.className = 'vn-row';
    var hint = document.createElement('span');
    hint.className = 'vn-hint';
    hint.innerHTML = (recorder && recorder.supported)
      ? 'Click the mic and say: “go to Income”, “go to Events”, “go to Prayer Ministry”. Auto-stops after 1 minute.'
      : 'Voice navigation needs Chrome, Edge, or Safari.';
    row.appendChild(hint);
    wrap.appendChild(row);
    return true;
  }

  function init() {
    if (!injectUI()) return;
    // Disable up-front if the church is over its voice limit / voice is off.
    if (window.VoiceOpenAI) {
      window.VoiceOpenAI.getStatus().then(function (s) {
        if (s && s.voiceAvailable === false) {
          disableMic(s.voiceEnabled === false
            ? 'Voice is disabled by your administrator.'
            : 'Voice limit reached. Please contact your administrator.');
        }
      });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { setTimeout(init, 400); });
  } else {
    setTimeout(init, 400);
  }

  window.CGP_VOICE_NAV = { matchRoute: matchRoute, permissionFor: permissionFor, _routes: ROUTES };
})();
