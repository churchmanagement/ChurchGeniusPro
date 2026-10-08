/* ============================================================================
 * product-tour.js — First-login product tour for a trial/demo STAFF account.
 *
 * Loaded on every authenticated page (via shell.js) but does NOTHING unless the
 * server says this sign-in should see it: a demo or trial tenant, a staff login,
 * the trial agreement already accepted, and the tour not yet finished or skipped.
 * A paying church, a Member or Kids portal login, and anyone who has already been
 * through it all get an immediate "show: false" and no overlay is built.
 *
 * The server is the source of truth for "already seen" — it is recorded on the
 * demo_role_access row, per LOGIN rather than per session, so closing the browser
 * does not replay the tour. Finishing and skipping record the same thing, because
 * "do not show me this again" is what both of them mean.
 *
 * Unlike the agreement popup this overlay is NOT a gate: Escape, the × and Skip
 * all close it, and nothing behind it is withheld. It is an introduction, not a
 * condition of use.
 * ========================================================================== */
(function () {
  'use strict';
  if (window.__CGP_PRODUCT_TOUR__) return; window.__CGP_PRODUCT_TOUR__ = true;

  var OVERLAY_ID = 'cgpTourOverlay';
  var BRAND      = '#673147';
  var step       = 0;
  var steps      = [];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* ── Content ───────────────────────────────────────────────────────────────
     Every "where" line names a real place in this application, so the tour can
     be followed rather than just read. Keep descriptions to one sentence: the
     tour is an introduction, not the Help Center.                             */

  function buildSteps(endDate) {
    return [
      {
        key: 'welcome',
        eyebrow: 'Getting started',
        title: 'Welcome to Church Genius Pro',
        lead: 'A two-minute tour of what to set up first, and what the product can do '
            + 'once it is. You can leave at any point and pick things up as you go.',
        note: endDate
          ? 'Your account is available until <strong>' + esc(endDate) + '</strong>.'
          : '',
        cards: [
          { icon: '⚙️', title: 'Initial setup',   text: 'The four things worth configuring before anything else.' },
          { icon: '✨', title: 'Handy features',  text: 'Shortcuts and tools that save the most time day to day.' },
          { icon: '🤖', title: 'AI assistance',   text: 'Where you can type, talk or hold a conversation with it.' }
        ]
      },
      {
        key: 'setup',
        eyebrow: 'Initial setup · 1 of 2',
        title: 'Set these up first',
        lead: 'Four short screens. Everything else in the product reads from them.',
        cards: [
          { icon: '💰', title: 'Account Settings — accounting setup',
            text: 'Your funds, purposes and transaction types. Every gift and expense is filed '
                + 'against these, so it is worth naming them the way your church already talks.',
            where: 'ACCOUNT SETTINGS in the top bar → Fund, Purpose, Transaction Type' },
          { icon: '📅', title: 'Meeting Categories',
            text: 'The kinds of gathering you hold — Sunday Service, Prayer Meeting, Bible Study. '
                + 'Meetings and attendance are grouped and reported by these.',
            where: 'ADMIN SETTINGS in the top bar → Meeting Type' }
        ]
      },
      {
        key: 'setup2',
        eyebrow: 'Initial setup · 2 of 2',
        title: 'Then how you reach people',
        lead: 'How email leaves your church, and what it says at the bottom.',
        cards: [
          { icon: '✉️', title: 'Email Settings',
            text: 'The sender name people see, your signature and footer, and whether to add your '
                + 'logo and a daily verse to every message.',
            where: 'ADMIN SETTINGS in the top bar → Email Settings' },
          { icon: '📖', title: 'Daily Verse',
            text: 'Load a whole year of verses in one click so every email can close with scripture. '
                + 'Only needs doing once — the list is reused every year.',
            where: 'Email Settings → Daily Verse Setup' }
        ]
      },
      {
        key: 'everyday',
        eyebrow: 'Features · 1 of 3',
        title: 'Everyday shortcuts',
        lead: 'Two small things that pay for themselves in the first week.',
        cards: [
          { icon: '⭐', title: 'Favorites',
            text: 'Star the pages you use most and they pin to the top of the side menu, so the '
                + 'five screens you actually live in are never more than one click away.',
            where: 'The ☆ on any page or side-menu item' },
          { icon: '🔄', title: 'Switch Accounts',
            text: 'Hold more than one login — staff in one church, a member in another — and move '
                + 'between them without signing out and back in.',
            where: 'Your name at the top of the side menu' }
        ]
      },
      {
        key: 'access',
        eyebrow: 'Features · 2 of 3',
        title: 'Access and sign-in',
        lead: 'Ways to let people in — with a login, without one, or with a tap.',
        cards: [
          { icon: '🔒', title: 'Private Page Access',
            text: 'Share one page with someone who has no login, protected by a passcode you set '
                + 'and can withdraw whenever you like.',
            where: 'Admin → Private Page Access' },
          { icon: '📱', title: 'NTag Landing Page',
            text: 'A tap-to-open page for an NFC tag or QR code at your entrance — events, giving '
                + 'and the connect card in one place.',
            where: 'More → Public Screens' },
          { icon: '🔑', title: 'NTag & Barcode Login',
            text: 'Let staff and volunteers sign in by tapping a tag or scanning a barcode instead '
                + 'of typing a password on a shared device.',
            where: 'Admin → NTAG Login' },
          { icon: '📺', title: 'Public Screens',
            text: 'Publish a link for the foyer screen or your website — calendar, upcoming events, '
                + 'membership form or connect card.',
            where: 'More → Public Screens' }
        ]
      },
      {
        key: 'doing',
        eyebrow: 'Features · 3 of 3',
        title: 'Less typing',
        lead: 'Four features that do the repetitive part for you.',
        cards: [
          { icon: '🎵', title: 'Digital Song Book',
            text: 'Build a searchable song library your worship team and congregation can open on '
                + 'any device, and control who may see it.',
            where: 'My Profile → Song Book (sharing: More → Song Book Access)' },
          { icon: '🔁', title: 'Recurring Transactions',
            text: 'Mark an income or expense as recurring and it is kept as a template — next '
                + "month's entry is a click rather than a re-typing.",
            where: 'Accounting → Income or Expense, the 🔁 Recurring toggle' },
          { icon: '🏦', title: 'Scan Check',
            text: 'Photograph a check and the amount, date and payer are read off it for you, '
                + 'ready to confirm before saving.',
            where: 'Accounting → Income or Expense' },
          { icon: '🎼', title: 'Automatic Worship Assignment',
            text: 'Build the rota from who plays what and who is available, then adjust by hand '
                + 'where you want to.',
            where: 'General → Ministry → Worship Planning' }
        ]
      },
      {
        key: 'ai',
        eyebrow: 'AI',
        title: 'AI is built into three places',
        lead: 'Not a separate product — it sits inside the pages you already use.',
        cards: [
          { icon: '🔍', title: 'Search',
            text: 'Ask where something is, or what a number is, from the bar at the top of every page.',
            where: 'The search bar, anywhere' },
          { icon: '📅', title: 'Meetings',
            text: 'Dictate notes and minutes as you go, and ask questions about past meetings.',
            where: 'General → Meetings' },
          { icon: '💵', title: 'Income & Expense',
            text: 'Record and query transactions by describing them, instead of filling in the form.',
            where: 'Accounting → Income and Expense' }
        ],
        modes: [
          { icon: '⌨️', title: 'Type',     text: 'Write the question and read the answer.' },
          { icon: '🎙️', title: 'Voice',    text: 'Speak a command and it acts on it.' },
          { icon: '💬', title: 'Converse', text: 'It asks follow-up questions until it has what it needs.' }
        ],
        note: 'Look for the three small icons at the right-hand end of the search bar — that is '
            + 'where you choose between them.'
      },
      {
        key: 'install',
        eyebrow: 'Take it with you',
        title: 'Install the app on your phone',
        lead: 'Church Genius Pro installs straight from the website — it is not in the Play Store '
            + 'or App Store. Once installed it opens full-screen and updates itself.',
        cards: [
          { icon: '🤖', title: 'Android (Chrome)',
            text: 'Open Chrome and go to churchgeniuspro.net.',
            steps: ['Tap the ⋮ menu → Install app (or Add to Home screen → Install)',
                    'Open Church Genius Pro from your home screen and sign in',
                    'Long-press the icon for the Member Home and Dashboard shortcuts'] },
          { icon: '🍎', title: 'iPhone / iPad (Safari)',
            text: 'Open Safari and go to churchgeniuspro.net.',
            steps: ['Tap Share (the square with an arrow) → Add to Home Screen → Add',
                    'Open Church Genius Pro from your home screen and sign in'] }
        ],
        note: 'No Install option? Open the site directly in Chrome or Safari, not from a link inside '
            + 'another app. Allow notifications if you want reminders on your phone.'
      },
      {
        key: 'finish',
        eyebrow: 'All done',
        title: "That's the tour",
        lead: 'Start with the accounting setup and your meeting categories — most other screens '
            + 'read from those two. Everything here is also in the Help Center whenever you need it.',
        links: [
          { icon: '💰', label: 'Accounting setup',  href: '/fund'            },
          { icon: '📅', label: 'Meeting Categories', href: '/meetingtype'    },
          { icon: '📖', label: 'Daily Verse Setup', href: '/dailyVerseSetup' },
          { icon: '📚', label: 'Help Center',       href: '/helpCenter'      },
          { icon: '📱', label: 'Install the app',   href: '/helpCenter#install-app' }
        ]
      }
    ];
  }

  /* ── Styles ─────────────────────────────────────────────────────────────── */

  function injectStyle() {
    if (document.getElementById('cgpTourStyle')) return;
    var css =
      '#' + OVERLAY_ID + '{position:fixed;inset:0;z-index:2147483500;display:flex;' +
        'align-items:center;justify-content:center;padding:20px;' +
        'background:rgba(24,15,20,.66);-webkit-backdrop-filter:blur(3px);backdrop-filter:blur(3px);' +
        'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;}' +
      '.cgpt-card{width:100%;max-width:760px;max-height:92vh;display:flex;flex-direction:column;' +
        'background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 30px 70px rgba(0,0,0,.4);' +
        'animation:cgptIn .22s ease;}' +
      '@keyframes cgptIn{from{opacity:0;transform:translateY(10px) scale(.99);}to{opacity:1;transform:none;}}' +
      '@media (prefers-reduced-motion:reduce){.cgpt-card{animation:none;}}' +
      '.cgpt-head{position:relative;padding:22px 26px 18px;color:#fff;' +
        'background:linear-gradient(135deg,' + BRAND + ' 0%,#4f2538 100%);}' +
      '.cgpt-eyebrow{font-size:11px;font-weight:700;letter-spacing:1.2px;text-transform:uppercase;' +
        'opacity:.72;margin-bottom:6px;}' +
      '.cgpt-title{font-size:22px;font-weight:700;line-height:1.25;margin:0;padding-right:38px;}' +
      '.cgpt-lead{font-size:14px;line-height:1.6;opacity:.9;margin-top:8px;}' +
      '.cgpt-x{position:absolute;top:16px;right:16px;width:30px;height:30px;border-radius:8px;' +
        'border:0;background:rgba(255,255,255,.14);color:#fff;font-size:17px;line-height:1;cursor:pointer;}' +
      '.cgpt-x:hover{background:rgba(255,255,255,.28);}' +
      '.cgpt-bars{display:flex;gap:4px;margin-top:16px;}' +
      '.cgpt-bars i{flex:1;height:4px;border-radius:3px;background:rgba(255,255,255,.26);}' +
      '.cgpt-bars i.on{background:#fff;}' +
      '.cgpt-body{padding:22px 26px;overflow-y:auto;}' +
      '.cgpt-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;}' +
      '.cgpt-grid.one{grid-template-columns:1fr;}' +
      '.cgpt-item{display:flex;gap:12px;padding:14px 15px;border:1px solid #eee3e9;border-radius:11px;' +
        'background:#fdfbfc;}' +
      '.cgpt-ico{font-size:20px;line-height:1.2;flex-shrink:0;}' +
      '.cgpt-it{font-size:14.5px;font-weight:700;color:' + BRAND + ';}' +
      '.cgpt-id{font-size:13px;color:#6b6470;line-height:1.6;margin-top:3px;}' +
      '.cgpt-iw{font-size:11.5px;color:#9a8fa3;line-height:1.5;margin-top:7px;' +
        'padding-top:7px;border-top:1px dashed #eadfe5;}' +
      '.cgpt-iw b{color:#7d6f86;font-weight:600;}' +
      '.cgpt-ol{margin:8px 0 0;padding-left:18px;font-size:13px;color:#4a4350;line-height:1.6;}' +
      '.cgpt-ol li{margin-bottom:3px;}' +
      '.cgpt-modes{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:14px;}' +
      '.cgpt-mode{text-align:center;padding:12px 10px;border-radius:10px;background:#f6f1f4;}' +
      '.cgpt-mode .cgpt-ico{font-size:19px;}' +
      '.cgpt-mode b{display:block;font-size:13px;color:' + BRAND + ';margin:5px 0 3px;}' +
      '.cgpt-mode span{font-size:12px;color:#7d7384;line-height:1.5;}' +
      '.cgpt-note{margin-top:14px;padding:11px 14px;border-radius:9px;background:#fff7ed;' +
        'border:1px solid #fde4c4;color:#7c4a12;font-size:13px;line-height:1.6;}' +
      '.cgpt-links{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;}' +
      '.cgpt-link{display:flex;align-items:center;gap:10px;padding:13px 15px;border-radius:10px;' +
        'border:1px solid #eee3e9;background:#fdfbfc;color:' + BRAND + ';text-decoration:none;' +
        'font-size:14px;font-weight:600;}' +
      '.cgpt-link:hover{border-color:#c5a0b5;background:#f9f3f6;}' +
      '.cgpt-foot{display:flex;align-items:center;gap:10px;padding:15px 26px 20px;' +
        'border-top:1px solid #f1ecef;flex-wrap:wrap;}' +
      '.cgpt-count{font-size:12.5px;color:#9a8fa3;margin-right:auto;}' +
      '.cgpt-btn{padding:10px 22px;border-radius:9px;font-size:14px;font-weight:600;border:0;cursor:pointer;' +
        'font-family:inherit;}' +
      '.cgpt-next{background:' + BRAND + ';color:#fff;min-width:118px;}' +
      '.cgpt-next:hover{background:#4f2538;}' +
      '.cgpt-back{background:transparent;color:' + BRAND + ';border:1px solid #d9c4cf;}' +
      '.cgpt-back:hover{background:#f7f1f4;}' +
      '.cgpt-skip{background:transparent;color:#9a8fa3;font-size:13px;font-weight:600;' +
        'text-decoration:underline;padding:10px 6px;border:0;cursor:pointer;font-family:inherit;}' +
      '.cgpt-skip:hover{color:' + BRAND + ';}' +
      '@media (max-width:640px){' +
        '.cgpt-grid,.cgpt-modes,.cgpt-links{grid-template-columns:1fr;}' +
        '.cgpt-title{font-size:19px;}' +
        '.cgpt-head,.cgpt-body,.cgpt-foot{padding-left:16px;padding-right:16px;}' +
        '.cgpt-count{width:100%;margin:0 0 6px;}' +
        '.cgpt-btn{flex:1;}' +
      '}';
    var s = document.createElement('style');
    s.id = 'cgpTourStyle';
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }

  /* ── Render ─────────────────────────────────────────────────────────────── */

  function itemHtml(c) {
    return '<div class="cgpt-item">'
      + '<div class="cgpt-ico">' + esc(c.icon) + '</div>'
      + '<div>'
        + '<div class="cgpt-it">' + esc(c.title) + '</div>'
        + '<div class="cgpt-id">' + esc(c.text) + '</div>'
        + (c.steps ? '<ol class="cgpt-ol">' + c.steps.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ol>' : '')
        + (c.where ? '<div class="cgpt-iw"><b>Where:</b> ' + esc(c.where) + '</div>' : '')
      + '</div>'
      + '</div>';
  }

  function bodyHtml(s) {
    var html = '';
    if (s.cards && s.cards.length) {
      html += '<div class="cgpt-grid' + (s.cards.length < 2 ? ' one' : '') + '">'
            + s.cards.map(itemHtml).join('') + '</div>';
    }
    if (s.modes) {
      html += '<div class="cgpt-modes">' + s.modes.map(function (m) {
        return '<div class="cgpt-mode"><div class="cgpt-ico">' + esc(m.icon) + '</div>'
             + '<b>' + esc(m.title) + '</b><span>' + esc(m.text) + '</span></div>';
      }).join('') + '</div>';
    }
    if (s.links) {
      html += '<div class="cgpt-links">' + s.links.map(function (l) {
        // Following a link ends the tour deliberately: the person is acting on it,
        // which is the best possible outcome, and it must not reappear behind them.
        return '<a class="cgpt-link" href="' + esc(l.href) + '" data-cgpt-go="1">'
             + '<span class="cgpt-ico">' + esc(l.icon) + '</span>' + esc(l.label) + '</a>';
      }).join('') + '</div>';
    }
    // s.note is authored above, never user input, and carries deliberate markup.
    if (s.note) html += '<div class="cgpt-note">' + s.note + '</div>';
    return html;
  }

  function render() {
    var ov = document.getElementById(OVERLAY_ID);
    if (!ov) return;
    var s     = steps[step];
    var first = step === 0;
    var last  = step === steps.length - 1;

    ov.querySelector('.cgpt-card').innerHTML =
      '<div class="cgpt-head">'
        + '<button class="cgpt-x" type="button" id="cgptX" aria-label="Close the tour">✕</button>'
        + '<div class="cgpt-eyebrow">' + esc(s.eyebrow) + '</div>'
        + '<h2 class="cgpt-title" id="cgptTitle">' + esc(s.title) + '</h2>'
        + (s.lead ? '<div class="cgpt-lead">' + esc(s.lead) + '</div>' : '')
        + '<div class="cgpt-bars">'
          + steps.map(function (_, i) { return '<i class="' + (i <= step ? 'on' : '') + '"></i>'; }).join('')
        + '</div>'
      + '</div>'
      + '<div class="cgpt-body">' + bodyHtml(s) + '</div>'
      + '<div class="cgpt-foot">'
        + '<span class="cgpt-count">Step ' + (step + 1) + ' of ' + steps.length + '</span>'
        + (first ? '' : '<button class="cgpt-btn cgpt-back" type="button" id="cgptBack">← Back</button>')
        + (last  ? '' : '<button class="cgpt-skip" type="button" id="cgptSkipStep">Skip this step</button>')
        + (last  ? '' : '<button class="cgpt-skip" type="button" id="cgptSkipAll">Skip tour</button>')
        + '<button class="cgpt-btn cgpt-next" type="button" id="cgptNext">'
          + (last ? 'Finish' : 'Next →') + '</button>'
      + '</div>';

    bind('cgptX',        function () { finish(); });
    bind('cgptBack',     function () { go(step - 1); });
    bind('cgptSkipStep', function () { go(step + 1); });
    bind('cgptSkipAll',  function () { finish(); });
    bind('cgptNext',     function () { last ? finish() : go(step + 1); });

    // A link on the last screen records the tour as done before navigating, so
    // it does not reopen on the page the person just asked for.
    Array.prototype.forEach.call(ov.querySelectorAll('[data-cgpt-go]'), function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault();
        var href = a.getAttribute('href');
        record().then(function () { window.location.href = href; });
      });
    });

    var next = document.getElementById('cgptNext');
    if (next) next.focus();
    ov.querySelector('.cgpt-body').scrollTop = 0;
  }

  function bind(id, fn) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('click', fn);
  }

  function go(i) {
    if (i < 0 || i >= steps.length) return;
    step = i;
    render();
  }

  /* ── Open / close ───────────────────────────────────────────────────────── */

  function onKey(e) {
    if (!document.getElementById(OVERLAY_ID)) return;
    if (e.key === 'Escape' || e.key === 'Esc') { e.preventDefault(); finish(); }
    else if (e.key === 'ArrowRight') { go(step + 1); }
    else if (e.key === 'ArrowLeft')  { go(step - 1); }
  }

  function close() {
    var ov = document.getElementById(OVERLAY_ID);
    if (ov && ov.parentNode) ov.parentNode.removeChild(ov);
    document.removeEventListener('keydown', onKey, true);
    document.body.style.overflow = '';
  }

  /** Records the tour as seen. Resolves either way — a failed write must not trap anyone. */
  function record() {
    return fetch('/api/demo/product-tour/complete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    }).catch(function () { /* closing matters more than recording */ });
  }

  function finish() {
    close();
    record();
  }

  function open(endDate) {
    if (document.getElementById(OVERLAY_ID)) return;
    injectStyle();
    steps = buildSteps(endDate);
    step  = 0;

    var ov = document.createElement('div');
    ov.id = OVERLAY_ID;
    ov.setAttribute('role', 'dialog');
    ov.setAttribute('aria-modal', 'true');
    ov.setAttribute('aria-labelledby', 'cgptTitle');
    ov.innerHTML = '<div class="cgpt-card"></div>';
    // Clicking the backdrop closes it. This is an introduction, not a gate.
    ov.addEventListener('click', function (e) { if (e.target === ov) finish(); });

    document.body.appendChild(ov);
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', onKey, true);
    render();
  }

  /* Manual replay, for a "show me the tour again" link later on. */
  window.CGP_openProductTour = function (endDate) { open(endDate || ''); };

  function prettyDate(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    if (!m) return '';
    var months = ['January','February','March','April','May','June',
                  'July','August','September','October','November','December'];
    var mi = parseInt(m[2], 10) - 1;
    return (mi < 0 || mi > 11) ? '' : months[mi] + ' ' + parseInt(m[3], 10) + ', ' + m[1];
  }

  function init() {
    fetch('/api/demo/product-tour')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.show) return;          // not a trial staff login, or already seen
        open(prettyDate(d.endDate));
      })
      .catch(function () { /* never block a normal page on this check */ });
  }

  // Deliberately AFTER load, not on DOMContentLoaded: the tour is an
  // introduction that can wait a moment, and starting it later keeps it out of
  // the burst of parallel API calls a page makes as it opens. That burst is what
  // exposed a Spring Session race (see SessionConfig) — the race is fixed there,
  // but there is no reason for an overlay to be part of the stampede.
  function start() { setTimeout(init, 400); }

  if (document.readyState === 'complete') {
    start();
  } else {
    window.addEventListener('load', start);
  }

})();
