/* ============================================================================
 * meeting-voice.js — AI meeting creation for the Meetings page (/meetings).
 *
 * Three modes, mirroring the rest of the app's AI assistant:
 *   • Type     — type a full request ("Create a Bible Study at John's house on
 *                June 3rd at 4 pm") → fields are extracted (OpenAI) and the form
 *                is populated for review. Nothing is saved automatically.
 *   • Voice    — speak the same request (OpenAI STT) → same population.
 *   • Converse — guided, conversational creation: the assistant asks for the
 *                category, location, date, time and occurrence one at a time,
 *                with full occurrence logic (One-time / Daily / Weekly / Monthly,
 *                incl. specific-day vs week-pattern), then notes, then a save
 *                confirmation.
 *
 * Integrates with the page's own functions/fields (meetingType, selectLocation,
 * onOccurrenceChange, setSelectedDays/Months, saveMeeting, …) — it never bypasses
 * them, so validation and the existing save flow are preserved.
 * ========================================================================== */
(function () {
  'use strict';
  if (!/^\/meetings(\b|\/|$)/.test(window.location.pathname)) return;
  if (window.__CGP_MEETING__) return; window.__CGP_MEETING__ = true;

  /* ════════════════════════ Parsers (pure, unit-tested) ════════════════════════ */
  var MONTHS = {january:1,february:2,march:3,april:4,may:5,june:6,july:7,august:8,september:9,october:10,november:11,december:12,
                jan:1,feb:2,mar:3,apr:4,jun:6,jul:7,aug:8,sep:9,sept:9,oct:10,nov:11,dec:12};
  var DOW = {sunday:0,monday:1,tuesday:2,wednesday:3,thursday:4,friday:5,saturday:6,
             sun:0,mon:1,tue:2,tues:2,wed:3,thu:4,thur:4,thurs:4,fri:5,sat:6};

  var _today = null;                                   // injectable for tests
  function today(){ return _today ? new Date(_today + 'T00:00:00') : new Date(); }
  function pad(n){ return (n<10?'0':'')+n; }
  function iso(d){ return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate()); }
  function disp(d){ return pad(d.getMonth()+1)+'/'+pad(d.getDate())+'/'+d.getFullYear(); }

  function parseOccurrence(text){
    var t=String(text||'').toLowerCase();
    if (/\b(one[\s-]?time|once|single|just once|onetime)\b/.test(t)) return 'One-time';
    if (/\b(daily|every day|each day)\b/.test(t)) return 'Daily';
    if (/\b(weekly|every week|each week)\b/.test(t)) return 'Weekly';
    if (/\b(monthly|every month|each month)\b/.test(t)) return 'Monthly';
    return null;
  }

  // Normalize "7.30 p.m." / "7 30" / "7pm" → a parseable form, THEN parse.
  function normTime(text){
    var t=String(text||'').toLowerCase();
    t=t.replace(/([ap])\s*\.\s*m\.?/g,'$1m');     // "p.m." / "p. m." → "pm"
    t=t.replace(/(\d)\s*[.·]\s*(\d{2})\b/g,'$1:$2');   // "7.30" → "7:30"
    t=t.replace(/\b(\d{1,2})\s+(\d{2})\b/g,'$1:$2');         // "7 30" → "7:30"
    return t.replace(/\s+/g,' ').trim();
  }
  var TIMEWORD={ oh:0,o:0,zero:0,one:1,two:2,three:3,four:4,five:5,six:6,seven:7,eight:8,nine:9,ten:10,
    eleven:11,twelve:12,thirteen:13,fourteen:14,fifteen:15,sixteen:16,seventeen:17,eighteen:18,nineteen:19,
    twenty:20,thirty:30,forty:40,fifty:50 };
  function parseWordTime(t){
    var pm=/\bpm\b/.test(t), am=/\bam\b/.test(t);
    var toks=t.replace(/\b(am|pm)\b/g,'').replace(/o'?clock/g,'').trim().split(/\s+/);
    var hour=null, i=0;
    for(;i<toks.length;i++){ var v=TIMEWORD[toks[i]]; if(v!=null && v>=1 && v<=12){ hour=v; i++; break; } }
    if(hour==null) return null;
    var min=0, got=false;
    for(var j=i;j<toks.length;j++){ var mv=TIMEWORD[toks[j]]; if(mv!=null){ min+=mv; got=true; } }
    if(min>59) min=min%60;
    var h=hour;
    if(pm){ if(h!==12) h+=12; } else if(am){ if(h===12) h=0; } else if(h>=1 && h<=7){ h+=12; }   // bare → evening default
    return fmtTime(h, got?min:0);
  }
  function parseTime(text){
    var t=normTime(text);
    if (/\bnoon\b/.test(t)) return { h24:'12:00', display:'12:00 PM' };
    if (/\bmidnight\b/.test(t)) return { h24:'00:00', display:'12:00 AM' };
    var m = t.match(/\b(\d{1,2}):(\d{2})\s*(am|pm)?\b/);    // "7:30", "7:30 pm", "19:30"
    if (m){
      var h=parseInt(m[1],10), mm=parseInt(m[2],10), ap=m[3];
      if (ap){ if(h===12) h=(ap==='pm')?12:0; else if(ap==='pm') h+=12; }
      else if (h>=1 && h<=7) h+=12;                         // bare time → evening default (7:30 → 7:30 PM)
      return fmtTime(h,mm);
    }
    m = t.match(/\b(\d{1,2})\s*(am|pm)\b/);                 // "7 pm", "7pm"
    if (m){ var h2=parseInt(m[1],10), pm2=(m[2]==='pm'); if(h2===12) h2=pm2?12:0; else if(pm2) h2+=12; return fmtTime(h2,0); }
    m = t.match(/\b(\d{1,2})\s*o'?clock\b/);
    if (m){ var h3=parseInt(m[1],10); if(h3>=1&&h3<=7) h3+=12; return fmtTime(h3,0); }
    var w=parseWordTime(t); if(w) return w;                 // "seven thirty", "ten forty five"
    return null;
  }
  function fmtTime(h,mm){
    h=((h%24)+24)%24; mm=((mm%60)+60)%60;
    var ap=h<12?'AM':'PM', h12=h%12; if(h12===0)h12=12;
    return { h24:pad(h)+':'+pad(mm), display:h12+':'+pad(mm)+' '+ap };
  }

  function parseDate(text){
    var t=String(text||'').toLowerCase().trim(); if(!t) return null;
    var base=today();
    if (/\btoday\b/.test(t)) return mk(base);
    if (/\btomorrow\b/.test(t)){ var d=new Date(base); d.setDate(d.getDate()+1); return mk(d); }
    if (/\byesterday\b/.test(t)){ var d2=new Date(base); d2.setDate(d2.getDate()-1); return mk(d2); }
    // M/D/YYYY or M/D/YY or M/D
    var m=t.match(/\b(\d{1,2})[\/\-](\d{1,2})(?:[\/\-](\d{2,4}))?\b/);
    if (m){
      var mo=+m[1], da=+m[2], yr=m[3]?+m[3]:null;
      if (yr && yr<100) yr+=2000;
      return resolveMD(mo, da, yr, base);
    }
    // Month name + day  /  day + Month name
    var mn=t.match(/\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t)?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\b/);
    var dn=t.match(/\b(\d{1,2})(?:st|nd|rd|th)?\b/);
    if (mn && dn){
      var mo2=MONTHS[mn[1]] || MONTHS[mn[1].slice(0,3)];
      var yrm=t.match(/\b(20\d{2})\b/);
      return resolveMD(mo2, +dn[1], yrm?+yrm[1]:null, base);
    }
    // this/next <weekday>
    var wd=t.match(/\b(?:this|next|coming)?\s*(sunday|monday|tuesday|wednesday|thursday|friday|saturday|sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat)\b/);
    if (wd){
      var want=DOW[wd[1]], d3=new Date(base), forceNext=/\bnext\b/.test(t);
      var add=(want-d3.getDay()+7)%7; if(add===0 && forceNext) add=7; if(add===0 && !forceNext) add=0;
      if(add===0 && !/\btoday\b/.test(t)) add=7;                // a named weekday means the upcoming one
      d3.setDate(d3.getDate()+add); return mk(d3);
    }
    return null;
  }
  function mk(d){ return { iso:iso(d), display:disp(d) }; }
  function prettyDate(isoStr){
    var p=String(isoStr||'').split('-'); if(p.length!==3) return isoStr;
    var mn=['January','February','March','April','May','June','July','August','September','October','November','December'];
    return mn[(+p[1])-1]+' '+(+p[2])+', '+p[0];
  }
  function resolveMD(mo, da, yr, base){
    if(!mo||!da) return null;
    var y = yr || base.getFullYear();
    var d = new Date(y, mo-1, da);
    if(!yr){
      // No year given: prefer this year; only roll to next year if it's well past.
      var diff = (d - base)/86400000;
      if (diff < -60) d = new Date(y+1, mo-1, da);
    }
    return mk(d);
  }

  function parseDays(text){
    var t=String(text||'').toLowerCase(), out={};
    if (/\bweekdays?\b/.test(t)) [1,2,3,4,5].forEach(function(n){out[n]=1;});
    if (/\bweekends?\b/.test(t)) [0,6].forEach(function(n){out[n]=1;});
    Object.keys(DOW).forEach(function(k){
      if (new RegExp('\\b'+k+'\\b').test(t)) out[DOW[k]]=1;
    });
    return Object.keys(out).map(Number).sort(function(a,b){return a-b;});
  }
  function parseMonths(text){
    var t=String(text||'').toLowerCase(), out={};
    Object.keys(MONTHS).forEach(function(k){
      if (new RegExp('\\b'+k+'\\b').test(t)) out[MONTHS[k]]=1;
    });
    return Object.keys(out).map(Number).sort(function(a,b){return a-b;});
  }
  function parseOrdinalWeek(text){
    var t=String(text||'').toLowerCase();
    if (/\b(first|1st)\b/.test(t)) return 1;
    if (/\b(second|2nd)\b/.test(t)) return 2;
    if (/\b(third|3rd)\b/.test(t)) return 3;
    if (/\b(fourth|4th)\b/.test(t)) return 4;
    if (/\b(last|fifth|5th)\b/.test(t)) return 5;
    return null;
  }
  function parseSingleDOW(text){
    var t=String(text||'').toLowerCase();
    var keys=Object.keys(DOW).sort(function(a,b){return b.length-a.length;});  // longest first
    for (var i=0;i<keys.length;i++){ if(new RegExp('\\b'+keys[i]+'\\b').test(t)) return DOW[keys[i]]; }
    return null;
  }
  function parseDayOfMonth(text){
    var m=String(text||'').toLowerCase().match(/\b(\d{1,2})(?:st|nd|rd|th)?\b/);
    if(!m) return null; var n=+m[1]; return (n>=1&&n<=31)?n:null;
  }
  // Negation wins over positive words (so "not correct" / "that's wrong" → NO even
  // though they contain "correct"/"right"). normTxt strips punctuation/apostrophes.
  function isNo(t){
    var s=' '+normTxt(t)+' ';
    return /\b(no|nope|nah|negative|wrong|incorrect|none|neither)\b/.test(s)
        || /\bnot\b/.test(s) || /\btry again\b/.test(s) || /\bwait\b/.test(s) || /\bcancel\b/.test(s);
  }
  function isYes(t){
    if(isNo(t)) return false;
    var s=' '+normTxt(t)+' ';
    return /\b(yes|yeah|yep|yup|yah|ya|correct|right|exactly|sure|ok|okay|affirmative|confirm|confirmed|sounds good|go ahead|do it|please do|thats right|that is right|perfect|good)\b/.test(s);
  }
  function isSaveNow(t){ return /\bsave( it| this| now| the meeting)?\b/i.test(String(t||'')); }
  function stripHouse(s){ return String(s||'').replace(/'?s\s+(house|home|place|residence)\b.*/i,'').replace(/\b(?:at|the)\b\s*$/i,'').trim(); }

  // ── Self-echo guard: remember what the AI just said and discard any transcript
  //    that matches it (the recognizer sometimes captures the spoken prompt). ──
  var recentSpoken=[];
  function normTxt(s){ return String(s||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').replace(/\s+/g,' ').trim(); }
  function rememberSpoken(msg){ var n=normTxt(msg); if(n){ recentSpoken.push({norm:n,t:Date.now()}); if(recentSpoken.length>6) recentSpoken.shift(); } }
  // Short answers (yes/no/option N/none/numbers) must NEVER be treated as echoes —
  // they're almost always genuine user replies, and "no" can appear as a substring
  // of a remembered prompt (e.g. "...not..."), which previously swallowed them.
  function isShortAnswer(n){
    if(n.length<=4) return true;
    if(/^(option\s*\d+|number\s*\d+|none|neither|first|second|third|fourth|one|two|three|four)$/.test(n)) return true;
    // A brief confirmation (few words) — but a long sentence that merely contains a
    // yes/no word is NOT a short answer (so a captured full prompt is still filtered).
    if(n.split(' ').length<=3 && (isYes(n)||isNo(n))) return true;
    return false;
  }
  var _lastEchoSim=0;
  function isSelfTranscript(t){
    var n=normTxt(t); _lastEchoSim=0; if(!n) return false;
    if(isShortAnswer(n)) return false;
    var now=Date.now(), best=0, nw=n.split(' ');
    for(var i=0;i<recentSpoken.length;i++){
      var r=recentSpoken[i]; if(!r.norm || (now-r.t)>12000) continue;
      if(r.norm===n){ _lastEchoSim=1; return true; }
      // partial capture — captured text is a chunk of a recent prompt (or vice-versa)
      if(n.length>=10 && r.norm.length>=12 && (r.norm.indexOf(n)!==-1 || n.indexOf(r.norm)!==-1)){ _lastEchoSim=1; return true; }
      // word overlap — most of the words appear in a recent prompt (robust to STT noise)
      if(nw.length>=4){
        var rp=' '+r.norm+' ', inR=0;
        for(var k=0;k<nw.length;k++){ if(nw[k].length>1 && rp.indexOf(' '+nw[k]+' ')!==-1) inR++; }
        var ov=inR/nw.length; if(ov>best) best=ov;
      }
      var s=simRatio(n, r.norm); if(s>best) best=s;
    }
    _lastEchoSim=best;
    return best>=0.80;   // high similarity/overlap to a recent AI prompt → treat as an echo
  }

  // ── Fuzzy location matching (handles STT mishears like "BINI" → "Binny") ──
  function lev(a,b){
    a=a||''; b=b||''; var m=a.length,n=b.length; if(!m) return n; if(!n) return m;
    var prev=[],cur=[],i,j; for(j=0;j<=n;j++) prev[j]=j;
    for(i=1;i<=m;i++){ cur[0]=i; for(j=1;j<=n;j++){ var c=a.charAt(i-1)===b.charAt(j-1)?0:1; cur[j]=Math.min(prev[j]+1,cur[j-1]+1,prev[j-1]+c); } for(j=0;j<=n;j++) prev[j]=cur[j]; }
    return prev[n];
  }
  function simRatio(a,b){ a=normTxt(a); b=normTxt(b); if(!a||!b) return 0; var ml=Math.max(a.length,b.length); return ml?1-lev(a,b)/ml:0; }
  // Soundex (American) — groups consonants that sound alike.
  function soundex(s){
    s=String(s||'').toUpperCase().replace(/[^A-Z]/g,''); if(!s) return '';
    var code={B:'1',F:'1',P:'1',V:'1',C:'2',G:'2',J:'2',K:'2',Q:'2',S:'2',X:'2',Z:'2',D:'3',T:'3',L:'4',M:'5',N:'5',R:'6'};
    var out=s.charAt(0), prev=code[s.charAt(0)]||'';
    for(var i=1;i<s.length && out.length<4;i++){ var c=code[s.charAt(i)]||'';
      if(c && c!==prev) out+=c; if(s.charAt(i)!=='H' && s.charAt(i)!=='W') prev=c; }
    return (out+'000').slice(0,4);
  }
  // Phonetic normalization for common mishears (v↔b, ph→f, k→c, z→s, doubled letters).
  function phon(s){ return normTxt(s).replace(/[^a-z ]/g,'').replace(/ph/g,'f').replace(/v/g,'b').replace(/k/g,'c').replace(/z/g,'s').replace(/(.)\1+/g,'$1'); }
  // Exact → starts-with → contains → phonetic(soundex) → fuzzy, with the method recorded.
  function fuzzyMatches(query, list, threshold, limit){
    list = list || allLocs(); threshold=(threshold==null?0.30:threshold); limit=limit||3;
    var q=normTxt(query); if(!q) return [];
    var qFirst=q.split(' ')[0]||q, qSdx=soundex(qFirst), qPhon=phon(q);
    var scored=list.map(function(l){
      var dn=String(l.displayName||''), ndn=normTxt(dn), toks=ndn.split(' ');
      var best=0, method='Fuzzy';
      function take(sc,m){ if(sc>best){ best=sc; method=m; } }
      if(ndn===q) take(1.0,'Exact');
      else if(ndn.indexOf(q)===0 || (toks[0]||'').indexOf(q)===0) take(0.96,'Starts-with');
      else if(ndn.indexOf(q)!==-1) take(0.9,'Contains');
      take(simRatio(q,ndn),'Fuzzy');
      toks.forEach(function(t){ take(simRatio(q,t),'Fuzzy'); });
      // phonetic
      if(qSdx && soundex(toks[0]||ndn)===qSdx) take(0.86,'Phonetic');
      take(simRatio(qPhon, phon(ndn))*0.95,'Phonetic + Fuzzy');
      phon(ndn).split(' ').forEach(function(t){ take(simRatio(qPhon,t)*0.95,'Phonetic + Fuzzy'); });
      return { loc:l, name:dn, score:Math.min(1,best), method:method };
    });
    return scored.filter(function(x){ return x.score>=threshold; })
                 .sort(function(a,b){ return b.score-a.score; }).slice(0,limit);
  }
  // "Option 2" / "2" / "second" / "number two" → 1-based index (or null).
  function parseOption(text, count){
    var t=normTxt(text);
    var m=t.match(/(?:option|number|choice|pick|select)\s*(\d+)/) || t.match(/^\s*(\d+)\s*$/) || t.match(/\b(\d+)\b/);
    if(m){ var n=+m[1]; if(n>=1 && n<=count) return n; }
    // Ordinals before cardinals so "the second one" → 2 (not the trailing "one").
    var words=[['first',1],['second',2],['third',3],['fourth',4],['one',1],['two',2],['three',3],['four',4]];
    for(var i=0;i<words.length;i++){ if(new RegExp('\\b'+words[i][0]+'\\b').test(t) && words[i][1]<=count) return words[i][1]; }
    return null;
  }
  function isNoneChoice(low){ return /\b(none|neither|no(?:ne)? of (these|them)|not (these|them|it)|nope)\b/.test(low) || /^\s*no\s*$/.test(low); }

  /* ════════════════════════ DOM application (page integration) ════════════════════════ */
  function el(id){ return document.getElementById(id); }
  function setVal(id,v){ var e=el(id); if(e){ e.value=(v==null?'':v); } return !!e; }
  function win(fn){ return (typeof window[fn]==='function') ? window[fn] : null; }

  function applyCategory(name){
    var sel=el('meetingType'); if(!sel||!name) return false;
    var want=String(name).toLowerCase().trim(), hit=null;
    for (var i=0;i<sel.options.length;i++){
      var txt=(sel.options[i].text||'').toLowerCase().trim();
      if(!sel.options[i].value) continue;
      if(txt===want){ hit=sel.options[i]; break; }
      if(!hit && txt.indexOf(want)!==-1) hit=sel.options[i];
    }
    if(!hit) return false;
    // Pass fromAssistant so the page DOESN'T auto-fill last-used start/end times:
    // the assistant supplies the explicit detected time and only sets an end time
    // when the user actually stated one. Without this flag the async last-times
    // fetch resolves after applyTime() and clobbers the detected start time
    // (and injects an end time the user never asked for).
    sel.value=hit.value; var f=win('onMeetingTypeChange'); if(f) f(hit.value, { fromAssistant:true });
    dbg.category=hit.text.trim(); return true;
  }
  function applyLocation(name){
    var clean=stripHouse(name); if(!clean) return false;
    dbg.location=clean;
    var list=window.allLocations||[], want=clean.toLowerCase();
    var hit=null;
    for (var i=0;i<list.length;i++){
      var dn=String(list[i].displayName||'').toLowerCase();
      if(dn===want){ hit=list[i]; break; } if(!hit && dn.indexOf(want)!==-1) hit=list[i];
    }
    var sf=win('selectLocation');
    if(hit && sf){ sf(hit.locType, hit.id); return true; }
    setVal('locationSearch', clean); return true;     // manual location text
  }
  function applyOccurrence(occ){
    occ=normOcc(occ); setVal('occurrence', occ);
    var f=win('onOccurrenceChange'); if(f) f(occ); dbg.occurrence=occ; return true;
  }
  function normOcc(o){
    var p=parseOccurrence(o)||o; if(p==='One-time'||p==='Daily'||p==='Weekly'||p==='Monthly') return p; return 'One-time';
  }
  function applyDate(d){
    if(!d) return false; var o=el('occurrence')?el('occurrence').value:'One-time';
    if(o==='One-time'){ setVal('meetingDate', d.iso); } else { setVal('seriesStartDate', d.iso); }
    dbg.date=d.display; return true;
  }
  function applyTime(t){ if(!t) return false; setVal('startTime', t.h24); dbg.time=t.display; return true; }
  function applyEndDate(d){ if(!d) return false; setVal('endDate', d.iso); dbg.endDate=d.display; return true; }
  function applyWeekDays(days){ var f=win('setSelectedDays'); if(f) f(days); dbg.days=days.join(','); return true; }
  function applyMonths(months){ var f=win('setSelectedMonths'); if(f) f(months); dbg.months=months.join(','); return true; }
  function applyMonthDom(dom){ setVal('monthDayOfMonth', String(dom)); dbg.monthDay=dom; return true; }
  function applyMonthWeekOrdinal(ord){ setVal('monthWeekOrdinal', String(ord)); dbg.weekOrd=ord; return true; }
  function applyMonthWeekDay(dow){ setVal('monthWeekDay', String(dow)); dbg.weekDow=dow; return true; }
  function appendNote(text){
    var e=el('note'); if(!e) return false;
    var add=String(text||'').replace(/\bperiod\b/gi,'.').replace(/\s+\./g,'.').trim();
    if(!add) return false;
    e.value = e.value ? (e.value.replace(/\s+$/,'') + ' ' + add) : add;
    dbg.notes=e.value; return true;
  }
  function clearNote(){ setVal('note',''); dbg.notes=''; return true; }
  function doSave(){ var f=win('saveMeeting'); if(f){ f(); return true; } return false; }

  function applyFields(f){
    f=f||{};
    if (f.occurrence) applyOccurrence(f.occurrence); else applyOccurrence('One-time');
    if (f.category)   applyCategory(f.category);
    if (f.date)       applyDate(parseDate(f.date) || null);
    if (f.time)       applyTime(parseTime(f.time) || null);
    if (f.location)   applyLocation(f.location);
    if (f.note)       appendNote(f.note);
  }

  /* ════════════════════════ Converse state machine ════════════════════════ */
  var PROMPT = {
    category:'Which category of meeting would you like to add?',
    location:'Where will it be conducted?',
    date:'When will it be conducted?',
    time:'What time works best for you?',
    occurrence:'What should the occurrence be? You can say One Time, Daily, Weekly, or Monthly.'
  };
  var GREETING   = "Hello! I'd be happy to help you create a meeting today. " + PROMPT.category;
  var COMPLETION = 'Thank you for providing the meeting details. Would you like to update anything else? '
                 + 'When you\'re ready, you can say "Save Now".';
  var FILLED     = "I've filled the meeting details. Would you like to add more information, or say \"Save Now\" when you're ready?";

  var DOW_NAME=['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
  var MON_NAME=['','January','February','March','April','May','June','July','August','September','October','November','December'];
  function ordWord(n){ return ({1:'First',2:'Second',3:'Third',4:'Fourth',5:'Last'})[n]||String(n); }
  function ordStr(n){ var s=['th','st','nd','rd'], v=n%100; return n+(s[(v-20)%10]||s[v]||s[0]); }

  // stage = field being collected; confirm = pending "is that correct?" gate;
  // retry/spelling = location recovery state.
  var mstate = { stage:null, confirm:null, retry:0, spelling:false };
  function resetFlow(){ mstate = { stage:null, confirm:null, retry:0, spelling:false }; updFlowDbg(); }
  function updFlowDbg(){ dbg.stage = mstate.stage||'—'; dbg.awaiting = mstate.confirm?'Yes':'No'; dbg.retry = mstate.retry||0; }

  /* ── Progress tracking + resume ──────────────────────────────────────────── */
  var FIELD_ORDER=['category','location','date','time','occurrence'];
  var FIELD_NAME={category:'Category',location:'Location',date:'Date',time:'Time',occurrence:'Occurrence',notes:'Notes'};
  var progress={ done:{} };
  function persistProgress(){ try{ sessionStorage.setItem('mv.progress', JSON.stringify(progress.done)); }catch(e){} }
  function loadProgress(){ try{ var d=JSON.parse(sessionStorage.getItem('mv.progress')||'null'); progress.done = d||{}; }catch(e){ progress.done={}; } }
  function markDone(field){ if(field){ progress.done[field]=true; persistProgress(); } }
  // A field counts as completed if confirmed this session OR already filled on the form
  // (e.g. editing an existing meeting, or values entered manually).
  function formHasValue(field){
    if(field==='category')   return !!(el('meetingType') && el('meetingType').value);
    if(field==='location')   return !!(el('locationSearch') && String(el('locationSearch').value||'').trim());
    if(field==='date')       return !!((el('meetingDate')&&el('meetingDate').value) || (el('seriesStartDate')&&el('seriesStartDate').value));
    if(field==='time')       return !!(el('startTime') && el('startTime').value);
    if(field==='occurrence') return false;   // has a default — only "done" once confirmed
    return false;
  }
  function isCompleted(field){ return !!progress.done[field] || formHasValue(field); }
  function firstMissingStage(){ for(var i=0;i<FIELD_ORDER.length;i++){ if(!isCompleted(FIELD_ORDER[i])) return FIELD_ORDER[i]; } return 'notes'; }
  function completedFieldNames(){ return FIELD_ORDER.filter(isCompleted).map(function(f){ return FIELD_NAME[f]; }); }
  function joinList(a){ if(a.length<=1) return a.join(''); if(a.length===2) return a[0]+' and '+a[1]; return a.slice(0,-1).join(', ')+', and '+a[a.length-1]; }

  function startConverse(){
    ending=false;
    var fm=firstMissingStage();
    mstate={ stage:fm, confirm:null, retry:0, spelling:false }; updFlowDbg(); dbg.status='Listening';
    var done=completedFieldNames();
    // Fresh start (nothing captured yet) → warm greeting + first question.
    if(fm==='category' && !done.length) return GREETING;
    // Resume: skip completed fields, ask the first missing one.
    logConvo('System','Converse Restarted');
    logConvo('System','Existing values: '+FIELD_ORDER.map(function(f){ return FIELD_NAME[f]+(isCompleted(f)?' ✓':' ✗'); }).join('  '));
    logConvo('System','Resuming from: '+(FIELD_NAME[fm]||fm));
    return 'Welcome back. ' + (done.length ? ('I already have the '+joinList(done)+'. ') : '') + stagePrompt(fm);
  }

  /* ── Skip / Stop command vocabularies (punctuation-insensitive) ──────────── */
  function isSkip(t){
    var s=normTxt(t);
    return /^(skip|continue|next|next field|leave blank|no value|not now|later|move on|proceed|pass)$/.test(s)
        || /\b(skip (this|that|the)|leave (it|this) blank|move on|next field)\b/.test(s);
  }
  function isStopCmd(t){
    var s=normTxt(t);
    return /^(stop|stop conversation|stop listening|cancel|cancel conversation|exit|exit converse|end|end conversation|quit|goodbye|bye|i m done|im done|that s all|thats all|done)$/.test(s)
        || /\bstop (the )?conversation\b|\bcancel (the )?conversation\b|\bend (the )?conversation\b|\bexit converse\b|\bstop listening\b|\bi m done\b/.test(s);
  }
  // The stage to advance to when the current field is skipped.
  function skipNext(stage){
    var map={ category:'location', location:'date', 'location-pick':'date', 'location-spell':'date', 'location-failed':'date',
      date:'time', time:'occurrence', occurrence:'notes',
      'daily-end':'notes', 'weekly-days':'weekly-end', 'weekly-end':'notes',
      'monthly-type':'notes', 'monthly-months':'monthly-dom', 'monthly-dom':'notes',
      'monthly-week-ordinal':'monthly-week-day', 'monthly-week-day':'notes', 'notes':'complete' };
    return map[stage]||'complete';
  }

  function findCategoryText(raw){
    var sel=el('meetingType'); if(!sel) return null; var want=String(raw||'').toLowerCase().trim(), hit=null;
    for(var i=0;i<sel.options.length;i++){ var o=sel.options[i]; if(!o.value) continue; var t=(o.text||'').toLowerCase().trim();
      if(t===want) return o.text.trim(); if(!hit && t.indexOf(want)!==-1) hit=o.text.trim(); }
    return hit;
  }
  // The locations/members list. Prefer our own fetched copy, fall back to the
  // page's window.allLocations (the page assigns it after loading).
  var locList=[];
  function allLocs(){ return (locList && locList.length) ? locList : (window.allLocations||[]); }
  function loadLocations(){
    return fetch('/api/meetings/locations').then(function(r){ return r.ok?r.json():[]; })
      .then(function(j){ if(Array.isArray(j) && j.length){ locList=j; } return locList; })
      .catch(function(){ return locList; });
  }
  function findLocation(phrase){
    var list=allLocs(), want=String(phrase||'').toLowerCase().trim(); if(!want) return null; var hit=null;
    for(var i=0;i<list.length;i++){ var dn=String(list[i].displayName||'').toLowerCase();
      if(dn===want) return list[i]; if(!hit && (dn.indexOf(want)!==-1 || want.indexOf(dn)!==-1)) hit=list[i]; }
    return hit;
  }
  function parseSpelling(raw){ return String(raw||'').replace(/[^a-z0-9]/gi,'').toUpperCase(); }

  // Ask "I heard X. Is that correct?" and remember how to apply it on "yes".
  function askConfirm(c){
    c.stage = mstate.stage;                 // remember exactly which field this confirms
    mstate.confirm=c; dbg.recognized=c.display; dbg.awaiting='Yes'; dbg.status='Waiting for Confirmation';
    renderYesNoOptions();                   // typed/text mode always gets clickable Yes/No
    return 'I ' + (c.verb||'heard') + ' ' + c.display + '. Is that correct?';
  }
  // Every confirmation question is answerable in the UI (not just by voice):
  // show Yes / No buttons in the options row until the question is resolved.
  function renderYesNoOptions(){
    var box=el('mvOptions'); if(!box) return;
    box.innerHTML='<button class="mv-opt" type="button" data-yn="yes">✓ Yes</button>'
      +'<button class="mv-opt mv-opt-none" type="button" data-yn="no">✗ No</button>';
    box.style.display='flex';
    box.querySelectorAll('.mv-opt').forEach(function(b){
      b.addEventListener('click', function(){ handleUtterance(b.getAttribute('data-yn')==='yes'?'Yes':'No'); });
    });
  }
  // The question to (re)ask for a given stage — used so "No" returns to the SAME field.
  function stagePrompt(stage){
    switch(stage){
      case 'category':            return PROMPT.category;
      case 'location':            return PROMPT.location;
      case 'date':                return PROMPT.date;
      case 'time':                return PROMPT.time;
      case 'occurrence':          return PROMPT.occurrence;
      case 'daily-end':           return 'When should the daily occurrence end?';
      case 'weekly-days':         return 'Which days of the week should this meeting occur? For example: Monday, Wednesday, Friday.';
      case 'weekly-end':          return 'When should the weekly occurrence end?';
      case 'monthly-months':      return 'Which months should this meeting occur? For example: January, February, March.';
      case 'monthly-dom':         return 'What day of the month should it occur? For example "15th".';
      case 'monthly-week-ordinal':return 'Which week should it occur? First, Second, Third, Fourth, or Last.';
      case 'monthly-week-day':    return 'Which day of the week? For example "Sunday".';
      case 'notes':               return 'What note would you like to add?';
      default:                    return PROMPT.location;
    }
  }
  function handleConfirm(low){
    var c=mstate.confirm;
    // Check NO first so negations that contain positive words ("not correct") win.
    if(isNo(low)){
      // Discard the pending value and re-ask the SAME field — never jump back to Category.
      var st = c.stage || mstate.stage;
      mstate.confirm=null; mstate.stage=st; mstate.matches=null; clearOptions();
      dbg.awaiting='No'; dbg.status='Re-asking';
      dbg.confSaid=low; dbg.confDetected='NO'; dbg.confConfidence='99%'; updFlowDbg();
      logConvo('System','Discarded pending value — staying on stage: '+st);
      return "I'm sorry. Let's try again. "+stagePrompt(st);
    }
    if(isYes(low)){
      mstate.confirm=null; mstate.retry=0; mstate.spelling=false; clearOptions();
      dbg.awaiting='No'; dbg.status='Confirmed';
      dbg.confSaid=low; dbg.confDetected='YES'; dbg.confConfidence='99%';
      var r=c.onYes();   // onYes itself decides if a field was set (esp. Location, which may search)
      var fld=({category:'category',location:'location',date:'date',time:'time',occurrence:'occurrence'})[c.stage];
      if(fld) markDone(fld);   // remember progress so a later resume skips it
      // Log generic "<field> set" for plain fields; Location logs itself only when truly set.
      if(c.field && (c.fieldLower!=='location')){ dbg.fieldUpdated=c.field; dbg.fieldStatus='Confirmed'; logConvo('System', c.field+' has been set.'); }
      dbg.nextStage=mstate.stage; updFlowDbg(); return r;
    }
    dbg.confDetected='Unrecognized'; dbg.confConfidence='—';
    return 'Sorry, was that a yes or a no? Please say "yes" or "no".';
  }

  // Validation failed for the current field → log it and re-ask WITHOUT confirming.
  function failVal(label, reason){
    dbg.validation='FAILED'; dbg.validationReason=reason; dbg.field=label;
    logConvo('System','Validation: FAILED — '+reason);
    return "I didn't hear a valid "+label.toLowerCase()+". "+stagePrompt(mstate.stage);
  }
  // Category must match the list (exact / contains / fuzzy) — rejects recognition garbage.
  function categoryMatch(raw){
    var t=findCategoryText(raw); if(t) return t;
    var sel=el('meetingType'); if(!sel) return null;
    var q=normTxt(raw), best=null, bs=0;
    for(var i=0;i<sel.options.length;i++){ var o=sel.options[i]; if(!o.value) continue;
      var s=simRatio(q, o.text); if(s>bs){ bs=s; best=o.text.trim(); } }
    return bs>=0.55 ? best : null;
  }

  function matchUpdate(low){
    if (/\b(update|change|edit|set)\b.*\bdate\b/.test(low)) return 'date';
    if (/\b(update|change|edit|set)\b.*\blocation\b/.test(low)) return 'location';
    if (/\b(update|change|edit|set)\b.*\bcategor/.test(low)) return 'category';
    if (/\b(update|change|edit|set)\b.*\btime\b/.test(low)) return 'time';
    if (/\b(update|change|edit|set|add)\b.*\bnotes?\b/.test(low)) return 'notes';
    if (/\b(update|change|edit|set)\b.*\boccurrence\b/.test(low)) return 'occurrence';
    return null;
  }

  // Returns the assistant's spoken/printed response for a user utterance.
  function converseStep(text){
    var raw=String(text||'').trim(), low=raw.toLowerCase();

    // ── Stop confirmation: a spoken "stop" never ends immediately. ──
    if (mstate.stage==='stop-confirm'){
      if(isYes(low)){ logConvo('System','Stop Confirmed'); logConvo('System','Conversation Ended');
        endSession('said-stop'); return 'Okay. The conversation has been stopped.'; }
      if(isNo(low)){ var back=mstate.stopReturn||'category'; mstate.stage=back; mstate.confirm=null; updFlowDbg();
        logConvo('System','Stop cancelled — resuming '+(FIELD_NAME[back]||back)); return "Great. Let's continue. "+stagePrompt(back); }
      return 'Please say "yes" to stop, or "no" to keep going.';
    }
    if (isStopCmd(low)){
      logConvo('System','User Command: Stop — awaiting confirmation');
      mstate.stopReturn = (mstate.stage && mstate.stage!=='stop-confirm') ? mstate.stage : 'category';
      mstate.confirm=null; mstate.stage='stop-confirm'; updFlowDbg();
      return 'Would you like to stop the conversation? Please say Yes or No.';
    }

    // ── Save flow ──
    if (mstate.stage==='save-confirm'){
      if (isYes(low)){ var ok=doSave(); dbg.save='Saving'; endSession('saved');
        return ok ? 'Saving your meeting now. Thank you!' : "I'm sorry, I couldn't reach the save action on this page."; }
      if (isNo(low)){ mstate.stage='complete'; dbg.save='Cancelled';
        return "Okay, I won't save yet. You can say \"update\" with a field name to change something, or \"Save Now\" when ready."; }
      return 'Please say "yes" to save this meeting, or "no" to keep editing.';
    }
    if (isSaveNow(low) && !/^\s*save\s+(date|time|location|category|note)/.test(low)){
      mstate.stage='save-confirm'; mstate.confirm=null; dbg.save='Pending Confirmation'; dbg.status='Awaiting Save Confirmation';
      return 'Would you like me to save this meeting?';
    }

    // ── Skip the current field (keep any existing value, move on). Checked BEFORE
    //    confirmation handling so "Skip" works even while awaiting a yes/no. ──
    if (isSkip(low) && mstate.stage && mstate.stage!=='complete' && mstate.stage!=='notes-clear-confirm' && mstate.stage!=='save-confirm'){
      var cur=mstate.stage, nxt=skipNext(cur);
      // Mark the skipped field done so a later resume does NOT ask it again.
      var skf=({category:'category',location:'location',date:'date',time:'time',occurrence:'occurrence'})[cur];
      if(skf) markDone(skf);
      logConvo('System','User Command: Skip'); logConvo('System',(FIELD_NAME[cur]||cur)+' skipped');
      logConvo('System','Moving to: '+(FIELD_NAME[nxt]||nxt));
      mstate.confirm=null; mstate.retry=0; mstate.spelling=false; mstate.matches=null; mstate.stage=nxt; updFlowDbg();
      var label=(FIELD_NAME[cur]||cur).toLowerCase();
      if(nxt==='complete') return "No problem. We'll leave the "+label+" blank. "+COMPLETION;
      return "No problem. We'll leave the "+label+" blank. "+stagePrompt(nxt);
    }

    // ── Awaiting a field confirmation? ──
    if (mstate.confirm){ return handleConfirm(low); }

    // ── Jump to a field on "update <field>" ──
    var upd=matchUpdate(low);
    if (upd){ mstate.stage=upd; mstate.retry=0; mstate.spelling=false; updFlowDbg(); return reAsk(upd); }

    dbg.recognized=raw; updFlowDbg();

    switch (mstate.stage){
      case 'category': {
        var ct=categoryMatch(raw);
        if(!ct) return failVal('category','not a known category');
        dbg.validation='Passed';
        return askConfirm({ field:'Category', fieldLower:'category', display:ct,
          onYes:(function(name){ return function(){ applyCategory(name); mstate.stage='location';
            return 'Thank you. Category has been set. '+PROMPT.location; }; })(ct) });
      }
      case 'location':       return locationInput(raw);
      case 'location-pick':  return locationPick(raw, low);
      case 'location-spell': return locationSpell(raw);
      case 'location-failed': {
        if(isYes(low)){ mstate.stage='date'; mstate.retry=0; mstate.spelling=false; updFlowDbg(); return 'Okay, we can add the location later. '+PROMPT.date; }
        if(isNo(low)){ mstate.stage='location'; mstate.retry=0; mstate.spelling=false; updFlowDbg(); return 'No problem. Could you please tell me the location again?'; }
        return 'Would you like to continue with the next field? Please say "yes" or "no".';
      }
      case 'date': {
        var d=parseDate(raw); if(!d) return failVal('date','not a valid date');
        dbg.validation='Passed';
        return askConfirm({ field:'Date', fieldLower:'date', display:prettyDate(d.iso),
          onYes:(function(x){ return function(){ applyDate(x); mstate.stage='time'; return 'Thank you. The date is set. '+PROMPT.time; }; })(d) });
      }
      case 'time': {
        var t=parseTime(raw);
        if(!t){ dbg.validation='FAILED'; dbg.validationReason='not a valid time'; logConvo('System','Time Parse Failed');
          return "I didn't hear a valid time. Please say something like 7:30 PM or 19:30."; }
        dbg.validation='Passed';
        return askConfirm({ field:'Time', fieldLower:'time', display:t.display,
          onYes:(function(x){ return function(){ applyTime(x); mstate.stage='occurrence'; return 'Thank you. The time is set. '+PROMPT.occurrence; }; })(t) });
      }
      case 'occurrence': {
        var o=parseOccurrence(raw); if(!o) return PROMPT.occurrence;
        return askConfirm({ field:'Occurrence', fieldLower:'occurrence', display:o,
          onYes:(function(x){ return function(){ applyOccurrence(x);
            if(x==='One-time'){ mstate.stage='notes'; return 'Wonderful. Do you have any notes to add?'; }
            if(x==='Daily'){ mstate.stage='daily-end'; return 'Wonderful. When should the daily occurrence end?'; }
            if(x==='Weekly'){ mstate.stage='weekly-days'; return 'Wonderful. Which days of the week should this meeting occur? For example: Monday, Wednesday, Friday.'; }
            mstate.stage='monthly-type'; return 'Wonderful. Is this based on a week pattern or a specific day of the month? Say "week pattern" or "specific day".';
          }; })(o) });
      }
      case 'daily-end': {
        var de=parseDate(raw); if(!de) return failVal('end date','not a valid date');
        dbg.validation='Passed';
        return askConfirm({ field:'End date', fieldLower:'end date', display:prettyDate(de.iso),
          onYes:(function(x){ return function(){ applyEndDate(x); mstate.stage='notes'; return 'Thank you. Do you have any notes to add?'; }; })(de) });
      }
      case 'weekly-days': {
        var days=parseDays(raw); if(!days.length) return 'Which days? For example: Monday, Wednesday, Friday.';
        return askConfirm({ field:'Days', fieldLower:'days of the week', display:days.map(function(n){return DOW_NAME[n];}).join(', '),
          onYes:(function(x){ return function(){ applyWeekDays(x); mstate.stage='weekly-end'; return 'Thank you. When should the weekly occurrence end?'; }; })(days) });
      }
      case 'weekly-end': {
        var we=parseDate(raw); if(!we) return failVal('end date','not a valid date');
        dbg.validation='Passed';
        return askConfirm({ field:'End date', fieldLower:'end date', display:prettyDate(we.iso),
          onYes:(function(x){ return function(){ applyEndDate(x); mstate.stage='notes'; return 'Thank you. Do you have any notes to add?'; }; })(we) });
      }
      case 'monthly-type': {
        if(/\bweek\b/.test(low)){ applyMonthMode('week'); mstate.stage='monthly-week-ordinal';
          return 'Which week should it occur? First, Second, Third, Fourth, or Last.'; }
        if(/specific|day of (the )?month|\bdate\b|exact/.test(low)){ applyMonthMode('specific'); mstate.stage='monthly-months';
          return 'Which months should this meeting occur? For example: January, February, March.'; }
        return 'Please say "week pattern" or "specific day".';
      }
      case 'monthly-months': {
        var ms=parseMonths(raw); if(!ms.length) return 'Which months? For example: January, February, March.';
        return askConfirm({ field:'Months', fieldLower:'months', display:ms.map(function(n){return MON_NAME[n];}).join(', '),
          onYes:(function(x){ return function(){ applyMonths(x); mstate.stage='monthly-dom'; return 'Thank you. What day of the month should it occur? For example "15th".'; }; })(ms) });
      }
      case 'monthly-dom': {
        var dom=parseDayOfMonth(raw); if(!dom) return 'What day of the month? For example "15th".';
        return askConfirm({ field:'Day of month', fieldLower:'day of the month', display:ordStr(dom),
          onYes:(function(x){ return function(){ applyMonthDom(x); mstate.stage='notes'; return 'Thank you. Do you have any notes to add?'; }; })(dom) });
      }
      case 'monthly-week-ordinal': {
        var ord=parseOrdinalWeek(raw); if(!ord) return 'Which week? First, Second, Third, Fourth, or Last.';
        return askConfirm({ field:'Week', fieldLower:'week', display:ordWord(ord),
          onYes:(function(x){ return function(){ applyMonthWeekOrdinal(x); mstate.stage='monthly-week-day'; return 'Thank you. Which day of the week? For example "Sunday".'; }; })(ord) });
      }
      case 'monthly-week-day': {
        var dw=parseSingleDOW(raw); if(dw==null) return 'Which day of the week? For example "Sunday".';
        return askConfirm({ field:'Day of week', fieldLower:'day of the week', display:DOW_NAME[dw],
          onYes:(function(x){ return function(){ applyMonthWeekDay(x); mstate.stage='notes'; return 'Thank you. Do you have any notes to add?'; }; })(dw) });
      }
      case 'notes':
        if(/^\s*(no|nope|none|no thanks|skip|that'?s all|done)\s*$/i.test(low)){ mstate.stage='complete'; updFlowDbg(); return COMPLETION; }
        if(/\bclear notes?\b/i.test(low)){ mstate.stage='notes-clear-confirm'; return "Are you sure you'd like to clear the notes? Please say \"yes\" to confirm."; }
        return askConfirm({ field:'Notes', fieldLower:'note', display:'"'+raw+'"',
          onYes:(function(x){ return function(){ appendNote(x); mstate.stage='notes'; return "Thank you. Is there anything else for the notes? Say \"no\" when you're done, or \"Save Now\"."; }; })(raw) });
      case 'notes-clear-confirm':
        if(isYes(low)){ clearNote(); mstate.stage='notes'; return 'Notes cleared. Is there anything else to add? Say "no" when done.'; }
        mstate.stage='notes'; return "Okay, I'll keep the notes. Anything else? Say \"no\" when done.";
      case 'complete':
      default:
        return COMPLETION;
    }
  }

  // Location: exact match → confirm; else fuzzy match → present options;
  // else retry → spell letter-by-letter → fuzzy on the spelled value.
  function confirmLocation(loc, shownName, opts){
    opts=opts||{};
    mstate.stage='location';                              // so a "No" re-asks the location
    clearOptions(); dbg.convState='locationConfirm';
    var disp = opts.noQuote ? (shownName||loc.displayName) : ("'"+(shownName||loc.displayName)+"'");
    return askConfirm({ field:'Location', fieldLower:'location', verb:(opts.verb||'heard'), display:disp,
      onYes:(function(l){ return function(){ var f=win('selectLocation'); if(f) window.selectLocation(l.locType,l.id);
        dbg.location=l.displayName; dbg.selected=l.displayName; dbg.selectedMatch=l.displayName; dbg.matchType='Confirmed';
        dbg.fieldUpdated='Location'; dbg.fieldStatus='Confirmed'; logConvo('System','Location updated: '+l.displayName);
        mstate.retry=0; mstate.matches=null; mstate.stage='date'; clearOptions();
        return 'Thank you. Location has been set. I\'m listening. '+PROMPT.date; }; })(loc) });
  }
  // Collapse duplicate candidates (same display name) keeping the best score —
  // the user can't tell two identical "Anson Mathew" rows apart anyway.
  function dedupeMatches(matches){
    var seen={}, out=[];
    (matches||[]).forEach(function(m){
      var key=String(m.name||(m.loc&&m.loc.displayName)||'').toLowerCase().replace(/\s+/g,' ').trim();
      if(!(key in seen)){ seen[key]=out.length; out.push(m); }
      else if((m.score||0)>(out[seen[key]].score||0)){ out[seen[key]]=m; }
    });
    return out;
  }

  function presentMatches(matches, type){
    matches=dedupeMatches(matches);
    // Single result → confirm it directly instead of a one-item option list.
    if(matches.length===1){
      var m0=matches[0];
      dbg.field='Location'; dbg.matchType=type; dbg.matchingMethod=m0.method||'Phonetic + Fuzzy';
      dbg.matches='1. '+m0.name+' ('+Math.round(m0.score*100)+'%)';
      logConvo('System','Single match ('+dbg.matchingMethod+'): '+m0.name+' ('+Math.round(m0.score*100)+'%)');
      return confirmLocation(m0.loc, m0.name, { verb:'found', noQuote:true });
    }
    mstate.matches=matches; mstate.stage='location-pick';
    dbg.field='Location'; dbg.matchType=type; dbg.awaitingSel='Yes';
    dbg.convState='candidateSelection'; dbg.awaitingOptions='Option Number | No | Skip | Stop';
    dbg.matchingMethod = (matches[0] && matches[0].method) ? matches[0].method : 'Phonetic + Fuzzy';
    dbg.matches=matches.map(function(m,i){ return (i+1)+'. '+m.name+' ('+Math.round(m.score*100)+'%)'; }).join('   ');
    renderLocationOptions(matches);
    logConvo('System','Candidate Count: '+matches.length);
    logConvo('System', 'Matches found ('+dbg.matchingMethod+'): '+matches.map(function(m,i){ return (i+1)+'. '+m.name+' ('+Math.round(m.score*100)+'%)'; }).join(', '));
    var lines=matches.map(function(m,i){ return 'Option '+(i+1)+'. '+m.name; }).join('  ');
    return 'I found some matching names. '+lines+'. Please say the option number, say "No" if none match, say "Skip", or say "Stop".';
  }
  function locationInput(raw){
    dbg.field='Location'; dbg.recognized=raw;
    var hit=findLocation(raw);
    if(hit){ mstate.retry=0; dbg.matchType='Exact'; logConvo('System','Location Search: Exact match — '+hit.displayName);
      return confirmLocation(hit, raw); }
    // No exact match → fuzzy + phonetic. Log all candidates but only PRESENT strong
    // (>=80%) or possible (70-79%) matches — discard weak/irrelevant ones (<70%).
    var all=fuzzyMatches(raw, null, 0, 6);
    var matches=all.filter(function(m){ return m.score>=0.70; });
    logConvo('System','Location Search: No exact match found.');
    if(all.length) logConvo('System','Fuzzy Search Results: '+all.map(function(m){ return m.name+' ('+Math.round(m.score*100)+'%)'; }).join(', '));
    if(matches.length){
      dbg.matchingMethod=matches[0].method||'Phonetic + Fuzzy';
      return presentMatches(matches,'Phonetic + Fuzzy');
    }
    logConvo('System','Location Search: No match found.');
    dbg.matchType='None'; dbg.status='Location not found';
    mstate.retry=(mstate.retry||0)+1; dbg.retry=mstate.retry; logConvo('System','Retry Count: '+mstate.retry);
    // Attempt 1 → ask again; Attempt 2 → switch to spelling (no endless loop).
    if(mstate.retry>=2){ mstate.stage='location-spell'; mstate.spelling=true; updFlowDbg();
      return "I'm sorry, I'm still unable to find a matching location. Could you please spell it letter by letter? For example, B I N N Y."; }
    return "I couldn't find a match. Could you please say it again?";
  }
  // User chose an option (or said No) from the presented matches.
  function locationPick(raw, low){
    var matches=mstate.matches||[];
    // "No / none / not listed / different person / try again" → exit selection,
    // discard candidates, and ask the location again (do NOT loop on options).
    if(isNo(low) || /\b(different person|not listed|try again|wrong (one|person))\b/.test(normTxt(low))){
      logConvo('System','Candidate Selection Rejected'); logConvo('System','Returning to: Location Capture');
      mstate.matches=null; clearOptions(); dbg.awaitingSel='No'; dbg.convState='locationCapture';
      mstate.stage='location'; updFlowDbg();
      return "No problem. Let's try again. "+PROMPT.location;
    }
    var idx=parseOption(raw, matches.length);
    if(!idx){
      var upTo=matches.length>1?(' to Option '+matches.length):'';
      return 'Please say the option number — for example "Option 1"'+upTo+' — or say "No" to try a different name, "Skip" to skip the location, or "Stop".';
    }
    // The user EXPLICITLY chose an option (clicked or said the number) — apply it
    // immediately. Re-asking "Is that correct?" here was redundant and, in typed
    // mode, unanswerable; the choice IS the confirmation.
    var m=matches[idx-1];
    clearOptions(); mstate.matches=null; mstate.retry=0;
    var f=win('selectLocation'); if(f) window.selectLocation(m.loc.locType, m.loc.id);
    setVal('locationSearch', m.name||m.loc.displayName);
    dbg.location=m.loc.displayName; dbg.selected=m.loc.displayName; dbg.selectedMatch=m.loc.displayName;
    dbg.matchType='User Selected'; dbg.fieldUpdated='Location'; dbg.fieldStatus='Confirmed';
    logConvo('System','Location selected by user: '+m.loc.displayName);
    markDone('location');
    // In "Fill Form" (direct) mode the other fields were already populated before
    // the choice was requested — finish there instead of restarting the interview.
    if(mstate.afterPick==='complete'){
      mstate.afterPick=null; mstate.stage='complete'; updFlowDbg();
      dbg.result='Form populated'; dbg.missing=missingFields(); renderDebug();
      return 'Thank you. Location has been set to '+(m.name||m.loc.displayName)
           + '. The meeting form is populated'
           + (missingFields()==='None' ? ' — say "Save Now" to save.' : ' — please review the remaining fields.');
    }
    mstate.stage='date'; updFlowDbg();
    return 'Thank you. Location has been set to '+(m.name||m.loc.displayName)+'. '+PROMPT.date;
  }
  function locationSpell(raw){
    var cand=parseSpelling(raw), disp=cand.split('').join('-');
    return askConfirm({ field:'Location', fieldLower:'location', display:disp,
      onYes:(function(c){ return function(){
        // Normalize the spelled value and ALWAYS run a fuzzy/phonetic search — present
        // candidates rather than failing immediately.
        var allS=fuzzyMatches(c, null, 0, 6);
        var matches=allS.filter(function(m){ return m.score>=0.70; });
        logConvo('System','Spelling search for "'+c+'": '+(allS.length?allS.map(function(m){return m.name+' ('+Math.round(m.score*100)+'%)';}).join(', '):'no candidates'));
        if(matches.length){ mstate.spelling=false; dbg.matchingMethod=matches[0].method||'Phonetic + Fuzzy';
          return presentMatches(matches,'Phonetic + Fuzzy (spelled)'); }
        mstate.spelling=false; mstate.stage='location-failed';
        return "I'm sorry. I wasn't able to find that location. You may enter it manually later if needed. Would you like to continue with the next field?";
      }; })(cand) });
  }

  function applyMonthMode(mode){
    if(mode==='week'){ setVal('monthDayOfMonth',''); dbg.monthlyType='Week Pattern'; }
    else { setVal('monthWeekOrdinal',''); setVal('monthWeekDay',''); dbg.monthlyType='Specific Day'; }
  }
  function reAsk(stage){
    if(stage==='category') return 'Sure. '+PROMPT.category;
    if(stage==='location') return 'Sure. '+PROMPT.location;
    if(stage==='date')     return 'Sure. '+PROMPT.date;
    if(stage==='time')     return 'Sure. '+PROMPT.time;
    if(stage==='occurrence')return 'Sure. '+PROMPT.occurrence;
    if(stage==='notes')    return 'What note would you like to add? You can dictate it, or say "clear notes".';
    return COMPLETION;
  }

  /* ════════════════════════ Direct create (Type / Voice) ════════════════════════ */
  /* ── Client-side natural-language meeting parser (Type/Voice "Send") ─────────
     Parses a one-shot request like "Create a Prayer Meeting in Benny Peter's
     house at 4:30 PM on Saturday" into Category / Location / Date / Time /
     Occurrence and auto-populates the form. Falls back to the LLM extractor only
     when nothing meaningful is recognized. */
  var CATEGORY_PHRASES=['prayer meeting','bible study','cottage meeting','youth meeting',
    "women's fellowship","men's fellowship",'sunday school','choir practice',
    'leadership meeting','prayer ministry','kids ministry','worship service','sunday service'];
  function detectCategory(t){
    var low=normTxt(t), best=null, bl=0;     // normTxt strips apostrophes → "womens fellowship"
    CATEGORY_PHRASES.forEach(function(p){ var np=normTxt(p);
      if(np && low.indexOf(np)!==-1 && np.length>bl){ best=p; bl=np.length; } });
    if(best) return categoryMatch(best) || best;   // map to an actual dropdown option when possible
    return null;
  }
  function cleanLoc(s){
    return String(s||'')
      .replace(/(?:'s|’s|s')\s+(?:house|home|place|residence|office)\b.*/i,'')
      .replace(/^(?:the)\s+/i,'').replace(/[.,;:]+$/,'').replace(/\s+/g,' ').trim();
  }
  function detectLocationName(t){
    // 1) "at/in <Name>'s house/home/place"
    var m=t.match(/\b(?:at|in)\s+(.+?)(?:'s|’s|s')\s+(?:house|home|place|residence|office)\b/i);
    if(m) return cleanLoc(m[1]);
    // 2) "at/in <Place>" up to a time/date/occurrence boundary
    m=t.match(/\b(?:at|in)\s+(.+?)(?=\s+(?:at|on|every|each|daily|weekly|monthly|fortnightly|next|this|coming|today|tomorrow|tonight|starting|beginning|from|\d|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b|[.,;]|$)/i);
    if(m){ var n=cleanLoc(m[1]); if(n && n.length>1) return n; }
    return null;
  }
  // Strip explicit time tokens before date parsing so "at 7 PM" never feeds the day regex.
  function detectDate(t){
    var clean=String(t||'')
      .replace(/\b\d{1,2}\s*[:.]\s*\d{2}\s*(?:[ap]\.?\s*m\.?)?/gi,' ')   // 4:30 PM, 16:30, 4.30 p.m.
      .replace(/\b\d{1,2}\s*(?:[ap]\.?\s*m\.?)\b/gi,' ')                  // 7 PM, 6 AM
      .replace(/\bo'?clock\b/gi,' ');
    return parseDate(clean);
  }
  function detectOccurrence(t){
    var occ=parseOccurrence(t), days=null, defaulted=false, low=String(t||'').toLowerCase();
    if(/\bevery\b|\beach\b/.test(low) && !/\bevery\s+day\b|\beach\s+day\b/.test(low)){
      var d=parseDays(t);
      if(d.length){ days=d; if(!occ || occ==='One-time') occ='Weekly'; }
    }
    if(!occ){ occ='One-time'; defaulted=true; }
    return { occ:occ, days:days, defaulted:defaulted };
  }
  function dateHint(t){
    var low=String(t||'').toLowerCase(), m;
    if((m=low.match(/\b(?:next |this |coming )?(today|tomorrow|tonight|yesterday|sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b/))) return m[0].trim();
    if((m=low.match(/\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s*\d{4})?/))) return m[0].trim();
    if((m=low.match(/\b\d{1,2}[\/\-]\d{1,2}(?:[\/\-]\d{2,4})?\b/))) return m[0];
    return 'date';
  }
  function mdy(isoStr){ var p=String(isoStr||'').split('-'); return p.length===3 ? (p[1]+'/'+p[2]+'/'+p[0]) : isoStr; }

  function parseMeetingRequest(text){
    var t=String(text||'');
    var cat=detectCategory(t);
    var locName=detectLocationName(t);
    var o=detectOccurrence(t);
    var time=parseTime(t);
    var date=(o.occ==='One-time') ? detectDate(t) : null;   // recurrence carries its own schedule
    var got=0;
    if(cat) got++; if(locName) got++; if(time) got++;
    if(date || o.occ!=='One-time') got++;       // a calendar date OR a real recurrence
    got++;                                       // occurrence is always resolved
    var confidence=Math.round((got/5)*100);
    if(o.defaulted) confidence=Math.max(70, confidence-4);   // small ding for a defaulted occurrence
    return { category:cat, location:locName, occurrence:o.occ, days:o.days,
             defaulted:o.defaulted, time:time, date:date, confidence:confidence };
  }
  function detectedSummary(p){
    var lines=['AI detected:',
      'Category: '  + (dbg.category || p.category || 'Not detected'),
      'Location: '  + (p.location || 'Not detected')];
    if(p.occurrence==='One-time') lines.push('Date: ' + (p.date ? prettyDate(p.date.iso) : 'Not detected'));
    else if(p.days && p.days.length) lines.push('Days: ' + p.days.map(function(n){ return DOW_NAME[n]; }).join(', '));
    lines.push('Time: ' + (p.time ? p.time.display : 'Not detected'));
    lines.push('Occurrence: ' + p.occurrence + (p.kept ? ' (unchanged)' : (p.defaulted ? ' (default)' : '')));
    lines.push('Confidence: ' + p.confidence + '%');
    return lines.join('\n');
  }

  function directCreate(text){
    busy(true); dbg.input=text; dbg.intent='Create Meeting';
    var p=parseMeetingRequest(text);
    // Nothing recognized client-side → defer to the LLM extractor.
    if(!p.category && !p.location && !p.time && !p.date){ return llmExtract(text); }

    // Apply occurrence first so date routes to the right field and the weekly-day UI
    // appears. A DEFAULTED occurrence (not mentioned in the text) is applied only for a
    // fresh "create …" request — a partial update must not reset an existing choice.
    var isCreateReq=/\b(create|add|schedule|set\s?up|new)\b/i.test(text);
    if(p.defaulted && !isCreateReq){
      p.occurrence=(el('occurrence')&&el('occurrence').value)||'One-time'; p.kept=true;   // keep the form's value
    } else {
      applyOccurrence(p.occurrence);
    }
    if(p.days && p.days.length) applyWeekDays(p.days);
    if(p.category) applyCategory(p.category);
    if(p.time)     applyTime(p.time);
    if(p.date)     applyDate(p.date);

    // ── Location: exact / single fuzzy → populate; multiple → present choices. ──
    var locNote='';
    if(p.location){
      var hit=findLocation(p.location);
      if(hit){ setLoc(hit); }
      else {
        var all=fuzzyMatches(p.location, null, 0, 6);
        var matches=dedupeMatches(all.filter(function(m){ return m.score>=0.70; }));
        if(matches.length===1){ setLoc(matches[0].loc, matches[0].name, matches[0].score); }
        else if(matches.length>1){
          busy(false);
          logDetect(text, p);
          logConvo('System','Location ambiguous — '+matches.length+' candidates, awaiting choice');
          mstate.matches=matches; mstate.stage='location-pick';
          mstate.afterPick='complete';   // rest of the form was already populated above
          dbg.field='Location'; dbg.convState='candidateSelection';
          dbg.matches=matches.map(function(m,i){ return (i+1)+'. '+m.name+' ('+Math.round(m.score*100)+'%)'; }).join('   ');
          renderLocationOptions(matches);
          dbg.result='Awaiting location choice'; dbg.missing=missingFields(); renderDebug();
          respond(detectedSummary(p)+'\n\nI found more than one matching location — please choose:\n'
            + matches.map(function(m,i){ return (i+1)+'. '+m.name; }).join('\n'));
          return Promise.resolve();
        } else {
          setVal('locationSearch', p.location); dbg.location=p.location;
          logConvo('System','Location entered as text (no match): '+p.location);
          locNote='\n(Note: "'+p.location+'" wasn\'t found in your list — entered as typed; please verify.)';
        }
      }
    }

    busy(false);
    logDetect(text, p);
    mstate.stage='complete';                       // so "Save Now" works next
    dbg.confidence=p.confidence+'%'; dbg.result='Form populated'; dbg.missing=missingFields(); renderDebug();
    respond(detectedSummary(p)+'\n\nPopulating meeting form…'+locNote);
    speakIf("I've filled in the meeting details. "
      + (missingFields()==='None' ? 'Everything looks set — say "Save Now" to save.'
                                  : 'Please review the highlighted fields.'));
    return Promise.resolve();

    function setLoc(loc, shown, score){
      var sf=win('selectLocation'); if(sf) window.selectLocation(loc.locType, loc.id);
      setVal('locationSearch', shown||loc.displayName);
      dbg.location=loc.displayName; dbg.selected=loc.displayName;
      logConvo('System','Location set: '+loc.displayName + (score!=null ? (' ('+Math.round(score*100)+'%)') : ''));
    }
  }
  // Step-by-step recognition trail for the debug panel / conversation history.
  function logDetect(text, p){
    logConvo('System','Intent: Create Meeting');
    logConvo('System','Category Detected: '+(dbg.category || p.category || '—'));
    logConvo('System','Location Detected: '+(p.location || '—'));
    if(p.occurrence==='One-time')
      logConvo('System','Date Detected: '+(p.date ? (dateHint(text)+' -> '+mdy(p.date.iso)) : '— (none specified)'));
    else if(p.days && p.days.length)
      logConvo('System','Days Detected: '+p.days.map(function(n){ return DOW_NAME[n]; }).join(', '));
    logConvo('System','Time Detected: '+(p.time ? p.time.display : '—'));
    logConvo('System','Occurrence Detected: '+p.occurrence+(p.kept ? ' (kept existing)' : (p.defaulted ? ' (defaulted)' : '')));
    logConvo('System','Confidence: '+p.confidence+'%');
  }
  // LLM fallback — only used when the client parser recognizes nothing.
  function llmExtract(text){
    busy(true); dbg.intent='Create Meeting';
    return fetch('/api/voice/extract', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ text:text, context:'meeting' })
    }).then(function(r){ return r.ok ? r.json() : null; }).then(function(j){
      busy(false);
      var f=(j && j.fields) || null;
      if(!f || !Object.keys(f).length){
        respond('I couldn\'t pull meeting details from that. Try: "Create a Bible Study at John\'s house on June 3rd at 4 pm", or switch to Converse mode.');
        dbg.result='No fields extracted'; renderDebug(); return;
      }
      applyFields(f);
      mstate.stage='complete';                       // so "Save Now" works next
      dbg.confidence='95%'; dbg.result='Form populated'; dbg.missing=missingFields(); renderDebug();
      respond(FILLED);
      speakIf(FILLED);
    }).catch(function(){ busy(false); respond('Something went wrong extracting the details. Please try again.'); });
  }
  function missingFields(){
    var miss=[];
    if(!(el('meetingType')&&el('meetingType').value)) miss.push('Category');
    var occ=el('occurrence')?el('occurrence').value:'One-time';
    var dateOk = occ==='One-time' ? (el('meetingDate')&&el('meetingDate').value) : (el('seriesStartDate')&&el('seriesStartDate').value)||occ!=='Daily';
    if(!dateOk) miss.push('Date');
    if(!(el('startTime')&&el('startTime').value)) miss.push('Time');
    return miss.length ? miss.join(', ') : 'None';
  }

  /* ════════════════════════ Entry point (all modes) ════════════════════════ */
  function handleUtterance(text){
    text=String(text||'').trim(); if(!text) return;
    dbg.mode=curMode(); dbg.input=text; dbg.transcript=(mode!=='type'?text:'—');
    dbg.raw=text; dbg.normalized=normTxt(text);
    logConvo('User', text);
    // A NEW full request typed/spoken outside an active Converse session is always
    // re-parsed and applied — updating fields even when they're already populated.
    // (A previous Send leaves stage 'complete', which used to swallow new requests.)
    if (mode!=='converse' && !conversationActive){
      var probe=parseMeetingRequest(text);
      var comps=(probe.category?1:0)+(probe.location?1:0)+(probe.time?1:0)+(probe.date?1:0)+((probe.days&&probe.days.length)?1:0);
      var createVerb=/\b(create|add|schedule|set\s?up|new)\b/i.test(text);
      if (comps>=2 || (createVerb && comps>=1)){
        mstate.stage=null; mstate.confirm=null; mstate.matches=null; updFlowDbg(); clearOptions();
        directCreate(text);
        return;
      }
    }
    // An active flow (converse, notes, complete, or pending save) continues.
    if (mstate.stage){
      dbg.currentQuestion = lastAiQuestion || '—';
      dbg.intent = detectIntent(mstate.stage, text);
      var cand = mstate.confirm ? String(mstate.confirm.display||'').replace(/^['"‘’]+|['"‘’]+$/g,'') : null;
      dbg.context = JSON.stringify({ stage: mstate.stage + (mstate.confirm?'_confirmation':''), candidateLocation:cand, retryCount: mstate.retry||0 });
      dbg.aiRequest = JSON.stringify({ context:'meeting_converse', stage:mstate.stage, userInput:text, meetingData:{ category:dbg.category||null } });
      dbg.aiResponse = JSON.stringify({ intent:dbg.intent, confidence:confForIntent(dbg.intent) });
      var searching = needsProcessing(mstate.stage, text);
      if(searching){ dbg.status='Processing'; dbg.micStatus='Processing'; logConvo('AI','Processing, please wait.'); }
      var say=converseStep(text); updFlowDbg(); dbg.missing=missingFields();
      logConvo('AI', say); lastAiQuestion=say; renderDebug(); respond(say);
      // Speak the processing notice ahead of the result so the user hears both.
      speakIf(searching ? ('Processing, please wait. '+say) : say);
      return;
    }
    // Only auto-start a guided flow when a session is actually active (set by the
    // Converse button). This prevents a "ghost restart" from a stray transcript
    // captured just after the conversation was stopped.
    if (mode==='converse'){
      if(!conversationActive || ending) return;
      var s=startConverse(); logConvo('AI', s); lastAiQuestion=s; renderDebug(); respond(s); speakIf(s); return;
    }
    directCreate(text);                              // Type / Voice
  }
  function confForIntent(intent){ return /Confirmation|Selection|Spelling/.test(intent||'') ? 0.99 : ((intent==='Unrecognized')?0.4:0.95); }
  // Turns that involve a search / lookup / validation get a "Processing, please wait." notice.
  function needsProcessing(stage, text){
    if(mstate.confirm){ return stage==='location-spell' && isYes(text); }   // only the spelling confirm searches
    return stage==='location' || stage==='category' || stage==='location-spell';
  }
  function detectIntent(stage, raw){
    if(mstate.confirm){ if(isNo(raw)) return 'ConfirmationNo'; if(isYes(raw)) return 'ConfirmationYes'; return 'Unrecognized'; }
    if(stage==='save-confirm'){ if(isNo(raw)) return 'ConfirmationNo'; if(isYes(raw)) return 'ConfirmationYes'; return 'Unrecognized'; }
    if(stage==='notes-clear-confirm'){ return isYes(raw)?'ConfirmationYes':'ConfirmationNo'; }
    if(stage==='location-pick'){ if(isNo(raw)) return 'ConfirmationNo'; if(parseOption(raw,(mstate.matches||[]).length)) return 'LocationSelection'; return 'Unrecognized'; }
    var map={category:'CategoryInput',location:'LocationInput',date:'DateInput',time:'TimeInput',occurrence:'OccurrenceInput',notes:'NotesInput',
      'daily-end':'DateInput','weekly-days':'DaysInput','weekly-end':'DateInput','monthly-type':'MonthlyTypeInput','monthly-months':'MonthsInput',
      'monthly-dom':'DayOfMonthInput','monthly-week-ordinal':'WeekOrdinalInput','monthly-week-day':'WeekDayInput','location-spell':'Spelling','location-failed':'ContinueChoice'};
    return map[stage]||'General';
  }

  /* ════════════════════════ UI ════════════════════════ */
  var mode='type', speaking=false, recorder=null;
  // ── Dictation-to-input: when the user focuses #mvInput and then activates
  //    Voice/Converse, speech is TYPED into the field (editable) instead of being
  //    processed immediately; Send/Enter then runs the normal parsing. ──
  var dictateToInput=false, dictationSession=false, _armHold=0;
  var dictTimer=null, DICT_MS=60000;     // auto-stop dictation after 1 min of no "Done"/"Stop"

  // ── Whisper hallucination filter ──────────────────────────────────────────
  // On silence or low audio, the speech model sometimes emits canned video-outro
  // text ("Thanks for watching", "Please subscribe", "Let's do this", subtitle
  // credits, etc.). These were never spoken — strip them so only real words remain.
  var HALLU_STRONG=[
    /thank(s| you)?\s+(for|to)\s+watching[^.!?]*/gi,
    /(please\s+)?(don'?t forget to\s+)?(like(,|\s+and)?\s*)?(comment(,|\s+and)?\s*)?subscribe[^.!?]*/gi,
    /(please\s+)?leave\s+(your\s+)?(comments?|likes?)(\s+and\s+(comments?|likes?))?[^.!?]*/gi,
    /like(,|\s+and)?\s*comment(,|\s+and)?\s*(and\s+)?subscribe[^.!?]*/gi,
    /(i'?ll\s+)?see you (in the )?next (time|video|one)[^.!?]*/gi,
    /let'?s do this[.!]?/gi,
    /(sub)?titles?\s+(by|provided by)[^.!?]*/gi,
    /transcription\s+(by|provided by)[^.!?]*/gi,
    /amara\.org\S*/gi,
    /\[?\s*music\s*\]?/gi,
    /\bbye(\s*bye)?[.!]?/gi
  ];
  function stripStrong(raw){
    var s=' '+String(raw||'')+' ';
    HALLU_STRONG.forEach(function(re){ s=s.replace(re,' '); });
    return s.replace(/\s+([.,!?])/g,'$1').replace(/([.,!?])[\s.,!?]*([.,!?])/g,'$1')   // collapse leftover ".." / ". ."
            .replace(/\s{2,}/g,' ').replace(/^[\s.,!?-]+|[\s,-]+$/g,'').trim();
  }
  // Whole transcript is nothing but hallucinated filler → drop it entirely.
  function isStrongHallucination(raw){
    var orig=normTxt(raw); if(!orig) return false;
    return stripStrong(raw).replace(/[^a-z0-9]/gi,'')==='' ;
  }
  // Pure-filler sentences only stripped inside dictation (never in yes/no Q&A, so a
  // genuine "okay" / "yes" answer is never lost).
  var FILLER_SENTENCE=/^(thank you( so much)?|thanks( a lot)?|you|so|um+|uh+|hmm+|okay|ok|music|please|right)$/;
  function stripHallucinations(raw){
    var s=stripStrong(raw);
    var parts=s.split(/([.!?]+)/), out='';
    for(var i=0;i<parts.length;i+=2){
      var seg=(parts[i]||'').trim(), delim=parts[i+1]||'';
      var norm=seg.toLowerCase().replace(/[^a-z0-9 ]+/g,' ').replace(/\s+/g,' ').trim();
      if(!norm || FILLER_SENTENCE.test(norm)) continue;     // drop empty / pure-filler sentence
      out += (out?' ':'') + seg + delim;
    }
    return out.replace(/\s+([.,!?])/g,'$1').replace(/([.,!?])[\s.,!?]*([.,!?])/g,'$1')
              .replace(/\s{2,}/g,' ').replace(/^[\s.,!?-]+/,'').trim();
  }
  // "… Done." / "… Stop" — a spoken termination command (kept OUT of the text).
  var TERMINATOR=/(^|\s)(done|stop|that'?s all|that is all|finished?|submit|over)\s*[.!]*\s*$/i;
  function hasTerminator(t){ return TERMINATOR.test(String(t||'')); }
  function stripTerminator(t){ return String(t||'').replace(TERMINATOR,'').replace(/[\s,.!-]+$/,'').trim(); }

  function resetDictTimer(){
    if(!dictationSession) return;
    clearTimeout(dictTimer);
    dictTimer=setTimeout(function(){ logConvo('System','Auto-stopped — 1 minute with no "Done".'); finalizeDictation('timeout'); }, DICT_MS);
  }
  // End a dictation session cleanly: stop the mic, keep ONLY the captured text,
  // restore Type mode (so Send parses it), and confirm.
  function finalizeDictation(){
    clearTimeout(dictTimer);
    var inp=el('mvInput'); var v=inp?String(inp.value||'').trim():'';
    if(inp) inp.value=v;
    finishDictation();
    if(v){ respond('Command captured. Review the text and click Send.'); speakIf('Command captured.'); }
    else { respond('I didn\'t catch any speech. Please try again, or type your request.'); }
    dbg.dictation='Captured'; renderDebug();
    if(inp){ try{ inp.focus(); }catch(e){} }
  }
  function finishDictation(){
    if(!dictateToInput && !dictationSession) return;
    dictateToInput=false; dbg.dictation='Off'; clearTimeout(dictTimer);
    // Converse dictation: switch back to Type — this ends the listening session AND
    // ensures the submitted text is parsed (converse mode would otherwise swallow it).
    if(dictationSession){ dictationSession=false; setMode('type'); }
    else if(mode==='voice' && recorder && recorder.isRecording && recorder.isRecording()){ try{ recorder.stop(); }catch(e){} }
  }
  // Converse session: stays active (listening) for up to 2 minutes, restarting
  // the recorder between turns, until Stop / "stop" / save / navigation / timeout.
  var SESSION_MS=300000;   // 5-minute Converse session
  var conversationActive=false, ending=false, ttsActive=false;
  var sessionStart=0, sessionTimeoutId=null, sessionTick=null;
  var convo=[], lastAiQuestion='';     // running conversation history + last AI prompt
  function fmtMMSS(ms){ var s=Math.max(0,Math.floor(ms/1000)); return pad(Math.floor(s/60))+':'+pad(s%60); }
  function curMode(){ return mode.charAt(0).toUpperCase()+mode.slice(1); }
  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
  function busy(on){ var b=el('mvSend'); if(b){ b.disabled=!!on; b.textContent=on?'…':'Fill Form'; } }
  function respond(msg){ var p=el('mvReply'); if(p){ p.textContent=msg; p.style.display='block'; } }

  // ── Conversation history + debug export ──
  function clockTs(ms){ var d=new Date(ms||Date.now()), h=d.getHours(), ap=h<12?'AM':'PM'; h=h%12||12; return h+':'+pad(d.getMinutes())+':'+pad(d.getSeconds())+' '+ap; }
  function logConvo(who, text){ if(text==null||text==='') return; convo.push({t:Date.now(), who:who, text:String(text)}); if(convo.length>400) convo.shift(); renderHistory(); }
  function renderHistory(){
    var box=el('mvHistory'); if(!box) return;
    box.innerHTML = convo.slice(-50).map(function(e){
      var cls = e.who==='User'?'mv-h-user':(e.who==='AI'?'mv-h-ai':'mv-h-sys');
      return '<div class="mv-h-row"><span class="mv-h-t">['+clockTs(e.t)+']</span> <span class="'+cls+'">'+esc(e.who)+':</span> '+esc(e.text)+'</div>';
    }).join('');
    box.scrollTop = box.scrollHeight;
  }
  function safeParse(s){ try{ return JSON.parse(s); }catch(e){ return s||null; } }
  function exportDebug(){
    var data={
      exportedAt: new Date().toISOString(),
      page: '/meetings',
      conversationStatus: { conversationActive:conversationActive, listening:dbg.listening, speaking:dbg.aiSpeaking,
                            currentStage:mstate.stage||null, retryCount:mstate.retry||0,
                            sessionDuration: sessionStart?fmtMMSS(Date.now()-sessionStart):'00:00' },
      lastTurn: { rawTranscript:dbg.raw, normalizedTranscript:dbg.normalized, intent:dbg.intent,
                  currentQuestion:dbg.currentQuestion, context:safeParse(dbg.context),
                  aiRequest:safeParse(dbg.aiRequest), aiResponse:safeParse(dbg.aiResponse) },
      confirmation: { said:dbg.confSaid, detected:dbg.confDetected, confidence:dbg.confConfidence },
      fuzzyMatches: dbg.matches, matchType:dbg.matchType,
      fieldUpdate: { field:dbg.fieldUpdated, value:dbg.selected||dbg.fieldValue, status:dbg.fieldStatus },
      meetingData: { category:dbg.category, location:dbg.location, date:dbg.date, time:dbg.time, occurrence:dbg.occurrence,
                     monthlyType:dbg.monthlyType, days:dbg.days, months:dbg.months, dayOfMonth:dbg.monthDay, notes:dbg.notes },
      conversationHistory: convo.map(function(e){ return { time:clockTs(e.t), who:e.who, text:e.text }; }),
      timing: { sessionStart: sessionStart?new Date(sessionStart).toISOString():null, sessionLimitMs:SESSION_MS }
    };
    try{
      var blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'});
      var a=document.createElement('a'); a.href=URL.createObjectURL(blob); a.download='conversation-debug.json';
      document.body.appendChild(a); a.click(); setTimeout(function(){ URL.revokeObjectURL(a.href); if(a.parentNode) a.parentNode.removeChild(a); }, 200);
    }catch(e){ respond('Could not export the debug log in this browser.'); }
  }

  // Render fuzzy-match results as clickable buttons (clicking == saying the option).
  function renderLocationOptions(matches){
    var box=el('mvOptions'); if(!box) return;
    var html=matches.map(function(m,i){ return '<button class="mv-opt" type="button" data-opt="'+(i+1)+'">'+(i+1)+'. '+esc(m.name)+' <span class="mv-opt-pct">'+Math.round(m.score*100)+'%</span></button>'; }).join('');
    html+='<button class="mv-opt mv-opt-none" type="button" data-opt="none">None of These</button>';
    box.innerHTML=html; box.style.display='flex';
    box.querySelectorAll('.mv-opt').forEach(function(b){ b.addEventListener('click', function(){ var o=b.getAttribute('data-opt'); handleUtterance(o==='none'?'None of these':('Option '+o)); }); });
  }
  function clearOptions(){ var box=el('mvOptions'); if(box){ box.innerHTML=''; box.style.display='none'; } dbg.awaitingSel='No'; }
  // Pick a warm, female English voice for a welcoming, church-friendly tone.
  var _voice=null;
  function pickVoice(){
    if(!('speechSynthesis' in window)) return null;
    var vs=window.speechSynthesis.getVoices()||[];
    var en=vs.filter(function(v){ return /^en/i.test(v.lang||''); });
    var prefer=['samantha','victoria','karen','moira','tessa','fiona','serena','allison','ava','susan','joanna',
                'zira','aria','jenny','jane','michelle','sonia','libby','google us english','female','woman'];
    for(var i=0;i<prefer.length;i++){ for(var j=0;j<en.length;j++){ if((en[j].name||'').toLowerCase().indexOf(prefer[i])>=0) return en[j]; } }
    // Avoid obviously male voices, else fall back to the first English voice.
    var male=/(david|mark|alex|fred|daniel|george|james|paul|tom|guy|male|man)/i;
    for(var k=0;k<en.length;k++){ if(!male.test(en[k].name||'')) return en[k]; }
    return en[0]||vs[0]||null;
  }
  function ensureVoice(){ if(!_voice) _voice=pickVoice(); return _voice; }
  if('speechSynthesis' in window){ try{ window.speechSynthesis.onvoiceschanged=function(){ _voice=pickVoice(); }; }catch(e){} }

  function setSpeakingDebug(){ dbg.aiSpeaking='Yes'; dbg.listening='No'; dbg.micStatus='Paused (AI Speaking)'; dbg.ttsStatus='Speaking'; dbg.status='Waiting for speech completion'; renderDebug(); }
  function setListeningDebug(){ dbg.aiSpeaking='No'; dbg.listening=conversationActive?'Yes':'No'; dbg.micStatus=conversationActive?'Listening':'Idle'; dbg.ttsStatus='Complete'; if(conversationActive) dbg.status='Waiting for user response'; renderDebug(); }

  function speakIf(msg){
    if(!speaking || !('speechSynthesis' in window)) return;
    try{
      // INVARIANT: while the AI speaks, listening is OFF (paused, not just muted)
      // so the recognizer cannot hear the AI. Remember the text for the echo guard.
      ttsActive=true; rememberSpoken(msg); setSpeakingDebug();
      try{ if(recorder && recorder.isRecording && recorder.isRecording()) recorder.stop(); }catch(e){}
      if(recorder && recorder.setMuted) recorder.setMuted(true);
      window.speechSynthesis.cancel();
      var u=new SpeechSynthesisUtterance(String(msg||'').slice(0,400));
      var v=ensureVoice(); if(v) u.voice=v;
      u.rate=0.98;      // calm, unhurried
      u.pitch=1.12;     // warm, friendly
      u.volume=1;
      var resumed=false;
      var doneTts=function(){
        if(resumed) return; resumed=true;
        ttsActive=false; if(recorder&&recorder.setMuted) recorder.setMuted(false);
        setListeningDebug();
        // Resume listening only AFTER speech completes, with a short guard delay
        // so the tail of the spoken audio isn't captured.
        if(conversationActive && !ending){
          // Safety delay (~800ms) so speaker playback fully settles before we listen.
          setTimeout(function(){ if(conversationActive && !ending && !ttsActive && recorder && recorder.isRecording && !recorder.isRecording()){ try{ recorder.start(); }catch(e){} } }, 800);
        }
      };
      u.onend=u.onerror=doneTts;
      window.speechSynthesis.speak(u);
      setTimeout(doneTts, Math.min(12000, 1500 + String(msg||'').length*60));   // safety net
    }catch(e){ ttsActive=false; if(recorder&&recorder.setMuted) recorder.setMuted(false); setListeningDebug(); }
  }

  var dbg={ mode:'Type', intent:'', input:'', recognized:'', awaiting:'No', retry:0, status:'Idle', nextStage:'',
            aiSpeaking:'No', listening:'No', ignored:'', ignoreReason:'',
            field:'', matchType:'', matches:'', awaitingSel:'No', selected:'',
            raw:'', normalized:'', currentQuestion:'', context:'', aiRequest:'', aiResponse:'',
            confSaid:'', confDetected:'', confConfidence:'', fieldUpdated:'', fieldValue:'', fieldStatus:'',
            recognitionLang:'English (US)', detectedLang:'', matchingMethod:'', selectedMatch:'',
            micStatus:'Idle', ttsStatus:'Idle', echoSim:'', convState:'', awaitingOptions:'', validation:'', validationReason:'',
            sessionTime:'00:00', remaining:'05:00', reason:'', duration:'',
            category:'', location:'', date:'', time:'', occurrence:'',
            monthlyType:'', days:'', months:'', monthDay:'', notes:'', missing:'', confidence:'', save:'Not started',
            stage:'', transcript:'', result:'' };
  function renderDebug(){
    var e=el('mvDebug'); if(!e) return;
    var rows=[['Mode',dbg.mode],['AI Speaking',dbg.aiSpeaking||'No'],['Listening',dbg.listening||'No'],['Status',dbg.status||'—'],
      ['Session Time',dbg.sessionTime||'—'],['Remaining Time',dbg.remaining||'—'],['Reason',dbg.reason||'—'],['Duration',dbg.duration||'—'],
      ['Ignored Transcript',dbg.ignored||'—'],['Ignore Reason',dbg.ignoreReason||'—'],
      ['Current Stage',dbg.stage||'—'],
      ['TTS Status',dbg.ttsStatus||'—'],['Microphone Status',dbg.micStatus||'—'],['Echo Similarity',dbg.echoSim||'—'],
      ['Conversation State',dbg.convState||'—'],['Awaiting',dbg.awaitingOptions||'—'],
      ['Recognition Language',dbg.recognitionLang||'English (US)'],['Detected Language',dbg.detectedLang||'—'],
      ['Matching Method',dbg.matchingMethod||'—'],['Selected Match',dbg.selectedMatch||'—'],
      ['Validation',dbg.validation||'—'],['Validation Reason',dbg.validationReason||'—'],
      ['Raw Transcript',dbg.raw||'—'],['Normalized Transcript',dbg.normalized||'—'],['Intent',dbg.intent||'—'],
      ['Current Question',dbg.currentQuestion||'—'],['Current Context',dbg.context||'—'],
      ['AI Request',dbg.aiRequest||'—'],['AI Response',dbg.aiResponse||'—'],
      ['Confirmation Said',dbg.confSaid||'—'],['Confirmation Detected',dbg.confDetected||'—'],['Confirmation Confidence',dbg.confConfidence||'—'],
      ['Field Updated',dbg.fieldUpdated||'—'],['Field Status',dbg.fieldStatus||'—'],
      ['Recognized Value',dbg.recognized||'—'],
      ['Field',dbg.field||'—'],['Match Type',dbg.matchType||'—'],['Matches Found',dbg.matches||'—'],
      ['Awaiting Selection',dbg.awaitingSel||'No'],['Selected',dbg.selected||'—'],
      ['Awaiting Confirmation',dbg.awaiting||'No'],['Retry Count',String(dbg.retry||0)],
      ['Next Stage',dbg.nextStage||'—'],['User Input',dbg.input||'—'],['Category',dbg.category||'—'],
      ['Location',dbg.location||'—'],['Date',dbg.date||'—'],['Time',dbg.time||'—'],['Occurrence',dbg.occurrence||'—'],
      ['Monthly Type',dbg.monthlyType||'—'],['Selected Days',dbg.days||'—'],['Months',dbg.months||'—'],
      ['Day of Month',dbg.monthDay||'—'],['Notes',dbg.notes||'—'],['Missing Fields',dbg.missing||'—'],
      ['Confidence',dbg.confidence||'—'],['Save Status',dbg.save||'—']];
    e.innerHTML=rows.map(function(r){ return '<div class="mv-drow"><span class="mv-dk">'+r[0]+'</span><span class="mv-dv">'+esc(r[1])+'</span></div>'; }).join('');
  }

  function injectStyles(){
    if(el('mv-style')) return; var s=document.createElement('style'); s.id='mv-style';
    s.textContent=[
      '#mvHost{margin:0 0 14px;border:1px solid #e6dde2;border-radius:12px;background:#fbf7f9;padding:12px 14px;}',
      '.mv-header-tools{display:flex;align-items:center;gap:8px;flex-wrap:nowrap;justify-content:flex-end;white-space:nowrap;}',
      '#mvControls{display:flex;align-items:center;gap:8px;flex-wrap:nowrap;}',
      '#mvControls .mv-seg button,#mvControls .mv-ghost{font-size:15px;line-height:1;padding:7px 10px;}',   // icon-only buttons
      '@media(max-width:760px){ .mv-header-tools{width:100%;flex-wrap:wrap;justify-content:flex-start;margin-top:8px;white-space:normal;} }',
      '#mvHost .mv-title{font-weight:700;color:#673147;font-size:13.5px;margin-bottom:8px;}',
      '#mvHost .mv-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;}',
      '#mvInput{flex:1;min-width:220px;border:1.5px solid #d7c9d0;border-radius:8px;padding:8px 11px;font-size:13.5px;}',
      '#mvSend{border:none;background:#673147;color:#fff;font-weight:700;font-size:13px;padding:8px 16px;border-radius:8px;cursor:pointer;}',
      '.mv-seg{display:inline-flex;border:1.5px solid #d7c9d0;border-radius:8px;overflow:hidden;}',
      '.mv-seg button{border:none;background:#fff;color:#673147;font-size:12.5px;font-weight:600;padding:7px 11px;cursor:pointer;}',
      '.mv-seg button+button{border-left:1px solid #e6dde2;}',
      '.mv-seg button.on{background:#673147;color:#fff;}',
      '.mv-seg button.rec{background:#e53935;color:#fff;animation:mvPulse 1.1s infinite;}',
      '@keyframes mvPulse{50%{opacity:.6;}}',
      '.mv-ghost{border:1.5px solid #c7c0d7;background:#fff;color:#41506b;font-size:12.5px;font-weight:600;padding:7px 11px;border-radius:8px;cursor:pointer;}',
      '#mvReply{margin-top:9px;font-size:13.5px;color:#41506b;background:#fff;border:1px solid #eadfe6;border-radius:8px;padding:9px 11px;display:none;white-space:pre-line;}',
      '#mvDebug{margin-top:9px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;background:#11151c;color:#cfe3ff;border-radius:8px;padding:10px 12px;display:none;}',
      '#mvDebug .mv-drow{display:flex;gap:8px;padding:1px 0;}',
      '#mvDebug .mv-dk{color:#7fd1ff;min-width:150px;}',
      '#mvDebug .mv-dv{color:#cfe3ff;word-break:break-word;}',
      '.mv-stop{display:inline-flex;align-items:center;gap:6px;border:1.5px solid #e53935;background:#e53935;color:#fff;font-size:12.5px;font-weight:700;padding:7px 12px;border-radius:8px;cursor:pointer;}',
      '.mv-stop:hover{background:#c62828;border-color:#c62828;}',
      '.mv-listening{display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:600;color:#673147;}',
      '.mv-listening .mv-dot{width:9px;height:9px;border-radius:50%;background:#bbb;}',
      '.mv-listening.live .mv-dot{background:#e53935;animation:mvPulse 1.1s infinite;}',
      '.mv-options{display:flex;flex-wrap:wrap;gap:8px;margin-top:9px;}',
      '.mv-opt{border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-size:13px;font-weight:600;padding:8px 13px;border-radius:9px;cursor:pointer;}',
      '.mv-opt:hover{background:#673147;color:#fff;border-color:#673147;}',
      '.mv-opt-none{border-color:#c7c0d7;color:#41506b;}',
      '.mv-opt .mv-opt-pct{font-size:11px;opacity:.7;}',
      '#mvDebugExtra .mv-h-title{margin-top:10px;font-weight:700;color:#673147;font-size:12.5px;}',
      '.mv-history{margin-top:6px;max-height:220px;overflow:auto;background:#fff;border:1px solid #eadfe6;border-radius:8px;padding:8px 10px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11.5px;line-height:1.5;color:#333;}',
      '.mv-history .mv-h-row{padding:1px 0;word-break:break-word;}',
      '.mv-history .mv-h-t{color:#9a7d8b;}',
      '.mv-history .mv-h-user{color:#1565c0;font-weight:700;}',
      '.mv-history .mv-h-ai{color:#673147;font-weight:700;}',
      '.mv-history .mv-h-sys{color:#2e7d32;font-weight:700;}',
      // ── Help (?) icon + help modal ──
      '.mv-help{width:26px;height:26px;flex:0 0 auto;border-radius:50%;border:1.5px solid #c5a0b5;background:#fff;color:#673147;font-weight:700;font-size:13px;cursor:pointer;line-height:1;padding:0;}',
      '.mv-help:hover{background:#673147;color:#fff;}',
      '.mv-modal-ov{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;display:flex;align-items:center;justify-content:center;padding:20px;}',
      '.mv-modal{background:#fff;border-radius:14px;max-width:660px;width:100%;max-height:85vh;overflow:auto;padding:20px 22px;box-shadow:0 10px 40px rgba(0,0,0,.25);}',
      '.mv-modal h3{margin:0 0 6px;color:#673147;font-size:16.5px;}',
      '.mv-modal h4{margin:16px 0 4px;color:#673147;font-size:13.5px;}',
      '.mv-modal p,.mv-modal li{font-size:13px;color:#444;line-height:1.55;margin:4px 0;}',
      '.mv-modal ul{margin:4px 0 8px;padding-left:20px;}',
      '.mv-modal .mv-ex{background:#fbf7f9;border:1px solid #eadfe6;border-radius:8px;padding:8px 10px;margin:6px 0;font-size:12.5px;color:#555;font-style:italic;}',
      '.mv-modal-x{float:right;border:none;background:none;font-size:16px;cursor:pointer;color:#999;padding:2px 6px;}',
      '.mv-modal-x:hover{color:#673147;}'
    ].join(''); document.head.appendChild(s);
  }

  // Keep the Converse / Stop buttons and the listening indicator in sync with
  // whether a conversation session is actually active.
  function setConverseUI(active){
    var cv=el('mvMode_converse'), st=el('mvStopBtn'), li=el('mvListening');
    if(cv){ cv.style.display = active?'none':''; cv.disabled=!!active; }
    if(st){ st.style.display = active?'inline-flex':'none'; }
    if(li){ li.style.display = active?'inline-flex':'none'; }
  }
  function reasonLabel(r){ return ({timeout:'Session Timeout',user:'User Stopped Conversation',
    'said-stop':'User Stopped Conversation',saved:'Meeting Saved',navigation:'Page Navigation',
    error:'Speech Recognition Failed'})[r]||r; }

  function startSession(){
    ending=false; conversationActive=true; sessionStart=Date.now();
    setConverseUI(true);
    startVoice();                                          // begin listening
    clearTimeout(sessionTimeoutId); sessionTimeoutId=setTimeout(function(){ endSession('timeout'); }, SESSION_MS);
    clearInterval(sessionTick);    sessionTick=setInterval(updateSessionDebug, 1000);
    updateSessionDebug();
  }
  function updateSessionDebug(){
    if(!conversationActive) return;
    var elapsed=Date.now()-sessionStart, rem=Math.max(0,SESSION_MS-elapsed);
    dbg.status='Listening'; dbg.sessionTime=fmtMMSS(elapsed); dbg.remaining=fmtMMSS(rem); renderDebug();
  }
  // Single cleanup path for every way a session can end.
  function endSession(reason){
    if(ending) return;
    ending=true; conversationActive=false;
    clearTimeout(sessionTimeoutId); clearInterval(sessionTick);
    try{ if(recorder && recorder.isRecording && recorder.isRecording()) recorder.stop(); }catch(e){}
    if(reason!=='timeout'){ try{ if('speechSynthesis' in window) window.speechSynthesis.cancel(); }catch(e){} }
    var durMs = sessionStart ? (Date.now()-sessionStart) : 0;
    resetFlow();
    if(reason==='saved'){ progress.done={}; persistProgress(); }   // start fresh after a save
    setConverseUI(false);                                  // hide Stop, restore Converse + indicator
    dbg.status='Ended'; dbg.reason=reasonLabel(reason); dbg.duration=fmtMMSS(durMs);
    dbg.sessionTime=fmtMMSS(durMs); dbg.remaining='00:00'; renderDebug();
    if(reason==='timeout'){
      var msg="Our conversation session has ended. If you'd like to continue, please press Converse again.";
      respond(msg);
      if('speechSynthesis' in window){ try{ var u=new SpeechSynthesisUtterance(msg); var v=ensureVoice(); if(v) u.voice=v; u.rate=0.98; u.pitch=1.12; window.speechSynthesis.speak(u); }catch(e){} }
    }
  }
  // Stop button handler.
  function stopConversation(){
    var msg="Okay. Conversation stopped. You can start again whenever you're ready.";
    endSession('user');
    respond(msg);
    if('speechSynthesis' in window){ try{ var u=new SpeechSynthesisUtterance(msg); var v=ensureVoice(); if(v) u.voice=v; u.rate=0.98; u.pitch=1.12; window.speechSynthesis.speak(u); }catch(e){} }
  }
  // Issue 3: a clicked/tabbed/focused meeting field takes over the conversation.
  function onFieldFocus(e){
    if(!conversationActive) return;
    var id = e.target && e.target.id; if(!id) return;
    var stage=null;
    if(id==='meetingType') stage='category';
    else if(id==='locationSearch') stage='location';
    else if(id==='meetingDate'||id==='seriesStartDate') stage='date';
    else if(id==='startTime') stage='time';
    else if(id==='occurrence') stage='occurrence';
    else if(id==='note') stage='notes';
    else if(id==='endDate'){ var occ=el('occurrence')?el('occurrence').value:''; stage = occ==='Daily'?'daily-end':(occ==='Weekly'?'weekly-end':'date'); }
    if(!stage) return;
    if(mstate.stage===stage && !mstate.confirm) return;      // already focused on this field
    mstate.confirm=null; mstate.retry=0; mstate.spelling=false; mstate.stage=stage; updFlowDbg();
    logConvo('System','Field Focus Changed'); logConvo('System','Focused Field: '+(FIELD_NAME[stage]||stage)); logConvo('System','Conversation Context Updated');
    var label=(FIELD_NAME[stage]||stage).toLowerCase();
    var msg = "Let's update the "+label+" field. "+stagePrompt(stage);
    respond(msg); speakIf(msg);
  }

  function setMode(m){
    // Leaving Converse for any reason ends the session cleanly (UI + mic + timers).
    if(mode==='converse' && m!=='converse' && conversationActive){ endSession('user'); }
    if(mode==='voice' && m!=='voice' && recorder && recorder.isRecording && recorder.isRecording()){ try{recorder.stop();}catch(e){} }
    mode=m; speaking=(m==='voice'||m==='converse');
    ['type','voice','converse'].forEach(function(k){ var b=el('mvMode_'+k); if(b){ b.classList.toggle('on',k===m); if(k!=='voice') b.classList.remove('rec'); } });
    dbg.mode=curMode(); renderDebug();
    if(m==='converse'){
      resetFlow();
      // Input focused → Converse acts as dictation: listen continuously and type the
      // speech into #mvInput for review, skipping the question-and-answer flow.
      if(dictateToInput){
        dictationSession=true;
        var inp0=el('mvInput'); if(inp0){ inp0.value=''; }   // clear any leftover buffer first
        recentSpoken=[];                                      // reset echo memory for a clean session
        startSession();                                       // mic + session timer + Stop button
        resetDictTimer();                                     // 1-minute no-"Done" auto-stop
        var dmsg='Started listening. Please say your command. When you are finished, say "Done".';
        dbg.dictation='Listening'; logConvo('AI', dmsg); renderDebug(); respond(dmsg); speakIf(dmsg);
        return;
      }
      startSession();                                // starts mic + 2-min timer + UI sync
      var s=startConverse(); renderDebug(); respond(s); speakIf(s);
    }
    if(m==='voice') startVoice();
  }
  // Start (or reuse) the shared recorder. It listens continuously and is muted
  // only while the assistant is speaking (speakIf), so after each spoken prompt
  // the microphone automatically resumes — in both Voice and Converse modes.
  function startVoice(){
    if(!window.VoiceOpenAI){ respond('Voice needs a recent Chrome, Edge, or Safari, and the AI service configured. You can still type your answers.'); return; }
    if(!recorder){
      recorder=window.VoiceOpenAI.createRecorder({
        context:'meeting',
        onState:function(on){
          // Never show "listening" while the AI is speaking.
          var live = on && !ttsActive;
          ['mvMode_voice','mvMode_converse'].forEach(function(x){ var b=el(x); if(b) b.classList.remove('rec'); });
          if(live){ var b=el(mode==='converse'?'mvMode_converse':'mvMode_voice'); if(b) b.classList.add('rec'); }
          var li=el('mvListening'); if(li) li.classList.toggle('live', !!live);
          if(!ttsActive){ dbg.listening = on?'Yes':'No'; dbg.micStatus = on?'Listening':(conversationActive?'Idle':'Off'); if(conversationActive && on) dbg.status='Waiting for user response'; renderDebug(); }
          // Converse: if the recorder auto-stopped (silence/own timeout) mid-session
          // and we're not speaking, resume listening so the 2-minute session continues.
          if(!on && conversationActive && !ending && !ttsActive){
            setTimeout(function(){ if(conversationActive && !ending && !ttsActive && recorder && recorder.isRecording && !recorder.isRecording()){ try{ recorder.start(); }catch(e){} } }, 250);
          }
        },
        onStatus:function(){},
        onTranscript:function(t){
          // Drop anything captured after a Converse session has ended (no ghost restart).
          if(mode==='converse' && (ending || !conversationActive)){ return; }
          dbg.recognitionLang='English (US)';
          dbg.detectedLang = /[a-z]/i.test(String(t||'')) ? 'English' : 'Non-English (will normalize)';
          // Discard anything captured while the AI is speaking, or that matches what
          // the AI just said — the system must never process its own response.
          if(ttsActive){ dbg.ignored=t; dbg.ignoreReason='AI is speaking'; dbg.ttsStatus='Speaking'; dbg.micStatus='Paused (AI Speaking)';
            logConvo('System','Ignored (AI speaking): "'+t+'"'); renderDebug(); return; }
          if(isSelfTranscript(t)){ dbg.ignored=t; dbg.echoSim=Math.round(_lastEchoSim*100)+'%';
            dbg.ignoreReason='Matched a recent AI prompt ('+dbg.echoSim+')';
            logConvo('System','Transcript ignored — echo of AI prompt ('+dbg.echoSim+'): "'+t+'"'); renderDebug(); return; }
          // Drop transcripts that are nothing but model-hallucinated filler (never spoken).
          if(isStrongHallucination(t)){ dbg.ignored=t; dbg.ignoreReason='Hallucinated filler (not spoken)';
            logConvo('System','Ignored hallucinated filler: "'+t+'"'); renderDebug(); return; }
          // ── Dictation: the input is the target → type ONLY the user's real words into
          //    #mvInput for review/editing; do NOT process. "Done"/"Stop" ends capture. ──
          if(dictateToInput){
            var ended=hasTerminator(t);
            var clean=stripHallucinations(t);          // remove any filler the model mixed in
            if(ended) clean=stripTerminator(clean);    // the termination word is never part of the text
            if(clean){
              var inp=el('mvInput');
              if(inp){ inp.value = inp.value ? (inp.value.replace(/\s+$/,'')+' '+clean) : clean; try{ inp.focus(); }catch(e){} }
              dbg.transcript=clean; logConvo('User','(dictated) '+clean);
            } else {
              logConvo('System','Ignored — no real speech in segment: "'+t+'"');
            }
            if(ended){ logConvo('System','Termination word detected — capture complete.'); finalizeDictation(); return; }
            resetDictTimer();
            if(clean) respond('Listening… say "Done" when you\'re finished.');
            renderDebug(); return;
          }
          var i=el('mvInput'); if(i) i.value=t; handleUtterance(t);
        },
        onDisabled:function(){ if(conversationActive) endSession('error'); respond('Voice is unavailable (disabled or limit reached). You can still type your answers.'); }
      });
    }
    if(recorder && recorder.isRecording && !recorder.isRecording()){
      try{ recorder.start(); }catch(e){ respond('Unable to start the microphone. Please allow mic access, or type your answer.'); }
    }
  }

  /* ── Help (?) dialogs ────────────────────────────────────────────────────── */
  function showHelpModal(title, bodyHtml){
    var old=document.querySelector('.mv-modal-ov'); if(old && old.parentNode) old.parentNode.removeChild(old);
    var ov=document.createElement('div'); ov.className='mv-modal-ov';
    ov.innerHTML='<div class="mv-modal" role="dialog" aria-modal="true" aria-label="'+title+'">'
      +'<button class="mv-modal-x" type="button" aria-label="Close">✕</button>'
      +'<h3>'+title+'</h3>'+bodyHtml+'</div>';
    function close(){ if(ov.parentNode) ov.parentNode.removeChild(ov); document.removeEventListener('keydown',escK); }
    function escK(e){ if(e.key==='Escape') close(); }
    ov.addEventListener('click', function(e){ if(e.target===ov) close(); });
    ov.querySelector('.mv-modal-x').addEventListener('click', close);
    document.addEventListener('keydown', escK);
    document.body.appendChild(ov);
  }
  function inputHelpHtml(){
    return '<p>This AI assistant lets you create a meeting by describing it in plain English. '
      +'It reads your request, detects the details, and fills in the Add Meeting form for you — '
      +'<b>nothing is saved automatically</b>; you always review the form first.</p>'
      +'<h4>What the AI can detect and populate</h4>'
      +'<ul><li><b>Category</b> — matched against your meeting categories (e.g. Bible Study, Prayer Meeting).</li>'
      +'<li><b>Location</b> — a member’s home or a place from your location list. Close spellings are matched automatically, and if several people match you’ll be shown choices to pick from.</li>'
      +'<li><b>Date</b> — exact dates ("August 10") or relative ones ("next Thursday", "tomorrow").</li>'
      +'<li><b>Time</b> — "7 PM", "7:30 in the evening", "seven thirty".</li>'
      +'<li><b>Occurrence</b> — one-time, daily, weekly, or monthly patterns.</li>'
      +'<li><b>Notes</b> — any extra remarks in your request.</li></ul>'
      +'<h4>Example requests</h4>'
      +'<div class="mv-ex">"Create a Bible Study at John’s house next Thursday at 7 PM."</div>'
      +'<div class="mv-ex">"Schedule a Prayer Meeting every Friday at 6:30 PM."</div>'
      +'<div class="mv-ex">"Create a Youth Meeting on August 10 at the church."</div>'
      +'<h4>Tips for best results</h4>'
      +'<ul><li>Use relative dates freely — <b>next Thursday</b>, <b>tomorrow</b>, <b>next week</b>, or <b>this Sunday</b> — and the AI will work out and populate the correct date.</li>'
      +'<li>For recurring meetings, use phrases such as <b>every Monday</b>, <b>every first Saturday</b>, <b>monthly</b>, or <b>weekly</b> — the Occurrence field is filled automatically.</li>'
      +'<li>Include the category, place, date, and time in one sentence for the most complete result.</li>'
      +'<li>If a detail is missed, just type it in the form directly, or say "update date", "update location", etc.</li>'
      +'<li>When you’re happy with the form, click Save — or say "Save Now" in Converse mode.</li></ul>';
  }
  function modesHelpHtml(){
    return '<p>There are three ways to give the AI your meeting details. Pick whichever suits you — they all fill the same form.</p>'
      +'<h4>🔎 Type</h4>'
      +'<p>Type your full request in the text box in natural language, then click <b>Fill Form</b> (or press Enter). '
      +'The AI extracts the details and populates the meeting fields for your review.</p>'
      +'<div class="mv-ex">"Create a Bible Study at John’s house on June 3rd at 4 PM."</div>'
      +'<h4>🎤 Voice</h4>'
      +'<p>Click the microphone and speak your request naturally — your speech is converted to text and processed exactly like a typed request. Click the microphone again to stop.</p>'
      +'<ul><li>Allow microphone access when the browser asks.</li>'
      +'<li>Speak at a normal pace in a quiet environment for the best transcription.</li>'
      +'<li>Tip: if you click into the text box first, your speech is typed into the box so you can review and edit it before sending.</li></ul>'
      +'<h4>🗣 Converse</h4>'
      +'<p>Have a conversation with the AI to build the meeting step by step. It asks for the category, location, date, time, and occurrence one at a time, and may ask follow-up questions when it needs more information.</p>'
      +'<ul><li>Answer confirmation questions with <b>Yes</b> / <b>No</b> (spoken, typed, or by clicking the buttons shown).</li>'
      +'<li>Say <b>"Skip"</b> to leave a field blank, <b>"update time"</b> (or date/location/category/notes) to change a field, and <b>"Save Now"</b> when you’re ready — the AI confirms before saving.</li>'
      +'<li>Say <b>"Stop"</b> to end the conversation at any time; a session also ends automatically after 5 minutes of inactivity.</li></ul>'
      +'<h4>Limitations</h4>'
      +'<ul><li>English is supported; dates, times, and categories are matched to your church’s own lists.</li>'
      +'<li>Voice and Converse need a working microphone and speech service; if unavailable, Type mode always works.</li>'
      +'<li>The AI never saves a meeting without your confirmation.</li></ul>';
  }

  function injectUI(){
    if(el('mvHost')) return true;
    var main=document.querySelector('main.page-content')||document.querySelector('.page-content'); if(!main) return false;
    injectStyles();
    // ── Control buttons — relocated into the Meetings page header, next to Category ──
    var controls=document.createElement('div'); controls.id='mvControls'; controls.className='mv-controls';
    controls.innerHTML=
        '<span class="mv-seg"><button id="mvMode_type" class="on" type="button" title="Type" aria-label="Type">🔎</button>'
      + '<button id="mvMode_voice" type="button" title="Voice" aria-label="Voice">🎤</button>'
      + '<button id="mvMode_converse" type="button" title="Converse" aria-label="Converse">🗣</button></span>'
      + '<button id="mvGuideBtn" class="mv-ghost" type="button" title="Help — Type, Voice &amp; Converse input methods" aria-label="Help — input methods">❔</button>'
      + '<button id="mvDebugBtn" class="mv-ghost" type="button" title="Debug" aria-label="Debug">🐞</button>'
      + '<button id="mvStopBtn" class="mv-stop" type="button" title="Stop Conversation" style="display:none;">⏹ Stop</button>'
      + '<span id="mvListening" class="mv-listening" style="display:none;"><span class="mv-dot"></span> Listening…</span>';

    // ── Body — input + responses + debug panels (sits just below the header) ──
    var host=document.createElement('div'); host.id='mvHost'; host.className='mv-host';
    host.innerHTML=
        '<div class="mv-row">'
      + '<input id="mvInput" type="text" placeholder="e.g. Create a Bible Study at John\'s house on June 3rd at 4 pm">'
      + '<button id="mvSend" type="button" title="Extracts the details from your text and fills in the Add Meeting form">Fill Form</button>'
      + '<button id="mvHelpBtn" class="mv-help" type="button" title="Help — how to use the AI meeting input" aria-label="Help">?</button></div>'
      + '<div id="mvReply"></div><div id="mvOptions" class="mv-options" style="display:none;"></div>'
      + '<div id="mvDebug"></div>'
      + '<div id="mvDebugExtra" style="display:none;">'
      + '<div class="mv-h-title">Conversation History</div><div id="mvHistory" class="mv-history"></div>'
      + '<button id="mvExportBtn" class="mv-ghost" type="button" style="margin-top:8px;">⬇ Export Conversation Debug</button></div>';

    var header=document.querySelector('.page-header');
    var cat=document.getElementById('btnCategory');
    if(header && cat){
      // Group Category + the assistant buttons on the right side of the header.
      var tools=document.createElement('div'); tools.className='mv-header-tools';
      header.insertBefore(tools, cat); tools.appendChild(cat); tools.appendChild(controls);
      main.insertBefore(host, header.nextSibling);     // input/panels right under the header
    } else {
      // Fallback (header not found): keep everything together at the top of the content.
      host.insertBefore(controls, host.firstChild);
      main.insertBefore(host, main.firstChild);
    }

    el('mvSend').addEventListener('click', function(){ finishDictation(); var i=el('mvInput'); var v=i?i.value:''; if(i) i.value=''; handleUtterance(v); });
    el('mvInput').addEventListener('keydown', function(e){ if(e.key==='Enter'){ e.preventDefault(); finishDictation(); var v=this.value; this.value=''; handleUtterance(v); } });
    // ── Dictation arming: focusing the input targets Voice/Converse speech at it.
    //    Moving focus to our own control buttons keeps it armed (so focus → click 🎤
    //    works); focusing anything else disarms it. ──
    el('mvInput').addEventListener('focus', function(){ dictateToInput=true; dbg.dictation='Armed (mvInput)'; renderDebug(); });
    el('mvInput').addEventListener('blur', function(e){
      var id=(e.relatedTarget && e.relatedTarget.id)||'';
      if(/^mv(Mode_|GuideBtn|DebugBtn|StopBtn|Send)/.test(id)) return;          // → our controls: stay armed
      if(!e.relatedTarget && (Date.now()-_armHold)<600) return;                 // Safari: buttons may not take focus
      if(dictationSession) return;                                              // active dictation session keeps the target
      dictateToInput=false; dbg.dictation='Off'; renderDebug();
    });
    controls.addEventListener('mousedown', function(){ _armHold=Date.now(); });
    el('mvMode_type').addEventListener('click', function(){ setMode('type'); });
    el('mvMode_voice').addEventListener('click', function(){ if(mode==='voice'&&recorder&&recorder.isRecording&&recorder.isRecording()){ try{recorder.stop();}catch(e){} return; } setMode('voice'); });
    el('mvMode_converse').addEventListener('click', function(){ setMode('converse'); });
    el('mvGuideBtn').addEventListener('click', function(){
      showHelpModal('AI Input Methods — Type, Voice & Converse', modesHelpHtml());
    });
    el('mvHelpBtn').addEventListener('click', function(){
      showHelpModal('AI Meeting Assistant — Help', inputHelpHtml());
    });
    el('mvDebugBtn').addEventListener('click', function(){
      var d=el('mvDebug'), x=el('mvDebugExtra'); var show = d && (d.style.display==='none'||!d.style.display);
      if(d) d.style.display = show?'block':'none';
      if(x) x.style.display = show?'block':'none';
      if(show){ renderDebug(); renderHistory(); }
    });
    var ex=el('mvExportBtn'); if(ex) ex.addEventListener('click', exportDebug);
    el('mvStopBtn').addEventListener('click', stopConversation);
    renderDebug();
    return true;
  }

  function init(){
    if(!injectUI()) return;
    loadLocations();   // fetch our own copy of members/locations for matching
    // Restore progress for a same-tab resume, but drop stale progress if the form is empty
    // (e.g. a fresh page load) so we don't skip fields that aren't actually filled.
    loadProgress(); if(!FIELD_ORDER.some(formHasValue)){ progress.done={}; persistProgress(); }
    document.addEventListener('focusin', onFieldFocus);   // focused field takes priority
    // End any active session if the user navigates away (stops the mic cleanly).
    window.addEventListener('beforeunload', function(){ try{ if(conversationActive) endSession('navigation'); }catch(e){} });
    window.addEventListener('pagehide',     function(){ try{ if(conversationActive) endSession('navigation'); }catch(e){} });
  }
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(init,400); });
  else setTimeout(init,400);

  // Exposed for testing.
  window.CGP_MEETING = {
    parseDate:parseDate, parseTime:parseTime, parseDays:parseDays, parseMonths:parseMonths,
    parseOrdinalWeek:parseOrdinalWeek, parseSingleDOW:parseSingleDOW, parseDayOfMonth:parseDayOfMonth,
    parseOccurrence:parseOccurrence, stripHouse:stripHouse, converseStep:converseStep, startConverse:startConverse,
    applyFields:applyFields, isSelfTranscript:isSelfTranscript, rememberSpoken:rememberSpoken,
    fuzzyMatches:fuzzyMatches, parseOption:parseOption, simRatio:simRatio,
    _state:function(){ return mstate; }, _reset:resetFlow, setToday:function(d){ _today=d; } };
})();
