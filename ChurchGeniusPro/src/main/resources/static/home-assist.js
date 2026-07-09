/* ============================================================================
 * home-assist.js — AI Search Assistant for the Home dashboard.
 *
 * Enhances the existing "Ask anything…" search bar (it does NOT rebuild it) with
 * a unified, role-aware help/navigation/report assistant in three modes:
 *
 *   • Type      — type a question; instant answer + "Open that page?" suggestion.
 *   • Voice     — speak a question (OpenAI STT) → same answer engine, spoken back.
 *   • Converse  — interactive: the assistant asks follow-ups, then offers to open.
 *
 * Knowledge layers (role-aware — never recommends a page the user can't open):
 *   1. Navigation Guide — reuses window.CGP_HOME_VOICE (DESTS + isAvailable +
 *      matchDest) so "Where is the Tax Report?" → path + URL + voice command.
 *   2. Reports Guide    — what each report contains / is used for + where it lives.
 *   3. Help Center / LLM — free-text & how-to questions route to /api/ai-assist
 *      (OpenAI, grounded in the role-filtered context). Falls back to the static
 *      /api/help/search when the LLM endpoint is unconfigured, and to the original
 *      data search (/api/ai-search) for data lookups (income, birthdays, …).
 *
 * Loads only on /home, AFTER home-voice.js (which provides CGP_HOME_VOICE).
 * ========================================================================== */
