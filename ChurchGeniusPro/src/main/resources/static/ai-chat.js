/*
 * ai-chat.js — Genius Assistant chat panel (2026-11-09).
 *
 * The conversation with the assistant as a messaging-style panel: one transcript of bubbles, a message box with
 * Submit at the bottom, choice cards, a review card and clear Draft / Saving / Saved / Cancelled states.
 *
 *   • Docked on the right of every staff page (≥1280 px, the page narrows beside it), an overlay on tablets and a
 *     full-screen sheet on phones. /ai-assistant hosts the same panel at full width (page mode, #gaPageHost).
 *   • It decides NOTHING about a message: ai-assistant.js (the engine) routes every message exactly as before,
 *     calls the server, checks consent, saves through the one-time confirm token. The engine reports each turn
 *     through window.CGP_ASSISTANT_UI (question / answer / busy / notice / prefill), which this file provides.
 *   • The transcript lives in this browser tab only (sessionStorage "ga.chat", 50 turns) and is dropped when a
 *     different login signs in. Nothing here is sent anywhere.
 *   • ai.chat.ui=false on the server (session.aiChatUi) → the engine never attaches this panel and the classic
 *     results box under the search bar is used instead.
 */
(function () {
  'use strict';
  if (window.CGP_CHAT) return;

  var KEY = 'ga.chat', MAX_ITEMS = 50, MAX_HTML = 60000, DOCK_MIN = 1280, PHONE_MAX = 767;
  var api = null, el = {}, pageMode = false, busyOn = false, wrapObserver = null;
  var st = { who: '', open: false, full: false, min: false, draft: null, nodes: [] };   // full = full-screen; min = minimized to the launcher   // nodes: transcript elements in order

  /* ───────────────────────────── helpers ───────────────────────────── */
  function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
  function timeOf(ts) { try { return new Date(ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); } catch (e) { return ''; } }
  function width() { return window.innerWidth || document.documentElement.clientWidth || 1280; }
  function isPhone() { return width() <= PHONE_MAX; }
  function isDock() { return !pageMode && width() >= DOCK_MIN; }
  function cap(s) { s = String(s || ''); return s.charAt(0).toUpperCase() + s.slice(1); }
  function conv(intent) { return /^(meeting|action|guided|manage|email)\./.test(String(intent || '')); }
  function pendingOf(d) { return !!(d && d.handled !== false && (d.pending === true || /^(meeting|action|guided)\.(ask|confirm)$/.test(String(d.intent || '')))); }
  /* 2026-11-11: how a turn ends a review card — also when the conversation goes on ("Group created. Add a member?"). */
  function outcomeOf(d) {
    var i = String((d && d.intent) || '');
    if (/\.(done|created|sent)$/.test(i) || d.created || d.executed) return 'saved';
    if (/cancel/.test(i)) return 'cancelled';
    if (/\.(failed|error|expired|refused)$/.test(i)) return 'failed';
    return null;
  }

  /* ───────────────────────────── persistence ───────────────────────────── */
  function save() {
    try {
      var items = st.nodes.slice(-MAX_ITEMS).map(function (n) { var h = n.outerHTML; return h.length > MAX_HTML ? '' : h; }).filter(Boolean);
      sessionStorage.setItem(KEY, JSON.stringify({ who: st.who, open: st.open, full: st.full, min: st.min, draft: st.draft, items: items }));
    } catch (e) { /* private window / full storage: the panel still works for this page */ }
  }
  function load() {
    try { var d = JSON.parse(sessionStorage.getItem(KEY) || 'null'); return d && Array.isArray(d.items) ? d : null; } catch (e) { return null; }
  }
  function forget() { try { sessionStorage.removeItem(KEY); } catch (e) {} }

  /* ───────────────────────────── styles ───────────────────────────── */
  function injectStyles() {
    if (document.getElementById('ga-styles')) return;
    var css = [
      ':root{--ga-plum:#673147;--ga-plum-d:#4f2538;--ga-soft:#f6f1f4;--ga-line:#e7dee3;--ga-muted:#7a6f7e;--ga-ok:#1f7a45;--ga-okbg:#eaf6ef;--ga-warn:#8a4b0f;--ga-warnbg:#fff6e6;--ga-w:420px;}',
      '.ga-panel{position:fixed;top:env(safe-area-inset-top,0px);right:0;bottom:0;width:var(--ga-w);max-width:100vw;z-index:960;background:#fff;border-left:1px solid var(--ga-line);box-shadow:-10px 0 28px rgba(40,10,25,.08);display:none;flex-direction:column;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#26232b;}',
      'html.ga-open .ga-panel{display:flex;}',
      /* full screen: the whole browser window (below the status bar in the app); the page underneath does not scroll */
      'html.ga-open.ga-full .ga-panel{left:0;right:0;width:auto;max-width:none;height:auto;bottom:0;border-left:0;box-shadow:none;z-index:990;}',
      'html.ga-open.ga-full,html.ga-open.ga-full body{overflow:hidden;}',
      'html.ga-open.ga-full .ga-backdrop{display:none !important;}',
      'html.ga-full .ga-log,html.ga-full .ga-composer,html.ga-full .ga-draft,html.ga-full .ga-hint{padding-left:max(16px, calc((100% - 1100px) / 2));padding-right:max(16px, calc((100% - 1100px) / 2));}',
      '.ga-full-btn .ga-i-exit{display:none;}',
      'html.ga-full .ga-full-btn .ga-i-exit{display:inline;}',
      'html.ga-full .ga-full-btn .ga-i-enter{display:none;}',
      'html.ga-min:not(.ga-open) .ga-fab{display:flex;}',
      'html.ga-docked .app{padding-right:var(--ga-w);box-sizing:border-box;}',
      /* grid / flex children holding a chart canvas must be allowed to shrink beside the docked panel (Attendance charts) */
      'html.ga-docked .page-content .charts > *{min-width:0;}',
      'html.ga-docked .page-content canvas{max-width:100%;}',
      '.ga-backdrop{display:none;position:fixed;inset:0;background:rgba(30,10,20,.32);z-index:955;}',
      'html.ga-open.ga-overlay .ga-backdrop{display:block;}',
      '@media (min-width:768px) and (max-width:1279px){.ga-panel{--ga-w:480px;}}',
      /* 2026-11-10: in the Android / iOS app the page is drawn under the status bar (mobile.css pushes the app down by
         env(safe-area-inset-top)); the panel starts below it too, so its header buttons are never under the status icons.
         The inset is 0 in a browser. */
      '@media (max-width:767px){.ga-panel{width:100vw;border-left:0;box-shadow:none;height:calc(100dvh - env(safe-area-inset-top,0px));bottom:auto;padding-bottom:env(safe-area-inset-bottom);}html.ga-open.ga-sheet,html.ga-open.ga-sheet body{overflow:hidden;}}',
      '.ga-panel.ga-page{position:relative;display:flex;width:auto;height:100%;max-width:none;border:1px solid var(--ga-line);border-radius:16px;box-shadow:0 2px 14px rgba(103,49,71,.08);z-index:auto;overflow:hidden;}',
      /* header */
      '.ga-head{display:flex;align-items:center;gap:10px;padding:10px 12px;border-bottom:1px solid var(--ga-line);background:#fff;flex-shrink:0;min-height:60px;}',
      '.ga-av{width:34px;height:34px;border-radius:11px;background:linear-gradient(135deg,#7d3f58,#a5577a);display:flex;align-items:center;justify-content:center;color:#fff;font-size:16px;flex-shrink:0;}',
      '.ga-title{display:flex;flex-direction:column;min-width:0;}',
      '.ga-title b{font-size:15px;color:#26232b;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
      '.ga-status{font-size:11.5px;color:var(--ga-ok);display:flex;align-items:center;gap:5px;}',
      '.ga-status::before{content:"";width:7px;height:7px;border-radius:50%;background:currentColor;}',
      '.ga-status.ga-thinking{color:#8a5a6c;}',
      '.ga-status.ga-listening{color:#b4232f;}',
      '.ga-tools{margin-left:auto;display:flex;align-items:center;gap:2px;}',
      '.ga-new{border:1px solid var(--ga-line);border-radius:999px;padding:6px 11px;font-size:12px;color:#4a4250;background:#fff;cursor:pointer;white-space:nowrap;}',
      '.ga-new:hover{border-color:var(--ga-plum);color:var(--ga-plum);}',
      '.ga-ib{width:34px;height:34px;border-radius:9px;border:none;background:none;color:#6b6070;font-size:16px;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;}',
      '.ga-ib:hover{background:var(--ga-soft);color:var(--ga-plum);}',
      '.ga-ib:focus-visible,.ga-new:focus-visible,.ga-submit:focus-visible,.ga-mic:focus-visible,.ga-panel button:focus-visible{outline:2px solid var(--ga-plum);outline-offset:2px;}',
      '.ga-menu{position:absolute;right:10px;top:56px;background:#fff;border:1px solid var(--ga-line);border-radius:12px;box-shadow:0 10px 28px rgba(40,10,25,.14);padding:6px;min-width:210px;z-index:5;}',
      '.ga-menu[hidden]{display:none;}',
      '.ga-menu button,.ga-menu a{display:flex;width:100%;align-items:center;gap:8px;text-align:left;border:none;background:none;padding:9px 10px;border-radius:8px;font-size:13.5px;color:#333;cursor:pointer;text-decoration:none;box-sizing:border-box;}',
      '.ga-menu button:hover,.ga-menu a:hover{background:var(--ga-soft);}',
      '.ga-menu .ga-switch{margin-left:auto;font-size:11px;font-weight:700;color:var(--ga-muted);}',
      '.ga-menu .ga-switch.on{color:var(--ga-plum);}',
      /* transcript */
      '.ga-log{flex:1;min-height:0;overflow-y:auto;padding:16px 14px 10px;display:flex;flex-direction:column;gap:12px;background:#fbfafb;overscroll-behavior:contain;}',
      '.ga-msg{display:flex;gap:8px;align-items:flex-end;max-width:100%;animation:gaIn .16s ease-out;}',
      '@keyframes gaIn{from{opacity:0;transform:translateY(4px);}to{opacity:1;transform:none;}}',
      '.ga-user{flex-direction:column;align-items:flex-end;}',
      '.ga-user .ga-bub{max-width:85%;background:var(--ga-plum);color:#fff;border-radius:16px 16px 5px 16px;padding:9px 13px;font-size:14px;line-height:1.5;white-space:pre-wrap;overflow-wrap:anywhere;}',
      '.ga-ts{font-size:10.5px;color:#a49aa8;margin-top:3px;}',
      '.ga-ai .ga-mini{width:26px;height:26px;border-radius:9px;background:linear-gradient(135deg,#7d3f58,#a5577a);color:#fff;font-size:12px;display:flex;align-items:center;justify-content:center;flex-shrink:0;}',
      '.ga-ai .ga-body{flex:1;min-width:0;display:flex;flex-direction:column;gap:8px;}',
      '.ga-ai .ga-bub{align-self:flex-start;max-width:100%;background:#fff;border:1px solid var(--ga-line);border-radius:16px 16px 16px 5px;padding:10px 13px;font-size:14px;line-height:1.55;color:#2b2b36;overflow-wrap:anywhere;}',
      '.ga-qlabel{font-size:10.5px;text-transform:uppercase;letter-spacing:.07em;color:var(--ga-plum);font-weight:700;margin-bottom:3px;}',
      '.ga-ai.ga-question .ga-bub .ai-answer{font-weight:600;}',
      '.ga-ai.ga-ok .ga-bub{background:var(--ga-okbg);border-color:#bfe3cc;color:#174d2e;}',
      '.ga-ai.ga-ok .ga-bub::before{content:"\\2713  Saved";display:block;font-size:11px;font-weight:800;letter-spacing:.06em;text-transform:uppercase;color:var(--ga-ok);margin-bottom:3px;}',
      '.ga-ai.ga-warn .ga-bub{background:var(--ga-warnbg);border-color:#f3dcb3;color:#6b3a08;}',
      '.ga-ai.ga-info .ga-bub{background:#f2f5fb;border-color:#d9e1f0;color:#28344d;}',
      '.ga-ai.ga-muted .ga-bub{background:#f3f2f4;border-color:#e6e3e8;color:#5c5662;}',
      '.ga-note{justify-content:center;}',
      '.ga-note p{font-size:12.5px;color:#6c6370;background:#f1eef2;border-radius:999px;padding:5px 12px;text-align:center;max-width:92%;}',
      '.ga-typing .ga-bub{display:inline-flex;gap:5px;align-items:center;padding:12px 14px;}',
      '.ga-typing i{width:7px;height:7px;border-radius:50%;background:#a98a99;animation:gaBlink 1.2s infinite;display:block;}',
      '.ga-typing i:nth-child(2){animation-delay:.2s;}.ga-typing i:nth-child(3){animation-delay:.4s;}',
      '@keyframes gaBlink{0%,80%,100%{opacity:.25;}40%{opacity:1;}}',
      '.ga-old .ga-bub,.ga-old .ga-extra{opacity:.82;}',
      /* answer extras (data cards, choices, review) */
      '.ga-extra{display:flex;flex-direction:column;gap:8px;min-width:0;}',
      '.ga-panel .aa-summary{display:flex;flex-wrap:wrap;gap:8px;}',
      '.ga-panel .aa-sum{flex:1 1 110px;min-width:100px;background:#fff;border:1px solid var(--ga-line);border-radius:10px;padding:8px 10px;}',
      '.ga-panel .aa-sum-k{font-size:11px;color:#7a6a72;text-transform:uppercase;letter-spacing:.03em;}',
      '.ga-panel .aa-sum-v{font-size:15px;font-weight:700;color:var(--ga-plum);margin-top:2px;overflow-wrap:anywhere;}',
      '.ga-panel .aa-sum-v.aa-sum-sm{font-size:13.5px;}',
      '.ga-panel .aa-tablewrap{max-height:320px;overflow:auto;border:1px solid var(--ga-line);border-radius:10px;background:#fff;}',
      '.ga-panel .aa-table{width:100%;border-collapse:collapse;font-size:12.5px;}',
      '.ga-panel .aa-table th{position:sticky;top:0;background:#f6eef3;color:var(--ga-plum);text-align:left;padding:7px 9px;border-bottom:1px solid var(--ga-line);white-space:nowrap;}',
      '.ga-panel .aa-table td{padding:6px 9px;border-bottom:1px solid #f3edf0;vertical-align:top;}',
      '.ga-panel .aa-more,.ga-panel .aa-sources,.ga-panel .aa-defs{font-size:12px;color:#666;}',
      '.ga-panel .aa-defs summary{cursor:pointer;color:var(--ga-plum);font-weight:600;}',
      '.ga-panel .aa-defs ul{margin:6px 0 0 18px;padding:0;}',
      '.ga-panel .aa-chips{display:flex;flex-wrap:wrap;gap:6px;}',
      '.ga-panel .aa-chip{display:inline-flex;align-items:center;gap:4px;background:#f3eef1;color:#4f2538;border:1px solid #e3d5dc;border-radius:14px;padding:3px 10px;font-size:12px;font-weight:600;}',
      '.ga-panel .aa-chip-x{border:none;background:none;color:#8a5a6c;cursor:pointer;font-size:12px;line-height:1;padding:0 2px;}',
      '.ga-panel .aa-publinks{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:6px;}',
      '.ga-panel .aa-publinks li{border:1px solid var(--ga-line);background:#fff;border-radius:9px;padding:8px 11px;font-size:13px;}',
      '.ga-panel .aa-publinks a{color:var(--ga-plum);font-weight:700;}',
      '.ga-panel .aa-suggest{background:#fff;border-color:var(--ga-line);}',
      '.ga-panel .ai-answer{font-size:14px;line-height:1.55;color:inherit;white-space:pre-wrap;}',
      '.ga-panel .ai-answer.ai-denied{color:inherit;}',
      '.ga-panel .ai-results-panel{background:none;border:none;box-shadow:none;padding:0;margin:0;}',
      '.ga-panel .ai-results-hdr,.ga-panel .ai-results-close{display:none !important;}',
      /* choice cards */
      '.ga-panel .aa-choices{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;margin:0;}',
      '.ga-panel .aa-choices.ga-few{display:flex;flex-wrap:wrap;}',
      '.ga-panel .aa-choice{display:flex;align-items:center;gap:8px;text-align:left;border:1px solid var(--ga-line);background:#fff;color:#2f2933;font-size:13px;font-weight:500;padding:9px 11px;border-radius:10px;min-height:42px;cursor:pointer;line-height:1.3;}',
      '.ga-panel .aa-choices.ga-few .aa-choice{border-radius:999px;padding:7px 13px;min-height:36px;border-color:#d9c7d0;color:var(--ga-plum);font-weight:600;}',
      '.ga-panel .aa-choices:not(.ga-few) .aa-choice::before{content:"";width:14px;height:14px;border-radius:50%;border:1.5px solid #c9bcc4;flex-shrink:0;box-sizing:border-box;}',
      '.ga-panel .aa-choice:hover:not(:disabled){border-color:var(--ga-plum);background:#fbf5f8;}',
      '.ga-panel .aa-choice.ga-sel{border-color:var(--ga-plum);background:#f7eef2;font-weight:700;color:var(--ga-plum);opacity:1;}',
      '.ga-panel .aa-choices:not(.ga-few) .aa-choice.ga-sel::before{border:4px solid var(--ga-plum);}',
      '.ga-panel .aa-choice[hidden]{display:none;}',
      '.ga-filter{width:100%;box-sizing:border-box;border:1px solid var(--ga-line);border-radius:10px;padding:8px 11px;font-size:13px;background:#faf8f9;font-family:inherit;}',
      '.ga-filter:focus{outline:2px solid var(--ga-plum);outline-offset:0;background:#fff;}',
      /* review card */
      '.ga-review{border:1px solid var(--ga-line);border-radius:14px;background:#fff;overflow:hidden;box-shadow:0 1px 2px rgba(0,0,0,.03);}',
      '.ga-review-h{display:flex;align-items:center;gap:8px;padding:10px 13px;border-bottom:1px solid var(--ga-line);}',
      '.ga-review-h b{font-size:14px;color:#26232b;}',
      '.ga-badge{margin-left:auto;font-size:11px;font-weight:700;padding:3px 9px;border-radius:999px;background:#efedf0;color:#5b5361;white-space:nowrap;}',
      '.ga-review.ga-saving .ga-badge{background:#f7eef2;color:var(--ga-plum);}',
      '.ga-review.ga-saved .ga-badge{background:var(--ga-okbg);color:var(--ga-ok);}',
      '.ga-review.ga-cancelled .ga-badge{background:#f3f2f4;color:#6c6370;}',
      '.ga-panel .ga-review .aa-fields{margin:0;border:none;border-radius:0;}',
      '.ga-panel .ga-review .aa-field{grid-template-columns:minmax(0,34%) minmax(0,1fr) auto;padding:9px 13px;}',
      '.ga-panel .ga-review .aa-actions{margin:0;padding:10px 13px;background:#fbf9fa;border-top:1px solid var(--ga-line);}',
      '.ga-panel .ga-review .aa-attach{margin:0;padding:10px 13px;border-top:1px solid var(--ga-line);}',
      '.ga-panel .aa-confirm{background:var(--ga-plum);color:#fff;border-radius:10px;padding:9px 16px;font-weight:700;font-size:13px;}',
      '.ga-panel .aa-cancel{border-radius:10px;padding:9px 14px;}',
      '.ga-panel .aa-edit{min-height:30px;}',
      /* draft bar + composer */
      '.ga-pill{position:absolute;left:50%;transform:translateX(-50%);bottom:132px;background:var(--ga-plum);color:#fff;border:none;border-radius:999px;padding:6px 13px;font-size:12px;font-weight:600;box-shadow:0 4px 12px rgba(0,0,0,.18);cursor:pointer;z-index:3;}',
      '.ga-pill[hidden]{display:none;}',
      '.ga-draft{display:flex;align-items:center;gap:8px;font-size:12.5px;padding:7px 14px;background:#f8f4f6;border-top:1px solid var(--ga-line);color:#5b5361;flex-shrink:0;}',
      '.ga-draft[hidden]{display:none;}',
      '.ga-draft .ga-dot{width:7px;height:7px;border-radius:50%;background:#b08a9c;flex-shrink:0;}',
      '.ga-draft button{margin-left:auto;border:none;background:none;color:var(--ga-plum);font-weight:700;font-size:12.5px;cursor:pointer;padding:2px 4px;}',
      '.ga-composer{display:flex;gap:8px;align-items:flex-end;padding:10px 12px 12px;border-top:1px solid var(--ga-line);background:#fff;flex-shrink:0;}',
      '.ga-input{flex:1;min-width:0;resize:none;border:1.5px solid #dccdd5;border-radius:14px;padding:10px 12px;font-size:14px;line-height:1.4;font-family:inherit;color:#26232b;max-height:120px;min-height:42px;box-sizing:border-box;background:#fff;}',
      '.ga-input:focus{outline:none;border-color:var(--ga-plum);box-shadow:0 0 0 3px rgba(103,49,71,.12);}',
      '.ga-mic{width:42px;height:42px;border-radius:12px;border:1px solid var(--ga-line);background:#fff;font-size:17px;cursor:pointer;flex-shrink:0;}',
      '.ga-mic.ga-rec{background:#fdecee;border-color:#e6a1a9;}',
      '.ga-submit{height:42px;border-radius:12px;border:none;background:var(--ga-plum);color:#fff;font-weight:700;font-size:14px;padding:0 16px;cursor:pointer;flex-shrink:0;}',
      '.ga-submit:hover:not(:disabled){background:var(--ga-plum-d);}',
      '.ga-submit:disabled{opacity:.55;cursor:default;}',
      '.ga-hint{font-size:12px;color:#6c5560;padding:6px 14px;background:#fff;border-top:1px solid var(--ga-line);}',
      '.ga-hint[hidden]{display:none;}',
      /* welcome */
      '.ga-welcome{margin:auto 0;text-align:center;padding:12px 6px;color:#5b5361;}',
      '.ga-welcome .ga-av{width:48px;height:48px;border-radius:15px;font-size:22px;margin:0 auto 10px;}',
      '.ga-welcome h3{font-size:16px;color:#26232b;margin:0 0 4px;}',
      '.ga-welcome p{font-size:13px;margin:0 0 12px;line-height:1.5;}',
      '.ga-ex{display:flex;flex-direction:column;gap:6px;align-items:stretch;}',
      '.ga-ex button{border:1px solid var(--ga-line);background:#fff;border-radius:10px;padding:8px 11px;font-size:13px;color:#3b3540;text-align:left;cursor:pointer;}',
      '.ga-ex button:hover{border-color:var(--ga-plum);color:var(--ga-plum);}',
      /* launcher */
      '.ga-fab{position:fixed;right:18px;bottom:18px;z-index:950;display:none;align-items:center;gap:8px;background:var(--ga-plum);color:#fff;border:none;border-radius:999px;padding:11px 16px 11px 12px;font-size:13.5px;font-weight:700;box-shadow:0 8px 22px rgba(70,20,45,.3);cursor:pointer;}',
      /* 2026-11-10: the launcher is on every page from the start (it is the only way in now), not only after a first message */
      'html.ga-on:not(.ga-open) .ga-fab{display:flex;}',
      /* room to scroll the last rows / buttons of a page clear of the launcher */
      'html.ga-on:not(.ga-pagemode) .page-content::after{content:"";display:block;height:68px;flex:0 0 68px;grid-column:1/-1;}',
      '.ga-help-btn{margin-left:4px;font-weight:700;}',
      /* the side panel is narrow: "New" is its ↺ icon there (the word stays in the full-page and full-screen views) so the title is not cut */
      'html:not(.ga-full) .ga-panel:not(.ga-page) #gaNew span{display:none;}',
      'html:not(.ga-full) .ga-panel:not(.ga-page) .ga-tools .ga-ib{width:29px;}',
      'html:not(.ga-full) .ga-panel:not(.ga-page) .ga-new{padding:5px 8px;}',
      '.ga-conv{width:auto;padding:0 10px;display:inline-flex;align-items:center;gap:6px;font-size:16px;color:#4a4250;}',
      '.ga-conv .ga-conv-t{font-size:12.5px;font-weight:700;}',
      '.ga-conv.on{background:var(--ga-plum);border-color:var(--ga-plum);color:#fff;}',
      '@media (max-width:767px){.ga-conv .ga-conv-t{display:none;}.ga-conv{width:42px;padding:0;justify-content:center;}.ga-help-btn{padding:5px 8px;margin-left:2px;}.ga-new{padding:5px 8px;}.ga-tools .ga-ib{width:29px;}.ga-title b{font-size:14px;}}',
      '.ga-fab .ga-fdot{width:8px;height:8px;border-radius:50%;background:#ffd36e;display:none;}',
      'html.ga-drafting .ga-fab .ga-fdot{display:block;}',
      '@media (max-width:767px){.ga-fab{right:12px;bottom:calc(12px + env(safe-area-inset-bottom));}.ga-new span{display:none;}.ga-panel .aa-choices{grid-template-columns:1fr;}.ga-panel .ga-review .aa-field{grid-template-columns:minmax(0,1fr) auto;}.ga-panel .ga-review .aa-field-k{grid-column:1 / -1;}.ga-submit{padding:0 13px;}.ga-full-btn{display:none !important;}}',
      'html.ga-pagemode #aaHost,html.ga-pagemode .ga-fab{display:none !important;}'
    ].join('\n');
    var s = document.createElement('style'); s.id = 'ga-styles'; s.textContent = css; document.head.appendChild(s);
  }

  /* ───────────────────────────── build ───────────────────────────── */
  function build() {
    var host = document.getElementById('gaPageHost');
    pageMode = !!host;
    var panel = document.createElement(pageMode ? 'section' : 'aside');
    panel.className = 'ga-panel' + (pageMode ? ' ga-page' : '');
    panel.id = 'gaPanel';
    panel.setAttribute('aria-label', 'Genius Assistant');
    panel.innerHTML =
      '<div class="ga-head">'
      + '<div class="ga-av" aria-hidden="true">✦</div>'
      + '<div class="ga-title"><b>Genius Assistant</b><span class="ga-status" id="gaStatus" role="status" aria-live="polite">Ready</span></div>'
      + '<div class="ga-tools">'
      + '<button type="button" class="ga-new" id="gaNew" title="Start a new conversation (nothing unsaved is kept)" aria-label="New conversation">↺ <span>New</span></button>'
      + '<button type="button" class="ga-new ga-help-btn" id="gaHelp" title="Help — what you can ask and how it works" aria-label="Help">Help</button>'
      + (pageMode ? '' : '<button type="button" class="ga-ib ga-full-btn" id="gaFull" title="Full screen" aria-label="Full screen" aria-pressed="false">'
                         + '<span class="ga-i-enter" aria-hidden="true">⤢</span><span class="ga-i-exit" aria-hidden="true">⤡</span></button>')
      + '<button type="button" class="ga-ib" id="gaMenuBtn" title="More" aria-label="More options" aria-haspopup="true" aria-expanded="false">⋯</button>'
      + (pageMode ? '' : '<button type="button" class="ga-ib" id="gaMin" title="Minimize" aria-label="Minimize the assistant">–</button>')
      + (pageMode ? '' : '<button type="button" class="ga-ib" id="gaClose" title="Close (Esc)" aria-label="Close the assistant">✕</button>')
      + '</div>'
      + '<div class="ga-menu" id="gaMenu" role="menu" hidden>'
      + '<button type="button" role="menuitem" id="gaMGuide">📖 Guide — all commands</button>'
      + '<button type="button" role="menuitem" id="gaMShare" hidden>🔐 AI data sharing</button>'
      + (pageMode ? '' : '<a role="menuitem" id="gaMPage" href="/ai-assistant">↗ Open as a page</a>')
      + '</div>'
      + '</div>'
      + '<div class="ga-log" id="gaLog" role="log" aria-live="polite" aria-label="Conversation with Genius Assistant" tabindex="0"></div>'
      + '<button type="button" class="ga-pill" id="gaPill" hidden>↓ New message</button>'
      + '<div class="ga-draft" id="gaDraft" hidden><span class="ga-dot" aria-hidden="true"></span><span id="gaDraftText">Draft — not saved yet</span><button type="button" id="gaDraftCancel">Cancel</button></div>'
      + '<div class="ga-hint" id="gaHint" role="status" hidden></div>'
      + '<form class="ga-composer" id="gaForm" autocomplete="off">'
      + '<textarea class="ga-input" id="gaInput" rows="1" placeholder="Ask anything…" aria-label="Message to Genius Assistant"></textarea>'
      + '<button type="button" class="ga-mic" id="gaMic" title="Speak your request" aria-label="Speak your request">🎤</button>'
      + '<button type="button" class="ga-mic ga-conv" id="gaConv" title="Converse mode — talk back and forth; replies are spoken" aria-label="Converse mode" aria-pressed="false">'
      + '<span aria-hidden="true">🗣</span><span class="ga-conv-t">Converse</span></button>'
      + '<button type="submit" class="ga-submit" id="gaSubmit">Submit</button>'
      + '</form>';
    if (pageMode) { host.appendChild(panel); document.documentElement.classList.add('ga-pagemode', 'ga-open'); }
    else {
      var bd = document.createElement('div'); bd.className = 'ga-backdrop'; bd.id = 'gaBackdrop';
      document.body.appendChild(bd); document.body.appendChild(panel);
      var fab = document.createElement('button'); fab.type = 'button'; fab.className = 'ga-fab'; fab.id = 'gaFab';
      fab.setAttribute('aria-label', 'Open Genius Assistant');
      fab.innerHTML = '<span class="ga-av" style="width:24px;height:24px;font-size:12px;border-radius:8px;" aria-hidden="true">✦</span>Genius<span class="ga-fdot" aria-hidden="true"></span>';
      document.body.appendChild(fab);
      bd.addEventListener('click', close);
      fab.addEventListener('click', function () { open(true); });
    }
    ['gaLog', 'gaInput', 'gaSubmit', 'gaMic', 'gaForm', 'gaStatus', 'gaDraft', 'gaDraftText', 'gaHint', 'gaPill', 'gaMenu', 'gaMenuBtn'].forEach(function (id) { el[id] = document.getElementById(id); });
    el.panel = panel;
    wire();
  }

  function wire() {
    el.gaForm.addEventListener('submit', function (e) { e.preventDefault(); submit(); });
    el.gaInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); submit(); }
    });
    el.gaInput.addEventListener('input', autosize);
    el.gaMic.addEventListener('click', function () {
      if (!api || !api.setMode) return;
      var m = api.mode ? api.mode() : 'type';
      // While listening the mic is a Stop button (the engine's own button does the stopping)
      api.setMode(m === 'converse' ? 'converse' : 'voice');
      setTimeout(syncMode, 60);
    });
    document.getElementById('gaNew').addEventListener('click', newConversation);
    var cl = document.getElementById('gaClose'); if (cl) cl.addEventListener('click', close);
    var mn = document.getElementById('gaMin'); if (mn) mn.addEventListener('click', minimize);
    var fb = document.getElementById('gaFull'); if (fb) fb.addEventListener('click', function () { setFull(!st.full); });
    el.gaPill.addEventListener('click', function () { scrollEnd(true); });
    el.gaLog.addEventListener('scroll', function () { if (nearEnd()) el.gaPill.hidden = true; });
    document.getElementById('gaDraftCancel').addEventListener('click', cancelDraft);
    // menu
    el.gaMenuBtn.addEventListener('click', function (e) { e.stopPropagation(); toggleMenu(); });
    document.addEventListener('click', function (e) { if (!el.gaMenu.hidden && !el.gaMenu.contains(e.target)) toggleMenu(false); });
    document.getElementById('gaHelp').addEventListener('click', function () { toggleMenu(false); if (api && api.showHelp) api.showHelp(); });
    document.getElementById('gaMGuide').addEventListener('click', function () {
      toggleMenu(false);
      var g = document.getElementById('aaGuideBtn'); if (!g) return;
      g.click();   // opens in a window over the panel; choosing a command sends it into this conversation
    });
    // Converse mode on / off — the SAME engine switch the old bar's 🗣 button used (one voice implementation);
    // the transcript, a draft and anything typed stay as they are.
    document.getElementById('gaConv').addEventListener('click', function () {
      toggleMenu(false);
      if (!api || !api.setMode) return;
      api.setMode(api.mode && api.mode() === 'converse' ? 'type' : 'converse');
      setTimeout(syncMode, 50);
    });
    var share = document.getElementById('gaMShare');
    if (document.getElementById('aaShareBtn')) {
      share.hidden = false;
      share.addEventListener('click', function () { toggleMenu(false); var b = document.getElementById('aaShareBtn'); if (b) b.click(); });
    }
    // An "Open" page card on a phone leaves the sheet closed on the next page (the user wants to see that page).
    el.gaLog.addEventListener('click', function (e) {
      var b = e.target.closest && e.target.closest('.aa-open[data-url]');
      if (b && !pageMode && !isDock()) { st.open = false; save(); }
      var c = e.target.closest && e.target.closest('.aa-choice');
      if (c && !c.disabled) c.classList.add('ga-sel');
    }, true);
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape' || pageMode || !document.documentElement.classList.contains('ga-open')) return;
      if (document.querySelector('.aa-modal-ov')) return;          // the help window closes first
      if (!el.gaMenu.hidden) { toggleMenu(false); return; }
      if (st.full) { setFull(false); return; }                       // Esc leaves full screen before it closes
      close();
    });
    window.addEventListener('resize', layout);
    // Phone keyboard: keep the message box above it (iOS does not shrink 100dvh for the keyboard).
    if (window.visualViewport) {
      var fit = function () {
        if (pageMode || !isPhone() || !document.documentElement.classList.contains('ga-open')) { el.panel.style.height = ''; el.panel.style.top = ''; return; }
        var top = safeTop();
        el.panel.style.height = Math.max(0, window.visualViewport.height - top) + 'px';
        el.panel.style.top = (window.visualViewport.offsetTop + top) + 'px';
        scrollEnd(false);
      };
      window.visualViewport.addEventListener('resize', fit);
      window.visualViewport.addEventListener('scroll', fit);
    }
    // The engine's mode buttons (🎤 / 🗣) live in the search bar; keep the panel's mic and menu in step with them.
    ['aaMode_type', 'aaMode_voice', 'aaMode_converse'].forEach(function (id) {
      var b = document.getElementById(id);
      if (b && window.MutationObserver) new MutationObserver(syncMode).observe(b, { attributes: true, attributeFilter: ['class'] });
    });
    var hint = document.getElementById('aaHint');
    if (hint && window.MutationObserver) new MutationObserver(syncHint).observe(hint, { childList: true, characterData: true, subtree: true });
  }

  function toggleMenu(on) {
    var show = on == null ? el.gaMenu.hidden : !!on;
    el.gaMenu.hidden = !show; el.gaMenuBtn.setAttribute('aria-expanded', show ? 'true' : 'false');
  }
  function syncMode() {
    var m = api && api.mode ? api.mode() : 'type';
    // The engine's 🎤 / 🗣 button carries the live state: .rec = listening, .aa-proc = working on the recording.
    var vb = document.getElementById('aaMode_' + m), rec = !!(vb && vb.classList.contains('rec')), proc = !!(vb && vb.classList.contains('aa-proc'));
    var live = (m === 'voice' || m === 'converse') && (rec || proc);
    el.gaMic.classList.toggle('ga-rec', live);
    el.gaMic.textContent = live ? '\u23f9' : '\ud83c\udfa4';
    el.gaMic.setAttribute('aria-label', live ? 'Stop listening' : 'Speak your request');
    el.gaMic.title = live ? 'Stop listening' : 'Speak your request';
    el.gaMic.setAttribute('aria-pressed', live ? 'true' : 'false');
    var cb = document.getElementById('gaConv');
    if (cb) {
      var on = m === 'converse';
      cb.classList.toggle('on', on); cb.setAttribute('aria-pressed', on ? 'true' : 'false');
      cb.title = on ? 'Converse mode is on — click to go back to typing' : 'Converse mode — talk back and forth; replies are spoken';
    }
    if (!busyOn) status(rec ? 'listening' : proc ? 'thinking' : 'ready');
  }
  function syncHint() {
    var h = document.getElementById('aaHint'); var t = h ? (h.textContent || '').trim() : '';
    if (/^Type a question|^Ready|^Tip:/i.test(t)) t = '';                 // the bar's idle guidance is not news
    // the engine's short messages ("Still working…", "Listening…", voice errors) are shown under the transcript
    el.gaHint.textContent = t; el.gaHint.hidden = !t || !document.documentElement.classList.contains('ga-open');
  }

  /* ───────────────────────────── layout / open / close ───────────────────────────── */
  /** The status-bar height the app draws under (env(safe-area-inset-top)); 0 in a browser. */
  function safeTop() {
    try {
      var probe = document.getElementById('gaSafeProbe');
      if (!probe) {
        probe = document.createElement('div'); probe.id = 'gaSafeProbe'; probe.setAttribute('aria-hidden', 'true');
        probe.style.cssText = 'position:fixed;top:0;left:0;width:0;visibility:hidden;pointer-events:none;height:env(safe-area-inset-top,0px);';
        document.body.appendChild(probe);
      }
      return probe.offsetHeight || 0;
    } catch (e) { return 0; }
  }
  /** Full screen on / off (2026-11-10). Phones always use the whole screen, so there it changes nothing visible. */
  function setFull(on) {
    if (pageMode) return;
    st.full = !!on;
    document.documentElement.classList.toggle('ga-full', st.full && document.documentElement.classList.contains('ga-open'));
    var b = document.getElementById('gaFull');
    if (b) {
      b.setAttribute('aria-pressed', String(st.full));
      b.title = st.full ? 'Exit full screen (Esc)' : 'Full screen';
      b.setAttribute('aria-label', st.full ? 'Exit full screen' : 'Full screen');
    }
    layout(); save(); scrollEnd(false);
  }
  /** Minimize: the panel folds into the "Genius" launcher (kept for this tab); the conversation stays. */
  function minimize() {
    if (pageMode) return;
    st.min = true;
    document.documentElement.classList.add('ga-min');
    close();
  }
  function layout() {
    var h = document.documentElement;
    if (pageMode) return;
    var on = h.classList.contains('ga-open');
    h.classList.toggle('ga-full', on && st.full);
    h.classList.toggle('ga-docked', on && isDock());
    h.classList.toggle('ga-overlay', on && !isDock() && !isPhone());
    h.classList.toggle('ga-sheet', on && isPhone());
  }
  function open(focus) {
    document.documentElement.classList.add('ga-open');
    document.documentElement.classList.remove('ga-min');
    st.open = true; st.min = false; layout(); save(); syncHint();
    if (focus) setTimeout(function () { try { el.gaInput.focus(); } catch (e) {} }, 30);
    scrollEnd(true);
  }
  function close() {
    if (pageMode) return;
    document.documentElement.classList.remove('ga-open');
    st.open = false; layout(); save(); toggleMenu(false);
    try { if (document.activeElement && el.panel.contains(document.activeElement)) document.activeElement.blur(); } catch (e) {}
    var b = document.getElementById('gaFab'); try { if (b && !isPhone()) b.focus(); } catch (e) {}   // back to the launcher (the old bar is gone)
  }
  function status(kind) {
    var s = el.gaStatus; if (!s) return;
    s.className = 'ga-status' + (kind === 'thinking' ? ' ga-thinking' : kind === 'listening' ? ' ga-listening' : '');
    s.textContent = kind === 'thinking' ? 'Thinking…' : kind === 'listening' ? 'Listening…' : 'Ready';
  }

  /* ───────────────────────────── transcript ───────────────────────────── */
  function nearEnd() { var l = el.gaLog; return l.scrollHeight - l.scrollTop - l.clientHeight < 90; }
  function scrollEnd(force) {
    var l = el.gaLog; if (!l) return;
    if (force || nearEnd()) { l.scrollTop = l.scrollHeight; el.gaPill.hidden = true; }
    else el.gaPill.hidden = false;
  }
  function push(node, follow) {
    var w = el.gaLog.querySelector('.ga-welcome'); if (w && !node.classList.contains('ga-note')) w.remove();
    var stick = follow || nearEnd();
    el.gaLog.appendChild(node);
    st.nodes.push(node);
    while (st.nodes.length > MAX_ITEMS) { var old = st.nodes.shift(); if (old && old.parentNode) old.parentNode.removeChild(old); }
    document.documentElement.classList.add('ga-has');
    scrollEnd(stick);
    save();
  }
  function userBubble(text) {
    var n = document.createElement('div'); n.className = 'ga-msg ga-user';
    n.innerHTML = '<div class="ga-bub">' + esc(text) + '</div><div class="ga-ts">' + esc(timeOf(Date.now())) + '</div>';
    return n;
  }
  function noteBubble(text) {
    var n = document.createElement('div'); n.className = 'ga-msg ga-note';
    n.innerHTML = '<p>' + esc(text) + '</p>';
    return n;
  }
  function aiShell(cls) {
    var n = document.createElement('div'); n.className = 'ga-msg ga-ai' + (cls ? ' ' + cls : '');
    n.innerHTML = '<div class="ga-mini" aria-hidden="true">✦</div><div class="ga-body"></div>';
    return n;
  }
  function welcome() {
    var real = st.nodes.some(function (n) { return !n.classList.contains('ga-note'); });
    if (real || el.gaLog.querySelector('.ga-welcome')) return;
    var w = document.createElement('div'); w.className = 'ga-welcome';
    var ex = ['How much income did we receive last month?', 'Who has a birthday this week?', 'Create a meeting', 'Record an income of $500 for Sunday offerings.', 'Go to Families'];
    w.innerHTML = '<div class="ga-av" aria-hidden="true">✦</div><h3>Hi! I’m Genius, your church assistant.</h3>'
      + '<p>Ask a question, find a page, or tell me what to create — type it, or tap 🎤 and say it. I’ll ask for anything I still need, and nothing is saved until you confirm.</p>'
      + '<div class="ga-ex">' + ex.map(function (x) { return '<button type="button">' + esc(x) + '</button>'; }).join('') + '</div>';
    el.gaLog.insertBefore(w, el.gaLog.firstChild);
    w.querySelectorAll('button').forEach(function (b) { b.addEventListener('click', function () { send(b.textContent); }); });
  }

  /* Disable the buttons of earlier conversation cards — only the newest card can act (the server agrees: it
     withdraws an older token when a newer card is drawn). */
  function retireOld(except) {
    st.nodes.forEach(function (n) {
      if (n === except || !n.classList || !n.classList.contains('ga-ai')) return;
      var any = false;
      n.querySelectorAll('.aa-choice, .aa-edit, .aa-confirm, .aa-cancel, .ga-filter, .aa-send, .aa-send-cancel, .aa-files, .aa-file-x').forEach(function (b) {
        if (!b.disabled) { b.disabled = true; b.setAttribute('aria-disabled', 'true'); any = true; }
      });
      if (any) {
        n.classList.add('ga-old');
        var note = n.querySelector('.aa-actions-note'); if (note) note.textContent = 'Replaced by a newer card.';
      }
    });
  }

  /* ───────────────────────────── answers ───────────────────────────── */
  function kindOf(a) {
    var d = (a && a.data) || {}, i = String(d.intent || a.intent || '');
    if (d.created || d.executed || /\.(done|created|sent)$/.test(i)) return 'ga-ok';
    if (/cancel/.test(i)) return 'ga-muted';
    if (i === 'manage.redirect') return 'ga-info';
    if (d.denied || /\.(denied|refused|unavailable|expired|failed|error)$/.test(i)) return 'ga-warn';
    if (Array.isArray(d.choices) && d.choices.length && !(d.confirm && d.confirm.token)) return 'ga-question';
    if (/\.ask$/.test(i)) return 'ga-question';
    return '';
  }
  function draftLabel(d) {
    var i = String(d.intent || '');
    if (/^meeting\./.test(i)) return 'Meeting';
    if (/^email\./.test(i)) return 'Email';
    if (d.kind) return cap(String(d.kind).replace(/[_-]+/g, ' '));
    if (d.confirm && d.confirm.label) return String(d.confirm.label).replace(/^(save|create)\s+/i, '').replace(/^./, function (c) { return c.toUpperCase(); });
    return 'Action';
  }
  function reviewTitle(d) {
    var l = d.confirm && d.confirm.label ? String(d.confirm.label) : 'Details';
    var t = l.replace(/^(save|create)\s+/i, '');
    return (/^meeting/i.test(t) ? '📅 ' : /income|offering|donation/i.test(t) ? '💵 ' : /expense/i.test(t) ? '🧾 ' : '📝 ') + cap(t) + ' details';
  }

  function answer(cardEl, a /*, question */) {
    stopTyping();
    var d = (a && a.data) || {};
    var kind = kindOf(a);
    var n = aiShell(kind);
    var body = n.querySelector('.ga-body');
    // Unwrap the engine's results panel: no header, no "Help Center" footer — the bubble carries the answer.
    var src = cardEl;
    if (src && src.classList && src.classList.contains('ai-results-panel')) {
      src.querySelectorAll('.ai-results-hdr').forEach(function (h) { h.remove(); });
      var last = src.lastElementChild;
      if (last && /Need more detail\?/.test(last.textContent || '')) last.remove();
    }
    var bub = document.createElement('div'); bub.className = 'ga-bub';
    var ans = src ? src.querySelector('.ai-answer') : null;
    if (kind === 'ga-question') {
      var q = document.createElement('div'); q.className = 'ga-qlabel'; q.textContent = 'Question'; bub.appendChild(q);
    }
    if (d.question && kind === 'ga-question') {
      // S1: the short question above the choices; the full answer (with the names, for voice) stays for screen readers
      var qq = document.createElement('div'); qq.className = 'ai-answer'; qq.textContent = d.question; bub.appendChild(qq);
      if (ans) ans.remove();
    } else if (ans) bub.appendChild(ans);
    if (bub.textContent.trim()) body.appendChild(bub);
    var extra = document.createElement('div'); extra.className = 'ga-extra';
    if (src) { while (src.firstChild) extra.appendChild(src.firstChild); }
    if (extra.childNodes.length) body.appendChild(extra);

    // Review card: the rows + Create / Save + Cancel in one bordered card with a Draft badge.
    var fields = extra.querySelector('.aa-fields'), acts = extra.querySelector('.aa-actions');
    if (d.confirm && d.confirm.token && acts && acts.querySelector('.aa-confirm')) {
      var card = document.createElement('div'); card.className = 'ga-review';
      card.innerHTML = '<div class="ga-review-h"><b>' + esc(reviewTitle(d)) + '</b><span class="ga-badge">Draft · not saved</span></div>';
      if (fields) card.appendChild(fields);
      else { var sm = extra.querySelector('.aa-summary'); if (sm) card.appendChild(sm); }
      card.appendChild(acts);
      extra.appendChild(card);
      watchReview(card);
    } else if (d.email && d.email.token && acts && acts.querySelector('.aa-send')) {
      // 2026-11-11: the e-mail review card — recipients / subject, attachments, Send email + Cancel.
      var ec = document.createElement('div'); ec.className = 'ga-review ga-review-email';
      ec.innerHTML = '<div class="ga-review-h"><b>\u2709\ufe0f Email to send</b><span class="ga-badge">Draft \u00b7 not sent</span></div>';
      if (fields) ec.appendChild(fields);
      var att = extra.querySelector('.aa-attach'); if (att) ec.appendChild(att);
      ec.appendChild(acts);
      extra.appendChild(ec);
      watchReview(ec);
    }
    // Choices: selection cards (2 columns; a filter above 8 options); 3 or fewer short ones stay as buttons.
    var ch = extra.querySelector('.aa-choices');
    if (ch) {
      var opts = ch.querySelectorAll('.aa-choice');
      var short = opts.length <= 3 && Array.prototype.every.call(opts, function (o) { return (o.textContent || '').length <= 28; });
      if (short) ch.classList.add('ga-few');
      if (opts.length > 8) {
        var f = document.createElement('input'); f.type = 'search'; f.className = 'ga-filter';
        f.placeholder = 'Filter ' + opts.length + ' options…'; f.setAttribute('aria-label', 'Filter the options');
        f.addEventListener('input', function () {
          var t = f.value.trim().toLowerCase();
          opts.forEach(function (o) { o.hidden = !!t && (o.textContent || '').toLowerCase().indexOf(t) < 0; });
        });
        f.addEventListener('keydown', function (e) {
          if (e.key !== 'Enter') return; e.preventDefault();
          var vis = Array.prototype.filter.call(opts, function (o) { return !o.hidden && !o.disabled; });
          if (vis.length === 1) vis[0].click();
        });
        ch.parentNode.insertBefore(f, ch);
      }
    }
    if (conv(d.intent) || extra.querySelector('.aa-choice, .aa-confirm, .aa-send')) retireOld(n);
    push(n, true);
    if (api && api.wireCard) { try { api.wireCard(n); } catch (e) {} }
    updateDraft(d);
    if (!document.documentElement.classList.contains('ga-open')) open(false);
    save();
  }

  /* The Create / Save button's progress (the engine relabels it "Creating…" / "Working…" and disables the row):
     the card's badge follows — Draft → Saving → Saved / Cancelled. */
  function watchReview(card) {
    if (!window.MutationObserver) return;
    var mo = new MutationObserver(function () {
      if (card.classList.contains('ga-saving') || card.classList.contains('ga-saved') || card.classList.contains('ga-cancelled')) return;   // set once (no loop)
      var cf = card.querySelector('.aa-confirm, .aa-send');
      if (cf && cf.disabled && /…|\.\.\./.test(cf.textContent || '') && !card.classList.contains('ga-saved')) {
        card.classList.add('ga-saving'); var b = card.querySelector('.ga-badge'); if (b) b.textContent = card.classList.contains('ga-review-email') ? 'Sending…' : 'Saving…';
      }
    });
    mo.observe(card, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['disabled'] });
  }
  function settleReviews(state) {
    el.gaLog.querySelectorAll('.ga-review.ga-saving, .ga-review:not(.ga-saved):not(.ga-cancelled)').forEach(function (c) {
      var cf = c.querySelector('.aa-confirm, .aa-send'), mail = c.classList.contains('ga-review-email');
      if (state === 'saved' && c.classList.contains('ga-saving')) {
        c.classList.remove('ga-saving'); c.classList.add('ga-saved'); var b = c.querySelector('.ga-badge'); if (b) b.textContent = mail ? '✓ Sent' : '✓ Saved';
        if (cf) cf.textContent = mail ? 'Sent' : 'Saved';
      } else if (state === 'cancelled' && (!cf || !cf.disabled || c.classList.contains('ga-saving'))) {
        c.classList.remove('ga-saving'); c.classList.add('ga-cancelled'); var b2 = c.querySelector('.ga-badge'); if (b2) b2.textContent = mail ? 'Cancelled · not sent' : 'Cancelled · not saved';
        c.querySelectorAll('button').forEach(function (x) { x.disabled = true; });
      } else if (state === 'failed' && c.classList.contains('ga-saving')) {
        c.classList.remove('ga-saving'); var b3 = c.querySelector('.ga-badge'); if (b3) b3.textContent = mail ? 'Not sent' : 'Not saved';
      }
    });
  }

  function updateDraft(d) {
    var i = String((d && d.intent) || '');
    if (pendingOf(d)) {
      var fin = conv(i) ? outcomeOf(d) : null;          // saved / cancelled, and the conversation carries on
      if (fin) settleReviews(fin);
      st.draft = draftLabel(d);
    } else if (conv(i) && !(d.pending === true)) {
      if (/\.(done|created|sent)$/.test(i) || d.created || d.executed) settleReviews('saved');
      else if (/cancel/.test(i)) settleReviews('cancelled');
      else if (/\.(failed|error|expired|refused)$/.test(i)) settleReviews('failed');
      if (i !== 'manage.redirect') st.draft = null;
    }
    renderDraft();
  }
  function renderDraft() {
    var on = !!st.draft;
    el.gaDraft.hidden = !on;
    if (on) el.gaDraftText.textContent = 'Draft · ' + st.draft + (st.draft === 'Email' ? ' — not sent yet' : ' — not saved yet');
    document.documentElement.classList.toggle('ga-drafting', on);
  }
  function cancelDraft() {
    // the newest card's own Cancel (it rejects the one-time token), else tell the open conversation "cancel"
    var btns = el.gaLog.querySelectorAll('.aa-cancel:not([disabled])');
    var b = btns.length ? btns[btns.length - 1] : null;
    if (b) { b.click(); return; }
    send('cancel');
  }

  /* ───────────────────────────── engine hooks ───────────────────────────── */
  var typingNode = null;
  function startTyping() {
    if (typingNode) return;
    typingNode = aiShell('ga-typing');
    typingNode.querySelector('.ga-body').innerHTML = '<div class="ga-bub" aria-label="Genius is working"><i></i><i></i><i></i></div>';
    var stick = nearEnd();
    el.gaLog.appendChild(typingNode); scrollEnd(stick);
  }
  function stopTyping() { if (typingNode && typingNode.parentNode) typingNode.parentNode.removeChild(typingNode); typingNode = null; }
  function setBusy(on) {
    busyOn = !!on;
    el.gaSubmit.disabled = busyOn;
    if (busyOn) { startTyping(); status('thinking'); }
    else { stopTyping(); syncMode(); }
  }
  var UI = {
    question: function (text) {
      var fromBar = document.activeElement && document.activeElement.id === 'aiSearchInput';
      if (!document.documentElement.classList.contains('ga-open')) open(fromBar && !isPhone());
      else if (fromBar && !isPhone()) setTimeout(function () { try { el.gaInput.focus(); } catch (e) {} }, 30);
      push(userBubble(text), true);
    },
    answer: answer,
    busy: setBusy,
    notice: function (text) {
      stopTyping();
      if (/nothing (was|is) (saved|done)|cancel/i.test(String(text))) { st.draft = null; renderDraft(); settleReviews('cancelled'); }
      push(noteBubble(text), true);
    },
    prefill: function (text) {
      if (!document.documentElement.classList.contains('ga-open')) open(false);
      el.gaInput.value = text; autosize();
      try { el.gaInput.focus(); el.gaInput.setSelectionRange(text.length, text.length); } catch (e) {}
    }
  };

  /* Answers some pages still draw into the classic results box (the Home page's own search, the help-search
     fallback) are moved into the transcript as they appear, so nothing is shown outside the chat. */
  function watchClassicBox() {
    var wrap = document.getElementById('aiResultsWrap');
    if (!wrap || !window.MutationObserver) return;
    var move = function () {
      if (!wrap.firstElementChild) return;
      var only = wrap.children.length === 1 && wrap.firstElementChild.classList.contains('aa-toast');
      if (only) { var t = wrap.firstElementChild.textContent; wrap.innerHTML = ''; wrap.style.display = 'none'; UI.notice(t); return; }
      var holder = document.createElement('div'); holder.className = 'ai-results-panel';
      while (wrap.firstChild) holder.appendChild(wrap.firstChild);
      wrap.style.display = 'none';
      setBusy(false);
      answer(holder, { intent: 'classic', data: {} });
    };
    wrapObserver = new MutationObserver(function () { setTimeout(move, 0); });
    wrapObserver.observe(wrap, { childList: true });
  }

  /* ───────────────────────────── sending ───────────────────────────── */
  function autosize() { var t = el.gaInput; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 120) + 'px'; }
  function send(text) {
    text = String(text || '').trim();
    if (!text || !api) return false;
    if (busyOn || (api.inFlight && api.inFlight())) { flash('Still working on your last message — one moment…'); return false; }
    api.ask(text);
    return true;
  }
  function submit() {
    var t = el.gaInput.value;
    if (send(t)) { el.gaInput.value = ''; autosize(); }
    try { el.gaInput.focus(); } catch (e) {}
  }
  function flash(msg) {
    el.gaHint.textContent = msg; el.gaHint.hidden = false;
    clearTimeout(flash._t); flash._t = setTimeout(function () { el.gaHint.hidden = true; }, 3500);
  }
  function newConversation() {
    st.nodes.forEach(function (n) { if (n.parentNode) n.parentNode.removeChild(n); });
    st.nodes = []; st.draft = null; renderDraft(); stopTyping();
    document.documentElement.classList.remove('ga-has');
    save();
    if (api && api.newConversation) api.newConversation();     // rejects an open token + tells the server "cancel"
    welcome();
    try { el.gaInput.focus(); } catch (e) {}
  }

  /* ───────────────────────────── restore ───────────────────────────── */
  function restore() {
    var who = api && api.user ? api.user() : '';
    var d = load();
    st.who = who;
    if (d && d.who && who && d.who !== who) { forget(); d = null; }   // another login's conversation is never shown
    if (d && d.items.length) {
      var tmp = document.createElement('div');
      d.items.forEach(function (h) {
        tmp.innerHTML = h; var n = tmp.firstElementChild; if (!n) return;
        n.classList.remove('ga-typing');
        if (n.classList.contains('ga-typing')) return;
        el.gaLog.appendChild(n); st.nodes.push(n);
      });
      st.draft = d.draft || null;
      // Only the newest assistant card may act after a reload; everything older is switched off.
      var lastAi = null;
      for (var i = st.nodes.length - 1; i >= 0; i--) { if (st.nodes[i].classList.contains('ga-ai')) { lastAi = st.nodes[i]; break; } }
      retireOld(lastAi);
      if (lastAi) {
        lastAi.querySelectorAll('.ga-review.ga-saving').forEach(function (c) { c.classList.remove('ga-saving'); });
        if (api && api.wireCard) { try { api.wireCard(lastAi); } catch (e) {} }
        lastAi.querySelectorAll('.ga-review').forEach(watchReview);
        var f = lastAi.querySelector('.ga-filter'), ch = lastAi.querySelector('.aa-choices');
        if (f && ch) f.addEventListener('input', function () {
          var t = f.value.trim().toLowerCase();
          ch.querySelectorAll('.aa-choice').forEach(function (o) { o.hidden = !!t && (o.textContent || '').toLowerCase().indexOf(t) < 0; });
        });
      }
      if (st.nodes.length) document.documentElement.classList.add('ga-has');
      renderDraft();
      if (!pageMode && d.full) setFull(true);
      if (!pageMode && d.min && !d.open) { st.min = true; document.documentElement.classList.add('ga-min'); }
      if (d.open || pageMode) open(false);
    } else if (pageMode) open(false);
    welcome();
    if (pageMode) setTimeout(function () { try { el.gaInput.focus(); } catch (e) {} }, 60);
  }

  /* ───────────────────────────── attach (called by the engine) ───────────────────────────── */
  function attach(engine) {
    if (api) return;
    api = engine;
    injectStyles();
    build();
    window.CGP_ASSISTANT_UI = UI;
    document.documentElement.classList.add('ga-on');
    watchClassicBox();
    restore();
    layout();
    applyModes();
    syncMode();
  }
  /* 2026-11-11: the plan decides whether 🎤 Voice and 🗣 Converse are offered (Standard: text only). */
  function applyModes() {
    var m = api && api.modes ? api.modes() : { voice: true, converse: true };
    [['gaMic', m.voice !== false], ['gaConv', m.converse !== false]].forEach(function (x) {
      var b = document.getElementById(x[0]); if (!b) return;
      b.hidden = !x[1]; b.disabled = !x[1];
      if (!x[1]) { b.style.display = 'none'; b.setAttribute('aria-hidden', 'true'); }
    });
  }

  window.CGP_CHAT = { attach: attach, open: open, close: close, send: send, newConversation: newConversation,
    _state: function () { return { open: document.documentElement.classList.contains('ga-open'), draft: st.draft, items: st.nodes.length, pageMode: pageMode }; } };
})();
