/* ============================================================================
 * workflows-help.js — the Help window of the Automations page (2026-11-16).
 * Adds a "❓ Help" button to the page and explains, in plain words, every field
 * and option, how to set up, switch on and test a rule, and each template.
 * Fixed text only (nothing from the server is written here). Keep it in step
 * with workflow/WorkflowSchema and workflow/WorkflowTemplates — the test
 * enhancements/WorkflowsHelpTest fails when a trigger, action or template is
 * missing from this file.
 * ========================================================================== */
(function () {
  'use strict';

  var TEMPLATES = [
    { key: 'first_time_visitor', name: 'First-time visitor → follow-up in 2 days',
      does: 'Makes sure every brand-new visitor gets a welcome call.',
      trigger: 'A visitor is checked in on the Attendance page.',
      condition: 'The visitor is new — no earlier visitor record matched them.',
      who: 'The rule owner. No message is sent; a follow-up task appears in their Follow-Ups list.',
      when: 'Straight away, when the check-in is saved.',
      result: 'A HIGH priority follow-up “Welcome call: <visitor’s name>”, due in 2 days, with the visit date, service and interest in its description.' },
    { key: 'interested_visitor', name: 'Interested visitor → notify the owner',
      does: 'Tells you at once when a visitor says they are interested in the church.',
      trigger: 'A visitor is checked in on the Attendance page.',
      condition: 'Interest is marked “Interested” on the check-in form.',
      who: 'The rule owner, as a notification in ChurchGeniusPro (bell, and a push notification on devices where they allowed it).',
      when: 'Straight away.',
      result: 'A notification “Interested visitor: <name>” with the service and date.' },
    { key: 'third_visit', name: 'Third visit → membership conversation',
      does: 'Reminds you to talk about membership with someone who keeps coming back.',
      trigger: 'A visitor is checked in on the Attendance page.',
      condition: 'This is at least their third recorded visit.',
      who: 'The rule owner. No message is sent; a follow-up task is created for them.',
      when: 'Straight away. It can fire again on the 4th, 5th … visit — each visit is a new event.',
      result: 'A follow-up “Membership conversation: <name>”, due in 7 days.' },
    { key: 'family_visit', name: 'Family visit → e-mail the owner',
      does: 'Sends you the details when a whole family visits.',
      trigger: 'A visitor is checked in on the Attendance page.',
      condition: 'The check-in was entered as a Family (not Individual or Group).',
      who: 'The rule owner, by e-mail to the address on their staff login.',
      when: 'Straight away.',
      result: 'An e-mail “New visiting family: <name>” with the number of people, date, service, interest and whether a follow-up was requested.' },
    { key: 'at_risk_member', name: 'Member slips to At risk → follow-up',
      does: 'Flags a regular member who has stopped attending or giving, so someone can check in.',
      trigger: 'The nightly engagement check gives a member a different signal than the day before.',
      condition: 'The new signal is “At risk”.',
      who: 'The rule owner. No message is sent; a follow-up task is created for them.',
      when: 'Once a night (about 3:20 AM Central), only on the night the signal changes.',
      result: 'A HIGH priority follow-up “Check in with <name>”, due in 5 days, linked to the member.' },
    { key: 'quiet_member_note', name: 'Member goes Quiet → family note + notify',
      does: 'Records that a member has had no activity for a year, and tells you.',
      trigger: 'The nightly engagement check gives a member a different signal than the day before.',
      condition: 'The new signal is “Quiet” (no attendance, gift, group or serving in the last year).',
      who: 'The rule owner, as a notification. The note is stored with the member’s family.',
      when: 'Once a night (about 3:20 AM Central), only on the night the signal changes.',
      result: 'A family note “Gone quiet” and a notification “Gone quiet: <name>”.' },
    { key: 'overdue_follow_up', name: 'Follow-up 3+ days overdue → notify the owner',
      does: 'Keeps overdue follow-ups from being forgotten.',
      trigger: 'The nightly check finds an open follow-up past its due date.',
      condition: 'It is 3 or more days overdue.',
      who: 'The rule owner, as a notification — not the person the follow-up is assigned to.',
      when: 'Once a night per overdue follow-up, every night until it is completed.',
      result: 'A notification “Overdue follow-up: <title>” naming who it is assigned to and how many days overdue.' },
    { key: 'high_priority_followup', name: 'High-priority follow-up created → notify the owner',
      does: 'Tells you when anyone adds an urgent follow-up.',
      trigger: 'A follow-up is created (by hand, from a visitor check-in or by the assistant).',
      condition: 'Its priority is HIGH.',
      who: 'The rule owner, as a notification.',
      when: 'Straight away.',
      result: 'A notification “High-priority follow-up” with the title, who created it and who it is assigned to.' },
    { key: 'new_prayer_request', name: 'New prayer request → e-mail the owner',
      does: 'Lets you know when a prayer request is recorded.',
      trigger: 'A staff member records a new prayer request on the Prayer page.',
      condition: 'None — every new request recorded there.',
      who: 'The rule owner, by e-mail to the address on their staff login.',
      when: 'Straight away. Requests sent through the public Connect / Prayer page are not included.',
      result: 'An e-mail “New prayer request: <title>” with its status and who asked.' },
    { key: 'large_gift', name: 'Gift of $1,000 or more → thank-you follow-up',
      does: 'Reminds you to thank the giver of a large gift.',
      trigger: 'An income entry is saved (typed, scanned or an online gift posted to Income).',
      condition: 'The amount is $1,000 or more.',
      who: 'The rule owner. No message is sent to the giver; a follow-up task is created for the owner.',
      when: 'Straight away, when the income is saved.',
      result: 'A follow-up “Thank-you note: <name>”, due in 3 days, naming the fund and date.' }
  ];

  var TRIGGERS = [
    ['A visitor checks in', 'A visitor (one person, a family or a group) is recorded on the Attendance page.'],
    ['A member checks in', 'A member’s attendance is saved for the first time for that service and date.'],
    ['A follow-up is created', 'A new follow-up task is added — by hand, from a visitor check-in or by the assistant.'],
    ['A prayer request is added', 'A staff member records a new prayer request.'],
    ['A gift is recorded', 'An income entry is saved.'],
    ['A member’s engagement signal changes (nightly)', 'The nightly check gives a member a different signal than the day before (for example Engaged → At risk).'],
    ['A follow-up is overdue (nightly)', 'An open follow-up has passed its due date. Checked every night while it stays open.']
  ];
  var ACTIONS = [
    ['Create a follow-up', 'Adds a task to Follow-Ups. Title is required. You choose who it is assigned to (the rule owner or a named staff login), how many days until it is due (0–365) and its priority.'],
    ['Notify a staff member', 'Sends a notification inside ChurchGeniusPro to the rule owner or a named staff login. Title and Message are required.'],
    ['E-mail a staff member', 'Sends a plain e-mail to the rule owner’s or a named staff login’s address. Subject and Body are required. If that login has no e-mail address, the step is skipped and the run log says so.'],
    ['Add a family note', 'Stores a note with the family of the member the event is about. Title and Body are required. Only for events about a member (member check-in, gift from a member, engagement signal).'],
    ['Draft an e-mail with AI', 'Asks the writing assistant for a draft and saves it as a follow-up called “Draft: …” for the owner to review. Nothing is sent. Needs AI writing in your plan.']
  ];
  var OPS = [
    ['eq', 'is exactly', 'interest eq INTERESTED'], ['neq', 'is not', 'status neq COMPLETED'],
    ['contains', 'contains this text', 'serviceName contains Sunday'],
    ['gt / gte', 'greater than / greater than or equal to (numbers and dates)', 'amount gte 1000'],
    ['lt / lte', 'less than / less than or equal to', 'visitCount lt 3'],
    ['in', 'is one of a comma-separated list', 'signal in AT_RISK,QUIET'],
    ['is_true / is_false', 'for yes/no facts — no value needed', 'newVisitor is_true'],
    ['exists', 'the fact has any value — no value needed', 'memberId exists']
  ];

  function css() {
    if (document.getElementById('wfHelpStyle')) return;
    var st = document.createElement('style'); st.id = 'wfHelpStyle';
    st.textContent =
      '.wfh-ov{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:1500;display:flex;align-items:flex-start;justify-content:center;padding:24px 14px;overflow:auto;}' +
      '.wfh-box{background:#fff;border-radius:12px;box-shadow:0 10px 44px rgba(0,0,0,.28);width:860px;max-width:100%;font-size:14px;line-height:1.55;color:#333;}' +
      '.wfh-head{position:sticky;top:0;background:#673147;color:#fff;border-radius:12px 12px 0 0;padding:14px 20px;display:flex;align-items:center;justify-content:space-between;gap:12px;z-index:1;}' +
      '.wfh-head h2{margin:0;font-size:16px;color:#fff;}' +
      '.wfh-x{background:rgba(255,255,255,.18) !important;color:#fff !important;border:none !important;border-radius:8px;width:36px;height:36px;font-size:18px;cursor:pointer;flex:0 0 auto;}' +
      '.wfh-body{padding:14px 20px 20px;}' +
      '.wfh-body p{margin:0 0 10px;}.wfh-body ol,.wfh-body ul{margin:0 0 10px 20px;padding:0;}.wfh-body li{margin-bottom:4px;}' +
      '.wfh-sec{border:1px solid #eadfe5;border-radius:10px;margin:0 0 10px;overflow:hidden;}' +
      '.wfh-sec>summary{cursor:pointer;padding:11px 14px;font-weight:700;color:#4a2233;background:#faf6f8;list-style-position:inside;}' +
      '.wfh-sec[open]>summary{border-bottom:1px solid #eadfe5;}.wfh-in{padding:12px 14px 4px;}' +
      '.wfh-dl{display:grid;grid-template-columns:150px minmax(0,1fr);gap:6px 14px;margin:0 0 12px;}' +
      '.wfh-dl dt{font-weight:700;color:#4a2233;}.wfh-dl dd{margin:0;}' +
      '.wfh-t{border-top:1px solid #f1e9ed;padding:12px 0 2px;}.wfh-t:first-child{border-top:none;padding-top:0;}' +
      '.wfh-t h4{margin:0 0 6px;font-size:14px;color:#4a2233;}' +
      '.wfh-tbl{width:100%;border-collapse:collapse;margin:0 0 12px;font-size:13px;}' +
      '.wfh-tbl th,.wfh-tbl td{text-align:left;padding:6px 8px;border-bottom:1px solid #f1e9ed;vertical-align:top;}' +
      '.wfh-tbl th{font-size:11.5px;text-transform:uppercase;letter-spacing:.04em;color:#8a7a82;}' +
      '.wfh-code{font-family:ui-monospace,Consolas,monospace;font-size:12.5px;background:#f4eef1;border-radius:4px;padding:1px 5px;white-space:nowrap;}' +
      '.wfh-tip{background:#fff8ee;border:1px solid #f3c98b;border-radius:8px;padding:9px 12px;margin:0 0 12px;font-size:13px;color:#6b4a12;}' +
      '@media (max-width:600px){.wfh-ov{padding:0;}.wfh-box{border-radius:0;min-height:100%;}.wfh-head{border-radius:0;}.wfh-dl{grid-template-columns:1fr;gap:0 0;}.wfh-dl dd{margin-bottom:8px;}.wfh-tbl{display:block;overflow-x:auto;}}';
    document.head.appendChild(st);
  }
  function h(tag, text, cls) { var e = document.createElement(tag); if (text != null) e.textContent = text; if (cls) e.className = cls; return e; }
  function sec(title, open, fill) {
    var d = document.createElement('details'); d.className = 'wfh-sec'; if (open) d.open = true;
    d.appendChild(h('summary', title)); var inner = h('div', null, 'wfh-in'); fill(inner); d.appendChild(inner); return d;
  }
  function list(parent, tag, items) { var l = document.createElement(tag); items.forEach(function (t) { l.appendChild(h('li', t)); }); parent.appendChild(l); }
  function dl(parent, pairs) { var d = h('dl', null, 'wfh-dl'); pairs.forEach(function (p) { d.appendChild(h('dt', p[0])); d.appendChild(h('dd', p[1])); }); parent.appendChild(d); }
  function table(parent, heads, rows, codeCol) {
    var t = h('table', null, 'wfh-tbl'), hr = t.insertRow();
    heads.forEach(function (x) { hr.appendChild(h('th', x)); });
    rows.forEach(function (r) { var tr = t.insertRow(); r.forEach(function (c, i) { var td = tr.insertCell(); if (codeCol && codeCol.indexOf(i) >= 0) td.appendChild(h('span', c, 'wfh-code')); else td.textContent = c; }); });
    parent.appendChild(t);
  }

  function open() {
    css();
    var old = document.getElementById('wfHelp'); if (old) old.remove();
    var ov = h('div', null, 'wfh-ov'); ov.id = 'wfHelp';
    var box = h('div', null, 'wfh-box'); box.setAttribute('role', 'dialog'); box.setAttribute('aria-modal', 'true'); box.setAttribute('aria-labelledby', 'wfHelpTitle');
    var head = h('div', null, 'wfh-head'); var ttl = h('h2', 'Automations — Help'); ttl.id = 'wfHelpTitle';
    var x = h('button', '✕', 'wfh-x'); x.type = 'button'; x.setAttribute('aria-label', 'Close help');
    head.appendChild(ttl); head.appendChild(x); box.appendChild(head);
    var body = h('div', null, 'wfh-body');

    body.appendChild(sec('What an automation is', true, function (b) {
      b.appendChild(h('p', 'An automation is a rule that does a small job for you when something happens in ChurchGeniusPro. Every rule has three parts:'));
      list(b, 'ul', ['Trigger — the event that starts it (for example “A visitor checks in”).',
        'Conditions — optional checks that must ALL be true (for example “the visitor is new”). With no conditions the rule runs every time the trigger happens.',
        'Actions — what the rule does, in order (for example “Create a follow-up”).']);
      b.appendChild(h('p', 'Automations only ever create follow-ups, notes and messages for your own staff. A rule never sends an e-mail, text or notification to a member, visitor or giver.', 'wfh-tip'));
    }));

    body.appendChild(sec('Set up a rule, step by step', true, function (b) {
      list(b, 'ol', [
        'Start from a template (scroll to Templates and press “+ Add”), or press “+ New rule”. You can also type what you want under “In your own words” and press “✨ Draft the rule” — the form is filled in for you to check.',
        'Give the rule a Name and choose its Trigger.',
        'Add Conditions if the rule should only run in some cases. Each condition is a fact, a comparison and a value.',
        'Add at least one Action and fill in its fields.',
        'Press Save. The rule is saved switched OFF.',
        'Press “▶ Test run” to see what the rule would do with example data. A test run writes nothing and sends nothing.',
        'Switch the rule on with the On/Off switch on its card.',
        'After the event has really happened, press “📜 Runs” to see the result.']);
    }));

    body.appendChild(sec('The fields, one by one', false, function (b) {
      dl(b, [
        ['In your own words', 'Optional. Describe the rule in a sentence and press “✨ Draft the rule”. It only fills in the form below; nothing is saved until you press Save. Why: it is the quickest way to start if you are unsure which trigger or action to pick.'],
        ['Name', 'Required. A short title you will recognise, such as “Welcome call for new visitors”. Why: it is shown on the rule’s card and in the Runs list. Give each rule its own name.'],
        ['Trigger', 'Required. The event that starts the rule. Why: it decides when the rule is looked at and which facts you can use in conditions and messages. See “Triggers” below.'],
        ['Description', 'Optional. A note for you and other administrators about what the rule is for.'],
        ['Conditions', 'Optional, up to 10. Pick a fact of the event (left), a comparison (middle) and type the value (right). All conditions must be true for the rule to run. Why: without them the rule runs for every event of that kind.'],
        ['Actions', 'Required, 1 to 5. Pick what should happen and fill in its fields; fields marked required must be filled. Actions run in the order shown.'],
        ['To / Assign to', 'Who receives the notification, e-mail or follow-up: “Rule owner” or a staff login of your church. Members cannot be chosen.'],
        ['Due in days', 'For a follow-up: how many days from the event until it is due (0 = the same day).'],
        ['Priority', 'For a follow-up: Low, Medium or High.'],
        ['Tone', 'For “Draft an e-mail with AI”: the style of the draft, such as warm or formal.'],
        ['On / Off switch', 'On the rule’s card. A rule does nothing while it is off. Every new or copied rule starts off.']]);
      b.appendChild(h('p', 'Using facts in your text: write a fact’s name between double curly brackets and it is replaced when the rule runs. For example the title “Welcome call: {{name}}” becomes “Welcome call: James Gomez”. The facts you can use are listed under the Trigger box once you choose a trigger.'));
    }));

    body.appendChild(sec('Triggers — what starts a rule', false, function (b) {
      table(b, ['Trigger', 'When it happens'], TRIGGERS);
      b.appendChild(h('p', 'The two nightly triggers are checked once a night, at about 3:20 AM Central time. All other triggers run within moments of the event being saved.'));
    }));
    body.appendChild(sec('Comparisons you can use in a condition', false, function (b) {
      table(b, ['Option', 'Meaning', 'Example'], OPS, [0, 2]);
      b.appendChild(h('p', 'Type values exactly as the fact lists them — for example INTERESTED, FAMILY, HIGH, AT_RISK. A wrong value is not an error; the condition is simply never true, and the Runs list shows “Skipped” with the condition that failed.'));
    }));
    body.appendChild(sec('Actions — what a rule can do', false, function (b) { table(b, ['Action', 'What it does'], ACTIONS); }));

    body.appendChild(sec('Who the rule runs as, and who receives things', false, function (b) {
      list(b, 'ul', [
        'The rule owner is the staff login that last saved or switched on the rule. The card shows the owner’s name.',
        'A rule can only do what its owner is allowed to do. If the owner loses the Follow-Ups permission, a rule that creates follow-ups stops and the Runs list shows “Denied”. If the owner’s login is disabled, all their rules stop until another administrator saves or switches them on.',
        '“Rule owner” as the recipient means that person. To send to someone else, choose their staff login in the To / Assign to box.',
        'E-mails go to the address on the staff login. Notifications appear in ChurchGeniusPro, and as a push notification on devices where that person allowed notifications.']);
    }));

    body.appendChild(sec('Switching on, testing and checking that it works', false, function (b) {
      list(b, 'ol', [
        'Test run (▶ in the editor): shows, with example data, whether the conditions pass and what each action would do — “Would create a follow-up …”. Nothing is created or sent.',
        'Switch the rule on with the switch on its card.',
        'Make the real event happen once — for example check in a test visitor on the Attendance page.',
        'Open “📜 Runs”. Each line is one time the rule was looked at, with its outcome and a step-by-step log.']);
      table(b, ['Outcome', 'What it means'], [
        ['Done', 'The conditions were true and every action ran.'],
        ['Skipped', 'A condition was not true, so nothing was done. The log names the condition.'],
        ['Denied', 'The owner is not allowed to do one of the actions (or the login is disabled). Nothing was done.'],
        ['Capped', 'Your church reached the daily limit of 200 rule runs. Runs start again the next day.'],
        ['Failed', 'An action could not be completed. Nothing from that run is kept. Try the Test run, then contact support if it repeats.'],
        ['Test', 'A test run from the editor.']]);
      b.appendChild(h('p', 'Good to know: the same event never runs the same rule twice; a church can have up to 50 rules; nightly rules show their runs the next morning.'));
    }));

    body.appendChild(sec('The templates, one by one', false, function (b) {
      b.appendChild(h('p', 'Pressing “+ Add” copies a template into “Your rules”, switched off, with you as the owner. You can change anything in it before switching it on. In every template the recipient is the rule owner.'));
      TEMPLATES.forEach(function (t) {
        var w = h('div', null, 'wfh-t'); w.setAttribute('data-template', t.key);
        w.appendChild(h('h4', t.name));
        dl(w, [['What it does', t.does], ['Trigger', t.trigger], ['Condition', t.condition], ['Who receives it', t.who], ['When', t.when], ['Result', t.result]]);
        b.appendChild(w);
      });
    }));

    body.appendChild(sec('Common questions', false, function (b) {
      dl(b, [
        ['My rule did nothing', 'Check that it is switched on, then open Runs. “Skipped” means a condition was not true; no line at all means the trigger event did not happen (for example the visitor matched an existing record, so they were not “new”).'],
        ['The e-mail did not arrive', 'The recipient’s staff login needs an e-mail address, and your church’s e-mail must be set up (Email Settings). The run log says when an e-mail was skipped. Trial and demo churches do not send real e-mail.'],
        ['Can it text or e-mail members?', 'No. Automations are for staff only. To contact members use Notify Email or reminders.'],
        ['Who can use Automations?', 'SuperAdmin and Admin logins with the Automations permission, on a plan that includes Automations.']]);
    }));

    box.appendChild(body); ov.appendChild(box); document.body.appendChild(ov);
    var prev = document.activeElement;
    function close() { document.removeEventListener('keydown', key, true); ov.remove(); if (prev && prev.focus) { try { prev.focus(); } catch (e) {} } }
    function key(e) { if (e.key === 'Escape') { e.preventDefault(); close(); } }
    document.addEventListener('keydown', key, true);
    x.addEventListener('click', close);
    ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
    x.focus();
  }

  function mount() {
    if (document.getElementById('wfHelpBtn')) return;
    var newBtn = document.getElementById('newBtn');
    if (!newBtn || !newBtn.parentNode) return;
    var b = document.createElement('button'); b.type = 'button'; b.id = 'wfHelpBtn'; b.className = 'btn-sec';
    b.textContent = '❓ Help'; b.setAttribute('aria-haspopup', 'dialog');
    b.addEventListener('click', open);
    newBtn.parentNode.insertBefore(b, newBtn);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
  window.CGP_workflowsHelp = { open: open, templates: TEMPLATES.map(function (t) { return t.key; }) };
})();
