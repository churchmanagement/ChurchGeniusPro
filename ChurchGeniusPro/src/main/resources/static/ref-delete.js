/* ============================================================================
 * ref-delete.js — Account Settings: delete / restore / permanently delete a
 * Fund category, sub-category, Purpose or Transaction Type (2026-11-16).
 *
 *   CGP_refDelete.ask({ kind, id, name, noun, onDone })     the two-choice delete dialog
 *   CGP_refDelete.mountDeleted({ list, anchor, onChange })  the "Deleted" card (Restore / Permanently delete)
 *
 * kind: main-source | sub-source | purpose | transaction-type      list: fund | purpose | transaction-type
 * Everything is written as text (textContent) — names never become markup. No browser dialogs.
 * ========================================================================== */
(function (root) {
  'use strict';

  var SOFT_URL = { 'main-source': '/api/sources/', 'sub-source': '/api/sub-sources/', 'purpose': '/api/purposes/', 'transaction-type': '/api/transaction-types/' };
  var NOUN = { 'main-source': 'main category', 'sub-source': 'sub-category', 'purpose': 'purpose', 'transaction-type': 'transaction type' };

  function css() {
    if (document.getElementById('rdStyle')) return;
    var st = document.createElement('style'); st.id = 'rdStyle';
    st.textContent =
      '.rd-ov{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:1200;display:flex;align-items:center;justify-content:center;padding:16px;}' +
      '.rd-box{background:#fff;border-radius:12px;box-shadow:0 8px 40px rgba(0,0,0,.25);width:540px;max-width:100%;max-height:calc(100vh - 32px);overflow:auto;font-size:14px;color:#333;line-height:1.5;}' +
      '.rd-head{background:#673147;color:#fff;font-weight:700;font-size:15px;padding:14px 20px;border-radius:12px 12px 0 0;word-break:break-word;}' +
      '.rd-body{padding:16px 20px 4px;}.rd-body p{margin:0 0 10px;}' +
      '.rd-use{background:#faf6f8;border:1px solid #eadfe5;border-radius:8px;padding:10px 12px;margin:0 0 12px;font-size:13px;}' +
      '.rd-use ul{margin:4px 0 0 18px;padding:0;}' +
      '.rd-opt{display:flex;gap:10px;align-items:flex-start;border:1.5px solid #e0d5db;border-radius:10px;padding:11px 12px;margin:0 0 10px;cursor:pointer;}' +
      '.rd-opt.sel{border-color:#673147;background:#fbf7f9;}.rd-opt.off{opacity:.6;cursor:not-allowed;}' +
      '.rd-opt input{margin-top:3px;flex:0 0 auto;accent-color:#673147;}.rd-opt b{display:block;color:#4a2233;}.rd-opt span{font-size:13px;color:#555;}' +
      '.rd-opt .rd-why{display:block;color:#a5322a;margin-top:4px;}' +
      '.rd-err{color:#a5322a;font-size:13px;margin:0 0 10px;}' +
      '.rd-foot{display:flex;justify-content:flex-end;gap:10px;flex-wrap:wrap;padding:8px 20px 18px;}' +
      '.rd-btn{border:1px solid #d9cdd3 !important;background:#fff !important;color:#444 !important;border-radius:8px;padding:9px 16px;font-size:14px;font-weight:600;cursor:pointer;min-height:40px;font-family:inherit;}' +
      '.rd-btn.rd-go{background:#673147 !important;border-color:#673147 !important;color:#fff !important;}' +
      '.rd-btn.rd-red{background:#b3261e !important;border-color:#b3261e !important;color:#fff !important;}' +
      '.rd-btn:disabled{opacity:.55;cursor:default;}' +
      '.rd-card{background:#fff;border:1px solid #eadfe5;border-radius:12px;margin-top:22px;overflow:hidden;}' +
      '.rd-card-h{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;padding:13px 18px;font-size:14px;font-weight:700;color:#4a2233;cursor:pointer;background:#faf6f8 !important;border:none !important;text-align:left;font-family:inherit;}' +
      '.rd-card-b{padding:6px 18px 14px;}.rd-card-b[hidden]{display:none;}' +
      '.rd-note{font-size:13px;color:#666;margin:8px 0 10px;}' +
      '.rd-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:9px 0;border-top:1px solid #f1e9ed;}' +
      '.rd-row.rd-sub{padding-left:22px;}' +
      '.rd-name{flex:1 1 180px;min-width:0;word-break:break-word;font-weight:600;color:#333;}.rd-sub .rd-name{font-weight:500;}' +
      '.rd-meta{display:block;font-weight:400;font-size:12px;color:#888;}' +
      '.rd-tag{display:inline-block;background:#fdecea;color:#a5322a;border:1px solid #f5c6c1;border-radius:10px;font-size:11px;font-weight:700;padding:1px 7px;margin-left:6px;vertical-align:middle;}' +
      '.rd-mini{border:1px solid #d9cdd3 !important;background:#fff !important;color:#4a2233 !important;border-radius:7px;padding:6px 11px;font-size:13px;font-weight:600;cursor:pointer;min-height:34px;font-family:inherit;}' +
      '.rd-mini.rd-res{background:#eef8f1 !important;border-color:#b9dcc6 !important;color:#1b6e3a !important;}' +
      '.rd-msg{font-size:13px;margin:8px 0 0;}.rd-msg.ok{color:#1b6e3a;}.rd-msg.bad{color:#a5322a;}';
    document.head.appendChild(st);
  }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function api(url, method) {
    return fetch(url, { method: method || 'GET', headers: { 'Accept': 'application/json' } }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, body: j || {} }; });
    });
  }
  function usesList(parent, uses) {
    var ul = el('ul');
    (uses || []).forEach(function (u) { ul.appendChild(el('li', null, Number(u.count).toLocaleString('en-US') + ' ' + u.label)); });
    parent.appendChild(ul);
  }
  function when(s) {
    if (!s) return '';
    var d = new Date(String(s).replace(' ', 'T'));
    return isNaN(d) ? '' : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  }

  /* ── The delete dialog: Delete (soft) or Permanently delete ─────────────── */
  function ask(o) {
    css();
    var noun = o.noun || NOUN[o.kind] || 'entry';
    var ov = el('div', 'rd-ov'), box = el('div', 'rd-box');
    box.setAttribute('role', 'alertdialog'); box.setAttribute('aria-modal', 'true'); box.setAttribute('aria-label', 'Delete ' + noun);
    box.appendChild(el('div', 'rd-head', 'Delete “' + o.name + '”?'));
    var body = el('div', 'rd-body');
    var use = el('div', 'rd-use', 'Checking where this ' + noun + ' is used…');
    body.appendChild(use);

    function option(value, title, text) {
      var lab = el('label', 'rd-opt'), inp = el('input');
      inp.type = 'radio'; inp.name = 'rdMode'; inp.value = value;
      var txt = el('div'); txt.appendChild(el('b', null, title)); txt.appendChild(el('span', null, text));
      lab.appendChild(inp); lab.appendChild(txt);
      body.appendChild(lab);
      return { lab: lab, inp: inp, txt: txt };
    }
    var soft = option('soft', 'Delete (recommended)',
      'Hides this ' + noun + (o.kind === 'main-source' ? ' and its sub-categories' : '') + ' from new entries. Every existing transaction is kept and still shows it, marked “Deleted”, and it stays available in report filters. You can restore it later.');
    var perm = option('permanent', 'Permanently delete',
      'Removes this ' + noun + (o.kind === 'main-source' ? ' and its sub-categories' : '') + ' completely. This cannot be undone, and is only possible when no record uses it.');
    soft.inp.checked = true; soft.lab.classList.add('sel');
    perm.inp.disabled = true; perm.lab.classList.add('off');
    var err = el('p', 'rd-err'); err.hidden = true; body.appendChild(err);
    box.appendChild(body);

    var foot = el('div', 'rd-foot'), no = el('button', 'rd-btn', 'Cancel'), go = el('button', 'rd-btn rd-go', 'Delete');
    no.type = go.type = 'button'; foot.appendChild(no); foot.appendChild(go); box.appendChild(foot);
    ov.appendChild(box); document.body.appendChild(ov);
    var prev = document.activeElement; no.focus();

    function mode() { return perm.inp.checked ? 'permanent' : 'soft'; }
    function sync() {
      soft.lab.classList.toggle('sel', soft.inp.checked); perm.lab.classList.toggle('sel', perm.inp.checked);
      go.textContent = mode() === 'permanent' ? 'Permanently delete' : 'Delete';
      go.className = 'rd-btn ' + (mode() === 'permanent' ? 'rd-red' : 'rd-go');
    }
    soft.inp.addEventListener('change', sync); perm.inp.addEventListener('change', sync);
    function close() { document.removeEventListener('keydown', key, true); if (ov.parentNode) ov.parentNode.removeChild(ov); if (prev && prev.focus) { try { prev.focus(); } catch (e) {} } }
    function key(e) { if (e.key === 'Escape') { e.preventDefault(); close(); } }
    document.addEventListener('keydown', key, true);
    ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
    no.addEventListener('click', close);

    api('/api/reference-data/' + o.kind + '/' + o.id + '/usage').then(function (r) {
      use.textContent = '';
      if (!r.ok) { use.textContent = 'Could not check where this ' + noun + ' is used. You can still delete it; permanent delete is turned off.'; return; }
      if (r.body.inUse) {
        use.appendChild(el('strong', null, 'Still used by:'));
        usesList(use, r.body.uses);
        perm.txt.appendChild(el('span', 'rd-why', 'Not available: existing records refer to it. They are never removed or changed to make room for a delete.'));
      } else {
        use.textContent = 'No transaction or other record uses this ' + noun + '.';
        perm.inp.disabled = false; perm.lab.classList.remove('off');
      }
    }).catch(function () { use.textContent = 'Could not check where this ' + noun + ' is used. Permanent delete is turned off.'; });

    go.addEventListener('click', function () {
      var m = mode();
      go.disabled = no.disabled = true; err.hidden = true; go.textContent = 'Deleting…';
      var call = m === 'permanent' ? api('/api/reference-data/' + o.kind + '/' + o.id + '/permanent', 'DELETE')
                                   : api(SOFT_URL[o.kind] + o.id, 'DELETE');
      call.then(function (r) {
        if (r.ok) { close(); if (o.onDone) o.onDone({ mode: m, body: r.body }); return; }
        go.disabled = no.disabled = false; sync();
        err.hidden = false; err.textContent = r.body.error || 'Could not delete. Nothing was changed.';
        if (r.body.uses && r.body.uses.length) { use.textContent = ''; use.appendChild(el('strong', null, 'Still used by:')); usesList(use, r.body.uses); perm.inp.disabled = true; perm.lab.classList.add('off'); soft.inp.checked = true; sync(); }
      }).catch(function () { go.disabled = no.disabled = false; sync(); err.hidden = false; err.textContent = 'Network error — nothing was changed.'; });
    });
  }

  /* ── The "Deleted" card: Restore / Permanently delete ───────────────────── */
  function mountDeleted(o) {
    css();
    var card = el('div', 'rd-card'); card.id = 'rdDeletedCard'; card.hidden = true;
    var head = el('button', 'rd-card-h'); head.type = 'button'; head.setAttribute('aria-expanded', 'false');
    var title = el('span', null, 'Deleted'); var chev = el('span', null, '▸');
    head.appendChild(title); head.appendChild(chev);
    var body = el('div', 'rd-card-b'); body.hidden = true;
    card.appendChild(head); card.appendChild(body);
    (o.anchor || document.querySelector('main.page-content') || document.body).appendChild(card);
    head.addEventListener('click', function () {
      body.hidden = !body.hidden; chev.textContent = body.hidden ? '▸' : '▾'; head.setAttribute('aria-expanded', String(!body.hidden));
    });
    var lastMsg = null;

    function row(kind, item, label, sub, canRestore) {
      var r = el('div', 'rd-row' + (sub ? ' rd-sub' : ''));
      var nm = el('div', 'rd-name', label); nm.appendChild(el('span', 'rd-tag', 'Deleted'));
      var meta = [when(item.deletedAt) ? 'deleted ' + when(item.deletedAt) : '', item.deletedBy ? 'by ' + item.deletedBy : ''].filter(Boolean).join(' ');
      if (meta) nm.appendChild(el('span', 'rd-meta', meta));
      r.appendChild(nm);
      if (canRestore) {
        var res = el('button', 'rd-mini rd-res', 'Restore'); res.type = 'button';
        res.setAttribute('aria-label', 'Restore ' + label);
        res.addEventListener('click', function () {
          res.disabled = true; res.textContent = 'Restoring…';
          api('/api/reference-data/' + kind + '/' + item.id + '/restore', 'POST').then(function (x) {
            if (x.ok) {
              var n = Number(x.body.restoredSubCategories) || 0;
              done('“' + label + '” has been restored' + (n ? ' with ' + n + ' sub-categor' + (n === 1 ? 'y' : 'ies') : '') + '.', true);
            } else { res.disabled = false; res.textContent = 'Restore'; say(x.body.error || 'Could not restore.', false); }
          }).catch(function () { res.disabled = false; res.textContent = 'Restore'; say('Network error — nothing was changed.', false); });
        });
        r.appendChild(res);
      }
      var del = el('button', 'rd-mini', 'Delete permanently'); del.type = 'button';
      del.setAttribute('aria-label', 'Permanently delete ' + label);
      var armed = false, timer = null;
      del.addEventListener('click', function () {
        if (!armed) {
          armed = true; del.textContent = 'Click again to confirm';
          timer = setTimeout(function () { armed = false; del.textContent = 'Delete permanently'; }, 6000);
          return;
        }
        clearTimeout(timer); armed = false; del.disabled = true; del.textContent = 'Deleting…';
        api('/api/reference-data/' + kind + '/' + item.id + '/permanent', 'DELETE').then(function (x) {
          if (x.ok) { done('“' + label + '” has been permanently deleted.', true); return; }
          del.disabled = false; del.textContent = 'Delete permanently';
          var msg = x.body.error || 'Could not delete. Nothing was changed.';
          if (x.body.uses && x.body.uses.length) msg += ' Still used by: ' + x.body.uses.map(function (u) { return Number(u.count).toLocaleString('en-US') + ' ' + u.label; }).join('; ') + '.';
          say(msg, false);
        }).catch(function () { del.disabled = false; del.textContent = 'Delete permanently'; say('Network error — nothing was changed.', false); });
      });
      r.appendChild(del);
      return r;
    }
    function say(text, ok) {
      if (lastMsg && lastMsg.parentNode) lastMsg.parentNode.removeChild(lastMsg);
      lastMsg = el('p', 'rd-msg ' + (ok ? 'ok' : 'bad'), text); lastMsg.setAttribute('role', ok ? 'status' : 'alert');
      body.insertBefore(lastMsg, body.firstChild);
    }
    function done(text, ok) {
      refresh().then(function () { if (!card.hidden) say(text, ok); });
      if (o.onChange) o.onChange(text);
    }
    function refresh() {
      return api('/api/reference-data/deleted?list=' + encodeURIComponent(o.list)).then(function (r) {
        if (!r.ok) { card.hidden = true; return; }
        body.textContent = ''; lastMsg = null;
        var n = 0;
        body.appendChild(el('p', 'rd-note', 'Deleted entries are hidden from new transactions. Existing transactions keep them and show a “Deleted” tag. Restore makes an entry usable again; Delete permanently is only possible when no record uses it.'));
        if (o.list === 'fund') {
          (r.body.mainCategories || []).forEach(function (m) {
            n++;
            body.appendChild(row('main-source', m, m.name, false, true));
            (m.subCategories || []).forEach(function (s) {
              var line = el('div', 'rd-row rd-sub');
              line.appendChild(el('div', 'rd-name', s.name + (s.deletedWithParent === false ? ' (deleted earlier, on its own)' : '')));
              body.appendChild(line);
            });
          });
          (r.body.subCategories || []).forEach(function (s) { n++; body.appendChild(row('sub-source', s, s.mainName + ' / ' + s.name, false, true)); });
        } else {
          (r.body.items || []).forEach(function (it) { n++; body.appendChild(row(o.list, it, it.name, false, true)); });
        }
        title.textContent = 'Deleted (' + n + ')';
        card.hidden = n === 0;
      }).catch(function () { card.hidden = true; });
    }
    refresh();
    return { refresh: refresh, element: card };
  }

  root.CGP_refDelete = { ask: ask, mountDeleted: mountDeleted };
})(window);
