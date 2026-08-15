/* ============================================================================
 * expense-voice.js — Voice input for the Expense entry form.
 *
 * Mirrors income-voice.js. Lets users dictate an expense ("Expense Office
 * Supplies, Fund General, Date May thirty-first twenty twenty-six, Method Bank
 * Transfer, Amount one hundred") and auto-populates the Expense (purpose)
 * combobox, Fund/Method selects, date and amount. Uses the browser Web Speech
 * API (Chrome/Edge desktop, Android Chrome, iOS Safari 14.5+).
 *
 * Self-contained: injects its own UI + styles, fetches the purpose list, reads
 * the Fund/Method <select> options from the DOM, and reuses the page's global
 * selectExpense() to drive the purpose combobox. The parsing logic is identical
 * to the income module's (unit-tested, 41 cases).
 * ========================================================================== */
(function () {
  'use strict';

  // ── Shared parser (voice-core.js) ─────────────────────────────────────────
  var VC = window.VoiceCore || {};
  var parseAmount = VC.parseAmount,
      parseDate   = VC.parseDate,
      matchOption = VC.matchOption,
      titleCase   = VC.titleCase;

  // Field keywords for the expense form (passed to VoiceCore.segment).
  var KEYWORDS = {
    expense:['expense','purpose','category'],
    fund:['fund','source'],
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
  function markField(id, state){ var el=$(id); if(!el) return; el.classList.remove('voice-ok','voice-confirm','voice-fail'); if(state) el.classList.add('voice-'+state); }

  var purposesCache=null;
  function loadPurposes(){
    return fetch('/api/expense/purposes').then(function(r){return r.ok?r.json():[];})
      .then(function(data){ var arr=Array.isArray(data)?data:(data&&data.data)||[];
        purposesCache = arr.map(function(p){ return {value:String(p.id), text:p.purposeName||p.name||''}; }); return purposesCache; })
      .catch(function(){ purposesCache=[]; return purposesCache; });
  }

  // ── UI injection ──────────────────────────────────────────────────────────
  function injectStyles(){
    if($('voiceStyles')) return;
    var css = ''
      + '.btn-voice-mic{display:inline-flex;align-items:center;gap:6px;padding:7px 13px;border-radius:7px;border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-size:13px;font-weight:600;cursor:pointer;}'
      + '.btn-voice-mic:hover{background:#673147;color:#fff;border-color:#673147;}'
      + '.btn-voice-mic.recording{background:#e53935;color:#fff;border-color:#e53935;animation:voicePulse 1s infinite;}'
      + '.btn-voice-mic[disabled]{opacity:.5;cursor:not-allowed;}'
      + '.btn-voice-guide{padding:7px 11px;border-radius:7px;border:1px solid #d7c9d0;background:#fff;color:#673147;font-size:12px;font-weight:600;cursor:pointer;}'
      + '@keyframes voicePulse{50%{opacity:.65;}}'
      + '#voicePanel{margin:0 0 14px;border:1px solid #e6dde2;border-radius:9px;background:#fbf7f9;padding:12px 14px;font-size:13px;color:#444;}'
      + '#voicePanel.hidden{display:none;}'
      + '#voiceStatus{font-weight:600;color:#673147;}'
      + '#voiceTranscript{margin-top:6px;font-style:italic;color:#555;}'
      + '#voiceResults{margin-top:8px;display:flex;flex-wrap:wrap;gap:6px;}'
      + '.voice-chip{font-size:12px;padding:3px 9px;border-radius:999px;background:#eef1f6;color:#41506b;}'
      + '.voice-chip.ok{background:#e7f5ec;color:#1e7a44;} .voice-chip.confirm{background:#fff6e5;color:#9a6b00;} .voice-chip.fail{background:#fdeaea;color:#b42424;}'
      + '#voiceSuggest{margin-top:10px;display:flex;flex-direction:column;gap:8px;}'
      + '.voice-sugg-row{font-size:12.5px;} .voice-sugg-row b{color:#673147;}'
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
      + '#voiceDebug .vd-row{display:block;} #voiceDebug .vd-time{color:#6b7a90;} #voiceDebug .vd-tag{color:#7fd1ff;font-weight:700;}'
      + '#voiceDebug .vd-warn{color:#ffcf6b;} #voiceDebug .vd-error{color:#ff8e8e;} #voiceDebug .vd-fill{color:#9ff0b0;}'
      + '#voiceDebugHead{margin-top:10px;display:none;font-size:11.5px;color:#41506b;background:#eef1f6;border:1px solid #dfe4ee;border-radius:8px 8px 0 0;padding:8px 12px;line-height:1.6;}'
      + '#voiceDebugHead b{color:#5c6bc0;}'
      + '.btn-voice-debug{margin-left:6px;padding:7px 11px;border-radius:7px;border:1px solid #c7c0d7;background:#fff;color:#41506b;font-size:12px;font-weight:600;cursor:pointer;}'
      // ── Help (?) icon + help modal ──
      + '.btn-voice-help{margin-left:6px;width:30px;height:30px;border-radius:50%;border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-weight:700;font-size:13px;cursor:pointer;line-height:1;padding:0;}'
      + '.btn-voice-help:hover{background:#673147;color:#fff;}'
      + '.vh-modal-ov{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;display:flex;align-items:center;justify-content:center;padding:20px;}'
      + '.vh-modal{background:#fff;border-radius:14px;max-width:660px;width:100%;max-height:85vh;overflow:auto;padding:20px 22px;box-shadow:0 10px 40px rgba(0,0,0,.25);}'
      + '.vh-modal h3{margin:0 0 6px;color:#673147;font-size:16.5px;}'
      + '.vh-modal h4{margin:16px 0 4px;color:#673147;font-size:13.5px;}'
      + '.vh-modal p,.vh-modal li{font-size:13px;color:#444;line-height:1.55;margin:4px 0;}'
      + '.vh-modal ul{margin:4px 0 8px;padding-left:20px;}'
      + '.vh-modal .vh-ex{background:#fbf7f9;border:1px solid #eadfe6;border-radius:8px;padding:8px 10px;margin:6px 0;font-size:12.5px;color:#555;font-style:italic;}'
      + '.vh-modal-x{float:right;border:none;background:none;font-size:16px;cursor:pointer;color:#999;padding:2px 6px;}'
      + '.vh-modal-x:hover{color:#673147;}';
    var st=document.createElement('style'); st.id='voiceStyles'; st.textContent=css; document.head.appendChild(st);
  }

  // ── Help (?) dialog — the three AI input methods on this page ─────────────
  function showHelpModal(title, bodyHtml){
    var old=document.querySelector('.vh-modal-ov'); if(old && old.parentNode) old.parentNode.removeChild(old);
    var ov=document.createElement('div'); ov.className='vh-modal-ov';
    ov.innerHTML='<div class="vh-modal" role="dialog" aria-modal="true" aria-label="'+title+'">'
      +'<button class="vh-modal-x" type="button" aria-label="Close">✕</button>'
      +'<h3>'+title+'</h3>'+bodyHtml+'</div>';
    function close(){ if(ov.parentNode) ov.parentNode.removeChild(ov); document.removeEventListener('keydown',escK); }
    function escK(e){ if(e.key==='Escape') close(); }
    ov.addEventListener('click', function(e){ if(e.target===ov) close(); });
    ov.querySelector('.vh-modal-x').addEventListener('click', close);
    document.addEventListener('keydown', escK);
    document.body.appendChild(ov);
  }
  function methodsHelpHtml(){
    return '<p>There are three ways to use AI on this page. Voice and Converse fill the Add Expense form for you; nothing is saved until you review and click Save.</p>'
      +'<h4>🔎 Type</h4>'
      +'<p>Use the <b>AI Search box at the top of the page</b> to type requests in natural language — navigate ("Go to Expense Report"), find features ("Where do I see spending by fund?"), or ask how-to questions ("How do I record a check payment?"). Form fields can of course also be typed directly.</p>'
      +'<div class="vh-ex">"Show me the expense report" · "How do I add an expense category?"</div>'
      +'<h4>🎤 Voice</h4>'
      +'<p>Click the 🎤 microphone on the form and speak — one field at a time (just say the value and the AI finds the right field), or everything in one sentence naming each field.</p>'
      +'<div class="vh-ex">"Expense Office Supplies, Fund General, Date May thirty-first twenty twenty-six, Method Bank Transfer, Amount one hundred"</div>'
      +'<ul><li>Pause briefly between fields; amounts and dates work in words or digits ("one hundred", "5 31 2026").</li>'
      +'<li>For reference/check numbers with hyphens, say "dash": "Check Number TXN dash 2026 dash 001" → TXN-2026-001.</li>'
      +'<li>Fix mistakes by voice: <b>"Clear Expense"</b>, <b>"Clear Amount"</b>, <b>"Reset Field"</b>, or <b>"Clear Form"</b>.</li>'
      +'<li>Auto-filled fields are outlined — <b>amber</b> means confirm before saving; if several categories or funds match, pick from the suggestions shown.</li>'
      +'<li>Requires microphone permission; speak at a normal pace in a quiet environment.</li></ul>'
      +'<h4>🗣 Converse</h4>'
      +'<p>Click 🗣 for a guided conversation — the assistant asks for the expense category, fund, date, method, and amount one at a time, and asks follow-up questions when it needs more information (e.g. to choose between two matching categories).</p>'
      +'<ul><li>Answer naturally; say <b>"Skip"</b> to leave a field blank or <b>"Stop"</b> to end the conversation.</li>'
      +'<li>Great for hands-free entry — each answer is confirmed before it is applied.</li></ul>'
      +'<h4>Limitations</h4>'
      +'<ul><li>English is supported; categories, funds, and methods are matched against your church’s own lists.</li>'
      +'<li>Voice and Converse need a working microphone and speech service; typing always works.</li>'
      +'<li>The AI never saves a record without your review — you always click Save yourself.</li></ul>'
      +'<p style="color:#888;">Tip: the ❔ Guide button shows a quick field-by-field voice command reference.</p>';
  }

  function guideHTML(){
    return ''
      + '<div id="voiceGuide" class="hidden">'
      + '<div style="font-weight:700;color:#673147;margin-bottom:4px;">Voice Command Guide</div>'
      + 'Click the mic, then speak <b>one field at a time</b> — you can just say the value and it finds the right field. Pause between fields. Examples:<br>'
      + '<code>Office Supplies</code> … <code>General Fund</code> … <code>Bank Transfer</code> … <code>one hundred</code> … <code>May thirty-first twenty twenty-six</code><br>'
      + 'Or say it all at once, naming each field:<br>'
      + '<code>Expense Office Supplies, Fund General, Date May thirty-first twenty twenty-six, Method Bank Transfer, Amount one hundred</code>'
      + '<ul style="margin:8px 0 0 18px;padding:0;">'
      + '<li><b>Expense</b> — the expense category: <code>Expense Office Supplies</code> or <code>Purpose Utilities</code>.</li>'
      + '<li><b>Fund</b> — the fund/source name: <code>Fund General</code> or <code>Fund Building</code>.</li>'
      + '<li><b>Date</b> — many formats: <code>Date May thirty-first twenty twenty-six</code>, <code>Date five thirty-one two zero two six</code>, or <code>Date 5 31 2026</code>.</li>'
      + '<li><b>Method</b> — payment method: <code>Method Bank Transfer</code>, <code>Method Cash</code>, <code>Method Check</code>.</li>'
      + '<li><b>Amount</b> — words or digits: <code>Amount one hundred</code> or <code>Amount 100</code> → 100.00.</li>'
      + '<li><b>Reference / Check No</b> — <code>Reference Number 12345</code>, <code>Ref No 987654</code>, <code>Check Number 4567</code>, <code>Check No 7890</code>, <code>Cheque No …</code>. Letters work too: <code>Reference Number ABC123</code>; for hyphens say “dash”: <code>Check Number TXN dash 2026 dash 001</code> → TXN-2026-001.</li>'
      + '<li><b>Note</b>: <code>Note quarterly bill</code>.</li>'
      + '<li><b>Clear / Reset</b> — fix mistakes by voice: <code>Clear Expense</code>, <code>Clear Amount</code>, <code>Clear Date</code>, <code>Clear Method</code> (clears one field) · <code>Reset Field</code> (resets the field you changed last) · <code>Reset Fields</code> / <code>Clear Form</code> / <code>Reset Form</code> (clears the whole form).</li>'
      + '</ul>'
      + '<div style="margin-top:6px;color:#888;">Auto-filled fields are outlined; amber means please confirm before saving. If several categories or funds match, pick the right one from the suggestions.</div>'
      + '</div>';
  }

  function injectUI(){
    injectStyles();
    var card = $('expenseFormCard');
    var grid = card && card.querySelector('.form-grid');
    if(card && grid && !$('voiceMicBtn')){
      var bar=document.createElement('div');
      bar.style.cssText='display:flex;justify-content:flex-end;align-items:center;gap:6px;margin-bottom:10px;';
      bar.innerHTML='<button type="button" id="voiceMicBtn" class="btn-voice-mic" title="Voice — fill the form by voice" aria-label="Voice">🎤</button>'
        + '<button type="button" id="voiceConverseBtn" class="btn-voice-mic" title="Converse — guided voice conversation" aria-label="Converse">🗣</button>'
        + '<button type="button" id="voiceGuideBtn" class="btn-voice-guide" title="Guide — voice command reference" aria-label="Guide">❔</button>'
        + '<button type="button" id="voiceHelpBtn" class="btn-voice-help" title="Help — Type, Voice &amp; Converse input methods" aria-label="Help">?</button>'
        + '<button type="button" id="voiceDebugBtn" class="btn-voice-debug" title="Debug — show the voice debug console" aria-label="Debug">🐞</button>';
      grid.parentNode.insertBefore(bar, grid);
      var panel=document.createElement('div'); panel.id='voicePanel'; panel.className='hidden';
      panel.innerHTML = '<div id="voiceStatus"></div><div id="voiceTranscript"></div>'
        + '<div id="voiceCurrent"></div>'
        + '<div id="voiceResults"></div><div id="voiceSuggest"></div>'
        + '<div id="voiceDebugHead"></div>'
        + '<div id="voiceDebug"></div>'
        + guideHTML();
      grid.parentNode.insertBefore(panel, grid);
    }
  }

  function showPanel(){ var p=$('voicePanel'); if(p) p.classList.remove('hidden'); }
  function setStatus(html){ var s=$('voiceStatus'); if(s) s.innerHTML=html; showPanel(); }
  function setTranscript(t){ var s=$('voiceTranscript'); if(s) s.textContent = t?('You said: “'+t+'”'):''; }
  function chip(label,state){ return '<span class="voice-chip '+(state||'')+'">'+label+'</span>'; }
  function usDate(iso){ var p=(iso||'').split('-'); return p.length===3 ? (p[1]+'/'+p[2]+'/'+p[0]) : iso; }

  function applyExpense(id,name){
    if(typeof window.selectExpense==='function'){ window.selectExpense(id, name); }
    else { var h=$('fExpense'), d=$('fExpenseInput'); if(h) h.value=id; if(d) d.value=name; }
  }
  window.__voicePickExp = function(field, value, name){
    if(field==='expense'){ applyExpense(value, name); markField('fExpenseInput','ok'); }
    else { setSelect(field, value); markField(field,'ok'); }
  };
  function suggestRow(title, opts){
    var html='<div class="voice-sugg-row"><b>'+title+'</b><br>';
    opts.forEach(function(o){ html += '<span class="voice-sugg-opt" onclick="'+o.onpick.replace(/"/g,'&quot;')+'">'+o.label+'</span>'; });
    return html+'</div>';
  }

  // ── Field fillers (apply one value to the DOM, return {chip, suggest, confirm}) ──
  function fillExpense(value){
    var po=matchOption(value, purposesCache||[]);
    if(po.best && !po.ambiguous){ applyExpense(po.best.value, po.best.text); markField('fExpenseInput','ok'); return {chip:chip('Expense: '+po.best.text,'ok')}; }
    if(po.candidates.length){ markField('fExpenseInput','confirm'); return {confirm:true, chip:chip('Expense: choose below','confirm'),
      suggest:suggestRow('Expense — "'+value+'"', po.candidates.map(function(c){ return {label:c.text, onpick:"window.__voicePickExp('expense','"+c.value+"',"+JSON.stringify(c.text)+")"}; }))}; }
    markField('fExpenseInput','fail'); return {confirm:true, chip:chip('Expense: not found','fail')};
  }
  function fillFund(value){
    var all=selectOptions('fFund');
    var opts=all.filter(function(o){ return o.value!=='' && o.value!=null; });
    var fo=matchOption(value, all);
    if(typeof logEvent==='function') logEvent('fund-match', {said:value, options:opts.length, best:(fo.best?fo.best.text:null), score:(fo.best?fo.best.score:0), ambiguous:!!fo.ambiguous});
    if(!opts.length){ markField('fFund','fail'); return {confirm:true, chip:chip('Fund list isn’t loaded yet — wait a second and try again','fail')}; }
    if(fo.best && !fo.ambiguous){ setSelect('fFund',fo.best.value); markField('fFund','ok'); return {chip:chip('Fund selected: '+fo.best.text,'ok')}; }
    if(fo.candidates.length){ markField('fFund','confirm'); return {confirm:true, chip:chip('Fund: choose below','confirm'),
      suggest:suggestRow('Fund — "'+value+'"', fo.candidates.map(function(c){ return {label:c.text, onpick:"window.__voicePickExp('fFund','"+c.value+"')"}; }))}; }
    markField('fFund','fail'); return {confirm:true, chip:chip('Fund: not found in the list','fail')};
  }
  function fillMethod(value){
    var mo=matchOption(value, selectOptions('fMethod'));
    if(mo.best && !mo.ambiguous){ setSelect('fMethod',mo.best.value); markField('fMethod','ok'); return {chip:chip('Method: '+mo.best.text,'ok')}; }
    if(mo.candidates.length){ markField('fMethod','confirm'); return {confirm:true, chip:chip('Method: choose below','confirm'),
      suggest:suggestRow('Method — "'+value+'"', mo.candidates.map(function(c){ return {label:c.text, onpick:"window.__voicePickExp('fMethod','"+c.value+"')"}; }))}; }
    markField('fMethod','fail'); return {confirm:true, chip:chip('Method: not found','fail')};
  }
  function fillDate(value){
    var s=String(value==null?'':value).trim();
    var d = resolveNaturalDate(s);
    if(!d && /^\d{4}-\d{2}-\d{2}$/.test(s)) d = s;
    if(!d) d = parseDate(s);
    if(d){ var de=$('fDate'); if(de){ de.value=d; de.dispatchEvent(new Event('change',{bubbles:true})); } markField('fDate','ok'); return {chip:chip('Date: '+usDate(d),'ok')}; }
    markField('fDate','fail'); return {confirm:true, chip:chip('Date: not understood','fail')};
  }
  function resolveNaturalDate(text){
    if(!text) return null;
    var t=String(text).toLowerCase().trim();
    var today=new Date(); today.setHours(0,0,0,0);
    function iso(d){ return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'); }
    function addDays(n){ var d=new Date(today); d.setDate(d.getDate()+n); return d; }
    if(/\btoday\b/.test(t) || /\b(tonight|now)\b/.test(t)) return iso(today);
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
      return iso(addDays((wd-cur+7)%7));
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
  var FILLERS={expense:fillExpense,fund:fillFund,method:fillMethod,date:fillDate,amount:fillAmount,ref:fillRef,note:fillNote};
  var FIELD_LABEL={expense:'expense',fund:'fund',method:'method',date:'date',amount:'amount',ref:'reference',note:'note'};

  // A "bare" field-name utterance (e.g. just "expense") so the NEXT utterance fills it.
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
    var po=matchOption(text, purposesCache||[]); if(po.best) consider('expense', po.best.score/100);
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
  var DISPLAY={expense:'Expense',fund:'Fund',method:'Method',date:'Date',amount:'Amount',ref:'Reference',note:'Note'};
  // Field-name synonyms accepted in a "Clear <field>" command.
  var FIELD_WORDS={
    expense:['expense','expenses','purpose','category'],
    fund:['fund','source'],
    date:['date','dated'],
    method:['method','payment','mode'],
    amount:['amount','value','total','money'],
    ref:['reference','ref'],
    note:['note','notes','memo','remark']
  };
  // DOM ids cleared for each logical field (the first is the highlighted/primary one).
  var CLEAR_TARGETS={
    expense:['fExpenseInput','fExpense'], fund:['fFund'], method:['fMethod'], date:['fDate'], amount:['fAmount'], ref:['fRefNo'], note:['fNote']
  };
  var lastField=null;
  function flash(id){ var el=$(id); if(!el) return; el.classList.add('voice-cleared'); setTimeout(function(){ el.classList.remove('voice-cleared'); }, 1300); }
  function clearDom(id){ var el=$(id); if(!el) return; el.value=''; try{ el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('input',{bubbles:true})); }catch(e){} }
  function clearFieldKey(field){
    if(!CLEAR_TARGETS[field]) return false;
    CLEAR_TARGETS[field].forEach(function(id){ clearDom(id); markField(id,''); });
    flash(CLEAR_TARGETS[field][0]);
    delete sessionFields[field]; if(lastField===field) lastField=null;
    renderSession(); return true;
  }
  function resetAllFields(){
    Object.keys(CLEAR_TARGETS).forEach(function(field){ CLEAR_TARGETS[field].forEach(function(id){ clearDom(id); markField(id,''); flash(id); }); });
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
    setStatus('Heard a clear command but not which field. Try “Clear Amount”, “Clear Expense”, or “Reset Form”.');
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
  var ORDER=['expense','fund','date','method','amount','ref','note'];
  function applyPayload(payload){
    payload = payload || {};
    var transcript = payload.transcript || '';
    setTranscript(transcript);
    var fields = payload.fields || {};
    logEvent('entities', JSON.stringify(fields));

    if(payload.command){ logEvent('command', payload.command);
      if(handleCommand(payload.command)){ setCurrentField(null); return; } }

    var pairs=[];
    ORDER.forEach(function(f){ if(fields[f]) pairs.push({field:f, value:String(fields[f])}); });
    Object.keys(fields).forEach(function(f){ if(fields[f] && ORDER.indexOf(f)<0) pairs.push({field:f, value:String(fields[f])}); });
    if(pairs.length){ applyPairs(pairs); return; }

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
    setStatus('Didn’t catch a value in that phrase. Say e.g. “Expense Office Supplies”, then pause; then “Amount 100”, then pause.');
  }

  function applyPairs(pairs){
    var i=0;
    function step(){
      if(i>=pairs.length){
        setCurrentField(null);
        var confirm = renderSession();
        setStatus(confirm
          ? '⚠️ Review the highlighted fields, fix anything in amber/red, then Save.'
          : '✅ Field filled. Keep speaking the next field, or tap Stop.');
        return;
      }
      var p=pairs[i++];
      setCurrentField(p.field);
      logEvent('fill', (DISPLAY[p.field]||p.field)+' = "'+p.value+'"');
      if(FILLERS[p.field]){
        var res = FILLERS[p.field](p.value);
        sessionFields[p.field]=res; lastField=p.field;
        renderSession();
        logEvent('result', (DISPLAY[p.field]||p.field)+': '+(res && res.confirm ? 'needs confirmation' : 'set'));
      }
      setTimeout(step, i<pairs.length ? 300 : 40);
    }
    step();
  }

  // ── Voice engine (shared OpenAI recorder) ─────────────────────────────────
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
  function logEvent(name, detail){
    var el=$('voiceDebug'); if(!el) return;
    var d=(detail!==undefined && detail!=='') ? (typeof detail==='object'?JSON.stringify(detail):String(detail)) : '';
    var n=String(name||'');
    var cls = /error/i.test(n) ? 'vd-error' : /warn/i.test(n) ? 'vd-warn' : /fill|result|done/i.test(n) ? 'vd-fill' : '';
    el.innerHTML += '<span class="vd-row"><span class="vd-time">'+dbgEsc((new Date()).toLocaleTimeString())+'</span> '
      + '<span class="vd-tag '+cls+'">'+dbgEsc(n)+'</span> '+(d?dbgEsc(d):'')+'</span>';
    el.scrollTop = el.scrollHeight;
  }
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
    context:      'expense',
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
   * THAT field only (so an expense category can never land in Date), validates
   * the value before applying, offers a numbered menu when several values match
   * or after repeated misses, pauses listening while it updates ("Processing"),
   * and keeps the Converse button in sync with the real listening state.
   * Saving is always two steps: say "Save Now", then confirm. */
  var convo = { active:false, awaitingConfirm:false, expecting:null, step:-1, confirmField:null, focusField:null, awaitingClearNotes:false, fieldMisses:0,
                options:null, methodAsked:false, lastPrompt:'', cpi:0, cci:0, uci:0 };
  // ── Ordered conversation script ───────────────────────────────────────────
  // expense → fund → date → method → (only if Method = Check: ref → note) → amount.
  function methodText(){ var e=$('fMethod'); return (e&&e.selectedIndex>0)?(e.options[e.selectedIndex].text||''):''; }
  function isCheck(){ return /check|cheque/i.test(methodText()); }
  var SCRIPT = [
    { f:'expense' },
    { f:'fund' },
    { f:'date', confirm:true },   // the form defaults Date to today → confirm it, never silently skip
    { f:'method', confirm:true }, // Method may default on the form → confirm it, never skip to Ref No
    { f:'ref',  opt:true, when:isCheck },
    { f:'note', opt:true, when:isCheck },
    { f:'amount' }
  ];
  var REQUIRED = ['expense','fund','date','method','amount'];   // ref + note are optional
  var FIELD_PROMPT = {
    expense:'What is the expense category?',
    fund:'What is the fund?',
    date:'What is the date?',
    method:'What is the payment method?',
    ref:'What is the reference number?',
    note:'What are the notes? Speak your note — I’ll add each phrase. Say “continue” when you’re done.',
    amount:'What is the amount?'
  };
  var LABEL = {expense:'expense category',fund:'fund',date:'date',method:'payment method',ref:'reference number',note:'notes',amount:'amount'};
  function labelFor(f){ return LABEL[f]||f; }
  function capitalize(s){ s=String(s||''); return s.charAt(0).toUpperCase()+s.slice(1); }
  function stepApplicable(s){ return !s.when || !!s.when(); }
  function stepIndexOf(field){ for(var i=0;i<SCRIPT.length;i++){ if(SCRIPT[i].f===field) return i; } return -1; }
  function isSkip(t){ return /(^|\b)(skip|skip it|skip this|leave (it )?blank|not now|no thanks|move on|next field)(\b|$)/.test(t); }

  function ttsSupported(){ return typeof window!=='undefined' && 'speechSynthesis' in window; }

  // ── Self-echo guard ───────────────────────────────────────────────────────
  // Drop a transcription that closely matches what the assistant just SAID (its
  // own TTS leaking into the mic). The length guard keeps short answers safe.
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
      if(sw<3) continue;
      if(tw < Math.max(3, Math.floor(sw*0.5))) continue;
      if(_diceWords(t, sp) >= 0.6) return true;
    }
    return false;
  }

  // Mic muted BEFORE any audio; resumed ONLY after speechSynthesis is fully idle.
  // The sequence token stops a finishing prompt from un-muting while a newer one
  // is still speaking (the main self-capture bug).
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
      if(mySeq!==speakSeq) return;
      var tries=0;
      (function settle(){
        if(mySeq!==speakSeq) return;
        var busy = ttsSupported() && (window.speechSynthesis.speaking || window.speechSynthesis.pending);
        if(busy && tries++ < 300){ return setTimeout(settle, 100); }
        setTimeout(function(){
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
      setTimeout(resumeAfterSpeech, Math.min(30000, 2500 + text.length*90));
    }catch(e){ resumeAfterSpeech(); }
  }
  function say(text, ephemeral){ if(!ephemeral) convo.lastPrompt=text; speak(text); }
  function confirmYes(t){
    var s=(t||'').toLowerCase();
    return /(^|\b)(yes|yeah|yep|yup|sure|confirm|confirmed|correct|affirmative|go ahead|do it|save)(\b|$)/.test(s);
  }

  function fieldPresent(field){
    if(field==='expense'){ var i=$('fExpenseInput'),h=$('fExpense'); return !!((i&&i.value&&i.value.trim())||(h&&h.value)); }
    if(field==='fund'){ var e=$('fFund'); return !!(e&&e.value); }
    if(field==='amount'){ var e=$('fAmount'); return !!(e&&e.value && parseFloat(e.value)>0); }
    if(field==='date'){ var e=$('fDate'); return !!(e&&e.value); }
    if(field==='method'){ var e=$('fMethod'); return !!(e&&e.value); }
    if(field==='ref'){ var e=$('fRefNo'); return !!(e&&e.value && e.value.trim()); }
    if(field==='note'){ var e=$('fNote'); return !!(e&&e.value && e.value.trim()); }
    return false;
  }
  function nextMissing(){ for(var i=0;i<REQUIRED.length;i++){ if(!fieldPresent(REQUIRED[i])) return REQUIRED[i]; } return null; }
  function friendlyDate(iso){ var p=(iso||'').split('-'); if(p.length!==3) return iso; var d=new Date(+p[0],+p[1]-1,+p[2]); return isNaN(d.getTime())?iso:d.toLocaleDateString(undefined,{month:'long',day:'numeric',year:'numeric'}); }
  function _isoToday(off){ var d=new Date(); d.setHours(0,0,0,0); d.setDate(d.getDate()+(off||0)); return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'); }
  function datePhrase(iso){ if(iso===_isoToday(0)) return 'today'; if(iso===_isoToday(1)) return 'tomorrow'; if(iso===_isoToday(-1)) return 'yesterday'; return friendlyDate(iso); }
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
    var parts=[];
    var ex=$('fExpenseInput'); if(ex&&ex.value) parts.push('expense '+ex.value);
    var fd=$('fFund'); if(fd&&fd.selectedIndex>0) parts.push('fund '+fd.options[fd.selectedIndex].text);
    var am=$('fAmount'); if(am&&am.value) parts.push('amount '+am.value);
    var dt=$('fDate'); if(dt&&dt.value) parts.push('date '+friendlyDate(dt.value));
    var mt=$('fMethod'); if(mt&&mt.selectedIndex>0) parts.push('method '+mt.options[mt.selectedIndex].text);
    var rf=$('fRefNo'); if(rf&&rf.value) parts.push('reference '+rf.value);
    if(!parts.length) return 'Nothing has been entered yet. '+(FIELD_PROMPT[nextMissing()]||'');
    return 'Here’s what I have. '+parts.join(', ')+'. You can update a field, add details, or say “Save Now” to save.';
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
  // Typo-tolerant option matching so a near-miss transcription still maps (issue 1).
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
  // After repeated misses on a list field, read its options as a numbered menu.
  function presentSelectMenu(field){
    var list;
    if(field==='expense'){ list=(purposesCache||[]).filter(function(o){ return o.value; }); }
    else { var id=(field==='fund')?'fFund':'fMethod'; list=selectOptions(id).filter(function(o){ return o.value!=='' && o.value!=null; }); }
    list=list.slice(0,6);
    if(!list.length){ say(FIELD_PROMPT[field]); return; }
    convo.options = list.map(function(o,i){ return {num:i+1, text:o.text,
      apply:(function(v,tx){ return function(){
        if(field==='expense'){ applyExpense(v,tx); markField('fExpenseInput','ok'); }
        else { var id2=(field==='fund')?'fFund':'fMethod'; setSelect(id2,v); markField(id2,'ok'); }
      }; })(o.value,o.text),
      confirm:(field==='expense'?'Expense ':field==='fund'?'Fund ':'Method ')+o.text+'.'}; });
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

  // ── Resolve a spoken value for a SPECIFIC field (validate before applying) ─
  function resolveField(f, val){
    val=String(val==null?'':val).trim();
    if(f==='expense'){
      var po=matchOption(val, purposesCache||[]);
      if(po.best && !po.ambiguous && po.best.score>=70){ return {status:'ok', apply:function(){ applyExpense(po.best.value, po.best.text); markField('fExpenseInput','ok'); }, confirm:'Expense '+po.best.text+'.'}; }
      if(po.candidates && po.candidates.length>1){ return {status:'multiple', head:'I found multiple matching expense categories.',
        options: po.candidates.map(function(c){ return {text:c.text, apply:(function(v,tx){ return function(){ applyExpense(v,tx); markField('fExpenseInput','ok'); }; })(c.value,c.text), confirm:'Expense '+c.text+'.'}; })}; }
      if(po.candidates && po.candidates.length===1){ var c0=po.candidates[0]; return {status:'ok', apply:function(){ applyExpense(c0.value,c0.text); markField('fExpenseInput','ok'); }, confirm:'Expense '+c0.text+'.'}; }
      return {status:'none'};
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
      if(mo.candidates && mo.candidates.length===1){ var c1=mo.candidates[0]; return {status:'ok', apply:function(){ setSelect(id,c1.value); markField(id,'ok'); }, confirm:(f==='fund'?'Fund ':'Method ')+c1.text+'.'}; }
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

  // Decide what to ask next: required fields first, then optional method, then
  // the natural "complete" prompt. Only complete when EVERY required field is filled.
  // Walk the SCRIPT forward; ref/note only when Method = Check; amount last; after
  // the last step give the "complete" prompt and STAY active (issue 4).
  function advanceFrom(fromIdx, prefix){
    var start=(typeof fromIdx==='number'?fromIdx:-1)+1;
    for(var i=start; i<SCRIPT.length; i++){
      var s=SCRIPT[i];
      if(!stepApplicable(s)) continue;
      if(fieldPresent(s.f)){
        if(s.confirm){   // Date pre-filled to today → confirm it, never silently skip
          convo.step=i; convo.expecting=s.f; convo.confirmField=s.f; convo.fieldMisses=0; convo.options=null; convo.menuShown=false; convo.mni=0;
          setCurrentField(s.f); logEvent('confirm-field', labelFor(s.f));
          say((prefix?prefix+' ':'')+confirmFieldPrompt(s.f));
          return;
        }
        continue;
      }
      convo.step=i; convo.expecting=s.f; convo.confirmField=null; convo.fieldMisses=0; convo.options=null;
      convo.menuShown=false; convo.mni=0;
      setCurrentField(s.f); logEvent('next-question', labelFor(s.f));
      var extra = s.opt ? ' You can say “skip”.' : '';
      say((prefix?prefix+' ':'')+FIELD_PROMPT[s.f]+extra);
      return;
    }
    convo.step=SCRIPT.length; convo.expecting=null; convo.options=null; convo.confirmField=null; setCurrentField(null); convo.menuShown=false;
    logEvent('next-question', 'all fields collected — awaiting update / review / Save Now');
    say((prefix?prefix+' ':'')+completePrompt(), true);
  }
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
  // ── Field-focus mode ───────────────────────────────────────────────────────
  // Clicking / tabbing into a form field makes the assistant jump to that field.
  var FOCUS_MAP = { fExpenseInput:'expense', fExpense:'expense', fFund:'fund',
                    fDate:'date', fMethod:'method', fAmount:'amount', fRefNo:'ref', fNote:'note' };
  var FIELD_SYNS = { expense:['expense','purpose','category'], fund:['fund','source'],
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
    if(!convo.active) return;
    if(convo._ignoreFocusUntil && Date.now()<convo._ignoreFocusUntil) return;
    if(convo.focusField===f && convo.expecting===f) return;
    focusField(f);
  }
  function focusField(f){
    speakSeq++; try{ if(window.speechSynthesis) window.speechSynthesis.cancel(); }catch(e){}
    convo.focusField=f; convo.expecting=f; convo.step=stepIndexOf(f);
    convo.confirmField=null; convo.options=null; convo.menuShown=false; convo.mni=0; convo.fieldMisses=0; convo.awaitingConfirm=false;
    clearOptionChips(); setCurrentField(f); logEvent('field-focus', labelFor(f)+' (selected by user)');
    var s=SCRIPT[stepIndexOf(f)];
    if(s && s.confirm && fieldPresent(f)){ convo.confirmField=f; say(confirmFieldPrompt(f)); }
    else say(FIELD_PROMPT[f]);
  }
  function explicitFieldSwitch(t){
    var m=t.match(/^\s*(?:go to|switch to|change|update|edit|move to|the)\s+(.+?)\s*(?:field)?\s*$/);
    var bare=t.match(/^\s*(expense|category|purpose|fund|date|method|reference|ref|note|notes|amount)\s*(?:field)?\s*$/);
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
    if(!cur){ setNote(add.charAt(0).toUpperCase()+add.slice(1)); return; }
    var endsSentence=/[.!?]$/.test(cur);
    var chunk = endsSentence ? (add.charAt(0).toUpperCase()+add.slice(1)) : add;
    setNote(cur+' '+chunk);
  }
  function handleNoteEntry(transcript, tl, fields){
    var got=Object.keys(fields).filter(function(k){ return fields[k]; });
    if(!got.length && /\b(continue|resume|go on|keep going|carry on|next field|done here|that'?s all|that is all|no more|i'?m done|im done|finish notes?|end notes?|next)\b/.test(tl)){
      convo.focusField=null; advanceFrom(stepIndexOf('note'), 'Okay.'); return;
    }
    if(!got.length && isSkip(tl)){ convo.focusField=null; advanceFrom(stepIndexOf('note'), 'Notes skipped.'); return; }
    if(!got.length){ var sw=explicitFieldSwitch(tl); if(sw && sw!=='note'){ focusField(sw); return; } }
    if(!got.length && isPunctCommand(tl)){ appendPunct(punctFor(tl)); setStatus('📝 Notes: '+getNote()); logEvent('note', 'punctuation '+tl); return; }
    var noteText=(fields.note ? String(fields.note) : transcript).trim();
    if(!noteText){ say('Go ahead with the note, or say “continue” when you’re done.', true); return; }
    appendNoteWord(noteText); setStatus('📝 Notes: '+getNote()); logEvent('note', 'appended “'+noteText+'”');
  }

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

  function clearAllFields(){
    try{ if(typeof resetAllFields==='function') resetAllFields(); }catch(e){}
    setCurrentField(null); clearOptionChips();
    convo.awaitingConfirm=false; convo.fieldMisses=0; convo.options=null; convo.expecting=null; convo.step=-1; convo.confirmField=null; convo.focusField=null; convo.awaitingClearNotes=false;
    convo.menuShown=false; convo.mni=0;
  }

  function startConvo(){
    if(!speech){ setStatus('Voice support did not load. Please hard-refresh.'); return; }
    if(speech.supported===false){ setStatus('Voice conversation needs Chrome, Edge, or Safari with a microphone.'); return; }
    if(convo.active){ endConvo('Conversation ended.'); return; }
    convo={active:true, awaitingConfirm:false, expecting:null, step:-1, confirmField:null, focusField:null, awaitingClearNotes:false, fieldMisses:0, options:null, methodAsked:false, menuShown:false, mni:0, lastPrompt:'', cpi:0, cci:0, uci:0};
    showPanel(); clearDebug(); resetSession(); setCurrentField(null); clearOptionChips();
    ending=false; clearTimeout(sessionTimer);
    sessionTimer=setTimeout(function(){
      logEvent('system','Conversation Ended'); logEvent('system','Listening OFF'); logEvent('system','Converse Mode OFF');
      endConvo(null, { say:"The conversation session has reached the five-minute limit. You can start a new conversation whenever you're ready." });
    }, SESSION_MS);
    logEvent('system','Converse Started'); logEvent('system','Max Session Duration = 5 minutes');
    syncConverseButton();
    if(!recording) speech.start();
    advanceFrom(-1, 'Let’s add an expense.');   // ask the first applicable, unfilled field
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

  // Handle a spoken answer for the field we are currently expecting — routed to
  // THAT field only, so it can never populate the wrong one (issue 2).
  function handleExpected(transcript, fields){
    var f=convo.expecting;
    var tl=transcript.toLowerCase();

    // CONFIRMING a pre-filled field (e.g. Date defaulted to today).
    if(convo.confirmField===f){
      var cval = fields[f] ? String(fields[f]) : transcript;
      var cr = resolveField(f, cval);
      if(cr.status==='ok' && !isKeep(tl)){ convo.confirmField=null; convo.fieldMisses=0; convoProcess(cr.apply, cr.confirm); return; }
      if(isKeep(tl) || isSkip(tl)){
        convo.confirmField=null; convo.fieldMisses=0;
        var keepMsg = (f==='date') ? ('Keeping the date as '+datePhrase(($('fDate')||{}).value)+'.') : (capitalize(labelFor(f))+' kept.');
        convoProcess(function(){}, keepMsg); return;
      }
      if(wantsChange(tl)){ convo.confirmField=null; logEvent('next-question', labelFor(f)); say(FIELD_PROMPT[f]); return; }
      say(confirmFieldPrompt(f)); return;
    }

    // NOTES — append-accumulate mode (speech is added to the existing note text).
    if(f==='note'){ handleNoteEntry(transcript, tl, fields); return; }

    // SKIP — leave this field blank and move on (issue 3).
    if(isSkip(tl) && !fields[f]){
      logEvent('skip', f);
      convoProcess(function(){}, capitalize(labelFor(f))+' skipped.');
      return;
    }

    var val = fields[f] ? String(fields[f]) : transcript;
    var r=resolveField(f, val);
    if(r.status==='ok'){ convo.fieldMisses=0; convoProcess(r.apply, r.confirm); return; }
    if(r.status==='multiple'){ presentOptions(f, r.options, r.head); return; }
    convo.fieldMisses=(convo.fieldMisses||0)+1;
    if(f==='expense'||f==='fund'||f==='method'){
      if(!convo.menuShown && convo.fieldMisses>=2){ presentSelectMenu(f); return; }
      if(convo.menuShown){ say(menuNudge()); return; }
      say(unclearLine()+' '+FIELD_PROMPT[f]); return;
    }
    if(f==='ref' || f==='note'){ convoProcess(function(){}, capitalize(labelFor(f))+' noted.'); return; }
    if(convo.fieldMisses>=3){ convo.fieldMisses=0; say('I’m having trouble with the '+labelFor(f)+'. You can also type it. '+FIELD_PROMPT[f]); return; }
    say(unclearLine()+' '+FIELD_PROMPT[f]);
  }

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

    // ── Notes: clear-with-confirmation (precedes "cancel" so "cancel notes" isn't a quit) ──
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

    var isStop = !/\bfull stop\b/.test(tl) && (
        /^\s*(stop|stop it|cancel|never ?mind|quit|exit|end|done)\s*[.!]?$/.test(tl)
        || /\b(stop|end|cancel|exit) (the )?conversation\b/.test(tl)
        || /\bstop listening\b/.test(tl));
    if(isStop){
      logEvent('system','User Command = Stop'); logEvent('system','Conversation Terminated');
      endConvo(null, { say:'Okay. The conversation has been stopped.' }); return;
    }

    if(!got.length){
      if(/\b(please wait|hold on|not ready|one moment|give me a (sec|second|minute)|pause)\b/.test(tl)){ convo.awaitingConfirm=false; say('Okay, I’ll wait. Say the value when you’re ready, or “Save Now” to save.', true); return; }
      if(/\b(repeat|say again|come again|pardon|what was that)\b/.test(tl)){ if(convo.lastPrompt) speak(convo.lastPrompt); else reAsk(); return; }
      if(/\b(review|read back|read it back|what do i have|what have i got)\b/.test(tl)){ say(reviewValues(), true); return; }
      if(/\b(clear form|reset form|reset fields|clear everything|start over|start again)\b/.test(tl)){ clearAllFields(); convo.step=-1; convo.methodAsked=false; advanceFrom(-1, 'Cleared. Let’s start over.'); return; }
    }

    if(convo.awaitingConfirm){
      if(!got.length && confirmYes(tl)){ doSaveViaVoice(); return; }
      convo.awaitingConfirm=false; say('Okay, I won’t save yet.', true); advance(); return;
    }

    if(/\bsave now\b/.test(tl) || /\b(i'?m |i am )?ready to save\b/.test(tl)){
      var miss=nextMissing();
      if(miss){ convo.step=stepIndexOf(miss); convo.expecting=miss; convo.fieldMisses=0; convo.options=null; setCurrentField(miss); say('Before saving, I still need the '+labelFor(miss)+'. '+FIELD_PROMPT[miss]); return; }
      convo.awaitingConfirm=true; say('Do you want to save this expense?'); return;
    }

    // A numbered menu is showing → a spoken number selects that option, EVEN IF
    // the speech engine mislabeled the bare number as another field (e.g. amount).
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
      if(/\b(continue|resume|go on|keep going|carry on|next field|done here|exit focus)\b/.test(tl)){
        var fc=convo.focusField; convo.focusField=null; logEvent('field-focus', 'exited — resuming guided flow');
        advanceFrom(stepIndexOf(fc), 'Okay, continuing.'); return;
      }
      var nf=explicitFieldSwitch(tl); if(nf && nf!==convo.focusField){ focusField(nf); return; }
    }

    if(convo.expecting){ handleExpected(transcript, fields); return; }

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
    say('Saving the expense.', true);
    setTimeout(function(){
      try{
        var ret = (typeof window.submitForm==='function') ? window.submitForm() : null;
        if(ret && typeof ret.then==='function'){
          ret.then(function(){ speak('Expense saved successfully.', function(){ endConvo('✅ Saved.'); }); })
             .catch(function(){ speak('Sorry, I could not save the expense. Please review the form and try again.', function(){ endConvo(); }); });
        } else {
          var b=$('saveBtn'); if(!ret && b) b.click();
          speak('Expense saved successfully.', function(){ endConvo('✅ Saved.'); });
        }
      }catch(e){ speak('Sorry, I could not save the expense.', function(){ endConvo(); }); }
    }, 350);
  }

  // ── Init ──────────────────────────────────────────────────────────────────
  function init(){
    if(!$('expenseFormCard')) return;
    injectUI();
    var mic=$('voiceMicBtn'), guide=$('voiceGuideBtn'), conv=$('voiceConverseBtn');
    if(mic){ if(speech && !speech.supported) mic.title='Voice input not supported in this browser'; mic.addEventListener('click', startRecognition); }
    if(conv){ if(speech && !speech.supported){ conv.disabled=true; conv.title='Voice not supported in this browser'; }
      conv.addEventListener('click', function(){ if(convo.active) endConvo('Conversation ended.'); else startConvo(); }); }
    if(guide){ guide.addEventListener('click', function(){ var g=$('voiceGuide'); if(g){ g.classList.toggle('hidden'); showPanel(); } }); }
    var helpBtn=$('voiceHelpBtn');
    if(helpBtn){ helpBtn.addEventListener('click', function(){
      showHelpModal('AI Input Methods — Type, Voice & Converse', methodsHelpHtml()); }); }
    var dbgBtn=$('voiceDebugBtn');
    if(dbgBtn){ dbgBtn.addEventListener('click', function(){
      var el=$('voiceDebug'), hd=$('voiceDebugHead'); if(!el) return;
      var show=(el.style.display==='none' || !el.style.display);
      el.style.display=show?'block':'none';
      if(hd) hd.style.display=show?'block':'none';
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
    if(window.VoiceOpenAI){ window.VoiceOpenAI.getStatus().then(function(s){
      if(s && s.voiceAvailable === false){
        disableVoice(s.voiceEnabled === false
          ? 'Voice is disabled by your administrator.'
          : 'Voice limit reached. Please contact your administrator.');
      }
    }); }
    loadPurposes();
    attachFieldFocus();   // click / tab into a field → assistant focuses it (when active)
  }
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
