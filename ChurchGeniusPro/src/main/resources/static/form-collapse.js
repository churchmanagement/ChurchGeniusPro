/*
 * form-collapse.js — minimise / expand an entry form (Income, Expense; 2026-11-03).
 *
 * CGP_formCollapse.init({ card: 'formCard', head: '.card-header', key: 'income', label: 'Income form' })
 *   card  id of the form card
 *   head  selector (inside the card) of the row that stays visible when minimised
 *   key   remembers the choice per page in this browser (localStorage, best effort)
 *
 * Display only: minimising hides the rows below the header with CSS. Nothing is
 * removed from the page, so typed values, validation, Save and Voice keep working.
 * Safety rules:
 *   - while a Voice / Converse session runs (its button carries .recording) the form
 *     cannot be minimised, and it is expanded if it was;
 *   - pressing Voice / Converse, editing a row, or anything that scrolls the form into
 *     view (Scan Check) expands it first;
 *   - a Save in progress is unaffected (the form stays in the page either way).
 */
(function () {
  'use strict';
  if (window.CGP_formCollapse) return;

  var STYLE =
    '.cgp-fc-collapsed > :not(.cgp-fc-head){display:none !important;}' +
    '.cgp-fc-collapsed > .cgp-fc-head{border-bottom-color:transparent !important;}' +
    '.cgp-fc-head > :first-child{margin-right:auto;}' +
    '.cgp-fc-head > .cgp-fc-btn{order:99;}' +
    '.cgp-fc-btn{display:inline-flex;align-items:center;gap:6px;flex:none;height:30px;padding:0 10px;margin-left:8px;' +
      'border:1px solid #e0d4da;border-radius:8px;background:#fff;color:#673147;font:600 12px/1 inherit;cursor:pointer;}' +
    '.cgp-fc-btn:hover:not(:disabled){background:#f7eef2;}' +
    '.cgp-fc-btn:focus-visible{outline:2px solid #673147;outline-offset:2px;}' +
    '.cgp-fc-btn:disabled{opacity:.5;cursor:not-allowed;}' +
    '.cgp-fc-btn svg{width:14px;height:14px;transition:transform .2s ease;}' +
    '.cgp-fc-collapsed .cgp-fc-btn svg{transform:rotate(-90deg);}' +
    '.cgp-fc-card:not(.cgp-fc-collapsed) > :not(.cgp-fc-head){animation:cgpFcIn .18s ease;}' +
    '@keyframes cgpFcIn{from{opacity:0;transform:translateY(-4px);}to{opacity:1;transform:none;}}' +
    '@media (prefers-reduced-motion:reduce){.cgp-fc-btn svg{transition:none;}.cgp-fc-card > *{animation:none !important;}}' +
    '@media (max-width:560px){.cgp-fc-btn .cgp-fc-txt{display:none;}.cgp-fc-btn{padding:0 8px;}}';

  var CHEVRON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>';
  var forms = {};

  function store(key, v) {
    try { if (v === undefined) return localStorage.getItem('cgpFormCollapsed:' + key); localStorage.setItem('cgpFormCollapsed:' + key, v); }
    catch (e) { return null; }
  }

  function voiceActive() {
    var m = document.getElementById('voiceMicBtn'), c = document.getElementById('voiceConverseBtn');
    return !!((m && m.classList.contains('recording')) || (c && c.classList.contains('recording')));
  }

  function paint(f) {
    var collapsed = f.card.classList.contains('cgp-fc-collapsed');
    var busy = voiceActive();
    f.btn.setAttribute('aria-expanded', String(!collapsed));
    f.btn.querySelector('.cgp-fc-txt').textContent = collapsed ? 'Expand' : 'Minimize';
    f.btn.title = collapsed ? 'Expand the ' + f.label
                : busy ? 'Finish or stop the voice entry before minimizing'
                : 'Minimize the ' + f.label;
    f.btn.setAttribute('aria-label', collapsed ? 'Expand ' + f.label : 'Minimize ' + f.label);
    f.btn.disabled = !collapsed && busy;
  }

  function set(f, collapse, remember) {
    if (collapse && voiceActive()) collapse = false;
    f.card.classList.toggle('cgp-fc-collapsed', !!collapse);
    if (remember) store(f.key, collapse ? '1' : '0');
    paint(f);
  }

  function init(opts) {
    var card = document.getElementById(opts.card);
    var head = card && card.querySelector(opts.head);
    if (!card || !head || forms[opts.card]) return;
    if (!document.getElementById('cgpFormCollapseCss')) {
      var st = document.createElement('style'); st.id = 'cgpFormCollapseCss'; st.textContent = STYLE;
      document.head.appendChild(st);
    }
    card.classList.add('cgp-fc-card');
    head.classList.add('cgp-fc-head');
    var btn = document.createElement('button');
    btn.type = 'button'; btn.className = 'cgp-fc-btn'; btn.id = opts.card + 'CollapseBtn';
    btn.setAttribute('aria-controls', opts.card);
    btn.innerHTML = CHEVRON + '<span class="cgp-fc-txt">Minimize</span>';
    head.appendChild(btn);
    var f = { card: card, btn: btn, key: opts.key || opts.card, label: opts.label || 'form' };
    forms[opts.card] = f;

    btn.addEventListener('click', function () {
      set(f, !card.classList.contains('cgp-fc-collapsed'), true);
    });

    // Anything that brings the form into view (Scan Check, voice) opens it first.
    var nativeScroll = card.scrollIntoView;
    card.scrollIntoView = function () { set(f, false, true); return nativeScroll.apply(card, arguments); };

    // Voice / Converse pressed → expand before the session starts guiding fields.
    document.addEventListener('click', function (e) {
      var t = e.target && e.target.closest ? e.target.closest('#voiceMicBtn, #voiceConverseBtn') : null;
      if (t && card.classList.contains('cgp-fc-collapsed')) set(f, false, true);
    }, true);

    // Keep the button's state in step with the voice buttons (added later by the voice script).
    var watched = {};
    function watch() {
      ['voiceMicBtn', 'voiceConverseBtn'].forEach(function (id) {
        var el = document.getElementById(id);
        if (el && !watched[id]) {
          watched[id] = true;
          new MutationObserver(function () {
            if (voiceActive() && card.classList.contains('cgp-fc-collapsed')) set(f, false, false);
            paint(f);
          }).observe(el, { attributes: true, attributeFilter: ['class'] });
        }
      });
    }
    watch();
    if (!watched.voiceMicBtn) {
      var tries = 0, iv = setInterval(function () { watch(); if (watched.voiceMicBtn || ++tries > 40) clearInterval(iv); }, 250);
    }

    set(f, store(f.key) === '1', false);
  }

  function expand(cardId) { var f = forms[cardId]; if (f) set(f, false, true); }

  window.CGP_formCollapse = { init: init, expand: expand };
})();
