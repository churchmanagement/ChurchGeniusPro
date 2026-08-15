/* ============================================================================
 * ai-assistant.js — Global AI Search Assistant header (all authenticated pages).
 *
 * A single, self-contained component (no per-page wiring): loaded once by
 * shell.js, it injects a consistent toolbar + search input + results panel on
 * every page and answers questions about navigation, reports, features, help,
 * workflows and voice commands — with Type / Voice / Converse modes, a Guide,
 * and a Debug panel. It supersedes the home-only home-voice.js + home-assist.js.
 *
 * Knowledge layers (role-aware — never recommends a page the user can't open):
 *   • Navigation Guide — DESTS + isAvailable + matchDest (role/section gating).
 *   • Reports Guide    — what each report contains / is used for + where it lives.
 *   • Help Center / LLM — free-text & how-to → /api/ai-assist (grounded), with a
 *     fallback to /api/help/search, and (on Home) the page's own data search.
 *
 * Public pages resolve their live token via /api/public-screens (never hardcoded).
 * Mode, debug visibility and conversation context persist across navigation.
 * ========================================================================== */
(function () {
  'use strict';
  if (window.__CGP_ASSISTANT__) return;            // single instance per page
  window.__CGP_ASSISTANT__ = true;

  /* ════════════════════════ Navigation knowledge ════════════════════════ */
  var DESTS = [
    { label:'My Family',      target:'/memberHome?tab=family',       section:'members', cat:'Profile', keys:['my family','family'] },
    { label:'Contributions',  target:'/memberHome?tab=give',         section:'members', cat:'Profile', keys:['contributions','my contributions','giving'] },
    { label:'Groups',         target:'/memberHome?tab=groups',       section:'members', cat:'Member', keys:['groups','my groups'] },
    { label:'Classes',        target:'/memberHome?tab=sundaySchool', section:'members', cat:'Member', keys:['classes','class','sunday school'] },
    { label:'Volunteer',      target:'/memberHome?tab=volunteer',    section:'members', cat:'Member', keys:['volunteer','volunteering'] },
    { label:'Directory',      target:'/memberHome?tab=directory',    section:'members', cat:'Member', keys:['directory','member directory'] },
    { label:'Families',       target:'/viewfamily',          section:'admin', cat:'Admin', adminFamily:true,  keys:['families','admin families','view families'] },
    { label:'Admin Groups',   target:'/groups',              section:'admin', cat:'Admin', keys:['admin groups'] },
    { label:'Membership Requests', target:'/membershipRequests', section:'admin', cat:'Admin', churchSub:true, keys:['membership requests','membership request'] },
    { label:'Unsubscribed List',   target:'/unsubscribed-list',  section:'admin', cat:'Admin', keys:['unsubscribed list','unsubscribed'] },
    { label:'Users',          target:'/viewusers',           section:'admin', cat:'Admin', churchSub:true, usersOnly:true, keys:['users','view users','user management','manage users'] },
    { label:'Income',         target:'/income',              section:'accounting', cat:'Finance', keys:['income'] },
    { label:'Expense',        target:'/expense',             section:'accounting', cat:'Finance', keys:['expense','expenses'] },
    { label:'Bank Import',    target:'/bank-import',         section:'accounting', cat:'Finance', keys:['bank import','import bank','bank statement'] },
    { label:'Pledges',        target:'/pledges',             section:'accounting', cat:'Finance', keys:['pledges','pledge'] },
    { label:'Donation',       target:'/donation-review',     section:'accounting', cat:'Finance', keys:['donation','donations','donation review'] },
    { label:'Reports',            target:'/accountingReports',  section:'accounting', cat:'Reports', keys:['reports','report','accounting reports'] },
    { label:'Income Report',      target:'/income-report',      section:'accounting', cat:'Reports', keys:['income report'] },
    { label:'Expense Report',     target:'/expense-report',     section:'accounting', cat:'Reports', keys:['expense report'] },
    { label:'Transaction Report', target:'/transactions-report',section:'accounting', cat:'Reports', keys:['transaction report','transactions report'] },
    { label:'Tax Report',         target:'/tax-report',         section:'accounting', cat:'Reports', keys:['tax report'] },
    { label:'Financial Report',   target:'/tax-report',         section:'accounting', cat:'Reports', keys:['financial report'] },
    { label:'Payroll',          target:'/payroll',           section:'accounting', cat:'Payroll', payroll:true, keys:['payroll'] },
    { label:'Employee Payroll', target:'/payroll/employees', section:'accounting', cat:'Payroll', payroll:true, keys:['employee payroll','employees payroll'] },
    { label:'Payroll Runs',     target:'/payroll/runs',      section:'accounting', cat:'Payroll', payroll:true, keys:['payroll runs','payroll run'] },
    { label:'Payroll Reports',  target:'/payroll/reports',   section:'accounting', cat:'Payroll', payroll:true, keys:['payroll reports'] },
    { label:'Payroll Activity', target:'/payroll/activity',  section:'accounting', cat:'Payroll', payroll:true, keys:['payroll activity'] },
    { label:'Meetings',         target:'/meetings',          section:'general', cat:'Ministry', keys:['meetings','meeting'] },
    { label:'Events',           target:'/events',            section:'general', cat:'Ministry', keys:['events','event'] },
    { label:'Ministry',         target:'/ministry',          section:'general', cat:'Ministry', keys:['ministry'] },
    { label:'Kids Ministry',    target:'/kidsMinistry',      section:'general', cat:'Ministry', keys:['kids ministry','children ministry'] },
    { label:'Worship Planning', target:'/worshipPlanning',   section:'general', cat:'Ministry', keys:['worship planning','worship'] },
    { label:'Prayer Ministry',  target:'/prayerRequest',     section:'general', cat:'Ministry', keys:['prayer ministry','prayer'] },
    { label:'Email',              target:'/notifyEmail',     section:'general', cat:'Communications', keys:['email','compose email','send email','notify email'] },
    { label:'Reminders',          target:'/reminders',       section:'general', cat:'Communications', keys:['reminders','reminder'] },
    { label:'Event Reminders',    target:'/eventReminders',  section:'general', cat:'Communications', keys:['event reminders'] },
    { label:'Periodic Reminders', target:'/autoReminders',   section:'general', cat:'Communications', keys:['periodic reminders'] },
    { label:'One-Time Reminders', target:'/oneReminders',    section:'general', cat:'Communications', keys:['one time reminders','one-time reminders'] },
    { label:'Certificates',     target:'/certificates',      section:'more', cat:'Administration', keys:['certificates','certificate'] },
    { label:'Public Screens',   target:'/publicScreens',     section:'more', cat:'Administration', keys:['public screens','public screen'] },
    { label:'Follow-Ups',       target:'/followups',         section:'more', cat:'Administration', keys:['follow ups','follow-ups','followups','follow up'] },
    { label:'Help Center',      target:'/helpCenter',        section:'more', cat:'Administration', keys:['help center','help centre'] },
    { label:'Fund',             target:'/fund',              section:'account-settings', cat:'Administration', keys:['fund','funds'] },
    { label:'Purpose',          target:'/purpose',           section:'account-settings', cat:'Administration', keys:['purpose','purposes'] },
    { label:'Transaction Type', target:'/transactiontype',   section:'account-settings', cat:'Administration', keys:['transaction type'] },
    { label:'Email Settings',   target:'/notifyEmail',       section:'general',           cat:'Administration', keys:['email settings'] },
    // Public pages with dynamic identifiers (resolved at run time via /api/public-screens).
    { label:'Kids Check-In',   dynamic:true, base:'/kidsCheckin',       rtype:'cid',   cat:'Public', keys:['kids check in','kids check-in','kids checkin'] },
    { label:'Prayer Requests', dynamic:true, base:'/viewPrayerRequest', rtype:'cid',   cat:'Public', keys:['prayer requests','prayer request'] },
    { label:'Member Signup',   dynamic:true, base:'/memberSignup',      rtype:'token', cat:'Public', keys:['member signup','member sign up','signup page','sign up page'] },
    { label:'Membership Form', dynamic:true, base:'/membershipForm',    rtype:'cid',   cat:'Public', keys:['membership form'] },
    { label:'Event Calendar',  dynamic:true, base:'/viewEventCalendar', rtype:'cid',   cat:'Public', keys:['event calendar','church calendar'] },
    { label:'Donation Page',   dynamic:true, base:'/donate',            rtype:'id',    cat:'Public', keys:['donation page','donate'] }
  ];

  var REPORTS = [
    { label:'Income Report', url:'/income-report', cat:'Reports',
      keys:['income report','giving report','giving history','contributions report','revenue report'],
      contains:'All recorded contributions/income by date, contributor, fund and method.',
      usedFor:'Reviewing giving and income totals over a period; giving history.' },
    { label:'Expense Report', url:'/expense-report', cat:'Reports',
      keys:['expense report','spending report','outgoing report'],
      contains:'All recorded expenses by date, fund, purpose, method and amount.',
      usedFor:'Reviewing spending and outgoing totals over a period.' },
    { label:'Transaction Report', url:'/transactions-report', cat:'Reports',
      keys:['transaction report','transactions report','ledger report','all transactions'],
      contains:'A combined ledger of income and expense transactions.',
      usedFor:'A full money-in/money-out view across the church.' },
    { label:'Tax Report', url:'/tax-report', cat:'Reports',
      keys:['tax report','taxes','for tax','tax purposes','giving statement','contribution statement','year end statement','annual statement'],
      contains:'Tax-deductible giving totals per contributor for the period.',
      usedFor:'Year-end giving statements and tax / charitable-deduction purposes.' },
    { label:'Financial Report', url:'/tax-report', cat:'Reports',
      keys:['financial report','finance report','financial summary'],
      contains:'A financial summary of giving and funds.',
      usedFor:'A high-level financial overview.' },
    { label:'Payroll Reports', url:'/payroll/reports', cat:'Payroll', payroll:true,
      keys:['payroll report','payroll reports','payroll summary','w-2','w2','tax forms'],
      contains:'Payroll run summaries, employee pay totals and W-2 / year-end exports.',
      usedFor:'Reviewing payroll history and producing payroll tax documents.' },
    { label:'Payroll Activity', url:'/payroll/activity', cat:'Payroll', payroll:true,
      keys:['payroll activity','payroll log','payroll audit'],
      contains:'A chronological audit log of payroll actions and runs.',
      usedFor:'Tracking what changed in payroll and when.' },
    { label:'Donation Review', url:'/donation-review', cat:'Finance',
      keys:['donation report','donations report','online donations','review donations','donation review'],
      contains:'Incoming online donations awaiting review/posting.',
      usedFor:'Reviewing and reconciling online donations.' },
    { label:'Attendance', url:null, cat:'Ministry',
      keys:['attendance report','attendance','headcount','check in report','checkin report'],
      contains:'Attendance / check-in counts.',
      usedFor:'Tracking attendance — recorded via Events and Kids Check-In; a dedicated report is coming.' }
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
  var session = { role:(safeLS('role')||'User'), church:(safeLS('church')==='true'), ready:false };
  function safeLS(k){ try{ return localStorage.getItem(k); }catch(e){ return null; } }
  function allowedSections(){ if (session.church) return ['admin','accounting']; return ROLE_SECTIONS[session.role] || ['general','more','help']; }

  function isAvailable(d){
    if (d.placeholder) return false;
    if (d.dynamic) return (publicLinks === null) ? true : !!findPublicLink(d.base);
    var role = session.role, church = session.church;
    if (d.section === 'admin-settings')   return !church && (role === 'Admin' || role === 'SuperAdmin');
    if (d.section === 'account-settings')  return !church && (role === 'Accountant' || role === 'SuperAdmin');
    if (allowedSections().indexOf(d.section) === -1) return false;
    if (church) {
      if (d.section === 'admin')      return d.usersOnly || d.churchSub;
      if (d.section === 'accounting') return !!d.payroll;
      return false;
    }
    if (d.usersOnly) return false;
    if (role === 'Accountant' && d.section === 'admin') return !!d.adminFamily;
    if (role === 'Admin' && d.section === 'accounting') return !!d.payroll;
    return true;
  }

  /* ── Matching ─────────────────────────────────────────────────────────────── */
  var LEAD_RE = /^(?:please\s+)?(?:can you\s+|could you\s+)?(?:go to|goto|navigate to|take me to|open up|open|show me|show|launch|visit|move to)\s+/i;
  function norm(s){ return ' ' + String(s||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').replace(/\s+/g,' ').trim() + ' '; }
  function escRe(s){ return s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'); }
  function matchDest(text){
    var t=norm(text), tNoSp=t.replace(/ /g,''), best=null, bestLen=0;
    DESTS.forEach(function(d){ d.keys.forEach(function(k){
      if (k.length<=bestLen) return;
      var spaced = new RegExp('(^| )'+escRe(k)+'( |$)').test(t);
      var noSpace = tNoSp.indexOf(k.replace(/ /g,''))!==-1;
      if (spaced || noSpace) { best=d; bestLen=k.length; }
    }); });
    return best;
  }
  function destByUrl(url){ for (var i=0;i<DESTS.length;i++){ if (DESTS[i].target===url) return DESTS[i]; } return null; }
  function voiceCmd(label){ return 'Go to ' + label; }
  function reportAvailable(r){ if (!r.url) return true; var d=destByUrl(r.url); return d ? isAvailable(d) : true; }
  function matchReport(text){
    var t = norm(text), best=null, bestLen=0;
    REPORTS.forEach(function(r){ r.keys.forEach(function(k){
      if (k.length<=bestLen) return;
      if (t.indexOf(' '+k+' ')!==-1) { best=r; bestLen=k.length; }
    }); });
    return best;
  }

  var NEWTAB_RE = /\b(?:in|as|on|into)?\s*(?:a|an|another|the|one)?\s*(?:new|another|separate|different)\s+(?:tab|window)\b/i;
  function wantsNewTab(text){ return NEWTAB_RE.test(' '+String(text||'')+' '); }
  function stripNewTab(text){ return String(text||'').replace(NEWTAB_RE,' ').replace(/\s+/g,' ').trim(); }

  // ── Home: a role-based destination (Member→/memberHome, Super Admin / Church→
  //    /viewusers, everyone else→/home). Recognised as its own command because the
  //    target depends on the signed-in role, not a fixed URL. ────────────────────
  var HOME_WORD = /\bhome(?:\s*page)?\b/i;                       // "home", "home page", "homepage"
  var HOME_LEAD = /\b(?:go(?:ing)?|take\s+me|navigate|show|open|launch|return|back|move|head)\b/i;
  function isHomeCommand(text){
    var t = String(text||'');
    if (!HOME_WORD.test(t)) return false;
    if (LEAD_RE.test(t.trim())) return true;                     // "go to / open / show home"
    if (HOME_LEAD.test(t)) return true;                          // "take me home", "return home", "back to home"
    if (mode !== 'type' && /^\s*home(?:\s*page)?\s*$/i.test(t)) return true;  // bare "home" by voice
    return false;
  }
  function homeTarget(){
    // "Home" always resolves to the dashboard/home screen — never the Users
    // management page. Only Members have a distinct home (/memberHome).
    // /viewusers is reached ONLY via an explicit "Users" command.
    var r = String(session.role||'').toLowerCase().replace(/\s+/g,'');
    if (r === 'member') return '/memberHome';
    return '/home';                                              // Super Admin, Admin, Accountant, Church, User
  }
  function detectedRoleLabel(){ return session.church ? 'Church' : (session.role || 'User'); }

  /* ── Public-link resolution ───────────────────────────────────────────────── */
  var publicLinks = null;
  function relUrl(u){ try{ var a=document.createElement('a'); a.href=u; return a.pathname+a.search; }catch(e){ return String(u||''); } }
  function loadPublicLinks(){
    return fetch('/api/public-screens').then(function(r){ return r.ok?r.json():[]; }).then(function(j){
      var list=Array.isArray(j)?j:((j&&j.data)||[]);
      publicLinks = list.filter(function(x){ return x && x.pageUrl; });
      return publicLinks;
    }).catch(function(){ if(publicLinks===null) publicLinks=[]; return publicLinks; });
  }
  function expirationValid(l){
    if(!l.expirationDate) return true;
    var ed=new Date(String(l.expirationDate).slice(0,10)+'T00:00:00'); var t=new Date(); t.setHours(0,0,0,0); return ed>=t;
  }
  function findPublicLink(base){
    var list=publicLinks||[];
    for(var i=0;i<list.length;i++){ var l=list[i];
      if(String(l.pageUrl)!==base) continue;
      if(l.revoked===true) continue;
      if(!expirationValid(l)) continue;
      return l;
    }
    return null;
  }
  function resolvePublic(base){
    return loadPublicLinks().then(function(){
      var l=findPublicLink(base);
      if(!l) return null;
      return l.publicUrl ? relUrl(l.publicUrl) : (base + '?token=' + encodeURIComponent(l.token||''));
    });
  }

  /* ── Account switching ────────────────────────────────────────────────────── */
  function matchAccountSwitch(text){
    var t=norm(text); if(!/\bswitch\b/.test(t)) return null;
    var roles=[['super admin','Super Admin'],['superadmin','Super Admin'],['administrator','Admin'],['admin','Admin'],['accountant','Accountant'],['member','Member']];
    for(var i=0;i<roles.length;i++){ if(new RegExp('(^| )'+escRe(roles[i][0])+'( |$)').test(t)) return roles[i][1]; }
    return null;
  }
  function findSwitchItem(roleName){
    var menu=document.getElementById('rsSwitcherMenu'); if(!menu) return null;
    var want=roleName.toLowerCase(), items=menu.querySelectorAll('.rs-item');
    for(var i=0;i<items.length;i++){ var it=items[i];
      if(it.classList.contains('rs-current')||it.classList.contains('rs-no-login')) continue;
      var roleEl=it.querySelector('.rs-role'); var roleTxt=(roleEl?roleEl.textContent:it.textContent||'').toLowerCase();
      if(roleTxt.indexOf(want)!==-1 || (want==='super admin'&&roleTxt.indexOf('superadmin')!==-1)) return it;
    }
    return null;
  }

  /* ════════════════════════ Answer engine ════════════════════════ */
  function classify(qRaw){
    var q=String(qRaw||'').toLowerCase().trim();
    var howTo=/\b(how (do|can|to|would)|steps?|tutorial|walk me|guide me|set up|configure|create|add|record|enter|send|register|check ?in)\b/.test(q);
    var where=/\b(where|which|find|locate|show me|go to|open|navigate|take me|what report|what page)\b/.test(q) || /^(go to|open|show)\b/.test(q);
    return { howTo:howTo, where:where };
  }

  function resolveLocal(qRaw){
    var q=String(qRaw||'').trim(); if(!q) return null;
    var c=classify(q);

    var rep=matchReport(q);
    if (rep && (!c.howTo || c.where)){
      if (!reportAvailable(rep))
        return { intent:'report', category:'Reports', source:'Reports Guide', confidence:0.9, entity:rep.label,
                 answer:'The '+rep.label+' is not available for your current account.', pages:[], suggestedAction:'none' };
      var ans=rep.label+' — '+rep.contains+' '+rep.usedFor;
      if (rep.url) ans+=' Location: Reports → '+rep.label+'. URL: '+rep.url+'. Voice command: "'+voiceCmd(rep.label)+'".';
      return { intent:'report', category:rep.cat, source:'Reports Guide', confidence: rep.url?0.95:0.6, entity:rep.label,
               answer:ans,
               pages: rep.url ? [{label:rep.label,url:rep.url,voiceCommand:voiceCmd(rep.label),description:rep.usedFor}] : [],
               suggestedAction: rep.url?'open_page':'none' };
    }

    if (!c.howTo || c.where){
      var d=matchDest(q);
      if (d){
        if (!isAvailable(d))
          return { intent:'navigation', category:d.cat||'', source:'Navigation Guide', confidence:0.9, entity:d.label,
                   answer:'That page ('+d.label+') is not available for your current account.', pages:[], suggestedAction:'none' };
        if (d.dynamic)
          return { intent:'navigation', category:d.cat||'Public', source:'Navigation Guide', confidence:0.85, entity:d.label,
                   answer:d.label+' is a public page. I can open its active public link for you. Voice command: "'+voiceCmd(d.label)+'".',
                   pages:[{label:d.label,url:'__dynamic__:'+d.base,voiceCommand:voiceCmd(d.label),description:'Public-facing page'}],
                   suggestedAction:'open_page' };
        return { intent:'navigation', category:d.cat||'', source:'Navigation Guide', confidence:0.95, entity:d.label,
                 answer:d.label+' is under '+(d.cat||'the menu')+'. URL: '+d.target+'. Voice command: "'+voiceCmd(d.label)+'".',
                 pages:[{label:d.label,url:d.target,voiceCommand:voiceCmd(d.label)}], suggestedAction:'open_page' };
      }
    }
    return null;
  }

  function buildContext(){
    var pages=[];
    DESTS.forEach(function(d){ if(!isAvailable(d)) return;
      pages.push({ label:d.label, url:d.dynamic?(d.base+' (public link — generated on open)'):(d.target||''),
                   voiceCommand:voiceCmd(d.label), category:d.cat||'', description:d.dynamic?'Public-facing page':'' });
    });
    var reports=REPORTS.filter(reportAvailable).map(function(r){
      return { label:r.label, url:r.url||'', voiceCommand:voiceCmd(r.label), contains:r.contains, usedFor:r.usedFor };
    });
    return { role:session.role, currentPage:location.pathname, availablePages:pages, reports:reports };
  }

  /* ════════════════════════ Converse dialogue ════════════════════════ */
  var conv = { active:false, topic:null, step:0, data:{} };
  var _convLog = [];
  function resetConv(){ conv={active:false,topic:null,step:0,data:{}}; persistConv(); }
  function convHistory(){ return _convLog.slice(-6).join('\n'); }

  function converseStart(qRaw){
    var q=String(qRaw||'').toLowerCase();
    if (/remind(er|ers)?\b|reminder/.test(q)){ conv={active:true,topic:'reminders',step:1,data:{}}; persistConv();
      return ask('Sure — what kind of reminder? You can say: Event, Periodic (recurring), or One-Time.'); }
    if (/report|giving|donation/.test(q) && /\b(donation|giving|income|report)\b/.test(q)){ conv={active:true,topic:'report',step:1,data:{}}; persistConv();
      return ask('I can help with reports. Which one do you need: Income, Transaction, Tax, or Financial?'); }
    if (/\b(join|new member|membership|sign ?up|add (a )?family|wants to join)\b/.test(q)){ conv={active:true,topic:'join',step:1,data:{}}; persistConv();
      return ask('Great — how would you like to proceed? You can: Send a Membership Form, open Member Signup, or Create a Family record.'); }
    return null;
  }
  function converseStep(qRaw){
    var q=String(qRaw||'').toLowerCase().trim();
    if (conv.topic==='reminders'){
      if (conv.step===1){
        if (/event/.test(q)){ conv.data.type='event'; conv.step=2; persistConv(); return ask('For the event — should it go out Before, On, or After the event day?'); }
        if (/periodic|recurring|auto/.test(q)) return offerOpen('Periodic Reminders','/autoReminders','Periodic (recurring) reminders');
        if (/one|single|once/.test(q)) return offerOpen('One-Time Reminders','/oneReminders','One-time reminders');
        return ask('Please say Event, Periodic, or One-Time.');
      }
      if (conv.step===2){
        var when=/before/.test(q)?'Before':(/after/.test(q)?'After':'On');
        return offerOpen('Event Reminders','/eventReminders','Event reminders — configure the "'+when+' Days" template');
      }
    }
    if (conv.topic==='report'){
      if (/income/.test(q)) return offerOpen('Income Report','/income-report','the Income Report');
      if (/transaction/.test(q)) return offerOpen('Transaction Report','/transactions-report','the Transaction Report');
      if (/tax/.test(q)) return offerOpen('Tax Report','/tax-report','the Tax Report');
      if (/financial|finance/.test(q)) return offerOpen('Financial Report','/tax-report','the Financial Report');
      return ask('Please choose: Income, Transaction, Tax, or Financial.');
    }
    if (conv.topic==='join'){
      if (/membership form|form/.test(q)) return offerOpenDynamic('Membership Form','/membershipForm','the Membership Form public link');
      if (/sign ?up|member signup/.test(q)) return offerOpenDynamic('Member Signup','/memberSignup','the Member Signup public link');
      if (/family|create/.test(q)) return offerOpen('Families','/viewfamily','the Families page to create a new family');
      return ask('Please choose: Membership Form, Member Signup, or Create a Family.');
    }
    return null;
  }
  function ask(text){
    renderCard({ intent:'converse', category:'Assistant', source:'Assistant', confidence:1, answer:text, pages:[], suggestedAction:'none' });
    setDebug({ intent:'Converse (follow-up)', entity:conv.topic||'—', source:'Assistant', navTarget:'—', confidence:'100%', result:'Awaiting reply' });
    log('Follow-up: '+text);
    if (speaking) speak(text);
    return true;
  }
  function offerOpen(label,url,human){
    resetConv();
    renderCard({ intent:'navigation', category:'Assistant', source:'Navigation Guide', confidence:0.95,
      answer:'Open '+human+'. Would you like me to open it?',
      pages:[{label:label,url:url,voiceCommand:voiceCmd(label)}], suggestedAction:'open_page' });
    setDebug({ intent:'Navigation', entity:label, source:'Navigation Guide', navTarget:url, confidence:'95%', result:'Offered to open' });
    if (speaking) speak('Would you like me to open '+label+'?');
    return true;
  }
  function offerOpenDynamic(label,base,human){
    resetConv();
    renderCard({ intent:'navigation', category:'Public', source:'Navigation Guide', confidence:0.85,
      answer:'I found '+human+'. Would you like me to open it?',
      pages:[{label:label,url:'__dynamic__:'+base,voiceCommand:voiceCmd(label)}], suggestedAction:'open_page' });
    setDebug({ intent:'Navigation', entity:label, source:'Navigation Guide', navTarget:base+' (public link)', confidence:'85%', result:'Offered to open public link' });
    if (speaking) speak('Would you like me to open '+label+'?');
    return true;
  }

  /* ════════════════════════ LLM / help routing ════════════════════════ */
  function runLLM(query){
    setDebug({ openai:'Sending…' });
    var body={ query:query, context:buildContext(), history: conv.active?convHistory():'' };
    return fetch('/api/ai-assist',{ method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body) })
      .then(function(r){ if(r.status===503||r.status===404){ setDebug({openai:'Not configured ('+r.status+')'}); return null; } return r.ok?r.json():null; })
      .catch(function(){ setDebug({openai:'Error'}); return null; });
  }
  function fallbackHelp(query){
    return fetch('/api/help/search',{ method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({query:query}) })
      .then(function(r){ return r.ok?r.json():null; }).catch(function(){ return null; });
  }

  /* ════════════════════════ Main entry ════════════════════════ */
  var lastQ='', _t0=0, _origRunAiSearch=null;
  function runAssist(query){
    var input=document.getElementById('aiSearchInput');
    query=(query!=null?query:(input?input.value:''))||''; query=String(query).trim();
    if(!query){ if(input) input.focus(); return; }
    lastQ=query; _t0=Date.now();
    busy(true);
    setDebug({ page:location.pathname, mode:curMode(), input:query, transcript:(mode!=='type'?query:'—'),
               intent:'…', entity:'—', role:detectedRoleLabel(), source:'—', navTarget:'—', confidence:'—',
               responseMs:'…', openai:'—', result:'…', dynamic:'—', publicFound:'—', openMode:'—' });

    // Explicit "Go to / Open <page>" → navigate immediately (handles new-tab,
    // permissions, dynamic public links). Runs in every mode and pre-empts the
    // Converse follow-up trees so direct commands are never intercepted.
    if (directNav(query)){ if(input) input.value=''; return; }

    // ── Natural-language DATA questions ("When is Anson's birthday?",
    //    "What was the income for March?", "Who are the volunteers?").
    //    Answered server-side with permission checks; runs in ALL modes.
    //    handled=false → fall through to the normal flow below.
    if (looksLikeDataQuestion(query)){
      tryDataSearch(query).then(function(d){
        if (d && d.handled){
          busy(false);
          renderCard({ intent:d.intent||'data', category:'Data', source:'Church Data',
            confidence:1, answer:d.answer||'', pages:[],
            suggestedAction:'none' });
          setDebug({ intent:'Data: '+(d.intent||'lookup'), source:'Church Data',
            result: d.denied ? 'Permission denied' : 'Answered (data)' });
          if (mode==='converse'){ _convLog.push('Assistant: '+(d.answer||'')); persistConv(); }
          if (speaking) speak(stripUrls(d.answer||''));
          finishTiming(); if(input) input.value='';
          return;
        }
        assistFlow(query, input);   // not a data question after all → normal flow
      });
      return;
    }
    assistFlow(query, input);
  }

  function looksLikeDataQuestion(q){
    return /\b(birthday|anniversar\w*|income|revenue|collections?|tithe|contribut\w*|donat\w*|gave|giving|volunteers?|offering)\b/i.test(q);
  }
  function tryDataSearch(query){
    return fetch('/api/ai-search/data', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ query:query })
    }).then(function(r){ return r.ok ? r.json() : null; })
      .catch(function(){ return null; });
  }

  function assistFlow(query, input){
    if (mode==='converse'){
      _convLog.push('User: '+query); persistConv();
      var handled = conv.active ? converseStep(query) : converseStart(query);
      if (handled){ busy(false); finishTiming(); if(input) input.value=''; return; }
      conv.active=true; persistConv();
      runLLM(query).then(function(d){ busy(false);
        if (d){ renderFromLLM(d); _convLog.push('Assistant: '+(d.followUpQuestion||d.answer||'')); persistConv(); }
        else { renderCard({intent:'converse',category:'Assistant',source:'Assistant',confidence:0,
                 answer:'I can help you navigate, find reports, and answer how-to questions. Could you tell me a bit more about what you want to do?',pages:[],suggestedAction:'none'});
               setDebug({intent:'Converse',result:'Awaiting more detail'}); if(speaking) speak('Could you tell me a bit more about what you want to do?'); }
        finishTiming();
      });
      if(input) input.value='';
      return;
    }

    var local=resolveLocal(query);
    if (local){ busy(false); setDebug({openai:'Local (no API call)'}); renderFromLocal(local); if(speaking) speak(stripUrls(local.answer)); finishTiming(); return; }

    var c=classify(query);
    var helpish = c.howTo || c.where || (typeof window._isHelpQuery==='function' && window._isHelpQuery(query));
    if (!helpish && typeof _origRunAiSearch==='function'){
      busy(false); setDebug({intent:'Data lookup',source:'Page search',result:'Delegated to page'}); finishTiming();
      if(input) input.value=query; return _origRunAiSearch();
    }
    runLLM(query).then(function(d){
      if (d){ busy(false); setDebug({openai:'OK'}); renderFromLLM(d); if(speaking) speak(stripUrls(d.answer||'')); finishTiming(); return; }
      return fallbackHelp(query).then(function(h){
        busy(false); setDebug({openai:'Fallback → Help search'});
        if (h && typeof window.renderAiResults==='function'){ window.renderAiResults(h,query);
          setDebug({intent:(h.intent||'help'),entity:'—',source:'Help Center',navTarget:'—',confidence:'—',result:'Help search'}); }
        else renderCard({intent:'help',category:'Help',source:'Help Center',confidence:0,
               answer:(h&&h.answer)||'I couldn’t find an exact answer. The Help Center has more detail.',
               pages:[{label:'Help Center',url:'/helpCenter',voiceCommand:'Go to Help Center'}],suggestedAction:'open_page'});
        finishTiming();
      });
    });
  }
  function finishTiming(){ setDebug({ responseMs:(Date.now()-_t0)+' ms' }); }

  function renderFromLocal(a){
    renderCard(a);
    setDebug({ intent:a.intent, entity:a.entity||'—', category:a.category||'—', source:a.source||'—',
      navTarget:(a.pages&&a.pages[0]?a.pages[0].url:'—'),
      confidence:pct(a.confidence), result:'Answered (local)' });
  }
  function renderFromLLM(d){
    var pages=(d.relevantPages||d.pages||[]).filter(function(p){return p&&p.url;});
    renderCard({ intent:d.intent||'assist', category:d.category||'', source:d.source||'Assistant',
      confidence:(d.confidence!=null?d.confidence:''), answer:d.answer||'', pages:pages,
      suggestedAction:d.suggestedAction||(pages.length?'open_page':'none'), followUp:d.followUpQuestion });
    setDebug({ intent:d.intent||'assist', entity:(pages[0]?pages[0].label:'—'), category:d.category||'—',
      source:d.source||'Assistant', navTarget:(pages[0]?pages[0].url:'—'), confidence:pct(d.confidence),
      result: d.followUpQuestion?'Follow-up asked':'Answered (LLM)' });
  }
  function pct(c){ if(c===''||c==null) return '—'; var n=Number(c); if(isNaN(n)) return String(c); return (n<=1?Math.round(n*100):Math.round(n))+'%'; }
  function stripUrls(s){ return String(s||'').replace(/https?:\/\/\S+/g,'').replace(/URL:\s*\/\S+/g,'').replace(/\s{2,}/g,' ').trim(); }

  /* ════════════════════════ Open a page ════════════════════════ */
  function open(url,label,newTab){
    if (url && url.indexOf('__dynamic__:')===0){
      var base=url.slice('__dynamic__:'.length);
      resolvePublic(base).then(function(rel){
        if(!rel){ setDebug({result:'No active public link'}); toast('No active public link is available for '+label+'.'); if(speaking) speak('No active public link is available for '+label+'.'); return; }
        doOpen(rel,label,newTab);
      }); return;
    }
    doOpen(url,label,newTab);
  }
  function doOpen(url,label,newTab){
    setDebug({ navTarget:url, openMode:(newTab?'New Tab':'Current Tab'), result:'Opening '+label+(newTab?' (new tab)':'') });
    if (newTab){ var w=null; try{ w=window.open(url,'_blank'); }catch(e){} if(!w) toast('Pop-up blocked — allow pop-ups to open new tabs.'); }
    else { if(speaking) speak('Opening '+label+'.'); setTimeout(function(){ window.location.href=url; }, 150); }
  }

  /* ════════════════════════ Direct navigation fast-path ════════════════════════
   * Explicit navigation commands ("Go to / Open / Take me to / Navigate to /
   * Show me / Launch <page>") should navigate immediately — in Voice and Converse
   * modes a matched page name alone is enough, in Type mode an explicit lead verb
   * is required. This runs BEFORE the Converse follow-up trees so commands like
   * "Go to Income Report" are never intercepted by a clarifying question.
   * Returns true when it has fully handled the request. */
  function directNav(text){
    var newTab  = wantsNewTab(text);
    var navText = stripNewTab(text);

    // Home — resolve the role-appropriate home page (never a single hardcoded URL).
    if (isHomeCommand(navText)){
      var homeUrl = homeTarget();
      var hOpen   = newTab ? 'New Tab' : 'Current Tab';
      setDebug({ intent:'Navigation', entity:'Home Page', source:'Navigation Guide', dynamic:'No',
                 role:detectedRoleLabel(), openMode:hOpen, confidence:'98%', navTarget:homeUrl, result:'Success' });
      announceAndGo(homeUrl, 'Home Page', newTab);
      return true;
    }

    var hasLead = LEAD_RE.test(navText.trim());
    var cleaned = navText.replace(LEAD_RE,'').trim() || navText;
    var d = matchDest(cleaned) || matchDest(navText);
    if (!d) return false;
    var c = classify(navText);
    if (!hasLead){
      if (mode === 'type') return false;        // Type needs an explicit command verb
      if (c.howTo || c.where) return false;      // a question, not a command — answer it instead
    }
    var openMode = newTab ? 'New Tab' : 'Current Tab';
    setDebug({ intent:'Navigation', entity:d.label, source:'Navigation Guide',
               dynamic:(d.dynamic?'Yes':'No'), openMode:openMode, confidence:'95%' });

    // Permission check first — never navigate to a page the user can't access.
    if (!isAvailable(d)){
      setDebug({ publicFound:(d.dynamic?'No':'—'), navTarget:'—', result:'Blocked — not available for current account' });
      renderCard({ intent:'navigation', category:d.cat||'', source:'Navigation Guide', confidence:0.9,
                   answer:'That page is not available for your current account.', pages:[], suggestedAction:'none' });
      if (speaking) speak('That page is not available for your current account.');
      busy(false); finishTiming(); return true;
    }

    // Dynamic public page → resolve the active public_screen_link, then open.
    if (d.dynamic){
      setDebug({ result:'Looking up public link…' });
      resolvePublic(d.base).then(function(rel){
        if (!rel){
          setDebug({ publicFound:'No', navTarget:'—', result:'No active public link' });
          renderCard({ intent:'navigation', category:'Public', source:'Navigation Guide', confidence:0,
                       answer:'No active public link is available for '+d.label+'.', pages:[], suggestedAction:'none' });
          if (speaking) speak('No active public link is available for this page.');
          busy(false); finishTiming(); return;
        }
        setDebug({ publicFound:'Yes', navTarget:rel, result:'Success' });
        announceAndGo(rel, d.label, newTab);
      });
      return true;
    }

    // Static page.
    setDebug({ publicFound:'—', navTarget:d.target, result:'Success' });
    announceAndGo(d.target, d.label, newTab);
    return true;
  }

  // Announce "Opening <label> [in a new tab]." then navigate (current or new tab).
  function announceAndGo(url, label, newTab){
    var msg = 'Opening ' + label + (newTab ? ' in a new tab' : '') + '.';
    renderCard({ intent:'navigation', category:'Assistant', source:'Navigation Guide', confidence:0.97,
                 answer:msg, pages:[], suggestedAction:'none' });
    if (speaking) speak(msg);
    busy(false); finishTiming();
    if (newTab){ var w=null; try{ w=window.open(url,'_blank'); }catch(e){} if(!w) toast('Pop-up blocked — allow pop-ups to open new tabs.'); }
    else { setTimeout(function(){ window.location.href = url; }, 350); }
  }

  /* ════════════════════════ TTS ════════════════════════ */
  function speak(text){
    if(!('speechSynthesis' in window)) return;
    try{ if(recorder&&recorder.setMuted) recorder.setMuted(true);
      window.speechSynthesis.cancel();
      var u=new SpeechSynthesisUtterance(String(text||'').slice(0,400)); u.rate=1.03;
      u.onend=u.onerror=function(){ if(recorder&&recorder.setMuted) recorder.setMuted(false); };
      window.speechSynthesis.speak(u);
      setTimeout(function(){ if(recorder&&recorder.setMuted) recorder.setMuted(false); }, 8000);
    }catch(e){ if(recorder&&recorder.setMuted) recorder.setMuted(false); }
  }

  /* ════════════════════════ Rendering ════════════════════════ */
  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
  function results(){ return document.getElementById('aiResultsWrap'); }
  function renderCard(a){
    var wrap=results(); if(!wrap) return;
    var pages=a.pages||[];
    var html='<div class="ai-results-panel">'
      + '<div class="ai-results-hdr"><div class="ai-results-title">✨ Assistant <em style="font-size:12px;color:#aaa;font-weight:400;">for: '+esc(lastQ)+'</em></div>'
      + '<button class="ai-results-close" onclick="document.getElementById(\'aiResultsWrap\').style.display=\'none\'" title="Close">✕</button></div>'
      + '<div class="ai-answer">'+esc(a.answer||'')+'</div>';
    if (pages.length){
      html+='<div style="margin-top:12px;display:flex;flex-direction:column;gap:8px;">';
      pages.forEach(function(p){
        html+='<div class="aa-suggest"><div><div class="aa-suggest-label">'+esc(p.label)+'</div>'
          +(p.description?'<div class="aa-suggest-desc">'+esc(p.description)+'</div>':'')
          +(p.voiceCommand?'<div class="aa-suggest-vc">🎤 “'+esc(p.voiceCommand)+'”</div>':'')+'</div>'
          +'<div class="aa-suggest-actions">'
          +'<button class="aa-open" data-url="'+esc(p.url)+'" data-label="'+esc(p.label)+'" data-nt="0">Open</button>'
          +'<button class="aa-open alt" data-url="'+esc(p.url)+'" data-label="'+esc(p.label)+'" data-nt="1">New tab</button>'
          +'</div></div>';
      });
      html+='</div>';
    }
    if (a.intent!=='converse')
      html+='<div style="margin-top:14px;padding-top:12px;border-top:1px solid #f0eaf2;font-size:12px;color:#888;">Need more detail? <a href="/helpCenter" style="color:#673147;font-weight:700;">Open the full Help Center →</a></div>';
    html+='</div>';
    wrap.innerHTML=html; wrap.style.display='block';
    wrap.querySelectorAll('.aa-open').forEach(function(b){
      b.addEventListener('click', function(){ open(b.getAttribute('data-url'), b.getAttribute('data-label'), b.getAttribute('data-nt')==='1' || wantsNewTab(lastQ)); });
    });
    try{ wrap.scrollIntoView({behavior:'smooth',block:'nearest'}); }catch(e){}
  }
  function toast(msg){
    var wrap=results(); if(!wrap) return;
    var d=document.createElement('div'); d.className='aa-toast'; d.textContent=msg; wrap.style.display='block';
    wrap.appendChild(d); setTimeout(function(){ try{ wrap.removeChild(d); }catch(e){} }, 5000);
  }

  /* ════════════════════════ Guide ════════════════════════ */
  function openGuide(){
    var el=document.getElementById('aaGuide'); if(!el) return;
    if(!el.classList.contains('hidden')){ el.classList.add('hidden'); return; }
    var cats=['Profile','Member','Admin','Finance','Reports','Payroll','Ministry','Communications','Administration','Public'];
    var html='<input id="aaGuideSearch" type="text" placeholder="Search the guide… (income, payroll, family, report)">'
      + '<div class="aa-g-note">Type or speak any of these. Greyed-out items aren’t available for your account.</div>'
      + '<div class="aa-g-cat">Examples</div><div class="aa-g-ex">'
      + '<span class="aa-cmd" data-run="How do I add a contribution?">How do I add a contribution?</span>'
      + '<span class="aa-cmd" data-run="Where is the Tax Report?">Where is the Tax Report?</span>'
      + '<span class="aa-cmd" data-run="How do I create a payroll run?">How do I create a payroll run?</span>'
      + '<span class="aa-cmd" data-run="I need to send reminders">Converse: I need to send reminders</span>'
      + '</div>';
    cats.forEach(function(cat){
      var items=DESTS.filter(function(d){ return d.cat===cat; });
      if(!items.length) return;
      html+='<div class="aa-g-cat">'+cat+'</div><div class="aa-g-row">';
      items.forEach(function(d){
        var na=!isAvailable(d);
        html+='<span class="aa-cmd'+(na?' aa-na':'')+'" data-run="'+esc(voiceCmd(d.label))+'" data-keys="'+esc(d.keys.join(' '))+'">'+esc(d.label)+'</span>';
      });
      html+='</div>';
    });
    html+='<div class="aa-g-cat">Reports</div><div class="aa-g-row">';
    REPORTS.forEach(function(r){ var na=!reportAvailable(r);
      html+='<span class="aa-cmd'+(na?' aa-na':'')+'" data-run="'+esc('Where is the '+r.label+'?')+'" data-keys="'+esc(r.keys.join(' '))+'">'+esc(r.label)+'</span>'; });
    html+='</div>';
    html+='<div class="aa-g-cat">Account Switch</div><div class="aa-g-row">'
      + '<span class="aa-cmd" data-run="switch account to Admin">Switch Account to Admin</span>'
      + '<span class="aa-cmd" data-run="switch account to Accountant">Switch Account to Accountant</span>'
      + '<span class="aa-cmd" data-run="switch account to Member">Switch Account to Member</span></div>';
    el.innerHTML=html; el.classList.remove('hidden');
    var search=document.getElementById('aaGuideSearch');
    if(search) search.addEventListener('input', function(){ var q=this.value.toLowerCase().trim();
      el.querySelectorAll('.aa-cmd').forEach(function(c){ var hay=(c.textContent+' '+(c.getAttribute('data-keys')||'')).toLowerCase();
        c.style.display=(!q||hay.indexOf(q)>=0)?'':'none'; }); });
    el.querySelectorAll('.aa-cmd').forEach(function(c){ c.addEventListener('click', function(){
      var inp=document.getElementById('aiSearchInput'); var run=c.getAttribute('data-run');
      if(inp) inp.value=run; runAssist(run);
    }); });
  }

  /* ════════════════════════ Debug ════════════════════════ */
  var dbg={ page:location.pathname, mode:'Type', input:'', intent:'', entity:'', role:'', source:'', navTarget:'',
            confidence:'', responseMs:'', transcript:'', openai:'', dynamic:'', publicFound:'', openMode:'', result:'' };
  var dbgLog=[];
  function log(){ for(var i=0;i<arguments.length;i++) dbgLog.push(arguments[i]); if(dbgLog.length>12) dbgLog=dbgLog.slice(-12); renderDebug(); }
  function setDebug(p){ Object.keys(p||{}).forEach(function(k){ dbg[k]=p[k]; }); renderDebug(); }
  function renderDebug(){
    var el=document.getElementById('aaDebug'); if(!el) return;
    var rows=[['Current Page',dbg.page],['Search Mode',dbg.mode],['User Command',dbg.input],['Intent',dbg.intent],
      ['Target',dbg.entity],['Detected Role',dbg.role],['Knowledge Source',dbg.source],['Dynamic Link',dbg.dynamic],
      ['Public Link Found',dbg.publicFound],['Open Mode',dbg.openMode],['Resolved URL',dbg.navTarget],
      ['Confidence Score',dbg.confidence],['Response Time',dbg.responseMs],['Voice Transcript',dbg.transcript],
      ['OpenAI Request Status',dbg.openai],['Navigation Result',dbg.result]];
    var html=rows.map(function(r){ return '<div class="aa-drow"><span class="aa-dk">'+r[0]+'</span><span class="aa-dv">'+esc(r[1]||'—')+'</span></div>'; }).join('');
    if(dbgLog.length) html+='<div class="aa-dlog">'+dbgLog.map(function(l){return esc(l);}).join('<br>')+'</div>';
    el.innerHTML=html;
  }

  /* ════════════════════════ Modes ════════════════════════ */
  var mode='type', speaking=false, recorder=null, voiceErr='';
  function curMode(){ return mode.charAt(0).toUpperCase()+mode.slice(1); }
  function busy(on){ var b=document.getElementById('aiSearchBtn'); if(b){ b.disabled=!!on; b.textContent=on?'⏳ Searching…':'Search'; } }
  function persist(){ try{ localStorage.setItem('aa.mode',mode); localStorage.setItem('aa.debug', document.getElementById('aaDebug') && !document.getElementById('aaDebug').classList.contains('hidden') ? '1':'0'); }catch(e){} }
  function persistConv(){ try{ sessionStorage.setItem('aa.conv', JSON.stringify(conv)); sessionStorage.setItem('aa.convlog', JSON.stringify(_convLog.slice(-12))); }catch(e){} }
  function restoreConv(){ try{ var c=JSON.parse(sessionStorage.getItem('aa.conv')||'null'); if(c) conv=c; var l=JSON.parse(sessionStorage.getItem('aa.convlog')||'null'); if(l) _convLog=l; }catch(e){} }

  function setMode(m,opts){
    opts=opts||{};
    if (m!=='converse' && mode==='converse'){ resetConv(); _convLog=[]; persistConv(); }
    if (mode==='voice' && m!=='voice' && recorder && recorder.isRecording && recorder.isRecording()){ try{ recorder.stop(); }catch(e){} }
    mode=m; speaking=(m==='voice'||m==='converse');
    ['type','voice','converse'].forEach(function(k){ var b=document.getElementById('aaMode_'+k); if(b){ b.classList.toggle('on',k===m); if(k!=='voice') b.classList.remove('rec'); } });
    var hint=document.getElementById('aaHint');
    if(hint) hint.textContent = m==='voice' ? 'Click 🎤 Voice again and speak your question.'
                              : m==='converse' ? 'Conversational mode — I’ll ask follow-up questions.'
                              : 'Type a question, e.g. “Where is the Tax Report?”';
    dbg.mode=curMode(); dbg.page=location.pathname; renderDebug(); persist();
    if (m==='voice' && !opts.silent) startVoice();
    if (m==='converse' && !opts.silent) startConverse();
  }

  function startConverse(){
    log('Mode Changed: Converse','Converse Session Started','Conversation State: Active');
    var listening='No';
    if (canVoice()){ var okv=startVoice(true); listening = okv ? 'Yes' : 'No'; if(!okv && voiceErr) toast(voiceErr); }
    setDebug({ intent:'Converse', entity:'—', source:'Assistant', navTarget:'—', result:'Converse session active' });
    log('Listening: '+listening);
    var wrap=results();
    if (wrap && (wrap.style.display==='none' || !wrap.innerHTML)){
      renderCard({ intent:'converse', category:'Assistant', source:'Assistant', confidence:1,
        answer:'Converse mode is on. Tell me what you’d like to do — for example “I need to send reminders”, “I need a giving report”, or “someone wants to join the church” — and I’ll ask a couple of quick questions to get you there.',
        pages:[], suggestedAction:'none' });
    }
    if (speaking) speak('Converse mode is on. What would you like to do?');
  }

  function canVoice(){ return !!window.VoiceOpenAI; }
  // Returns true if listening started, false (with voiceErr set) otherwise.
  function startVoice(){
    voiceErr='';
    if (!window.VoiceOpenAI){ voiceErr='Unable to start voice mode. The voice engine is unavailable — please check the AI service configuration or use a recent Chrome, Edge, or Safari.'; toastIfVoice(); return false; }
    if (!recorder){
      try{
        recorder=window.VoiceOpenAI.createRecorder({
          context:'home',
          onState:function(on){ var b=document.getElementById('aaMode_voice'); if(b) b.classList.toggle('rec',!!on); setDebug({ transcript: on?'(listening…)':dbg.transcript }); },
          onStatus:function(){},
          onTranscript:function(t){ var inp=document.getElementById('aiSearchInput'); if(inp) inp.value=t; setDebug({transcript:t}); runAssist(t); },
          onDisabled:function(){ voiceErr='Voice is unavailable for your account (disabled or limit reached).'; toast(voiceErr); }
        });
      }catch(e){ voiceErr='Unable to start voice mode. Please check the AI service configuration.'; toast(voiceErr); return false; }
    }
    if (!recorder){ voiceErr='Unable to start voice mode. Please check the AI service configuration.'; toast(voiceErr); return false; }
    if (recorder.supported===false){ voiceErr='Voice mode needs a recent Chrome, Edge, or Safari.'; toast(voiceErr); return false; }
    try{ if(!recorder.isRecording()) recorder.start(); }catch(e){ voiceErr='Unable to start microphone. Please allow mic access.'; toast(voiceErr); return false; }
    return true;
  }
  function toastIfVoice(){ if(voiceErr) toast(voiceErr); }

  /* ════════════════════════ Styles + UI ════════════════════════ */
  function injectStyles(){
    if (document.getElementById('aa-style')) return;
    var s=document.createElement('style'); s.id='aa-style';
    s.textContent=[
      // Match the home page (.ai-search-wrap / .ai-search-bar / .ai-search-btn)
      // exactly so the AI search box is identical on every page.
      '.aa-host{padding:18px 24px 0;margin-bottom:18px;}',
      // Single-row toolbar: ✨ input (grows) · Search · Type · Voice · Converse · Guide.
      // The mode buttons live INSIDE the search bar so every control shares one row
      // and one height; on narrow screens flex-wrap lets them drop gracefully.
      '.aa-searchbar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;background:#fff;border:2px solid #c5cae9;border-radius:14px;padding:8px 12px;box-shadow:0 2px 12px rgba(103,49,71,.1);transition:border-color .2s,box-shadow .2s;}',
      '.aa-searchbar:focus-within{border-color:#673147;box-shadow:0 0 0 4px rgba(103,49,71,.1);}',
      '.aa-searchbar .aa-ic{font-size:18px;flex-shrink:0;}',
      '#aiSearchInput.aa-input{flex:1 1 180px;border:none;outline:none;font-size:14px;color:#1e2139;background:transparent;min-width:120px;}',
      '#aiSearchInput.aa-input::placeholder{color:#b0b8d0;}',
      // Also let the home page search bar carry the controls on one wrapping row.
      '.ai-search-wrap .ai-search-bar{flex-wrap:wrap;gap:10px;}',
      '.ai-search-wrap .ai-search-input{flex:1 1 180px;min-width:120px;}',
      // Uniform control height for the Search button, mode segment, and Guide.
      '.aa-searchbtn,.ai-search-wrap .ai-search-btn{height:36px;display:inline-flex;align-items:center;justify-content:center;background:transparent;color:#673147;border:none;border-radius:9px;padding:0 18px;font-size:13px;font-weight:700;cursor:pointer;flex-shrink:0;white-space:nowrap;transition:background .15s,color .15s;}',
      '.aa-searchbtn:hover,.ai-search-wrap .ai-search-btn:hover{background:#673147;color:#fff;}',
      '.aa-searchbtn[disabled]{opacity:.6;cursor:not-allowed;}',
      '.aa-controls{display:inline-flex;align-items:center;gap:8px;flex-shrink:0;}',
      '.aa-seg{display:inline-flex;border:1.5px solid #d7c9d0;border-radius:8px;overflow:hidden;}',
      '.aa-seg button{height:36px;display:inline-flex;align-items:center;justify-content:center;border:none;background:#fff;color:#673147;font-size:15px;line-height:1;font-weight:600;padding:0 12px;cursor:pointer;}',
      '.aa-seg button+button{border-left:1px solid #e6dde2;}',
      '.aa-seg button.on{background:#673147;color:#fff;}',
      '.aa-seg button.rec{background:#e53935;color:#fff;animation:aaPulse 1.1s infinite;}',
      '@keyframes aaPulse{50%{opacity:.6;}}',
      '.aa-ghost{height:36px;display:inline-flex;align-items:center;justify-content:center;border:1.5px solid #c7c0d7;background:#fff;color:#41506b;font-size:14px;font-weight:600;padding:0 13px;border-radius:8px;cursor:pointer;}',
      '.aa-ghost:hover{background:#41506b;color:#fff;}',
      '.aa-hintrow{margin-top:6px;}',
      '.aa-hint{font-size:12px;color:#999;}',
      '#aaGuide{margin-top:8px;border:1px solid #e6dde2;border-radius:10px;background:#fbf7f9;padding:12px 14px;font-size:13px;color:#444;}',
      '#aaGuide.hidden{display:none;}',
      '#aaGuideSearch{width:100%;box-sizing:border-box;padding:7px 10px;border:1px solid #d7c9d0;border-radius:7px;font-size:13px;margin-bottom:8px;}',
      '.aa-g-note{font-size:11.5px;color:#888;margin-bottom:6px;}',
      '.aa-g-cat{font-weight:700;color:#673147;margin:8px 0 3px;font-size:12.5px;}',
      '.aa-cmd{display:inline-block;margin:2px 6px 2px 0;padding:3px 9px;border:1px solid #e0d3da;border-radius:999px;background:#fff;font-size:12px;color:#444;cursor:pointer;}',
      '.aa-cmd:hover{background:#673147;color:#fff;border-color:#673147;}',
      '.aa-cmd.aa-na{opacity:.42;}',
      '.aa-suggest{display:flex;justify-content:space-between;align-items:center;gap:10px;border:1px solid #eadfe6;background:#fbf7f9;border-radius:9px;padding:9px 12px;}',
      '.aa-suggest-label{font-weight:700;color:#673147;font-size:13.5px;}',
      '.aa-suggest-desc{font-size:12px;color:#777;margin-top:1px;}',
      '.aa-suggest-vc{font-size:11.5px;color:#9a7d8b;margin-top:2px;}',
      '.aa-suggest-actions{display:flex;gap:6px;flex-shrink:0;}',
      '.aa-open{border:none;background:#673147;color:#fff;font-size:12.5px;font-weight:600;padding:6px 13px;border-radius:7px;cursor:pointer;}',
      '.aa-open.alt{background:#fff;color:#673147;border:1.5px solid #c5a0b5;}',
      '.aa-toast{margin-top:8px;font-size:12.5px;color:#a23;background:#fdeef0;border:1px solid #f3cfd5;border-radius:7px;padding:7px 10px;}',
      '#aiResultsWrap .ai-results-panel{margin-top:10px;border:1px solid #eadfe6;border-radius:12px;background:#fff;padding:14px 16px;box-shadow:0 1px 3px rgba(0,0,0,.05);}',
      '#aiResultsWrap .ai-results-hdr{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;}',
      '#aiResultsWrap .ai-results-title{font-weight:700;color:#673147;font-size:14px;}',
      '#aiResultsWrap .ai-results-close{border:none;background:none;cursor:pointer;color:#aaa;font-size:14px;}',
      '#aiResultsWrap .ai-answer{font-size:13.5px;color:#444;line-height:1.55;}',
      '#aaDebug{margin-top:10px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;background:#11151c;color:#cfe3ff;border-radius:8px;padding:10px 12px;}',
      '#aaDebug.hidden{display:none;}',
      '#aaDebug .aa-drow{display:flex;gap:8px;padding:1px 0;}',
      '#aaDebug .aa-dk{color:#7fd1ff;min-width:150px;}',
      '#aaDebug .aa-dv{color:#cfe3ff;word-break:break-word;}',
      '#aaDebug .aa-dlog{margin-top:8px;padding-top:6px;border-top:1px dashed #2a3550;color:#9ad27f;}',
      // ── Help (?) icon + help modal ──
      '.aa-help{width:28px;height:28px;flex:0 0 auto;border-radius:50%;border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-weight:700;font-size:13px;cursor:pointer;line-height:1;padding:0;}',
      '.aa-help:hover{background:#673147;color:#fff;}',
      '.aa-modal-ov{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;display:flex;align-items:center;justify-content:center;padding:20px;}',
      '.aa-modal{background:#fff;border-radius:14px;max-width:660px;width:100%;max-height:85vh;overflow:auto;padding:20px 22px;box-shadow:0 10px 40px rgba(0,0,0,.25);}',
      '.aa-modal h3{margin:0 0 6px;color:#673147;font-size:16.5px;}',
      '.aa-modal h4{margin:16px 0 4px;color:#673147;font-size:13.5px;}',
      '.aa-modal p,.aa-modal li{font-size:13px;color:#444;line-height:1.55;margin:4px 0;}',
      '.aa-modal ul{margin:4px 0 8px;padding-left:20px;}',
      '.aa-modal .aa-ex{background:#fbf7f9;border:1px solid #eadfe6;border-radius:8px;padding:8px 10px;margin:6px 0;font-size:12.5px;color:#555;font-style:italic;}',
      '.aa-modal-x{float:right;border:none;background:none;font-size:16px;cursor:pointer;color:#999;padding:2px 6px;}',
      '.aa-modal-x:hover{color:#673147;}'
    ].join('');
    document.head.appendChild(s);
  }

  /* ── Help (?) dialog ─────────────────────────────────────────────────────── */
  function showHelpModal(title, bodyHtml){
    var old=document.querySelector('.aa-modal-ov'); if(old && old.parentNode) old.parentNode.removeChild(old);
    var ov=document.createElement('div'); ov.className='aa-modal-ov';
    ov.innerHTML='<div class="aa-modal" role="dialog" aria-modal="true" aria-label="'+title+'">'
      +'<button class="aa-modal-x" type="button" aria-label="Close">✕</button>'
      +'<h3>'+title+'</h3>'+bodyHtml+'</div>';
    function close(){ if(ov.parentNode) ov.parentNode.removeChild(ov); document.removeEventListener('keydown',escK); }
    function escK(e){ if(e.key==='Escape') close(); }
    ov.addEventListener('click', function(e){ if(e.target===ov) close(); });
    ov.querySelector('.aa-modal-x').addEventListener('click', close);
    document.addEventListener('keydown', escK);
    document.body.appendChild(ov);
  }
  function searchHelpHtml(){
    return '<p>AI Search understands plain English. Ask it to open a page, find a feature or report, '
      +'explain how to do something, or look up help — it works the same on every page of the app.</p>'
      +'<h4>What you can search for</h4>'
      +'<ul><li><b>Navigation</b> — "Go to Payroll", "Open the event calendar", "Take me to Income".</li>'
      +'<li><b>Feature locations</b> — "Where do I record a donation?", "Where are attendance reports?".</li>'
      +'<li><b>How-to questions</b> — "How do I add a new member?", "How do I send SMS reminders?".</li>'
      +'<li><b>Reports</b> — "Show me the donation report", "Monthly income report".</li>'
      +'<li><b>Public page links</b> — "Member signup page", "Kids check-in link".</li></ul>'
      +'<h4>Example searches</h4>'
      +'<div class="aa-ex">"Go to Family"</div>'
      +'<div class="aa-ex">"How do I create a pledge?"</div>'
      +'<div class="aa-ex">"Where can I upload songs?"</div>'
      +'<div class="aa-ex">"I need to send reminders" (Converse mode — the assistant asks follow-up questions)</div>'
      +'<h4>🔎 Type · 🎤 Voice · 🗣 Converse</h4>'
      +'<ul><li><b>Type</b> — enter your question in the box and press Enter or click Search.</li>'
      +'<li><b>Voice</b> — click the microphone and speak naturally; your speech is converted to text and searched. Click again to stop. In Voice mode, saying a page name (e.g. "Go to Events") navigates immediately.</li>'
      +'<li><b>Converse</b> — a back-and-forth conversation: describe what you want to do (e.g. "I need to send reminders") and the assistant asks follow-up questions to get you to the right place.</li></ul>'
      +'<h4>Tips for better results</h4>'
      +'<ul><li>Use full, natural sentences — no special syntax is needed.</li>'
      +'<li>Start with a verb (<b>Go to</b>, <b>Open</b>, <b>Show me</b>) when you want to navigate straight to a page.</li>'
      +'<li>Click the ❔ Guide button to browse every available command for your account.</li>'
      +'<li>Voice and Converse need microphone access; Type mode always works.</li></ul>';
  }

  function injectUI(){
    if (document.getElementById('aaBar')) return true;
    injectStyles();

    // Icon-only mode buttons (tooltips + aria-labels carry the names). Voice is
    // NEVER active by default — it starts only when the user clicks the 🎤 icon.
    // Mode controls (Type / Voice / Converse + Guide) — these sit INSIDE the search
    // bar so the input, Search button, and all mode buttons share one aligned row.
    var bar=document.createElement('span'); bar.className='aa-controls'; bar.id='aaBar';
    bar.innerHTML=
      '<span class="aa-seg">'
      + '<button id="aaMode_type" class="on" type="button" title="Type" aria-label="Type">🔎</button>'
      + '<button id="aaMode_voice" type="button" title="Voice" aria-label="Voice">🎤</button>'
      + '<button id="aaMode_converse" type="button" title="Converse" aria-label="Converse">🗣</button>'
      + '</span>'
      + '<button id="aaGuideBtn" class="aa-ghost" type="button" title="Guide — all commands" aria-label="Guide">❔</button>'
      + '<button id="aaHelpBtn" class="aa-help" type="button" title="Help — how AI Search works" aria-label="Help">?</button>';
    // Hint goes on its own line BELOW the toolbar so it never affects alignment.
    var hintRow=document.createElement('div'); hintRow.className='aa-hintrow';
    hintRow.innerHTML='<span id="aaHint" class="aa-hint"></span>';
    var guide=document.createElement('div'); guide.id='aaGuide'; guide.className='hidden';

    var homeWrap=document.querySelector('.ai-search-wrap');
    if (homeWrap){
      // Home page: drop the mode controls into the existing search bar row; the hint
      // + guide go beneath, above the results.
      var homeBar=homeWrap.querySelector('.ai-search-bar');
      if (homeBar){ homeBar.appendChild(bar); } else { homeWrap.appendChild(bar); }
      var res=document.getElementById('aiResultsWrap');
      if (res && res.parentNode===homeWrap){ homeWrap.insertBefore(hintRow,res); homeWrap.insertBefore(guide,res); }
      else { homeWrap.appendChild(hintRow); homeWrap.appendChild(guide); }
    } else {
      // Other pages: build the single-row search bar at the top of the content area.
      var main=document.querySelector('main.page-content')||document.querySelector('.page-content');
      if (!main) return false;                       // not an app content page → skip
      var host=document.createElement('div'); host.className='aa-host'; host.id='aaHost';
      var sb=document.createElement('div'); sb.className='aa-searchbar';
      sb.innerHTML='<span class="aa-ic">✨</span>'
        + '<input id="aiSearchInput" class="aa-input" type="text" placeholder="Ask anything — navigation, reports, how-to, help…">'
        + '<button id="aiSearchBtn" class="aa-searchbtn" type="button">Search</button>';
      sb.appendChild(bar);                           // mode buttons inside the bar → one row
      var resWrap=document.createElement('div'); resWrap.id='aiResultsWrap'; resWrap.style.display='none';
      host.appendChild(sb); host.appendChild(hintRow); host.appendChild(guide); host.appendChild(resWrap);
      main.insertBefore(host, main.firstChild);
      // Wire the input + button we just created (the home page already has inline
      // handlers that call the overridden window.runAiSearch, so skip there).
      var input=document.getElementById('aiSearchInput');
      var btn=document.getElementById('aiSearchBtn');
      if (input){ input.addEventListener('keydown', function(e){ if(e.key==='Enter'){ e.preventDefault(); runAssist(); } }); }
      if (btn){ btn.addEventListener('click', function(){ runAssist(); }); }
    }

    document.getElementById('aaMode_type').addEventListener('click', function(){ setMode('type'); });
    document.getElementById('aaMode_voice').addEventListener('click', function(){
      if (mode==='voice' && recorder && recorder.isRecording && recorder.isRecording()){ try{ recorder.stop(); }catch(e){} return; }
      setMode('voice');                      // mic starts ONLY on this explicit click
    });
    document.getElementById('aaMode_converse').addEventListener('click', function(){ setMode('converse'); });
    document.getElementById('aaGuideBtn').addEventListener('click', openGuide);
    document.getElementById('aaHelpBtn').addEventListener('click', function(){
      showHelpModal('AI Search — Help', searchHelpHtml());
    });

    return true;
  }

  /* ── Override the page's search entry point (preserve original for data lookups) ── */
  function captureOriginal(){
    if (typeof window.runAiSearch==='function' && window.runAiSearch.__aa!==true){ _origRunAiSearch=window.runAiSearch; }
    var f=function(){ return runAssist(); }; f.__aa=true; window.runAiSearch=f;
  }

  /* ── Restore persisted mode / debug / conversation ─────────────────────────── */
  function restoreState(){
    restoreConv();
    // Always start in Type mode — Voice/Converse are never active by default,
    // even if a previous session persisted them; the user must click 🎤 / 🗣.
    setMode('type',{silent:true});
  }

  function loadSession(){
    return fetch('/api/session').then(function(r){ return r.ok?r.json():{}; }).then(function(s){
      session.role=s.role||safeLS('role')||'User';
      session.church=(s.church===true||s.church==='true');
      session.ready=true;
    }).catch(function(){ session.role=safeLS('role')||'User'; session.ready=true; });
  }

  /* ════════════════════════ Bootstrap ════════════════════════ */
  function init(){
    if(!injectUI()) return;
    captureOriginal();
    loadSession();
    loadPublicLinks();
    restoreState();
    if (window.VoiceOpenAI && window.VoiceOpenAI.getStatus){
      window.VoiceOpenAI.getStatus().then(function(st){ if(st && st.voiceAvailable===false){ /* voice gated — errors surfaced on use */ } }).catch(function(){});
    }
  }
  if (document.readyState==='loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(init,300); });
  else setTimeout(init,200);

  // Exposed for testing / external use.
  window.CGP_ASSISTANT = { matchDest:matchDest, matchReport:matchReport, isAvailable:isAvailable, reportAvailable:reportAvailable,
    resolveLocal:resolveLocal, classify:classify, buildContext:buildContext, converseStart:converseStart, converseStep:converseStep,
    matchAccountSwitch:matchAccountSwitch, directNav:directNav, _dests:DESTS, _reports:REPORTS, _conv:function(){return conv;},
    _setSession:function(r,c){ session.role=r; session.church=!!c; }, _setMode:function(m){ mode=m; speaking=(m==='voice'||m==='converse'); } };
})();
