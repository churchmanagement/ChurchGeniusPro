/* ════════════════════════════════════════════════════════════════════════
   Certificate engine (shared by every certificate type).

   A page supplies window.CERT_CONFIG and includes certificate.css + this file.
   The engine renders the certificate overlays and the edit panel, wires member
   search, live field sync, church/logo auto-load, print, PDF download, and
   record persistence — so all certificate types share one identical
   look, behaviour, print and PDF pipeline.

   CERT_CONFIG schema (see the per-type HTML pages for examples):
   {
     key, title, fileName, pageTitle,
     preline,                              // small caps line above the name
     name:  { label, placeholder, member },        // primary recipient slot
     name2: { label, placeholder, member, join },   // optional 2nd name (marriage)
     fields: [ { id, label, type:'text'|'textarea'|'date', placeholder } ],
     body,                                 // template with {token} placeholders
     signatures: [ { nameField, title } ], // 1 or 2 signature blocks
     primaryDateField,                     // field id used as the record date
     certNumberField,                      // optional field id printed as number
     scripture,                            // optional footer text (or null)
     seal                                  // boolean
   }
════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';
  var CFG = window.CERT_CONFIG || {};
  var CERT_W = 900, CERT_H = 636;

  /* ── state ── */
  var allMembers = [];
  var nameVals = { name: '', name2: '' };
  var fieldVals = {};                 // id -> string value
  var nameStyle = { font: "'Great Vibes', cursive", color: '#1a1035', bold: false, italic: false };
  var church = { name: '', city: '' };
  var slots = {};                     // slot -> { selected, filtered, hi }
  var currentTitle = CFG.title || '';  // live arched title (editable for "other")

  /* ── tiny helpers ── */
  function el(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
  function nl2br(s) { return esc(s).replace(/\n/g, '<br/>'); }
  function todayISO() { var t = new Date(); return t.getFullYear() + '-' + String(t.getMonth()+1).padStart(2,'0') + '-' + String(t.getDate()).padStart(2,'0'); }
  function fmtDate(iso) {
    if (!iso) return '';
    var d = new Date(iso + 'T00:00:00');
    if (isNaN(d)) return iso;
    return d.toLocaleDateString('en-US', { year:'numeric', month:'long', day:'2-digit' });
  }
  function dateFieldIds() { return (CFG.fields || []).filter(function (f){ return f.type === 'date'; }).map(function (f){ return f.id; }); }
  function isDateField(id) { return dateFieldIds().indexOf(id) >= 0; }

  /* ════════════════════════ BUILD CERTIFICATE OVERLAYS ════════════════════════ */
  function buildCertificate() {
    var c = el('certificate');
    var dual = !!CFG.name2;
    var parts = [];

    // Arched title
    parts.push(
      '<svg class="cert-title-svg" viewBox="0 0 900 636" preserveAspectRatio="xMidYMid meet" aria-label="' + esc(CFG.title) + '">' +
      '<defs><path id="certTitleArc" d="M 150 158 A 560 560 0 0 1 750 158" fill="none"/></defs>' +
      '<text id="certTitleTextEl" text-anchor="middle" fill="#1a1035" font-size="' + titleFontSize(CFG.title) + '">' +
      '<textPath id="certTitlePath" href="#certTitleArc" startOffset="50%">' + esc(CFG.title) + '</textPath></text></svg>');

    // Logo
    parts.push('<div class="cert-logo empty" id="logoWrap"></div>');

    // Pre-name line
    if (CFG.preline) parts.push('<div class="cert-preline">' + esc(CFG.preline) + '</div>');

    // Recipient name(s)
    parts.push('<div class="cert-name' + (dual ? ' dual' : '') + '" id="certName">' + esc(CFG.name.label || 'Recipient Name') + '</div>');

    // Divider rule
    parts.push('<div class="cert-rule"><span class="dia-l"></span><span class="dia-r"></span></div>');

    // Body
    parts.push('<div class="cert-para" id="certBody"></div>');

    // Signatures
    var sigs = CFG.signatures || [];
    var sigHtml = sigs.map(function (s, i) {
      return '<div class="cert-sig"><div class="cert-sig-name" id="sigName' + i + '"></div>' +
             '<div class="cert-sig-line"></div>' +
             '<div class="cert-sig-title" id="sigTitle' + i + '">' + esc(s.title || '') + '</div></div>';
    }).join('');
    parts.push('<div class="cert-sigs ' + (sigs.length === 2 ? 'two' : 'one') + '">' + sigHtml + '</div>');

    // Seal
    parts.push('<div class="cert-seal' + (CFG.seal ? '' : ' hidden') + '" id="certSeal">' + (CFG.seal ? sealSvg() : '') + '</div>');

    // Scripture / footer
    if (CFG.scripture) parts.push('<div class="cert-scripture" id="certScripture">' + nl2br(CFG.scripture) + '</div>');

    // Record number
    parts.push('<div class="cert-number" id="certNumber"></div>');

    c.innerHTML = parts.join('\n');
  }

  /** Arched-title font size — shrink for longer titles so ends never clip. */
  function titleFontSize(t) {
    var n = (t || '').length;
    if (n >= 30) return 24;
    if (n >= 26) return 27;
    if (n >= 24) return 29;
    return 31;
  }
  /** Update the arched title text live (used by the "other" custom-title field). */
  function setTitle(txt) {
    currentTitle = (txt && txt.trim()) ? txt.trim().toUpperCase() : (CFG.title || '');
    var p = el('certTitlePath'), t = el('certTitleTextEl');
    if (p) p.textContent = currentTitle;
    if (t) t.setAttribute('font-size', titleFontSize(currentTitle));
  }

  /** An elegant gold/navy vector seal (ribbon rosette). */
  function sealSvg() {
    var gold = '#c9a24b', goldD = '#a6822f', navy = '#1a1035';
    var pts = [];
    for (var i = 0; i < 24; i++) {
      var a = (Math.PI * 2 / 24) * i;
      var r = (i % 2 === 0) ? 46 : 40;
      pts.push((52 + r * Math.cos(a)).toFixed(1) + ',' + (52 + r * Math.sin(a)).toFixed(1));
    }
    return '<svg viewBox="0 0 104 124" width="104" height="124" xmlns="http://www.w3.org/2000/svg">' +
      // ribbon tails
      '<path d="M40 92 L30 122 L46 112 L52 122 L58 112 L74 122 L64 92 Z" fill="' + goldD + '"/>' +
      // scalloped outer
      '<polygon points="' + pts.join(' ') + '" fill="' + gold + '"/>' +
      '<circle cx="52" cy="52" r="38" fill="#fff"/>' +
      '<circle cx="52" cy="52" r="36" fill="none" stroke="' + navy + '" stroke-width="2"/>' +
      '<circle cx="52" cy="52" r="29" fill="none" stroke="' + gold + '" stroke-width="1.4"/>' +
      // central star
      '<path d="M52 34 l4.6 9.3 10.3 1.5 -7.4 7.2 1.7 10.2 -9.2 -4.8 -9.2 4.8 1.7 -10.2 -7.4 -7.2 10.3 -1.5 Z" fill="' + gold + '" stroke="' + goldD + '" stroke-width=".6"/>' +
      '</svg>';
  }

  /* ════════════════════════ BUILD EDIT PANEL ════════════════════════ */
  function buildPanel() {
    var body = el('editPanelBody');
    var html = '';

    // Recipient name slot(s)
    html += nameSlotHtml('name', CFG.name);
    if (CFG.name2) html += nameSlotHtml('name2', CFG.name2);

    // Name style controls
    html +=
      '<div class="ep-section-title">Name Style</div>' +
      '<div class="ep-field"><div class="ep-controls">' +
        '<select class="ep-select" id="epNameFont">' +
          '<option value="\'Great Vibes\', cursive">Great Vibes</option>' +
          '<option value="\'EB Garamond\', Georgia, serif">Garamond</option>' +
          '<option value="\'Cinzel\', serif">Cinzel</option>' +
          '<option value="Georgia, serif">Georgia</option>' +
        '</select>' +
        '<input type="color" class="ep-color" id="epNameColor" value="#1a1035" title="Name color" />' +
        '<button class="ep-toggle" id="epNameBold" title="Bold">B</button>' +
        '<button class="ep-toggle" id="epNameItalic" title="Italic" style="font-style:italic;">I</button>' +
      '</div></div>';

    // Church name (standard, prefilled from church info)
    html +=
      '<div class="ep-section-title">Details</div>' +
      '<div class="ep-field"><label class="ep-label">Church Name</label>' +
      '<input type="text" class="ep-input" id="fld_church" placeholder="Church name" /></div>';

    // Type-specific fields
    (CFG.fields || []).forEach(function (f) {
      html += '<div class="ep-field"><label class="ep-label">' + esc(f.label) + '</label>';
      if (f.type === 'textarea') {
        html += '<textarea class="ep-input" id="fld_' + f.id + '" rows="3" placeholder="' + esc(f.placeholder || '') + '"></textarea>';
      } else if (f.type === 'date') {
        html += '<input type="date" class="ep-input" id="fld_' + f.id + '" />';
      } else {
        html += '<input type="text" class="ep-input" id="fld_' + f.id + '" placeholder="' + esc(f.placeholder || '') + '" />';
      }
      html += '</div>';
    });

    // Scripture override (only if the type has one)
    if (CFG.scripture) {
      html += '<div class="ep-section-title">Scripture</div>' +
        '<div class="ep-field"><textarea class="ep-input" id="fld_scripture" rows="4"></textarea></div>';
    }

    body.innerHTML = html;

    // Wire inputs
    if (CFG.name)  wireNameSlot('name');
    if (CFG.name2) wireNameSlot('name2');

    el('epNameFont').addEventListener('change', function () { nameStyle.font = this.value; applyNameStyle(); });
    el('epNameColor').addEventListener('input', function () { nameStyle.color = this.value; applyNameStyle(); });
    el('epNameBold').addEventListener('click', function () { nameStyle.bold = !nameStyle.bold; this.classList.toggle('active', nameStyle.bold); applyNameStyle(); });
    el('epNameItalic').addEventListener('click', function () { nameStyle.italic = !nameStyle.italic; this.classList.toggle('active', nameStyle.italic); applyNameStyle(); });

    el('fld_church').addEventListener('input', function () { fieldVals.church = this.value; renderBody(); });
    (CFG.fields || []).forEach(function (f) {
      var input = el('fld_' + f.id);
      if (!input) return;
      input.addEventListener('input', function () { fieldVals[f.id] = this.value; if (CFG.titleField === f.id) setTitle(this.value); renderBody(); renderSignatures(); renderNumber(); });
    });
    if (CFG.scripture) {
      el('fld_scripture').addEventListener('input', function () { var s = el('certScripture'); if (s) s.innerHTML = nl2br(this.value); });
    }
  }

  function nameSlotHtml(slot, def) {
    var h = '<div class="ep-section-title">' + esc(def.label || 'Name') + '</div>';
    if (def.member) {
      h +=
        '<div class="ep-field"><label class="ep-label">Search Member</label>' +
        '<div class="search-wrap" id="wrap_' + slot + '">' +
          '<div class="search-inner">' +
            '<input type="text" id="srch_' + slot + '" placeholder="Type a name…" autocomplete="off" />' +
            '<button class="search-clear" id="clr_' + slot + '" style="display:none;">✕</button>' +
          '</div>' +
          '<div class="dropdown" id="dd_' + slot + '"></div>' +
        '</div>' +
        '<div class="selected-badge" id="badge_' + slot + '"><span class="selected-badge-name" id="badgeName_' + slot + '"></span>' +
        '<button class="selected-badge-rm" id="badgeRm_' + slot + '" title="Remove">✕</button></div></div>' +
        '<div class="or-div">OR</div>';
    }
    h += '<div class="ep-field"><label class="ep-label">' + (def.member ? 'Enter Name Manually' : esc(def.label || 'Name')) + '</label>' +
         '<input type="text" class="ep-input" id="manual_' + slot + '" placeholder="' + esc(def.placeholder || 'Full name') + '" /></div>';
    return h;
  }

  /* ════════════════════════ MEMBER SEARCH (per slot) ════════════════════════ */
  function wireNameSlot(slot) {
    var def = CFG[slot];
    slots[slot] = { selected: null, filtered: [], hi: -1 };
    var manual = el('manual_' + slot);
    manual.addEventListener('input', function () {
      if (def.member && slots[slot].selected) clearSelection(slot, false);
      setName(slot, this.value);
    });
    if (!def.member) return;

    var srch = el('srch_' + slot);
    srch.addEventListener('input', function () { el('clr_' + slot).style.display = this.value ? '' : 'none'; if (slots[slot].selected && this.value !== slots[slot].selected._n) clearSelection(slot, false); filterDD(slot, this.value); });
    srch.addEventListener('focus', function () { filterDD(slot, this.value); });
    srch.addEventListener('keydown', function (e) { onSearchKey(slot, e); });
    el('clr_' + slot).addEventListener('click', function () { clearSelection(slot, true); setName(slot, ''); });
    el('badgeRm_' + slot).addEventListener('click', function () { clearSelection(slot, true); setName(slot, ''); });
    document.addEventListener('click', function (e) { var w = el('wrap_' + slot); if (w && !w.contains(e.target)) closeDD(slot); });
  }
  function filterDD(slot, val) {
    var q = (val || '').trim().toLowerCase();
    var list = el('dd_' + slot);
    if (!q) { closeDD(slot); return; }
    slots[slot].filtered = allMembers.filter(function (m) { return memberName(m).toLowerCase().indexOf(q) >= 0; }).slice(0, 50);
    list.innerHTML = slots[slot].filtered.length
      ? slots[slot].filtered.map(function (m, i) { return '<div class="dd-item" data-i="' + i + '">' + esc(memberName(m)) + '</div>'; }).join('')
      : '<div class="dd-empty">No members found</div>';
    Array.prototype.forEach.call(list.querySelectorAll('.dd-item'), function (it) {
      it.addEventListener('mousedown', function () { pickMember(slot, parseInt(this.getAttribute('data-i'), 10)); });
      it.addEventListener('mouseover', function () { hiDD(slot, parseInt(this.getAttribute('data-i'), 10)); });
    });
    list.classList.add('open'); slots[slot].hi = -1;
  }
  function closeDD(slot) { var l = el('dd_' + slot); if (l) l.classList.remove('open'); slots[slot].hi = -1; }
  function hiDD(slot, i) { slots[slot].hi = i; Array.prototype.forEach.call(el('dd_' + slot).querySelectorAll('.dd-item'), function (e2, j) { e2.classList.toggle('hi', j === i); }); }
  function onSearchKey(slot, e) {
    var list = el('dd_' + slot); if (!list.classList.contains('open')) return;
    var n = slots[slot].filtered.length;
    if (e.key === 'ArrowDown') { e.preventDefault(); hiDD(slot, Math.min(slots[slot].hi + 1, n - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); hiDD(slot, Math.max(slots[slot].hi - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); if (slots[slot].hi >= 0) pickMember(slot, slots[slot].hi); }
    else if (e.key === 'Escape') closeDD(slot);
  }
  function pickMember(slot, i) {
    var m = slots[slot].filtered[i]; if (!m) return;
    var name = memberName(m); m._n = name;
    slots[slot].selected = m;
    el('srch_' + slot).value = name;
    el('clr_' + slot).style.display = '';
    el('badgeName_' + slot).textContent = name;
    el('badge_' + slot).classList.add('show');
    el('manual_' + slot).value = '';
    closeDD(slot); setName(slot, name);
  }
  function clearSelection(slot, resetInput) {
    slots[slot].selected = null;
    if (resetInput && el('srch_' + slot)) { el('srch_' + slot).value = ''; el('clr_' + slot).style.display = 'none'; }
    var b = el('badge_' + slot); if (b) b.classList.remove('show');
    closeDD(slot);
  }
  function memberName(m) { return [m.firstName, m.lastName].filter(Boolean).join(' ') || m.name || ''; }

  /* ════════════════════════ FIELD SYNC / RENDER ════════════════════════ */
  function setName(slot, val) {
    nameVals[slot] = val || '';
    renderName(); renderBody(); renderSignatures();
  }
  function renderName() {
    var eln = el('certName');
    if (CFG.name2) {
      var a = nameVals.name || '________', b = nameVals.name2 || '________';
      var join = CFG.name2.join || '&';
      eln.textContent = a + '   ' + join + '   ' + b;
    } else {
      eln.textContent = nameVals.name || (CFG.name.label || 'Recipient Name');
    }
    applyNameStyle();
  }
  function applyNameStyle() {
    var eln = el('certName');
    eln.style.fontFamily = nameStyle.font;
    eln.style.color = nameStyle.color;
    eln.style.fontWeight = nameStyle.bold ? '700' : 'normal';
    eln.style.fontStyle = nameStyle.italic ? 'italic' : 'normal';
  }
  function tokenValue(tok) {
    if (tok === 'church') return fieldVals.church || church.name || '';
    if (tok === 'city')   return church.city || '';
    if (tok === 'name')   return nameVals.name || '';
    if (tok === 'name2')  return nameVals.name2 || '';
    var v = fieldVals[tok] || '';
    if (isDateField(tok)) return fmtDate(v);
    return v;
  }
  function renderBody() {
    var tmpl = CFG.body || '';
    var out = tmpl.replace(/\{(\w+)\}/g, function (_, tok) { return tokenValue(tok); });
    el('certBody').innerHTML = nl2br(out);
  }
  function renderSignatures() {
    (CFG.signatures || []).forEach(function (s, i) {
      var nm = '';
      if (s.nameField === 'name') nm = nameVals.name;
      else if (s.nameField === 'name2') nm = nameVals.name2;
      else if (s.nameField) nm = fieldVals[s.nameField] || '';
      var ne = el('sigName' + i); if (ne) ne.textContent = nm || '';
    });
  }
  function renderNumber() {
    var e2 = el('certNumber'); if (!e2) return;
    var num = CFG.certNumberField ? (fieldVals[CFG.certNumberField] || '') : '';
    e2.textContent = num ? ('No. ' + num) : '';
  }

  /* ════════════════════════ SCALING ════════════════════════ */
  function scaleCert() {
    var host = el('certScaleHost');
    var scale = host.clientWidth / CERT_W;
    el('certificate').style.transform = 'scale(' + scale + ')';
  }
  window.addEventListener('resize', scaleCert);

  /* ════════════════════════ CHURCH / LOGO / MEMBERS ════════════════════════ */
  async function loadChurchInfo() {
    var churchName = '', cityState = '';
    try { var r = await fetch('/api/email-settings'); if (r.ok) { var d = await r.json(); if (d.displayName) churchName = d.displayName; } } catch (e) {}
    try {
      var rs = await fetch('/api/session');
      if (rs.ok) {
        var sess = await rs.json();
        var cid = sess.appClientId || sess.clientId || '';
        if (cid) {
          var rp = await fetch('/api/churchregistration/prefill?clientId=' + encodeURIComponent(cid));
          if (rp.ok) { var dd = await rp.json(); cityState = [dd.city, dd.state].filter(Boolean).join(', '); if (!churchName && dd.churchName) churchName = dd.churchName; }
        }
      }
    } catch (e) {}
    church.name = churchName || '';
    church.city = cityState || '';
    if (church.name) { fieldVals.church = church.name; var fc = el('fld_church'); if (fc) fc.value = church.name; }
    renderBody();
  }
  function loadLogo() {
    var img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function () { var wrap = el('logoWrap'); wrap.innerHTML = ''; wrap.classList.remove('empty'); img.style.cssText = 'width:100%;height:100%;object-fit:contain;display:block;'; wrap.appendChild(img); };
    img.onerror = function () {};
    img.src = '/api/logo/image?' + Date.now();
  }
  async function loadMembers() {
    try { var res = await fetch('/api/members?search=&showDeleted=false&showInactive=false'); if (res.ok) { var d = await res.json(); allMembers = Array.isArray(d) ? d : []; } } catch (e) {}
  }

  /* ════════════════════════ PRINT / PDF / SAVE / RESET ════════════════════════ */
  window.printCert = function () { window.print(); };

  window.downloadPdf = async function () {
    var btn = el('downloadBtn'); var orig = btn.innerHTML;
    btn.disabled = true; btn.innerHTML = '⏳ Generating…';
    var certEl = el('certificate'); var prev = certEl.style.transform;
    certEl.style.transform = 'none';
    try {
      var canvas = await html2canvas(certEl, { scale: 2, useCORS: true, allowTaint: true, backgroundColor: null, logging: false, width: CERT_W, height: CERT_H });
      var jsPDF = window.jspdf.jsPDF;
      var pdf = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' });
      pdf.addImage(canvas.toDataURL('image/jpeg', 0.95), 'JPEG', 0, 0, pdf.internal.pageSize.getWidth(), pdf.internal.pageSize.getHeight());
      var safe = (nameVals.name || CFG.fileName || 'certificate').replace(/[^a-zA-Z0-9\s]/g, '').trim().replace(/\s+/g, '_');
      pdf.save((CFG.fileName || 'Certificate') + '_' + safe + '.pdf');
    } catch (e) { console.error(e); alert('PDF generation failed. Please use Print instead.'); }
    finally { certEl.style.transform = prev; btn.disabled = false; btn.innerHTML = orig; }
  };

  window.saveRecord = async function () {
    var btn = el('saveBtn'); var orig = btn ? btn.innerHTML : '';
    if (!nameVals.name) { toast('Enter a recipient name first.', true); return; }
    if (btn) { btn.disabled = true; btn.innerHTML = '⏳ Saving…'; }
    var details = { names: nameVals, fields: fieldVals, nameStyle: nameStyle };
    var primaryDate = CFG.primaryDateField ? fieldVals[CFG.primaryDateField] : (dateFieldIds()[0] ? fieldVals[dateFieldIds()[0]] : '');
    var payload = {
      certType: CFG.key,
      title: currentTitle || CFG.title,
      recipientName: nameVals.name,
      secondaryName: nameVals.name2 || null,
      churchName: fieldVals.church || church.name || '',
      issuedDate: primaryDate || '',
      certNumber: CFG.certNumberField ? (fieldVals[CFG.certNumberField] || '') : '',
      details: details
    };
    try {
      var r = await fetch('/api/certificates/issue', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
      if (r.ok) { var d = await r.json(); toast('Saved · Record ' + (d.certNumber || '#' + d.id)); }
      else if (r.status === 401) toast('Please sign in to save records.', true);
      else toast('Could not save the record.', true);
    } catch (e) { toast('Could not save the record.', true); }
    finally { if (btn) { btn.disabled = false; btn.innerHTML = orig; } }
  };

  window.resetCert = function () {
    nameVals = { name: '', name2: '' };
    nameStyle = { font: "'Great Vibes', cursive", color: '#1a1035', bold: false, italic: false };
    ['name', 'name2'].forEach(function (slot) {
      if (!CFG[slot]) return;
      if (el('manual_' + slot)) el('manual_' + slot).value = '';
      if (CFG[slot].member) clearSelection(slot, true);
    });
    el('epNameFont').value = "'Great Vibes', cursive";
    el('epNameColor').value = '#1a1035';
    el('epNameBold').classList.remove('active');
    el('epNameItalic').classList.remove('active');
    // reset fields to defaults
    (CFG.fields || []).forEach(function (f) {
      var input = el('fld_' + f.id); if (!input) return;
      var v = (f.type === 'date') ? (f.id === (CFG.primaryDateField || dateFieldIds()[0]) ? todayISO() : '') : '';
      input.value = v; fieldVals[f.id] = v;
    });
    if (CFG.scripture) el('fld_scripture').value = CFG.scripture;
    if (CFG.titleField) setTitle('');
    if (church.name) { fieldVals.church = church.name; el('fld_church').value = church.name; }
    var sc = el('certScripture'); if (sc && CFG.scripture) sc.innerHTML = nl2br(CFG.scripture);
    renderName(); renderBody(); renderSignatures(); renderNumber();
  };

  var toastT;
  function toast(msg, err) {
    var t = el('certToast');
    if (!t) { t = document.createElement('div'); t.id = 'certToast'; t.className = 'cert-toast'; document.body.appendChild(t); }
    t.textContent = msg; t.className = 'cert-toast show' + (err ? ' err' : '');
    clearTimeout(toastT); toastT = setTimeout(function () { t.className = 'cert-toast'; }, 3200);
  }

  /* ════════════════════════ INIT ════════════════════════ */
  async function init() {
    buildCertificate();
    buildPanel();
    try { await document.fonts.ready; } catch (e) {}
    scaleCert();

    // defaults
    (CFG.fields || []).forEach(function (f) {
      var input = el('fld_' + f.id); if (!input) return;
      if (f.type === 'date' && f.id === (CFG.primaryDateField || dateFieldIds()[0])) { input.value = todayISO(); fieldVals[f.id] = todayISO(); }
      else fieldVals[f.id] = '';
    });
    fieldVals.church = '';
    renderName(); renderBody(); renderSignatures(); renderNumber();

    await loadChurchInfo();
    loadLogo();
    await loadMembers();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
