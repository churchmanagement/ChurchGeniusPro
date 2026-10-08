/* ============================================================================
 * voice-openai.js — OpenAI-powered, REAL-TIME voice capture.
 *
 * Continuously records the microphone and uses a Web Audio level meter to detect
 * pauses (silence). Each spoken phrase is cut at the pause, sent to
 * /api/voice/command (OpenAI transcription + structured extraction), and applied
 * to the form immediately — so fields populate one at a time as you speak,
 * without waiting for the session to end. Auto-stops after 1 minute; press the
 * button again for a new session.
 *
 * window.VoiceOpenAI:
 *   createRecorder(opts) -> { supported, start(), stop(), cancel(), toggle(),
 *                              isRecording(), isBusy() }
 *     stop()   finishes cleanly: the phrase in progress is flushed and sent.
 *     cancel() aborts: pending audio is discarded, in-flight requests are
 *              aborted, timers cleared and the mic released immediately, and
 *              no queued callback may touch the UI afterwards.
 *     opts callbacks: onState(recording), onStatus(msg,kind), onDebug(label,detail),
 *                     onTranscript(text), onStatusData(status), onResult(payload),
 *                     onDisabled(status), onPhase('listening'|'processing'|'idle'),
 *                     onCancel()
 *   getStatus()      -> Promise<usage status>   (voice/vision availability)
 *   getVoiceStatus() -> Promise<diagnostics>    (apiType, models, endpoint…)
 * ========================================================================== */