(function () {
  'use strict';
  if (!/^\/home(\b|\/|$)/.test(window.location.pathname)) return;

  /* ── Reports knowledge base (what each report contains / is used for) ──────── */
  var REPORTS = [
    { label:'Income Report',      url:'/income-report',       cat:'Reports',
      keys:['income report','giving report','giving history','contributions report','revenue report'],
      contains:'All recorded contributions/income by date, contributor, fund and method.',
      usedFor:'Reviewing giving and income totals over a period; giving history.' },
    { label:'Expense Report',     url:'/expense-report',      cat:'Reports',
      keys:['expense report','spending report','outgoing report'],
      contains:'All recorded expenses by date, fund, purpose, method and amount.',
      usedFor:'Reviewing spending and outgoing totals over a period.' },
    { label:'Transaction Report', url:'/transactions-report', cat:'Reports',
      keys:['transaction report','transactions report','ledger report','all transactions'],
      contains:'A combined ledger of income and expense transactions.',
      usedFor:'A full money-in/money-out view across the church.' },
    { label:'Tax Report',         url:'/tax-report',          cat:'Reports',
      keys:['tax report','taxes','for tax','tax purposes','giving statement','contribution statement','year end statement','annual statement'],
      contains:'Tax-deductible giving totals per contributor for the period.',
      usedFor:'Year-end giving statements and tax / charitable-deduction purposes.' },
    { label:'Financial Report',   url:'/tax-report',          cat:'Reports',
      keys:['financial report','finance report','financial summary'],
      contains:'A financial summary of giving and funds.',
      usedFor:'A high-level financial overview.' },
    { label:'Payroll Reports',    url:'/payroll/reports',     cat:'Payroll', payroll:true,
      keys:['payroll report','payroll reports','payroll summary','w-2','w2','tax forms'],
      contains:'Payroll run summaries, employee pay totals and W-2 / year-end exports.',
      usedFor:'Reviewing payroll history and producing payroll tax documents.' },
    { label:'Payroll Activity',   url:'/payroll/activity',    cat:'Payroll', payroll:true,
      keys:['payroll activity','payroll log','payroll audit'],
      contains:'A chronological audit log of payroll actions and runs.',
      usedFor:'Tracking what changed in payroll and when.' },
    { label:'Donation Review',    url:'/donation-review',     cat:'Finance',
      keys:['donation report','donations report','online donations','review donations','donation review'],
      contains:'Incoming online donations awaiting review/posting.',
      usedFor:'Reviewing and reconciling online donations.' },
    // Informational only — no single confirmed page yet (tracked via Events / Kids Check-In).
    { label:'Attendance',         url:null,                   cat:'Ministry',
      keys:['attendance report','attendance','headcount','check in report','checkin report'],
      contains:'Attendance / check-in counts.',
      usedFor:'Tracking attendance. Recorded via Events and Kids Check-In; a dedicated report is coming.' }
  ];

  /* ── Access to the navigation knowledge layer (home-voice.js) ─────────────── */
  function HV(){ return window.CGP_HOME_VOICE || null; }
  function dests(){ var h=HV(); return (h && h._dests) ? h._dests : []; }
  function navMatch(text){ var h=HV(); return (h && h.matchDest) ? h.matchDest(text) : null; }
  function navAvailable(d){ var h=HV(); return (h && h.isAvailable) ? h.isAvailable(d) : true; }
  function destByUrl(url){
    var list = dests();
    for (var i=0;i<list.length;i++){ if (list[i].target === url) return list[i]; }
    return null;
  }
  function voiceCmd(label){ return 'Go to ' + label; }
  // A report is available if its backing nav destination is (Payroll items gate on payroll access).
  function reportAvailable(r){
    if (!r.url) return true;                       // informational entry
    var d = destByUrl(r.url);
    return d ? navAvailable(d) : true;
  }
  function matchReport(text){
    var t = ' ' + String(text||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').replace(/\s+/g,' ').trim() + ' ';
    var best=null, bestLen=0;
    REPORTS.forEach(function(r){
      r.keys.forEach(function(k){
        if (k.length<=bestLen) return;
        if (t.indexOf(' '+k+' ')!==-1) { best=r; bestLen=k.length; }
      });
    });
    return best;
  }

  /* ── Role-filtered context for the LLM (only pages the user can open) ─────── */
  function buildContext(){
    var pages = [];
    dests().forEach(function(d){
      if (!navAvailable(d)) return;
      pages.push({
        label: d.label,
        url: d.dynamic ? (d.base + ' (public link — generated on open)') : (d.target||''),
        voiceCommand: voiceCmd(d.label),
        category: d.cat || '',
        description: d.dynamic ? 'Public-facing page' : ''
      });
    });
    var reports = REPORTS.filter(reportAvailable).map(function(r){
      return { label:r.label, url:r.url||'', voiceCommand:voiceCmd(r.label),
               contains:r.contains, usedFor:r.usedFor };
    });
    var role = 'User';
    try { role = (localStorage.getItem('role')||'User'); } catch(e){}
    return { role: role, currentPage:'/home', availablePages: pages, reports: reports };
  }

  /* ── New-tab phrasing (mirrors home-voice.js) ─────────────────────────────── */
  var NEWTAB_RE = /\b(?:in|as|on|into)?\s*(?:a|an|another|the|one)?\s*(?:new|another|separate|different)\s+(?:tab|window)\b/i;
  function wantsNewTab(text){ return NEWTAB_RE.test(' ' + String(text||'') + ' '); }

  /* ── Intent classification ────────────────────────────────────────────────── */
  function classify(qRaw){
    var q = String(qRaw||'').toLowerCase().trim();
    var isHowTo = /\b(how (do|can|to|would)|steps?|tutorial|walk me|guide me|set up|configure|create|add|record|enter|send|register|check ?in)\b/.test(q);
    var isWhere = /\b(where|which|find|locate|show me|go to|open|navigate|take me|what report|what page)\b/.test(q) || /^(go to|open|show)\b/.test(q);
    return { howTo:isHowTo, where:isWhere };
  }

  /* ── Local resolver — returns a structured answer or null ──────────────────── */
  function resolveLocal(qRaw){
    var q = String(qRaw||'').trim();
    if (!q) return null;
    var c = classify(q);

    // Report questions ("which report is used for taxes?", "where is the tax report")
    var rep = matchReport(q);
    if (rep && (!c.howTo || c.where)) {
      var avail = reportAvailable(rep);
      if (!avail) {
        return { intent:'report', category:'Reports', source:'Reports Guide', confidence:0.9,
                 answer:'The ' + rep.label + ' is not available for your current account.',
                 pages:[], suggestedAction:'none' };
      }
      var ans = rep.label + ' — ' + rep.contains + ' ' + rep.usedFor;
      if (rep.url) ans += ' Location: Reports → ' + rep.label + '. URL: ' + rep.url + '. Voice command: "' + voiceCmd(rep.label) + '".';
      return { intent:'report', category: rep.cat, source:'Reports Guide', confidence: rep.url?0.95:0.6,
               answer: ans,
               pages: rep.url ? [{label:rep.label, url:rep.url, voiceCommand:voiceCmd(rep.label),
                                  description: rep.usedFor}] : [],
               suggestedAction: rep.url ? 'open_page' : 'none' };
    }

    // Navigation questions ("where is X", "open X", a bare page name) — not pure how-to
    if (!c.howTo || c.where) {
      var d = navMatch(q);
      if (d) {
        if (!navAvailable(d)) {
          return { intent:'navigation', category:d.cat||'', source:'Navigation Guide', confidence:0.9,
                   answer:'That page (' + d.label + ') is not available for your current account.',
                   pages:[], suggestedAction:'none' };
        }
        if (d.dynamic) {
          return { intent:'navigation', category:d.cat||'Public', source:'Navigation Guide', confidence:0.85,
                   answer: d.label + ' is a public page. I can open its active public link for you. Voice command: "' + voiceCmd(d.label) + '".',
                   pages:[{label:d.label, url:'__dynamic__:'+d.base, voiceCommand:voiceCmd(d.label),
                           description:'Public-facing page'}],
                   suggestedAction:'open_page' };
        }
        return { intent:'navigation', category:d.cat||'', source:'Navigation Guide', confidence:0.95,
                 answer: d.label + ' is under ' + (d.cat||'the menu') + '. URL: ' + d.target + '. Voice command: "' + voiceCmd(d.label) + '".',
                 pages:[{label:d.label, url:d.target, voiceCommand:voiceCmd(d.label)}],
                 suggestedAction:'open_page' };
      }
    }
    return null;   // → route to LLM / help search
  }

  /* ── Public-link resolution for dynamic pages (mirrors home-voice.js) ─────── */
  function relUrl(u){ try{ var a=document.createElement('a'); a.href=u; return a.pathname+a.search; }catch(e){ return String(u||''); } }
  function expirationValid(l){
    if(!l.expirationDate) return true;
    var ed=new Date(String(l.expirationDate).slice(0,10)+'T00:00:00'); var t=new Date(); t.setHours(0,0,0,0); return ed>=t;
  }
  function resolvePublic(base){
    return fetch('/api/public-screens').then(function(r){ return r.ok?r.json():[]; }).then(function(j){
      var list=Array.isArray(j)?j:((j&&j.data)||[]);
      for(var i=0;i<list.length;i++){
        var l=list[i];
        if(String(l.pageUrl)!==base) continue;
        if(l.revoked===true) continue;
        if(!expirationValid(l)) continue;
        return l.publicUrl ? relUrl(l.publicUrl) : (base + '?token=' + encodeURIComponent(l.token||''));
      }
      return null;
    }).catch(function(){ return null; });
  }

  /* ── TTS (only when answering by voice / converse) ────────────────────────── */
  function speak(text){
    if (!('speechSynthesis' in window)) return;
    try { if(recorder&&recorder.setMuted) recorder.setMuted(true);
      window.speechSynthesis.cancel();
      var u=new SpeechSynthesisUtterance(String(text||'').slice(0,400)); u.rate=1.03;
      u.onend=u.onerror=function(){ if(recorder&&recorder.setMuted) recorder.setMuted(false); };
      window.speechSynthesis.speak(u);
      setTimeout(function(){ if(recorder&&recorder.setMuted) recorder.setMuted(false); }, 8000);
    } catch(e){ if(recorder&&recorder.setMuted) recorder.setMuted(false); }
  }

  /* ── Open a page (current tab / new tab), resolving dynamic links first ────── */
  function open(url, label, newTab){
    if (url && url.indexOf('__dynamic__:')===0){
      var base=url.slice('__dynamic__:'.length);
      resolvePublic(base).then(function(rel){
        if(!rel){ setDebug({result:'No active public link'}); toast('No active public link is available for ' + label + '.'); if(speaking) speak('No active public link is available for ' + label + '.'); return; }
        doOpen(rel, label, newTab);
      });
      return;
    }
    doOpen(url, label, newTab);
  }
  function doOpen(url, label, newTab){
    setDebug({ result:'Opening ' + label + (newTab?' (new tab)':'') , url:url });
    if (newTab){ var w=null; try{ w=window.open(url,'_blank'); }catch(e){} if(!w) toast('Pop-up blocked — allow pop-ups to open new tabs.'); }
    else { if(speaking) speak('Opening ' + label + '.'); setTimeout(function(){ window.location.href=url; }, 150); }
  }

  /* ════════════════════════ Converse dialogue ════════════════════════ */
  var conv = { active:false, topic:null, step:0, data:{} };
  function resetConv(){ conv={active:false, topic:null, step:0, data:{}}; }

  // Built-in interactive trees (the examples from the spec) + LLM fallback.
  function converseStart(qRaw){
    var q=String(qRaw||'').toLowerCase();
    if (/\bremind(er|ers|)\b|reminder/.test(q)){
      conv={active:true, topic:'reminders', step:1, data:{}};
      return ask('Sure — what kind of reminder? You can say: Event, Periodic (recurring), or One-Time.');
    }
    if (/\b(donation|giving|income|report)\b/.test(q) && /report|giving|donation/.test(q)){
      conv={active:true, topic:'report', step:1, data:{}};
      return ask('I can help with reports. Which one do you need: Income, Transaction, Tax, or Financial?');
    }
    if (/\b(join|new member|membership|sign up|signup|add (a )?family|wants to join)\b/.test(q)){
      conv={active:true, topic:'join', step:1, data:{}};
      return ask('Great — how would you like to proceed? You can: Send a Membership Form, open Member Signup, or Create a Family record.');
    }
    // No built-in tree → LLM converse with history.
    return null;
  }

  function converseStep(qRaw){
    var q=String(qRaw||'').toLowerCase().trim();
    if (conv.topic==='reminders'){
      if (conv.step===1){
        if (/event/.test(q)){ conv.data.type='event'; conv.step=2; return ask('For the event — should it go out Before, On, or After the event day?'); }
        if (/periodic|recurring|auto/.test(q)) return offerOpen('Periodic Reminders','/autoReminders','Periodic (recurring) reminders');
        if (/one|single|once/.test(q)) return offerOpen('One-Time Reminders','/oneReminders','One-time reminders');
        return ask('Please say Event, Periodic, or One-Time.');
      }
      if (conv.step===2){
        var when = /before/.test(q)?'Before':(/after/.test(q)?'After':'On');
        return offerOpen('Event Reminders','/eventReminders','Event reminders — configure the "' + when + ' Days" template');
      }
    }
    if (conv.topic==='report'){
      if (/income/.test(q)) return offerOpen('Income Report','/income-report','the Income Report');
      if (/transaction/.test(q)) return offerOpen('Transaction Report','/transactions-report','the Transaction Report');
      if (/tax/.test(q)) return offerOpen('Tax Report','/tax-report','the Tax Report');
      if (/financial|finance/.test(q)) return offerOpen('Financial Report','/tax-report','the Financial Report');
      return ask('Please choose: Income, Transaction, Tax, or Financial.');
    }
    if (conv.topic==='join'){
      if (/membership form|form/.test(q)) return offerOpenDynamic('Membership Form','/membershipForm','the Membership Form public link');
      if (/signup|sign up|member signup/.test(q)) return offerOpenDynamic('Member Signup','/memberSignup','the Member Signup public link');
      if (/family|create/.test(q)) return offerOpen('Families','/viewfamily','the Families page to create a new family');
      return ask('Please choose: Membership Form, Member Signup, or Create a Family.');
    }
    return null;   // fall through to LLM
  }

  function ask(text){
    renderCard({ intent:'converse', category:'Assistant', source:'Assistant', confidence:1,
                 answer:text, pages:[], suggestedAction:'none', followUp:text });
    setDebug({ mode:curMode(), question:lastQ, intent:'converse (follow-up)', category:'Assistant',
               pages:'—', confidence:'1', action:'ask', source:'Assistant', result:'Awaiting reply' });
    if (speaking) speak(text);
    return true;
  }
  function offerOpen(label, url, human){
    resetConv();
    renderCard({ intent:'navigation', category:'Assistant', source:'Navigation Guide', confidence:0.95,
      answer:'Open ' + human + '. Would you like me to open it?',
      pages:[{label:label, url:url, voiceCommand:voiceCmd(label)}], suggestedAction:'open_page' });
    setDebug({ mode:curMode(), question:lastQ, intent:'navigation', category:'Assistant',
               pages:label, confidence:'0.95', action:'open_page', source:'Navigation Guide', result:'Offered to open' });
    if (speaking) speak('Would you like me to open ' + label + '?');
    return true;
  }
  function offerOpenDynamic(label, base, human){
    resetConv();
    renderCard({ intent:'navigation', category:'Public', source:'Navigation Guide', confidence:0.85,
      answer:'I found ' + human + '. Would you like me to open it?',
      pages:[{label:label, url:'__dynamic__:'+base, voiceCommand:voiceCmd(label)}], suggestedAction:'open_page' });
    setDebug({ mode:curMode(), question:lastQ, intent:'navigation', category:'Public',
               pages:label, confidence:'0.85', action:'open_page', source:'Navigation Guide', result:'Offered to open public link' });
    if (speaking) speak('Would you like me to open ' + label + '?');
    return true;
  }

  /* ════════════════════════ LLM / help routing ════════════════════════ */
  function runLLM(query){
    var body = { query: query, context: buildContext(),
                 history: conv.active ? convHistory() : '' };
    return fetch('/api/ai-assist', {
      method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)
    }).then(function(r){
      if (r.status===503 || r.status===404) return null;   // not configured → caller falls back
      return r.ok ? r.json() : null;
    }).catch(function(){ return null; });
  }
  var _convLog=[];
  function convHistory(){ return _convLog.slice(-6).join('\n'); }

  function fallbackHelp(query){
    return fetch('/api/help/search', {
      method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ query:query })
    }).then(function(r){ return r.ok ? r.json() : null; }).catch(function(){ return null; });
  }

  /* ════════════════════════ Main entry ════════════════════════ */
  var lastQ='';
  function runAssist(query){
    var input=document.getElementById('aiSearchInput');
    query = (query!=null ? query : (input ? input.value : '')) || '';
    query = String(query).trim();
    if (!query){ if(input) input.focus(); return; }
    lastQ=query;
    busy(true);

    // Converse mode: continue or start an interactive dialogue.
    if (mode==='converse'){
      _convLog.push('User: '+query);
      var handled = conv.active ? converseStep(query) : converseStart(query);
      if (handled){ busy(false); if(input) input.value=''; return; }
      // No built-in branch → LLM converse, then show followUp/answer.
      conv.active=true;
      runLLM(query).then(function(d){
        busy(false);
        if (d){ renderFromLLM(d); _convLog.push('Assistant: '+(d.followUpQuestion||d.answer||'')); }
        else { renderCard({intent:'unknown',category:'Assistant',source:'Assistant',confidence:0,
                 answer:'I can help you navigate, find reports, and answer how-to questions. Could you tell me a bit more about what you want to do?',pages:[],suggestedAction:'none'});
               if(speaking) speak('Could you tell me a bit more about what you want to do?'); }
      });
      if(input) input.value='';
      return;
    }

    // Type / Voice: local resolve → LLM → help search → data search.
    var local = resolveLocal(query);
    if (local){
      busy(false); renderFromLocal(local);
      if (speaking) speak(stripUrls(local.answer));
      return;
    }
    var c = classify(query);
    var helpish = c.howTo || c.where || (typeof window._isHelpQuery==='function' && window._isHelpQuery(query));
    if (!helpish && typeof _origRunAiSearch==='function'){
      // Data lookup (income totals, birthdays, …) — defer to the original engine.
      busy(false); if(input) input.value=query; return _origRunAiSearch();
    }
    runLLM(query).then(function(d){
      if (d){ busy(false); renderFromLLM(d); if(speaking) speak(stripUrls(d.answer||'')); return; }
      return fallbackHelp(query).then(function(h){
        busy(false);
        if (h && typeof renderAiResults==='function'){ renderAiResults(h, query);
          setDebug({mode:curMode(),question:query,intent:(h.intent||'help'),category:'Help',pages:'—',
                    confidence:'—',action:'none',source:'Help Center',result:'Help search'}); }
        else renderCard({intent:'unknown',category:'Help',source:'Help Center',confidence:0,
               answer:'I couldn’t find an answer. Try the Help Center for more detail.',
               pages:[{label:'Help Center',url:'/helpCenter',voiceCommand:'Go to Help Center'}],suggestedAction:'open_page'});
      });
    });
  }

  function renderFromLocal(a){
    renderCard(a);
    setDebug({ mode:curMode(), question:lastQ, intent:a.intent, category:a.category||'—',
      pages:(a.pages||[]).map(function(p){return p.label;}).join(', ')||'—',
      confidence:String(a.confidence!=null?a.confidence:'—'),
      action:a.suggestedAction||'none', source:a.source||'—', result:'Answered (local)' });
  }
  function renderFromLLM(d){
    var pages=(d.relevantPages||d.pages||[]).filter(function(p){return p&&p.url;});
    renderCard({ intent:d.intent||'assist', category:d.category||'', source:d.source||'Assistant',
      confidence:(d.confidence!=null?d.confidence:''), answer:d.answer||'',
      pages:pages, suggestedAction:d.suggestedAction||(pages.length?'open_page':'none'),
      followUp:d.followUpQuestion });
    setDebug({ mode:curMode(), question:lastQ, intent:d.intent||'assist', category:d.category||'—',
      pages:pages.map(function(p){return p.label;}).join(', ')||'—',
      confidence:String(d.confidence!=null?d.confidence:'—'),
      action:d.suggestedAction||(pages.length?'open_page':'none'), source:d.source||'Assistant',
      result: d.followUpQuestion?'Follow-up asked':'Answered (LLM)' });
  }
  function stripUrls(s){ return String(s||'').replace(/https?:\/\/\S+/g,'').replace(/URL:\s*\/\S+/g,'').replace(/\s{2,}/g,' ').trim(); }

  /* ════════════════════════ Rendering ════════════════════════ */
  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
  function renderCard(a){
    var wrap=document.getElementById('aiResultsWrap'); if(!wrap) return;
    var pages=a.pages||[];
    var html='<div class="ai-results-panel">'
      + '<div class="ai-results-hdr"><div class="ai-results-title">✨ Assistant <em style="font-size:12px;color:#aaa;font-weight:400;">for: '+esc(lastQ)+'</em></div>'
      + '<button class="ai-results-close" onclick="document.getElementById(\'aiResultsWrap\').style.display=\'none\'" title="Close">✕</button></div>'
      + '<div class="ai-answer">'+esc(a.answer||'')+'</div>';
    if (pages.length){
      var newTab = wantsNewTab(lastQ);
      html += '<div style="margin-top:12px;display:flex;flex-direction:column;gap:8px;">';
      pages.forEach(function(p,i){
        var safeUrl=esc(p.url), safeLabel=esc(p.label);
        html += '<div class="aa-suggest">'
          + '<div><div class="aa-suggest-label">'+safeLabel+'</div>'
          + (p.description?'<div class="aa-suggest-desc">'+esc(p.description)+'</div>':'')
          + (p.voiceCommand?'<div class="aa-suggest-vc">🎤 “'+esc(p.voiceCommand)+'”</div>':'')+'</div>'
          + '<div class="aa-suggest-actions">'
          + '<button class="aa-open" data-url="'+safeUrl+'" data-label="'+safeLabel+'" data-nt="0">Open</button>'
          + '<button class="aa-open alt" data-url="'+safeUrl+'" data-label="'+safeLabel+'" data-nt="1">New tab</button>'
          + '</div></div>';
        if(i===0 && a.suggestedAction==='open_page' && !newTab){ /* primary highlighted via CSS */ }
      });
      html += '</div>';
    }
    if (a.intent!=='converse'){
      html += '<div style="margin-top:14px;padding-top:12px;border-top:1px solid #f0eaf2;font-size:12px;color:#888;">'
        + 'Need more detail? <a href="/helpCenter" style="color:#673147;font-weight:700;">Open the full Help Center →</a></div>';
    }
    html += '</div>';
    wrap.innerHTML=html; wrap.style.display='block';
    wrap.querySelectorAll('.aa-open').forEach(function(b){
      b.addEventListener('click', function(){ open(b.getAttribute('data-url'), b.getAttribute('data-label'), b.getAttribute('data-nt')==='1' || wantsNewTab(lastQ)); });
    });
    try { wrap.scrollIntoView({behavior:'smooth',block:'nearest'}); } catch(e){}
  }
  function toast(msg){
    var wrap=document.getElementById('aiResultsWrap'); if(!wrap) return;
    var d=document.createElement('div'); d.className='aa-toast'; d.textContent=msg;
    wrap.appendChild(d); setTimeout(function(){ try{ wrap.removeChild(d); }catch(e){} }, 4000);
  }

  /* ════════════════════════ Mode + Debug UI ════════════════════════ */
  var mode='type', speaking=false, recorder=null;
  function curMode(){ return mode.charAt(0).toUpperCase()+mode.slice(1); }
  function busy(on){ var b=document.getElementById('aiSearchBtn'); if(b){ b.disabled=!!on; b.textContent=on?'⏳ Searching…':'Search'; } }

  // Debug state
  var dbg={ mode:'Type', question:'', intent:'', category:'', pages:'', confidence:'', action:'', source:'', result:'' };
  function setDebug(p){ Object.keys(p||{}).forEach(function(k){ dbg[k]=p[k]; }); renderDebug(); }
  function renderDebug(){
    var el=document.getElementById('aaDebug'); if(!el) return;
    var rows=[['Search Mode',dbg.mode],['User Question',dbg.question],['Intent',dbg.intent],['Category',dbg.category],
      ['Relevant Pages',dbg.pages],['Confidence',dbg.confidence],['Suggested Action',dbg.action],
      ['Answer Source',dbg.source],['Result',dbg.result]];
    el.innerHTML=rows.map(function(r){ return '<div class="aa-drow"><span class="aa-dk">'+r[0]+'</span><span class="aa-dv">'+esc(r[1]||'—')+'</span></div>'; }).join('');
  }

  function injectStyles(){
    if (document.getElementById('home-assist-style')) return;
    var s=document.createElement('style'); s.id='home-assist-style';
    s.textContent=[
      '.aa-bar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:8px 0 2px;}',
      '.aa-seg{display:inline-flex;border:1.5px solid #d7c9d0;border-radius:8px;overflow:hidden;}',
      '.aa-seg button{border:none;background:#fff;color:#673147;font-size:13px;font-weight:600;padding:7px 13px;cursor:pointer;}',
      '.aa-seg button+button{border-left:1px solid #e6dde2;}',
      '.aa-seg button.on{background:#673147;color:#fff;}',
      '.aa-seg button.rec{background:#e53935;color:#fff;animation:aaPulse 1.1s infinite;}',
      '@keyframes aaPulse{50%{opacity:.6;}}',
      '.aa-ghost{border:1.5px solid #c7c0d7;background:#fff;color:#41506b;font-size:13px;font-weight:600;padding:7px 12px;border-radius:8px;cursor:pointer;}',
      '.aa-ghost:hover{background:#41506b;color:#fff;}',
      '.aa-hint{font-size:12px;color:#999;}',
      '.aa-suggest{display:flex;justify-content:space-between;align-items:center;gap:10px;border:1px solid #eadfe6;background:#fbf7f9;border-radius:9px;padding:9px 12px;}',
      '.aa-suggest-label{font-weight:700;color:#673147;font-size:13.5px;}',
      '.aa-suggest-desc{font-size:12px;color:#777;margin-top:1px;}',
      '.aa-suggest-vc{font-size:11.5px;color:#9a7d8b;margin-top:2px;}',
      '.aa-suggest-actions{display:flex;gap:6px;flex-shrink:0;}',
      '.aa-open{border:none;background:#673147;color:#fff;font-size:12.5px;font-weight:600;padding:6px 13px;border-radius:7px;cursor:pointer;}',
      '.aa-open.alt{background:#fff;color:#673147;border:1.5px solid #c5a0b5;}',
      '.aa-open:hover{opacity:.9;}',
      '.aa-toast{margin-top:8px;font-size:12.5px;color:#a23;background:#fdeef0;border:1px solid #f3cfd5;border-radius:7px;padding:7px 10px;}',
      '#aaDebug{margin-top:10px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;background:#11151c;color:#cfe3ff;border-radius:8px;padding:10px 12px;}',
      '#aaDebug.hidden{display:none;}',
      '#aaDebug .aa-drow{display:flex;gap:8px;padding:1px 0;}',
      '#aaDebug .aa-dk{color:#7fd1ff;min-width:130px;}',
      '#aaDebug .aa-dv{color:#cfe3ff;word-break:break-word;}'
    ].join('');
    document.head.appendChild(s);
  }

  function setMode(m){
    if (m!=='converse'){ resetConv(); _convLog=[]; }
    if (mode==='voice' && m!=='voice' && recorder && recorder.isRecording && recorder.isRecording()){ try{ recorder.stop(); }catch(e){} }
    mode=m; speaking=(m==='voice'||m==='converse');
    ['type','voice','converse'].forEach(function(k){
      var btn=document.getElementById('aaMode_'+k); if(btn){ btn.classList.toggle('on', k===m); if(k!=='voice') btn.classList.remove('rec'); }
    });
    var hint=document.getElementById('aaHint');
    if(hint) hint.textContent = m==='voice' ? 'Click 🎤 Voice again and speak your question.'
                              : m==='converse' ? 'Conversational mode — I’ll ask follow-up questions.'
                              : '';
    dbg.mode=curMode(); renderDebug();
    if (m==='voice') startVoice();
  }

  function startVoice(){
    if (!window.VoiceOpenAI){ toast('Voice needs a recent Chrome, Edge, or Safari.'); return; }
    if (!recorder){
      recorder = window.VoiceOpenAI.createRecorder({
        context:'home',
        onState: function(on){ var b=document.getElementById('aaMode_voice'); if(b) b.classList.toggle('rec', !!on); },
        onStatus: function(){},
        onTranscript: function(t){ var inp=document.getElementById('aiSearchInput'); if(inp) inp.value=t; setDebug({question:t}); runAssist(t); },
        onDisabled: function(){ toast('Voice is unavailable (disabled or limit reached).'); }
      });
    }
    if (recorder && !recorder.isRecording()) { try{ recorder.start(); }catch(e){} }
  }

  function injectUI(){
    var wrap=document.querySelector('.ai-search-wrap'); if(!wrap) return false;
    if (document.getElementById('aaBar')) return true;
    injectStyles();
    var bar=document.createElement('div'); bar.className='aa-bar'; bar.id='aaBar';
    bar.innerHTML=
      '<span class="aa-seg">'
      + '<button id="aaMode_type" class="on" type="button">🔎 Type</button>'
      + '<button id="aaMode_voice" type="button">🎤 Voice</button>'
      + '<button id="aaMode_converse" type="button">🗣 Converse</button>'
      + '</span>'
      + '<button id="aaDebugBtn" class="aa-ghost" type="button">🐞 Debug</button>'
      + '<span id="aaHint" class="aa-hint"></span>';
    // Insert the controls right under the search bar, above the results.
    var results=document.getElementById('aiResultsWrap');
    if (results && results.parentNode===wrap) wrap.insertBefore(bar, results);
    else wrap.appendChild(bar);
    var dbgEl=document.createElement('div'); dbgEl.id='aaDebug'; dbgEl.className='hidden';
    if (results && results.parentNode===wrap) wrap.insertBefore(dbgEl, results.nextSibling);
    else wrap.appendChild(dbgEl);
    renderDebug();

    document.getElementById('aaMode_type').addEventListener('click', function(){ setMode('type'); });
    document.getElementById('aaMode_voice').addEventListener('click', function(){
      if (mode==='voice' && recorder && recorder.isRecording && recorder.isRecording()){ try{ recorder.stop(); }catch(e){} return; }
      setMode('voice');
    });
    document.getElementById('aaMode_converse').addEventListener('click', function(){ setMode('converse'); });
    document.getElementById('aaDebugBtn').addEventListener('click', function(){
      var el=document.getElementById('aaDebug'); if(el) el.classList.toggle('hidden');
    });
    return true;
  }

  /* ── Override the search entry point (keep the original for data lookups) ──── */
  var _origRunAiSearch = (typeof window.runAiSearch==='function') ? window.runAiSearch : null;
  window.runAiSearch = function(){ return runAssist(); };

  /* ── Bootstrap ───────────────────────────────────────────────────────────── */
  function init(){ injectUI(); }
  if (document.readyState==='loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(init, 500); });
  else setTimeout(init, 500);

  // Exposed for testing.
  window.CGP_ASSIST = { resolveLocal:resolveLocal, classify:classify, matchReport:matchReport,
                        buildContext:buildContext, _reports:REPORTS, converseStart:converseStart,
                        converseStep:converseStep, _conv:function(){return conv;}, _setMode:function(m){mode=m;} };
})();
