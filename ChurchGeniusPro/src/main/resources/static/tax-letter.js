/* ChurchGeniusPro — the Year-End Tax Report letter, in one place (2026-11-20).
 *
 * The Tax Report page and Genius Assistant both draw contribution statements with this file, so a statement looks
 * the same and carries the same rows wherever it was asked for. It only DRAWS: every name, address, gift and total
 * comes from the report the server sent (GET /api/reports/tax, or the assistant's answer) and is written as text
 * (escaped). The wording around the table (introHtml / closingHtml) is HTML the server sanitised or built itself.
 *
 *   CGP_TAX_LETTER.lettersHtml(data, year, logoDataUrl)  → '<div class="letters-wrap">…one .letter-page per contributor…</div>'
 *   CGP_TAX_LETTER.loadLogo()                            → Promise<dataUrl|null>   (the church logo, when there is one)
 *   CGP_TAX_LETTER.ensureCss()                           → adds the letter styles to a page that does not have them
 *   CGP_TAX_LETTER.downloadPdf(element, filename)        → Promise; loads html2pdf on first use
 */
(function(){
  'use strict';
  if (window.CGP_TAX_LETTER) return;

  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
  function fmt(n){ return '$'+Number(n).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2}); }
  var MONTHS=['January','February','March','April','May','June','July','August','September','October','November','December'];
  function fmtDate(s){
    if(!s) return '';
    var p=String(s).split('-');
    return MONTHS[parseInt(p[1],10)-1]+' '+parseInt(p[2],10)+', '+p[0];
  }
  function fmtDateShort(s){
    if(!s) return '';
    var p=String(s).split('-');
    return MONTHS[parseInt(p[1],10)-1].slice(0,3)+' '+parseInt(p[2],10)+', '+p[0];
  }
  function today(){
    var d=new Date();                                   // the reader's own date, as the page has always printed
    return fmtDate(d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'));
  }

  /* The original wording, used only if the server sends none. */
  function DEFAULT_INTRO_HTML(churchName, year) {
    return '<p class="ltr-body">Thank you for your faithful giving and generous support to <strong>'
      + churchName + '</strong> during the <strong>' + year + '</strong> calendar year. This letter '
      + 'serves as your official charitable contribution statement for income tax purposes.</p>';
  }
  var DEFAULT_CLOSING_HTML =
      '<div class="ltr-disclaimer"><strong>Important Tax Notice:</strong> No goods or services were '
    + 'provided to you in exchange for these contributions. This statement is provided in accordance '
    + 'with IRS requirements for charitable contributions. Please retain this letter for your tax '
    + 'records. We recommend consulting a qualified tax professional for guidance on deductibility.</div>'
    + '<p class="ltr-closing">We are truly grateful for your partnership and continued support of our '
    + 'ministry. Your generosity makes a meaningful difference in our congregation and community. '
    + 'May God bless you abundantly for your faithfulness.</p>';

  /* One letter per contributor in data.members; '' when there is nobody. */
  function lettersHtml(data, year, churchLogoDataUrl){
    data = data || {};
    const members = data.members || [];
    if (!members.length) return '';
    year = esc(year);
    let stored = ''; try { stored = localStorage.getItem('churchName') || ''; } catch(e) {}
    const churchName = esc(data.churchName || stored || '');
    // The church's own wording around the contribution table, sanitised and
    // placeholder-filled server-side. The literals are the original text, kept only
    // as a fallback for a server that sends neither.
    const introHtml   = data.introHtml   || DEFAULT_INTRO_HTML(churchName, year);
    const closingHtml = data.closingHtml || DEFAULT_CLOSING_HTML;
    const logoHtml = churchLogoDataUrl
      ? `<div style="text-align:center;margin-bottom:14px;"><img src="${churchLogoDataUrl}" alt="Church Logo" style="max-height:90px;max-width:220px;object-fit:contain;" /></div>`
      : '';
    const todayStr = today();
    let html = '<div class="letters-wrap">';

    members.forEach(m => {
      // For guests: firstName holds the full guestName, lastName is empty
      const firstName   = esc(m.firstName || '');
      const lastName    = esc(m.lastName  || '');
      const displayName = lastName ? `${firstName} ${lastName}`.trim() : firstName;
      const addr1       = esc(m.address1 || '');
      const addr2       = esc(m.address2 || '');
      const city        = esc(m.city     || '');
      const state       = esc(m.state    || '');
      const pin         = esc(m.pinCode  || '');
      const cityLine    = [city, state, pin].filter(Boolean).join(', ');
      const memberTotal = Number(m.memberTotal || 0);
      const entries     = m.entries || [];
      const hasDates    = entries.length > 0;

      // Address block
      let addrHtml = `<strong>${displayName}</strong>`;
      if (addr1) addrHtml += `<br>${addr1}`;
      if (addr2) addrHtml += `<br>${addr2}`;
      if (cityLine) addrHtml += `<br>${cityLine}`;

      // Contribution rows
      let tRows = '';
      if (hasDates) {
        entries.forEach(e => {
          tRows += `<tr>
            <td>${esc(fmtDateShort(e.date))}</td>
            <td>${esc(e.subCategory || e.mainCategory || '')}</td>
            <td class="ltr-tbl-amt">${fmt(e.amount)}</td>
            <td>${esc(e.method || '')}</td>
          </tr>`;
        });
      } else {
        (m.contributions || []).forEach(c => {
          tRows += `<tr>
            <td colspan="2">${esc(c.subCategory || c.mainCategory || '')}</td>
            <td class="ltr-tbl-amt">${fmt(c.total)}</td>
            <td></td>
          </tr>`;
        });
      }

      // Salutation: use first name if available, else full guest name
      const salutationName = firstName || displayName;

      html += `
      <div class="letter-page">
        ${logoHtml}<div class="ltr-church-name">${churchName}</div>
        <hr class="ltr-divider"/>
        <div class="ltr-heading">Year-End Giving Summary — Tax Year ${year}</div>
        <div class="ltr-top">
          <div class="ltr-date">${todayStr}</div>
          <div class="ltr-address">${addrHtml}</div>
        </div>
        <p class="ltr-salutation">Dear ${salutationName},</p>
        ${introHtml}
        <div class="ltr-table-wrap">
          <table class="ltr-tbl">
            <thead><tr>
              <th style="width:18%">Date</th>
              <th>Category</th>
              <th class="ltr-tbl-amt" style="width:16%">Amount</th>
              <th style="width:14%">Method</th>
            </tr></thead>
            <tbody>${tRows}</tbody>
            <tfoot><tr>
              <td colspan="2">Total Contributions — ${year}</td>
              <td class="ltr-tbl-amt">${fmt(memberTotal)}</td>
              <td></td>
            </tr></tfoot>
          </table>
        </div>
        ${closingHtml}
        <p class="ltr-closing">Sincerely,</p>
        <div class="ltr-sig-name">${data.signatureName || churchName}</div>
        <div class="ltr-sig-title">${data.signatureTitle || 'Finance Department'}</div>
      </div>`;
    });


    html += '</div>';
    return html;
  }

  var CSS = ".letters-wrap{font-family:Georgia,'Times New Roman',serif;}.letter-page{background:#fff;border:1px solid #ddd;border-radius:4px;padding:48px 56px;margin-bottom:24px;max-width:760px;page-break-after: always;}.letter-page:last-child{page-break-after:auto;}.ltr-church-name{font-size:22px;font-weight:bold;text-align:center;color:#1a237e;letter-spacing:.5px;margin-bottom:4px;}.ltr-church-sub{font-size: 13px;text-align:center;color:#555;margin-bottom:2px;}.ltr-divider{border:none;border-top:2px solid #3949ab;margin:14px 0 18px;}.ltr-heading{font-size:14px;font-weight:bold;text-align:center;color:#1a237e;text-transform:uppercase;letter-spacing:1px;margin-bottom:18px;}.ltr-top{display:flex;justify-content:space-between;gap:20px;margin-bottom:24px;}.ltr-date{font-size:13px;color:#333;}.ltr-address{font-size:13px;color:#333;text-align:left;}.ltr-address strong{font-size:14px;display:block;margin-bottom:2px;}.ltr-salutation{font-size:13px;margin-bottom:10px;color:#222;}.ltr-body{font-size:13px;line-height:1.7;color:#444;margin-bottom:18px;}.ltr-table-wrap{margin:0 0 16px;}table.ltr-tbl{width:100%;border-collapse:collapse;font-size:13px;font-family:inherit;}table.ltr-tbl thead th{background:#e8eaf6;color:#1a237e;font-weight:bold;padding:8px 10px;text-align:left;border-bottom:2px solid #3949ab;font-size: 13px;text-transform:uppercase;letter-spacing:.4px;}table.ltr-tbl tbody td{padding:7px 10px;border-bottom:1px solid #eee;color:#333;vertical-align:top;}table.ltr-tbl tbody tr:nth-child(even) td{background:#fafafa;}table.ltr-tbl tfoot td{padding:9px 10px;font-weight:bold;border-top:2px solid #3949ab;background:#e8eaf6;color:#1a237e;}.ltr-tbl-amt{text-align:right;}.ltr-disclaimer{font-size:11.5px;color:#555;border:1px solid #ddd;border-radius:4px;padding:10px 14px;background:#fafafa;line-height:1.6;margin-bottom:18px;}.ltr-closing{font-size:13px;color:#333;margin-bottom:24px;line-height:1.6;}.ltr-sig-name{font-size:14px;font-weight:bold;color:#1a237e;border-top:1px solid #333;display:inline-block;padding-top:4px;margin-top:32px;min-width:220px;}.ltr-sig-title{font-size: 13px;color:#666;margin-top:2px;}";
  function ensureCss(){
    if (document.getElementById('cgpTaxLetterCss')) return;
    var st=document.createElement('style'); st.id='cgpTaxLetterCss'; st.textContent=CSS; document.head.appendChild(st);
  }

  function loadLogo(){
    return fetch('/api/logo/image').then(function(res){
      if (!res.ok) return null;
      return res.blob().then(function(blob){
        return new Promise(function(resolve){
          var reader=new FileReader();
          reader.onloadend=function(){ resolve(typeof reader.result==='string' && /^data:image\//.test(reader.result) ? reader.result : null); };
          reader.onerror=function(){ resolve(null); };
          reader.readAsDataURL(blob);
        });
      });
    }).catch(function(){ return null; });
  }

  var PDF_LIB='https://cdnjs.cloudflare.com/ajax/libs/html2pdf.js/0.10.1/html2pdf.bundle.min.js', _pdfLoading=null;
  function loadPdfLib(){
    if (typeof window.html2pdf==='function') return Promise.resolve();
    if (_pdfLoading) return _pdfLoading;
    _pdfLoading=new Promise(function(resolve, reject){
      var sc=document.createElement('script'); sc.src=PDF_LIB;
      sc.onload=function(){ resolve(); };
      sc.onerror=function(){ _pdfLoading=null; reject(new Error('The PDF tool could not be loaded.')); };
      document.head.appendChild(sc);
    });
    return _pdfLoading;
  }
  /* The same settings the Tax Report page has always used: A4 portrait, one letter per page. */
  function downloadPdf(el, filename){
    return loadPdfLib().then(function(){
      return window.html2pdf().set({
        margin: [12,12,12,12],
        filename: filename || 'tax-report.pdf',
        image: { type:'jpeg', quality:0.98 },
        html2canvas: { scale:2, useCORS:true, letterRendering:true },
        jsPDF: { unit:'mm', format:'a4', orientation:'portrait' }
      }).from(el).save();
    });
  }

  window.CGP_TAX_LETTER = { lettersHtml:lettersHtml, loadLogo:loadLogo, ensureCss:ensureCss, downloadPdf:downloadPdf, loadPdfLib:loadPdfLib,
                            fmt:fmt, esc:esc, DEFAULT_INTRO_HTML:DEFAULT_INTRO_HTML, DEFAULT_CLOSING_HTML:DEFAULT_CLOSING_HTML };
})();
