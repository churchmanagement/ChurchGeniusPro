/*
 * entry-alerts.js — Income & Expense alerts on the pages (2026-11-09, spec "Income & Expense Alerts, Validation,
 * and AI Entry Confirmation").
 *
 * The server decides (EntryAlertService — the same check the Genius Assistant uses). When a new entry raises one of
 * the signed-in login's own alerts, POST /api/income|expense answers 409 {code:"ENTRY_ALERTS", alerts:[…]} and saves
 * nothing. This file:
 *   CGP_entryAlerts.review(alerts, noun) → Promise<boolean>   in-page dialog: "Go back and review" / "Save anyway"
 *   CGP_entryAlerts.post(url, payload, opts) → Promise<{res, data}>  POST that handles the 409 and re-sends with
 *                                                              alertsConfirmed:true only after the user agrees
 *   CGP_entryAlerts.mountSettings({kind, anchor})            "🔔 Alert settings" button + panel (this login, this role)
 * Every message is in-page (no browser dialogs); all text is set with textContent.
 */
(function () {
  'use strict';
  if (window.CGP_entryAlerts) return;

  function css() {
    if (document.getElementById('cgp-entry-alerts-css')) return;
    var st = document.createElement('style'); st.id = 'cgp-entry-alerts-css';
    st.textContent = [
      '.ea-back{position:fixed;inset:0;background:rgba(30,20,26,.45);z-index:10050;display:flex;align-items:center;justify-content:center;padding:16px;}',
      '.ea-dlg{background:#fff;border-radius:14px;max-width:460px;width:100%;box-shadow:0 18px 50px rgba(0,0,0,.25);padding:20px 20px 16px;font-family:inherit;}',
      '.ea-dlg h2{margin:0 0 6px;font-size:17px;color:#7a5a12;display:flex;gap:8px;align-items:center;}',
      '.ea-dlg p{margin:0 0 10px;font-size:13.5px;color:#555;line-height:1.5;}',
      '.ea-list{margin:0 0 14px;padding:10px 12px 10px 30px;background:#fff8e6;border:1px solid #f0d9a8;border-radius:9px;color:#5c4510;font-size:13.5px;line-height:1.5;}',
      '.ea-list li{margin:3px 0;}',
      '.ea-acts{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap;}',
      '.ea-btn{border-radius:9px;padding:9px 16px;font-size:13.5px;font-weight:700;cursor:pointer;border:1.5px solid #c5a0b5;background:#fff;color:#673147;min-height:40px;}',
      '.ea-btn.ea-go{background:#673147;border-color:#673147;color:#fff;}',
      '.ea-btn:focus-visible{outline:3px solid #e2b6cb;outline-offset:2px;}',
      '.ea-toggle{display:inline-flex;align-items:center;gap:6px;border:1.5px solid #e2d3db;background:#fff;color:#673147;border-radius:9px;padding:6px 12px;font-size:13px;font-weight:600;cursor:pointer;margin:0 0 12px;}',
      '.ea-toggle[aria-expanded="true"]{background:#fbf7f9;}',
      '.ea-panel{border:1px solid #eadfe6;border-radius:12px;background:#fff;padding:14px 16px;margin:0 0 16px;}',
      '.ea-panel h3{margin:0 0 4px;font-size:15px;color:#3b2430;}',
      '.ea-sub{font-size:12.5px;color:#777;margin:0 0 12px;line-height:1.5;}',
      '.ea-grp{font-size:12px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:#8a6a7a;margin:10px 0 6px;}',
      '.ea-row{display:flex;align-items:center;flex-wrap:wrap;gap:6px 8px;padding:7px 0;border-top:1px solid #f4eef1;font-size:13.5px;color:#333;}',
      '.ea-row label{display:flex;align-items:center;gap:8px;cursor:pointer;flex:1 1 260px;min-width:0;}',
      '.ea-row input[type=checkbox]{width:18px;height:18px;accent-color:#673147;flex:none;}',
      '.ea-val{display:flex;align-items:center;gap:6px;}',
      '.ea-val input{width:110px;border:1.5px solid #e2d3db;border-radius:8px;padding:6px 8px;font-size:13.5px;font-family:inherit;}',
      '.ea-val input:disabled{background:#f6f3f5;color:#999;}',
      '.ea-val input[aria-invalid=true]{border-color:#c62828;}',
      '.ea-err{flex-basis:100%;color:#c62828;font-size:12.5px;margin-left:26px;}',
      '.ea-foot{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:12px;}',
      '.ea-msg{font-size:13px;}',
      '.ea-msg.ok{color:#2e7d32;} .ea-msg.bad{color:#c62828;}',
      '@media (max-width:520px){.ea-val input{width:96px;} .ea-acts{flex-direction:column-reverse;} .ea-acts .ea-btn{width:100%;}}'
    ].join('\n');
    document.head.appendChild(st);
  }

  /** In-page dialog listing why the entry raised alerts. Resolves true only on "Save anyway". */
  function review(alerts, noun) {
    css();
    return new Promise(function (resolve) {
      var prev = document.activeElement;
      var back = document.createElement('div'); back.className = 'ea-back';
      var dlg = document.createElement('div'); dlg.className = 'ea-dlg';
      dlg.setAttribute('role', 'alertdialog'); dlg.setAttribute('aria-modal', 'true');
      dlg.setAttribute('aria-labelledby', 'eaTitle'); dlg.setAttribute('aria-describedby', 'eaList');
      var h = document.createElement('h2'); h.id = 'eaTitle'; h.textContent = '⚠️ Please check this ' + (noun || 'entry');
      var p = document.createElement('p'); p.textContent = 'Nothing has been saved yet. ' + (alerts.length === 1 ? 'One of your alerts was' : alerts.length + ' of your alerts were') + ' triggered:';
      var ul = document.createElement('ul'); ul.className = 'ea-list'; ul.id = 'eaList';
      (alerts || []).forEach(function (a) { var li = document.createElement('li'); li.textContent = (a && a.message) || ''; ul.appendChild(li); });
      var acts = document.createElement('div'); acts.className = 'ea-acts';
      var back1 = document.createElement('button'); back1.type = 'button'; back1.className = 'ea-btn'; back1.textContent = 'Go back and review';
      var go = document.createElement('button'); go.type = 'button'; go.className = 'ea-btn ea-go'; go.textContent = 'Save anyway';
      acts.appendChild(back1); acts.appendChild(go);
      dlg.appendChild(h); dlg.appendChild(p); dlg.appendChild(ul); dlg.appendChild(acts);
      back.appendChild(dlg); document.body.appendChild(back);
      function done(v) {
        document.removeEventListener('keydown', onKey, true);
        back.remove();
        try { if (prev && prev.focus) prev.focus(); } catch (e) {}
        resolve(v);
      }
      function onKey(e) {
        if (e.key === 'Escape') { e.preventDefault(); done(false); }
        else if (e.key === 'Tab') {                                   // keep focus inside the dialog
          var f = [back1, go], i = f.indexOf(document.activeElement);
          e.preventDefault(); f[(i + (e.shiftKey ? f.length - 1 : 1)) % f.length].focus();
        }
      }
      back1.onclick = function () { done(false); };
      go.onclick = function () { done(true); };
      back.addEventListener('click', function (e) { if (e.target === back) done(false); });
      document.addEventListener('keydown', onKey, true);
      back1.focus();
    });
  }

  /**
   * POST a new income / expense. On 409 ENTRY_ALERTS the review dialog is shown; "Save anyway" sends the same payload
   * once more with alertsConfirmed:true. Resolves {res, data, cancelled} — cancelled = the user went back to review.
   */
  function post(url, payload, opts) {
    opts = opts || {};
    function send(body) {
      return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
        .then(function (res) { return res.json().catch(function () { return {}; }).then(function (data) { return { res: res, data: data }; }); });
    }
    return send(payload).then(function (r) {
      if (r.res.status === 409 && r.data && r.data.code === 'ENTRY_ALERTS' && Array.isArray(r.data.alerts)) {
        return review(r.data.alerts, opts.noun).then(function (ok) {
          if (!ok) return { res: r.res, data: r.data, cancelled: true };
          return send(Object.assign({}, payload, { alertsConfirmed: true }));
        });
      }
      return r;
    });
  }

  /* ── settings panel ─────────────────────────────────────────────────────── */

  var ROWS = [
    { grp: 'Date alerts', on: 'futureEnabled', val: 'futureDays', kind: 'days', pre: 'Alert me if I enter a future-dated transaction more than', post: 'days ahead', def: 30 },
    { grp: null, on: 'pastEnabled', val: 'pastDays', kind: 'days', pre: 'Alert me if I enter an old-dated transaction more than', post: 'days in the past', def: 365 },
    { grp: 'Amount alerts', on: 'maxEnabled', val: 'maxAmount', kind: 'money', pre: 'Alert me if the amount is greater than', post: '', def: '' },
    { grp: null, on: 'minEnabled', val: 'minAmount', kind: 'money', pre: 'Alert me if the amount is less than', post: '', def: '' }
  ];

  function mountSettings(opt) {
    css();
    var kind = opt.kind === 'expense' ? 'expense' : 'income';
    var noun = kind === 'expense' ? 'Expense' : 'Income';
    var anchor = typeof opt.anchor === 'string' ? document.getElementById(opt.anchor) : opt.anchor;
    if (!anchor || document.getElementById('eaToggle')) return;
    var btn = document.createElement('button');
    btn.type = 'button'; btn.className = 'ea-toggle'; btn.id = 'eaToggle';
    btn.setAttribute('aria-expanded', 'false'); btn.setAttribute('aria-controls', 'eaPanel');
    btn.textContent = '🔔 ' + noun + ' alert settings';
    var panel = document.createElement('section'); panel.className = 'ea-panel'; panel.id = 'eaPanel'; panel.hidden = true;
    panel.setAttribute('aria-labelledby', 'eaPanelTitle');
    anchor.parentNode.insertBefore(btn, anchor);
    anchor.parentNode.insertBefore(panel, anchor);
    var loaded = false;
    btn.onclick = function () {
      var open = panel.hidden;
      panel.hidden = !open; btn.setAttribute('aria-expanded', String(open));
      if (open && !loaded) load();
    };

    function load() {
      panel.textContent = 'Loading your alert settings…';
      fetch('/api/entry-alerts/' + kind).then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
        .then(function (x) {
          if (!x.ok) { panel.textContent = (x.d && x.d.error) || 'Your alert settings could not be loaded.'; return; }
          loaded = true; render(x.d);
        })
        .catch(function () { panel.textContent = 'Your alert settings could not be loaded. Please try again.'; });
    }

    function render(d) {
      var s = d.settings || {};
      panel.textContent = '';
      var h = document.createElement('h3'); h.id = 'eaPanelTitle'; h.textContent = noun + ' alert settings';
      var sub = document.createElement('p'); sub.className = 'ea-sub';
      sub.textContent = 'These warnings are yours only (signed in as ' + (d.role || 'your role') + '). They never block a save — '
        + 'you see why and can save anyway. They also apply when you add ' + kind + ' through the Genius Assistant.';
      panel.appendChild(h); panel.appendChild(sub);
      var inputs = {};
      ROWS.forEach(function (r) {
        if (r.grp) { var g = document.createElement('div'); g.className = 'ea-grp'; g.textContent = r.grp; panel.appendChild(g); }
        var row = document.createElement('div'); row.className = 'ea-row';
        var lab = document.createElement('label');
        var cb = document.createElement('input'); cb.type = 'checkbox'; cb.id = 'ea_' + r.on; cb.checked = !!s[r.on];
        var txt = document.createElement('span'); txt.textContent = r.pre;
        lab.appendChild(cb); lab.appendChild(txt);
        var val = document.createElement('span'); val.className = 'ea-val';
        var inp = document.createElement('input'); inp.id = 'ea_' + r.val;
        inp.inputMode = r.kind === 'days' ? 'numeric' : 'decimal';
        inp.setAttribute('aria-label', r.pre + (r.post ? ' … ' + r.post : '') + (r.kind === 'money' ? ' (dollars)' : ''));
        inp.value = s[r.val] != null ? s[r.val] : '';
        if (r.kind === 'money') { var dol = document.createElement('span'); dol.textContent = '$'; val.appendChild(dol); }
        val.appendChild(inp);
        if (r.post) { var pt = document.createElement('span'); pt.textContent = r.post; val.appendChild(pt); }
        var err = document.createElement('div'); err.className = 'ea-err'; err.id = 'ea_err_' + r.val; err.hidden = true;
        inp.setAttribute('aria-describedby', err.id);
        inp.disabled = !cb.checked;
        cb.onchange = function () {
          inp.disabled = !cb.checked;
          if (cb.checked && !inp.value && r.def !== '') inp.value = r.def;
          if (cb.checked) inp.focus();
        };
        row.appendChild(lab); row.appendChild(val); row.appendChild(err);
        panel.appendChild(row);
        inputs[r.on] = cb; inputs[r.val] = inp; inputs['err_' + r.val] = err;
      });
      var foot = document.createElement('div'); foot.className = 'ea-foot';
      var save = document.createElement('button'); save.type = 'button'; save.className = 'btn-save'; save.textContent = 'Save alert settings';
      var msg = document.createElement('span'); msg.className = 'ea-msg'; msg.setAttribute('role', 'status');
      foot.appendChild(save); foot.appendChild(msg); panel.appendChild(foot);

      save.onclick = function () {
        var body = {};
        ROWS.forEach(function (r) {
          body[r.on] = inputs[r.on].checked;
          body[r.val] = inputs[r.val].value.trim() === '' ? null : inputs[r.val].value.trim();
          inputs['err_' + r.val].hidden = true; inputs[r.val].removeAttribute('aria-invalid');
        });
        // the same rules as the server, so mistakes show at once (the server still decides)
        var local = check(body);
        if (Object.keys(local).length) { show(local); return; }
        save.disabled = true; msg.className = 'ea-msg'; msg.textContent = 'Saving…';
        fetch('/api/entry-alerts/' + kind, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
          .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
          .then(function (x) {
            if (x.ok) { msg.className = 'ea-msg ok'; msg.textContent = '✅ Saved. New ' + kind + ' entries will be checked with these alerts.'; return; }
            if (x.d && x.d.problems) show(x.d.problems);
            msg.className = 'ea-msg bad'; msg.textContent = (x.d && x.d.error) || 'Your alert settings were not saved.';
          })
          .catch(function () { msg.className = 'ea-msg bad'; msg.textContent = 'Your alert settings were not saved — could not reach the server.'; })
          .then(function () { save.disabled = false; });
      };
      function show(problems) {
        var first = null;
        Object.keys(problems).forEach(function (k) {
          var e = inputs['err_' + k], inp = inputs[k];
          if (!e || !inp) return;
          e.textContent = problems[k]; e.hidden = false; inp.setAttribute('aria-invalid', 'true');
          if (!first) first = inp;
        });
        msg.className = 'ea-msg bad'; msg.textContent = 'Please fix the highlighted values.';
        if (first) first.focus();
      }
    }
  }

  /** Client-side copy of the server's rules (EntryAlertService.save) — field → message. */
  function check(b) {
    var p = {};
    function days(k, on, what) {
      var v = b[k];
      if (v == null) { if (on) p[k] = 'Enter the ' + what + ' (a whole number from 1 to 3650).'; return; }
      if (!/^\d{1,5}$/.test(v)) { p[k] = 'The ' + what + ' must be a whole number from 1 to 3650.'; return; }
      var n = parseInt(v, 10); if (n < 1 || n > 3650) p[k] = 'The ' + what + ' must be from 1 to 3650.';
    }
    function money(k, on, what) {
      var v = b[k] == null ? null : String(b[k]).replace(/,/g, '').replace(/^\$/, '');
      if (v == null || v === '') { if (on) p[k] = 'Enter the ' + what + ' (greater than $0).'; return; }
      if (!/^-?\d+(\.\d+)?$/.test(v)) { p[k] = 'The ' + what + ' must be a number, such as 100 or 100.50.'; return; }
      if (parseFloat(v) <= 0) { p[k] = 'The ' + what + ' must be greater than $0.'; return; }
      if (/\.\d{3,}$/.test(v)) p[k] = 'The ' + what + ' can have at most two decimal places.';
    }
    days('futureDays', b.futureEnabled, 'number of days ahead');
    days('pastDays', b.pastEnabled, 'number of days in the past');
    money('maxAmount', b.maxEnabled, 'amount for “greater than”');
    money('minAmount', b.minEnabled, 'amount for “less than”');
    if (b.maxEnabled && b.minEnabled && !p.maxAmount && !p.minAmount && b.maxAmount != null && b.minAmount != null
        && parseFloat(String(b.minAmount).replace(/,/g, '').replace(/^\$/, '')) >= parseFloat(String(b.maxAmount).replace(/,/g, '').replace(/^\$/, '')))
      p.minAmount = 'The “less than” amount must be smaller than the “greater than” amount.';
    return p;
  }

  window.CGP_entryAlerts = { review: review, post: post, mountSettings: mountSettings, _check: check };
})();
