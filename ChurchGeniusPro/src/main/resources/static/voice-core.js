/* ============================================================================
 * voice-core.js — Shared voice-input parsing for the Income and Expense forms.
 *
 * Exposes window.VoiceCore with the pure (DOM-free) parsing + matching logic
 * used by income-voice.js and expense-voice.js, so the spoken-value parsers
 * live in one place. Unit-tested (41 cases). Load this BEFORE the page module:
 *
 *   <script src="/voice-core.js"></script>
 *   <script src="/income-voice.js"></script>
 *
 * API:
 *   VoiceCore.parseDate(text)            -> "YYYY-MM-DD" | null
 *   VoiceCore.parseAmount(text)          -> Number | null
 *   VoiceCore.matchOption(spoken, opts)  -> {best, candidates[], ambiguous}   opts=[{value,text}]
 *   VoiceCore.matchContributor(s, people)-> {best, candidates[], ambiguous}   people=[{id,fullName}]
 *   VoiceCore.segment(transcript, kw)    -> {field: "value", ...}   kw={field:[keywords...]}
 *   VoiceCore.titleCase / normKey / words helpers
 * ========================================================================== */
(function (root) {
  'use strict';

  var ONES = {zero:0,oh:0,o:0,one:1,two:2,three:3,four:4,five:5,six:6,seven:7,eight:8,nine:9};
  var TEENS = {ten:10,eleven:11,twelve:12,thirteen:13,fourteen:14,fifteen:15,sixteen:16,seventeen:17,eighteen:18,nineteen:19};
  var TENS = {twenty:20,thirty:30,forty:40,fourty:40,fifty:50,sixty:60,seventy:70,eighty:80,ninety:90};
  var ORD = {first:1,second:2,third:3,fourth:4,fifth:5,sixth:6,seventh:7,eighth:8,ninth:9,tenth:10,
    eleventh:11,twelfth:12,thirteenth:13,fourteenth:14,fifteenth:15,sixteenth:16,seventeenth:17,eighteenth:18,nineteenth:19,
    twentieth:20,thirtieth:30};
  var MONTHS = {january:1,jan:1,february:2,feb:2,march:3,mar:3,april:4,apr:4,may:5,june:6,jun:6,july:7,jul:7,
    august:8,aug:8,september:9,sep:9,sept:9,october:10,oct:10,november:11,nov:11,december:12,dec:12};

  function normKey(s){ return (s||'').toLowerCase().replace(/[^a-z0-9]+/g,''); }
  function words(s){ return (s||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').split(/\s+/).filter(Boolean); }
  function titleCase(s){ return (s||'').replace(/\w\S*/g, function(t){ return t.charAt(0).toUpperCase()+t.slice(1).toLowerCase(); }); }

  function parseAmount(text){
    if(text==null) return null;
    var t = text.toLowerCase().replace(/dollars?|bucks?|\band\b/g,' ').trim();
    if(t.replace(/[, ]+/g,'').match(/^\$?\d+(\.\d+)?$/)) return parseFloat(t.replace(/[$, ]/g,''));
    var bare = t.match(/\d+(\.\d+)?/);
    var toks = t.split(/\s+/).filter(Boolean), result=0, current=0, used=false;
    for(var i=0;i<toks.length;i++){ var w=toks[i];
      if(w in ONES){ current+=ONES[w]; used=true; }
      else if(w in TEENS){ current+=TEENS[w]; used=true; }
      else if(w in TENS){ current+=TENS[w]; used=true; }
      else if(w==='hundred'){ current=(current||1)*100; used=true; }
      else if(w==='thousand'){ result+=(current||1)*1000; current=0; used=true; }
      else if(w==='million'){ result+=(current||1)*1000000; current=0; used=true; }
      else if(/^\d+(\.\d+)?$/.test(w)){ current+=parseFloat(w); used=true; }
    }
    result+=current;
    if(used && result>0) return result;
    return bare ? parseFloat(bare[0]) : null;
  }

  function wordVal(w){
    if(/^\d+$/.test(w)) return parseInt(w,10);
    if(w in ONES) return ONES[w]; if(w in TEENS) return TEENS[w];
    if(w in TENS) return TENS[w]; if(w in ORD) return ORD[w]; return null;
  }
  function takeDay(toks,i){
    var a=toks[i]; if(a===undefined) return [null,i];
    if(/^\d{1,2}$/.test(a)) return [parseInt(a,10), i+1];
    if(a in TENS){ var b=toks[i+1];
      if(b!==undefined && ((b in ONES)||(b in ORD)) && (ONES[b]||ORD[b])>=1 && (ONES[b]||ORD[b])<=9)
        return [TENS[a]+((b in ONES)?ONES[b]:ORD[b]), i+2];
      return [TENS[a], i+1]; }
    if(a in TEENS) return [TEENS[a], i+1];
    if(a in ONES) return [ONES[a], i+1];
    if(a in ORD) return [ORD[a], i+1];
    return [null,i];
  }
  function parseYear(toks){
    if(!toks.length) return null;
    if(toks.length===1 && /^\d{4}$/.test(toks[0])) return parseInt(toks[0],10);
    if(toks.length===1 && /^\d{2}$/.test(toks[0])) return 2000+parseInt(toks[0],10);
    if(toks.indexOf('thousand')!==-1){ var v=parseAmount(toks.join(' ')); if(v&&v>=1000&&v<=9999) return Math.round(v); }
    var vals=toks.map(wordVal);
    if(vals.every(function(v){return v!==null;}) && vals.every(function(v){return v<=9;})){
      var s=vals.join(''); if(s.length>=2) return parseInt(s.slice(0,4),10);
    }
    var chunks=[], i=0;
    while(i<toks.length){ var t=toks[i];
      if(t in TENS){ var n=toks[i+1]; if(n!==undefined && (n in ONES)){ chunks.push(TENS[t]+ONES[n]); i+=2; continue; } chunks.push(TENS[t]); i++; continue; }
      if(t in TEENS){ chunks.push(TEENS[t]); i++; continue; }
      if(/^\d{1,2}$/.test(t)){ chunks.push(parseInt(t,10)); i++; continue; }
      if(t in ONES){ chunks.push(ONES[t]); i++; continue; }
      i++;
    }
    if(chunks.length){ var str=chunks.map(function(c){return String(c).padStart(2,'0');}).join('');
      if(str.length>=4) return parseInt(str.slice(0,4),10); if(str.length>0) return parseInt(str,10); }
    return null;
  }
  function ymd(y,m,d){ if(!y||!m||!d) return null; if(m<1||m>12||d<1||d>31) return null;
    return y+'-'+String(m).padStart(2,'0')+'-'+String(d).padStart(2,'0'); }
  function parseDate(text){
    if(text==null) return null;
    var t = text.toLowerCase().replace(/(\d+)(st|nd|rd|th)\b/g,'$1').replace(/[\/.,-]+/g,' ').replace(/\s+/g,' ').trim();
    var toks=t.split(/\s+/).filter(Boolean), month=null, di=-1;
    for(var i=0;i<toks.length;i++){ if(toks[i] in MONTHS){ month=MONTHS[toks[i]]; di=i; break; } }
    if(month!==null){ toks.splice(di,1); var r=takeDay(toks,0); var yr=parseYear(toks.slice(r[1]));
      return (r[0]&&yr)?ymd(yr,month,r[0]):null; }
    var m=t.match(/\b(\d{1,2})\s+(\d{1,2})\s+(\d{2,4})\b/);
    if(m){ var y=+m[3]; return ymd(y<100?2000+y:y, +m[1], +m[2]); }
    var d0=takeDay(toks,0);
    if(d0[0]>=1 && d0[0]<=12){ var d1=takeDay(toks,d0[1]); var yr2=parseYear(toks.slice(d1[1]));
      if(d1[0]&&yr2) return ymd(yr2,d0[0],d1[0]); }
    return null;
  }

  // Bounded Levenshtein distance (returns cap+1 once it provably exceeds cap).
  function levN(a,b,cap){
    cap = cap||2;
    var m=a.length, n=b.length;
    if(Math.abs(m-n)>cap) return cap+1;
    var prev=[], cur=[], i, j;
    for(j=0;j<=n;j++) prev[j]=j;
    for(i=1;i<=m;i++){
      cur[0]=i; var rowBest=i;
      for(j=1;j<=n;j++){
        var cost=(a.charAt(i-1)===b.charAt(j-1))?0:1;
        cur[j]=Math.min(prev[j]+1, cur[j-1]+1, prev[j-1]+cost);
        if(cur[j]<rowBest) rowBest=cur[j];
      }
      if(rowBest>cap) return cap+1;
      for(j=0;j<=n;j++) prev[j]=cur[j];
    }
    return prev[n];
  }
  // Fuzzy word equality — tolerates mishearings like "hanson" for "anson".
  function tokenSim(a,b){
    if(a===b) return true;
    if(a.length>=3 && b.length>=3 && (a.indexOf(b)===0 || b.indexOf(a)===0)) return true; // prefix
    var maxLen=Math.max(a.length,b.length);
    if(maxLen<4) return false;
    var d=levN(a,b,2);
    return maxLen<=5 ? d<=1 : d<=2;
  }

  function matchOption(spoken, options){
    var sN=normKey(spoken), sToks=words(spoken);
    if(!sN) return {best:null,candidates:[],ambiguous:false};
    var scored=[];
    options.forEach(function(o){
      if(o.value==='' || o.value==null) return;
      var variants=[o.text]; if(o.text.indexOf('/')!==-1) variants.push(o.text.split('/').pop());
      var best=0;
      variants.forEach(function(v){
        var oN=normKey(v); if(!oN) return;
        if(oN===sN) best=Math.max(best,100);
        else if(oN.endsWith(sN)||oN.startsWith(sN)) best=Math.max(best,78);
        else if(oN.indexOf(sN)!==-1) best=Math.max(best,62);
        else if(sN.indexOf(oN)!==-1) best=Math.max(best,55);
        else if(Math.max(sN.length,oN.length)>=4 && levN(sN,oN,3)<=2) best=Math.max(best,70); // whole-string fuzzy
        var vToks=words(v), mm=0;
        sToks.forEach(function(st){ if(vToks.some(function(vt){ return tokenSim(st,vt); })) mm++; });
        if(sToks.length && mm===sToks.length) best=Math.max(best, 66+mm);
        else if(mm>0) best=Math.max(best, 42+mm*4);
      });
      if(best>0) scored.push({value:o.value,text:o.text,score:best});
    });
    scored.sort(function(a,b){return b.score-a.score;});
    return {best:scored.length?scored[0]:null, candidates:scored.slice(0,5), ambiguous: scored.length>1 && Math.abs(scored[0].score-scored[1].score)<5};
  }
  function matchContributor(spoken, people){
    var sToks=words(spoken), sN=normKey(spoken);
    if(!sToks.length) return {best:null,candidates:[],ambiguous:false};
    var scored=[];
    people.forEach(function(p){
      var nToks=words(p.fullName), nN=normKey(p.fullName), score=0;
      if(nN===sN) score=100;
      else {
        var mm=0;
        sToks.forEach(function(st){ if(nToks.some(function(nt){ return tokenSim(st,nt); })) mm++; });
        if(mm===sToks.length && mm>0) score=62+mm*6;
        else if(mm>0) score=32+mm*6;
        if(Math.max(sN.length,nN.length)>=5 && levN(sN,nN,3)<=2) score=Math.max(score,84); // whole-name fuzzy
        if(nN.indexOf(sN)!==-1||sN.indexOf(nN)!==-1) score=Math.max(score,80);
      }
      if(score>0) scored.push({id:p.id,fullName:p.fullName,score:score});
    });
    scored.sort(function(a,b){return b.score-a.score;});
    return {best:scored.length?scored[0]:null, candidates:scored.slice(0,5), ambiguous: scored.length>1 && Math.abs(scored[0].score-scored[1].score)<5};
  }

  // True if a and b are within edit distance 1 (handles common mishearings like
  // "find" for "fund" or "offing" → "offering" is distance 2 so won't match).
  function lev1(a,b){
    if(a===b) return true;
    var m=a.length, n=b.length; if(Math.abs(m-n)>1) return false;
    var i=0, j=0, diff=0;
    while(i<m && j<n){
      if(a.charAt(i)===b.charAt(j)){ i++; j++; continue; }
      if(++diff>1) return false;
      if(m>n) i++; else if(n>m) j++; else { i++; j++; }
    }
    if(i<m || j<n) diff++;
    return diff<=1;
  }
  function keywordField(token, kw2field){
    if(kw2field[token]) return kw2field[token];
    if(token.length>=4){ for(var k in kw2field){ if(k.length>=4 && lev1(token,k)) return kw2field[k]; } }
    return null;
  }
  function segment(transcript, keywords){
    var kw2field={}; Object.keys(keywords||{}).forEach(function(f){ keywords[f].forEach(function(k){ kw2field[k]=f; }); });
    var toks=(transcript||'').toLowerCase().replace(/[^a-z0-9. ]+/g,' ').split(/\s+/).filter(Boolean);
    var out={}, cur=null, buf=[];
    function flush(){ if(cur && buf.length){ out[cur]=(out[cur]?out[cur]+' ':'')+buf.join(' ').trim(); } buf=[]; }
    for(var i=0;i<toks.length;i++){
      var w=toks[i];
      var field=keywordField(w, kw2field);
      // Start a new segment only when the keyword maps to a DIFFERENT field. A
      // repeated same-field keyword (e.g. the trailing "Fund" in "Fund Building
      // Fund") is treated as part of the value, not a new boundary.
      if(field && field!==cur){ flush(); cur=field;
        if(cur==='ref' && (toks[i+1]==='number'||toks[i+1]==='no')) i++; continue; }
      if(cur) buf.push(w);
    }
    flush(); return out;
  }

  // ── Speech-recognition engine (browser Web Speech API) ────────────────────
  // Robust wrapper used by both page modules: interim results for live feedback,
  // accumulate-and-process-on-end, silence auto-stop, secure-context check, and
  // specific error messages. opts: {lang, listeningText, silenceMs, maxMs,
  // onState(recording), onStatus(msg,kind), onInterim(text), onFinal(text)}.
  function createSpeech(opts){
    opts = opts || {};
    var hasWin = (typeof window !== 'undefined');
    var SR = hasWin && (window.SpeechRecognition || window.webkitSpeechRecognition);
    var api = { supported: !!SR };
    var rec=null, finalText='', interimText='', maxT=null, restartT=null,
        errored=false, audioStarted=false, soundDetected=false, sawResult=false, manualStop=false,
        anyUtterance=false, startedAt=0, lastResultAt=0;
    function clearTimers(){ if(maxT){clearTimeout(maxT);maxT=null;} if(restartT){clearTimeout(restartT);restartT=null;} }
    function status(m,k){ if(opts.onStatus) opts.onStatus(m, k||'info'); }
    function evt(name, detail){
      try { if(typeof console!=='undefined' && console.log) console.log('[voice] '+name, detail===undefined?'':detail); } catch(e){}
      if(opts.onEvent){ try{ opts.onEvent(name, detail); }catch(e){} }
    }
    function secureOk(){
      if(!hasWin) return true;
      if(window.isSecureContext) return true;
      var h=(window.location && window.location.hostname) || '';
      return h==='localhost' || h==='127.0.0.1' || h==='[::1]' || h==='::1';
    }
    function makeRec(){
      var r = new SR();
      r.lang = opts.lang || (hasWin && navigator.language && /^en/i.test(navigator.language) ? navigator.language : 'en-US');
      r.interimResults = true;
      r.continuous = false;          // single-utterance mode is the most reliable for capture
      r.maxAlternatives = 1;
      r.onstart      = function(){ evt('start'); if(opts.onState) opts.onState(true); status(opts.listeningText || '🎤 Listening… speak now, then pause.','listening'); };
      r.onaudiostart = function(){ evt('audiostart'); audioStarted = true; };
      r.onsoundstart = function(){ evt('soundstart'); soundDetected = true; };
      r.onspeechstart= function(){ evt('speechstart'); status('🎤 Hearing you… keep speaking, then pause.','listening'); };
      r.onspeechend  = function(){ evt('speechend'); };
      r.onsoundend   = function(){ evt('soundend'); };
      r.onaudioend   = function(){ evt('audioend'); };
      r.onnomatch    = function(){ evt('nomatch'); };
      r.onresult = function(ev){
        sawResult = true; lastResultAt = (hasWin && window.Date ? Date.now() : 0);
        var interim='', anyFinal=false;
        for(var i=ev.resultIndex; i<ev.results.length; i++){
          var res=ev.results[i]; var t=(res[0] && res[0].transcript) || '';
          if(res.isFinal){ finalText += t + ' '; anyFinal=true; } else interim += t;
        }
        interimText = interim;
        var shown=(finalText + ' ' + interim).replace(/\s+/g,' ').trim();
        evt('result', { isFinal: anyFinal, text: shown });
        if(opts.onInterim) opts.onInterim(shown);
      };
      r.onerror = function(e){
        var err=e && e.error, msg;
        evt('error', err || 'unknown');
        switch(err){
          case 'not-allowed': case 'service-not-allowed':
            errored=true; msg='Microphone permission is blocked. Click the mic/camera icon in the browser address bar, allow the microphone, then tap Voice again.'; break;
          case 'audio-capture':
            errored=true; msg='No microphone was found. Connect or enable a microphone, then try again.'; break;
          case 'network':
            errored=true; msg='Couldn’t reach the speech-recognition service. Some browsers (e.g. Firefox, Brave) don’t support it — please use Google Chrome, Edge, or Safari.'; break;
          case 'language-not-supported':
            errored=true; msg='Speech recognition isn’t available for this language in your browser.'; break;
          case 'no-speech': case 'aborted': msg=null; break;  // handled on end (allows mid-phrase pauses)
          default:
            errored=true; msg='Voice input error'+(err?(' ('+err+')'):'')+'. You can enter the form manually.';
        }
        if(msg) status(msg,'error');
      };
      r.onend = function(){
        evt('end', { final: finalText.trim(), sawResult: sawResult, audioStarted: audioStarted, soundDetected: soundDetected });
        var now = (hasWin && window.Date ? Date.now() : 0);
        // Process THIS utterance immediately (fill fields), then reset so repeats
        // don't pile up. Keep listening for the next field until the user taps Stop,
        // goes idle, the time cap is hit, or an error occurs.
        var utter = (finalText + ' ' + interimText).replace(/\s+/g,' ').trim();
        if(utter){ if(opts.onFinal) opts.onFinal(utter); anyUtterance = true; finalText=''; interimText=''; }
        var elapsed = now - startedAt;
        var idleSince = lastResultAt ? (now - lastResultAt) : elapsed;
        if(manualStop || errored || elapsed >= (opts.maxMs || 40000)
           || (anyUtterance && idleSince >= (opts.idleMs || 5000))
           || (!anyUtterance && idleSince >= (opts.noSpeechMs || 11000))){
          stopAll(); return;
        }
        restartT = setTimeout(function(){ try{ rec = makeRec(); rec.start(); }catch(e){ stopAll(); } }, 120);
      };
      return r;
    }
    function stopAll(){
      clearTimers(); if(opts.onState) opts.onState(false);
      if(anyUtterance || errored) return;       // fields were filled, or an error message was already shown
      if(!audioStarted){
        status('The microphone didn’t start. Check that this page has microphone permission and a microphone is connected, then tap Voice and try again.','error');
      } else if(!soundDetected){
        status('The microphone opened but no sound was detected — Chrome is likely capturing from the wrong or muted microphone. Open chrome://settings/content/microphone, pick the mic you speak into, make sure it isn’t muted, and confirm it shows an input level in your system sound settings; then tap Voice and try again.','error');
      } else {
        status('Sound was detected but no speech was recognized. Speak a little louder and clearly right after tapping Voice.','error');
      }
    }
    api.start = function(){
      if(!SR){ status('Voice input isn’t supported in this browser. Use Google Chrome, Microsoft Edge, or Safari (and allow microphone access) — or enter the form manually.','error'); return; }
      if(!secureOk()){ status('Voice input needs a secure (https) connection. Open this page over HTTPS and try again.','error'); return; }
      finalText=''; interimText=''; errored=false; audioStarted=false; soundDetected=false; sawResult=false; manualStop=false; anyUtterance=false;
      startedAt = (hasWin && window.Date ? Date.now() : 0);
      try { rec = makeRec(); } catch(e){ status('Could not start voice input. Please try again.','error'); return; }
      try { rec.start(); } catch(e){ status('Could not start voice input ('+((e&&e.message)||'error')+'). Please try again.','error'); return; }
      maxT = setTimeout(function(){ manualStop = true; try{ rec.stop(); }catch(e){} }, opts.maxMs || 40000);
    };
    api.stop  = function(){ manualStop = true; clearTimers(); if(rec){ try{ rec.stop();  }catch(e){} } };
    api.abort = function(){ manualStop = true; clearTimers(); if(rec){ try{ rec.abort(); }catch(e){} } };
    return api;
  }

  root.VoiceCore = {
    parseDate: parseDate, parseAmount: parseAmount,
    matchOption: matchOption, matchContributor: matchContributor,
    segment: segment, titleCase: titleCase, normKey: normKey, words: words,
    createSpeech: createSpeech
  };
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this));
