/*
 * Accounts (/viewusers) — Help window (2026-11-19).
 *
 * Four topics, written for church administrators:
 *   users    — add a user, choose the role, invite them, how they get in
 *   privs    — what each privilege on the 🔐 Permissions screen allows
 *   portal   — Member Portal accounts (how members get one, how to manage them)
 *   links    — public links (Public Screens): create, copy, share, check
 *
 * The privilege list is built from the SAME tree the Permissions screen draws
 * (passed in by the page: visiblePermTree()), so the names always match what the
 * administrator sees and options the church's plan hides are left out. Only the
 * one-line explanations live here (DESC, by permission key).
 *
 * Pictures are small copies of the page's own buttons (.vuh-btn), never screenshots.
 * Everything is text: no API call, nothing is saved.
 */
(function () {
  'use strict';
  if (window.CGP_usersHelp) return;

  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function btn(label, kind) { return '<span class="vuh-btn' + (kind ? ' vuh-btn-' + kind : '') + '">' + esc(label) + '</span>'; }

  /* What each privilege allows. Keys = the Permissions screen's own keys. */
  var DESC = {
    'favorites': 'The Favorites list at the top of the menu, for quick links to the pages a person uses most.',

    'admin': 'The Admin menu: the church\'s people records.',
    'admin.family': 'Families: see every family and its members, search, and run the Family & Member Report.',
    'admin.family.edit': 'Add a family or member, and change their details.',
    'admin.family.delete': 'Delete a family or member (they can be restored from Show Deleted).',
    'admin.family.inactive': 'Mark a family or member inactive, and make them active again.',
    'admin.groups': 'Groups: see the church\'s groups and who is in each one.',
    'admin.groups.edit': 'Add a group, rename it, and add or change its members.',
    'admin.groups.delete': 'Delete a group or remove a member from it.',
    'admin.email': 'Send an e-mail to the selected members of a group from the Groups page.',
    'admin.membership': 'Membership Requests: review the membership forms people submit, and approve them.',
    'admin.membership.delete': 'Delete a membership request.',
    'admin.unsubscribed': 'See who has unsubscribed from the church\'s e-mails.',

    'accounting': 'The Accounting menu: the church\'s money records.',
    'accounting.income': 'Income: see the gifts and other income that were recorded.',
    'accounting.income.edit': 'Record income and correct an entry.',
    'accounting.income.delete': 'Delete an income entry.',
    'accounting.expense': 'Expense: see the expenses that were recorded.',
    'accounting.expense.edit': 'Record an expense and correct an entry.',
    'accounting.expense.delete': 'Delete an expense entry.',
    'accounting.bankimport': 'Bank Import and Bank Sync: bring bank transactions in and review them.',
    'accounting.pledges': 'Pledges: see pledge campaigns and what each person pledged.',
    'accounting.pledges.edit': 'Add a campaign or a pledge, and change them.',
    'accounting.pledges.delete': 'Delete a campaign or a pledge.',
    'accounting.reports': 'Reports: income, expense, transactions, tax statements and the financial report.',
    'accounting.donation': 'Donation/Give: review online donations and post them to Income.',
    'accounting.payroll': 'Payroll: employees, pay runs and pay stubs.',
    'accounting.settings': 'Account Settings: funds, sub-funds, expense purposes and payment methods.',

    'general': 'The General menu: the church\'s week-to-week activity.',
    'general.meetings': 'Meetings: see the meeting schedule.',
    'general.meetings.edit': 'Add a meeting and change it.',
    'general.meetings.delete': 'Delete a meeting or one of its dates.',
    'general.meetings.notify': 'Send a meeting notification to the people invited.',
    'general.meetings.category': 'Add and change the meeting categories.',
    'general.attendance': 'Attendance: the attendance dashboard, records and reports.',
    'general.attendance.edit': 'Check people in, add visitors, and correct a record.',
    'general.attendance.delete': 'Delete an attendance record or a visitor.',
    'general.attendance.emailsms': 'E-mail or text the volunteers from the Attendance page.',
    'general.events': 'Events: see the church\'s events and who registered.',
    'general.events.edit': 'Add an event and change it.',
    'general.events.delete': 'Delete an event.',
    'general.events.emailsms': 'E-mail or text an event\'s volunteers.',
    'general.ministry': 'Ministry: the ministry pages below.',
    'general.ministry.kids': 'Kids Ministry and Sunday School: children, check-in, classes and exams.',
    'general.ministry.worship': 'Worship Planning: worship teams, assignments and song lists.',
    'general.ministry.prayer': 'Prayer Meeting: prayer requests and prayer notes.',
    'general.email': 'Compose Email: write and send an e-mail to members, guests or a group.',
    'general.emailsettings': 'Email Settings: the name and reply address the church\'s e-mails use.',
    'general.reminders': 'Reminders: birthday, anniversary, event and one-time reminders.',

    'activity': 'The Activity Corner menu.',
    'activity.guessit': 'Guess It: host and run the Guess It game.',

    'more': 'The More menu: tools used less often.',
    'more.certificates': 'Certificates: create and print certificates.',
    'more.publicscreens': 'Public Screens: create the public links the church shares (see the Public links tab).',
    'more.followups': 'Follow-ups: follow-up tasks, Connect With Us submissions and public prayer requests.',
    'more.helpcenter': 'Help Center: the step-by-step guides.',
    'more.ticketing': 'Ticketing: send a support request to ChurchGeniusPro and see its status.',
    'more.aiassistant': 'Genius Assistant: ask questions and enter records by typing or speaking.',
    'more.savedlists': 'Saved Lists: saved people searches that can be run, downloaded or e-mailed.',
    'more.ministryreport': 'Ministry Report: the monthly summary of attendance, visitors, giving and engagement.',
    'more.workflows': 'Automations: rules that create a follow-up or a notice when something happens.',

    'member': 'My Profile: the person\'s own member pages.',
    'member.family': 'Their own family\'s details.',
    'member.contributions': 'Their own giving history, online giving and their giving statement.',
    'member.groups': 'The groups they belong to, and Members Chat.',
    'member.classes': 'Their classes (Sunday School).',
    'member.volunteer': 'Their volunteer profile, requests and assignments.',
    'member.directory': 'The member directory.'
  };

  function privilegeHtml(tree) {
    var h = '';
    (tree || []).forEach(function (sec) {
      if (sec.standalone) {
        h += '<div class="vuh-priv-sec"><div class="vuh-priv-row vuh-priv-top"><b>' + esc(sec.label) + '</b><span>' + esc(DESC[sec.key] || '') + '</span></div></div>';
        return;
      }
      h += '<div class="vuh-priv-sec"><div class="vuh-priv-head">' + esc(sec.label) + '</div>';
      if (DESC[sec.key]) h += '<div class="vuh-priv-note">' + esc(DESC[sec.key]) + ' Untick the section to remove all of it at once.</div>';
      (sec.children || []).forEach(function (sub) {
        h += '<div class="vuh-priv-row"><b>' + esc(sub.label) + '</b><span>' + esc(DESC[sub.key] || '') + '</span></div>';
        (sub.actions || []).forEach(function (a) {
          h += '<div class="vuh-priv-row vuh-priv-action"><b>' + esc(a.label) + '</b><span>' + esc(DESC[a.key] || '') + '</span></div>';
        });
      });
      h += '</div>';
    });
    return h;
  }

  var TOPICS = [
    { id: 'users',  label: 'Add a user' },
    { id: 'privs',  label: 'Privileges' },
    { id: 'portal', label: 'Member Portal' },
    { id: 'links',  label: 'Public links' }
  ];

  function usersHtml() {
    return ''
      + '<p class="vuh-lead">A <b>user</b> is a staff member or volunteer who signs in to help run the church\'s records. '
      + 'You add the person here, choose what they may do, and send them an invitation. They choose their own username and password.</p>'
      + '<ol class="vuh-steps">'
      + '<li><b>Open Accounts.</b> Sign in with the <b>church\'s own login</b> (the account the church was registered with) and choose <b>Admin → Users</b> in the menu. That is this page; staff logins cannot open it.</li>'
      + '<li><b>Click ' + btn('＋ Add User', 'primary') + '</b> at the top right.</li>'
      + '<li><b>Enter the person\'s details.</b> <b>First Name</b>, <b>Last Name</b> and <b>Email</b> are required — the invitation is sent to this e-mail address, so check it carefully. '
      + 'Phone and address are optional. If the person is already in Families, pick them under <b>Select from Existing Member</b> and their details are filled in for you.</li>'
      + '<li><b>Choose the Role.</b> The role decides which menus the person starts with:'
      + '<ul class="vuh-roles">'
      + '<li><b>SuperAdmin</b> — everything, including Accounting.</li>'
      + '<li><b>Admin</b> — people records (Admin), General and More. No Accounting unless you tick it under Permissions.</li>'
      + '<li><b>Accountant</b> — Accounting, General and More.</li>'
      + '<li><b>User</b> — General and More only. No people records and no Accounting.</li>'
      + '<li><b>Limited</b> — the General menu only.</li>'
      + '</ul></li>'
      + '<li><b>Click ' + btn('Save User', 'primary') + '.</b> The person now appears in the list.</li>'
      + '<li><b>Assign the privileges.</b> In the person\'s row click ' + btn('🔐') + ' <b>Permissions</b>. Tick what the person should be able to open or do and untick the rest, then click <b>Save Permissions</b>. '
      + 'The <b>Privileges</b> tab of this help explains every box.</li>'
      + '<li><b>Review.</b> Open ' + btn('🔐') + ' once more and read down the list. A ticked page with unticked <i>Add or Edit</i> / <i>Delete</i> means the person can look but not change.</li>'
      + '<li><b>Click ' + btn('Invite', 'invite') + '</b> in the person\'s row. An e-mail with a personal sign-up link is sent, and a message at the top of the page confirms it. '
      + 'The button then reads ' + btn('ReInvite', 'invite') + ', which means: invited, waiting for the person.</li>'
      + '</ol>'
      + '<h4 class="vuh-h">What the invited person does</h4>'
      + '<ol class="vuh-steps">'
      + '<li>Opens the e-mail <b>“Complete Your … Sign-Up”</b> and clicks the button in it.</li>'
      + '<li>Chooses a <b>username</b> and a <b>password</b>, and types the verification code that is e-mailed to them.</li>'
      + '<li>Signs in at <b>churchgeniuspro.net</b> (or in the ChurchGeniusPro app) with that username and password. They see only the menus you allowed.</li>'
      + '</ol>'
      + '<div class="vuh-note"><b>Did the e-mail not arrive?</b> Ask the person to check their spam folder, check the address in the list, then click ' + btn('ReInvite', 'invite')
      + '. A new link is sent and the earlier link stops working. Nothing is ever re-sent on its own — only when you click the button.</div>'
      + '<div class="vuh-note"><b>Good to know.</b> The <b>Status</b> switch turns a person\'s access off without deleting them. '
      + 'Changes to permissions reach a signed-in person within about 15 seconds; they do not need to sign in again.</div>';
  }

  function privsHtml(tree) {
    return ''
      + '<p class="vuh-lead">Privileges are set per person on the ' + btn('🔐') + ' <b>Permissions</b> screen. A ticked box means the person can open that page or use that button; '
      + 'an unticked box hides it from their menu and refuses it if they try the address directly.</p>'
      + '<div class="vuh-note"><b>How the boxes work together.</b> A <b>section</b> (for example Accounting) must be ticked for anything inside it to work. '
      + 'A <b>page</b> (for example Income) lets the person see it. The boxes under a page — <i>Add or Edit</i>, <i>Delete</i> — allow those actions on it. '
      + '<b>Select All</b> and <b>Deselect All</b> at the top of the Permissions screen tick or clear everything. A new person starts with the boxes that suit their role already ticked.</div>'
      + '<div class="vuh-priv">' + privilegeHtml(tree) + '</div>'
      + '<div class="vuh-note"><b>Don\'t log out automatically.</b> At the top of the Permissions screen. Leave it unticked for most people: they are then signed out after 30 minutes without activity, which protects a shared or unattended computer.</div>'
      + '<div class="vuh-note"><b>Not on the list?</b> A privilege your church\'s plan does not include is not shown. The church\'s own login is not limited by these boxes.</div>';
  }

  function portalHtml() {
    return ''
      + '<p class="vuh-lead">The <b>Member Portal</b> is the members\' own sign-in: their family details, giving, groups, classes, volunteering and the directory. '
      + 'A portal account is not created by you — each member creates their own, using a sign-up link you share. This keeps passwords private.</p>'
      + '<h4 class="vuh-h">A. How to add a portal account</h4>'
      + '<ol class="vuh-steps">'
      + '<li><b>Make sure the person is in Families</b> (<b>Admin → Families</b>) with their <b>e-mail address</b> or phone number. The portal recognises people by these details.</li>'
      + '<li><b>Create the sign-up link.</b> Open <b>More → Public Screens</b>, choose <b>Member Signup</b> under <b>Select Page</b> and click ' + btn('Generate Link', 'primary') + '. (The <b>Public links</b> tab has the full steps.)</li>'
      + '<li><b>Share the link</b> with your members — by e-mail, text, the bulletin or a QR code.</li>'
      + '<li><b>The member opens the link</b>, enters <b>First Name</b>, <b>Last Name</b> and their <b>Email Address or Phone Number</b>, chooses a <b>username</b> and <b>password</b>, '
      + 'and types the verification code that is e-mailed to them. The account works straight away.</li>'
      + '<li><b>Check it.</b> Refresh this page: the person is now listed under <b>Member Portal Users</b>.</li>'
      + '</ol>'
      + '<h4 class="vuh-h">Settings and access for a portal account</h4>'
      + '<p class="vuh-p">Under <b>Member Portal Users</b>, lower on this page, each person has:</p>'
      + '<ul class="vuh-list">'
      + '<li>' + btn('📝') + ' <b>Edit</b> — correct the name or e-mail address.</li>'
      + '<li>' + btn('🔑 Reset', 'invite') + ' — e-mail the person a link to choose a new password.</li>'
      + '<li>' + btn('🗑') + ' <b>Remove login</b> — removes the person\'s Member Portal login only. They stay in Families with their giving and history, and can create a new login later from the Member Signup link.</li>'
      + '<li>' + btn('🔐') + ' <b>Permissions</b> — tick the <b>My Profile</b> pages the member may open: <b>Family</b>, <b>Contributions</b>, <b>Groups</b>, <b>Classes</b>, <b>Volunteer</b>, <b>Directory</b>, and <b>Favorites</b>. '
      + 'A member you have never changed gets all of these. Click <b>Save Permissions</b> and ask the member to refresh the page.</li>'
      + '</ul>'
      + '<div class="vuh-note"><b>Children.</b> A child\'s own portal account (Kids Portal) is available from age 13, using the date of birth in the church\'s family record. A child account has no Permissions button.</div>'
      + '<div class="vuh-note"><b>“No matching member found.”</b> The name, e-mail address or phone the member typed is different from the family record. Correct the record in Families and ask them to try again.</div>'
      + '<div class="vuh-note"><b>Someone who is both staff and a member</b> can switch between the two without signing out: use ' + btn('🔗 Link Accounts') + ' at the top of this page.</div>';
  }

  function linksHtml() {
    return ''
      + '<p class="vuh-lead">A <b>public link</b> opens one page of your church for people who do not sign in — for example the Donation Page, the Membership Form or the Member Signup page.</p>'
      + '<h4 class="vuh-h">B. How to add a public link</h4>'
      + '<ol class="vuh-steps">'
      + '<li><b>Open Public Screens.</b> Sign in with a staff login (SuperAdmin, Admin or User role) that has the <b>Public Screens</b> privilege, and choose <b>More → Public Screens</b> in the menu.</li>'
      + '<li><b>Choose the page.</b> Under <b>Generate a Public Link</b>, open <b>Select Page</b> and pick what the link should open — for example '
      + '<b>Member Signup</b> (portal accounts), <b>Donation Page</b>, <b>Membership Form</b>, <b>Connect With Us</b>, <b>Prayer Request (Public)</b>, <b>Event Calendar</b>, <b>Kids Check-In</b> or <b>SMS Opt-In Form</b>. '
      + 'Each link opens that one page only.</li>'
      + '<li><b>Set an end date if you want one.</b> <b>Expiration Date</b> is optional. Leave it empty for a link that keeps working; set a date for something temporary. '
      + 'For the <b>Membership Form</b> you can also choose to show a Declaration / Statement of Faith.</li>'
      + '<li><b>Click ' + btn('Generate Link', 'primary') + '.</b> The new link appears in a green box.</li>'
      + '<li><b>Copy it.</b> Click ' + btn('Copy') + ' beside the link. Every link you have made is also listed under <b>Generated Links</b>, each with its own copy button.</li>'
      + '<li><b>Share it.</b> Paste the link into an e-mail, a text message, your website or the bulletin, or turn it into a QR code.</li>'
      + '<li><b>Check that it works without signing in.</b> Open a <b>private (incognito) window</b> in your browser, or use a phone that is not signed in, and paste the link. '
      + 'The page should open directly, showing your church\'s name, with no sign-in screen.</li>'
      + '</ol>'
      + '<div class="vuh-note"><b>Example.</b> To let members create their portal accounts: Select Page = <b>Member Signup</b>, leave Expiration Date empty, Generate Link, Copy, and paste it into an e-mail to your members.</div>'
      + '<div class="vuh-note"><b>To switch a link off</b>, delete it under <b>Generated Links</b>. It stops working at once, wherever it was shared. You can create a new one at any time.</div>'
      + '<div class="vuh-note"><b>Security.</b> Every link carries a long random code that belongs to your church and to the one page it was made for. It cannot open another church\'s pages or any other page of yours.</div>'
      + '<p class="vuh-p"><a class="vuh-link" href="/publicScreens">Open Public Screens →</a></p>';
  }

  var CSS = ''
    + '#vuhModal .modal-box{width:820px;}'
    + '.vuh-tabs{display:flex;gap:6px;flex-wrap:wrap;padding:12px 24px 0;border-bottom:1px solid #eee;flex-shrink:0;background:#fff;}'
    + '.vuh-tab{padding:9px 14px;border:none;background:none;font:inherit;font-size:13.5px;font-weight:600;color:#777;cursor:pointer;border-bottom:3px solid transparent;border-radius:6px 6px 0 0;}'
    + '.vuh-tab:hover{color:#673147;background:#faf5f7;}'
    + '.vuh-tab[aria-selected="true"]{color:#673147;border-bottom-color:#673147;}'
    + '.vuh-tab:focus-visible{outline:2px solid #673147;outline-offset:1px;}'
    + '.vuh-body{font-size:14px;line-height:1.6;color:#333;}'
    + '.vuh-lead{margin:0 0 14px;color:#444;}'
    + '.vuh-p{margin:10px 0;}'
    + '.vuh-h{margin:20px 0 8px;font-size:14.5px;color:#673147;}'
    + '.vuh-steps{margin:0 0 6px 20px;padding:0;}'
    + '.vuh-steps>li{margin:0 0 9px;padding-left:4px;}'
    + '.vuh-roles,.vuh-list{margin:6px 0 2px 18px;padding:0;}'
    + '.vuh-roles li,.vuh-list li{margin:0 0 5px;}'
    + '.vuh-note{margin:12px 0 0;padding:10px 13px;background:#faf5f7;border-left:3px solid #c9a8b6;border-radius:6px;font-size:13.5px;color:#444;}'
    + '.vuh-btn{display:inline-block;padding:1px 8px;border:1.5px solid #cfd3e0;border-radius:6px;background:#fff;font-size:12.5px;font-weight:600;color:#444;white-space:nowrap;line-height:1.5;}'
    + '.vuh-btn-primary{background:#673147;border-color:#673147;color:#fff;}'
    + '.vuh-btn-invite{background:#eef4ff;border-color:#b9cdf5;color:#1d4ed8;}'
    + '.vuh-priv{margin-top:14px;}'
    + '.vuh-priv-sec{border:1px solid #eee;border-radius:9px;margin:0 0 10px;overflow:hidden;}'
    + '.vuh-priv-head{background:#673147;color:#fff;font-weight:700;font-size:13px;padding:7px 12px;letter-spacing:.3px;}'
    + '.vuh-priv-note{padding:8px 12px;font-size:13px;color:#666;background:#fcfafb;border-bottom:1px solid #f1eef0;}'
    + '.vuh-priv-row{display:grid;grid-template-columns:190px 1fr;gap:10px;padding:7px 12px;border-bottom:1px solid #f4f2f3;font-size:13.5px;}'
    + '.vuh-priv-row:last-child{border-bottom:none;}'
    + '.vuh-priv-row b{color:#2b2b3a;}'
    + '.vuh-priv-row span{color:#555;}'
    + '.vuh-priv-action b{font-weight:600;color:#555;padding-left:16px;}'
    + '.vuh-priv-action b:before{content:"↳ ";color:#bbb;}'
    + '.vuh-link{color:#673147;font-weight:600;}'
    + '@media (max-width:600px){.vuh-tabs{padding:8px 12px 0;}.vuh-tab{padding:8px 9px;font-size:13px;}'
    + '.vuh-priv-row{grid-template-columns:1fr;gap:2px;}#vuhModal .modal-body{padding:16px;}}';

  var built = false, treeFn = null, lastFocus = null;

  function build() {
    if (built) return;
    built = true;
    var st = document.createElement('style');
    st.id = 'vuhStyle'; st.textContent = CSS;
    document.head.appendChild(st);
    var ov = document.createElement('div');
    ov.className = 'modal-overlay'; ov.id = 'vuhModal';
    ov.setAttribute('role', 'dialog'); ov.setAttribute('aria-modal', 'true'); ov.setAttribute('aria-labelledby', 'vuhTitle');
    ov.innerHTML = '<div class="modal-box">'
      + '<div class="modal-header"><span class="modal-title" id="vuhTitle">❓ Help — Accounts, privileges, portals and public links</span>'
      + '<button type="button" class="modal-close" id="vuhClose" title="Close" aria-label="Close">✕</button></div>'
      + '<div class="vuh-tabs" role="tablist">'
      + TOPICS.map(function (t) { return '<button type="button" class="vuh-tab" role="tab" data-topic="' + t.id + '" id="vuhTab_' + t.id + '" aria-controls="vuhBody">' + esc(t.label) + '</button>'; }).join('')
      + '</div>'
      + '<div class="modal-body vuh-body" id="vuhBody" role="tabpanel" tabindex="0"></div>'
      + '<div class="modal-footer"><button type="button" class="btn-cancel" id="vuhDone">Close</button></div>'
      + '</div>';
    document.body.appendChild(ov);
    ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
    document.getElementById('vuhClose').addEventListener('click', close);
    document.getElementById('vuhDone').addEventListener('click', close);
    ov.querySelectorAll('.vuh-tab').forEach(function (b) {
      b.addEventListener('click', function () { show(b.getAttribute('data-topic')); });
    });
  }

  function show(topic) {
    var known = TOPICS.some(function (t) { return t.id === topic; });
    if (!known) topic = 'users';
    var body = document.getElementById('vuhBody');
    var tree = [];
    try { tree = treeFn ? treeFn() : []; } catch (e) { tree = []; }
    body.innerHTML = topic === 'privs' ? privsHtml(tree) : topic === 'portal' ? portalHtml() : topic === 'links' ? linksHtml() : usersHtml();
    body.scrollTop = 0;
    document.querySelectorAll('#vuhModal .vuh-tab').forEach(function (b) {
      b.setAttribute('aria-selected', b.getAttribute('data-topic') === topic ? 'true' : 'false');
    });
    body.setAttribute('aria-labelledby', 'vuhTab_' + topic);
  }

  function open(topic, getTree) {
    if (typeof getTree === 'function') treeFn = getTree;
    build();
    lastFocus = document.activeElement;
    show(topic || 'users');
    document.getElementById('vuhModal').classList.add('open');
    var c = document.getElementById('vuhClose'); if (c) c.focus();
  }

  function close() {
    var ov = document.getElementById('vuhModal');
    if (!ov) return;
    ov.classList.remove('open');
    if (lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (e) { /* element gone */ } }
  }

  function isOpen() { var ov = document.getElementById('vuhModal'); return !!(ov && ov.classList.contains('open')); }

  window.CGP_usersHelp = { open: open, close: close, isOpen: isOpen, describe: function (k) { return DESC[k] || ''; }, topics: TOPICS.map(function (t) { return t.id; }) };
})();
