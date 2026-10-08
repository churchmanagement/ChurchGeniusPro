/* ============================================================================
 * home-voice.js — Voice navigation for the Home dashboard (OpenAI-powered).
 *
 * Adds Voice / Converse / Guide / Debug controls to the Home page (mirroring the
 * Income/Expense voice UI) so users can navigate the app by voice:
 *   "Go to Income", "Open Event Reminders", "Switch Account to Admin", "Help".
 *
 * SECURITY: a destination is only navigable if it is available to the logged-in
 * user's role. Availability is derived from the app's own role→section model
 * (window.CGP_ROLE_SECTIONS) plus the documented sub-item filters in session.js.
 * If a destination is not available we say "That page is not available for your
 * current account" and do NOT navigate.
 *
 * Routes are all confirmed (taken from window.CGP_MENU + known sub-routes).
 * Only initialises on /home. Supersedes voice-nav.js on this page.
 * ========================================================================== */
(function () {
  'use strict';
  if (!/^\/home(\b|\/|$)/.test(window.location.pathname)) return;

  /* ── Confirmed destinations (label, target URL, nav section, spoken keys) ──
   * section ∈ members | admin | accounting | general | more | admin-settings |
   *           account-settings | reminders. `tab` = member-portal tab.
   * `payroll:true` marks Payroll items (the only Accounting items an Admin sees).
   * `placeholder:true` = recognised but not built yet. */
  var DESTS = [
    // ── My Profile (member portal tabs) ──
    { label:'My Family',      target:'/memberHome?tab=family',       section:'members', cat:'Profile',        keys:['my family','family'] },
    { label:'Contributions',  target:'/memberHome?tab=give',         section:'members', cat:'Profile',        keys:['contributions','my contributions','giving'] },
    // ── Member Functions ──
    { label:'Groups',         target:'/memberHome?tab=groups',       section:'members', cat:'Member',         keys:['groups','my groups'] },
    { label:'Classes',        target:'/memberHome?tab=sundaySchool', section:'members', cat:'Member',         keys:['classes','class','sunday school'] },
    { label:'Volunteer',      target:'/memberHome?tab=volunteer',    section:'members', cat:'Member',         keys:['volunteer','volunteering'] },
    { label:'Directory',      target:'/memberHome?tab=directory',    section:'members', cat:'Member',         keys:['directory','member directory'] },
    // ── Admin Functions ──
    { label:'Families',       target:'/viewfamily',          section:'admin', cat:'Admin', adminFamily:true,  keys:['families','admin families','view families'] },
    { label:'Admin Groups',   target:'/groups',              section:'admin', cat:'Admin',                    keys:['admin groups'] },
    { label:'Membership Requests', target:'/membershipRequests', section:'admin', cat:'Admin', churchSub:true, keys:['membership requests','membership request'] },
    { label:'Unsubscribed List',   target:'/unsubscribed-list',  section:'admin', cat:'Admin',                keys:['unsubscribed list','unsubscribed'] },
    { label:'Users',          target:'/viewusers',           section:'admin', cat:'Admin', churchSub:true, usersOnly:true, keys:['users','user management','manage users'] },
    // ── Finance ──
    { label:'Income',         target:'/income',              section:'accounting', cat:'Finance',            keys:['income'] },
    { label:'Expense',        target:'/expense',             section:'accounting', cat:'Finance',            keys:['expense','expenses'] },
    { label:'Bank Import',    target:'/bank-import',         section:'accounting', cat:'Finance',            keys:['bank import','import bank','bank statement'] },
    { label:'Pledges',        target:'/pledges',             section:'accounting', cat:'Finance',            keys:['pledges','pledge'] },
    { label:'Donation',       target:'/donation-review',     section:'accounting', cat:'Finance',            keys:['donation','donations','donation review'] },
    // ── Reports (each has its own page) ──
    { label:'Reports',            target:'/accountingReports',  section:'accounting', cat:'Reports',          keys:['reports','report','accounting reports'] },
    { label:'Income Report',      target:'/income-report',      section:'accounting', cat:'Reports',          keys:['income report'] },
    { label:'Expense Report',     target:'/expense-report',     section:'accounting', cat:'Reports',          keys:['expense report'] },
    { label:'Transaction Report', target:'/transactions-report',section:'accounting', cat:'Reports',          keys:['transaction report','transactions report'] },
    { label:'Tax Report',         target:'/tax-report',         section:'accounting', cat:'Reports',          keys:['tax report'] },
    { label:'Financial Report',   target:'/tax-report',         section:'accounting', cat:'Reports',          keys:['financial report'] },
    // ── Payroll (Accounting; the only Accounting items an Admin can see) ──
    { label:'Payroll',          target:'/payroll',           section:'accounting', cat:'Payroll', payroll:true, keys:['payroll'] },
    { label:'Employee Payroll', target:'/payroll/employees', section:'accounting', cat:'Payroll', payroll:true, keys:['employee payroll','employees payroll'] },
    { label:'Payroll Runs',     target:'/payroll/runs',      section:'accounting', cat:'Payroll', payroll:true, keys:['payroll runs','payroll run'] },
    { label:'Payroll Reports',  target:'/payroll/reports',   section:'accounting', cat:'Payroll', payroll:true, keys:['payroll reports','payroll report'] },
    { label:'Payroll Activity', target:'/payroll/activity',  section:'accounting', cat:'Payroll', payroll:true, keys:['payroll activity'] },
    // ── Ministry (General) ──
    { label:'Meetings',         target:'/meetings',          section:'general', cat:'Ministry',               keys:['meetings','meeting'] },
    { label:'Events',           target:'/events',            section:'general', cat:'Ministry',               keys:['events','event'] },
    { label:'Ministry',         target:'/ministry',          section:'general', cat:'Ministry',               keys:['ministry'] },
    { label:'Kids Ministry',    target:'/kidsMinistry',      section:'general', cat:'Ministry',               keys:['kids ministry','children ministry'] },
    { label:'Worship Planning', target:'/worshipPlanning',   section:'general', cat:'Ministry',               keys:['worship planning','worship'] },
    { label:'Prayer Ministry',  target:'/prayerRequest',     section:'general', cat:'Ministry',               keys:['prayer ministry','prayer'] },
    // ── Communications (General) ──
    { label:'Email',              target:'/notifyEmail',     section:'general', cat:'Communications',         keys:['email','compose email','send email','notify email'] },
    { label:'Reminders',          target:'/reminders',       section:'general', cat:'Communications',         keys:['reminders','reminder'] },
    { label:'Event Reminders',    target:'/eventReminders',  section:'general', cat:'Communications',         keys:['event reminders'] },
    { label:'Periodic Reminders', target:'/autoReminders',   section:'general', cat:'Communications',         keys:['periodic reminders'] },
    { label:'One-Time Reminders', target:'/oneReminders',    section:'general', cat:'Communications',         keys:['one time reminders','one-time reminders'] },
    // ── Administration ──
    { label:'Certificates',     target:'/certificates',      section:'more', cat:'Administration',            keys:['certificates','certificate'] },
    { label:'Public Screens',   target:'/publicScreens',     section:'more', cat:'Administration',            keys:['public screens','public screen'] },
    { label:'Follow-Ups',       target:'/followups',         section:'more', cat:'Administration',            keys:['follow ups','follow-ups','followups','follow up'] },
    { label:'Help Center',      target:'/helpCenter',        section:'more', cat:'Administration',            keys:['help center','help centre'] },
    { label:'Fund',             target:'/fund',              section:'account-settings', cat:'Administration', keys:['fund','funds'] },
    { label:'Purpose',          target:'/purpose',           section:'account-settings', cat:'Administration', keys:['purpose','purposes'] },
    { label:'Transaction Type', target:'/transactiontype',   section:'account-settings', cat:'Administration', keys:['transaction type'] },
    { label:'Email Settings',   target:'/notifyEmail',       section:'general',           cat:'Administration', keys:['email settings'] },
    // ── Public pages with DYNAMIC identifiers (cid / token / id) ──
    // The URL is discovered at run time: a rendered link if present, else built
    // from configuration data (the live church clientId). Never hardcoded.
    { label:'Kids Check-In',   dynamic:true, base:'/kidsCheckin',       rtype:'cid',   configCid:true, cat:'Public', keys:['kids check in','kids check-in','kids checkin','public kids check in','public kids check-in','public kids checkin'] },
    { label:'Prayer Requests', dynamic:true, base:'/viewPrayerRequest', rtype:'cid',   configCid:true, cat:'Public', keys:['prayer requests','public prayer requests','prayer request'] },
    { label:'Member Signup',   dynamic:true, base:'/memberSignup',      rtype:'token',                 cat:'Public', keys:['member signup','member sign up','signup page','sign up page'] },
    { label:'Membership Form', dynamic:true, base:'/membershipForm',    rtype:'cid',   configCid:true, cat:'Public', keys:['membership form'] },
    { label:'Event Calendar',  dynamic:true, base:'/viewEventCalendar', rtype:'cid',   configCid:true, cat:'Public', keys:['event calendar','church calendar'] },
    { label:'Donation Page',   dynamic:true, base:'/donate',            rtype:'id',                    cat:'Public', keys:['donation page','donate'] }
  ];

  /* ── Role / permission model (mirrors shell.js + session.js) ──────────────── */
  var ROLE_SECTIONS = (window.CGP_ROLE_SECTIONS) || {
    'SuperAdmin': ['admin','accounting','general','more','members','activity'],
    'Church':     ['admin','accounting'],
    'Admin':      ['admin','accounting','general','more','members','activity'],
    'Accountant': ['accounting','general','more','admin','members','activity'],
    'User':       ['general','more','members','activity'],
    'Member':     ['general','members','activity','more']
  };
  var session = { role: (localStorage.getItem('role') || 'User'), church: false, ready:false };

  function allowedSections() {
    if (session.church) return ['admin','accounting'];
    return ROLE_SECTIONS[session.role] || ['general','more','help'];
  }
  // Is this destination available to the current user? (faithful to the nav filters)
  function isAvailable(d) {
    if (d.placeholder) return false;
    if (d.dynamic) return (publicLinks === null) ? true : !!findPublicLink(d.base);   // active public link exists
    var role = session.role, church = session.church;
    if (d.section === 'admin-settings')   return !church && (role === 'Admin' || role === 'SuperAdmin');
    if (d.section === 'account-settings')  return !church && (role === 'Accountant' || role === 'SuperAdmin');
    if (d.section === 'reminders')         return allowedSections().indexOf('general') !== -1;

    if (allowedSections().indexOf(d.section) === -1) return false;

    // ── Church: admin→Users/Requests/Stripe/WhatsApp/Payroll, accounting→Payroll only ──
    if (church) {
      if (d.section === 'admin')      return d.usersOnly || d.churchSub;          // Users + Membership Requests (Stripe/WhatsApp not voice-mapped)
      if (d.section === 'accounting') return !!d.payroll;
      return false;
    }
    // ── Users link is church-only for everyone else ──
    if (d.usersOnly) return false;
    // ── Accountant: inside Admin, only Families ──
    if (role === 'Accountant' && d.section === 'admin') return !!d.adminFamily;
    // ── Admin: inside Accounting, only Payroll ──
    if (role === 'Admin' && d.section === 'accounting') return !!d.payroll;
    return true;
  }

  /* ── Command matching ─────────────────────────────────────────────────────── */
  var LEAD_RE = /^(?:please\s+)?(?:can you\s+|could you\s+)?(?:go to|goto|navigate to|take me to|open up|open|show me|show|launch|visit|move to)\s+/i;
  function norm(s){ return ' ' + String(s||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').replace(/\s+/g,' ').trim() + ' '; }
  function esc(s){ return s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'); }
  // Longest whole-phrase key match wins (so "income report" beats "income").
  // Also matches no-space variants ("TaxReport" / "taxreport" → "tax report").
  function matchDest(text) {
    var t = norm(text), tNoSp = t.replace(/ /g, ''), best = null, bestLen = 0;
    DESTS.forEach(function (d) {
      d.keys.forEach(function (k) {
        if (k.length <= bestLen) return;
        var spaced = new RegExp('(^| )' + esc(k) + '( |$)').test(t);
        var noSpace = tNoSp.indexOf(k.replace(/ /g, '')) !== -1;   // fallback for run-together speech
        if (spaced || noSpace) { best = d; bestLen = k.length; }
      });
    });
    return best;
  }
  // "open … in a new tab / as a new tab / in another tab / separate tab / new window"
  var NEWTAB_RE = /\b(?:in|as|on|into)?\s*(?:a|an|another|the|one)?\s*(?:new|another|separate|different)\s+(?:tab|window)\b/i;
  function wantsNewTab(text){ return NEWTAB_RE.test(' ' + String(text||'') + ' '); }
  function stripNewTab(text){ return String(text||'').replace(NEWTAB_RE, ' ').replace(/\s+/g, ' ').trim(); }
  // Open a resolved URL in the requested mode. New tab leaves the current page unchanged.
  function go(url, label, newTab){
    if (newTab){
      var w = null; try { w = window.open(url, '_blank'); } catch(e){}
      if (!w) setDebug({ result:'Pop-up blocked — allow pop-ups for this site to open new tabs' });
      speak('Opening ' + label + ' in a new tab.');
    } else {
      navigating = true;
      speak('Opening ' + label + '.', function(){ setTimeout(function(){ window.location.href = url; }, 150); });
    }
  }
  /* ── Dynamic public-page resolution via the Public Screens module ──────────
   * Public pages (Membership Form, Member Signup, Kids Check-In, Prayer Requests,
   * Event Calendar, Donation, …) carry a generated token stored in the
   * public_screen_link table. The ONLY correct destination is the URL shown in
   * Public Screens → Generated Links → Public URL — for the ACTIVE record
   * (revoked=false AND expiration_date >= today). We read it from
   * /api/public-screens and never construct or substitute a cid value. */
  function pathOf(u){ try { var a=document.createElement('a'); a.href=u; return a.pathname; } catch(e){ return String(u||'').split('?')[0].split('#')[0]; } }
  function relUrl(u){ try { var a=document.createElement('a'); a.href=u; return a.pathname + a.search; } catch(e){ return String(u||''); } }
  function maskToken(t){ t=String(t||''); return t.length<=8 ? '********' : (t.slice(0,4)+'…'+t.slice(-4)); }
  function detectType(url, d){
    if(/[?&]token=/.test(url)) return 'token';
    if(/[?&]cid=/.test(url))   return 'cid';
    if(/\/donate\//.test(url) || pathOf(url).indexOf(((d&&d.base)||'')+'/')===0) return 'id';
    return (d && d.rtype) || 'dynamic';
  }

  // Cached generated public links (refreshed before each public navigation).
  var publicLinks = null;   // null = not loaded yet; [] = loaded, none active
  function loadPublicLinks(){
    return fetch('/api/public-screens').then(function(r){ return r.ok ? r.json() : []; }).then(function(j){
      var list = Array.isArray(j) ? j : ((j && j.data) || []);
      publicLinks = list.filter(function(x){ return x && x.pageUrl; });
      return publicLinks;
    }).catch(function(){ if(publicLinks===null) publicLinks=[]; return publicLinks; });
  }
  function expirationValid(l){
    if(!l.expirationDate) return true;                          // no expiry → always valid
    var ed = new Date(String(l.expirationDate).slice(0,10) + 'T00:00:00');
    var today = new Date(); today.setHours(0,0,0,0);
    return ed >= today;                                          // expiration_date >= CURRENT_DATE
  }
  // The active public_screen_link for `base` (revoked=false AND not expired).
  function findPublicLink(base){
    var list = publicLinks || [];
    for(var i=0;i<list.length;i++){
      var l = list[i];
      if(String(l.pageUrl) !== base) continue;
      if(l.revoked === true) continue;
      if(!expirationValid(l)) continue;
      return l;                                                  // list is newest-first
    }
    return null;
  }
  // Resolve a dynamic destination from its active public link. Returns
  // {url, rtype, token, revoked, expValid} or null when no active link exists.
  function resolveDynamic(d){
    var l = findPublicLink(d.base);
    if(!l) return null;
    // Use the exact Public URL the Public Screens page shows; fall back to the
    // server's per-page convention only if publicUrl is missing.
    var url = l.publicUrl ? relUrl(l.publicUrl)
            : (d.base + (d.rtype==='token' ? '?token=' : '?cid=') + encodeURIComponent(l.token||''));
    return { url:url, rtype: detectType(url, d), token: l.token, revoked:false, expValid:true };
  }

  // "switch account to admin" / "switch to member" → role name
  function matchAccountSwitch(text) {
    var t = norm(text);
    if (!/\bswitch\b/.test(t)) return null;
    var roles = [['super admin','Super Admin'],['superadmin','Super Admin'],['administrator','Admin'],['admin','Admin'],['accountant','Accountant'],['member','Member']];
    for (var i=0;i<roles.length;i++){ if (new RegExp('(^| )' + esc(roles[i][0]) + '( |$)').test(t)) return roles[i][1]; }
    return null;
  }

  /* ── Spoken responses (TTS) ──────────────────────────────────────────────── */
  var lastSpoken = '';
  function ttsSupported(){ return 'speechSynthesis' in window; }
  function speak(text, after){
    lastSpoken = text;
    if (recorder && recorder.setMuted) recorder.setMuted(true);
    if (!ttsSupported()){ if (recorder && recorder.setMuted) recorder.setMuted(false); if (after) after(); return; }
    try {
      window.speechSynthesis.cancel();
      var u = new SpeechSynthesisUtterance(text); u.rate = 1.03;
      var done=false; function fin(){ if(done) return; done=true; if (recorder && recorder.setMuted) recorder.setMuted(false); if (after) after(); }
      u.onend=fin; u.onerror=fin; window.speechSynthesis.speak(u);
      setTimeout(fin, Math.min(9000, 900 + text.length*70));
    } catch(e){ if (recorder && recorder.setMuted) recorder.setMuted(false); if (after) after(); }
  }

  /* ── Execute a recognised command ────────────────────────────────────────── */
  var navigating = false;
  function processCommand(raw) {
    var text = (raw || '').trim();
    setDebug({ transcript: text, command: text, intent:'', dynamic:'', rtype:'', target:'',
               publicFound:'', revoked:'', expValid:'', token:'', openMode:'', allowed:'', url:'', result:'' });
    if (!text) return;

    // 1) Additional / control commands (never navigate)
    var t = norm(text);
    if (/\b(help|show commands|show me commands|what can i say|list commands|open guide|voice commands)\b/.test(t)) {
      setDebug({ intent:'Command', target:'Guide', result:'Opened command guide' });
      openGuide(); speak('Here are the commands you can say. You can search the list, or just say a page name.'); return;
    }
    if (/\b(open debug|open debug panel|show debug)\b/.test(t)) { setDebug({intent:'Command', result:'Debug opened'}); openDebug(); speak('Debug panel opened.'); return; }
    if (/\b(close debug|close debug panel|hide debug)\b/.test(t)) { setDebug({intent:'Command', result:'Debug closed'}); closeDebug(); speak('Debug panel closed.'); return; }
    if (/\b(repeat|say again|come again)\b/.test(t)) { setDebug({intent:'Command', result:'Repeated'}); speak(lastSpoken || 'Say a page name, like “go to Income”.'); return; }
    if (/\b(stop listening|stop voice|stop)\b/.test(t)) { setDebug({intent:'Command', result:'Stopped listening'}); stopListening(); return; }
    if (/\b(start listening|start voice|resume listening)\b/.test(t)) { setDebug({intent:'Command', result:'Started listening'}); startListening(); return; }

    // 2) Account switching
    var sw = matchAccountSwitch(text);
    if (sw) {
      setDebug({ intent:'Switch Account', target: sw });
      var item = findSwitchItem(sw);
      if (!item) { setDebug({ allowed:'No', result:'Account type not available' }); speak('That account type is not available for switching.'); return; }
      setDebug({ allowed:'Yes', result:'Switching account' });
      speak('Switching account to ' + sw + '.', function(){ try { item.click(); } catch(e){} });
      return;
    }

    // 3) Page navigation — detect "new tab" and strip it before matching.
    var newTab = wantsNewTab(text);
    var navText = stripNewTab(text);
    var cleaned = navText.replace(LEAD_RE, '').trim() || navText;
    var d = matchDest(cleaned) || matchDest(navText);
    var openMode = newTab ? 'New Tab' : 'Current Tab';
    if (!d) { setDebug({ intent:'Unknown', dynamic:'No', openMode: openMode, result:'No matching page' }); speak('Sorry, I could not find a page for “' + text + '”.'); return; }

    // ── Dynamic public page → resolve through the active public_screen_link ──
    if (d.dynamic) {
      setDebug({ intent:'Navigation', target:d.label, dynamic:'Yes', rtype:(d.rtype||'—'), openMode: openMode, result:'Looking up public link…' });
      loadPublicLinks().then(function(){
        var res = resolveDynamic(d);
        if (!res) {
          setDebug({ publicFound:'No', revoked:'—', expValid:'—', token:'—', allowed:'No', result:'No active public link' });
          speak('No active public link is available for this page.');
          return;
        }
        setDebug({ publicFound:'Yes', revoked:'False', expValid:'Yes', token: maskToken(res.token),
                   rtype: res.rtype, url: res.url, openMode: openMode, allowed:'Yes', result:'Navigation Successful' });
        go(res.url, d.label, newTab);
      });
      return;
    }

    // ── Static page ──
    setDebug({ intent:'Navigation', target: d.label, dynamic:'No', rtype:'', openMode: openMode, url: d.placeholder ? '(not built)' : d.target });
    if (d.placeholder) { setDebug({ allowed:'—', result:'Page not available yet' }); speak(d.label + ' is not available yet.'); return; }
    if (!isAvailable(d)) { setDebug({ allowed:'No', result:'Not available for current account' }); speak('That page is not available for your current account.'); return; }

    setDebug({ allowed:'Yes', openMode: openMode, result:'Navigation Successful' });
    go(d.target, d.label, newTab);
  }

  // Find a visible, switchable account-switcher item whose role matches `roleName`.
  function findSwitchItem(roleName) {
    var menu = document.getElementById('rsSwitcherMenu');
    if (!menu) return null;
    var want = roleName.toLowerCase();
    var items = menu.querySelectorAll('.rs-item');
    for (var i=0;i<items.length;i++){
      var it = items[i];
      if (it.classList.contains('rs-current') || it.classList.contains('rs-no-login')) continue;
      var roleEl = it.querySelector('.rs-role');
      var roleTxt = (roleEl ? roleEl.textContent : it.textContent || '').toLowerCase();
      if (roleTxt.indexOf(want) !== -1 || (want === 'super admin' && roleTxt.indexOf('superadmin') !== -1)) return it;
    }
    return null;
  }

  /* ════════════════════════ UI ════════════════════════ */
  var recorder = null, panel = null, voiceBtn = null, converseBtn = null, converse = false;

  function $(id){ return document.getElementById(id); }
  function injectStyles() {
    if ($('home-voice-style')) return;
    var s = document.createElement('style'); s.id = 'home-voice-style';
    s.textContent = [
      '.hv-bar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:10px;}',
      '.hv-btn{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:8px;border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-size:13px;font-weight:600;cursor:pointer;}',
      '.hv-btn:hover{background:#673147;color:#fff;border-color:#673147;}',
      '.hv-btn.active{background:#e53935;color:#fff;border-color:#e53935;animation:hvPulse 1.1s infinite;}',
      '.hv-btn[disabled]{opacity:.5;cursor:not-allowed;}',
      '.hv-btn.alt{border-color:#c7c0d7;color:#41506b;}',
      '@keyframes hvPulse{50%{opacity:.62;}}',
      '#hvPanel{margin-top:12px;border:1px solid #e6dde2;border-radius:10px;background:#fbf7f9;padding:12px 14px;font-size:13px;color:#444;}',
      '#hvPanel.hidden{display:none;}',
      '#hvStatus{font-weight:700;color:#673147;}',
      '#hvTranscript{margin-top:6px;font-style:italic;color:#555;min-height:18px;}',
      '#hvGuide,#hvDebug{margin-top:10px;border-top:1px dashed #e0d3da;padding-top:10px;}',
      '#hvGuide.hidden,#hvDebug.hidden{display:none;}',
      '#hvGuideSearch{width:100%;box-sizing:border-box;padding:7px 10px;border:1px solid #d7c9d0;border-radius:7px;font-size:13px;margin-bottom:8px;}',
      '.hv-cat{font-weight:700;color:#673147;margin:8px 0 3px;font-size:12.5px;}',
      '.hv-cmd{display:inline-block;margin:2px 6px 2px 0;padding:3px 9px;border:1px solid #e0d3da;border-radius:999px;background:#fff;font-size:12px;color:#444;cursor:pointer;}',
      '.hv-cmd:hover{background:#673147;color:#fff;border-color:#673147;}',
      '.hv-cmd.hv-na{opacity:.42;}',
      '#hvDebug{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;background:#11151c;color:#cfe3ff;border-radius:8px;padding:10px 12px;border-top:none;}',
      '#hvDebug .hv-drow{display:flex;gap:8px;padding:1px 0;}',
      '#hvDebug .hv-dk{color:#7fd1ff;min-width:120px;}',
      '#hvDebug .hv-dv{color:#cfe3ff;word-break:break-word;}'
    ].join('');
    document.head.appendChild(s);
  }

  function buildPanel() {
    panel = document.createElement('div'); panel.id = 'hvPanel'; panel.className = 'hidden';
    panel.innerHTML =
      '<div id="hvStatus">🎙 Voice navigation ready</div>' +
      '<div id="hvTranscript"></div>' +
      '<div id="hvGuide" class="hidden"></div>' +
      '<div id="hvDebug" class="hidden"></div>';
    return panel;
  }

  function setStatus(msg){ var s=$('hvStatus'); if(s) s.textContent = msg; showPanel(); }
  function setTranscript(t){ var s=$('hvTranscript'); if(s) s.textContent = t ? ('You said: “'+t+'”') : ''; }
  function showPanel(){ if(panel) panel.classList.remove('hidden'); }

  // ── Debug panel ──
  var dbg = { status:'Idle', transcript:'', command:'', intent:'', dynamic:'', rtype:'', target:'',
              publicFound:'', revoked:'', expValid:'', token:'', openMode:'', allowed:'', url:'', result:'' };
  function setDebug(patch){ Object.keys(patch||{}).forEach(function(k){ dbg[k]=patch[k]; }); renderDebug(); if(patch && patch.transcript!=null) setTranscript(patch.transcript); }
  function renderDebug(){
    var el=$('hvDebug'); if(!el) return;
    var rows=[['Voice Status',dbg.status],['Voice Command',dbg.command],['Transcript',dbg.transcript],
      ['Intent',dbg.intent],['Target Page',dbg.target],['Dynamic Route Detected',dbg.dynamic],['Route Type',dbg.rtype],
      ['Public Link Found',dbg.publicFound],['Revoked',dbg.revoked],['Expiration Valid',dbg.expValid],['Token',dbg.token],
      ['Open Mode',dbg.openMode],['URL Generated',dbg.url],['Navigation Allowed',dbg.allowed],['Result',dbg.result]];
    el.innerHTML = rows.map(function(r){ return '<div class="hv-drow"><span class="hv-dk">'+r[0]+'</span><span class="hv-dv">'+(r[1]||'—')+'</span></div>'; }).join('');
  }
  function openDebug(){ var el=$('hvDebug'); if(el){ el.classList.remove('hidden'); renderDebug(); } showPanel(); }
  function closeDebug(){ var el=$('hvDebug'); if(el) el.classList.add('hidden'); }
  function toggleDebug(){ var el=$('hvDebug'); if(el && el.classList.contains('hidden')) openDebug(); else closeDebug(); }

  // ── Guide panel (searchable command list) ──
  function buildGuide(){
    var el=$('hvGuide'); if(!el) return;
    var cats=['Profile','Member','Admin','Finance','Reports','Payroll','Ministry','Communications','Administration','Public'];
    var html='<input id="hvGuideSearch" type="text" placeholder="Search commands… (e.g. income, payroll, family)">'
      + '<div style="font-size:11.5px;color:#888;margin-bottom:6px;">Click a command to run it, or say it aloud. Greyed-out items aren’t available for your account.</div>';
    cats.forEach(function(cat){
      var items=DESTS.filter(function(d){ return d.cat===cat; });
      if(!items.length) return;
      html += '<div class="hv-cat" data-cat="'+cat+'">'+cat+'</div><div class="hv-catrow">';
      items.forEach(function(d){
        var na = (!d.placeholder && !isAvailable(d)) || d.placeholder;
        html += '<span class="hv-cmd'+(na?' hv-na':'')+'" data-label="'+d.label.toLowerCase()+'" data-keys="'+d.keys.join(' ')+'" data-target="'+(d.target||'')+'">'+d.label+'</span>';
      });
      html += '</div>';
    });
    html += '<div class="hv-cat">Account Switch</div><div>'
      + '<span class="hv-cmd" data-sw="Super Admin">Switch Account to Super Admin</span>'
      + '<span class="hv-cmd" data-sw="Admin">Switch Account to Admin</span>'
      + '<span class="hv-cmd" data-sw="Accountant">Switch Account to Accountant</span>'
      + '<span class="hv-cmd" data-sw="Member">Switch Account to Member</span></div>'
      + '<div class="hv-cat">More</div><div>'
      + '<span class="hv-cmd" data-cmd="help">Help</span><span class="hv-cmd" data-cmd="repeat">Repeat</span>'
      + '<span class="hv-cmd" data-cmd="stop listening">Stop Listening</span><span class="hv-cmd" data-cmd="start listening">Start Listening</span></div>';
    el.innerHTML = html;
    var search=$('hvGuideSearch');
    if(search) search.addEventListener('input', function(){
      var q=this.value.toLowerCase().trim();
      el.querySelectorAll('.hv-cmd').forEach(function(c){
        var hay=(c.getAttribute('data-label')||'')+' '+(c.getAttribute('data-keys')||'')+' '+c.textContent.toLowerCase();
        c.style.display = (!q || hay.indexOf(q)>=0) ? '' : 'none';
      });
      el.querySelectorAll('.hv-cat,.hv-catrow').forEach(function(g){ /* keep headers */ });
    });
    el.querySelectorAll('.hv-cmd').forEach(function(c){
      c.addEventListener('click', function(){
        if(c.getAttribute('data-sw')) return processCommand('switch account to '+c.getAttribute('data-sw'));
        if(c.getAttribute('data-cmd')) return processCommand(c.getAttribute('data-cmd'));
        processCommand(c.textContent);
      });
    });
  }
  function openGuide(){ var el=$('hvGuide'); if(el){ el.classList.remove('hidden'); buildGuide(); } showPanel(); }
  function closeGuide(){ var el=$('hvGuide'); if(el) el.classList.add('hidden'); }
  function toggleGuide(){ var el=$('hvGuide'); if(el && el.classList.contains('hidden')) openGuide(); else closeGuide(); }

  // ── Listening control ──
  function setListening(on){
    if(voiceBtn){ voiceBtn.classList.toggle('active', !!on); voiceBtn.innerHTML = on ? '⏹ Stop' : '🎤 Voice'; }
    if(converseBtn && !on){ converseBtn.classList.remove('active'); converseBtn.innerHTML='🗣 Converse'; }
    dbg.status = on ? 'Listening' : 'Idle'; renderDebug();
    setStatus(on ? '🎙 Listening… say a command, e.g. “Go to Income”.' : '🎙 Voice navigation ready');
  }
  function startListening(){ if(recorder && !recorder.isRecording()) recorder.start(); }
  function stopListening(){ converse=false; if(recorder && recorder.isRecording()) recorder.stop(); setListening(false); }

  // The text shown when the server refuses voice (403): the server's reason, never
  // a generic "limit reached" unless the quota gate said so.
  function voiceRefusalMessage(st, why){
    var reason = (why && why.reason) || '';
    if (reason === 'LIMIT' || (!reason && st && st.voiceAvailable === false && st.voiceEnabled !== false)) return 'Voice limit reached. Please contact your administrator.';
    if (reason === 'VOICE_OFF' || (st && st.voiceEnabled === false)) return 'Voice is disabled by your administrator.';
    if (why && why.error) return why.error;
    return 'Voice is not available for this login. Please contact your administrator.';
  }
  function disableVoice(msg){
    [voiceBtn,converseBtn].forEach(function(b){ if(b){ b.disabled=true; b.title=msg||'Voice unavailable'; } });
    if(msg) setStatus('🔇 '+msg);
  }

  function injectUI() {
    var bar = document.querySelector('.ai-search-bar');
    var wrap = document.querySelector('.ai-search-wrap') || (bar && bar.parentNode);
    var host = wrap || document.querySelector('.ai-search-section') || document.body;
    if (!host) return false;
    injectStyles();

    recorder = (window.VoiceOpenAI) ? window.VoiceOpenAI.createRecorder({
      context:      'home',
      onState:      function(on){ setListening(on); },
      onStatus:     function(m,k){ if(k==='error') setStatus('⚠️ '+m); },
      onTranscript: function(t){ if(navigating) return; setTranscript(t); dbg.status='Speaking'; renderDebug(); processCommand(t); if(!converse) { /* single-shot keeps listening until auto-stop */ } },
      onDisabled:   function(st, why){ disableVoice(voiceRefusalMessage(st, why)); }
    }) : null;

    var row = document.createElement('div'); row.className = 'hv-bar';
    // NOTE: the 🎤 Voice and 🗣 Converse controls now live in the AI Search
    // assistant bar (home-assist.js) so that voice/converse clearly belong to
    // "AI Search". This Home Voice bar keeps only the navigation command Guide
    // and the Debug panel. The voiceBtn/converseBtn elements are still created
    // (detached) so the shared status/disable helpers below have stable targets.
    voiceBtn = mkBtn('🎤 Voice', 'Moved to AI Search');
    converseBtn = mkBtn('🗣 Converse', 'Moved to AI Search', 'alt');
    var guideBtn = mkBtn('❔ Guide', 'Show the voice command guide', 'alt');
    var debugBtn = mkBtn('🐞 Debug', 'Show the voice debug panel', 'alt');

    if (!recorder || !recorder.supported) {
      disableVoice('Voice navigation needs a recent Chrome, Edge, or Safari.');
    }
    guideBtn.addEventListener('click', toggleGuide);
    debugBtn.addEventListener('click', toggleDebug);

    // Only Guide + Debug are surfaced here; Voice + Converse are in AI Search.
    row.appendChild(guideBtn); row.appendChild(debugBtn);
    host.appendChild(row);
    host.appendChild(buildPanel());
    return true;
  }
  function mkBtn(label, title, cls){
    var b=document.createElement('button'); b.type='button'; b.className='hv-btn'+(cls?(' '+cls):''); b.innerHTML=label; b.title=title||''; return b;
  }

  /* ── Bootstrap ───────────────────────────────────────────────────────────── */
  function loadSession(){
    return fetch('/api/session').then(function(r){ return r.ok?r.json():{}; }).then(function(s){
      session.role = s.role || localStorage.getItem('role') || 'User';
      session.church = (s.church === true || s.church === 'true');
      session.ready = true;
    }).catch(function(){ session.role = localStorage.getItem('role') || 'User'; session.ready = true; });
  }

  function init(){
    if(!injectUI()) return;
    loadSession();
    loadPublicLinks();   // preload generated public links so the Guide can show availability
    if(window.VoiceOpenAI){
      window.VoiceOpenAI.getStatus().then(function(s){
        if(s && s.voiceAvailable === false){
          disableVoice(s.voiceEnabled === false ? 'Voice is disabled by your administrator.' : 'Voice limit reached. Please contact your administrator.');
        }
      });
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(init, 400); });
  else setTimeout(init, 400);

  // Exposed for testing / external use.
  window.CGP_HOME_VOICE = { matchDest: matchDest, matchAccountSwitch: matchAccountSwitch, isAvailable: isAvailable,
                            _dests: DESTS, _setSession: function(r,c){ session.role=r; session.church=!!c; } };
})();
