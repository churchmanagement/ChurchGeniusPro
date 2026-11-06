/* ============================================================================
 * ai-consent.js — AI data-sharing notices in the iOS / Android app
 * (App Store readiness D6, approved 2026-11-06).
 *
 * In a web browser this file does NOTHING: CGP_aiConsent.ensure() resolves true at once,
 * no request is made and window.fetch is not wrapped. Inside the app:
 *
 *   CGP_aiConsent.ensure('VOICE'|'ASSISTANT'|'SCAN', {onAgree}) -> Promise<boolean>
 *       Shows the notice once per person (server-side, every device) and resolves true when
 *       the person agreed (now or before), false on Cancel or when the check failed.
 *       opts.onAgree runs synchronously inside the Agree click (file pickers need a gesture).
 *   CGP_aiConsent.rememberVoice(transcript, token)   (voice-openai.js)
 *       The server's one-time voice token for that transcript; attached to the follow-up
 *       request carrying the same text (/api/ai-assist, /api/help/assist, /api/voice/extract).
 *   CGP_aiConsent.openSettings()   "AI data sharing": status + Stop sharing / Stop all.
 *
 * Safety net: a 403 AI_CONSENT_REQUIRED from any AI path shows the notice and, after Agree,
 * repeats the request once. The server refuses every AI request from the app without consent
 * (AiGuardFilter), so nothing reaches OpenAI first — this file only makes that pleasant.
 * Elements with data-ai-consent="SCAN" (scan buttons) ask before the picker opens.
 * ========================================================================== */
