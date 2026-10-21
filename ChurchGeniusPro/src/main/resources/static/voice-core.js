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

  // Largest amount the ledger stores: income/expense.amount is numeric(15,2).
  var AMOUNT_MAX = 9999999999999.99;
  // Round to cents; reject anything that is not a finite, positive, in-range number.
  // Returns {ok:true, value} or {ok:false, reason:'none'|'range'|'invalid'}.
  function validateAmount(n){
    if(n==null || typeof n!=='number' || !isFinite(n) || isNaN(n)) return {ok:false, reason:'none'};
    var v=Math.round(n*100)/100;
    if(v<=0) return {ok:false, reason:'none'};
    if(v>AMOUNT_MAX) return {ok:false, reason:'range'};
    return {ok:true, value:v};
  }
  // Spoken or typed amount → Number (cents-rounded, within AMOUNT_MAX) or null.
  // A run of separate digit groups ("1 2 3 4", "100 200") is NOT concatenated —
  // that is how "1 2 3 … 24" once became 1.2345678910111214e+38 — unless the
  // groups are thousands separators ("1,250" / "1 250 000").
  function parseAmount(text){
    if(text==null) return null;
    var t = String(text).toLowerCase().replace(/dollars?|bucks?|usd|\band\b/g,' ').replace(/\$/g,' ').trim();
    if(!t) return null;
    function finish(n){ var v=validateAmount(n); return v.ok ? v.value : null; }
    // Plain number, optionally with thousands separators: 100 · 100.50 · 1,250 · 1 250 000 · 1,250.75
    var plain=t.match(/^(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?$/);
    if(plain) return finish(parseFloat(plain[1].replace(/,/g,'')+(plain[2]||'')));
    // More than one separate digit group that is not a thousands pattern → ambiguous, refuse.
    var groups=t.match(/\d+(?:\.\d+)?/g)||[];
    if(groups.length>1) return null;
    var toks = t.split(/\s+/).filter(Boolean), result=0, current=0, used=false, unknown=0, lastSmall=false;
    for(var i=0;i<toks.length;i++){ var w=toks[i];
      if(w in ONES){ current+=ONES[w]; used=true; lastSmall=ONES[w]>0; continue; }
      if(w in TEENS){ current+=TEENS[w]; used=true; lastSmall=true; continue; }
      // "one fifty" = 150, "twelve fifty" = 1250 (a small number spoken before a tens word)
      if(w in TENS){ current = lastSmall ? current*100+TENS[w] : current+TENS[w]; used=true; lastSmall=false; continue; }
      lastSmall=false;
      if(false){ }
      else if(w==='hundred'){ current=(current||1)*100; used=true; }
      else if(w==='thousand'){ result+=(current||1)*1000; current=0; used=true; }
      else if(w==='million'){ result+=(current||1)*1000000; current=0; used=true; }
      else if(/^\d+(\.\d+)?$/.test(w)){ current+=parseFloat(w); used=true; }
      else if(/^(amount|is|of|the|a|total|value|set|to|please|cents?|point)$/.test(w)){ /* filler */ }
      else unknown++;
    }
    result+=current;
    if(used && result>0 && unknown<=2) return finish(result);
    if(!used && groups.length===1 && unknown<=2) return finish(parseFloat(groups[0]));
    return null;
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
  // Bigram Dice similarity of two normalised strings (0..1) — order-tolerant,
  // forgiving of a dropped or added letter in the middle of a name.
  function bigrams(s){ var out={}; for(var i=0;i<s.length-1;i++){ var g=s.substr(i,2); out[g]=(out[g]||0)+1; } return out; }
  function diceSim(a,b){
    if(!a||!b||a.length<2||b.length<2) return 0;
    var A=bigrams(a), B=bigrams(b), inter=0, na=0, nb=0, k;
    for(k in A){ na+=A[k]; if(B[k]) inter+=Math.min(A[k],B[k]); }
    for(k in B){ nb+=B[k]; }
    return (2*inter)/(na+nb);
  }
  // A small phonetic key: how a name SOUNDS, so "Matthew"/"Mathew", "Hanson"/"Anson",
  // "Philip"/"Fillip" collapse to the same or nearly the same key.
  function phoneticKey(w){
    var s=String(w||'').toLowerCase().replace(/[^a-z]/g,'');
    if(!s) return '';
    s=s.replace(/^(kn|gn|pn|wr)/,function(m){ return m.charAt(1); }).replace(/^x/,'s').replace(/^wh/,'w').replace(/^h(?=[aeiou])/,'');
    s=s.replace(/ph/g,'f').replace(/ck/g,'k').replace(/sch/g,'sk').replace(/gh(?![aeiou])/g,'').replace(/dg/g,'j')
       .replace(/c(?=[eiy])/g,'s').replace(/c/g,'k').replace(/q/g,'k').replace(/x/g,'ks').replace(/z/g,'s').replace(/v/g,'f')
       .replace(/w(?![aeiou])/g,'').replace(/y(?![aeiou])/g,'').replace(/h/g,'');
    s=s.replace(/(.)\1+/g,'$1');                               // squash doubles
    s=s.charAt(0)+s.slice(1).replace(/[aeiou]/g,'');            // keep only a leading vowel
    return s;
  }
  function phoneticSim(a,b){
    var ka=phoneticKey(a), kb=phoneticKey(b);
    if(!ka||!kb) return false;
    if(ka===kb) return true;
    var max=Math.max(ka.length,kb.length);
    return max>=3 && levN(ka,kb,2)<=(max<=4?1:2);
  }
  // Score a spoken phrase against each known contributor. Scores ≥70 are safe to
  // apply; 40–69 are "did you mean…" candidates; below 40 is noise. Candidates are
  // ALWAYS returned (lowest first omitted) so a miss can still offer the nearest
  // names instead of jumping to "new contributor?".
  function matchContributor(spoken, people){
    var sToks=words(spoken), sN=normKey(spoken);
    if(!sToks.length) return {best:null,candidates:[],ambiguous:false};
    var scored=[];
    people.forEach(function(p){
      var nToks=words(p.fullName), nN=normKey(p.fullName), score=0;
      if(nN===sN) score=100;
      else {
        var mm=0, pm=0;
        sToks.forEach(function(st){
          if(nToks.some(function(nt){ return tokenSim(st,nt); })) mm++;
          else if(st.length>=4 && nToks.some(function(nt){ return phoneticSim(st,nt); })) pm++;
        });
        if(mm===sToks.length && mm>0) score=62+mm*6;
        else if(mm>0) score=32+mm*6;
        if(Math.max(sN.length,nN.length)>=5 && levN(sN,nN,3)<=2) score=Math.max(score,84); // whole-name fuzzy
        if(nN.indexOf(sN)!==-1||sN.indexOf(nN)!==-1) score=Math.max(score,80);
        if(mm+pm===sToks.length && pm>0) score=Math.max(score, 48+mm*8);       // sounds like every word
        else if(pm>0) score=Math.max(score, 40);                               // sounds like one word
        // Letter-overlap (bigram Dice) only between strings of comparable length — a long
        // noisy phrase shares letters with everything, and must not look like a name.
        function comparable(a,b){ var r=a.length/b.length; return r>=0.6 && r<=1.67; }
        // Below 0.8 the letters must also START alike or SOUND alike, or "weather" would
        // pass for "Mathew" on shared letters alone.
        var dice=0, alike=false;
        function consider(a,b){ if(!comparable(a,b)) return; var d=diceSim(a,b); if(d>dice){ dice=d; alike = a.charAt(0)===b.charAt(0) || phoneticSim(a,b); } }
        consider(sN,nN);
        sToks.forEach(function(st){ nToks.forEach(function(nt){ if(st.length>=4) consider(st,nt); }); });
        if(dice>=0.8) score=Math.max(score, 72);
        else if(dice>=0.6 && alike) score=Math.max(score, 55);
        else if(dice>=0.45 && alike) score=Math.max(score, 42);
      }
      if(score>=40) scored.push({id:p.id,fullName:p.fullName,score:score});
    });
    scored.sort(function(a,b){return b.score-a.score;});
    // Two plausible people → never pick one on sound alone; the page must ask.
    var amb = scored.length>1 && scored[0].score<90 && (scored[1].score>=60 || scored[0].score-scored[1].score<5);
    return {best:scored.length?scored[0]:null, candidates:scored.slice(0,5), ambiguous: amb};
  }

  // ── Explicit field selection ("Set Contributor to Anson Mathew", "Set Fund", "Done")
  // fieldWords = {contributor:['contributor','member',…], …}. Returns
  //   {kind:'set', field, value}   value '' when only the field was named
  //   {kind:'done'}                finish the current field
  //   null                         not a command
  function parseSetCommand(transcript, fieldWords){
    var t=String(transcript||'').toLowerCase().replace(/[^a-z0-9.,/\-: ]+/g,' ').replace(/\s+/g,' ').trim();
    if(!t) return null;
    if(/^(done|finished|finish|that'?s it|i'?m done|next field|next)\.?$/.test(t)) return {kind:'done'};
    var m=t.match(/^(?:please )?(?:set|select|choose|change|update|edit|enter|fill|go to|switch to|focus(?: on)?)(?: the)? ([a-z]+)(.*)$/);
    if(!m) return null;
    function fieldOf(word){ for(var f in fieldWords){ if(fieldWords[f].indexOf(word)>=0) return f; } return null; }
    var field=fieldOf(m[1]); if(!field) return null;
    var rest=(m[2]||'').trim();
    // swallow further words of the same field ("payment method", "reference number")
    var more=rest.match(/^([a-z]+)\b(.*)$/);
    while(more && (fieldOf(more[1])===field || /^(number|no|name|field|fields|box)$/.test(more[1]))){ rest=more[2].trim(); more=rest.match(/^([a-z]+)\b(.*)$/); }
    rest=rest.replace(/^(to|as|is|equals|=|:)\s+/,'').replace(/^(to|as|is|equals|=|:)$/,'').trim();
    return {kind:'set', field:field, value:rest};
  }

  // Transcriptions the speech model is known to invent on silence or noise ("Thank you
  // for watching"), and text in another script while the language is English, are never
  // treated as the user's words.
  var HALLUCINATIONS=[/^(thank you|thanks)( so much| very much)? for watching\.?$/i, /^(thank you|thanks) for (listening|your attention)\.?$/i,
    /subtitles? (by|provided by)/i, /^(please )?(like,? )?(and )?subscribe\.?$/i, /amara\.org/i, /^you\.?$/i, /^(bye|goodbye|see you)( bye)?\.?$/i];
  function isLikelyHallucination(text){
    var t=String(text||'').trim(); if(!t) return false;
    if(/[\u0400-\u04FF\u0590-\u05FF\u0600-\u06FF\u0900-\u097F\u3040-\u30FF\u4E00-\u9FFF\uAC00-\uD7AF]/.test(t)) return true;   // not Latin script
    for(var i=0;i<HALLUCINATIONS.length;i++){ if(HALLUCINATIONS[i].test(t)) return true; }
    return false;
  }

  // Decide which of the extractor's fields may be applied for one phrase.
  //   activeField  — the field the user selected ("Set Amount"); only it may change
  //   keywords     — {field:[spoken keywords]}; a field named in the phrase may change
  // Returns {pairs:[{field,value}], reason}. reason: 'active' | 'keyword' | 'single' |
  // 'none' (nothing to apply) | 'ambiguous' (several fields, none named — ask).
  function routeFields(fields, transcript, activeField, keywords, order){
    fields=fields||{}; order=order||Object.keys(fields);
    var present=[]; order.forEach(function(f){ if(fields[f]!=null && String(fields[f]).trim()!=='') present.push(f); });
    Object.keys(fields).forEach(function(f){ if(present.indexOf(f)<0 && fields[f]!=null && String(fields[f]).trim()!=='') present.push(f); });
    if(activeField){
      // A phrase that NAMES a different field ("amount 999" while Contributor is active)
      // is a deliberate switch; otherwise everything goes to the active field.
      var toksA=words(transcript), switched=null;
      present.forEach(function(f){ if(!switched && f!==activeField){ var kws=(keywords&&keywords[f])||[]; if(kws.some(function(k){ return toksA.indexOf(k)>=0; })) switched=f; } });
      if(switched) return {pairs:[{field:switched, value:String(fields[switched])}], reason:'switch'};
      var v = fields[activeField]!=null && String(fields[activeField]).trim()!=='' ? String(fields[activeField]) : String(transcript||'');
      return {pairs: v.trim() ? [{field:activeField, value:v.trim()}] : [], reason:'active'};
    }
    if(!present.length) return {pairs:[], reason:'none'};
    if(present.length===1) return {pairs:[{field:present[0], value:String(fields[present[0]])}], reason:'single'};
    var toks=words(transcript), named=[];
    present.forEach(function(f){ var kws=(keywords&&keywords[f])||[]; if(kws.some(function(k){ return toks.indexOf(k)>=0; })) named.push(f); });
    if(named.length) return {pairs: named.map(function(f){ return {field:f, value:String(fields[f])}; }), reason:'keyword'};
    return {pairs:[], reason:'ambiguous', fields:present};
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
    parseDate: parseDate, parseAmount: parseAmount, validateAmount: validateAmount, AMOUNT_MAX: AMOUNT_MAX,
    matchOption: matchOption, matchContributor: matchContributor, phoneticKey: phoneticKey, diceSim: diceSim,
    parseSetCommand: parseSetCommand, routeFields: routeFields, isLikelyHallucination: isLikelyHallucination,
    segment: segment, titleCase: titleCase, normKey: normKey, words: words,
    createSpeech: createSpeech
  };
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this));