(function (root) {
  'use strict';

  function getStatus() {
    return fetch('/api/openai-usage/status').then(function (r) { return r.ok ? r.json() : {}; }).catch(function () { return {}; });
  }
  function getVoiceStatus() {
    return fetch('/api/voice/status').then(function (r) { return r.ok ? r.json() : {}; }).catch(function () { return {}; });
  }

  /* ── Floating "Listening…" indicator (shared, fixed, semi-transparent) ─────
     Stays pinned on screen while voice is active, shows Listening/Processing,
     and is click-through (pointer-events:none) so it never blocks the form. */
  var _ind = null, _indStyled = false;
  function ensureIndicator() {
    if (typeof document === 'undefined') return null;
    if (!_indStyled) {
      var st = document.createElement('style');
      st.id = 'voiceListenStyle';
      st.textContent =
        '#voiceListenIndicator{position:fixed;right:18px;bottom:18px;z-index:2147483000;display:none;align-items:center;gap:10px;' +
          'padding:10px 16px;border-radius:999px;background:rgba(26,28,38,.82);color:#fff;' +
          'font:600 13px/1 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;' +
          'box-shadow:0 8px 24px rgba(0,0,0,.28);-webkit-backdrop-filter:blur(4px);backdrop-filter:blur(4px);' +
          'pointer-events:none;opacity:0;transform:translateY(8px);transition:opacity .2s,transform .2s;}' +
        '#voiceListenIndicator.show{opacity:.94;transform:translateY(0);}' +
        '#voiceListenIndicator .vli-mic{font-size:15px;line-height:1;}' +
        '#voiceListenIndicator .vli-dot{width:10px;height:10px;border-radius:50%;background:#ff5252;animation:vliPulse 1.2s infinite;}' +
        '#voiceListenIndicator.processing .vli-dot{background:transparent;width:12px;height:12px;border:2px solid rgba(255,255,255,.35);border-top-color:#ffb300;border-radius:50%;animation:vliSpin .8s linear infinite;}' +
        '#voiceListenIndicator .vli-wave{display:inline-flex;align-items:flex-end;gap:2px;height:16px;}' +
        '#voiceListenIndicator .vli-wave i{width:3px;height:6px;background:#7fd1ff;border-radius:2px;animation:vliWave 1s infinite ease-in-out;}' +
        '#voiceListenIndicator .vli-wave i:nth-child(2){animation-delay:.15s;}' +
        '#voiceListenIndicator .vli-wave i:nth-child(3){animation-delay:.3s;}' +
        '#voiceListenIndicator .vli-wave i:nth-child(4){animation-delay:.45s;}' +
        '#voiceListenIndicator .vli-wave i:nth-child(5){animation-delay:.6s;}' +
        '#voiceListenIndicator.processing .vli-wave{opacity:.3;}' +
        '@keyframes vliPulse{0%{box-shadow:0 0 0 0 rgba(255,82,82,.55);}70%{box-shadow:0 0 0 8px rgba(255,82,82,0);}100%{box-shadow:0 0 0 0 rgba(255,82,82,0);}}' +
        '@keyframes vliWave{0%,100%{height:5px;}50%{height:15px;}}' +
        '@keyframes vliSpin{to{transform:rotate(360deg);}}' +
        '@media print{#voiceListenIndicator{display:none !important;}}';
      (document.head || document.documentElement).appendChild(st);
      _indStyled = true;
    }
    if (!_ind && document.body) {
      _ind = document.createElement('div');
      _ind.id = 'voiceListenIndicator';
      _ind.setAttribute('aria-live', 'polite');
      _ind.innerHTML = '<span class="vli-mic">🎤</span><span class="vli-dot"></span>' +
        '<span class="vli-wave"><i></i><i></i><i></i><i></i><i></i></span>' +
        '<span class="vli-text">Listening…</span>';
      document.body.appendChild(_ind);
    }
    return _ind;
  }
  function showIndicator(state) {
    var el = ensureIndicator(); if (!el) return;
    el.classList.remove('processing');
    if (state === 'processing') el.classList.add('processing');
    var t = el.querySelector('.vli-text');
    if (t) t.textContent = (state === 'processing') ? 'Processing…' : 'Listening…';
    el.style.display = 'flex';
    void el.offsetWidth;            // reflow so the fade-in transitions
    el.classList.add('show');
  }
  function hideIndicator() {
    if (!_ind) return;
    _ind.classList.remove('show');
    var el = _ind;
    setTimeout(function () { if (el && !el.classList.contains('show')) el.style.display = 'none'; }, 250);
  }

  function createRecorder(opts) {
    opts = opts || {};
    var hasWin = (typeof window !== 'undefined');
    var supported = !!(hasWin && navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.MediaRecorder);
    var api = { supported: supported };

    var stream = null, ctx = null, analyser = null, source = null;
    var monitorTimer = null, maxT = null;
    var running = false, muted = false;
    var cur = null;               // { rec, chunks, had:{v}, startedAt }
    var lastVoiceAt = 0;
    var maxMs        = opts.maxMs        || 60000;   // 1-minute hard cap
    var silenceMs    = opts.silenceMs    || 850;     // pause that ends a phrase
    var minPhraseMs  = 400;
    var levelThresh  = (opts.levelThreshold != null) ? opts.levelThreshold : 0.020;

    var inFlight = 0;   // OpenAI requests currently being processed

    /* -- Hard-cancel bookkeeping ------------------------------------------
       `gen` is bumped by cancel(); every async callback captures the value it
       started with and returns early if it no longer matches, so a cancelled
       phrase can never write to the UI. `pending` holds the AbortControllers
       for requests still in flight so cancel() can tear the sockets down
       rather than merely ignoring the replies. */
    var gen = 0, pending = [], cancelled = false;
    function status(m, k) { if (opts.onStatus) opts.onStatus(m, k || 'info'); }
    function setState(on) { running = on; if (opts.onState) opts.onState(on); refreshIndicator(); }
    function dbg(label, detail) { if (opts.onDebug) { try { opts.onDebug(label, detail); } catch (e) {} } }
    // Keep the floating indicator in sync with activity: Listening while
    // recording, Processing while a phrase is in flight, hidden once stopped
    // and all phrases are done.
    function refreshIndicator() {
      var phase = running ? (inFlight > 0 ? 'processing' : 'listening')
                          : (inFlight > 0 ? 'processing' : 'idle');
      if (phase === 'idle') hideIndicator(); else showIndicator(phase);
      // Let the host page mirror the phase (e.g. swap its mic icon for Stop).
      if (opts.onPhase) { try { opts.onPhase(phase); } catch (e) {} }
    }

    function pickMime() {
      return (window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported('audio/webm'))
        ? 'audio/webm' : '';
    }

    function makeRecorder() {
      var chunks = [], had = { v: false }, startedAt = Date.now(), mime = pickMime(), rec;
      try { rec = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream); }
      catch (e) { rec = new MediaRecorder(stream); }
      rec.ondataavailable = function (e) { if (e.data && e.data.size > 0) chunks.push(e.data); };
      rec.onstop = function () { sendPhrase(chunks, rec.mimeType || mime || 'audio/webm', had.v, startedAt); };
      rec.start();
      return { rec: rec, chunks: chunks, had: had, startedAt: startedAt };
    }

    function startPhrase() { if (running && stream) cur = makeRecorder(); }

    function cutPhrase() {
      var c = cur; cur = null;
      if (c && c.rec && c.rec.state !== 'inactive') { try { c.rec.stop(); } catch (e) {} }  // → onstop → send
      startPhrase();   // resume capturing the next phrase right away
    }

    function monitor() {
      if (muted || !analyser) return;
      var buf = new Uint8Array(analyser.fftSize);
      analyser.getByteTimeDomainData(buf);
      var sum = 0;
      for (var i = 0; i < buf.length; i++) { var v = (buf[i] - 128) / 128; sum += v * v; }
      var rms = Math.sqrt(sum / buf.length);
      var now = Date.now();
      if (rms > levelThresh) {
        if (cur && !cur.had.v) dbg('audio', 'speech detected');
        if (cur) cur.had.v = true;
        lastVoiceAt = now;
      }
      if (cur && cur.had.v && (now - lastVoiceAt) > silenceMs && (now - cur.startedAt) > minPhraseMs) {
        dbg('recording', 'pause detected — sending phrase');
        cutPhrase();
      }
    }

    api.start = function () {
      if (!supported) { status('Voice recording isn’t supported in this browser. Use a recent Chrome, Edge, or Safari.', 'error'); return; }
      if (running) return;
      cancelled = false;
      var startGen = gen;
      lastVoiceAt = Date.now();
      status('🎤 Listening… say a field, pause, and it fills in. Tap Stop when done (auto-stops after 1 minute).', 'listening');
      dbg('mic', 'requesting microphone access…');
      navigator.mediaDevices.getUserMedia({ audio: true }).then(function (s) {
        // Cancelled while the permission prompt was open: release the mic and
        // never enter the running state.
        if (startGen !== gen) { try { s.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {} return; }
        stream = s;
        try {
          var AC = window.AudioContext || window.webkitAudioContext;
          if (AC) { ctx = new AC(); source = ctx.createMediaStreamSource(s); analyser = ctx.createAnalyser(); analyser.fftSize = 2048; source.connect(analyser); }
        } catch (e) { analyser = null; dbg('warn', 'no audio analyser — phrase will be sent when you tap Stop'); }
        setState(true);
        dbg('recording', 'started — speak; each phrase fills its field as you pause');
        if (!muted) startPhrase();
        if (analyser) monitorTimer = setInterval(monitor, 100);
        maxT = setTimeout(function () {
          status('⏱ Reached the 1-minute limit — processing…', 'info');
          dbg('recording', '1-minute cap reached');
          api.stop();
        }, maxMs);
      }).catch(function (err) {
        if (startGen !== gen) return;          // cancelled - stay quiet
        teardown(); setState(false);
        var name = err && err.name;
        dbg('error', 'microphone error: ' + (name || 'unknown'));
        if (name === 'NotAllowedError' || name === 'SecurityError') status('Microphone permission is blocked. Allow the microphone in your browser, then tap Voice again.', 'error');
        else if (name === 'NotFoundError') status('No microphone was found. Connect a microphone and try again.', 'error');
        else status('Could not start the microphone' + (name ? (' (' + name + ')') : '') + '. Try again.', 'error');
      });
    };

    api.stop = function () {
      if (!running) return;
      setState(false);
      dbg('recording', 'stopped');
      if (monitorTimer) { clearInterval(monitorTimer); monitorTimer = null; }
      if (maxT) { clearTimeout(maxT); maxT = null; }
      var c = cur; cur = null;
      if (c && c.rec && c.rec.state !== 'inactive') { try { c.rec.stop(); } catch (e) {} }  // flush final phrase
      setTimeout(teardown, 60);
    };

    api.toggle = function () { if (running) api.stop(); else api.start(); };
    api.isRecording = function () { return running; };
    // True while anything is still happening - listening OR a phrase in flight.
    api.isBusy = function () { return !!running || inFlight > 0; };

    /* Hard cancel. Unlike stop(), this does NOT flush the phrase in progress:
       the pending audio is discarded, in-flight requests are aborted, timers
       are cleared and the microphone is released immediately. Bumping `gen`
       turns any callback that was already queued into a no-op, so nothing can
       update the UI after the user has pressed Stop. */
    api.cancel = function () {
      cancelled = true;
      gen++;
      if (monitorTimer) { clearInterval(monitorTimer); monitorTimer = null; }
      if (maxT) { clearTimeout(maxT); maxT = null; }
      var c = cur; cur = null;
      if (c && c.rec) {
        // Detach onstop FIRST so stopping the recorder cannot queue a send.
        try { c.rec.ondataavailable = null; c.rec.onstop = null; } catch (e) {}
        try { if (c.rec.state !== 'inactive') c.rec.stop(); } catch (e) {}
      }
      if (c && c.chunks) { try { c.chunks.length = 0; } catch (e) {} }
      for (var i = 0; i < pending.length; i++) { try { pending[i].abort(); } catch (e) {} }
      pending.length = 0;
      inFlight = 0;
      muted = false;
      running = false;
      teardown();
      hideIndicator();
      dbg('recording', 'cancelled - audio discarded, requests aborted, mic released');
      if (opts.onState) { try { opts.onState(false); } catch (e) {} }
      if (opts.onPhase) { try { opts.onPhase('idle'); } catch (e) {} }
      if (opts.onCancel) { try { opts.onCancel(); } catch (e) {} }
    };

    /* Pause/resume capture without releasing the mic — used during voice
       playback (TTS) so the system's own speech isn't recorded. */
    api.setMuted = function (m) {
      m = !!m;
      if (m === muted) return;
      muted = m;
      if (muted) {
        var c = cur; cur = null;
        if (c && c.rec) { try { c.rec.onstop = null; if (c.rec.state !== 'inactive') c.rec.stop(); } catch (e) {} }
        dbg('mic', 'paused — system speaking (not listening)');
      } else {
        lastVoiceAt = Date.now();
        // Only actually resume capture if a session is still running. After a
        // stop, a late TTS-resume must NOT reopen the mic or claim "listening".
        if (running && stream) { if (!cur) startPhrase(); dbg('mic', 'resumed — listening'); }
        else dbg('mic', 'resume ignored — recording stopped (idle)');
      }
    };

    function teardown() {
      if (monitorTimer) { clearInterval(monitorTimer); monitorTimer = null; }
      if (maxT) { clearTimeout(maxT); maxT = null; }
      try { if (source) source.disconnect(); } catch (e) {}
      try { if (ctx && ctx.state !== 'closed') ctx.close(); } catch (e) {}
      ctx = null; analyser = null; source = null;
      if (stream) { try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {} stream = null; }
    }

    function sendPhrase(chunks, type, had, startedAt) {
      if (cancelled) return;                                  // stopped by the user
      if (!had || !chunks || !chunks.length) return;          // silence-only segment
      var blob = new Blob(chunks, { type: type || 'audio/webm' });
      if (blob.size < 1200) { dbg('audio', 'phrase too short — skipped'); return; }
      dbg('audio', Math.round(blob.size / 1024) + ' KB phrase (' + ((Date.now() - startedAt) / 1000).toFixed(1) + 's)');
      status('⏳ Processing…', 'info');
      postPhrase(blob, type);
    }

    function postPhrase(blob, type) {
      var ext = (type.indexOf('ogg') >= 0) ? 'ogg' : (type.indexOf('mp4') >= 0 ? 'mp4' : 'webm');
      var fd = new FormData();
      fd.append('audio', blob, 'voice.' + ext);
      fd.append('context', opts.context || 'income');
      var t0 = Date.now();
      dbg('request', 'POST /api/voice/command  context=' + (opts.context || 'income'));
      var myGen = gen;
      var ac = (typeof AbortController !== 'undefined') ? new AbortController() : null;
      if (ac) pending.push(ac);
      inFlight++; refreshIndicator();          // → "Processing…"
      fetch('/api/voice/command', ac ? { method: 'POST', body: fd, signal: ac.signal }
                                     : { method: 'POST', body: fd })
        .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, code: r.status, d: d }; }); })
        .then(function (res) {
          if (myGen !== gen) return;           // cancelled - discard the reply
          dbg('response', 'HTTP ' + res.code + ' in ' + (Date.now() - t0) + ' ms');
          if (res.code === 403) {
            status(res.d.error || 'Voice limit reached or disabled.', 'error');
            dbg('warn', res.d.error || 'voice limit reached / disabled');
            // Pass the server's own reason through: only the quota gate is a "limit";
            // staff-only, plan and per-church feature refusals must not be reported as one.
            var stat = res.d.status || {};
            if (opts.onDisabled) opts.onDisabled(stat, { reason: res.d.reason || res.d.code || '', error: res.d.error || '' });
            api.stop();
            return;
          }
          if (!res.ok) { dbg('error', res.d.error || ('HTTP ' + res.code)); status(res.d.error || 'Voice processing failed. Please try again.', 'error'); return; }
          var conf = (res.d.confidence != null && res.d.confidence >= 0) ? (' · conf ' + Math.round(res.d.confidence * 100) + '%') : '';
          if (res.d.durationSeconds != null) dbg('openai', 'transcribed ' + res.d.durationSeconds + 's of audio' + conf);
          if (opts.onTranscript) opts.onTranscript(res.d.transcript || '');
          if (opts.onStatusData && res.d.status) opts.onStatusData(res.d.status);
          if (opts.onResult) opts.onResult(res.d);
        })
        .catch(function (e) {
          if (myGen !== gen) return;           // cancelled - stay quiet
          if (e && e.name === 'AbortError') return;
          dbg('error', 'network error: ' + (e && e.message ? e.message : 'fetch failed'));
          status('Network error talking to the voice service. Try again.', 'error');
        })
        .then(function () {
          if (ac) { var ix = pending.indexOf(ac); if (ix >= 0) pending.splice(ix, 1); }
          if (myGen !== gen) return;           // cancel() already zeroed inFlight
          inFlight = Math.max(0, inFlight - 1); refreshIndicator();
        });  // back to Listening, or hide
    }

    return api;
  }

  root.VoiceOpenAI = { createRecorder: createRecorder, getStatus: getStatus, getVoiceStatus: getVoiceStatus };
})(typeof window !== 'undefined' ? window : this);
