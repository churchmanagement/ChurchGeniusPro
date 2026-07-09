/* ─────────────────────────────────────────────────────────────────────────────
 * voice-flags.js — per-church Voice feature gating (frontend).
 *
 * Loaded globally (via shell.js). Fetches the church's EFFECTIVE voice flags
 * from /api/voice/features and hides the corresponding buttons everywhere by
 * injecting a single <style> rule. Using CSS (not direct DOM removal) means
 * buttons injected later by ai-assistant.js / meeting-voice.js / income-voice.js
 * / expense-voice.js are still hidden — no timing/observer issues.
 *
 * The backend already applies the dependency rules (parent off → all off;
 * any of Type/Command/Converse off → Help + Debug off), so we just hide based
 * on the booleans we receive.
 * ──────────────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  // Map each voice feature to the selectors that must be hidden when it is OFF.
  var SELECTORS = {
    voiceType:    ['#aaMode_type', '#mvMode_type'],
    voiceCommand: ['#aaMode_voice', '#mvMode_voice', '#voiceMicBtn', '.btn-voice-mic'],
    converse:     ['#aaMode_converse', '#mvMode_converse', '#voiceConverseBtn', '.btn-voice-converse'],
    voiceHelp:    ['#voiceGuideBtn', '.btn-voice-guide', '#mvMode_guide', '#aaMode_help', '#aaMode_guide'],
    voiceDebug:   ['#voiceDebugBtn', '.btn-voice-debug', '#mvMode_debug', '#aaMode_debug']
  };

  function apply(flags) {
    window.CGP_VOICE = flags || {};
    var hide = [];
    Object.keys(SELECTORS).forEach(function (key) {
      if (flags[key] === false) hide = hide.concat(SELECTORS[key]);
    });

    var styleEl = document.getElementById('cgp-voice-flags');
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = 'cgp-voice-flags';
      (document.head || document.documentElement).appendChild(styleEl);
    }
    styleEl.textContent = hide.length
      ? hide.join(',') + '{display:none !important;}'
      : '';
  }

  function load() {
    fetch('/api/voice/features', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (flags) { if (flags) apply(flags); })
      .catch(function () { /* default to showing everything on any error */ });
  }

  // Allow other scripts to re-apply if they rebuild toolbars.
  window.CGP_applyVoiceFlags = function () { if (window.CGP_VOICE) apply(window.CGP_VOICE); };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load);
  } else {
    load();
  }
})();
