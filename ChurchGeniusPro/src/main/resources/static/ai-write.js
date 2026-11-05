/* ai-write.js — AI Phase 3 (2026-11-04): the shared "✨ Draft with AI" panel for the e-mail and SMS composers.
 *
 *   CGP_AI_WRITE.mount({ id, channel:'email'|'sms', target:'<id of textarea | contenteditable>', subject:'<input id>'|null,
 *                        anchor:'<element id the button is appended to>'|null, label?, channelOf?: () => 'email'|'sms', onInsert? })
 *
 * The panel asks for what to write (plus tone / length / language), calls POST /api/ai/write through CGP_api and
 * shows the DRAFT in a preview. Insert replaces the selection (or the whole body when nothing is selected), Append
 * adds it at the end — nothing is ever sent from here; the page's own Send button stays the only way out.
 * Rewrite / Shorten / Expand / Fix / Translate work on the selected text, or on the whole body when nothing is
 * selected. The button carries data-feature="aiWrite" so session.js hides it on plans without the feature.
 * No dialogs; every error is shown inside the panel.
 */
(function () {
  if (window.CGP_AI_WRITE) return;
  var TONES = [['warm','Warm'],['formal','Formal'],['casual','Casual'],['urgent','Urgent'],['encouraging','Encouraging'],['celebratory','Celebratory']];
  var LENGTHS = [['short','Short'],['medium','Medium'],['long','Long']];
  var LANGS = ['English','Spanish','French','Portuguese','Korean','Chinese','Tagalog','Vietnamese','Hindi','Telugu','Tamil','Malayalam','Swahili','German','Italian','Arabic'];
  var CSS = '.aiw-btn{display:inline-flex;align-items:center;gap:6px;background:#fff7ed;color:#9a3412;border:1px solid #fdba74;border-radius:8px;padding:6px 11px;font-size:12.5px;font-weight:700;cursor:pointer;font-family:inherit}'
    + '.aiw-btn:hover{background:#ffedd5}.aiw-btn[disabled]{opacity:.6;cursor:wait}'
    + '.aiw-panel{border:1px solid #fdba74;background:#fffbf5;border-radius:12px;padding:12px;margin:8px 0;font-size:13px;color:#333}'
    + '.aiw-panel[hidden]{display:none!important}.aiw-row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:8px}'
    + '.aiw-row label{font-size:11.5px;color:#777;font-weight:600;display:flex;flex-direction:column;gap:3px;min-width:0}'
    + '.aiw-panel select,.aiw-panel textarea{border:1px solid #e5d5c5;border-radius:8px;padding:7px 9px;font-size:13px;font-family:inherit;background:#fff;color:#333}'
    + '.aiw-panel textarea{width:100%;min-height:58px;resize:vertical;box-sizing:border-box}'
    + '.aiw-actions{display:flex;gap:6px;flex-wrap:wrap}.aiw-actions button{border:1px solid #e5d5c5;background:#fff;border-radius:8px;padding:6px 10px;font-size:12.5px;font-weight:600;cursor:pointer;font-family:inherit;color:#444}'
    + '.aiw-actions button.aiw-primary{background:#673147;color:#fff;border-color:#673147}.aiw-actions button[disabled]{opacity:.55;cursor:wait}'
    + '.aiw-preview{white-space:pre-wrap;background:#fff;border:1px dashed #e5d5c5;border-radius:8px;padding:10px;margin:8px 0;max-height:260px;overflow:auto;line-height:1.5}'
    + '.aiw-meta{font-size:11.5px;color:#8a8a8a;margin-bottom:6px}.aiw-err{background:#ffebee;color:#c62828;border-radius:8px;padding:8px 10px;font-size:12.5px;margin:6px 0}'
    + '.aiw-note{font-size:11.5px;color:#9a3412;margin-top:6px}.aiw-subj{font-weight:700;margin-bottom:4px}'
    + '@media (max-width:640px){.aiw-row label{flex:1 1 45%}.aiw-actions button{flex:1 1 30%}}';
  function css() { if (document.getElementById('aiwCss')) return; var s = document.createElement('style'); s.id = 'aiwCss'; s.textContent = CSS; document.head.appendChild(s); }
  function esc(s) { return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
  function opts(list, sel) { return list.map(function (o) { var v = Array.isArray(o) ? o[0] : o, l = Array.isArray(o) ? o[1] : o; return '<option value="' + esc(v) + '"' + (v === sel ? ' selected' : '') + '>' + esc(l) + '</option>'; }).join(''); }

  /* ── target adapters: plain textarea/input vs contenteditable ── */
  function isCE(el) { return el && el.isContentEditable; }
  function getText(el) { return isCE(el) ? (el.innerText || '') : (el.value || ''); }
  var savedRanges = new WeakMap();   // contenteditable target → last non-collapsed Range inside it
  document.addEventListener('selectionchange', function () {
    var sel = window.getSelection && window.getSelection();
    if (!sel || !sel.rangeCount || sel.isCollapsed) return;
    var r = sel.getRangeAt(0), node = r.commonAncestorContainer;
    var el = node.nodeType === 1 ? node : node.parentElement;
    var ce = el && el.closest && el.closest('[contenteditable="true"]');
    if (ce) savedRanges.set(ce, r.cloneRange());
  });
  function liveRange(el) {
    var sel = window.getSelection && window.getSelection();
    if (sel && sel.rangeCount && !sel.isCollapsed && el.contains(sel.anchorNode)) return sel.getRangeAt(0);
    var saved = savedRanges.get(el);
    if (saved && el.contains(saved.commonAncestorContainer) && String(saved).trim()) return saved;
    return null;
  }
  function getSelection(el) {
    if (!el) return '';
    if (isCE(el)) { var r = liveRange(el); return r ? String(r) : ''; }
    if (typeof el.selectionStart === 'number' && el.selectionEnd > el.selectionStart) return el.value.substring(el.selectionStart, el.selectionEnd);
    return '';
  }
  function toHtml(text) { return text.split(/\n{2,}/).map(function (p) { return '<p>' + esc(p).replace(/\n/g, '<br>') + '</p>'; }).join(''); }
  function setText(el, text, mode) {           // mode: 'replace' (the selection, else all) | 'all' | 'append'
    if (isCE(el)) {
      var html = toHtml(text);
      var range = mode === 'replace' ? liveRange(el) : null;
      if (range) {
        range.deleteContents();
        range.insertNode(range.createContextualFragment(html));
        savedRanges.delete(el);
      } else if (mode === 'append' && el.innerText.trim()) { el.insertAdjacentHTML('beforeend', html); }
      else { el.innerHTML = html; }
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return;
    }
    if (mode === 'replace' && typeof el.selectionStart === 'number' && el.selectionEnd > el.selectionStart) {
      // (textareas keep their selection while the panel is used)
      el.value = el.value.substring(0, el.selectionStart) + text + el.value.substring(el.selectionEnd);
    } else if (mode === 'append' && el.value.trim()) { el.value = el.value.replace(/\s+$/, '') + '\n\n' + text; }
    else { el.value = text; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  var mounts = {};
  function mount(cfg) {
    css();
    var target = document.getElementById(cfg.target);
    if (!target || mounts[cfg.id]) return mounts[cfg.id];
    var channel = cfg.channel === 'sms' ? 'sms' : 'email';
    var btn = document.createElement('button');
    btn.type = 'button'; btn.className = 'aiw-btn'; btn.id = cfg.id + 'Btn'; btn.setAttribute('data-feature', 'aiWrite');
    btn.innerHTML = '✨ ' + esc(cfg.label || 'Draft with AI');
    var panel = document.createElement('div');
    panel.className = 'aiw-panel'; panel.id = cfg.id + 'Panel'; panel.hidden = true; panel.setAttribute('data-feature', 'aiWrite');
    panel.innerHTML =
      '<div class="aiw-row"><strong style="flex:1">✨ Draft with AI <span style="font-weight:400;color:#8a8a8a">— a draft for you to review; nothing is sent from here</span></strong>'
      + '<button type="button" class="aiw-close" aria-label="Close" style="border:none;background:none;cursor:pointer;font-size:16px;color:#888">✕</button></div>'
      + '<textarea class="aiw-prompt" placeholder="' + (channel === 'sms' ? 'e.g. remind the ushers about Saturday 9 am training, friendly' : 'e.g. a warm reminder for Sunday\'s volunteer training at 9 am, with thanks') + '"></textarea>'
      + '<div class="aiw-row" style="margin-top:8px"><label>Tone<select class="aiw-tone">' + opts(TONES, 'warm') + '</select></label>'
      + (channel === 'sms' ? '<label>Length<select class="aiw-length" disabled><option value="short">One text (≤160)</option></select></label>' : '<label>Length<select class="aiw-length">' + opts(LENGTHS, 'medium') + '</select></label>')
      + '<label>Language<select class="aiw-lang">' + opts(LANGS, 'English') + '</select></label></div>'
      + '<div class="aiw-actions"><button type="button" class="aiw-primary" data-act="draft">Draft</button><button type="button" data-act="rewrite">Rewrite</button>'
      + '<button type="button" data-act="shorten">Shorten</button>' + (channel === 'sms' ? '' : '<button type="button" data-act="expand">Expand</button>')
      + '<button type="button" data-act="fix">Fix grammar</button><button type="button" data-act="translate">Translate</button></div>'
      + '<div class="aiw-meta" style="margin-top:6px">Rewrite, Shorten' + (channel === 'sms' ? '' : ', Expand') + ', Fix and Translate work on the selected text, or the whole message when nothing is selected.</div>'
      + '<div class="aiw-err" hidden></div><div class="aiw-result" hidden><div class="aiw-meta"></div><div class="aiw-preview"></div>'
      + '<div class="aiw-actions"><button type="button" class="aiw-primary" data-ins="replace">Insert</button><button type="button" data-ins="append">Append</button>'
      + '<button type="button" data-ins="copy">Copy</button></div><div class="aiw-note" hidden></div></div>';
    var anchor = cfg.anchor ? document.getElementById(cfg.anchor) : null;
    if (anchor) anchor.appendChild(btn); else target.parentNode.insertBefore(btn, target);
    target.parentNode.insertBefore(panel, target.nextSibling);

    var state = { busy: false, last: null, mode: 'replace' };
    var $ = function (sel) { return panel.querySelector(sel); };
    function err(m) { var e = $('.aiw-err'); if (!m) { e.hidden = true; e.textContent = ''; } else { e.hidden = false; e.textContent = m; } }
    function busy(b) { state.busy = b; panel.querySelectorAll('.aiw-actions button[data-act]').forEach(function (x) { x.disabled = b; }); btn.disabled = b; }
    btn.addEventListener('click', function () { panel.hidden = !panel.hidden; if (!panel.hidden) $('.aiw-prompt').focus(); });
    $('.aiw-close').addEventListener('click', function () { panel.hidden = true; });

    panel.addEventListener('click', function (ev) {
      var b = ev.target.closest('button[data-act]'); if (!b || state.busy) return;
      run(b.getAttribute('data-act'));
    });
    panel.addEventListener('click', function (ev) {
      var b = ev.target.closest('button[data-ins]'); if (!b || !state.last) return;
      var how = b.getAttribute('data-ins');
      if (how === 'copy') { try { navigator.clipboard && navigator.clipboard.writeText(state.last.body); b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy'; }, 1500); } catch (e) {} return; }
      // Replace = the selection when the action worked on one, else the whole body
      setText(target, state.last.body, how === 'append' ? 'append' : (state.mode === 'selection' ? 'replace' : 'all'));
      if (cfg.subject && state.last.subject) { var s = document.getElementById(cfg.subject); if (s && !s.value.trim()) { s.value = state.last.subject; s.dispatchEvent(new Event('input', { bubbles: true })); } }
      $('.aiw-result').hidden = true; panel.hidden = true;
      if (typeof cfg.onInsert === 'function') cfg.onInsert(state.last, how);
    });

    async function run(action) {
      err(null);
      var prompt = $('.aiw-prompt').value.trim();
      var selected = getSelection(target).trim();
      var whole = getText(target).trim();
      var text = action === 'draft' ? '' : (selected || whole);
      state.mode = action === 'draft' ? 'all' : (selected ? 'selection' : 'all');
      if (action === 'draft' && !prompt) { err('Describe what to write first.'); return; }
      if (action !== 'draft' && !text) { err('Type or select the text to work on first.'); return; }
      var subjEl = cfg.subject ? document.getElementById(cfg.subject) : null;
      var liveChannel = typeof cfg.channelOf === 'function' ? (cfg.channelOf() === 'sms' ? 'sms' : 'email') : channel;
      var body = { action: action, channel: liveChannel, prompt: prompt, text: text, tone: $('.aiw-tone').value, length: $('.aiw-length').value,
                   language: $('.aiw-lang').value, wantSubject: !!(liveChannel === 'email' && subjEl && !subjEl.value.trim() && action === 'draft') };
      busy(true);
      try {
        var r = await CGP_api('/api/ai/write', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        state.last = r;
        $('.aiw-result').hidden = false;
        $('.aiw-result .aiw-meta').textContent = (r.chars || 0) + ' characters · ' + action + (state.mode === 'selection' ? ' (selected text)' : '') + ' · draft — review before sending';
        $('.aiw-preview').innerHTML = (r.subject ? '<div class="aiw-subj">Subject: ' + esc(r.subject) + '</div>' : '') + esc(r.body);
        var n = $('.aiw-note'); if (r.note) { n.hidden = false; n.textContent = r.note; } else { n.hidden = true; }
      } catch (e) {
        state.last = null; $('.aiw-result').hidden = true;
        err(CGP_errorMessage(e, "The assistant couldn't produce a draft just now."));
      } finally { busy(false); }
    }
    mounts[cfg.id] = { button: btn, panel: panel, run: run, state: state };
    return mounts[cfg.id];
  }
  window.CGP_AI_WRITE = { mount: mount, css: css, _sanitizeForTest: { esc: esc, toHtml: toHtml } };
})();
