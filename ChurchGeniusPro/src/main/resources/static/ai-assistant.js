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
    { label:'My Family',      target:'/memberHome?tab=family',       section:'members', cat:'Profile', desc:'Your own family record in the Member Portal — contact details, members, photos.', keys:['my family','family'] },
    { label:'Contributions',  target:'/memberHome?tab=give',         section:'members', cat:'Profile', desc:"Member Portal Give / Contributions: pay a contribution online through the church's Stripe account and see your giving history and statements.", keys:['contributions','my contributions','giving'] },
    { label:'Groups',         target:'/memberHome?tab=groups',       section:'members', cat:'Member', desc:'The groups you belong to in the Member Portal, with group messages.', keys:['groups','my groups'] },
    { label:'Classes',        target:'/memberHome?tab=sundaySchool', section:'members', cat:'Member', desc:'Sunday School classes you or your children attend, with lessons and exams.', keys:['classes','class','sunday school'] },
    { label:'Volunteer',      target:'/memberHome?tab=volunteer',    section:'members', cat:'Member', desc:'Volunteer sign-ups and assignments in the Member Portal.', keys:['volunteer','volunteering'] },
    { label:'Directory',      target:'/memberHome?tab=directory',    section:'members', cat:'Member', desc:"The member directory (respects each member's privacy settings).", keys:['directory','member directory'] },
    { label:'Families',       target:'/viewfamily',          section:'admin', cat:'Admin', adminFamily:true,  desc:'The church directory shown as family cards, with each family\'s members and who volunteers, has follow-ups or belongs to groups; add, edit, photos, privacy, scanned membership-form import, printable membership form.', keys:['families','admin families','view families'] },
    { label:'Admin Groups',   target:'/groups',              section:'admin', cat:'Admin', desc:'Ministry groups shown as cards: create groups, view and add members, email a whole group.', keys:['admin groups'] },
    { label:'Membership Requests', target:'/membershipRequests', section:'admin', cat:'Admin', churchSub:true, desc:'Applications submitted on the public Membership Request page, reviewed here before adding or renewing a family.', keys:['membership requests','membership request'] },
    { label:'Unsubscribed List',   target:'/unsubscribed-list',  section:'admin', cat:'Admin', desc:'People who unsubscribed from church emails; manage opt-outs.', keys:['unsubscribed list','unsubscribed'] },
    { label:'Users',          target:'/viewusers',           section:'admin', cat:'Admin', churchSub:true, usersOnly:true, desc:'Staff logins, roles (SuperAdmin, Admin, Accountant, User) and the permission checkboxes that control which pages and menus each login sees; link multiple church accounts.', keys:['users','view users','user management','manage users'] },
    { label:'Income',         target:'/income',              section:'accounting', cat:'Finance', desc:'Record contributions and other income (tithes, offerings, funds); recurring income; AI voice/type entry.', keys:['income'] },
    { label:'Expense',        target:'/expense',             section:'accounting', cat:'Finance', desc:'Record expenses by category and fund; recurring expenses; AI voice/type entry.', keys:['expense','expenses'] },
    { label:'Bank Import',    target:'/bank-import',         section:'accounting', cat:'Finance', desc:'Import a downloaded bank statement file and review many income/expense lines at once.', keys:['bank import','import bank','bank statement'] },
    { label:'Pledges',        target:'/pledges',             section:'accounting', cat:'Finance', desc:"Pledge campaigns and each member's pledge progress.", keys:['pledges','pledge'] },
    { label:'Donation/Give',  target:'/donation-review',     section:'accounting', cat:'Finance', desc:'Donation / Give: review online donations from the public donation page and member contributions from the Member Portal (Stripe), mark them reviewed, see Stripe fees.', keys:['donation','donations','donation review','donation give','give','member contributions','online giving'] },
    { label:'Reports',            target:'/accountingReports',  section:'accounting', cat:'Reports', desc:'Accounting reports hub: income, expense, transaction, tax and financial reports.', keys:['reports','report','accounting reports'] },
    { label:'Income Report',      target:'/income-report',      section:'accounting', cat:'Reports', desc:'Income by period, fund and purpose.', keys:['income report'] },
    { label:'Expense Report',     target:'/expense-report',     section:'accounting', cat:'Reports', desc:'Expenses by period and category.', keys:['expense report'] },
    { label:'Transaction Report', target:'/transactions-report',section:'accounting', cat:'Reports', desc:'Every income and expense transaction for a period.', keys:['transaction report','transactions report'] },
    { label:'Tax Report',         target:'/tax-report',         section:'accounting', cat:'Reports', desc:'Year-end giving statements per contributor, with an editable letter and signature.', keys:['tax report'] },
    { label:'Financial Report',   target:'/financial-report',   section:'accounting', cat:'Reports', desc:'Church financial summary for a year: income by fund, expenses by purpose, net.', keys:['financial report','financial statement'] },
    { label:'Payroll',          target:'/payroll',           section:'accounting', cat:'Payroll', payroll:true, desc:'Employee payroll: pay runs with deductions, taxes and withholding.', keys:['payroll'] },
    { label:'Employee Payroll', target:'/payroll/employees', section:'accounting', cat:'Payroll', payroll:true, desc:'Employees and their pay settings.', keys:['employee payroll','employees payroll'] },
    { label:'Payroll Runs',     target:'/payroll/runs',      section:'accounting', cat:'Payroll', payroll:true, desc:'Create and finalise pay runs.', keys:['payroll runs','payroll run'] },
    { label:'Payroll Reports',  target:'/payroll/reports',   section:'accounting', cat:'Payroll', payroll:true, desc:'Payroll summaries and tax reports.', keys:['payroll reports'] },
    { label:'Payroll Activity', target:'/payroll/activity',  section:'accounting', cat:'Payroll', payroll:true, desc:'Payroll audit trail.', keys:['payroll activity'] },
    { label:'Meetings',         target:'/meetings',          section:'general', cat:'Ministry', desc:'Schedule meetings, notify attendees by email/SMS, keep history (auto-purged after 90 days unless kept).', keys:['meetings','meeting'] },
    { label:'Events',           target:'/events',            section:'general', cat:'Ministry', desc:'Church events with registration, QR check-in, reminders and volunteers.', keys:['events','event'] },
    { label:'Ministry',         target:'/ministry',          section:'general', cat:'Ministry', desc:'Ministry hub incl. Kids Ministry (children, check-in/out, pickup alerts) and Sunday School (classes, teachers, lessons, exams).', keys:['ministry'] },
    { label:'Kids Ministry',    target:'/kidsMinistry',      section:'general', cat:'Ministry', desc:"Children's registration, check-in and check-out with guardian pickup alerts.", keys:['kids ministry','children ministry'] },
    { label:'Worship Planning', target:'/worshipPlanning',   section:'general', cat:'Ministry', desc:'Worship groups, instruments, songs and auto-generated assignments with skip dates.', keys:['worship planning','worship'] },
    { label:'Prayer Ministry',  target:'/prayerRequest',     section:'general', cat:'Ministry', desc:'Prayer requests (public page + follow-up) and prayer volunteers.', keys:['prayer ministry','prayer'] },
    { label:'Email',              target:'/notifyEmail',     section:'general', cat:'Communications', desc:'Compose and send bulk email to members, groups or lists; birthday/anniversary emails.', keys:['email','compose email','send email','notify email'] },
    { label:'Reminders',          target:'/reminders',       section:'general', cat:'Communications', desc:'Automatic reminders: event, periodic (recurring) and one-time.', keys:['reminders','reminder'] },
    { label:'Event Reminders',    target:'/eventReminders',  section:'general', cat:'Communications', desc:'Reminder emails/SMS before, on and after an event.', keys:['event reminders'] },
    { label:'Periodic Reminders', target:'/autoReminders',   section:'general', cat:'Communications', desc:'Recurring reminders on a schedule.', keys:['periodic reminders'] },
    { label:'One-Time Reminders', target:'/oneReminders',    section:'general', cat:'Communications', desc:'A single reminder on a chosen date.', keys:['one time reminders','one-time reminders'] },
    { label:'Certificates',     target:'/certificates',      section:'more', cat:'Administration', desc:'Generate and print certificates (baptism, membership, awards).', keys:['certificates','certificate'] },
    { label:'Public Screens',   target:'/publicScreens',     section:'more', cat:'Administration', desc:'Encrypted public links for your church: event calendar, prayer requests, kids check-in, membership form, member signup, donation page.', keys:['public screens','public screen'] },
    { label:'Follow-Ups',       target:'/followups',         section:'more', cat:'Administration', desc:'Follow-up tasks for members and visitors, plus Connect With Us cards, public prayer requests, visitor follow-ups from check-in and reported chat messages.', keys:['follow ups','follow-ups','followups','follow up'] },
    { label:'Account & Privacy', target:'/accountPrivacy',   section:'more', cat:'Administration', desc:'Privacy Policy and Terms links, delete your own staff login, or (church login / SuperAdmin) request deletion of the whole church.', keys:['account and privacy','delete my login','delete account','delete church','privacy policy','terms of service'] },
    { label:'AI Data Sharing',  target:'/aiDataSharing',     section:'more', cat:'Administration', desc:'Your own answers to the AI data-sharing notices in the mobile app — see them and stop sharing with AI.', keys:['ai data sharing','stop sharing with ai','ai consent','data sharing'] },
    { label:'Help Center',      target:'/helpCenter',        section:'more', cat:'Administration', desc:'Help articles and the Help Assistant chat.', keys:['help center','help centre'] },
    { label:'Fund',             target:'/fund',              section:'account-settings', cat:'Administration', desc:'Add or edit income funds / sources (e.g. Tithe, Building Fund).', keys:['fund','funds','new fund','add fund'] },
    { label:'Expense Categories', target:'/purpose',         section:'account-settings', cat:'Administration', desc:'Add or edit expense categories (purposes) used when recording an expense.', keys:['purpose','purposes','expense category','expense categories','new expense category','add expense category'] },
    { label:'Transaction Types', target:'/transactiontype',  section:'account-settings', cat:'Administration', desc:'Add or edit payment / transaction methods (Cash, Check, Card, Bank Transfer…).', keys:['transaction type','transaction types','payment method','payment methods','transaction method','new payment method'] },
    { label:'Email Sender Settings', target:'/notifyEmail#emailSettings', section:'general', cat:'Administration', desc:'The Email Settings panel on the Email page: the display name shown as the sender, reply-to and signature.', keys:['email settings','sender settings','email display name','display name','sender name','set the email'] },
    { label:'Daily Bible Verse', target:'/dailyVerseSetup',  section:'admin-settings',   cat:'Administration', desc:'Set up the daily Bible verse shown on the home pages.', keys:['daily verse','daily bible verse','bible verse','verse of the day','promise verse'] },
    { label:'NTag Landing Page', target:'/ntagLandingAdmin', section:'admin-settings',   cat:'Admin', desc:'The NTag (NFC tap) landing page: enable it, choose its buttons and preview it.', keys:['ntag page','ntag landing','ntag landing page','nfc landing','nfc page'] },
    // ── Added 2026-10-07: every menu item has an entry, so the assistant can explain any feature ──
    { label:'Attendance',       target:'/attendance',        section:'general', cat:'Ministry', perm:'general.attendance', feature:'attendance', desc:'Check in members, families and first-time visitors (as an individual, a family or a group) for a service or event on any date, manage visitors, and run attendance and visitor reports.', keys:['attendance','take attendance','check in visitor','visitor check in','visitors'] },
    { label:'Volunteers',       target:'/volunteers',        section:'general', cat:'Ministry', feature:'volunteers', desc:'Volunteer roles and assignments across events, with email broadcasts to volunteers.', keys:['volunteers','volunteer roles'] },
    { label:'Sunday School',    target:'/ministry',          section:'general', cat:'Ministry', perm:'general.ministry', desc:'Sunday School classes, teachers, lessons, exams and student progress (under Ministry).', keys:['sunday school','sunday school class','classes admin'] },
    { label:'Bank Sync',        target:'/bankSync',          section:'accounting', cat:'Finance', perm:'accounting.bankimport', feature:'bankSync', desc:'Connect bank accounts (Plaid) and pull in new transactions to review and add. Trial accounts use a sandbox bank; the Standard plan allows up to 3 accounts.', keys:['bank sync','connect bank','plaid'] },
    { label:'Files & Notes',    target:'/filesUpload',       section:'admin', cat:'Admin', desc:'Upload files and keep notes for the church office.', keys:['files','files and notes','notes','upload file'] },
    { label:'Stripe Integration', target:'/stripeIntegration', section:'admin', cat:'Admin', desc:"Connect the church's own Stripe account for the public donation page and Member Portal giving; test the connection.", keys:['stripe','stripe integration','online giving setup'] },
    // HIDDEN — WhatsApp turned off for now (2026-11-06): { label:'WhatsApp Integration', target:'/whatsappIntegration', section:'admin', cat:'Admin', desc:'Connect a WhatsApp sender for member messaging.', keys:['whatsapp','whatsapp integration'] },
    { label:'Private Page Access', target:'/private-access-settings', section:'admin', cat:'Admin', feature:'privatePages', desc:"Restrict Kids Ministry, Event Check-in and Temporary Login so they only work from the church's own Wi-Fi/network.", keys:['private page access','private pages','private access'] },
    { label:'AI Activity',        target:'/aiActivity',              section:'admin', cat:'Admin', desc:"Every AI request made in the church — who asked, which feature, model tokens and result. Questions are never stored.", keys:['ai activity','ai audit','ai log','ai usage'] },
    { label:'Ministry Report',    target:'/ministryReport',          section:'more',  cat:'More',  desc:"Monthly ministry report: attendance, visitors, follow-ups, giving, groups, volunteers and engagement signals (engaged / watch / at risk / new / quiet) compared with last month and last year.", keys:['ministry report','monthly report','engagement report','church health','at risk','monthly summary'] },
    { label:'Automations',        target:'/workflows',               section:'more',  cat:'More',  desc:"Trigger → condition → action rules (visitor checks in, member at risk, overdue follow-up, gift recorded) that create follow-ups, notify or e-mail staff, add a family note or draft an e-mail; off until an admin enables them.", keys:['automations','automation','workflows','workflow','rules','triggers','if this then that','auto follow-up'] },
    { label:'AI Connections',     target:'/apiTokens',               section:'more',  cat:'More',  desc:"Personal access tokens for external AI tools (Claude Desktop, ChatGPT) that read your church's data with your permissions through MCP; create, scope and revoke them.", keys:['ai connections','api tokens','access tokens','mcp','claude desktop','chatgpt','external ai','personal access token'] },
    { label:'Saved Lists',        target:'/savedLists',              section:'more',  cat:'More',  desc:"People filters saved from AI Search (e.g. women 18–30 not in a group): run them again, download a CSV or e-mail everyone on the list.", keys:['saved lists','saved list','people filter','my lists'] },
    { label:'NTAG Login',       target:'/ntagAccess',        section:'admin', cat:'Admin', feature:'ntag', desc:'Temporary NTAG (NFC) login credentials and the NTag landing page with its links and Live Preview.', keys:['ntag','ntag login','nfc login','ntag landing'] },
    { label:'Temporary Access', target:'/temporaryAccess',   section:'admin', cat:'Admin', desc:'Time-limited logins for helpers with printable barcode badges.', keys:['temporary access','temporary login','access card','badge'] },
    { label:'Ticketing',        target:'/tickets',           section:'more', cat:'Support', perm:'more.ticketing', optIn:true, desc:'Support tickets to the ChurchGeniusPro team: subject, description, urgency and contact details; you get a reference number and email updates. Support may be limited during a trial.', keys:['ticketing','ticket','tickets','support ticket','raise a ticket','contact support'] },
    { label:'Genius Assistant', target:'/ai-assistant',      section:'more', cat:'Support', perm:'more.aiassistant', optIn:true, desc:'The Genius Assistant chat at full width: ask about your church data, find a page, create meetings, income, expenses and settings; type or speak.', keys:['genius assistant','genius','ai assistant','assistant page','ai chat'] },
    { label:'Song Book',        target:'/songbook',          section:'more', cat:'Ministry', feature:'songbook', desc:'Upload and organise songs and publish a public Song Book page.', keys:['song book','songbook','songs'] },
    { label:'Song Book Access', target:'/admin/songbook-access', section:'more', cat:'Ministry', feature:'songbook', desc:'Who can edit the Song Book.', keys:['song book access','songbook access'] },
    { label:'Guess It',         target:'/guessIt',           section:'activity', cat:'Activity', feature:'activityCorner', desc:'Activity Corner quiz game members join from their phones.', keys:['guess it','activity corner','quiz game'] },
    { label:'Email Settings',   target:'/emailSettings',     section:'general', cat:'Administration', perm:'general.emailsettings', desc:'Church email settings; on Trial/Demo accounts, verify the test email address that receives all outgoing mail.', keys:['email settings page','test email','verified email'] },
    // Public pages with dynamic identifiers (resolved at run time via /api/public-screens).
    { label:'Kids Check-In',   dynamic:true, base:'/kidsCheckin',       rtype:'cid',   cat:'Public', desc:"Public kids check-in / check-out page for the church's own link.", keys:['kids check in','kids check-in','kids checkin'] },
    { label:'Prayer Requests', dynamic:true, base:'/viewPrayerRequest', rtype:'cid',   cat:'Public', desc:'Public page where anyone can submit a prayer request.', keys:['prayer requests','prayer request'] },
    { label:'Member Signup',   dynamic:true, base:'/memberSignup',      rtype:'token', cat:'Public', desc:'Public page where members create their Member Portal login (approved by the church).', keys:['member signup','member sign up','signup page','sign up page'] },
    { label:'Membership Form', dynamic:true, base:'/membershipForm',    rtype:'cid',   cat:'Public', desc:'Public membership application form; submissions go to Membership Requests.', keys:['membership form'] },
    { label:'Event Calendar',  dynamic:true, base:'/viewEventCalendar', rtype:'cid',   cat:'Public', desc:'Public calendar of church events with registration.', keys:['event calendar','church calendar'] },
    { label:'Donation Page',   dynamic:true, base:'/donate',            rtype:'id',    cat:'Public', desc:"Public online donation page through the church's Stripe account.", keys:['donation page','donate'] }
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
  var session = { role:(safeLS('role')||'User'), church:(safeLS('church')==='true'), ready:false, aiAssistant:null };
  // Server verdict per catalogue page (GET /api/ai-search/nav): url → available. When present
  // it overrides the client-side role guesses below, so a link is only offered for a page the
  // server says this login can open. Loaded after the session; absent until then.
  var serverNav = null;
  function loadServerNav(){
    return fetch('/api/ai-search/nav').then(function(r){ return r.ok?r.json():null; }).then(function(j){
      if (!j || !Array.isArray(j.pages)) return;
      var m={}; j.pages.forEach(function(p){ if(p && p.url) m[p.url]=(p.available===true); }); serverNav=m;
    }).catch(function(){});
  }
  function safeLS(k){ try{ return localStorage.getItem(k); }catch(e){ return null; } }
  function allowedSections(){ if (session.church) return ['admin','accounting']; return ROLE_SECTIONS[session.role] || ['general','more','help']; }

  function isAvailable(d){
    if (serverNav && d && !d.dynamic && d.target && Object.prototype.hasOwnProperty.call(serverNav, d.target)) return serverNav[d.target];
    if (d.placeholder) return false;
    if (d.dynamic) return (publicLinks === null) ? true : !!findPublicLink(d.base);
    var role = session.role, church = session.church;
    // Entries that declare a permission / plan feature use the same rules as session.js:
    // an explicit false denies; opt-in pages need an explicit true for member logins.
    if (d.perm){
      var perms = window.CGP_PERMS;
      if (perms && perms[d.perm] === false) return false;
      if (d.optIn && role === 'Member' && !(perms && perms[d.perm] === true)) return false;
    }
    if (d.feature && typeof window.CGP_hasFeature === 'function' && !window.CGP_hasFeature(d.feature)) return false;
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
    // "What is X?", "explain X", "tell me about X" — a question about a feature, not a request to open it.
    var explain=/\b(what is|what's|whats|what are|what does|what do|explain|tell me about|describe|meaning of|purpose of|used for|do with)\b/.test(q) && !/\b(what report|what page)\b/.test(q);
    return { howTo:howTo, where:where, explain:explain };
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
        // A question ABOUT the feature: explain it from the catalogue whether or not this
        // user can open it; offer to open it only when they can. Never "it doesn't exist".
        if (c.explain && !c.where){
          var avail=isAvailable(d);
          var where=d.dynamic ? ' It is a public page you share from Public Screens.' : ' You find it under '+(d.cat||'the menu')+' (URL: '+d.target+').';
          return { intent:'explain', category:d.cat||'', source:'Navigation Guide', confidence:0.9, entity:d.label,
                   answer:d.label+(d.desc?' — '+d.desc:' is a ChurchGeniusPro feature.')
                     +(avail ? where : ' This feature is part of ChurchGeniusPro but is not enabled for your current role, permissions or plan — an administrator can enable it.'),
                   pages: avail && !d.dynamic ? [{label:d.label,url:d.target,voiceCommand:voiceCmd(d.label)}] : [],
                   suggestedAction:'none' };
        }
        if (!isAvailable(d))
          return { intent:'navigation', category:d.cat||'', source:'Navigation Guide', confidence:0.9, entity:d.label,
                   answer:'That page ('+d.label+') is not available for your current account.'
                     +(d.desc?' ('+d.label+': '+d.desc+' An administrator can enable it.)':''), pages:[], suggestedAction:'none' };
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
    // Every feature of the product, with whether THIS user can open it — so the assistant
    // can explain a feature it cannot offer to open, instead of claiming it does not exist.
    var all=[];
    DESTS.forEach(function(d){ if(d.placeholder) return;
      all.push({ label:d.label, url:d.dynamic?d.base:(d.target||''), category:d.cat||'', description:d.desc||'', available:isAvailable(d) });
    });
    return { role:session.role, currentPage:location.pathname, availablePages:pages, reports:reports, allFeatures:all };
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
    return fetch('/api/ai-assist',{ method:'POST', headers:{'Content-Type':'application/json'},
                                    body:JSON.stringify(body), signal:aaSignal() })
      .then(function(r){ if(r.status===503||r.status===404){ setDebug({openai:'Not configured ('+r.status+')'}); return null; } return r.ok?r.json():null; })
      .catch(function(e){ if(!(e&&e.name==='AbortError')) setDebug({openai:'Error'}); return null; });
  }
  function fallbackHelp(query){
    return fetch('/api/help/search',{ method:'POST', headers:{'Content-Type':'application/json'},
                                      body:JSON.stringify({query:query}), signal:aaSignal() })
      .then(function(r){ return r.ok?r.json():null; }).catch(function(){ return null; });
  }

  /* ════════════════════════ Main entry ════════════════════════ */
  var lastQ='', _t0=0, _origRunAiSearch=null;
  /* Optional page adapter (window.CGP_ASSISTANT_UI). A page that renders the
     assistant as a chat transcript (/ai-assistant) sets it; every other page
     leaves it undefined and gets the unchanged results-panel behaviour. Methods,
     all optional: question(text, mode) — a question entered the flow (once per
     turn, whatever the mode); answer(cardElement, card, question) — a finished
     answer card; busy(on) — a reply is in flight; notice(text) — a toast. */
  function uiHook(){ var u=window.CGP_ASSISTANT_UI; return (u && typeof u==='object') ? u : null; }
  /* The only place a user turn is reported to the chat page (runAssist, and the Phase E confirm click). */
  function reportQuestion(q, m){ uiCall('question', q, m); }
  function uiCall(fn){ var u=uiHook(); if(!u || typeof u[fn]!=='function') return false; try{ u[fn].apply(u, Array.prototype.slice.call(arguments,1)); }catch(e){} return true; }
  function runAssist(query){
    var input=document.getElementById('aiSearchInput');
    query=(query!=null?query:(input?input.value:''))||''; query=String(query).trim();
    if(!query){ if(input) input.focus(); return; }

    // Phase F: while an answer is in flight, Enter / Search / a new utterance does not
    // start another turn (no double submissions, no overlapping requests). Stop (voice
    // modes) or the 30 s watchdog releases the bar.
    if (_inFlight && !_stopped){ flashHint('Still working on your last question — one moment…'); return; }

    // A new turn supersedes the previous one: abort its requests, orphan its
    // callbacks, and clear anything it left on a timer. `g` is this turn's
    // ticket - every callback below checks it before touching the UI, so a
    // Stop (or a newer question) can never be overwritten by a stale reply.
    for (var pi=0; pi<_aborts.length; pi++){ try{ _aborts[pi].abort(); }catch(e){} }
    _aborts.length=0;
    for (var pt=0; pt<_timers.length; pt++){ try{ clearTimeout(_timers[pt]); }catch(e){} }
    _timers.length=0;
    _stopped=false;
    var g = ++runGen;
    _inFlight=true; setResultsBusy(true);
    // Client-side timeout: if nothing has answered after AA_TIMEOUT_MS the requests are
    // aborted and the user is told, instead of a spinner that never ends.
    _turnTimer = aaAfter(AA_TIMEOUT_MS, function(){ if (aaLive(g)) onTurnTimeout(g); });
    if (mode==='voice' || mode==='converse'){ _assistBusy=true; setPhase('processing'); }

    lastQ=query; _t0=Date.now();
    reportQuestion(query, mode);              // exactly once per turn: Type, Voice and Converse all enter here
    busy(true);
    setDebug({ page:location.pathname, mode:curMode(), input:query, transcript:(mode!=='type'?query:'—'),
               intent:'…', entity:'—', role:detectedRoleLabel(), source:'—', navTarget:'—', confidence:'—',
               responseMs:'…', openai:'—', result:'…', dynamic:'—', publicFound:'—', openMode:'—' });

    // Explicit "Go to / Open <page>" → navigate immediately (handles new-tab,
    // permissions, dynamic public links). Runs in every mode and pre-empts the
    // Converse follow-up trees so direct commands are never intercepted.
    //
    // A specific DATA question ("Show Anson's donations this month", "Open expenses for
    // September") is asked of the server first even when it starts with a command verb;
    // only if the server does not handle it does the direct-navigation shortcut run.
    // 2026-11-08: "delete / rename / change <a record>" is asked of the server first too — AI Search never edits or
    // deletes, the server answers with a link to the page that does; if it doesn't, the shortcut still runs below.
    // Create requests ("Record an income of $500…", "Schedule a Bible Study next Sunday…") too, so Voice and Converse
    // start the same guided conversation as Type instead of jumping to the page.
    // 2026-11-09: an open conversation's answer ("Prayer Meeting" to "Which category?") goes to the server first in
    // every mode — spoken, it used to match the Meetings page name and open the page instead of answering.
    var dataFirst = (looksLikeDataQuestion(query) && looksSpecific(query)) || looksLikeManage(query) || looksLikeAction(query) || _aaServerPending;
    if (!dataFirst && directNav(query)){ if(input) input.value=''; assistDone(g); return; }

    // ── Natural-language DATA questions ("When is Anson's birthday?",
    //    "What was the income for March?", "Who are the volunteers?").
    //    Answered server-side with permission checks; runs in ALL modes.
    //    handled=false → fall through to the normal flow below.
    if (looksLikeDataQuestion(query) || looksLikeAction(query) || _aaServerPending){
      tryDataSearch(query).then(function(d){
        if (!aaLive(g)) return;                     // stopped, or superseded
        // A meeting draft is open on the server (missing fields / awaiting "yes"): the next
        // message goes to the server too, whatever it looks like (Phase E).
        // 2026-11-08: guided income / expense too, and every message while a conversation thread is open.
        _aaServerPending = isPendingAnswer(d) || _thread.active;
        if (d && d.handled){
          busy(false);
          renderCard({ intent:d.intent||'data', category:'Data', source:'Church Data',
            confidence:1, answer:d.answer||'', pages:(d.link && d.link.url && !d.denied) ? [{label:d.link.label||'Open', url:d.link.url}] : [],
            suggestedAction:'none', data:d });
          setDebug({ intent:'Data: '+(d.intent||'lookup'), source:'Church Data',
            result: d.denied ? 'Permission denied' : 'Answered (data)' });
          if (mode==='converse'){ _convLog.push('Assistant: '+(d.answer||'')); persistConv(); }
          _aaServerPending = _aaServerPending || _thread.active;
          if (speaking) speak(stripUrls(d.answer||'') + (Array.isArray(d.choices) && d.choices.length && d.choices.length <= 4 ? ' ' + d.choices.join(', ') + '.' : ''));
          finishTiming(); assistDone(g); if(input) input.value='';
          return;
        }
        if (dataFirst && directNav(query)){ if(input) input.value=''; assistDone(g); return; }
        assistFlow(query, input, g);   // not a data question after all → normal flow
      });
      return;
    }
    assistFlow(query, input, g);
  }

  /* A turn has finished (or been superseded). Hand the phase back to the voice
     engine: still listening -> Listening, otherwise Done. */
  function assistDone(g){
    if (g != null && g !== runGen) return;
    _assistBusy = false;
    endTurn();
    if (mode!=='voice' && mode!=='converse'){ aaPhase='idle'; refreshModeButtons(); return; }
    if (_stopped) return;                           // stopAll owns the UI now
    var listening = !!(recorder && recorder.isRecording && recorder.isRecording());
    setPhase(listening ? 'listening' : 'done');
  }

  /* Phase F — one turn at a time, with a watchdog. */
  var AA_TIMEOUT_MS = 30000;
  var _inFlight=false, _turnTimer=null;
  function endTurn(){
    _inFlight=false; setResultsBusy(false);
    if (_turnTimer!=null){ try{ clearTimeout(_turnTimer); }catch(e){} var ix=_timers.indexOf(_turnTimer); if(ix>=0) _timers.splice(ix,1); _turnTimer=null; }
  }
  function onTurnTimeout(g){
    runGen++;                                        // orphan every callback of the timed-out turn
    for (var i=0;i<_aborts.length;i++){ try{ _aborts[i].abort(); }catch(e){} } _aborts.length=0;
    busy(false); endTurn();
    var msg='The assistant took too long to respond ('+Math.round(AA_TIMEOUT_MS/1000)+' seconds). Please try again, or open the Help Center.';
    renderCard({ intent:'timeout', category:'Assistant', source:'Assistant', confidence:0, answer:msg,
                 pages:[{label:'Help Center', url:'/helpCenter', voiceCommand:'Go to Help Center'}], suggestedAction:'none' });
    setDebug({ result:'Timed out after '+AA_TIMEOUT_MS+' ms', responseMs:'>'+AA_TIMEOUT_MS+' ms' });
    if (speaking) speak('Sorry, that took too long. Please try again.');
    _assistBusy=false;
    if (mode==='voice' || mode==='converse'){ var listening=!!(recorder && recorder.isRecording && recorder.isRecording()); setPhase(listening?'listening':'done'); }
    else { aaPhase='idle'; refreshModeButtons(); }
  }
  function setResultsBusy(on){
    var w=document.getElementById('aiResultsWrap'); if (w) w.setAttribute('aria-busy', on?'true':'false');
    var i=document.getElementById('aiSearchInput'); if (i) i.setAttribute('aria-busy', on?'true':'false');
  }
  var _hintTimer=null;
  function flashHint(text){
    var h=document.getElementById('aaHint'); if(!h){ toast(text); return; }
    var prev=h.textContent; h.textContent=text;
    if (_hintTimer) clearTimeout(_hintTimer);
    _hintTimer=setTimeout(function(){ if (h.textContent===text) h.textContent=prev; }, 2500);
  }
  var _aaServerPending=false;
  // Kept in step with the server's vocabulary (AiQueryLexicon): every synonym the server maps
  // must also bring the question to the server, or the bar answers it as navigation instead.
  function looksLikeDataQuestion(q){
    return /\b(birthdays?|anniversar\w*|income|revenues?|receipts|takings|earnings|collections?|tithes?|tithed|tithing|contribut\w*|donat\w*|donors?|gave|give|given|gives|giving|givers?|gifts?|offerings?|volunteers?|volunteering|volunteered|helpers?|serving|on duty|rota|roster|money|funds received|amount received|payments made|all entries|happenings|(?:church )?activities|pledged|expenses?|expenditures?|spent|spend|spending|costs?|bills|purchases|paid out|outgoings|transactions?|ledger|campaigns?|pledges?|meetings?|meet ?ups?|prayers?|follow[- ]?ups?|tasks?|events?|programs?|programmes?|functions|occasions|reminders?|members?|kids?|children|public (links?|screens?|pages?)|reports?|statements?|famil(?:y|ies)|households?|un-?alert\w*|non-?alert\w*|alerts? (?:are |is )?(?:disabled|off|turned off)|disabled alerts?|attend\w*|attenders?|attendees?|lapsed|regulars|visitors?|first[- ]time\w*|newcomers?|groups?|women|men|ladies|adults?|teens?|teenagers?|youth|seniors?|guests?|non-?members?|people|born|aged|years? old|e-?mails?|phones?|spouses?|heads? of household|registered|signed up)\b/i.test(q);
  }
  // AI Phase 4: a request to DO something ("add Sarah Lee to Welcome Team", "mark the Lees as followed up",
  // "note for the Smiths: …", "assign John as usher on Sunday", "remind me to call Pat") goes to the server
  // first — it proposes an action and asks for confirmation; nothing is written until the card is confirmed.
  function looksLikeManage(q){
    return /^\s*(please\s+)?(can you\s+|could you\s+)?(edit|change|update|modify|rename|delete|remove|erase|deactivate|archive)\b/i.test(q);
  }
  function looksLikeAction(q){
    // 2026-11-11: + compose / draft / send an e-mail, make / start / form a group or campaign, and the page-link
    // requests (run payroll, import a bank statement, print a certificate…), with a polite lead ("I want to …").
    return /^\s*(please\s+|can you\s+|could you\s+|can i\s+|i\s+(?:want|need|would like)\s+to\s+|i'?d like to\s+|let'?s\s+|help me\s+(?:to\s+)?)?(add|put|enrol|enroll|include|assign|sign up|mark|complete|close|finish|log|record|note|write|remind me|create|new|define|register|set ?up|schedule|book|plan|arrange|organi[sz]e|edit|change|update|modify|rename|delete|remove|erase|deactivate|archive|compose|draft|send|prepare|make|start|launch|begin|form|e-?mail|run|process|import|upload|sync|synchroni[sz]e|connect|print|generate|issue|raise|submit|publish)\b/i.test(q)
        && !/^\s*(how|where|what|when|which|who|why|is|are|do|does|(?:can|could)(?!\s+(?:you|i)\b)|list|show|find)\b/i.test(q);
  }
  // Names a person ("Anson's", "did Anson") or a period ("this month", "in September", "2026")
  // — i.e. asks for data rather than for a page.
  function looksSpecific(q){
    return /\b[A-Za-z]+(?:'|\u2019)s\b/.test(q)
        || /\b(today|yesterday|tomorrow|this|last|next|past|previous|since|between|during|so far|year to date|ytd|weekly|monthly|quarter|q[1-4]|january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec|\d{4})\b/i.test(q)
        || /^\s*(how much|how many|what|who|which|whose|did|has|have|does|do|is|are|was|were|when)\b/i.test(q)
        || /\b(did|has|have)\s+[A-Z][a-z]+\s+(give|gave|given|donate|donated|contribute|contributed|tithe|tithed)\b/.test(q)
        // A status filter ("deleted members", "inactive families", "unalert") asks for records, not a page.
        || /\b(deleted|removed|in-?active|deactivated|un-?alert\w*|non-?alert\w*|alerts? (?:are |is )?(?:disabled|off)|disabled alerts?)\b/i.test(q)
        // A cross-referenced question (Phase 1) asks for people, not a page: "regulars we haven't seen", "not in any group".
        || /\b(attend\w*|attenders?|lapsed|regulars|haven'?t seen|not seen|not in (?:a|any) (?:small )?groups?|not serving|first[- ]time visitors?|followed[- ]up)\b/i.test(q)
        // A composed people filter (AI Phase 1): age, gender, type, birth month, contact details, a named group / campaign / event.
        || /\b(women|men|ladies|adults?|teens?|teenagers?|youth|seniors?|guests?|non-?members?|born in|aged|years? old|over \d|under \d|\d\s*\+|no (?:e-?mail|phone)|without (?:an? )?(?:e-?mail|phone)|in the .+ group|registered for|signed up for|pledged|who (?:are|is|have|has|gave|attended|serve))\b/i.test(q);
  }
  function tryDataSearch(query){
    return fetch('/api/ai-search/data', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ query:query }), signal:aaSignal()
    }).then(function(r){ return r.ok ? r.json() : null; })
      .catch(function(){ return null; });
  }

  function assistFlow(query, input, g){
    if (mode==='converse'){
      _convLog.push('User: '+query); persistConv();
      var handled = conv.active ? converseStep(query) : converseStart(query);
      if (handled){ busy(false); finishTiming(); assistDone(g); if(input) input.value=''; return; }
      conv.active=true; persistConv();
      runLLM(query).then(function(d){
        if (!aaLive(g)) return;                     // stopped, or superseded
        busy(false);
        if (d){ renderFromLLM(d); _convLog.push('Assistant: '+(d.followUpQuestion||d.answer||'')); persistConv(); }
        else { renderCard({intent:'converse',category:'Assistant',source:'Assistant',confidence:0,
                 answer:'I can help you navigate, find reports, and answer how-to questions. Could you tell me a bit more about what you want to do?',pages:[],suggestedAction:'none'});
               setDebug({intent:'Converse',result:'Awaiting more detail'}); if(speaking) speak('Could you tell me a bit more about what you want to do?'); }
        finishTiming(); assistDone(g);
      });
      if(input) input.value='';
      return;
    }

    var local=resolveLocal(query);
    if (local){ busy(false); setDebug({openai:'Local (no API call)'}); renderFromLocal(local); if(speaking) speak(stripUrls(local.answer)); finishTiming(); assistDone(g); return; }

    var c=classify(query);
    var helpish = c.howTo || c.where || (typeof window._isHelpQuery==='function' && window._isHelpQuery(query));
    if (!helpish && typeof _origRunAiSearch==='function'){
      busy(false); setDebug({intent:'Data lookup',source:'Page search',result:'Delegated to page'}); finishTiming(); assistDone(g);
      if(input) input.value=query; return _origRunAiSearch();
    }
    runLLM(query).then(function(d){
      if (!aaLive(g)) return;                       // stopped, or superseded
      if (d){ busy(false); setDebug({openai:'OK'}); renderFromLLM(d); if(speaking) speak(stripUrls(d.answer||'')); finishTiming(); assistDone(g); return; }
      return fallbackHelp(query).then(function(h){
        if (!aaLive(g)) return;                     // stopped, or superseded
        busy(false); setDebug({openai:'Fallback → Help search'});
        if (h && typeof window.renderAiResults==='function'){ window.renderAiResults(h,query);
          setDebug({intent:(h.intent||'help'),entity:'—',source:'Help Center',navTarget:'—',confidence:'—',result:'Help search'}); }
        else renderCard({intent:'help',category:'Help',source:'Help Center',confidence:0,
               answer:(h&&h.answer)||'I couldn’t find an exact answer. The Help Center has more detail.',
               pages:[{label:'Help Center',url:'/helpCenter',voiceCommand:'Go to Help Center'}],suggestedAction:'open_page'});
        finishTiming(); assistDone(g);
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
    else { if(speaking) speak('Opening '+label+'.'); aaAfter(150, function(){ window.location.href=url; }); }
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
    else { aaAfter(350, function(){ window.location.href = url; }); }
  }

  /* ════════════════════════ TTS ════════════════════════ */
  function speak(text){
    if(!('speechSynthesis' in window)) return;
    if(_stopped) return;                            // cancelled - stay silent
    try{ if(recorder&&recorder.setMuted) recorder.setMuted(true);
      window.speechSynthesis.cancel();
      var u=new SpeechSynthesisUtterance(String(text||'').slice(0,400)); u.rate=1.03;
      u.onend=u.onerror=function(){ if(recorder&&recorder.setMuted) recorder.setMuted(false); };
      window.speechSynthesis.speak(u);
      aaAfter(8000, function(){ if(recorder&&recorder.setMuted) recorder.setMuted(false); });
    }catch(e){ if(recorder&&recorder.setMuted) recorder.setMuted(false); }
  }

  /* ════════════════════════ Rendering ════════════════════════ */
  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
  function results(){ return document.getElementById('aiResultsWrap'); }
  /* ── 2026-11-08: conversation thread ─────────────────────────────────────────────
     While a guided conversation is open (create a meeting / add an income / add an expense,
     or an action-agent question), each question and answer is added under the previous ones
     instead of replacing them. Older answers keep their text but their buttons are switched
     off; only the newest card can be used. The thread is kept in this browser tab
     (sessionStorage) across pages until the record is saved or cancelled, then cleared. */
  var THREAD_KEY='aa.thread';
  var _thread={ active:false, items:[] };
  function isConvIntent(i){ return /^(meeting|action|guided|email)\./.test(String(i||'')); }
  function isPendingAnswer(d){ return !!(d && d.handled && (d.pending===true || /^(meeting|action|guided)\.(ask|confirm)$/.test(String(d.intent||'')) || d.intent==='email.review')); }
  function threadOwner(){ return String(session.user||''); }
  function saveThread(){
    try{
      if (_thread.active) sessionStorage.setItem(THREAD_KEY, JSON.stringify({ active:true, who:threadOwner(), items:_thread.items.slice(-20) }));
      else sessionStorage.removeItem(THREAD_KEY);
    }catch(e){}
  }
  function clearThread(){ _thread={ active:false, items:[] }; saveThread(); }
  function slimCard(a){ return { intent:a.intent, category:a.category, source:a.source, confidence:a.confidence, answer:a.answer, pages:a.pages||[], suggestedAction:a.suggestedAction, data:a.data||null }; }
  function restoreThread(){
    var t=null;
    try{ t=JSON.parse(sessionStorage.getItem(THREAD_KEY)||'null'); }catch(e){ t=null; }
    if (!t || !t.active || !Array.isArray(t.items) || !t.items.length) return;
    if (String(t.who||'') !== threadOwner()){ try{ sessionStorage.removeItem(THREAD_KEY); }catch(e){} return; }   // another login's conversation is never shown
    _thread={ active:true, items:t.items.slice(-20) };
    _aaServerPending=true;
    if (uiHook()) return;                         // the chat page keeps its own transcript
    var wrap=results(); if(!wrap) return;
    wrap.innerHTML=threadHtml(); wrap.style.display='block'; wireCard(wrap);
  }
  function threadHtml(){
    var n=_thread.items.length, html='<div class="ai-results-panel aa-thread" role="log" aria-label="Conversation with the assistant">'
      + '<div class="ai-results-hdr"><div class="ai-results-title">✨ Assistant <em style="font-size:12px;color:#aaa;font-weight:400;">conversation</em></div>'
      + '<div class="aa-thread-tools">'+(_thread.active ? '<button type="button" class="aa-newconv" title="Cancel this conversation and start again">New conversation</button>' : '')
      + '<button class="ai-results-close" type="button" onclick="document.getElementById(\'aiResultsWrap\').style.display=\'none\'" title="Close" aria-label="Close results">✕</button></div></div>';
    _thread.items.forEach(function(it, i){
      var last = i===n-1;
      html+='<div class="aa-turn'+(last?' aa-turn-last':' aa-turn-old')+'">'
        + '<div class="aa-you"><span class="aa-you-b">'+esc(it.q||'')+'</span></div>'
        + '<div class="aa-bot">'+cardBody(it.a||{}, !last)+'</div></div>';
    });
    return html+'</div>';
  }
  function newConversation(){
    var last=_thread.items.length ? _thread.items[_thread.items.length-1] : null;
    var c=last && last.a && last.a.data && last.a.data.confirm;
    if (c && c.token && String(c.endpoint||'').indexOf('/api/ai/actions/')===0){
      fetch('/api/ai/actions/reject', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ token:c.token }) }).catch(function(){});
    }
    fetch('/api/ai-search/data', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ query:'cancel' }) }).catch(function(){});
    clearThread(); _aaServerPending=false;
    var wrap=results(); if (wrap){ wrap.innerHTML=''; wrap.style.display='none'; }
    var input=document.getElementById('aiSearchInput'); if (input){ input.value=''; try{ input.focus(); }catch(e){} }
    toast('Started a new conversation — nothing was saved.');
  }
  /* A suggested answer (chip) is sent as if typed; Edit puts "Change <field> to " in the bar. */
  function chooseAnswer(text){
    var input=document.getElementById('aiSearchInput');
    if (input && !uiHook()) input.value=text;
    runAssist(text);
  }
  function prefillEdit(text){
    if (uiCall('prefill', text)) return;                 // the chat panel's own message box
    var input=document.getElementById('aiSearchInput'); if(!input) return;
    input.value=text; try{ input.focus(); input.setSelectionRange(text.length, text.length); }catch(e){}
    if (typeof input.dispatchEvent==='function'){ try{ input.dispatchEvent(new Event('input', { bubbles:true })); }catch(e){} }
  }

  /*
   * 2026-11-10: an income / expense was just SAVED (the server's confirmation carries a receipt) — tell any page
   * that shows ledger figures (the Dashboard) to re-read them. Fired once, here, when the answer arrives: restored
   * transcripts never pass through renderCard, so they never fire. Same tab = a window event; other open tabs of
   * this app = a BroadcastChannel message. Listeners coalesce, so both arriving together means one refresh.
   */
  function announceLedgerChange(d){
    if (!d || d.intent!=='action.done' || !d.receipt || typeof d.receipt!=='object') return;
    var kind=d.receipt.kind;
    if (kind!=='income' && kind!=='expense') return;
    var detail={ kind:kind, at:Date.now() };
    try{ window.dispatchEvent(new CustomEvent('cgp:ledger-changed', { detail:detail })); }catch(e){}
    try{ if (typeof BroadcastChannel==='function'){ var bc=new BroadcastChannel('cgp-ledger'); bc.postMessage(detail); bc.close(); } }catch(e){}
  }

  function renderCard(a){
    var wrap=results(); if(!wrap && !uiHook()) return;
    var d0=a.data;
    announceLedgerChange(d0);
    var threaded=false, ending=false;
    if (_thread.active || isPendingAnswer(d0)){
      if (!_thread.active){ _thread={ active:true, items:[] }; }
      _thread.items.push({ q:lastQ, a:slimCard(a) });
      // A saved / created / cancelled / refused / expired answer of the conversation closes it.
      ending = !isPendingAnswer(d0) && !!(d0 && isConvIntent(d0.intent));
      threaded=true;
    }
    if (threaded && !uiHook()){
      var html0=threadHtml();
      if (ending){ var keep=_thread.items; clearThread(); _thread.items=keep; html0=threadHtml(); _thread.items=[]; }
      else saveThread();
      wrap.innerHTML=html0; wrap.style.display='block';
      wireCard(wrap);
      var lastTurn=wrap.querySelector('.aa-turn-last');
      try{ (lastTurn||wrap).scrollIntoView({behavior:'smooth',block:'nearest'}); }catch(e){}
      try{ var panel=wrap.querySelector('.aa-thread'); if(panel) panel.scrollTop=panel.scrollHeight; }catch(e){}
      return;
    }
    if (threaded){ if (ending) clearThread(); else saveThread(); }
    var html='<div class="ai-results-panel" role="group" aria-label="Assistant answer">'
      + '<div class="ai-results-hdr"><div class="ai-results-title">✨ Assistant <em style="font-size:12px;color:#aaa;font-weight:400;">for: '+esc(lastQ)+'</em></div>'
      + '<button class="ai-results-close" type="button" onclick="document.getElementById(\'aiResultsWrap\').style.display=\'none\'" title="Close" aria-label="Close results">✕</button></div>'
      + cardBody(a, false);
    if (a.intent!=='converse')
      html+='<div style="margin-top:14px;padding-top:12px;border-top:1px solid #f0eaf2;font-size:12px;color:#888;">Need more detail? <a href="/helpCenter" style="color:#673147;font-weight:700;">Open the full Help Center →</a></div>';
    html+='</div>';
    if (uiHook() && typeof uiHook().answer==='function'){
      // Chat-style page: hand over the finished card; the page places and wires it.
      var holder=document.createElement('div'); holder.innerHTML=html;
      uiCall('answer', holder.firstChild, a, lastQ);
      return;
    }
    wrap.innerHTML=html; wrap.style.display='block';
    wireCard(wrap);
    try{ wrap.scrollIntoView({behavior:'smooth',block:'nearest'}); }catch(e){}
  }

  /* The answer text and everything the server sent with it. {@code old} = an earlier turn of a thread: its
     buttons are drawn switched off (only the newest card acts). */
  function cardBody(a, old){
    var pages=a.pages||[];
    var dis = old ? ' disabled aria-disabled="true" tabindex="-1"' : '';
    // 2026-11-09: a saved entry shows its confirmation title (details below); an alerts question shows its
    // sentence without the bullet lines (they are listed below as warnings).
    var answerText = a.answer||'';
    if (a.data && a.data.receipt && a.data.receipt.title) answerText = a.data.receipt.title;
    else if (a.data && Array.isArray(a.data.alerts) && a.data.alerts.length) answerText = String(answerText).split('\n').filter(function(l){ return !/^\s*\u2022/.test(l); }).join(' ');
    var html='<div class="ai-answer'+(a.data && a.data.denied ? ' ai-denied' : '')+'">'+esc(answerText)+'</div>';
    // Structured data answers (Phase B): a summary strip and/or a bounded table.
    if (a.data && !a.data.denied){
      var d=a.data;
      if (d.summary && typeof d.summary==='object'){
        html+='<div class="aa-summary">';
        Object.keys(d.summary).forEach(function(k){ html+='<div class="aa-sum"><div class="aa-sum-k">'+esc(k)+'</div><div class="aa-sum-v">'+esc(d.summary[k])+'</div></div>'; });
        html+='</div>';
      }
      if (Array.isArray(d.columns) && Array.isArray(d.rows) && d.rows.length){
        html+='<div class="aa-tablewrap" tabindex="0" aria-label="Results table"><table class="aa-table"><thead><tr>';
        d.columns.forEach(function(c){ html+='<th scope="col">'+esc(c)+'</th>'; });
        html+='</tr></thead><tbody>';
        d.rows.forEach(function(r){ html+='<tr>'; (r||[]).forEach(function(c){ html+='<td>'+esc(c==null?'':c)+'</td>'; }); html+='</tr>'; });
        html+='</tbody></table></div>';
        if (d.truncated) html+='<div class="aa-more">Showing '+esc(String(d.shown||d.rows.length))+(d.total!=null?' of '+esc(String(d.total)):'')+' — open the page for the full list.</div>';
      }
      // Cross-referenced answers (Phase 1): which modules were read, and what the words mean.
      if (Array.isArray(d.sources) && d.sources.length){
        html+='<div class="aa-sources"><strong>Read from:</strong> '+d.sources.map(esc).join(' \u00b7 ')+'</div>';
      }
      if (Array.isArray(d.definitions) && d.definitions.length){
        html+='<details class="aa-defs"><summary>How this was worked out</summary><ul>';
        d.definitions.forEach(function(t){ html+='<li>'+esc(t)+'</li>'; });
        html+='</ul></details>';
      }
      // People filter (AI Phase 1): the conditions the server understood, as chips. ✕ re-runs the
      // question without that condition (the filter JSON goes back, never the text); "Save as list"
      // stores the FILTER under a name — every later run re-checks the viewer's permissions.
      if (d.filter && Array.isArray(d.filter.predicates) && d.filter.predicates.length){
        var labels = Array.isArray(d.filter.labels) ? d.filter.labels : [];
        html+='<div class="aa-chips" role="group" aria-label="Conditions understood">';
        d.filter.predicates.forEach(function(p, i){
          html+='<span class="aa-chip">'+esc(labels[i] || p.kind)
            + (d.filter.predicates.length > 1 ? '<button type="button" class="aa-chip-x" data-i="'+i+'" title="Remove this condition and search again" aria-label="Remove '+esc(labels[i]||p.kind)+'">✕</button>' : '')
            + '</span>';
        });
        html+='</div><div class="aa-actions aa-filter-actions">'
          + '<button type="button" class="aa-open alt aa-save-list">Save as list</button>'
          + '<span class="aa-actions-note">Conditions are combined with AND.</span></div>';
        html+='<script type="application/json" class="aa-filter-json">'+JSON.stringify(d.filter.predicates).replace(/</g,'\\u003c')+'</script>';
      }
      // Public screens: the screen's name is the link (its real public address from the server);
      // only a same-site relative address is ever turned into a link.
      if (Array.isArray(d.publicLinks) && d.publicLinks.length){
        html+='<ul class="aa-publinks">';
        d.publicLinks.forEach(function(l){
          var url=String((l&&l.url)||'');
          var safe=/^\/(?!\/)[^\s"'<>\\]*$/.test(url);
          html+='<li>'+(safe ? '<a href="'+esc(url)+'" target="_blank" rel="noopener">'+esc(l.label||'Public screen')+'</a>' : '<span>'+esc(l.label||'Public screen')+'</span>')
            +(l.description ? ' <span class="aa-pl-desc">\u2014 '+esc(l.description)+'</span>' : '')
            +(l.expires ? ' <span class="aa-pl-exp">('+esc(l.expires==='No expiry' ? 'no expiry' : 'expires '+l.expires)+')</span>' : '')
            +'</li>';
        });
        html+='</ul>';
        if (d.truncated) html+='<div class="aa-more">Showing '+esc(String(d.shown||d.publicLinks.length))+(d.total!=null?' of '+esc(String(d.total)):'')+' — open Public Screens for the full list.</div>';
      }
      // 2026-11-09: the login's own Income / Expense alerts that were triggered (warnings, not errors).
      if (Array.isArray(d.alerts) && d.alerts.length){
        html+='<ul class="aa-alerts" aria-label="Alerts">';
        d.alerts.forEach(function(x){ html+='<li>'+esc((x && x.message) || '')+'</li>'; });
        html+='</ul>';
      }
      // 2026-11-09: saved income / expense — what was saved and the fund total before and after (only when the
      // server could read both reliably; otherwise its note says why no total is shown).
      if (d.receipt && typeof d.receipt === 'object'){
        var rc = d.receipt;
        html+='<div class="aa-receipt" role="group" aria-label="Saved '+esc(rc.kind||'entry')+'">'
          +'<div class="aa-receipt-h"><span class="aa-receipt-ok" aria-hidden="true">\u2713</span> Saved</div><dl class="aa-receipt-rows">';
        (Array.isArray(rc.rows) ? rc.rows : []).forEach(function(r){ html+='<div><dt>'+esc(r.label||'')+'</dt><dd>'+esc(r.value||'')+'</dd></div>'; });
        html+='</dl>';
        if (rc.totals && typeof rc.totals === 'object'){
          var t = rc.totals;
          // 2026-11-10: the server names the rows (Previous Net Balance / Added Income|Expense / New Net Balance)
          // and signs the change (+ income, − expense)
          html+='<div class="aa-receipt-tot"><div class="aa-receipt-tl">'+esc(t.label||'Net Balance')+'</div>'
            +'<div class="aa-tot-row"><span>'+esc(t.previousLabel||'Previous total')+'</span><span>'+esc(t.previous||'')+'</span></div>'
            +'<div class="aa-tot-row aa-tot-add'+(/^\u2212|^-/.test(String(t.added||'')) ? ' aa-tot-sub' : '')+'"><span>'+esc(t.addedLabel||'Added')+'</span><span>'+esc(t.added||'')+'</span></div>'
            +'<div class="aa-tot-row aa-tot-new"><span>'+esc(t.nextLabel||'New total')+'</span><span>'+esc(t.next||'')+'</span></div></div>';
        } else if (rc.totalsNote){
          html+='<div class="aa-receipt-note">'+esc(rc.totalsNote)+'</div>';
        }
        html+='</div>';
      }
      // 2026-11-11 e-mail conversation: the draft as it stands (text only), and at the end the review card —
      // recipients / subject, optional attachments, and the ONE button that sends (one-time token).
      if (d.preview && typeof d.preview === 'object' && (d.preview.subject || d.preview.body)){
        html+='<div class="aa-preview" role="group" aria-label="Email draft">'
          +(d.preview.subject ? '<div class="aa-preview-s"><span>Subject</span>'+esc(d.preview.subject)+'</div>' : '')
          +(d.preview.body ? '<div class="aa-preview-b">'+esc(d.preview.body)+'</div>' : '')+'</div>';
      }
      if (d.email && d.email.token){
        var em=d.email;
        html+='<div class="aa-fields aa-email-fields" role="table" aria-label="Email to review">';
        (Array.isArray(em.fields) ? em.fields : []).forEach(function(f){
          var has = f && f.value != null && String(f.value) !== '';
          html+='<div class="aa-field" role="row"><div class="aa-field-k" role="rowheader">'+esc(f.label||'')+'</div>'
            + '<div class="aa-field-v'+((has||f.long)?'':' aa-empty')+'" role="cell">'+(has?esc(f.value):(f.long?'Shown above':'Not entered'))+'</div>'
            + ((f.edit||f.ask) ? '<button type="button" class="aa-edit" data-edit="'+esc(f.edit||'')+'"'+(f.ask ? ' data-ask="'+esc(f.ask)+'"' : '')+' aria-label="Edit '+esc(f.label||'')+'"'+dis+'>Edit</button>' : '')
            + '</div>';
        });
        html+='</div>'
          +'<div class="aa-attach" data-maxfiles="'+esc(String(em.maxFiles||10))+'" data-maxfilemb="'+esc(String(em.maxFileMb||15))+'" data-maxtotalmb="'+esc(String(em.maxTotalMb||20))+'">'
          +'<label class="aa-attach-btn'+(old?' aa-off':'')+'">\ud83d\udcce Attach files<input type="file" class="aa-files" multiple'+dis+'></label>'
          +'<span class="aa-attach-hint">Optional \u2014 up to '+esc(String(em.maxFiles||10))+' files, '+esc(String(em.maxFileMb||15))+' MB each.</span>'
          +'<ul class="aa-filelist" aria-label="Attached files"></ul><div class="aa-attach-err" role="alert"></div></div>'
          +'<div class="aa-actions">'
          +'<button type="button" class="aa-open aa-send" data-token="'+esc(em.token)+'" data-endpoint="'+esc(em.endpoint||'/api/ai/email/send')+'"'+dis+'>Send email</button>'
          +'<button type="button" class="aa-open alt aa-send-cancel"'+dis+'>Cancel</button>'
          +'<span class="aa-actions-note">'+(old ? 'Replaced by a newer card.' : 'Nothing is sent until you press Send email.')+'</span></div>';
      }
      // Meeting creation (Phase E): the draft summary and a one-time "Create meeting" button.
      // The token is single-use on the server, so a double click cannot create two meetings.
      if (d.confirm && d.confirm.token){
        if (Array.isArray(d.confirm.fields) && d.confirm.fields.length){
          // 2026-11-08 review card: every value with Required / Optional and an Edit button.
          html+='<div class="aa-fields" role="table" aria-label="Details to review">';
          d.confirm.fields.forEach(function(f){
            var has = f && f.value != null && String(f.value) !== '';
            html+='<div class="aa-field" role="row"><div class="aa-field-k" role="rowheader">'+esc(f.label||'')
              + ' <span class="'+(f.required?'aa-req':'aa-opt')+'">'+(f.required?'Required':'Optional')+'</span></div>'
              + '<div class="aa-field-v'+(has?'':' aa-empty')+'" role="cell">'+(has?esc(f.value):'Not entered')+'</div>'
              + ((f.edit||f.ask) ? '<button type="button" class="aa-edit" data-edit="'+esc(f.edit||'')+'"'+(f.ask ? ' data-ask="'+esc(f.ask)+'"' : '')+' aria-label="Edit '+esc(f.label||'')+'"'+dis+'>Edit</button>' : '')
              + '</div>';
          });
          html+='</div>';
        } else {
          var cs=d.confirm.summary||{};
          html+='<div class="aa-summary">';
          Object.keys(cs).forEach(function(k){ html+='<div class="aa-sum"><div class="aa-sum-k">'+esc(k)+'</div><div class="aa-sum-v aa-sum-sm">'+esc(cs[k])+'</div></div>'; });
          html+='</div>';
        }
        html+='<div class="aa-actions">'
          +'<button type="button" class="aa-open aa-confirm" data-token="'+esc(d.confirm.token)+'" data-label="'+esc(d.confirm.label||'Create meeting')+'" data-endpoint="'+esc(d.confirm.endpoint||'/api/ai-search/confirm')+'"'+dis+'>'+esc(d.confirm.label||'Create meeting')+'</button>'
          +'<button type="button" class="aa-open alt aa-cancel" data-token="'+esc(d.confirm.token)+'" data-endpoint="'+esc(d.confirm.endpoint||'')+'"'+dis+'>Cancel</button>'
          +'<span class="aa-actions-note">'+(old ? 'Replaced by a newer card.' : 'Nothing is saved until you confirm.')+'</span></div>';
      }
      // Suggested answers (categories, funds, AM / PM …) as buttons; typing still works.
      if (Array.isArray(d.choices) && d.choices.length && !(d.confirm && d.confirm.token)){
        html+='<div class="aa-choices" role="group" aria-label="Suggested answers">';
        d.choices.slice(0, 24).forEach(function(c){ html+='<button type="button" class="aa-choice" data-text="'+esc(c)+'"'+dis+'>'+esc(c)+'</button>'; });
        html+='</div>';
      }
    }
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
    return html;
  }

  /* One place wires every button of the results panel (single card or thread). */
  function wireCard(wrap){
    wrap.querySelectorAll('.aa-open').forEach(function(b){
      if (b.disabled) return;
      if (b.classList.contains('aa-send')) { b.addEventListener('click', function(){ sendEmail(b, wrap); }); return; }
      if (b.classList.contains('aa-send-cancel')) { b.addEventListener('click', function(){ wrap.querySelectorAll('.aa-send,.aa-send-cancel,.aa-edit,.aa-files').forEach(function(x){ x.disabled=true; }); chooseAnswer('Cancel'); }); return; }
      if (b.classList.contains('aa-confirm')) { b.addEventListener('click', function(){ confirmMeeting(b.getAttribute('data-token'), b); }); return; }
      if (b.classList.contains('aa-cancel'))  { b.addEventListener('click', function(){ cancelMeeting(b); }); return; }
      if (b.classList.contains('aa-save-list')) { b.addEventListener('click', function(){ saveFilterAsList(b.closest('.aa-turn') || wrap, b); }); return; }
      b.addEventListener('click', function(){ open(b.getAttribute('data-url'), b.getAttribute('data-label'), b.getAttribute('data-nt')==='1' || wantsNewTab(lastQ)); });
    });
    wrap.querySelectorAll('.aa-chip-x').forEach(function(x){
      x.addEventListener('click', function(){ rerunFilterWithout(x.closest('.aa-turn') || wrap, parseInt(x.getAttribute('data-i'), 10)); });
    });
    wrap.querySelectorAll('.aa-choice').forEach(function(b){
      if (b.disabled) return;
      b.addEventListener('click', function(){ wrap.querySelectorAll('.aa-choice').forEach(function(x){ x.disabled=true; }); chooseAnswer(b.getAttribute('data-text')||''); });
    });
    wrap.querySelectorAll('.aa-edit').forEach(function(b){
      if (b.disabled) return;
      // 2026-11-09: a field with choices (category, fund, payment method, main fund…) is asked again with its
      // choices; any other field puts "Change … to " in the message box.
      b.addEventListener('click', function(){
        var ask=b.getAttribute('data-ask');
        if (ask){ wrap.querySelectorAll('.aa-edit').forEach(function(x){ x.disabled=true; }); chooseAnswer(ask); }
        else prefillEdit(b.getAttribute('data-edit')||'');
      });
    });
    wrap.querySelectorAll('.aa-attach').forEach(wireAttach);
    var nc=wrap.querySelector('.aa-newconv');
    if (nc) nc.addEventListener('click', newConversation);
  }
  /* 2026-11-11: attachments of the e-mail review card. The chosen files live on the element only (never stored);
     a card restored after a reload starts with none. The server applies the same limits again. */
  function fmtSize(n){ return n >= 1048576 ? (n/1048576).toFixed(1)+' MB' : Math.max(1, Math.round(n/1024))+' KB'; }
  function wireAttach(box){
    var input=box.querySelector('.aa-files'), list=box.querySelector('.aa-filelist'), err=box.querySelector('.aa-attach-err');
    if (!input || !list) return;
    var maxFiles=parseInt(box.getAttribute('data-maxfiles'),10)||10, maxEach=(parseFloat(box.getAttribute('data-maxfilemb'))||15)*1048576,
        maxAll=(parseFloat(box.getAttribute('data-maxtotalmb'))||20)*1048576;
    box._aaFiles=[]; list.innerHTML=''; if (err) err.textContent='';
    function draw(){
      list.innerHTML='';
      box._aaFiles.forEach(function(f, i){
        var li=document.createElement('li'), nm=document.createElement('span'), x=document.createElement('button');
        nm.textContent=f.name+' ('+fmtSize(f.size)+')';
        x.type='button'; x.className='aa-file-x'; x.textContent='\u2715'; x.setAttribute('aria-label','Remove '+f.name);
        x.addEventListener('click', function(){ box._aaFiles.splice(i,1); if (err) err.textContent=''; draw(); });
        li.appendChild(nm); li.appendChild(x); list.appendChild(li);
      });
    }
    if (input.disabled) return;
    input.addEventListener('change', function(){
      var problems=[];
      Array.prototype.forEach.call(input.files||[], function(f){
        var total=box._aaFiles.reduce(function(t,x){ return t+x.size; }, 0);
        if (box._aaFiles.some(function(x){ return x.name===f.name && x.size===f.size; })) return;
        if (box._aaFiles.length >= maxFiles) problems.push('At most '+maxFiles+' files can be attached.');
        else if (f.size > maxEach) problems.push('\u201c'+f.name+'\u201d is larger than '+Math.round(maxEach/1048576)+' MB.');
        else if (total + f.size > maxAll) problems.push('The attachments together are larger than '+Math.round(maxAll/1048576)+' MB.');
        else box._aaFiles.push(f);
      });
      try{ input.value=''; }catch(e){}
      if (err) err.textContent=problems.filter(function(p,i){ return problems.indexOf(p)===i; }).join(' ');
      draw();
    });
  }
  /* The review card's "Send email": the one-time token and the chosen files go to the server, which sends through the
     Email page's own service. Disabled at once; the server refuses a second use of the token. */
  function sendEmail(btn, wrap){
    var token=btn.getAttribute('data-token'), endpoint=btn.getAttribute('data-endpoint')||'/api/ai/email/send';
    if (!token) return;
    if (_inFlight && !_stopped){ flashHint('Still working on your last question \u2014 one moment\u2026'); return; }
    var box=wrap.querySelector('.aa-attach'), files=(box && box._aaFiles) || [];
    var row=btn.closest ? btn.closest('.aa-actions') : null;
    wrap.querySelectorAll('.aa-send,.aa-send-cancel,.aa-edit,.aa-files,.aa-file-x').forEach(function(x){ x.disabled=true; });
    btn.textContent='Sending\u2026';
    var fd=new FormData(); fd.append('token', token);
    files.forEach(function(f){ fd.append('files', f, f.name); });
    lastQ='Send email (confirmed)';
    reportQuestion('Send the email', 'type');
    var g=++runGen; _stopped=false; _inFlight=true; setResultsBusy(true); busy(true);   // no turn timer: an upload may take longer
    fetch(endpoint, { method:'POST', body:fd, signal:aaSignal() })
      .then(function(r){ return r.json().catch(function(){ return null; }).then(function(j){ return { status:r.status, j:j }; }); }).catch(function(){ return null; })
      .then(function(res){
        if (!aaLive(g)) return;
        busy(false); endTurn();
        var d=res && res.j && res.j.handled ? res.j : null;
        if (!d){
          var why = res && res.status===413 ? 'The attachments are too large, so the email was not sent. Remove a file and say \u201ccompose an email\u201d again if the draft has closed.'
                  : (res && res.j && (res.j.error || res.j.message)) || 'The email could not be sent. Please try again, or use the Email page.';
          d={ handled:true, denied:false, intent:'email.failed', answer:String(why) };
        }
        _aaServerPending=isPendingAnswer(d);
        if (row && !_thread.active && !uiHook()){ row.remove(); }
        renderCard({ intent:d.intent||'data', category:'Data', source:'Church Data', confidence:1, answer:d.answer||'',
          pages:(d.link && d.link.url && !d.denied) ? [{label:d.link.label||'Open', url:d.link.url}] : [], suggestedAction:'none', data:d });
        setDebug({ intent:'Data: '+(d.intent||'email'), source:'Church Data', result: d.intent==='email.sent' ? 'Done' : 'Not done' });
        if (speaking) speak(stripUrls(d.answer||''));
      });
  }
  /* Phase E: the "Create meeting" button. Disabled at once (client side), and the server consumes
     the token on first use, so a repeat click or a stale transcript button creates nothing. */
  function confirmMeeting(token, btn){
    if (!token) return;
    // AI Phase 4: the same button confirms an action proposal — the card says which endpoint and label.
    var label = (btn && btn.getAttribute && btn.getAttribute('data-label')) || 'Create meeting';
    var endpoint = (btn && btn.getAttribute && btn.getAttribute('data-endpoint')) || '/api/ai-search/confirm';
    var isAction = endpoint.indexOf('/api/ai/actions/') === 0;
    var row = btn && btn.closest ? btn.closest('.aa-actions') : null;
    if (row){ row.querySelectorAll('button').forEach(function(x){ x.disabled=true; }); }
    if (btn){ btn.textContent= isAction ? 'Working…' : 'Creating…'; }
    if (_inFlight && !_stopped){ flashHint('Still working on your last question — one moment…'); if(row){ row.querySelectorAll('button').forEach(function(x){ x.disabled=false; }); } if(btn) btn.textContent=label; return; }
    lastQ=label+' (confirmed)';
    reportQuestion(isAction ? 'Yes, go ahead' : 'Yes, create it', 'type');  // the confirm click is its own turn
    var g=++runGen; _stopped=false; _inFlight=true; setResultsBusy(true);
    _turnTimer = aaAfter(AA_TIMEOUT_MS, function(){ if (aaLive(g)) onTurnTimeout(g); });
    busy(true);
    fetch(endpoint, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ token:token }), signal:aaSignal() })
      .then(function(r){ return r.ok ? r.json() : null; }).catch(function(){ return null; })
      .then(function(d){
        if (!aaLive(g)) return;
        busy(false); endTurn();
        _aaServerPending=isPendingAnswer(d);               // 2026-11-11: a follow-up question keeps the conversation open
        if (!d || !d.handled){ d= isAction ? { handled:true, denied:false, intent:'action.failed', answer:'The action could not be completed, so nothing was saved. Please try again or use the page directly.' }
                                           : { handled:true, denied:false, intent:'meeting.error', answer:'The meeting could not be created. Please try again or use the Meetings page.' }; }
        if (row && !_thread.active){ row.remove(); }
        renderCard({ intent:d.intent||'data', category:'Data', source:'Church Data', confidence:1, answer:d.answer||'',
          pages:(d.link && d.link.url && !d.denied) ? [{label:d.link.label||'Open', url:d.link.url}] : [], suggestedAction:'none', data:d });
        setDebug({ intent:'Data: '+(d.intent||'meeting'), source:'Church Data', result: (d.created || d.executed) ? 'Done' : (d.denied ? 'Permission denied' : 'Not done') });
        if (speaking) speak(stripUrls(d.answer||'') + (Array.isArray(d.choices) && d.choices.length && d.choices.length <= 4 ? ' ' + d.choices.join(', ') + '.' : ''));
      });
  }
  /* AI Phase 1: the filter JSON the card carries (what the server understood). */
  function filterPredicates(wrap){
    var tag = wrap ? wrap.querySelector('.aa-filter-json') : null;
    try { return tag ? JSON.parse(tag.textContent) : null; } catch(e){ return null; }
  }
  function renderFilterAnswer(d){
    renderCard({ intent:d.intent||'data', category:'Data', source:'Church Data', confidence:1, answer:d.answer||'',
      pages:(d.link && d.link.url && !d.denied) ? [{label:d.link.label||'Open', url:d.link.url}] : [], suggestedAction:'none', data:d });
  }
  function rerunFilterWithout(wrap, i){
    var preds = filterPredicates(wrap);
    if (!preds || preds.length < 2) return;
    preds.splice(i, 1);
    if (_inFlight && !_stopped){ flashHint('Still working on your last question — one moment…'); return; }
    var g=++runGen; _stopped=false; _inFlight=true; setResultsBusy(true); busy(true);
    _turnTimer = aaAfter(AA_TIMEOUT_MS, function(){ if (aaLive(g)) onTurnTimeout(g); });
    fetch('/api/ai-search/filter', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ predicates:preds }), signal:aaSignal() })
      .then(function(r){ return r.json().then(function(j){ return { ok:r.ok, j:j }; }); }).catch(function(){ return null; })
      .then(function(res){
        if (!aaLive(g)) return;
        busy(false); endTurn();
        var d = res && res.ok ? res.j : { handled:true, denied:false, intent:'filter.error', answer:(res && res.j && res.j.error) || 'The search could not be run. Please try again.' };
        renderFilterAnswer(d);
        setDebug({ intent:'Data: '+(d.intent||'filter'), source:'Church Data', result: d.denied ? 'Permission denied' : 'Answered (data)' });
      });
  }
  function saveFilterAsList(wrap, btn){
    var preds = filterPredicates(wrap);
    if (!preds || !preds.length) return;
    var name = window.prompt('Name for this list (e.g. "Women 18–30 not in a group"):', '');
    if (name == null) return;
    name = String(name).trim();
    if (!name){ toast('Please give the list a name.'); return; }
    if (btn) btn.disabled = true;
    fetch('/api/ai-search/saved-lists', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ name:name, predicates:preds }) })
      .then(function(r){ return r.json().then(function(j){ return { ok:r.ok, status:r.status, j:j }; }); })
      .then(function(res){
        if (res.ok){ toast('Saved — find it under More \u2192 Saved Lists.'); if (btn) btn.textContent='Saved \u2713'; }
        else { toast((res.j && res.j.error) || (res.status===403 ? 'You do not have access to Saved Lists.' : 'The list could not be saved.')); if (btn) btn.disabled=false; }
      })
      .catch(function(){ toast('The list could not be saved.'); if (btn) btn.disabled=false; });
  }
  function cancelMeeting(btn){
    var row = btn && btn.closest ? btn.closest('.aa-actions') : null;
    var endpoint = (btn && btn.getAttribute && btn.getAttribute('data-endpoint')) || '';
    var token = btn && btn.getAttribute ? btn.getAttribute('data-token') : null;
    var threaded = _thread.active && !uiHook();
    if (row && !threaded){ row.remove(); }
    _aaServerPending=false;
    var isAction = endpoint.indexOf('/api/ai/actions/') === 0 && token;
    if (isAction){
      // AI Phase 4: reject the proposal itself (the server marks it REJECTED; the token can never be used).
      // 2026-11-11: a guided conversation may answer with its next question ("add a different member?").
      fetch('/api/ai/actions/reject', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ token:token }) })
        .then(function(r){ return r.ok ? r.json() : null; }).catch(function(){ return null; })
        .then(function(d){
          if (d && d.handled && d.pending===true){
            _aaServerPending=true; lastQ='Cancel';
            renderCard({ intent:d.intent||'action.cancelled', category:'Data', source:'Church Data', confidence:1, answer:d.answer||'', pages:[], suggestedAction:'none', data:d });
            if (speaking) speak(stripUrls(d.answer||''));
          } else cancelDone(true, threaded);
        });
      return;
    }
    fetch('/api/ai-search/data', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ query:'cancel' }) }).catch(function(){});
    cancelDone(false, threaded);
  }
  function cancelDone(isAction, threaded){
    var msg = isAction ? 'Okay — nothing was saved.' : 'Okay — the meeting was not created.';
    if (threaded){
      lastQ='Cancel';
      renderCard({ intent:'guided.cancelled', category:'Data', source:'Church Data', confidence:1, answer:msg, pages:[], suggestedAction:'none',
                   data:{ handled:true, denied:false, intent:'guided.cancelled', answer:msg } });
      return;
    }
    if (_thread.active) clearThread();
    toast(isAction ? 'Okay — nothing was done.' : msg);
  }
  function toast(msg){
    if (uiCall('notice', msg)) return;
    var wrap=results(); if(!wrap) return;
    var d=document.createElement('div'); d.className='aa-toast'; d.textContent=msg; wrap.style.display='block';
    wrap.appendChild(d); setTimeout(function(){ try{ wrap.removeChild(d); }catch(e){} }, 5000);
  }

  /* ════════════════════════ Guide ════════════════════════ */
  function openGuide(){
    var el=document.getElementById('aaGuide'); if(!el) return;
    // 2026-11-10: with the bar off-screen (Genius Assistant is the interface) the guide opens in a window instead.
    var inModal=!!(el.closest && el.closest('.ga-engine-bar'));
    if (inModal){ showHelpModal('Guide \u2014 all commands', '<div id="aaGuideModal"></div>'); el=document.getElementById('aaGuideModal'); if(!el) return; }
    else if(!el.classList.contains('hidden')){ el.classList.add('hidden'); return; }
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
    var search=el.querySelector('#aaGuideSearch');
    if(search) search.addEventListener('input', function(){ var q=this.value.toLowerCase().trim();
      el.querySelectorAll('.aa-cmd').forEach(function(c){ var hay=(c.textContent+' '+(c.getAttribute('data-keys')||'')).toLowerCase();
        c.style.display=(!q||hay.indexOf(q)>=0)?'':'none'; }); });
    el.querySelectorAll('.aa-cmd').forEach(function(c){ c.addEventListener('click', function(){
      var inp=document.getElementById('aiSearchInput'); var run=c.getAttribute('data-run');
      if (inModal){ var ov=document.querySelector('.aa-modal-ov'); if(ov && ov.parentNode) ov.parentNode.removeChild(ov); }
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

  /* ══ Cancellation plumbing ════════════════════════════════════════════════
     Stop has to terminate the operation, not just repaint the toolbar, so
     three things are tracked for every Voice/Converse turn:
       runGen   a generation counter. Each request captures the value current
                when it started; stopAll() bumps it, so every queued callback
                sees a stale value and returns without touching the UI.
       _aborts  AbortControllers for the assistant's own HTTP calls, so Stop
                tears the requests down rather than merely ignoring replies.
       _timers  every pending setTimeout (deferred navigation, TTS un-mute),
                so Stop cannot leave a page-change queued behind it.
     _stopped additionally gates text-to-speech, which is fire-and-forget and
     has no handle of its own beyond speechSynthesis.cancel(). */
  var runGen = 0, _aborts = [], _timers = [], _stopped = false;
  var aaPhase = 'idle';        // idle | listening | processing | done
  var _assistBusy = false;     // the assistant owns the phase while true

  function aaLive(g){ return g === runGen && !_stopped; }
  function aaSignal(){
    if (typeof AbortController === 'undefined') return undefined;
    var ac = new AbortController(); _aborts.push(ac); return ac.signal;
  }
  function aaAfter(ms, fn){
    var id = setTimeout(function(){
      var ix = _timers.indexOf(id); if (ix >= 0) _timers.splice(ix, 1);
      fn();
    }, ms);
    _timers.push(id); return id;
  }
  function aaIsActive(){ return aaPhase === 'listening' || aaPhase === 'processing'; }

  /* The single Stop entry point. Terminates everything the active Voice or
     Converse operation owns, then returns the toolbar to its resting state. */
  function stopAll(reason){
    runGen++;                       // orphan every callback already in flight
    _stopped = true;
    endTurn();                      // the bar accepts a new question again

    // 1. Assistant HTTP requests (/api/ai-assist, /api/help/search, data search)
    for (var i = 0; i < _aborts.length; i++){ try{ _aborts[i].abort(); }catch(e){} }
    _aborts.length = 0;

    // 2. Deferred navigation / un-mute timers
    for (var j = 0; j < _timers.length; j++){ try{ clearTimeout(_timers[j]); }catch(e){} }
    _timers.length = 0;

    // 3. Microphone, speech recognition, audio analyser and the voice request
    //    in flight. cancel() discards pending audio; stop() would flush and
    //    send it, which is the opposite of what Stop means here.
    if (recorder){
      try {
        if (typeof recorder.cancel === 'function') recorder.cancel();
        else if (recorder.isRecording && recorder.isRecording()) recorder.stop();
      } catch(e){}
    }

    // 4. Text-to-speech playback
    try { if ('speechSynthesis' in window) window.speechSynthesis.cancel(); } catch(e){}

    // 5. UI back to rest
    busy(false);
    aaPhase = 'idle';
    refreshModeButtons();
    var hint = document.getElementById('aaHint');
    if (hint) hint.textContent = (mode === 'voice')
      ? 'Stopped. Click the microphone to start listening again.'
      : (mode === 'converse')
        ? 'Stopped. Click Converse to continue the conversation.'
        : phaseHint('idle');
    setDebug({ result:'Cancelled by user' });
    log('Stopped: ' + (reason || 'user') + ' (mic released, requests aborted, audio stopped)');
  }

  /* ── Phase → hint text. Spells out what the user should do next in each
        mode, and that Stop is available while something is running. ──────── */
  function phaseHint(p){
    if (mode === 'voice'){
      if (p === 'listening')  return 'Listening… Speak now. Click the Stop button to cancel.';
      if (p === 'processing') return 'Processing… Click the Stop button to cancel.';
      if (p === 'done')       return 'Done. Click the microphone to speak again.';
      return 'Click the microphone, then speak your question.';
    }
    if (mode === 'converse'){
      if (p === 'listening')  return 'Listening… Speak when you are ready. Click the Stop button to cancel.';
      if (p === 'processing') return 'Processing your answer… Click the Stop button to cancel.';
      if (p === 'done')       return 'Ready. Click Converse to keep the conversation going.';
      return 'Speak when listening mode is displayed. Click the Stop button at any time to stop.';
    }
    return '';   // 2026-11-10: no idle instruction text — Genius Assistant is the only entry point
  }

  function setPhase(p){
    aaPhase = p;
    refreshModeButtons();
    var hint = document.getElementById('aaHint');
    if (hint) hint.textContent = phaseHint(p);
  }

  /* ── Mode buttons. The active Voice/Converse icon becomes a Stop control
        while that mode is listening or processing, and reverts afterwards.
        Icons, titles and aria-labels are kept in step so the state is
        announced to screen readers, not just drawn. ─────────────────────── */
  var AA_MODE_ICON  = { type:'\ud83d\udd0e', voice:'\ud83c\udfa4', converse:'\ud83d\udde3' };
  var AA_MODE_TITLE = { type:'Type', voice:'Voice', converse:'Converse' };
  var AA_STOP_ICON  = '\u23f9';

  function refreshModeButtons(){
    ['type','voice','converse'].forEach(function(k){
      var b = document.getElementById('aaMode_' + k);
      if (!b) return;
      b.classList.toggle('on', k === mode);
      b.classList.remove('rec','aa-proc');
      var isStop = (k === mode) && (k === 'voice' || k === 'converse') && aaIsActive();
      if (isStop){
        b.classList.add(aaPhase === 'processing' ? 'aa-proc' : 'rec');
        b.textContent = AA_STOP_ICON;
        var what = (k === 'voice') ? 'Voice' : 'Converse';
        var why  = (aaPhase === 'processing') ? 'cancel processing' : 'stop listening';
        b.title = 'Stop ' + what + ' \u2014 ' + why;
        b.setAttribute('aria-label', 'Stop ' + what);
      } else {
        b.textContent = AA_MODE_ICON[k];
        b.title = AA_MODE_TITLE[k];
        b.setAttribute('aria-label', AA_MODE_TITLE[k]);
      }
      b.setAttribute('aria-pressed', k === mode ? 'true' : 'false');
    });
  }

  function curMode(){ return mode.charAt(0).toUpperCase()+mode.slice(1); }
  function busy(on){ if (uiCall('busy', !!on)) return; var b=document.getElementById('aiSearchBtn'); if(b){ b.disabled=!!on; b.textContent=on?'⏳ Working…':'Submit'; } }
  function persist(){ try{ localStorage.setItem('aa.mode',mode); localStorage.setItem('aa.debug', document.getElementById('aaDebug') && !document.getElementById('aaDebug').classList.contains('hidden') ? '1':'0'); }catch(e){} }
  function persistConv(){ try{ sessionStorage.setItem('aa.conv', JSON.stringify(conv)); sessionStorage.setItem('aa.convlog', JSON.stringify(_convLog.slice(-12))); }catch(e){} }
  function restoreConv(){ try{ var c=JSON.parse(sessionStorage.getItem('aa.conv')||'null'); if(c) conv=c; var l=JSON.parse(sessionStorage.getItem('aa.convlog')||'null'); if(l) _convLog=l; }catch(e){} }

  function setMode(m,opts){
    opts=opts||{};
    if (m!=='converse' && mode==='converse'){ resetConv(); _convLog=[]; persistConv(); }
    // Leaving a live Voice/Converse mode must actually release the microphone
    // and drop whatever is in flight, not merely repaint the toolbar.
    if ((mode==='voice'||mode==='converse') && m!==mode){
      runGen++;
      for (var ai=0; ai<_aborts.length; ai++){ try{ _aborts[ai].abort(); }catch(e){} }
      _aborts.length=0;
      for (var ti=0; ti<_timers.length; ti++){ try{ clearTimeout(_timers[ti]); }catch(e){} }
      _timers.length=0;
      if (recorder){
        try{ if (typeof recorder.cancel==='function') recorder.cancel();
             else if (recorder.isRecording && recorder.isRecording()) recorder.stop(); }catch(e){}
      }
      try{ if ('speechSynthesis' in window) window.speechSynthesis.cancel(); }catch(e){}
    }
    mode=m; speaking=(m==='voice'||m==='converse');
    _stopped=false; _assistBusy=false;      // a fresh mode selection re-enables output
    aaPhase='idle';
    refreshModeButtons();
    var hint=document.getElementById('aaHint');
    if(hint) hint.textContent = phaseHint('idle');
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
    setPhase(listening==='Yes' ? 'listening' : 'idle');
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
          onState:function(on){ setDebug({ transcript: on?'(listening…)':dbg.transcript }); },
          onStatus:function(){},
          // The engine reports listening / processing / idle; mirror it onto the
          // toolbar so the active icon is a Stop button exactly while something
          // is running. While the assistant itself is working it owns the phase,
          // so the engine going quiet must not downgrade Processing to Listening.
          onPhase:function(ph){
            if (mode!=='voice' && mode!=='converse') return;
            if (_stopped || _assistBusy) return;
            if (ph==='idle'){ if (aaIsActive()) setPhase('done'); return; }
            setPhase(ph);
          },
          onTranscript:function(t){
            if (_stopped) return;                    // cancelled before this landed
            var inp=document.getElementById('aiSearchInput'); if(inp) inp.value=t;
            setDebug({transcript:t}); runAssist(t);
          },
          // The server says WHY it refused (STAFF_ONLY / FEATURE_OFF / VOICE_OFF / LIMIT);
          // show that, never a blanket "limit reached" (the quota may be fine).
          onDisabled:function(st, why){ voiceErr=voiceRefusalMessage(st, why); toast(voiceErr); }
        });
      }catch(e){ voiceErr='Unable to start voice mode. Please check the AI service configuration.'; toast(voiceErr); return false; }
    }
    if (!recorder){ voiceErr='Unable to start voice mode. Please check the AI service configuration.'; toast(voiceErr); return false; }
    if (recorder.supported===false){ voiceErr='Voice mode needs a recent Chrome, Edge, or Safari.'; toast(voiceErr); return false; }
    try{ if(!recorder.isRecording()) recorder.start(); }catch(e){ voiceErr='Unable to start microphone. Please allow mic access.'; toast(voiceErr); return false; }
    setPhase('listening');
    return true;
  }
  function toastIfVoice(){ if(voiceErr) toast(voiceErr); }
  // Same rules as income/expense/voice-nav: the reason code wins, then the server's
  // own message (staff-only, church feature off), then a neutral fallback.
  function voiceRefusalMessage(st, why){
    var reason = (why && why.reason) || '';
    if (reason === 'LIMIT' || (!reason && st && st.voiceAvailable === false && st.voiceEnabled !== false)) return 'Voice limit reached. Please contact your administrator.';
    if (reason === 'VOICE_OFF' || (st && st.voiceEnabled === false)) return 'Voice is disabled by your administrator.';
    if (why && why.error) return why.error;
    return 'Voice is not available for this login. Please contact your administrator.';
  }

  /* ════════════════════════ Styles + UI ════════════════════════ */
  function injectStyles(){
    if (document.getElementById('aa-style')) return;
    var s=document.createElement('style'); s.id='aa-style';
    s.textContent=[
      // Match the home page (.ai-search-wrap / .ai-search-bar / .ai-search-btn)
      // exactly so the AI search box is identical on every page.
      '.aa-host{padding:18px 24px 0;margin-bottom:18px;}',
      // the engine's controls when Genius Assistant is the interface: in the page, never on screen, no space taken
      '.ga-engine-bar{position:fixed !important;left:-9999px !important;top:0 !important;width:1px !important;height:1px !important;overflow:hidden !important;visibility:hidden !important;margin:0 !important;padding:0 !important;border:0 !important;}',
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
      // Processing: the same Stop control, amber rather than red, so the two
      // active states are distinguishable at a glance.
      '.aa-seg button.aa-proc{background:#f39c12;color:#fff;animation:aaPulse 1.1s infinite;}',
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
      '.aa-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:10px;}',
      '.aa-publinks{list-style:none;margin:10px 0 0;padding:0;display:flex;flex-direction:column;gap:6px;}',
      '.aa-publinks li{border:1px solid #eadfe6;background:#fbf7f9;border-radius:9px;padding:8px 11px;font-size:13.5px;line-height:1.4;}',
      '.aa-publinks a{color:#673147;font-weight:700;text-decoration:underline;}',
      '.aa-pl-desc{color:#555;}',
      '.aa-pl-exp{color:#8a7a82;font-size:12px;}',
      '.aa-actions-note{font-size:11.5px;color:#9a7d8b;}',
      /* 2026-11-08 conversation thread, suggested answers, review card */
      '#aiResultsWrap .aa-thread-tools{display:flex;align-items:center;gap:8px;}',
      '#aiResultsWrap .aa-newconv{border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-size:12px;font-weight:600;padding:4px 10px;border-radius:7px;cursor:pointer;}',
      '#aiResultsWrap .aa-turn{padding:10px 0;border-top:1px solid #f3edf0;}',
      '#aiResultsWrap .aa-turn:first-of-type{border-top:none;padding-top:4px;}',
      '#aiResultsWrap .aa-you{display:flex;justify-content:flex-end;margin-bottom:6px;}',
      '#aiResultsWrap .aa-you-b{background:#673147;color:#fff;border-radius:12px 12px 2px 12px;padding:6px 11px;font-size:13px;max-width:85%;overflow-wrap:anywhere;}',
      '#aiResultsWrap .aa-turn-old .ai-answer{color:#777;}',
      '#aiResultsWrap .aa-turn-old .aa-fields,#aiResultsWrap .aa-turn-old .aa-choices{opacity:.6;}',
      '.aa-choices{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px;}',
      '.aa-choice{border:1.5px solid #c5a0b5;background:#fbf7f9;color:#673147;font-size:13px;font-weight:600;padding:6px 12px;border-radius:16px;cursor:pointer;min-height:34px;}',
      '.aa-choice:disabled,.aa-edit:disabled,.aa-open:disabled{opacity:.55;cursor:default;}',
      '.aa-alerts{margin:10px 0 0;padding:8px 12px 8px 30px;border:1px solid #f0d9a8;background:#fff8e6;border-radius:9px;color:#7a5a12;font-size:13px;line-height:1.5;}',
      '.aa-alerts li{margin:2px 0;}',
      '.aa-receipt{margin-top:10px;border:1.5px solid #c8e6c9;background:#f4faf4;border-radius:10px;padding:10px 12px;font-size:13px;color:#24402a;}',
      '.aa-receipt-h{font-weight:700;color:#2e7d32;margin-bottom:6px;}',
      '.aa-receipt-ok{display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;border-radius:50%;background:#2e7d32;color:#fff;font-size:11px;margin-right:4px;}',
      '.aa-receipt-rows{margin:0;display:grid;gap:3px;}',
      '.aa-receipt-rows>div{display:grid;grid-template-columns:minmax(0,42%) minmax(0,1fr);gap:8px;}',
      '.aa-receipt-rows dt{color:#4e6a52;font-weight:600;}',
      '.aa-receipt-rows dd{margin:0;overflow-wrap:anywhere;}',
      '.aa-receipt-tot{margin-top:9px;padding-top:8px;border-top:1px dashed #b7d8b9;}',
      '.aa-receipt-tl{font-weight:700;margin-bottom:4px;}',
      '.aa-tot-row{display:flex;justify-content:space-between;gap:10px;padding:1px 0;font-variant-numeric:tabular-nums;}',
      '.aa-tot-add{color:#2e7d32;}',
      '.aa-tot-add.aa-tot-sub{color:#c62828;}',
      '.aa-tot-new{font-weight:700;border-top:1px solid #b7d8b9;margin-top:3px;padding-top:4px;}',
      '.aa-receipt-note{margin-top:8px;color:#5f6b61;font-size:12.5px;}',
      '.aa-fields{margin-top:10px;border:1px solid #eadfe6;border-radius:9px;overflow:hidden;}',
      '.aa-preview{margin-top:10px;border:1px solid #eadfe6;border-radius:10px;background:#fff;overflow:hidden;font-size:13.5px;color:#2b2730;}',
      '.aa-preview-s{padding:9px 12px;border-bottom:1px solid #f0e8ed;font-weight:700;overflow-wrap:anywhere;}',
      '.aa-preview-s span{display:block;font-size:11px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:#8a7a84;margin-bottom:2px;}',
      '.aa-preview-b{padding:10px 12px;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.5;max-height:320px;overflow:auto;}',
      '.aa-attach{margin-top:10px;font-size:13px;color:#4a4350;}',
      '.aa-attach-btn{display:inline-flex;align-items:center;gap:6px;padding:7px 12px;border:1.5px solid #d9c9d3;border-radius:8px;background:#fff;color:#673147;font-weight:700;cursor:pointer;}',
      '.aa-attach-btn:focus-within{outline:2px solid #673147;outline-offset:2px;}',
      '.aa-attach-btn.aa-off{opacity:.55;cursor:default;}',
      '.aa-attach-btn input{position:absolute;width:1px;height:1px;opacity:0;pointer-events:none;}',
      '.aa-attach-hint{margin-left:8px;font-size:12px;color:#8a7a84;}',
      '.aa-filelist{list-style:none;margin:6px 0 0;padding:0;display:flex;flex-direction:column;gap:4px;}',
      '.aa-filelist li{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:5px 9px;border:1px solid #eadfe6;border-radius:7px;background:#fbf9fa;overflow-wrap:anywhere;}',
      '.aa-file-x{border:none;background:none;color:#8a2c2c;font-weight:700;cursor:pointer;padding:2px 6px;border-radius:5px;}',
      '.aa-attach-err{color:#a12626;font-size:12.5px;margin-top:4px;}',
      '.aa-field{display:grid;grid-template-columns:minmax(0,38%) minmax(0,1fr) auto;align-items:center;gap:8px;padding:8px 10px;border-top:1px solid #f3edf0;font-size:13px;}',
      '.aa-field:first-child{border-top:none;}',
      '.aa-field-k{color:#5a4450;font-weight:600;}',
      '.aa-field-v{color:#333;overflow-wrap:anywhere;}',
      '.aa-field-v.aa-empty{color:#9a8a92;font-style:italic;}',
      '.aa-req,.aa-opt{display:inline-block;font-size:10.5px;font-weight:700;border-radius:8px;padding:1px 6px;margin-left:4px;vertical-align:1px;}',
      '.aa-req{background:#f6e3ea;color:#8a2a4d;}',
      '.aa-opt{background:#eef1f5;color:#5a6472;}',
      '.aa-edit{border:1px solid #c5a0b5;background:#fff;color:#673147;font-size:12px;font-weight:600;padding:4px 10px;border-radius:7px;cursor:pointer;min-height:30px;}',
      '@media (max-width:560px){.aa-field{grid-template-columns:minmax(0,1fr) auto;}.aa-field-k{grid-column:1 / -1;}}',
      '#aiResultsWrap .aa-sum-v.aa-sum-sm{font-size:13.5px;}',
      '.aa-toast{margin-top:8px;font-size:12.5px;color:#a23;background:#fdeef0;border:1px solid #f3cfd5;border-radius:7px;padding:7px 10px;}',
      '#aiResultsWrap .ai-results-panel{margin-top:10px;border:1px solid #eadfe6;border-radius:12px;background:#fff;padding:14px 16px;box-shadow:0 1px 3px rgba(0,0,0,.05);max-height:min(65vh,560px);overflow:auto;}',
      '#aiResultsWrap[aria-busy="true"] .ai-results-panel{opacity:.7;}',
      '#aiResultsWrap .ai-results-hdr{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;}',
      '#aiResultsWrap .ai-results-title{font-weight:700;color:#673147;font-size:14px;}',
      '#aiResultsWrap .ai-results-close{border:none;background:none;cursor:pointer;color:#aaa;font-size:14px;}',
      '#aiResultsWrap .ai-answer{font-size:13.5px;color:#444;line-height:1.55;}',
      '#aiResultsWrap .ai-answer.ai-denied{color:#b42424;}',
      '#aiResultsWrap .aa-summary{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px;}',
      '#aiResultsWrap .aa-sum{flex:1 1 120px;min-width:110px;background:#fbf7f9;border:1px solid #eadfe6;border-radius:9px;padding:8px 10px;}',
      '#aiResultsWrap .aa-sum-k{font-size:11px;color:#7a6a72;text-transform:uppercase;letter-spacing:.03em;}',
      '#aiResultsWrap .aa-sum-v{font-size:15px;font-weight:700;color:#673147;margin-top:2px;}',
      '#aiResultsWrap .aa-tablewrap{margin-top:10px;max-height:320px;overflow:auto;border:1px solid #eadfe6;border-radius:9px;}',
      '#aiResultsWrap .aa-table{width:100%;border-collapse:collapse;font-size:12.5px;}',
      '#aiResultsWrap .aa-table th{position:sticky;top:0;background:#f6eef3;color:#673147;text-align:left;padding:7px 9px;font-weight:700;border-bottom:1px solid #eadfe6;white-space:nowrap;}',
      '#aiResultsWrap .aa-table td{padding:6px 9px;border-bottom:1px solid #f3edf0;color:#444;vertical-align:top;}',
      '#aiResultsWrap .aa-table tr:last-child td{border-bottom:none;}',
      '#aiResultsWrap .aa-more{margin-top:6px;font-size:12px;color:#888;}',
      '#aiResultsWrap .aa-sources{margin-top:8px;font-size:12px;color:#555;}',
      '#aiResultsWrap .aa-defs{margin-top:6px;font-size:12px;color:#555;}',
      '#aiResultsWrap .aa-defs summary{cursor:pointer;color:#673147;font-weight:600;}',
      '#aiResultsWrap .aa-defs ul{margin:6px 0 0 18px;padding:0;}',
      '#aiResultsWrap .aa-defs li{margin:2px 0;}',
      '#aiResultsWrap .aa-chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px;}',
      '#aiResultsWrap .aa-chip{display:inline-flex;align-items:center;gap:4px;background:#f3eef1;color:#4f2538;border:1px solid #e3d5dc;border-radius:14px;padding:3px 10px;font-size:12px;font-weight:600;}',
      '#aiResultsWrap .aa-chip-x{border:none;background:none;color:#8a5a6c;cursor:pointer;font-size:12px;line-height:1;padding:0 2px;}',
      '#aiResultsWrap .aa-chip-x:hover{color:#c62828;}',
      '#aiResultsWrap .aa-filter-actions{margin-top:8px;}',
      // Phase F — small screens: the input takes a full row, controls wrap under it,
      // summary tiles and suggestion rows stack, the panel stays scrollable.
      '@media (max-width:640px){'
        +'.aa-host{padding:12px 12px 0;margin-bottom:12px;}'
        +'.aa-searchbar{padding:8px 10px;gap:8px;}'
        +'#aiSearchInput.aa-input,.ai-search-wrap .ai-search-input{flex:1 1 100%;min-width:0;font-size:16px;}'
        +'.aa-searchbtn,.ai-search-wrap .ai-search-btn{flex:1 1 auto;padding:0 12px;}'
        +'#aiResultsWrap .ai-results-panel{padding:10px 12px;max-height:70vh;}'
        +'#aiResultsWrap .aa-sum{flex:1 1 100%;min-width:0;}'
        +'#aiResultsWrap .aa-table{font-size:12px;}'
        +'.aa-suggest{flex-direction:column;align-items:stretch;}'
        +'.aa-suggest-actions{justify-content:flex-end;}'
        +'.aa-actions{flex-direction:column;align-items:stretch;}'
        +'.aa-actions .aa-open{width:100%;}'
      +'}',
      '@media (prefers-reduced-motion:reduce){.aa-searchbar,.aa-searchbtn,.ai-search-wrap .ai-search-btn{transition:none;}}',
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
  /* 2026-11-08 — may this login open the page? Only the server's answer counts (GET /api/ai-search/nav, else the
     session's navAccess); with neither, no link is shown. */
  function navOk(url){
    if (serverNav && Object.prototype.hasOwnProperty.call(serverNav, url)) return serverNav[url] === true;
    var acc = window.CGP_NAV_ACCESS;
    return !!(acc && acc[url] === true);
  }
  function createHelpHtml(){
    var ex = [
      ['Meetings', ['Create a meeting', 'Schedule a Bible Study next Sunday at 10 AM.']],
      ['Income', ['Record an income of $500 for Sunday offerings.']],
      ['Expenses', ['Record an expense of $150 for electricity.']],
      ['Transaction types', ['Create a transaction type called Mission Offering.']],
      ['Funds', ['Create a fund called Building Fund.']],
      ['Sub-funds', ['Create a subfund called Youth Ministry under the Building Fund.']],
      ['Groups', ['Add a group', 'Add a member to the Youth group']],
      ['Pledge campaigns', ['Create a pledge campaign called Building Fund 2027.']],
      ['Email', ['Compose an email', 'Send an email to the members']]];
    var manage = [
      ['Meetings', 'Meetings', '/meetings'], ['Income entries', 'Income', '/income'], ['Expense entries', 'Expense', '/expense'],
      ['Transaction types', 'Account Settings \u2192 Transaction Type', '/transactiontype'],
      ['Funds and sub-funds', 'Account Settings \u2192 Fund', '/fund'], ['Purposes (expense categories)', 'Account Settings \u2192 Purpose', '/purpose']];
    var h = '<h4>Create new records \u2014 type it or say it</h4>'
      + '<p>Type your request and press Enter, or click the \ud83c\udfa4 microphone and say it. Use everyday words. '
      + 'If anything is missing, AI Search asks you one question at a time, then shows a review card. '
      + 'Nothing is saved until you press <b>Save</b> (or <b>Create meeting</b>) or say \u201cyes\u201d; say \u201ccancel\u201d to stop.</p>';
    ex.forEach(function(e){
      h += '<div class="aa-ex"><b style="font-style:normal;color:#673147;">' + esc(e[0]) + ':</b> '
         + e[1].map(function(t){ return '\u201c' + esc(t) + '\u201d'; }).join(' \u00b7 ') + '</div>';
    });
    h += '<p style="font-size:12.5px;color:#666;">You need the same permission as on the page (for example Accounting Settings for funds and transaction types). '
      + 'A meeting\u2019s category must be one of your church\u2019s meeting categories, and only one-time meetings are created here. A name that already exists is never created twice.</p>';
    h += '<p style="font-size:12.5px;color:#666;"><b>Email:</b> write it with AI or yourself, choose the recipients, attach files on the review card \u2014 it is sent only when you press <b>Send email</b>. '
      + '<b>Families, Bank Import, Bank Sync, Payroll, Events, Ministry, Certificates, Public Screens, Ticketing</b> and <b>Account &amp; Privacy</b> are done on their own pages: ask (\u201cadd a family\u201d, \u201crun payroll\u201d) and you get a link to the page.</p>';
    h += '<h4>Changing or deleting existing records</h4>'
      + '<p>AI Search only <b>creates</b> new records. It never changes or deletes an existing meeting, income or expense entry, transaction type, fund or sub-fund \u2014 '
      + 'ask it and it shows you the right page instead. Make the change there:</p><ul>';
    manage.forEach(function(m){
      h += '<li><b>' + esc(m[0]) + '</b> \u2014 ' + esc(m[1])
         + (navOk(m[2]) ? ' \u00b7 <a href="' + esc(m[2]) + '">Open</a>' : '') + '</li>';
    });
    h += '</ul><p style="font-size:12.5px;color:#666;">An \u201cOpen\u201d link appears only for pages you can open. If one is missing, ask your church administrator for access.</p>';
    return h;
  }
  function searchHelpHtml(){
    return '<p>Genius Assistant understands plain English. Ask it to open a page, look up your church\u2019s data, run a '
      +'report for a period, create a meeting, explain how to do something, or find help \u2014 it works the same on every page.</p>'
      +'<h4>What you can ask</h4>'
      +'<ul><li><b>Navigation</b> \u2014 \u201cGo to Payroll\u201d, \u201cOpen the event calendar\u201d, \u201cWhere do I set the email display name?\u201d.</li>'
      +'<li><b>Church data</b> \u2014 \u201cWho are the top contributors this year?\u201d, \u201cWhat were the expenses last month?\u201d, '
      +'\u201cHow many members do we have?\u201d, \u201cWhat meetings are coming up?\u201d, \u201cPending prayer requests\u201d, \u201cOpen follow-ups\u201d, '
      +'\u201cUpcoming events\u201d, \u201cActive reminders\u201d, \u201cCampaign totals\u201d, \u201cKids Ministry children\u201d, \u201cPublic links\u201d.</li>'
      +'<li><b>Reports for a period</b> \u2014 \u201cIncome report for last quarter\u201d, \u201cExpense report for September\u201d, '
      +'\u201cFinancial report for 2026\u201d \u2014 the totals appear here and a link opens the report page pre-filled.</li>'
      +'<li><b>Create new records</b> \u2014 \u201cCreate a meeting\u201d, \u201cRecord an income of $500 for Sunday offerings\u201d, a fund, a sub-fund or a transaction type (more examples below). '
      +'The assistant asks for anything missing and saves only after you press <b>Save</b> / <b>Create meeting</b> or say \u201cyes\u201d. '
      +'Recurring meetings and meeting images are set up on the Meetings page.</li>'
      +'<li><b>How-to questions</b> \u2014 \u201cHow do I add a new member?\u201d, \u201cHow do I send SMS reminders?\u201d.</li>'
      +'<li><b>Public page links</b> \u2014 \u201cMember signup page\u201d, \u201cKids check-in link\u201d.</li></ul>'
      +createHelpHtml()
      +'<h4>Example searches</h4>'
      +'<div class="aa-ex">\u201cGo to Family\u201d</div>'
      +'<div class="aa-ex">\u201cWhat was the income last month?\u201d</div>'
      +'<div class="aa-ex">\u201cExpense report for last quarter\u201d</div>'
      +'<div class="aa-ex">\u201cCreate a Prayer Meeting tomorrow at 6:30 pm\u201d</div>'
      +'<div class="aa-ex">\u201cI need to send reminders\u201d (Converse mode \u2014 the assistant asks follow-up questions)</div>'
      +'<h4>\ud83d\udd0e Type \u00b7 \ud83c\udfa4 Voice \u00b7 \ud83d\udde3 Converse</h4>'
      +'<ul><li><b>Type</b> \u2014 enter your question in the message box and press Enter or click Submit.</li>'
      +'<li><b>Voice</b> \u2014 click the microphone and speak naturally; your speech is converted to text and searched. Click again to stop. In Voice mode, saying a page name (e.g. \u201cGo to Events\u201d) navigates immediately.</li>'
      +'<li><b>Converse</b> \u2014 click the \ud83d\udde3 Converse button beside the microphone to switch it on or off (your conversation stays). A back-and-forth conversation: describe what you want to do (e.g. \u201cI need to send reminders\u201d) and the assistant asks follow-up questions to get you to the right place.</li></ul>'
      +'<h4>What you see depends on your access</h4>'
      +'<ul><li>Answers follow the same permissions and plan as the pages themselves: data you cannot open on its page is not shown here either, and the assistant says so.</li>'
      +'<li>Lists are kept short (the first 20 rows) with a link to the full page; very long date ranges are narrowed first.</li>'
      +'<li>Figures come straight from your records \u2014 online donations show the gross amount, with processing fees listed separately under expenses.</li></ul>'
      +'<h4>Tips for better results</h4>'
      +'<ul><li>Use full, natural sentences \u2014 no special syntax is needed.</li>'
      +'<li>Start with a verb (<b>Go to</b>, <b>Open</b>, <b>Show me</b>) when you want to navigate straight to a page.</li>'
      +'<li>One question at a time: while an answer is on its way, Enter waits; if nothing comes back within 30 seconds the assistant says so and you can try again.</li>'
      +'<li>Open \u22ef \u2192 Guide to browse every available command for your account.</li>'
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
      + '<button id="aaHelpBtn" class="aa-help" type="button" title="Help — how AI Search works" aria-label="Help">?</button>'
      // App Store readiness D6: "AI data sharing" (Stop sharing with AI) — app only
      + (/ChurchGeniusProApp\//.test(navigator.userAgent || '') || (window.CGP_NATIVE && window.CGP_NATIVE.isNative)
          ? '<button id="aaShareBtn" class="aa-ghost" type="button" title="AI data sharing" aria-label="AI data sharing">🔐</button>' : '');
    // Hint goes on its own line BELOW the toolbar so it never affects alignment.
    var hintRow=document.createElement('div'); hintRow.className='aa-hintrow';
    hintRow.innerHTML='<span id="aaHint" class="aa-hint" role="status" aria-live="polite"></span>';
    bar.setAttribute('role','toolbar'); bar.setAttribute('aria-label','Assistant modes');
    var guide=document.createElement('div'); guide.id='aaGuide'; guide.className='hidden';

    var homeWrap=document.querySelector('.ai-search-wrap');
    if (homeWrap){
      // Home page: drop the mode controls into the existing search bar row; the hint
      // + guide go beneath, above the results.
      var homeBar=homeWrap.querySelector('.ai-search-bar');
      if (homeBar){ homeBar.appendChild(bar); } else { homeWrap.appendChild(bar); }
      var hi=document.getElementById('aiSearchInput'); if (hi){ hi.setAttribute('aria-label','Ask the AI assistant'); hi.setAttribute('autocomplete','off'); }
      var res=document.getElementById('aiResultsWrap');
      if (res){ res.setAttribute('role','region'); res.setAttribute('aria-label','Assistant results'); res.setAttribute('aria-live','polite'); }
      if (res && res.parentNode===homeWrap){ homeWrap.insertBefore(hintRow,res); homeWrap.insertBefore(guide,res); }
      else { homeWrap.appendChild(hintRow); homeWrap.appendChild(guide); }
    } else {
      // Other pages: build the single-row search bar at the top of the content area.
      // 2026-11-10: the AI Search bar is no longer shown. Its input, mode buttons and results box stay in the
      // page as the ENGINE's controls (voice, Converse, routing and saving all run through them), kept
      // off-screen and out of the layout (.ga-engine-bar) — Genius Assistant (ai-chat.js) is the interface.
      // Only when the server switched the panel off (ai.chat.ui=false) is the classic bar drawn instead.
      var chatUi=(session.aiChatUi!==false);
      var main=document.querySelector('main.page-content')||document.querySelector('.page-content');
      if (!main){ if (!chatUi) return false; main=document.body; }   // classic bar needs a content area; the panel does not
      var host=document.createElement('div'); host.className='aa-host'+(chatUi?' ga-engine-bar':''); host.id='aaHost';
      if (chatUi) host.setAttribute('aria-hidden','true');
      host.setAttribute('data-perm','more.aiassistant');   // session.js hides it the moment the permission is switched off
      var sb=document.createElement('div'); sb.className='aa-searchbar';
      sb.innerHTML='<span class="aa-ic">✨</span>'
        + '<input id="aiSearchInput" class="aa-input" type="text" autocomplete="off" aria-label="Ask the AI assistant" placeholder="Ask Genius anything — questions, reports, pages, or tell me what to create…">'
        + '<button id="aiSearchBtn" class="aa-searchbtn" type="button" aria-label="Submit">Submit</button>';
      sb.appendChild(bar);                           // mode buttons inside the bar → one row
      var resWrap=document.createElement('div'); resWrap.id='aiResultsWrap'; resWrap.style.display='none';
      resWrap.setAttribute('role','region'); resWrap.setAttribute('aria-label','Assistant results'); resWrap.setAttribute('aria-live','polite');
      host.appendChild(sb); host.appendChild(hintRow); host.appendChild(guide); host.appendChild(resWrap);
      if (main===document.body) main.appendChild(host); else main.insertBefore(host, main.firstChild);
      // Wire the input + button we just created (the home page already has inline
      // handlers that call the overridden window.runAiSearch, so skip there).
      var input=document.getElementById('aiSearchInput');
      var btn=document.getElementById('aiSearchBtn');
      if (input){ input.addEventListener('keydown', function(e){ if(e.key==='Enter'){ e.preventDefault(); runAssist(); } }); }
      if (btn){ btn.addEventListener('click', function(){ runAssist(); }); }
    }

    document.getElementById('aaMode_type').addEventListener('click', function(){ setMode('type'); });
    document.getElementById('aaMode_voice').addEventListener('click', function(){
      // While Voice is listening or processing this button IS the Stop button.
      if (mode==='voice' && aaIsActive()){ stopAll('Voice stop button'); return; }
      setMode('voice');                      // mic starts ONLY on this explicit click
    });
    document.getElementById('aaMode_converse').addEventListener('click', function(){
      // Same for Converse - stop first, restart only on a second click.
      if (mode==='converse' && aaIsActive()){ stopAll('Converse stop button'); return; }
      setMode('converse');
    });
    document.getElementById('aaGuideBtn').addEventListener('click', openGuide);
    var shareBtn = document.getElementById('aaShareBtn');
    if (shareBtn) shareBtn.addEventListener('click', function(){
      if (window.CGP_aiConsent) window.CGP_aiConsent.openSettings(); else window.location.href = '/aiDataSharing';
    });
    document.getElementById('aaHelpBtn').addEventListener('click', function(){
      showHelpModal('Genius Assistant — Help', searchHelpHtml());
    });

    return true;
  }

  /* 2026-11-09 — Genius Assistant: ai-chat.js draws the conversation as a chat panel. It becomes the page adapter
     (window.CGP_ASSISTANT_UI) BEFORE the stored thread is restored, so every answer goes to the panel. The engine
     keeps every decision (routing, server calls, voice, saving); the panel only shows and sends. */
  function attachChat(){
    if (session.aiChatUi===false){
      // Panel switched off on the server: the full-page view falls back to the classic bar + results box.
      document.querySelectorAll('.ga-engine-bar').forEach(function(eb){ eb.classList.remove('ga-engine-bar'); eb.removeAttribute('aria-hidden'); });
      return;
    }
    if (!window.CGP_CHAT || typeof window.CGP_CHAT.attach!=='function') return;
    try{
      window.CGP_CHAT.attach({ ask:function(q){ return runAssist(q); }, wireCard:wireCard, newConversation:newConversation,
        user:function(){ return String(session.user||''); }, inFlight:function(){ return !!_inFlight; },
        helpHtml:function(){ return searchHelpHtml(); }, showHelp:function(){ showHelpModal('Genius Assistant — Help', searchHelpHtml()); },
        setMode:function(m){ var b=document.getElementById('aaMode_'+m); if(b) b.click(); }, mode:function(){ return mode; } });
    }catch(e){ /* the classic results box stays */ }
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
      // The server's own verdict (RoleGuard.requireFeature): false for a Kids Portal
      // session, a Member without the opt-in, or staff with the permission off. Only
      // an explicit true draws the assistant — an unknown answer is treated as "no".
      session.aiAssistant=(s.aiAssistant===true);
      // 2026-11-09: Genius Assistant chat panel (ai-chat.js) unless the server switched it off (ai.chat.ui=false).
      session.aiChatUi=(s.aiChatUi!==false);
      session.user=String(s.username||s.memberId||s.clientId||'');
      session.ready=true;
    }).catch(function(){ session.role=safeLS('role')||'User'; session.aiAssistant=false; session.ready=true; });
  }
  // Home carries its own search bar in the page; when the assistant is not allowed it
  // must not stay as a dead input (the server would answer 403 anyway).
  function hideHomeBar(){ var w=document.querySelector('.ai-search-wrap'); if(w) w.style.display='none'; }

  /* ════════════════════════ Bootstrap ════════════════════════ */
  function init(){
    loadSession().then(function(){
      if (session.aiAssistant!==true){ hideHomeBar(); return; }   // nothing is injected for a session the server refuses
      return ensureChat().then(start);
    });
  }
  /* The chat panel script (ai-chat.js) must be in place before the first answer is drawn; shell.js adds it too,
     but a dynamically added script may arrive after this one. Waits at most 4 s, then the classic box is used. */
  function ensureChat(){
    if (session.aiChatUi===false || window.CGP_CHAT) return Promise.resolve();
    return new Promise(function(res){
      var done=false; function fin(){ if(!done){ done=true; res(); } }
      var ex=document.getElementById('ai-chat-script');
      if (!ex){ ex=document.createElement('script'); ex.src='/ai-chat.js'; ex.id='ai-chat-script'; document.body.appendChild(ex); }
      ex.addEventListener('load', fin); ex.addEventListener('error', fin);
      var t0=Date.now(); (function poll(){ if (window.CGP_CHAT || Date.now()-t0>4000) return fin(); setTimeout(poll, 50); })();
    });
  }
  function start(){
    {
      if(!injectUI()) return;
      captureOriginal();
      attachChat();
      loadServerNav();
      loadPublicLinks();
      restoreState();
      restoreThread();
      if (window.VoiceOpenAI && window.VoiceOpenAI.getStatus){
        window.VoiceOpenAI.getStatus().then(function(st){ if(st && st.voiceAvailable===false){ /* voice gated — errors surfaced on use */ } }).catch(function(){});
      }
    }
  }
  if (document.readyState==='loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(init,300); });
  else setTimeout(init,200);

  // Exposed for testing / external use.
  window.CGP_ASSISTANT = { matchDest:matchDest, matchReport:matchReport, isAvailable:isAvailable, reportAvailable:reportAvailable,
    resolveLocal:resolveLocal, classify:classify, buildContext:buildContext, converseStart:converseStart, converseStep:converseStep,
    matchAccountSwitch:matchAccountSwitch, directNav:directNav, open:open, confirmMeeting:confirmMeeting, cancelMeeting:cancelMeeting,
    rerunFilterWithout:rerunFilterWithout, saveFilterAsList:saveFilterAsList, chooseAnswer:chooseAnswer, prefillEdit:prefillEdit,
    newConversation:newConversation, ask:function(q){ return runAssist(q); }, wireCard:wireCard, _thread:function(){ return _thread; }, _dests:DESTS, _reports:REPORTS, _conv:function(){return conv;},
    _setSession:function(r,c){ session.role=r; session.church=!!c; }, _setMode:function(m){ mode=m; speaking=(m==='voice'||m==='converse'); },
    _setTimeoutMs:function(ms){ AA_TIMEOUT_MS=ms; }, _inFlight:function(){ return _inFlight; } };
})();
