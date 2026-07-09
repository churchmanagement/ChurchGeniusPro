/* ============================================================================
 * income-voice.js — Voice input for the Income entry form.
 *
 * Lets users dictate an income entry ("Contributor Anson Mathew, Offering
 * Church Fund, Date May thirty-first twenty twenty-six, Method Bank Transfer,
 * Amount one hundred") and auto-populates the form fields, dropdowns, date and
 * amount. Uses the browser Web Speech API (Chrome/Edge desktop, Android Chrome,
 * iOS Safari 14.5+). Falls back gracefully where speech recognition is missing.
 *
 * Self-contained: injects its own UI + styles, fetches its own contributor list,
 * reads the Fund/Method <select> options from the DOM, and reuses the page's
 * global selectContributor()/toggleGuestMode() to drive the contributor combobox.
 * The parsing logic below was unit-tested separately (41 cases).
 * ========================================================================== */
(function () {
  'use strict';

  // ── Shared parser (voice-core.js) ─────────────────────────────────────────
  var VC = window.VoiceCore || {};
  var parseAmount      = VC.parseAmount,
      parseDate        = VC.parseDate,
      matchOption      = VC.matchOption,
      matchContributor = VC.matchContributor,
      titleCase        = VC.titleCase;

  // Field keywords for the income form (passed to VoiceCore.segment).
  var KEYWORDS = {
    contributor:['contributor','contributer','contributors','member','donor'],
    fund:['offering','offerings','fund','funds','category','purpose'],
    date:['date','dated'],
    method:['method','payment','mode'],
    amount:['amount','value','total'],
    ref:['reference','ref'],
    note:['note','notes','memo','remark']
  };
  function segment(transcript){ return VC.segment ? VC.segment(transcript, KEYWORDS) : {}; }

  // ── DOM helpers ───────────────────────────────────────────────────────────
  function $(id){ return document.getElementById(id); }
  function selectOptions(id){ var el=$(id); return el ? Array.prototype.slice.call(el.options).map(function(o){return {value:o.value,text:(o.textContent||'').trim()};}) : []; }
  function setSelect(id,val){ var el=$(id); if(!el) return; el.value=String(val); el.dispatchEvent(new Event('change',{bubbles:true})); }
  function markField(id, state){ // state: 'ok' | 'confirm' | 'fail' | ''
    var el=$(id); if(!el) return;
    el.classList.remove('voice-ok','voice-confirm','voice-fail');
    if(state) el.classList.add('voice-'+state);
  }

  var contributorsCache=null;
  function loadContributors(){
    return fetch('/api/income/contributors').then(function(r){return r.ok?r.json():[];})
      .then(function(data){ contributorsCache = Array.isArray(data)?data:(data&&data.data)||[]; return contributorsCache; })
      .catch(function(){ contributorsCache=[]; return contributorsCache; });
  }

  // ── UI injection ──────────────────────────────────────────────────────────
  function injectStyles(){
    if($('voiceStyles')) return;
    var css = ''
      + '.btn-voice-mic{display:inline-flex;align-items:center;gap:6px;padding:7px 13px;border-radius:7px;border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-size:13px;font-weight:600;cursor:pointer;}'
      + '.btn-voice-mic:hover{background:#673147;color:#fff;border-color:#673147;}'
      + '.btn-voice-mic.recording{background:#e53935;color:#fff;border-color:#e53935;animation:voicePulse 1s infinite;}'
      + '.btn-voice-mic[disabled]{opacity:.5;cursor:not-allowed;}'
      + '.btn-voice-guide{margin-left:6px;padding:7px 11px;border-radius:7px;border:1px solid #d7c9d0;background:#fff;color:#673147;font-size:12px;font-weight:600;cursor:pointer;}'
      + '@keyframes voicePulse{50%{opacity:.65;}}'
      + '#voicePanel{margin:0 0 14px;border:1px solid #e6dde2;border-radius:9px;background:#fbf7f9;padding:12px 14px;font-size:13px;color:#444;}'
      + '#voicePanel.hidden{display:none;}'
      + '#voiceStatus{font-weight:600;color:#673147;}'
      + '#voiceTranscript{margin-top:6px;font-style:italic;color:#555;}'
      + '#voiceResults{margin-top:8px;display:flex;flex-wrap:wrap;gap:6px;}'
      + '.voice-chip{font-size:12px;padding:3px 9px;border-radius:999px;background:#eef1f6;color:#41506b;}'
      + '.voice-chip.ok{background:#e7f5ec;color:#1e7a44;} .voice-chip.confirm{background:#fff6e5;color:#9a6b00;} .voice-chip.fail{background:#fdeaea;color:#b42424;}'
      + '#voiceSuggest{margin-top:10px;display:flex;flex-direction:column;gap:8px;}'
      + '.voice-sugg-row{font-size:12.5px;}'
      + '.voice-sugg-row b{color:#673147;}'
      + '.voice-sugg-opt{display:inline-block;margin:3px 5px 0 0;padding:4px 10px;border:1px solid #c5a0b5;border-radius:6px;background:#fff;color:#673147;cursor:pointer;font-size:12px;}'
      + '.voice-sugg-opt:hover{background:#673147;color:#fff;}'
      + '#voiceGuide{margin-top:10px;border-top:1px dashed #e0d3da;padding-top:10px;font-size:12.5px;color:#555;line-height:1.6;}'
      + '#voiceGuide.hidden{display:none;}'
      + '#voiceGuide code{background:#fff;border:1px solid #ecdfe6;border-radius:4px;padding:1px 5px;color:#673147;}'
      + '.voice-ok{outline:2px solid #43a047 !important;outline-offset:1px;} .voice-confirm{outline:2px solid #f4a000 !important;outline-offset:1px;} .voice-fail{outline:2px solid #e53935 !important;outline-offset:1px;}'
      + '.voice-cleared{outline:2px solid #2f6fd0 !important;outline-offset:1px;}'
      + '.voice-current{outline:3px solid #5c6bc0 !important;outline-offset:1px;box-shadow:0 0 0 4px rgba(92,107,192,.18) !important;}'
      + '#voiceCurrent{margin-top:8px;font-size:12.5px;font-weight:700;color:#5c6bc0;display:none;}'
      + '#voiceCurrent .vc-dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#5c6bc0;margin-right:6px;animation:voicePulse 1s infinite;}'
      + '#voiceDebug{margin-top:10px;display:none;background:#11151c;color:#cfe3ff;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:11.5px;line-height:1.5;'
      +   'padding:10px 12px;border-radius:8px;max-height:220px;overflow:auto;white-space:pre-wrap;word-break:break-word;}'
      + '#voiceDebug .vd-row{display:block;}'
      + '#voiceDebug .vd-time{color:#6b7a90;} #voiceDebug .vd-tag{color:#7fd1ff;font-weight:700;}'
      + '#voiceDebug .vd-warn{color:#ffcf6b;} #voiceDebug .vd-error{color:#ff8e8e;} #voiceDebug .vd-fill{color:#9ff0b0;}'
      + '#voiceDebugHead{margin-top:10px;display:none;font-size:11.5px;color:#41506b;background:#eef1f6;border:1px solid #dfe4ee;border-radius:8px 8px 0 0;padding:8px 12px;line-height:1.6;}'
      + '#voiceDebugHead b{color:#5c6bc0;}'
      + '.btn-voice-debug{margin-left:6px;padding:7px 11px;border-radius:7px;border:1px solid #c7c0d7;background:#fff;color:#41506b;font-size:12px;font-weight:600;cursor:pointer;}';
    var st=document.createElement('style'); st.id='voiceStyles'; st.textContent=css; document.head.appendChild(st);
  }

  function guideHTML(){
    return ''
      + '<div id="voiceGuide" class="hidden">'
      + '<div style="font-weight:700;color:#673147;margin-bottom:4px;">Voice Command Guide</div>'
      + 'Click the mic, then speak <b>one field at a time</b> — you can just say the value and it finds the right field. Pause between fields. Examples:<br>'
      + '<code>Anson Mathew</code> … <code>Church Fund</code> … <code>Bank Transfer</code> … <code>one hundred</code> … <code>May thirty-first twenty twenty-six</code><br>'
      + 'Or say it all at once, naming each field:<br>'
      + '<code>Contributor Anson Mathew, Offering Church Fund, Date May thirty-first twenty twenty-six, Method Bank Transfer, Amount one hundred</code>'
      + '<ul style="margin:8px 0 0 18px;padding:0;">'
      + '<li><b>Contributor</b> — say the person’s name: <code>Contributor Anson Mathew</code>. Not a member? It is entered as a guest name for you to confirm.</li>'
      + '<li><b>Offering / Fund</b> — say the fund name with either keyword: <code>Offering Church Fund</code>, <code>Fund Building Fund</code>, <code>Offering Missions</code> — or just say the name, e.g. <code>Church Fund</code>.</li>'
      + '<li><b>Date</b> — many formats: <code>Date May thirty-first twenty twenty-six</code>, <code>Date five thirty-one two zero two six</code>, or <code>Date 5 31 2026</code>.</li>'
      + '<li><b>Method</b> — payment method: <code>Method Bank Transfer</code>, <code>Method Cash</code>, <code>Method Check</code>.</li>'
      + '<li><b>Amount</b> — words or digits: <code>Amount one hundred</code> or <code>Amount 100</code> → 100.00.</li>'
      + '<li><b>Reference / Check No</b> — <code>Reference Number 12345</code>, <code>Ref No 987654</code>, <code>Check Number 4567</code>, <code>Check No 7890</code>, <code>Cheque No …</code>. Letters work too: <code>Reference Number ABC123</code>; for hyphens say “dash”: <code>Check Number TXN dash 2026 dash 001</code> → TXN-2026-001.</li>'
      + '<li><b>Note</b>: <code>Note weekly tithe</code>.</li>'
      + '<li><b>Clear / Reset</b> — fix mistakes by voice: <code>Clear Contributor</code>, <code>Clear Amount</code>, <code>Clear Date</code>, <code>Clear Method</code> (clears one field) · <code>Reset Field</code> (resets the field you changed last) · <code>Reset Fields</code> / <code>Clear Form</code> / <code>Reset Form</code> (clears the whole form).</li>'
      + '</ul>'
      + '<div style="margin-top:6px;color:#888;">Auto-filled fields are outlined; amber means please confirm before saving. If several people or funds match, pick the right one from the suggestions.</div>'
      + '</div>';
  }

  function injectUI(){
    injectStyles();
    var header = document.querySelector('#formCard .card-header');
    if(header && !$('voiceMicBtn')){
      var wrap=document.createElement('div'); wrap.style.display='flex'; wrap.style.alignItems='center';
      wrap.innerHTML = '<button type="button" id="voiceMicBtn" class="btn-voice-mic" title="Voice — fill the form by voice" aria-label="Voice">🎤</button>'
        + '<button type="button" id="voiceConverseBtn" class="btn-voice-mic" title="Converse — guided voice conversation" aria-label="Converse">🗣</button>'
        + '<button type="button" id="voiceGuideBtn" class="btn-voice-guide" title="Guide — voice command help" aria-label="Guide">❔</button>'
        + '<button type="button" id="voiceDebugBtn" class="btn-voice-debug" title="Debug — show the voice debug console" aria-label="Debug">🐞</button>';
      header.appendChild(wrap);
    }
    var body = document.querySelector('#formCard .card-body');
    var grid = document.querySelector('#formCard .form-grid');
    if(body && grid && !$('voicePanel')){
      var panel=document.createElement('div'); panel.id='voicePanel'; panel.className='hidden';
      panel.innerHTML = '<div id="voiceStatus"></div><div id="voiceTranscript"></div>'
        + '<div id="voiceCurrent"></div>'
        + '<div id="voiceResults"></div><div id="voiceSuggest"></div>'
        + '<div id="voiceDebugHead"></div>'
        + '<div id="voiceDebug"></div>'
        + guideHTML();
      body.insertBefore(panel, grid);
    }
  }

  function showPanel(){ var p=$('voicePanel'); if(p) p.classList.remove('hidden'); }
  function setStatus(html){ var s=$('voiceStatus'); if(s) s.innerHTML=html; showPanel(); }
  function setTranscript(t){ var s=$('voiceTranscript'); if(s) s.textContent = t?('You said: “'+t+'”'):''; }
  function chip(label,state){ return '<span class="voice-chip '+(state||'')+'">'+label+'</span>'; }

  // ── Field fillers (apply one value to the DOM, return {chip, suggest, confirm}) ──
  function fillContributor(value){
    var cm = matchContributor(value, contributorsCache||[]);
    if(cm.best && !cm.ambiguous && cm.best.score>=60){
      applyContributor(cm.best.id, cm.best.fullName); markField('fContributorInput','ok');
      return {chip:chip('Contributor: '+cm.best.fullName,'ok')};
    }
    if(cm.ambiguous || (cm.best && cm.candidates.length>1)){
      markField('fContributorInput','confirm');
      return {confirm:true, chip:chip('Contributor: choose below','confirm'),
        suggest:suggestRow('Contributor — "'+value+'"', cm.candidates.map(function(c){
          return {label:c.fullName, onpick:"window.__voicePick('contributor','"+c.id+"',"+JSON.stringify(c.fullName)+")"}; }))};
    }
    if(typeof window.toggleGuestMode==='function'){ window.toggleGuestMode(true); var g=$('fGuestName'); if(g){ g.value=titleCase(value); markField('fGuestName','confirm'); } }
    return {confirm:true, chip:chip('Contributor: guest "'+titleCase(value)+'" (confirm)','confirm')};
  }
  function fillFund(value){
    var all=selectOptions('fFund');
    var opts=all.filter(function(o){ return o.value!=='' && o.value!=null; });
    var fo=matchOption(value, all);
    if(typeof logEvent==='function') logEvent('fund-match', {said:value, options:opts.length, best:(fo.best?fo.best.text:null), score:(fo.best?fo.best.score:0), ambiguous:!!fo.ambiguous});
    if(!opts.length){ markField('fFund','fail'); return {confirm:true, chip:chip('Fund list isn’t loaded yet — wait a second and try again','fail')}; }
    if(fo.best && !fo.ambiguous){ setSelect('fFund',fo.best.value); markField('fFund','ok'); return {chip:chip('Fund selected: '+fo.best.text,'ok')}; }
    if(fo.candidates.length){ markField('fFund','confirm'); return {confirm:true, chip:chip('Fund: choose below','confirm'),
      suggest:suggestRow('Fund — "'+value+'"', fo.candidates.map(function(c){ return {label:c.text, onpick:"window.__voicePick('fFund','"+c.value+"')"}; }))}; }
    markField('fFund','fail'); return {confirm:true, chip:chip('Fund: not found in the list','fail')};
  }
  function fillMethod(value){
    var mo=matchOption(value, selectOptions('fMethod'));
    if(mo.best && !mo.ambiguous){ setSelect('fMethod',mo.best.value); markField('fMethod','ok'); return {chip:chip('Method: '+mo.best.text,'ok')}; }
    if(mo.candidates.length){ markField('fMethod','confirm'); return {confirm:true, chip:chip('Method: choose below','confirm'),
      suggest:suggestRow('Method — "'+value+'"', mo.candidates.map(function(c){ return {label:c.text, onpick:"window.__voicePick('fMethod','"+c.value+"')"}; }))}; }
    markField('fMethod','fail'); return {confirm:true, chip:chip('Method: not found','fail')};
  }
  function fillDate(value){
    var s=String(value==null?'':value).trim();
    var d = resolveNaturalDate(s);                          // today / tomorrow / last Sunday …
    if(!d && /^\d{4}-\d{2}-\d{2}$/.test(s)) d = s;          // ISO from the model
    if(!d) d = parseDate(s);                                // month names / numeric
    if(d){ var de=$('fDate'); if(de){ de.value=d; de.dispatchEvent(new Event('change',{bubbles:true})); } markField('fDate','ok'); return {chip:chip('Date: '+usDate(d),'ok')}; }
    markField('fDate','fail'); return {confirm:true, chip:chip('Date: not understood','fail')};
  }

  // Resolve common natural-language date expressions → "YYYY-MM-DD" (or null).
  function resolveNaturalDate(text){
    if(!text) return null;
    var t=String(text).toLowerCase().trim();
    var today=new Date(); today.setHours(0,0,0,0);
    function iso(d){ return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'); }
    function addDays(n){ var d=new Date(today); d.setDate(d.getDate()+n); return d; }
    if(/^(date[:\s-]*)?(today|tonight|now)\b/.test(t) || /\btoday\b/.test(t)) return iso(today);
    if(/\btomorrow\b/.test(t))  return iso(addDays(1));
    if(/\byesterday\b/.test(t)) return iso(addDays(-1));
    var inN=t.match(/\bin\s+(\d+)\s+days?\b/);   if(inN)  return iso(addDays(parseInt(inN[1],10)));
    var agoN=t.match(/\b(\d+)\s+days?\s+ago\b/); if(agoN) return iso(addDays(-parseInt(agoN[1],10)));
    var WD={sunday:0,monday:1,tuesday:2,wednesday:3,thursday:4,friday:5,saturday:6,
            sun:0,mon:1,tue:2,tues:2,wed:3,thu:4,thur:4,thurs:4,fri:5,sat:6};
    var m=t.match(/\b(last|next|this|coming|past)\s+([a-z]+)\b/);
    if(m && WD[m[2]]!=null){
      var rel=m[1], wd=WD[m[2]], cur=today.getDay();
      if(rel==='next'||rel==='coming'){ var f=(wd-cur+7)%7; return iso(addDays(f===0?7:f)); }
      if(rel==='last'||rel==='past'){ var b=(cur-wd+7)%7; return iso(addDays(b===0?-7:-b)); }
      return iso(addDays((wd-cur+7)%7));   // this <weekday>
    }
    var bw=t.match(/^\s*([a-z]+)\s*$/);
    if(bw && WD[bw[1]]!=null){ var f2=(WD[bw[1]]-today.getDay()+7)%7; return iso(addDays(f2===0?7:f2)); }
    return null;
  }
  function fillAmount(value){
    var a=parseAmount(value);
    if(a!=null && a>0){ var ae=$('fAmount'); if(ae){ ae.value=a.toFixed(2); ae.dispatchEvent(new Event('input',{bubbles:true})); } markField('fAmount','ok'); return {chip:chip('Amount: '+a.toFixed(2),'ok')}; }
    markField('fAmount','fail'); return {confirm:true, chip:chip('Amount: not understood','fail')};
  }
  // Normalize a spoken reference/check value: keep letters, digits, spaces and
  // hyphens; turn spoken "dash"/"hyphen" into "-"; uppercase (ref/check numbers
  // are conventionally upper-case). Does NOT strip alphanumerics like the old code.
  function cleanRef(value){
    var s=String(value||'');
    s=s.replace(/\b(dash|hyphen|minus)\b/gi,'-').replace(/\b(slash)\b/gi,'-').replace(/\b(space|gap)\b/gi,' ');
    s=s.replace(/[^A-Za-z0-9 \-]+/g,' ');                 // keep letters, digits, spaces, hyphens
    s=s.replace(/\s*-\s*/g,'-').replace(/\s+/g,' ').trim();
    return s.toUpperCase();
  }
  function fillRef(value){ var v=cleanRef(value); var re=$('fRefNo'); if(re){ re.value=v; re.dispatchEvent(new Event('input',{bubbles:true})); } return {chip:chip('Ref No / Check No set to '+v,'ok')}; }
  function fillNote(value){ var ne=$('fNote'); if(ne) ne.value=titleCase(value); return {chip:chip('Note: '+titleCase(value),'ok')}; }
  var FILLERS={contributor:fillContributor,fund:fillFund,method:fillMethod,date:fillDate,amount:fillAmount,ref:fillRef,note:fillNote};
  var FIELD_LABEL={contributor:'contributor',fund:'offering',method:'method',date:'date',amount:'amount',ref:'reference',note:'note'};

  // A "bare" field-name utterance (e.g. just "contributor") so the NEXT utterance fills it.
  function bareKeywordField(transcript){
    var kw2f={}; Object.keys(KEYWORDS).forEach(function(f){ KEYWORDS[f].forEach(function(k){ kw2f[k]=f; }); });
    var toks=(transcript||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').split(/\s+/).filter(Boolean);
    var last=null, valueAfter=false;
    for(var i=0;i<toks.length;i++){ if(kw2f[toks[i]]){ last=kw2f[toks[i]]; valueAfter=false; } else valueAfter=true; }
    return (last && !valueAfter) ? last : null;
  }
  // Keyword-free classification: which field does this spoken value belong to?
  var NUMWORD=/\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|dollar|dollars|cent|cents|buck|bucks)\b/;
  var MONTHWORD=/\b(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\b/;
  function classify(text){
    var best=null;
    function consider(field, score){ if(score>=0.6 && (!best || score>best.score)) best={field:field, score:score}; }
    if(/\d/.test(text) || NUMWORD.test(text)){ var a=parseAmount(text); if(a!=null && a>0) consider('amount', 0.9); }
    if(MONTHWORD.test(text) || /\b\d{1,2}[ \/.\-]\d{1,2}[ \/.\-]\d{2,4}\b/.test(text) || /\b(today|yesterday|tomorrow)\b/.test(text)){ if(parseDate(text)) consider('date', 0.95); }
    var cm=matchContributor(text, contributorsCache||[]); if(cm.best) consider('contributor', cm.best.score/100);
    var fo=matchOption(text, selectOptions('fFund')); if(fo.best) consider('fund', fo.best.score/100);
    var mo=matchOption(text, selectOptions('fMethod')); if(mo.best) consider('method', mo.best.score/100);
    return best;
  }

  // Per-session field state so the panel accumulates across utterances.
  var pendingField=null, sessionFields={};
  function resetSession(){ pendingField=null; lastField=null; sessionFields={}; var r=$('voiceResults'),s=$('voiceSuggest'); if(r)r.innerHTML=''; if(s)s.innerHTML=''; }
  function renderSession(){
    var chips=[], sug='', confirm=false;
    Object.keys(sessionFields).forEach(function(f){ var r=sessionFields[f]; if(!r)return; if(r.chip)chips.push(r.chip); if(r.suggest)sug+=r.suggest; if(r.confirm)confirm=true; });
    var resEl=$('voiceResults'), sugEl=$('voiceSuggest');
    if(resEl) resEl.innerHTML=chips.join(''); if(sugEl) sugEl.innerHTML=sug;
    return confirm;
  }

  // ── Clear / reset commands ────────────────────────────────────────────────
  var DISPLAY={contributor:'Contributor',fund:'Fund',method:'Method',date:'Date',amount:'Amount',ref:'Reference',note:'Note'};
  // Field-name synonyms accepted in a "Clear <field>" command.
  var FIELD_WORDS={
    contributor:['contributor','contributer','contributors','member','donor','name'],
    fund:['offering','offerings','fund','category','purpose'],
    date:['date','dated'],
    method:['method','payment','mode'],
    amount:['amount','value','total','money'],
    ref:['reference','ref'],
    note:['note','notes','memo','remark']
  };
  // DOM ids cleared for each logical field (the first is the highlighted/primary one).
  var CLEAR_TARGETS={
    contributor:['fContributorInput','fContributor','fGuestName'],
    fund:['fFund'], method:['fMethod'], date:['fDate'], amount:['fAmount'], ref:['fRefNo'], note:['fNote']
  };
  var lastField=null;
  function flash(id){ var el=$(id); if(!el) return; el.classList.add('voice-cleared'); setTimeout(function(){ el.classList.remove('voice-cleared'); }, 1300); }
  function clearDom(id){ var el=$(id); if(!el) return; el.value=''; try{ el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('input',{bubbles:true})); }catch(e){} }
  function clearFieldKey(field){
    if(!CLEAR_TARGETS[field]) return false;
    if(field==='contributor' && typeof window.toggleGuestMode==='function'){ try{ window.toggleGuestMode(false); }catch(e){} }
    CLEAR_TARGETS[field].forEach(function(id){ clearDom(id); markField(id,''); });
    flash(CLEAR_TARGETS[field][0]);
    delete sessionFields[field]; if(lastField===field) lastField=null;
    renderSession(); return true;
  }
  function resetAllFields(){
    Object.keys(CLEAR_TARGETS).forEach(function(field){
      if(field==='contributor' && typeof window.toggleGuestMode==='function'){ try{ window.toggleGuestMode(false); }catch(e){} }
      CLEAR_TARGETS[field].forEach(function(id){ clearDom(id); markField(id,''); flash(id); });
    });
    sessionFields={}; lastField=null; pendingField=null; renderSession();
  }
  function fieldFromWords(text){
    var toks=(text||'').toLowerCase().replace(/[^a-z ]+/g,' ').split(/\s+/).filter(Boolean);
    for(var i=0;i<toks.length;i++){ for(var f in FIELD_WORDS){ if(FIELD_WORDS[f].indexOf(toks[i])>=0) return f; } }
    return null;
  }
  // Handle "Clear <field>", "Reset Field", "Reset/Clear Form". Returns true if this
  // utterance was a command (so the normal fill logic is skipped).
  function handleCommand(transcript){
    var t=(transcript||'').toLowerCase().replace(/[^a-z ]+/g,' ').replace(/\s+/g,' ').trim();
    var m=t.match(/^(clear|reset|remove|delete|erase|empty)\b(.*)$/);   // verb must lead, avoids matching dictated values
    if(!m) return false;
    var rest=(m[2]||'').trim();
    pendingField=null;
    if(/\b(form|everything|fields|all)\b/.test(rest)){ resetAllFields(); setStatus('✅ Form reset successfully.'); return true; }
    if(rest==='' || /\bfield\b/.test(rest)){
      if(lastField && clearFieldKey(lastField)) setStatus('✅ '+DISPLAY[lastField]+' reset.');
      else setStatus('Nothing to reset yet — fill a field first, or say “Reset Form”.');
      return true;
    }
    var field=fieldFromWords(rest);
    if(field && clearFieldKey(field)){ setStatus('✅ '+DISPLAY[field]+' cleared.'); return true; }
    setStatus('Heard a clear command but not which field. Try “Clear Amount”, “Clear Contributor”, or “Reset Form”.');
    return true;
  }

  // ── Reference / Check number ──────────────────────────────────────────────
  // Reads the RAW transcript (so hyphens and letter-case survive) when the
  // utterance leads with a ref/check keyword and carries a digit. Handles
  // "Reference Number 12345", "Ref No 987654", "Check Number 4567", "Check No
  // 7890", "Cheque No …", and alphanumerics like "ABC123" / "TXN-2026-001".
  function handleRefCommand(transcript){
    var m=(transcript||'').match(/^\s*(?:the\s+)?(reference|refrence|ref|cheque|check)\s*(?:numbers?|num|no)?\b[\s:.\-]*(.+)$/i);
    if(!m) return false;
    var raw=(m[2]||'').trim();
    if(!/\d/.test(raw)) return false;   // ref/check values carry digits; digit-less phrases fall through to normal parsing
    var val=cleanRef(raw);
    pendingField=null;
    if(!val){ setStatus('Didn’t catch the reference/check number — say it again, e.g. “Reference Number 12345” or “Check No 7890”.'); return true; }
    var re=$('fRefNo'); if(re){ re.value=val; re.dispatchEvent(new Event('input',{bubbles:true})); markField('fRefNo','ok'); flash('fRefNo'); }
    lastField='ref'; sessionFields['ref']={chip:chip('Ref No / Check No set to '+val,'ok')}; renderSession();
    setStatus('✅ Ref No / Check No set to '+val+'.');
    return true;
  }

  // ── Apply one recognized phrase to the form, in real time ─────────────────
  // The backend returns a structured {fields} object per phrase, so each field
  // is applied as soon as it is recognized (one phrase ≈ one field).
  var ORDER=['contributor','fund','date','method','amount','ref','note'];
  function applyPayload(payload){
    payload = payload || {};
    var transcript = payload.transcript || '';
    setTranscript(transcript);
    var fields = payload.fields || {};
    logEvent('entities', JSON.stringify(fields));

    // Clear / reset command from the model (e.g. "clear amount", "reset form").
    if(payload.command){ logEvent('command', payload.command);
      if(handleCommand(payload.command)){ setCurrentField(null); return; } }

    // Structured fields → apply immediately.
    var pairs=[];
    ORDER.forEach(function(f){ if(fields[f]) pairs.push({field:f, value:String(fields[f])}); });
    Object.keys(fields).forEach(function(f){ if(fields[f] && ORDER.indexOf(f)<0) pairs.push({field:f, value:String(fields[f])}); });
    if(pairs.length){ applyPairs(pairs); return; }

    // Fallback: the deterministic segmenter on the raw transcript.
    if(transcript){
      if(handleCommand(transcript)){ setCurrentField(null); return; }
      if(handleRefCommand(transcript)){ setCurrentField(null); return; }
      var seg=segment(transcript);
      var fb=[]; ORDER.forEach(function(f){ if(seg[f]) fb.push({field:f, value:seg[f]}); });
      if(fb.length){ logEvent('warn','model returned no fields — used transcript segmenter'); applyPairs(fb); return; }
      var c=classify(transcript); if(c){ applyPairs([{field:c.field, value:transcript}]); return; }
    }
    setCurrentField(null);
    logEvent('warn','no field detected in this phrase');
    setStatus('Didn’t catch a value in that phrase. Say e.g. “Contributor Anson Mathew”, then pause; then “Amount 100”, then pause.');
  }

  // Apply field(s) from one phrase. Usually a single field → instant; multiple
  // fields stagger slightly so each one is visible as it lands.
  function applyPairs(pairs){
    var i=0;
    function step(){
      if(i>=pairs.length){
        setCurrentField(null);
        var confirm=renderSession();
        setStatus(confirm
          ? '⚠️ Review the highlighted fields, fix anything in amber/red, then Save.'
          : '✅ Field filled. Keep speaking the next field, or tap Stop.');
        return;
      }
      var p=pairs[i++];
      setCurrentField(p.field);
      logEvent('fill', (DISPLAY[p.field]||p.field)+' = "'+p.value+'"');
      if(FILLERS[p.field]){
        var res=FILLERS[p.field](p.value);
        sessionFields[p.field]=res; lastField=p.field; renderSession();
        logEvent('result', (DISPLAY[p.field]||p.field)+': '+(res && res.confirm ? 'needs confirmation' : 'set'));
      }
      setTimeout(step, i<pairs.length ? 300 : 40);
    }
    step();
  }

  function suggestRow(title, opts){
    var html='<div class="voice-sugg-row"><b>'+title+'</b><br>';
    opts.forEach(function(o){ html += '<span class="voice-sugg-opt" onclick="'+o.onpick.replace(/"/g,'&quot;')+'">'+o.label+'</span>'; });
    return html+'</div>';
  }
  function applyContributor(id,name){
    if(typeof window.selectContributor==='function'){ window.selectContributor(id, name); }
    else { var h=$('fContributor'), d=$('fContributorInput'); if(h) h.value=id; if(d) d.value=name; }
  }
  function usDate(iso){ var p=(iso||'').split('-'); return p.length===3 ? (p[1]+'/'+p[2]+'/'+p[0]) : iso; }

  // Suggestion picker (called from injected onclick)
  window.__voicePick = function(field, value, name){
    if(field==='contributor'){ applyContributor(value, name); markField('fContributorInput','ok'); }
    else { setSelect(field, value); markField(field,'ok'); }
  };

  // ── Speech recognition (shared engine in voice-core.js) ───────────────────
  var recording=false;
  var SESSION_MS=300000, sessionTimer=null, ending=false;   // 5-minute Converse session
  function setRecording(on){
    recording=on; var b=$('voiceMicBtn');
    if(b){ b.classList.toggle('recording', on); b.innerHTML = on ? '⏹' : '🎤'; b.title = on ? 'Stop listening' : 'Voice — fill the form by voice'; }
    // Mid-session the recorder may auto-stop (silence/cap). Unless we're ending,
    // resume so the conversation keeps listening for the full 5 minutes.
    if(!on && convo && convo.active && !ending){
      setTimeout(function(){ if(convo.active && !ending && !recording && speech){ try{ speech.start(); }catch(e){} } }, 300);
    }
    syncConverseButton();
  }
  function dbgEsc(s){ return String(s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];}); }
  function clearDebug(){ var el=$('voiceDebug'); if(el){ el.innerHTML=''; } }
  // Append a timestamped, colour-coded line to the debug console (visibility is
  // controlled by the 🐞 Debug toggle; logging is always recorded).
  function logEvent(name, detail){
    var el=$('voiceDebug'); if(!el) return;
    var d=(detail!==undefined && detail!=='') ? (typeof detail==='object'?JSON.stringify(detail):String(detail)) : '';
    var n=String(name||'');
    var cls = /error/i.test(n) ? 'vd-error' : /warn/i.test(n) ? 'vd-warn'
            : /fill|result|done/i.test(n) ? 'vd-fill' : '';
    el.innerHTML += '<span class="vd-row"><span class="vd-time">'+dbgEsc((new Date()).toLocaleTimeString())+'</span> '
      + '<span class="vd-tag '+cls+'">'+dbgEsc(n)+'</span> '+(d?dbgEsc(d):'')+'</span>';
    el.scrollTop = el.scrollHeight;
  }
  // Highlight the field currently being populated + show a status line.
  var _curEl=null;
  function setCurrentField(field){
    if(_curEl){ _curEl.classList.remove('voice-current'); _curEl=null; }
    var ind=$('voiceCurrent');
    if(!field){ if(ind){ ind.style.display='none'; ind.innerHTML=''; } return; }
    var id=(CLEAR_TARGETS[field] && CLEAR_TARGETS[field][0]) || null;
    if(id){ var dom=$(id); if(dom){ dom.classList.add('voice-current'); _curEl=dom; try{ dom.scrollIntoView({block:'center',behavior:'smooth'}); }catch(e){} } }
    if(ind){ ind.style.display='block'; ind.innerHTML='<span class="vc-dot"></span>🎯 Active field: <b>'+(DISPLAY[field]||field)+'</b>'; showPanel(); }
  }
  // OpenAI-powered voice (records audio → OpenAI transcription → normalized
  // command, applied by the same applyResult pipeline used before).
  var speech = (window.VoiceOpenAI) ? window.VoiceOpenAI.createRecorder({
    context:      'income',
    onState:      setRecording,
    onStatus:     function(m){ setStatus(m); },
    onDebug:      function(label, detail){ logEvent(label, detail); },
    onTranscript: function(t){ if(t) logEvent('transcript', t); },
    onStatusData: function(s){ if(s) logEvent('usage', 'voice used '+(s.voiceUsedSeconds||0)+'s of '+((s.voiceLimitMinutes||0)*60)+'s'); },
    onResult:     function(payload){ if(ending) return; if(convo.active){ convoTurn(payload); } else { applyPayload(payload); } },
    onDisabled:   function(st){ disableVoice(st && st.voiceEnabled === false
                    ? 'Voice is disabled by your administrator.'
                    : 'Voice limit reached. Please contact your administrator.'); }
  }) : null;
  function disableVoice(msg){
    var b=$('voiceMicBtn'); if(b){ b.disabled=true; b.title=msg||'Voice unavailable'; }
    var c=$('voiceConverseBtn'); if(c){ c.disabled=true; c.title=msg||'Voice unavailable'; }
    if(msg) setStatus('🔇 '+msg);
  }
  function startRecognition(){
    if(!speech){ setStatus('Voice support did not load (voice-openai.js). Please hard-refresh the page.'); return; }
    if(convo.active) endConvo();
    ending=false;   // plain Voice mic is a fresh start — allow results again
    if(recording) speech.stop(); else { clearDebug(); resetSession(); setCurrentField(null); logEvent('voice', 'session started'); speech.start(); }
  }

  /* ── Two-way voice conversation (guided, one field at a time) ─────────────
   * The assistant asks for each required field, routes the spoken answer to
   * THAT field only (so a contributor name can never land in Date), validates
   * the value before applying, offers a numbered menu when several values match
   * or after repeated misses, pauses listening while it updates ("Processing"),
   * and keeps the Converse button in sync with the real listening state.
   * Saving is always two steps: say "Save Now", then confirm. */
  var convo = { active:false, awaitingConfirm:false, expecting:null, step:-1, confirmField:null, focusField:null, awaitingClearNotes:false, fieldMisses:0,
                options:null, methodAsked:false, lastPrompt:'', cpi:0, cci:0, uci:0 };
  // ── Ordered conversation script ───────────────────────────────────────────
  // contributor → fund → date → method → (only if Method = Check: ref → note) → amount.
  // Amount is LAST so the conversation continues right up to "Save Now".
  function methodText(){ var e=$('fMethod'); return (e&&e.selectedIndex>0)?(e.options[e.selectedIndex].text||''):''; }
  function isCheck(){ return /check|cheque/i.test(methodText()); }
  var SCRIPT = [
    { f:'contributor' },
    { f:'fund' },
    { f:'date', confirm:true },   // the form defaults Date to today → confirm it, never silently skip
    { f:'method', confirm:true }, // Method may default on the form → confirm it, never skip to Ref No
    { f:'ref',  opt:true, when:isCheck },
    { f:'note', opt:true, when:isCheck },
    { f:'amount' }
  ];
  var REQUIRED = ['contributor','fund','date','method','amount'];   // ref + note are optional
  var FIELD_PROMPT = {
    contributor:'Who is the contributor?',
    fund:'What is the fund?',
    date:'What is the date?',
    method:'What is the payment method?',
    ref:'What is the reference number?',
    note:'What are the notes? Speak your note — I’ll add each phrase. Say “continue” when you’re done.',
    amount:'What is the amount?'
  };
  var LABEL = {contributor:'contributor',fund:'fund',date:'date',method:'payment method',ref:'reference number',note:'notes',amount:'amount'};
  function labelFor(f){ return LABEL[f]||f; }
  function capitalize(s){ s=String(s||''); return s.charAt(0).toUpperCase()+s.slice(1); }
  function stepApplicable(s){ return !s.when || !!s.when(); }
  function stepIndexOf(field){ for(var i=0;i<SCRIPT.length;i++){ if(SCRIPT[i].f===field) return i; } return -1; }
  function isSkip(t){ return /(^|\b)(skip|skip it|skip this|leave (it )?blank|not now|no thanks|move on|next field)(\b|$)/.test(t); }

  function ttsSupported(){ return typeof window!=='undefined' && 'speechSynthesis' in window; }

  // ── Self-echo guard ───────────────────────────────────────────────────────
  // Defensive backstop: if a transcription comes back closely matching what the
  // assistant just SAID (its own TTS leaking into the mic), drop it. The length
  // guard makes sure a short real answer ("Check", "Option 4") is never filtered.
  var recentSpoken=[];
  function _norm(s){ return String(s||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').replace(/\s+/g,' ').trim(); }
  function recordSpoken(t){ var n=_norm(t); if(n){ recentSpoken.push(n); if(recentSpoken.length>5) recentSpoken.shift(); } }
  function _diceWords(a,b){
    var A=_norm(a).split(' ').filter(Boolean), B=_norm(b).split(' ').filter(Boolean);
    if(!A.length||!B.length) return 0;
    var bag={}; B.forEach(function(w){ bag[w]=(bag[w]||0)+1; });
    var inter=0; A.forEach(function(w){ if(bag[w]>0){ inter++; bag[w]--; } });
    return (2*inter)/(A.length+B.length);
  }
  function looksLikeSelfEcho(transcript){
    var t=_norm(transcript); if(!t) return false;
    var tw=t.split(' ').length;
    for(var i=0;i<recentSpoken.length;i++){
      var sp=recentSpoken[i], sw=sp.split(' ').length;
      if(sw<3) continue;                                    // short prompts can't shadow real answers
      if(tw < Math.max(3, Math.floor(sw*0.5))) continue;    // a short answer is never a whole-prompt echo
      if(_diceWords(t, sp) >= 0.6) return true;             // high whole-phrase overlap → our own voice
    }
    return false;
  }

  // Speak a prompt. The mic is muted BEFORE any audio starts and is resumed ONLY
  // after speechSynthesis reports it has fully finished — so the system never
  // transcribes its own voice. A sequence token prevents a finishing prompt from
  // un-muting while a NEWER prompt is already speaking (the main self-capture bug).
  // Warm, female English voice — the same profile used by Meetings Converse.
  var _voice=null;
  function pickVoice(){
    if(!ttsSupported()) return null;
    var vs=window.speechSynthesis.getVoices()||[];
    var en=vs.filter(function(v){ return /^en/i.test(v.lang||''); });
    var prefer=['samantha','victoria','karen','moira','tessa','fiona','serena','allison','ava','susan','joanna',
                'zira','aria','jenny','jane','michelle','sonia','libby','google us english','female','woman'];
    for(var i=0;i<prefer.length;i++){ for(var j=0;j<en.length;j++){ if((en[j].name||'').toLowerCase().indexOf(prefer[i])>=0) return en[j]; } }
    var male=/(david|mark|alex|fred|daniel|george|james|paul|tom|guy|male|man)/i;
    for(var k=0;k<en.length;k++){ if(!male.test(en[k].name||'')) return en[k]; }
    return en[0]||vs[0]||null;
  }
  function ensureVoice(){ if(!_voice) _voice=pickVoice(); return _voice; }
  if('speechSynthesis' in window){ try{ window.speechSynthesis.onvoiceschanged=function(){ _voice=pickVoice(); }; }catch(e){} }

  var speakSeq=0;
  function speak(text, after){
    setStatus('🔊 '+text); logEvent('tts-start', text); recordSpoken(text);
    var mySeq=++speakSeq;
    if(speech && speech.setMuted) speech.setMuted(true);
    logEvent('mic', 'paused — system speaking');
    function resumeAfterSpeech(){
      if(mySeq!==speakSeq) return;                          // superseded by a newer prompt
      var tries=0;
      (function settle(){
        if(mySeq!==speakSeq) return;
        var busy = ttsSupported() && (window.speechSynthesis.speaking || window.speechSynthesis.pending);
        if(busy && tries++ < 300){ return setTimeout(settle, 100); }   // wait until truly done
        setTimeout(function(){                              // small cushion so the tail isn't captured
          if(mySeq!==speakSeq) return;
          logEvent('tts-end', '');
          if(speech && speech.setMuted) speech.setMuted(false);
          logEvent('mic', 'resumed — listening');
          if(after) after();
        }, 250);
      })();
    }
    if(!ttsSupported()){ setTimeout(resumeAfterSpeech, 400); return; }
    try{
      window.speechSynthesis.cancel();
      var u=new SpeechSynthesisUtterance(text);
      var v=ensureVoice(); if(v) u.voice=v;
      u.rate=0.98; u.pitch=1.12;          // calm, warm, friendly (same as Meetings)
      u.onend=resumeAfterSpeech; u.onerror=resumeAfterSpeech;
      window.speechSynthesis.speak(u);
      setTimeout(resumeAfterSpeech, Math.min(30000, 2500 + text.length*90));   // hard safety if onend never fires
    }catch(e){ resumeAfterSpeech(); }
  }
  // Speak a prompt and remember it (so "repeat" can replay the last QUESTION).
  // Pass ephemeral=true for acknowledgements that shouldn't be "repeated".
  function say(text, ephemeral){ if(!ephemeral) convo.lastPrompt=text; speak(text); }

  // ── Required-field presence (used to know what's still missing) ───────────
  function fieldPresent(field){
    if(field==='contributor'){ var h=$('fContributor'),d=$('fContributorInput'),g=$('fGuestName');
      return !!((h&&h.value)||(d&&d.value)||(g&&g.value&&g.value.trim())); }
    if(field==='fund'){ var e=$('fFund'); return !!(e&&e.value); }
    if(field==='amount'){ var e=$('fAmount'); return !!(e&&e.value && parseFloat(e.value)>0); }
    if(field==='date'){ var e=$('fDate'); return !!(e&&e.value); }
    if(field==='method'){ var e=$('fMethod'); return !!(e&&e.value); }
    if(field==='ref'){ var e=$('fRefNo'); return !!(e&&e.value && e.value.trim()); }
    if(field==='note'){ var e=$('fNote'); return !!(e&&e.value && e.value.trim()); }
    return false;
  }
  // Required fields still missing (used by Save Now). ref + note are never required.
  function nextMissing(){ for(var i=0;i<REQUIRED.length;i++){ if(!fieldPresent(REQUIRED[i])) return REQUIRED[i]; } return null; }
  function friendlyDate(iso){ var p=(iso||'').split('-'); if(p.length!==3) return iso; var d=new Date(+p[0],+p[1]-1,+p[2]); return isNaN(d.getTime())?iso:d.toLocaleDateString(undefined,{month:'long',day:'numeric',year:'numeric'}); }
  // Speak "today" / "tomorrow" / "yesterday" when the date is one of those, else the full date.
  function _isoToday(off){ var d=new Date(); d.setHours(0,0,0,0); d.setDate(d.getDate()+(off||0)); return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'); }
  function datePhrase(iso){ if(iso===_isoToday(0)) return 'today'; if(iso===_isoToday(1)) return 'tomorrow'; if(iso===_isoToday(-1)) return 'yesterday'; return friendlyDate(iso); }
  // "Keep the current value" vs "I want to change it" (used when confirming a pre-filled field).
  function isKeep(t){ return /(^|\b)(keep|keep it|leave it|leave as is|no change|don'?t change|that'?s? (fine|correct|right|good|okay|ok)|it'?s (fine|correct|right|good)|correct|no)(\b|$)/.test(t); }
  function wantsChange(t){ return /(^|\b)(yes|yeah|yep|change|change it|update|update it|edit|edit it|different|new date|wrong|incorrect)(\b|$)/.test(t); }
  function confirmFieldPrompt(f){
    if(f==='date'){ var e=$('fDate'); var ph=(e&&e.value)?datePhrase(e.value):'today';
      return 'The date is set to '+ph+'. Do you want to change it? Say a new date, or say “keep” to leave it.'; }
    if(f==='method'){ var m=$('fMethod'); var mt=(m&&m.selectedIndex>0)?(m.options[m.selectedIndex].text||''):'';
      return mt ? ('The payment method is set to '+mt+'. Do you want to change it? Say a new method, or say “keep” to leave it.')
                : FIELD_PROMPT.method; }
    return FIELD_PROMPT[f];
  }
  // STRICT confirmation — only an explicit yes/confirm/save counts.
  function confirmYes(t){
    var s=(t||'').toLowerCase();
    return /(^|\b)(yes|yeah|yep|yup|sure|confirm|confirmed|correct|affirmative|go ahead|do it|save)(\b|$)/.test(s);
  }

  // Rotating, natural prompts so the assistant never repeats the same line.
  var COMPLETE_PROMPTS = [
    'You can update any field, review the entry, or say “Save Now” when you’re ready to save.',
    'That’s everything I need. Update a field, say “review” to hear it back, or “Save Now” to save.',
    'All set. You can change a value, add a note, review the entry, or say “Save Now” when you’re ready.'
  ];
  var CLARIFY_COMPLETE = [
    'Sorry, I didn’t catch that. You can update a field, add a detail, say “review”, or “Save Now”.',
    'I didn’t get that. Tell me a value to change it, say “review” to hear what I have, or “Save Now” to save.',
    'Could you say that again? You can change a value, add a note, or say “Save Now”.'
  ];
  var UNCLEAR = ['Sorry, I didn’t catch that.','I didn’t quite get that.','Let’s try that once more.'];
  function completePrompt(){ var p=COMPLETE_PROMPTS[(convo.cpi||0)%COMPLETE_PROMPTS.length]; convo.cpi=(convo.cpi||0)+1; return p; }
  function clarifyComplete(){ var p=CLARIFY_COMPLETE[(convo.cci||0)%CLARIFY_COMPLETE.length]; convo.cci=(convo.cci||0)+1; return p; }
  function unclearLine(){ var p=UNCLEAR[(convo.uci||0)%UNCLEAR.length]; convo.uci=(convo.uci||0)+1; return p; }
  function reviewValues(){
    var out=[];
    var cv=($('fContributorInput')&&$('fContributorInput').value)||($('fGuestName')&&$('fGuestName').value);
    if(cv) out.push('contributor '+cv);
    var f=$('fFund'); if(f&&f.selectedIndex>0) out.push('fund '+f.options[f.selectedIndex].text);
    var a=$('fAmount'); if(a&&a.value) out.push('amount '+parseFloat(a.value));
    var d=$('fDate'); if(d&&d.value) out.push('date '+friendlyDate(d.value));
    var m=$('fMethod'); if(m&&m.selectedIndex>0) out.push('method '+m.options[m.selectedIndex].text);
    var r=$('fRefNo'); if(r&&r.value) out.push('reference '+r.value);
    return out.length ? ('Here’s what I have. '+out.join(', ')+'. You can update a field, add details, or say “Save Now” to save.')
                      : 'Nothing has been entered yet. '+(FIELD_PROMPT[nextMissing()]||'');
  }

  // ── Converse button + processing indicator (state always matches reality) ─
  function syncConverseButton(){
    var cb=$('voiceConverseBtn'); if(!cb) return;
    if(convo.active){ cb.classList.add('recording'); cb.innerHTML='⏹'; cb.title='End the conversation'; }
    else { cb.classList.remove('recording'); cb.innerHTML='🗣'; cb.title='Converse — guided voice conversation'; }
  }
  function showProcessing(on){
    var ind=$('voiceCurrent'); if(!ind) return;
    if(on){ ind.style.display='block'; ind.innerHTML='<span class="vc-dot"></span>Processing…'; showPanel(); }
  }

  // ── Numbered-option choosing (multiple matches / fallback menus) ──────────
  var ORD_ORDINAL={first:1,second:2,third:3,fourth:4,fifth:5,sixth:6,seventh:7,eighth:8,ninth:9,tenth:10};
  var ORD_CARDINAL={one:1,two:2,three:3,four:4,five:5,six:6,seven:7,eight:8,nine:9,ten:10};
  function parseOptionNum(t){
    var s=' '+String(t||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ')+' ';
    var m=s.match(/\b(\d{1,2})\b/); if(m) return parseInt(m[1],10);
    for(var k in ORD_ORDINAL){ if(new RegExp('\\b'+k+'\\b').test(s)) return ORD_ORDINAL[k]; }   // "the third one" → 3, not 1
    for(var k2 in ORD_CARDINAL){ if(new RegExp('\\b'+k2+'\\b').test(s)) return ORD_CARDINAL[k2]; }
    return null;
  }
  // Typo-tolerant option matching so a near-miss transcription ("dead"→"date",
  // "checked"→"Check") still maps to the right option (issue 1).
  function _lev(a,b){
    a=String(a); b=String(b); var m=a.length,n=b.length; if(!m) return n; if(!n) return m;
    var prev=[],cur=[],i,j; for(j=0;j<=n;j++) prev[j]=j;
    for(i=1;i<=m;i++){ cur[0]=i; for(j=1;j<=n;j++){ var cost=a.charAt(i-1)===b.charAt(j-1)?0:1; cur[j]=Math.min(cur[j-1]+1, prev[j]+1, prev[j-1]+cost); } for(j=0;j<=n;j++) prev[j]=cur[j]; }
    return prev[n];
  }
  function fuzzyOption(val, opts){
    var v=String(val||'').toLowerCase().trim(); if(!v) return null;
    var best=null, bestNorm=1;
    opts.forEach(function(o){
      var t=String(o.text||'').toLowerCase().trim(); if(!t) return;
      var d=_lev(v,t), norm=d/Math.max(v.length,t.length);
      // also reward a whole-word containment (e.g. "by check" ⊇ "check")
      if((' '+v+' ').indexOf(' '+t+' ')>=0 || (' '+t+' ').indexOf(' '+v+' ')>=0) norm=Math.min(norm, 0.2);
      if(norm<bestNorm){ bestNorm=norm; best=o; }
    });
    return (best && bestNorm<=0.34) ? best : null;
  }
  function optionsPhrase(){ if(!convo.options) return ''; return convo.options.map(function(o){ return o.num+'. '+o.text; }).join(', ')+'. Please say the number.'; }
  function showOptionChips(field){
    var sug=$('voiceSuggest'); if(!sug || !convo.options) return;
    var html='<div class="voice-sugg-row"><b>Choose '+labelFor(field)+':</b><br>';
    convo.options.forEach(function(o){ html+='<span class="voice-sugg-opt" onclick="window.__voiceConvoPick('+o.num+')">'+o.num+'. '+String(o.text).replace(/</g,'&lt;')+'</span>'; });
    sug.innerHTML=html+'</div>'; showPanel();
  }
  function clearOptionChips(){ var sug=$('voiceSuggest'); if(sug) sug.innerHTML=''; }
  window.__voiceConvoPick=function(num){
    if(!convo.active || !convo.options) return;
    var opt=null; for(var i=0;i<convo.options.length;i++){ if(convo.options[i].num===num){ opt=convo.options[i]; break; } }
    if(!opt) return;
    var apply=opt.apply, confirm=opt.confirm;
    convo.options=null; clearOptionChips(); convo.fieldMisses=0;
    convoProcess(apply, confirm);
  };
  // Present several matches and ASK the user to choose — never auto-pick (issue 5).
  function presentOptions(field, options, head){
    convo.options = options.slice(0,6).map(function(o,i){ return {num:i+1, text:o.text, apply:o.apply, confirm:o.confirm}; });
    setCurrentField(field); showOptionChips(field);
    say((head||'I found multiple matches.')+' '+optionsPhrase());
  }
  // After repeated misses on a dropdown field, read its options as a numbered menu
  // ONCE. convo.menuShown guards against re-reading the same list every turn.
  function presentSelectMenu(field){
    var id=(field==='fund')?'fFund':'fMethod';
    var opts=selectOptions(id).filter(function(o){ return o.value!=='' && o.value!=null; }).slice(0,6);
    if(!opts.length){ say(FIELD_PROMPT[field]); return; }
    convo.options = opts.map(function(o,i){ return {num:i+1, text:o.text,
      apply:(function(v){ return function(){ setSelect(id,v); markField(id,'ok'); }; })(o.value),
      confirm:(field==='fund'?'Fund ':'Method ')+o.text+'.'}; });
    convo.menuShown=true; convo.mni=0;
    setCurrentField(field); showOptionChips(field);
    say('Please choose the '+labelFor(field)+'. '+convo.options.map(function(o){ return 'Option '+o.num+', '+o.text+'.'; }).join(' ')+' Say the number, the name, or tap an option.');
  }
  // Rotating short nudges used AFTER the menu was already read, so we never just
  // repeat "Let me give you options." over and over.
  var MENU_NUDGE = [
    'Please say the number of the option you want.',
    'You can say the option number, or say the name. Which one is it?',
    'If none of those fit, say “skip” to leave it, or you can type it in.'
  ];
  function menuNudge(){ var p=MENU_NUDGE[(convo.mni||0)%MENU_NUDGE.length]; convo.mni=(convo.mni||0)+1; return p; }
  // Affirmative / negative detection for the "is this a new contributor?" question.
  function isAffirmative(t){ return /(^|\b)(yes|yeah|yep|yup|sure|correct|right|new|new one|new contributor|create)(\b|$)/.test(t); }
  function isNegative(t){ return /(^|\b)(no|nope|nah|not new|existing|try again|search again)(\b|$)/.test(t); }
  function enableGuestContributor(name){
    var gn=titleCase(name);
    if(typeof window.toggleGuestMode==='function'){ try{ window.toggleGuestMode(true); }catch(e){} }
    var g=$('fGuestName'); if(g){ g.value=gn; markField('fGuestName','confirm'); }
    return gn;
  }

  // ── Resolve a spoken value for a SPECIFIC field (validate before applying) ─
  // Returns {status:'ok'|'multiple'|'none', apply, confirm, options, head}.
  function resolveField(f, val){
    val=String(val==null?'':val).trim();
    if(f==='contributor'){
      var cm=matchContributor(val, contributorsCache||[]);
      if(cm.best && !cm.ambiguous && cm.best.score>=70){
        return {status:'ok', apply:function(){ applyContributor(cm.best.id, cm.best.fullName); markField('fContributorInput','ok'); }, confirm:'Contributor '+cm.best.fullName+'.'};
      }
      if(cm.candidates && cm.candidates.length>1){
        return {status:'multiple', head:'I found multiple matching contributors.',
          options: cm.candidates.map(function(c){ return {text:c.fullName,
            apply:function(){ applyContributor(c.id,c.fullName); markField('fContributorInput','ok'); },
            confirm:'Contributor '+c.fullName+'.'}; })};
      }
      if(cm.candidates && cm.candidates.length===1 && cm.best && cm.best.score>=70){
        return {status:'ok', apply:function(){ applyContributor(cm.best.id, cm.best.fullName); markField('fContributorInput','ok'); }, confirm:'Contributor '+cm.best.fullName+'.'};
      }
      // No confident match → don't silently create a guest. Report "none" with any
      // close matches so the conversation can offer the new-contributor flow.
      return {status:'none', closeMatches: (cm.candidates||[]).slice(0,6).map(function(c){ return {text:c.fullName,
        apply:function(){ applyContributor(c.id,c.fullName); markField('fContributorInput','ok'); },
        confirm:'Contributor '+c.fullName+'.'}; })};
    }
    if(f==='fund' || f==='method'){
      var id=(f==='fund')?'fFund':'fMethod';
      var all=selectOptions(id);
      var opts=all.filter(function(o){ return o.value!=='' && o.value!=null; });
      if(!opts.length) return {status:'none'};
      var mo=matchOption(val, all);
      if(mo.best && !mo.ambiguous && mo.best.score>=70){ return {status:'ok', apply:function(){ setSelect(id,mo.best.value); markField(id,'ok'); }, confirm:(f==='fund'?'Fund ':'Method ')+mo.best.text+'.'}; }
      if(mo.candidates && mo.candidates.length>1){ return {status:'multiple', head:'I found more than one matching '+(f==='fund'?'fund':'payment method')+'.',
        options: mo.candidates.map(function(c){ return {text:c.text, apply:(function(v){ return function(){ setSelect(id,v); markField(id,'ok'); }; })(c.value), confirm:(f==='fund'?'Fund ':'Method ')+c.text+'.'}; })}; }
      if(mo.candidates && mo.candidates.length===1){ var c0=mo.candidates[0]; return {status:'ok', apply:function(){ setSelect(id,c0.value); markField(id,'ok'); }, confirm:(f==='fund'?'Fund ':'Method ')+c0.text+'.'}; }
      var fz=fuzzyOption(val, opts);   // typo-tolerant last resort
      if(fz){ return {status:'ok', apply:function(){ setSelect(id,fz.value); markField(id,'ok'); }, confirm:(f==='fund'?'Fund ':'Method ')+fz.text+'.'}; }
      return {status:'none'};
    }
    if(f==='ref'){
      var rv=cleanRef(val);
      return {status:'ok', apply:function(){ var re=$('fRefNo'); if(re){ re.value=rv; re.dispatchEvent(new Event('input',{bubbles:true})); markField('fRefNo','ok'); } }, confirm:'Reference '+rv+'.'};
    }
    if(f==='note'){
      var nt=titleCase(val);
      return {status:'ok', apply:function(){ var ne=$('fNote'); if(ne){ ne.value=nt; ne.dispatchEvent(new Event('input',{bubbles:true})); markField('fNote','ok'); } }, confirm:'Note saved.'};
    }
    if(f==='amount'){
      var a=parseAmount(val);
      if(a!=null && a>0){ var disp=(a%1===0)?String(a):a.toFixed(2); return {status:'ok', apply:function(){ var ae=$('fAmount'); if(ae){ ae.value=a.toFixed(2); ae.dispatchEvent(new Event('input',{bubbles:true})); } markField('fAmount','ok'); }, confirm:'Amount set to '+disp+'.'}; }
      return {status:'none'};
    }
    if(f==='date'){
      var d=resolveNaturalDate(val); if(!d && /^\d{4}-\d{2}-\d{2}$/.test(val)) d=val; if(!d) d=parseDate(val);
      if(d){ var iso=d; return {status:'ok', apply:function(){ var de=$('fDate'); if(de){ de.value=iso; de.dispatchEvent(new Event('change',{bubbles:true})); } markField('fDate','ok'); }, confirm:'Date set to '+datePhrase(iso)+'.'}; }
      return {status:'none'};
    }
    return {status:'none'};
  }

  // Walk the SCRIPT forward from `fromIdx`, asking the next applicable, not-yet-
  // filled field. ref/note are skipped unless Method = Check. After the last step
  // the conversation gives the "complete" prompt and STAYS active (issue 4).
  function advanceFrom(fromIdx, prefix){
    var start=(typeof fromIdx==='number'?fromIdx:-1)+1;
    for(var i=start; i<SCRIPT.length; i++){
      var s=SCRIPT[i];
      if(!stepApplicable(s)) continue;        // ref/note only when Check
      if(fieldPresent(s.f)){
        // A confirm-step (Date) is pre-filled (defaulted to today) → ask to confirm
        // it rather than silently skipping it. Everything else: skip if filled.
        if(s.confirm){
          convo.step=i; convo.expecting=s.f; convo.confirmField=s.f; convo.fieldMisses=0; convo.options=null;
          convo.menuShown=false; convo.mni=0; convo.awaitingNewContributor=false; convo.collectingNewName=false;
          setCurrentField(s.f); logEvent('confirm-field', labelFor(s.f));
          say((prefix?prefix+' ':'')+confirmFieldPrompt(s.f));
          return;
        }
        continue;
      }
      convo.step=i; convo.expecting=s.f; convo.confirmField=null; convo.fieldMisses=0; convo.options=null;
      convo.menuShown=false; convo.mni=0; convo.awaitingNewContributor=false; convo.collectingNewName=false;
      setCurrentField(s.f); logEvent('next-question', labelFor(s.f));
      var extra = s.opt ? ' You can say “skip”.' : '';
      say((prefix?prefix+' ':'')+FIELD_PROMPT[s.f]+extra);
      return;
    }
    // Nothing left → complete; the conversation does NOT end.
    convo.step=SCRIPT.length; convo.expecting=null; convo.options=null; convo.confirmField=null; setCurrentField(null);
    convo.menuShown=false; convo.awaitingNewContributor=false; convo.collectingNewName=false;
    logEvent('next-question', 'all fields collected — awaiting update / review / Save Now');
    say((prefix?prefix+' ':'')+completePrompt(), true);
  }
  // After a field is filled or skipped, continue from the CURRENT step.
  // After a fill: in FIELD-FOCUS mode stay on the focused field (don't auto-advance);
  // otherwise continue the guided flow from the current step.
  var FOCUS_DONE = [
    'You can change it, click another field, or say “continue” to go on.',
    'Done. Click another field to jump there, or say “continue” to keep going.',
    'Set. Say a new value to change it, click another field, or say “continue”.'
  ];
  function focusDoneLine(){ var p=FOCUS_DONE[(convo.fdi||0)%FOCUS_DONE.length]; convo.fdi=(convo.fdi||0)+1; return p; }
  function respondAfterFill(confirmText){
    if(convo.focusField){
      convo.expecting=convo.focusField; convo.confirmField=null; convo.options=null; convo.fieldMisses=0;
      setCurrentField(convo.focusField);
      say((confirmText?confirmText+' ':'')+focusDoneLine(), true);
      return;
    }
    advanceFrom(convo.step, confirmText);
  }
  // Re-evaluate from the current step (used after "clear" / a declined save).
  function advance(prefix){ advanceFrom((typeof convo.step==='number'?convo.step:0)-1, prefix); }
  function reAsk(){ if(convo.options) say(optionsPhrase()); else if(convo.expecting) say(FIELD_PROMPT[convo.expecting]); else say(completePrompt(), true); }

  // Pause listening, show "Processing…", apply the field, then speak the result
  // (which resumes listening when it finishes) — prevents overlapping input (issue 6).
  function convoProcess(applyFn, confirmText){
    if(speech && speech.setMuted) speech.setMuted(true);   // stop listening while we update
    convo._ignoreFocusUntil = Date.now()+1200;             // ignore focus that our own apply() may trigger
    showProcessing(true); setStatus('⏳ Processing…'); logEvent('processing', confirmText||'');
    setTimeout(function(){
      try{ applyFn(); }catch(e){}
      logEvent('field-updated', confirmText||'');
      respondAfterFill(confirmText);
    }, 380);
  }

  // ── Field-focus mode ───────────────────────────────────────────────────────
  // Clicking / tabbing into a form field makes the assistant jump to that field,
  // ask its question, and route the next answer there — taking priority over the
  // guided flow until the user says "continue" or focuses a different field.
  var FOCUS_MAP = { fContributorInput:'contributor', fGuestName:'contributor', fFund:'fund',
                    fDate:'date', fMethod:'method', fAmount:'amount', fRefNo:'ref', fNote:'note' };
  var FIELD_SYNS = { contributor:['contributor','member','donor'], fund:['fund','offering'],
                     date:['date'], method:['method','payment'], ref:['reference','ref','reference number','check number'],
                     note:['note','notes','memo'], amount:['amount','value','total'] };
  function attachFieldFocus(){
    Object.keys(FOCUS_MAP).forEach(function(id){
      var el=$(id); if(!el || el._voiceFocusBound) return; el._voiceFocusBound=true;
      var handler=function(){ onFieldFocus(FOCUS_MAP[id]); };
      el.addEventListener('focus', handler);
      el.addEventListener('click', handler);
    });
  }
  function onFieldFocus(f){
    if(!convo.active) return;                                          // only while the assistant is running
    if(convo._ignoreFocusUntil && Date.now()<convo._ignoreFocusUntil) return;  // ignore programmatic focus
    if(convo.focusField===f && convo.expecting===f) return;           // already focused here
    focusField(f);
  }
  function focusField(f){
    speakSeq++; try{ if(window.speechSynthesis) window.speechSynthesis.cancel(); }catch(e){}  // interrupt any current prompt
    convo.focusField=f; convo.expecting=f; convo.step=stepIndexOf(f);
    convo.confirmField=null; convo.options=null; convo.menuShown=false; convo.mni=0; convo.fieldMisses=0;
    convo.awaitingConfirm=false; convo.awaitingNewContributor=false; convo.collectingNewName=false;
    clearOptionChips(); setCurrentField(f); logEvent('field-focus', labelFor(f)+' (selected by user)');
    var s=SCRIPT[stepIndexOf(f)];
    if(s && s.confirm && fieldPresent(f)){ convo.confirmField=f; say(confirmFieldPrompt(f)); }
    else say(FIELD_PROMPT[f]);
  }
  // Recognize an explicit request to move to a NAMED field ("go to amount", "the date field", "amount").
  function explicitFieldSwitch(t){
    var m=t.match(/^\s*(?:go to|switch to|change|update|edit|move to|the)\s+(.+?)\s*(?:field)?\s*$/);
    var bare=t.match(/^\s*(contributor|fund|date|method|reference|ref|note|notes|amount)\s*(?:field)?\s*$/);
    var phrase = m ? m[1] : (bare ? bare[1] : null);
    if(!phrase) return null;
    for(var f in FIELD_SYNS){ for(var i=0;i<FIELD_SYNS[f].length;i++){ if(phrase===FIELD_SYNS[f][i]) return f; } }
    return null;
  }

  // ── Notes: append-dictation helpers ────────────────────────────────────────
  function getNote(){ var e=$('fNote'); return e?String(e.value||''):''; }
  function setNote(v){ var e=$('fNote'); if(e){ e.value=v; try{ e.dispatchEvent(new Event('input',{bubbles:true})); }catch(x){} markField('fNote', v?'ok':''); } }
  function isPunctCommand(t){ return /^\s*(period|full stop|comma|question mark|exclamation( point| mark)?|dot)\s*$/i.test(t); }
  function punctFor(t){ t=String(t).toLowerCase().trim(); if(/^(period|full stop|dot)$/.test(t)) return '.'; if(t==='comma') return ','; if(t==='question mark') return '?'; if(/^exclamation/.test(t)) return '!'; return ''; }
  function appendPunct(p){ if(!p) return; var cur=getNote().replace(/\s+$/,'').replace(/[.,!?]+$/,''); setNote(cur+p); }
  function appendNoteWord(text){
    var add=String(text||'').trim(); if(!add) return;
    var cur=getNote().replace(/\s+$/,'');
    if(!cur){ setNote(add.charAt(0).toUpperCase()+add.slice(1)); return; }              // first word → capitalize
    var endsSentence=/[.!?]$/.test(cur);
    var chunk = endsSentence ? (add.charAt(0).toUpperCase()+add.slice(1)) : add;          // capitalize after a period
    setNote(cur+' '+chunk);
  }
  // While the active field is Notes, append spoken text to the existing note;
  // never overwrite. Returning WITHOUT speaking keeps the mic listening so the
  // user can keep dictating phrase after phrase.
  function handleNoteEntry(transcript, tl, fields){
    var got=Object.keys(fields).filter(function(k){ return fields[k]; });
    // Finish notes → continue the guided flow.
    if(!got.length && /\b(continue|resume|go on|keep going|carry on|next field|done here|that'?s all|that is all|no more|i'?m done|im done|finish notes?|end notes?|next)\b/.test(tl)){
      convo.focusField=null; advanceFrom(stepIndexOf('note'), 'Okay.'); return;
    }
    if(!got.length && isSkip(tl)){ convo.focusField=null; advanceFrom(stepIndexOf('note'), 'Notes skipped.'); return; }
    // Explicit switch to another field.
    if(!got.length){ var sw=explicitFieldSwitch(tl); if(sw && sw!=='note'){ focusField(sw); return; } }
    // Punctuation command ("period" → ".").
    if(!got.length && isPunctCommand(tl)){ appendPunct(punctFor(tl)); setStatus('📝 Notes: '+getNote()); logEvent('note', 'punctuation '+tl); return; }
    // Append spoken content to the existing note (never overwrite).
    var noteText=(fields.note ? String(fields.note) : transcript).trim();
    if(!noteText){ say('Go ahead with the note, or say “continue” when you’re done.', true); return; }
    appendNoteWord(noteText); setStatus('📝 Notes: '+getNote()); logEvent('note', 'appended “'+noteText+'”');
    // No spoken reply → keep listening for the next phrase.
  }

  function clearAllFields(){
    try{ if(typeof resetAllFields==='function') resetAllFields(); }catch(e){}
    setCurrentField(null); clearOptionChips();
    convo.awaitingConfirm=false; convo.fieldMisses=0; convo.options=null; convo.expecting=null; convo.step=-1; convo.confirmField=null; convo.focusField=null; convo.awaitingClearNotes=false;
    convo.menuShown=false; convo.mni=0; convo.awaitingNewContributor=false; convo.collectingNewName=false;
  }

  function startConvo(){
    if(!speech){ setStatus('Voice support did not load. Please hard-refresh.'); return; }
    if(speech.supported===false){ setStatus('Voice conversation needs Chrome, Edge, or Safari with a microphone.'); return; }
    if(convo.active){ endConvo('Conversation ended.'); return; }
    convo={active:true, awaitingConfirm:false, expecting:null, step:-1, confirmField:null, focusField:null, awaitingClearNotes:false, fieldMisses:0, options:null, methodAsked:false,
           menuShown:false, mni:0, awaitingNewContributor:false, collectingNewName:false, pendingName:'', contribCloseMatches:null,
           lastPrompt:'', cpi:0, cci:0, uci:0};
    showPanel(); clearDebug(); resetSession(); setCurrentField(null); clearOptionChips();
    ending=false; clearTimeout(sessionTimer);
    sessionTimer=setTimeout(function(){
      logEvent('system','Conversation Ended'); logEvent('system','Listening OFF'); logEvent('system','Converse Mode OFF');
      endConvo(null, { say:"The conversation session has reached the five-minute limit. You can start a new conversation whenever you're ready." });
    }, SESSION_MS);
    logEvent('system','Converse Started'); logEvent('system','Max Session Duration = 5 minutes');
    syncConverseButton();
    if(!recording) speech.start();
    advanceFrom(-1, 'Let’s add a contribution.');   // ask the first applicable, unfilled field
  }
  function endConvo(msg, opts){
    opts=opts||{};
    ending=true;                                  // block any recorder auto-restart / stray results
    var was=convo.active;
    convo.active=false; convo.awaitingConfirm=false; convo.expecting=null; convo.options=null; convo.focusField=null; convo.awaitingClearNotes=false;
    clearTimeout(sessionTimer);
    speakSeq++;                                   // void any pending TTS resume so the mic isn't reopened
    try{ if(window.speechSynthesis) window.speechSynthesis.cancel(); }catch(e){}
    if(was && speech) speech.stop();
    setCurrentField(null);                        // hide the active-field / listening indicator
    syncConverseButton(); clearOptionChips();     // Converse button re-enabled, Stop state cleared
    logEvent('conversation', 'ended — listening stopped');
    logEvent('system','Listening OFF'); logEvent('system','Converse Mode OFF');
    logEvent('system','Stop Button Hidden'); logEvent('system','Converse Button Enabled');
    logEvent('system','Pending Prompts Cleared'); logEvent('system','Conversation Fully Terminated');
    if(opts.say){
      setStatus(opts.say);
      if(ttsSupported()){ try{ var u=new SpeechSynthesisUtterance(opts.say); var v=ensureVoice(); if(v) u.voice=v; u.rate=0.98; u.pitch=1.12; window.speechSynthesis.speak(u); }catch(e){} }
    } else if(msg){ setStatus(msg); }
  }

  // Handle a spoken answer for the field we are currently expecting — the answer
  // is routed to THAT field only, so it can never populate the wrong one (issue 2).
  function handleExpected(transcript, fields){
    var f=convo.expecting;
    var tl=transcript.toLowerCase();

    // CONFIRMING a pre-filled field (e.g. Date defaulted to today).
    if(convo.confirmField===f){
      // A new value was given → use it.
      var cval = fields[f] ? String(fields[f]) : transcript;
      var cr = resolveField(f, cval);
      if(cr.status==='ok' && !isKeep(tl)){ convo.confirmField=null; convo.fieldMisses=0; convoProcess(cr.apply, cr.confirm); return; }
      // Keep it as-is (keep / no / that's fine / skip).
      if(isKeep(tl) || isSkip(tl)){
        convo.confirmField=null; convo.fieldMisses=0;
        var keepMsg = (f==='date') ? ('Keeping the date as '+datePhrase(($('fDate')||{}).value)+'.') : (capitalize(labelFor(f))+' kept.');
        convoProcess(function(){}, keepMsg); return;
      }
      // They want to change it but didn't give a value yet → ask plainly.
      if(wantsChange(tl)){ convo.confirmField=null; logEvent('next-question', labelFor(f)); say(FIELD_PROMPT[f]); return; }
      // Unclear → re-ask the confirm.
      say(confirmFieldPrompt(f)); return;
    }

    // NOTES — append-accumulate mode (speech is added to the existing note text).
    if(f==='note'){ handleNoteEntry(transcript, tl, fields); return; }

    // SKIP — leave this field blank and move on (issue 3). Optional fields skip
    // freely; a skipped required field still advances, but Save Now re-asks it.
    if(isSkip(tl) && !fields[f]){
      logEvent('skip', f);
      convoProcess(function(){}, capitalize(labelFor(f))+' skipped.');
      return;
    }

    var val = fields[f] ? String(fields[f]) : transcript;   // route the answer to THIS field only
    var r=resolveField(f, val);
    if(r.status==='ok'){ convo.fieldMisses=0; convoProcess(r.apply, r.confirm); return; }
    if(r.status==='multiple'){ presentOptions(f, r.options, r.head); return; }

    // ── status 'none' → field-specific re-ask ──
    convo.fieldMisses=(convo.fieldMisses||0)+1;

    if(f==='contributor'){
      convo.pendingName=val; convo.contribCloseMatches=r.closeMatches||[];
      if(convo.fieldMisses>=2){ convo.awaitingNewContributor=true; say('I couldn’t find an existing contributor. Is this a new contributor?'); return; }
      say('I couldn’t find ' + titleCase(val) + '. ' + FIELD_PROMPT.contributor); return;
    }
    if(f==='fund' || f==='method'){
      if(!convo.menuShown && convo.fieldMisses>=2){ presentSelectMenu(f); return; }
      if(convo.menuShown){ say(menuNudge()); return; }
      say(unclearLine()+' '+FIELD_PROMPT[f]); return;
    }
    // ref / note are free text — resolveField never returns 'none' for them, but
    // guard anyway: just move on.
    if(f==='ref' || f==='note'){ convoProcess(function(){}, capitalize(labelFor(f))+' noted.'); return; }
    // amount / date
    if(convo.fieldMisses>=3){ convo.fieldMisses=0; say('I’m having trouble with the '+labelFor(f)+'. You can also type it. '+FIELD_PROMPT[f]); return; }
    say(unclearLine()+' '+FIELD_PROMPT[f]);
  }

  // Main turn handler — guided; validates field mapping before updating.
  function convoTurn(payload){
    if(!convo.active) return;
    var fields=(payload&&payload.fields)||{};
    var transcript=(payload&&payload.transcript||'').trim();
    var tl=transcript.toLowerCase();
    setTranscript(transcript); logEvent('entities', JSON.stringify(fields));

    // Backstop: never act on the system's own voice if it leaked into the mic.
    if(looksLikeSelfEcho(transcript)){ logEvent('ignored', 'system speech echo — “'+transcript+'”'); return; }
    if(transcript) logEvent('user-speech', transcript);

    var got=Object.keys(fields).filter(function(k){ return fields[k]; });

    // ── Notes: clear-with-confirmation ──
    // Handle the confirmation reply first; and detect the clear-notes request
    // BEFORE the generic "cancel" handler so "cancel notes" isn't treated as quit.
    if(convo.awaitingClearNotes){
      if(!/\bno\b/.test(tl) && /\b(yes|yeah|yep|yup|sure|confirm|confirmed|clear|remove|delete|erase|do it|go ahead|please)\b/.test(tl)){
        convo.awaitingClearNotes=false; setNote(''); setStatus('📝 Notes cleared.'); say('Notes cleared. You can continue adding notes.', true); return;
      }
      if(/\b(no|nope|nah|cancel|keep|keep notes?|don'?t|do not|leave it|never mind)\b/.test(tl)){
        convo.awaitingClearNotes=false; say('Notes were not cleared.', true); return;
      }
      say('Do you want to clear the notes? Please say yes or no.'); return;
    }
    // "Clear notes" is a COMMAND even when the extractor labels it as note text
    // (fields.note = "clear notes") — otherwise it would be appended to the note.
    if(/\b(clear|remove|cancel|erase|delete)\s+(the\s+)?notes?\b/.test(tl)){
      convo.awaitingClearNotes=true; logEvent('note', 'clear requested — awaiting confirmation');
      say('Do you want to clear the notes?'); return;
    }

    // Stop / cancel works at any time and ends immediately (but never confuse the
    // notes punctuation "full stop" for a stop command).
    var isStop = !/\bfull stop\b/.test(tl) && (
        /^\s*(stop|stop it|cancel|never ?mind|quit|exit|end|done)\s*[.!]?$/.test(tl)
        || /\b(stop|end|cancel|exit) (the )?conversation\b/.test(tl)
        || /\bstop listening\b/.test(tl));
    if(isStop){
      logEvent('system','User Command = Stop'); logEvent('system','Conversation Terminated');
      endConvo(null, { say:'Okay. The conversation has been stopped.' }); return;
    }

    // ── New-contributor sub-flow ──
    // We asked "Is this a new contributor?" → handle yes / no.
    if(convo.awaitingNewContributor){
      if(isAffirmative(tl)){ convo.awaitingNewContributor=false; convo.collectingNewName=true;
        say('Please tell me the new contributor’s name.'); return; }
      if(isNegative(tl)){ convo.awaitingNewContributor=false; convo.fieldMisses=0;
        if(convo.contribCloseMatches && convo.contribCloseMatches.length){ presentOptions('contributor', convo.contribCloseMatches, 'Okay. Here are the closest matches.'); return; }
        say('Okay, let’s try again. '+FIELD_PROMPT.contributor); return; }
      say('Sorry, is this a new contributor? Please say yes or no.'); return;
    }
    // We asked for the new contributor's name → take the whole answer as the name.
    if(convo.collectingNewName){
      var newName=(fields.contributor ? String(fields.contributor) : transcript).trim();
      if(!newName){ say('I didn’t catch the name. Please tell me the new contributor’s name.'); return; }
      convo.collectingNewName=false; convo.fieldMisses=0;
      convoProcess(function(){ enableGuestContributor(newName); }, 'Added '+titleCase(newName)+' as a new contributor.');
      return;
    }

    // Conversation commands (only when no field value was extracted).
    if(!got.length){
      if(/\b(please wait|hold on|not ready|one moment|give me a (sec|second|minute)|pause)\b/.test(tl)){ convo.awaitingConfirm=false; say('Okay, I’ll wait. Say the value when you’re ready, or “Save Now” to save.', true); return; }
      if(/\b(repeat|say again|come again|pardon|what was that)\b/.test(tl)){ if(convo.lastPrompt) speak(convo.lastPrompt); else reAsk(); return; }
      if(/\b(review|read back|read it back|what do i have|what have i got)\b/.test(tl)){ say(reviewValues(), true); return; }
      if(/\b(clear form|reset form|reset fields|clear everything|start over|start again)\b/.test(tl)){ clearAllFields(); convo.step=-1; convo.methodAsked=false; advanceFrom(-1, 'Cleared. Let’s start over.'); return; }
    }

    // Step 2: confirming a save the user already initiated.
    if(convo.awaitingConfirm){
      if(!got.length && confirmYes(tl)){ doSaveViaVoice(); return; }
      convo.awaitingConfirm=false; say('Okay, I won’t save yet.', true); advance(); return;
    }

    // Step 1: explicit save request (two-step — confirm next).
    if(/\bsave now\b/.test(tl) || /\b(i'?m |i am )?ready to save\b/.test(tl)){
      var miss=nextMissing();
      if(miss){ convo.step=stepIndexOf(miss); convo.expecting=miss; convo.fieldMisses=0; convo.options=null; setCurrentField(miss); say('Before saving, I still need the '+labelFor(miss)+'. '+FIELD_PROMPT[miss]); return; }
      convo.awaitingConfirm=true; say('Do you want to save this contribution?'); return;
    }

    // A numbered menu is showing → a spoken number selects that option, EVEN IF
    // the speech engine mislabeled the bare number as another field (e.g. it
    // returns "4" as an amount). We check the number first, before field routing,
    // so "four" / "4" / "option 4" reliably pick the option.
    if(convo.options){
      var pick=parseOptionNum(tl);
      if(pick!=null){
        var opt=null; for(var i=0;i<convo.options.length;i++){ if(convo.options[i].num===pick){ opt=convo.options[i]; break; } }
        if(opt){ var apply=opt.apply, confirm=opt.confirm; convo.options=null; clearOptionChips(); convo.fieldMisses=0; convoProcess(apply, confirm); return; }
        say('That number isn’t on the list. '+optionsPhrase(), true); return;
      }
      // No number → maybe they said the option NAME; let the expecting handler resolve it.
    }

    // ── Field-focus mode: the manually-selected field takes priority ──
    if(convo.focusField && !got.length){
      // Exit focus mode and resume the guided flow.
      if(/\b(continue|resume|go on|keep going|carry on|next field|done here|exit focus)\b/.test(tl)){
        var fc=convo.focusField; convo.focusField=null; logEvent('field-focus', 'exited — resuming guided flow');
        advanceFrom(stepIndexOf(fc), 'Okay, continuing.'); return;
      }
      // Explicit switch to a different named field.
      var nf=explicitFieldSwitch(tl); if(nf && nf!==convo.focusField){ focusField(nf); return; }
    }

    // We are expecting a specific field → route the answer there only.
    if(convo.expecting){ handleExpected(transcript, fields); return; }

    // Form complete → the user is changing or adding something.
    if(got.length){
      var f0=got[0]; var r0=resolveField(f0, String(fields[f0]));
      if(r0.status==='multiple'){ presentOptions(f0, r0.options, r0.head); return; }
      if(r0.status==='ok'){ convoProcess(r0.apply, r0.confirm); return; }
    }
    say(clarifyComplete(), true);
  }

  function doSaveViaVoice(){
    convo.awaitingConfirm=false;
    var nm=nextMissing();
    if(nm){ convo.step=stepIndexOf(nm); convo.expecting=nm; setCurrentField(nm); say('I still need the '+labelFor(nm)+'. '+FIELD_PROMPT[nm]); return; }
    say('Saving the contribution.', true);
    setTimeout(function(){
      try{
        var ret = (typeof window.saveIncome==='function') ? window.saveIncome() : null;
        if(ret && typeof ret.then==='function'){
          ret.then(function(){ speak('Contribution saved successfully.', function(){ endConvo('✅ Saved.'); }); })
             .catch(function(){ speak('Sorry, I could not save the contribution. Please review the form and try again.', function(){ endConvo(); }); });
        } else {
          var b=$('saveBtn'); if(!ret && b) b.click();
          speak('Contribution saved successfully.', function(){ endConvo('✅ Saved.'); });
        }
      }catch(e){ speak('Sorry, I could not save the contribution.', function(){ endConvo(); }); }
    }, 350);
  }

  // ── Init ──────────────────────────────────────────────────────────────────
  function init(){
    if(!document.getElementById('formCard')) return;
    injectUI();
    var mic=$('voiceMicBtn'), guide=$('voiceGuideBtn'), conv=$('voiceConverseBtn');
    if(mic){ if(speech && !speech.supported){ mic.title='Voice input not supported in this browser'; }
      mic.addEventListener('click', startRecognition); }
    if(conv){ if(speech && !speech.supported){ conv.disabled=true; conv.title='Voice not supported in this browser'; }
      conv.addEventListener('click', function(){ if(convo.active) endConvo('Conversation ended.'); else startConvo(); }); }
    if(guide){ guide.addEventListener('click', function(){
      var g=$('voiceGuide'); if(g){ g.classList.toggle('hidden'); showPanel(); } }); }
    var dbgBtn=$('voiceDebugBtn');
    if(dbgBtn){ dbgBtn.addEventListener('click', function(){
      var el=$('voiceDebug'), hd=$('voiceDebugHead'); if(!el) return;
      var show = (el.style.display==='none' || !el.style.display);
      el.style.display = show ? 'block' : 'none';
      if(hd) hd.style.display = show ? 'block' : 'none';
      dbgBtn.classList.toggle('recording', show);
      if(show){ showPanel(); populateDebugHead(); }
    }); }
    function populateDebugHead(){
      var hd=$('voiceDebugHead'); if(!hd || !(window.VoiceOpenAI && window.VoiceOpenAI.getVoiceStatus)) return;
      window.VoiceOpenAI.getVoiceStatus().then(function(v){
        hd.innerHTML = '🔌 <b>'+(v.apiType||'OpenAI Voice API')+'</b> · transcribe: <b>'+(v.transcribeModel||'?')
          + '</b> · command: <b>'+(v.commandModel||'?')+'</b><br>endpoint: '+(v.endpoint||((v.apiBase||'?')+'/audio/transcriptions'))
          + ' · ' + (v.enabled ? 'enabled' : '<span style="color:#c62828">DISABLED — set OPENAI_API_KEY</span>');
      }).catch(function(){});
    }
    // Disable the button up-front if this church is over its voice limit or
    // voice has been turned off by a Service Admin.
    if(window.VoiceOpenAI){ window.VoiceOpenAI.getStatus().then(function(s){
      if(s && s.voiceAvailable === false){
        disableVoice(s.voiceEnabled === false
          ? 'Voice is disabled by your administrator.'
          : 'Voice limit reached. Please contact your administrator.');
      }
    }); }
    loadContributors();
    attachFieldFocus();   // click / tab into a field → assistant focuses it (when active)
  }
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