(function () {
  'use strict';
  if (window.CGP_aiConsent) return;

  var UA = (typeof navigator !== 'undefined' && navigator.userAgent) || '';
  function isApp() {
    if (/ChurchGeniusProApp\//.test(UA)) return true;
    if (window.CGP_NATIVE && window.CGP_NATIVE.isNative) return true;
    try { return !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform()); } catch (e) { return false; }
  }

  var DUAL = { '/api/ai-assist': 1, '/api/help/assist': 1, '/api/voice/extract': 1 };
  var NOTICE_PATHS = {
    '/api/voice/command': 1, '/api/ai-search': 1, '/api/ai-search/data': 1, '/api/ai/write': 1,
    '/api/ai/forms/draft': 1, '/api/workflows/draft': 1, '/api/ai-assist': 1, '/api/help/assist': 1,
    '/api/voice/extract': 1, '/api/income/check-scan': 1, '/api/expense/check-scan': 1,
    '/api/bank-import/scan': 1, '/api/family/scan-extract': 1, '/api/kids-ministry/child-scan-extract': 1
  };

  var origFetch = window.fetch ? window.fetch.bind(window) : null;
  var state = null, loadedAt = 0, loading = null, open = null;

  function pathOf(url) {
    try { return new URL(url, location.href).pathname.replace(/\/+$/, '') || '/'; } catch (e) { return ''; }
  }

  function load(force) {
    if (!origFetch) return Promise.resolve(null);
    if (!force && state && Date.now() - loadedAt < 60000) return Promise.resolve(state);
    if (loading) return loading;
    loading = origFetch('/api/ai/consent', { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { loading = null; if (j && j.notices) { state = j; loadedAt = Date.now(); } return j; })
      .catch(function () { loading = null; return null; });
    return loading;
  }
  function notice(type) {
    if (!state || !state.notices) return null;
    for (var i = 0; i < state.notices.length; i++) if (state.notices[i].type === type) return state.notices[i];
    return null;
  }
  function markNotAgreed(type) { var n = notice(type); if (n) n.agreed = false; }
  /** Synchronous: known to be agreed right now (state loaded, fresh, agreed). */
  function agreedNow(type) { var n = notice(type); return !!(n && n.agreed && Date.now() - loadedAt < 60000); }

  /* ── The notice dialog (in-page; never a browser dialog) ─────────────────── */
  var styled = false;
  function css() {
    if (styled) return; styled = true;
    var st = document.createElement('style');
    st.id = 'cgpAiConsentStyle';
    st.textContent =
      '#cgpAiConsent{position:fixed;inset:0;z-index:2147483600;background:rgba(20,18,30,.55);display:flex;align-items:center;justify-content:center;padding:16px;}' +
      '#cgpAiConsent .cac-card{background:#fff;color:#2a2230;max-width:440px;width:100%;border-radius:16px;box-shadow:0 18px 48px rgba(0,0,0,.3);padding:22px 20px 18px;max-height:calc(100vh - 32px);overflow:auto;box-sizing:border-box;}' +
      '#cgpAiConsent h2{font-size:17px;line-height:1.3;margin:0 0 12px;font-weight:700;}' +
      '#cgpAiConsent p{font-size:14.5px;line-height:1.5;margin:0 0 10px;}' +
      '#cgpAiConsent .cac-err{color:#b3261e;font-size:13px;margin:4px 0 0;min-height:0;}' +
      '#cgpAiConsent .cac-btns{display:flex;gap:10px;justify-content:flex-end;margin-top:16px;flex-wrap:wrap;}' +
      '#cgpAiConsent button{font:600 14.5px/1 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;border-radius:10px;padding:12px 16px;min-height:44px;cursor:pointer;border:1px solid transparent;}' +
      '#cgpAiConsent .cac-cancel{background:#f1eef3;color:#4a3d52;border-color:#e2dce6;}' +
      '#cgpAiConsent .cac-agree{background:#6b2d5c;color:#fff;}' +
      '#cgpAiConsent button[disabled]{opacity:.6;cursor:default;}' +
      '#cgpAiConsent .cac-row{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;padding:10px 0;border-top:1px solid #eee;}' +
      '#cgpAiConsent .cac-row b{display:block;font-size:14px;margin-bottom:2px;}' +
      '#cgpAiConsent .cac-row span{font-size:12.5px;color:#6d6275;}' +
      '#cgpAiConsent .cac-row button{padding:9px 12px;min-height:38px;font-size:13px;white-space:nowrap;}';
    (document.head || document.documentElement).appendChild(st);
  }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }

  function showNotice(n, opts) {
    css();
    return new Promise(function (resolve) {
      var wrap = el('div'); wrap.id = 'cgpAiConsent';
      wrap.setAttribute('role', 'dialog'); wrap.setAttribute('aria-modal', 'true');
      var card = el('div', 'cac-card');
      var h = el('h2', null, n.title); h.id = 'cgpAiConsentTitle';
      wrap.setAttribute('aria-labelledby', 'cgpAiConsentTitle');
      card.appendChild(h);
      (n.paragraphs || []).forEach(function (p) { card.appendChild(el('p', null, p)); });
      var err = el('div', 'cac-err'); err.setAttribute('role', 'alert');
      card.appendChild(err);
      var btns = el('div', 'cac-btns');
      var cancel = el('button', 'cac-cancel', n.cancel || 'Cancel'); cancel.type = 'button';
      var agree = el('button', 'cac-agree', n.agree || 'Agree & Continue'); agree.type = 'button';
      btns.appendChild(cancel); btns.appendChild(agree); card.appendChild(btns); wrap.appendChild(card);
      document.body.appendChild(wrap);
      try { agree.focus(); } catch (e) {}
      var done = false;
      function finish(v) { if (done) return; done = true; document.removeEventListener('keydown', onKey, true); wrap.remove(); resolve(v); }
      function onKey(e) { if (e.key === 'Escape') { e.preventDefault(); finish(false); } }
      document.addEventListener('keydown', onKey, true);
      cancel.onclick = function () { finish(false); };
      agree.onclick = function () {
        agree.disabled = cancel.disabled = true; err.textContent = '';
        var sync = opts && typeof opts.onAgree === 'function' ? opts.onAgree : null;
        origFetch('/api/ai/consent', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
          body: JSON.stringify({ type: n.type, version: n.version })
        }).then(function (r) {
          if (r.ok) { n.agreed = true; loadedAt = Date.now(); finish(true); return; }
          if (r.status === 409) { state = null; finish('outdated'); return; }
          return r.json().catch(function () { return {}; }).then(function (j) {
            err.textContent = (j && j.error) || 'Could not save your answer. Please try again.';
            agree.disabled = cancel.disabled = false;
          });
        }).catch(function () {
          err.textContent = 'Could not save your answer. Please check your connection and try again.';
          agree.disabled = cancel.disabled = false;
        });
        // A file picker must open inside the tap itself; the server still refuses the upload
        // until the answer above is stored (and the request is repeated once if needed).
        if (sync) { try { sync(); } catch (e) {} }
      };
    });
  }

  function ensure(type, opts) {
    if (!isApp()) return Promise.resolve(true);
    if (open && open.type === type) return open.promise;
    var p = load(!agreedNow(type)).then(function (st) {
      var n = notice(type);
      if (!st || !n) { toast('AI data sharing could not be checked. Please try again.'); return false; }
      if (st.available === false) { toast('AI features are not available for this sign-in. Please sign in again.'); return false; }
      if (n.agreed) return true;
      return showNotice(n, opts).then(function (v) {
        if (v === 'outdated') return load(true).then(function () { return ensure(type, opts); });
        return v === true;
      });
    });
    open = { type: type, promise: p };
    p.then(function () { open = null; }, function () { open = null; });
    return p;
  }

  function toast(msg) {
    try { if (window.CGP_showError) { window.CGP_showError(msg); return; } } catch (e) {}
    css();
    var t = el('div', null, msg);
    t.style.cssText = 'position:fixed;left:16px;right:16px;bottom:24px;z-index:2147483601;background:#2a2230;color:#fff;padding:12px 14px;border-radius:12px;font:500 14px/1.4 -apple-system,BlinkMacSystemFont,sans-serif;text-align:center;';
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 4000);
  }

  /* ── One-time voice tokens (server-issued; matched by transcript) ───────── */
  var voice = [];
  function norm(s) { return String(s == null ? '' : s).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim(); }
  function rememberVoice(transcript, token) {
    if (!token || !norm(transcript)) return;
    voice.push({ n: norm(transcript), t: token, at: Date.now() });
    if (voice.length > 10) voice.shift();
  }
  function takeVoice(text) {
    var n = norm(text), now = Date.now();
    for (var i = voice.length - 1; i >= 0; i--) {
      if (now - voice[i].at > 5 * 60000) { voice.splice(i, 1); continue; }
      if (voice[i].n === n) { var t = voice[i].t; voice.splice(i, 1); return t; }
    }
    return null;
  }
  function bodyText(body) {
    if (typeof body !== 'string') return null;
    try { var j = JSON.parse(body); return j && (j.query || j.question || j.text) || null; } catch (e) { return null; }
  }
  function withHeader(init, name, value) {
    var h = new Headers((init && init.headers) || {});
    if (value == null) h.delete(name); else h.set(name, value);
    var copy = {}; for (var k in init) copy[k] = init[k];
    copy.headers = h;
    return copy;
  }

  /* ── fetch safety net (app only) ──────────────────────────────────────────── */
  function install() {
    if (!origFetch) return;
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var path = pathOf(url);
      if (!NOTICE_PATHS[path] || typeof input !== 'string') return origFetch(input, init);
      init = init || {};
      if (DUAL[path]) {
        var tok = takeVoice(bodyText(init.body));
        init = withHeader(init, 'X-CGP-Voice-Token', tok);
      }
      return attempt(input, init, 0);
    };
  }
  function attempt(input, init, round) {
    return origFetch(input, init).then(function (r) {
      if (r.status !== 403 || round > 1) return r;
      return r.clone().json().then(function (j) {
        if (!j || !j.code) return r;
        if (j.code === 'AI_VOICE_TOKEN_INVALID') return attempt(input, withHeader(init, 'X-CGP-Voice-Token', null), round + 1);
        if (j.code !== 'AI_CONSENT_REQUIRED' || !j.notice) return r;
        markNotAgreed(j.notice);
        if (j.notice === 'VOICE' && pathOf(input) === '/api/voice/command') return r;   // voice asks before recording
        return ensure(j.notice).then(function (ok) {
          return ok ? attempt(input, j.notice === 'VOICE' ? init : withHeader(init, 'X-CGP-Voice-Token', null), round + 1) : r;
        });
      }).catch(function () { return r; });
    });
  }

  /* ── Ask before a scan picker opens ([data-ai-consent]) ──────────────────── */
  function onClickCapture(e) {
    var t = e.target && e.target.closest ? e.target.closest('[data-ai-consent]') : null;
    if (!t) return;
    if (t.__cgpConsentPass) { t.__cgpConsentPass = false; return; }
    var type = t.getAttribute('data-ai-consent');
    if (agreedNow(type)) return;
    e.preventDefault(); e.stopImmediatePropagation();
    var clicked = false;
    function go() { if (clicked) return; clicked = true; t.__cgpConsentPass = true; t.click(); }
    ensure(type, { onAgree: go }).then(function (ok) { if (ok) go(); });
  }

  /* ── "AI data sharing" panel — Stop sharing with AI ──────────────────────── */
  var LABEL = { VOICE: 'Voice entry', ASSISTANT: 'AI Assistant', SCAN: 'Document scan' };
  function openSettings() {
    css();
    var old = document.getElementById('cgpAiConsent'); if (old) old.remove();
    return load(true).then(function (st) {
      var wrap = el('div'); wrap.id = 'cgpAiConsent';
      wrap.setAttribute('role', 'dialog'); wrap.setAttribute('aria-modal', 'true'); wrap.setAttribute('aria-labelledby', 'cgpAiShareTitle');
      var card = el('div', 'cac-card');
      var h = el('h2', null, 'AI data sharing'); h.id = 'cgpAiShareTitle'; card.appendChild(h);
      card.appendChild(el('p', null, 'These are the AI features you agreed to share data with OpenAI for. Stopping takes effect at once on all your devices; you will be asked again the next time you use that feature. Information already sent is not recalled.'));
      var err = el('div', 'cac-err'); err.setAttribute('role', 'alert');
      if (!st || !st.notices) { err.textContent = 'AI data sharing could not be loaded. Please try again.'; card.appendChild(err); }
      else {
        st.notices.forEach(function (n) {
          var row = el('div', 'cac-row');
          var info = el('div');
          info.appendChild(el('b', null, LABEL[n.type] || n.type));
          info.appendChild(el('span', null, n.agreed ? 'Sharing allowed' + (n.agreedAt ? ' since ' + String(n.agreedAt).slice(0, 10) : '') : 'Not sharing — you will be asked first'));
          row.appendChild(info);
          if (n.agreed) {
            var b = el('button', 'cac-cancel', 'Stop sharing'); b.type = 'button';
            b.onclick = function () { withdraw(n.type, b, err); };
            row.appendChild(b);
          }
          card.appendChild(row);
        });
        card.appendChild(err);
      }
      var btns = el('div', 'cac-btns');
      var anyOn = st && st.notices && st.notices.some(function (n) { return n.agreed; });
      if (anyOn) {
        var all = el('button', 'cac-cancel', 'Stop all'); all.type = 'button';
        all.onclick = function () { withdraw('all', all, err); };
        btns.appendChild(all);
      }
      var close = el('button', 'cac-agree', 'Close'); close.type = 'button';
      close.onclick = function () { wrap.remove(); };
      btns.appendChild(close); card.appendChild(btns); wrap.appendChild(card);
      document.body.appendChild(wrap);
      try { close.focus(); } catch (e) {}
    });
  }
  function withdraw(type, btn, err) {
    btn.disabled = true; err.textContent = '';
    origFetch('/api/ai/consent/withdraw', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ type: type })
    }).then(function (r) {
      if (!r.ok) throw new Error('x');
      state = null; return openSettings();
    }).catch(function () { btn.disabled = false; err.textContent = 'Could not stop sharing. Please try again.'; });
  }

  window.CGP_aiConsent = {
    isApp: isApp, ensure: ensure, rememberVoice: rememberVoice, openSettings: openSettings,
    _takeVoice: takeVoice
  };

  if (isApp()) {
    install();
    document.addEventListener('click', onClickCapture, true);
    load(true);                 // so a scan button can decide inside the tap
    document.documentElement.classList.add('cgp-ai-consent-app');
  }
})();
