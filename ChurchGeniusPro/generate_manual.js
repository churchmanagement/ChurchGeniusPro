'use strict';
/**
 * ChurchGeniusPro — User Manual Generator
 * Uses PDFKit to produce a professional multi-chapter PDF manual.
 */

const PDFDocument = require('pdfkit');
const fs          = require('fs');
const path        = require('path');

const OUTPUT = path.join(__dirname,
  'src', 'main', 'resources', 'static', 'ChurchGeniusPro_User_Manual.pdf');

// ── Colour palette ────────────────────────────────────────────────────────
const C = {
  indigo:      '#3f4568',
  indigoLight: '#5c6bc0',
  indigoPale:  '#e8eaf6',
  indigoDark:  '#1a1c3a',
  green:       '#2e7d32',
  greenLight:  '#e8f5e9',
  red:         '#c62828',
  redLight:    '#ffebee',
  gold:        '#f9a825',
  greyDark:    '#555555',
  greyMid:     '#888888',
  greyLight:   '#f5f6fa',
  white:       '#ffffff',
  black:       '#000000',
};

// ── Page geometry ─────────────────────────────────────────────────────────
const PAGE_W = 612;   // letter width  (points)
const PAGE_H = 792;   // letter height (points)
const M_L    = 54;    // left margin
const M_R    = 54;    // right margin
const M_T    = 54;    // top margin
const M_B    = 60;    // bottom margin
const BODY_W = PAGE_W - M_L - M_R;

// ── Doc ───────────────────────────────────────────────────────────────────
const doc = new PDFDocument({
  size: 'LETTER',
  margins: { top: M_T, bottom: M_B, left: M_L, right: M_R },
  info: {
    Title:   'ChurchGeniusPro User Manual',
    Author:  'ChurchGeniusPro',
    Subject: 'Complete User Manual',
  },
  bufferPages: true,
});

doc.pipe(fs.createWriteStream(OUTPUT));

// ── Cursor tracking ───────────────────────────────────────────────────────
let y = M_T;  // current vertical position

function newPage() {
  doc.addPage();
  y = M_T;
}

function ensureSpace(needed) {
  if (y + needed > PAGE_H - M_B) newPage();
}

// ── Drawing primitives ────────────────────────────────────────────────────

/** Filled rectangle helper */
function fillRect(x, yy, w, h, color) {
  doc.save().fillColor(color).rect(x, yy, w, h).fill().restore();
}

/** Horizontal rule */
function hr(color = C.indigoPale, thickness = 0.8) {
  ensureSpace(10);
  doc.save()
     .strokeColor(color).lineWidth(thickness)
     .moveTo(M_L, y).lineTo(PAGE_W - M_R, y).stroke()
     .restore();
  y += 8;
}

/** Vertical spacer */
function sp(h = 8) {
  y += h;
}

/** Draw plain text, returns height used */
function text(str, opts = {}) {
  const fontSize  = opts.fontSize  || 10;
  const fontColor = opts.color     || C.greyDark;
  const align     = opts.align     || 'left';
  const font      = opts.bold      ? 'Helvetica-Bold'
                  : opts.italic    ? 'Helvetica-Oblique'
                  : 'Helvetica';
  const indent    = opts.indent    || 0;
  const width     = opts.width     || (BODY_W - indent);
  const lineGap   = opts.lineGap   || 2;
  const cont      = opts.continued || false;

  ensureSpace(fontSize + 6);

  doc.save()
     .font(font).fontSize(fontSize).fillColor(fontColor)
     .text(str, M_L + indent, y, {
       width, align, lineGap, continued: cont,
     })
     .restore();

  if (!cont) {
    y = doc.y + (opts.after || 5);
  }
}

/** Bullet point */
function bullet(str, indent = 0) {
  const full = '\u2022  ' + str;
  ensureSpace(16);
  doc.save()
     .font('Helvetica').fontSize(10).fillColor(C.greyDark)
     .text(full, M_L + 14 + indent * 16, y, {
       width: BODY_W - 14 - indent * 16, align: 'left', lineGap: 2,
     })
     .restore();
  y = doc.y + 4;
}

/** Section heading */
function sectionHead(str) {
  ensureSpace(30);
  sp(10);
  doc.save().font('Helvetica-Bold').fontSize(13).fillColor(C.indigo)
     .text(str, M_L, y, { width: BODY_W }).restore();
  y = doc.y + 4;
  hr(C.indigoPale, 0.8);
}

/** Sub-heading */
function subHead(str) {
  ensureSpace(20);
  sp(6);
  doc.save().font('Helvetica-Bold').fontSize(11).fillColor(C.indigoLight)
     .text(str, M_L, y, { width: BODY_W }).restore();
  y = doc.y + 3;
}

// ── Chapter banner ────────────────────────────────────────────────────────
function chapterBanner(num, title, subtitle, color = C.indigo) {
  newPage();
  const bh = 58;
  fillRect(M_L, y, BODY_W, bh, color);

  // Number badge
  doc.save().circle(M_L + 28, y + bh / 2, 20).fillColor('#ffffff20').fill().restore();
  doc.save().font('Helvetica-Bold').fontSize(18).fillColor(C.white)
     .text(String(num), M_L + 10, y + bh / 2 - 10, { width: 36, align: 'center' })
     .restore();

  // Title
  doc.save().font('Helvetica-Bold').fontSize(17).fillColor(C.white)
     .text(title, M_L + 60, y + 11, { width: BODY_W - 65 }).restore();

  // Subtitle
  if (subtitle) {
    doc.save().font('Helvetica').fontSize(9).fillColor('#c5cae9')
       .text(subtitle, M_L + 60, y + 33, { width: BODY_W - 65 }).restore();
  }

  y += bh + 14;
}

// ── Info box ──────────────────────────────────────────────────────────────
function infoBox(str, kind = 'info') {
  const cfg = {
    info:    { bg: '#e3f2fd', border: '#1565c0', icon: 'i  ' },
    tip:     { bg: C.greenLight, border: C.green, icon: '\u2714  ' },
    warning: { bg: '#fff8e1',    border: C.gold,  icon: '\u26a0  ' },
    note:    { bg: C.indigoPale, border: C.indigoLight, icon: '\u{1F4CC} ' },
  };
  const { bg, border, icon } = cfg[kind] || cfg.info;

  // Measure height
  doc.save().font('Helvetica').fontSize(9);
  const th = doc.heightOfString(icon + str, { width: BODY_W - 24 }) + 16;
  doc.restore();

  ensureSpace(th + 8);
  sp(4);

  // Box
  doc.save().roundedRect(M_L, y, BODY_W, th, 4)
     .fillColor(bg).fill()
     .strokeColor(border).lineWidth(1.2)
     .roundedRect(M_L, y, BODY_W, th, 4).stroke()
     .restore();

  doc.save().font('Helvetica').fontSize(9).fillColor(C.greyDark)
     .text(icon + str, M_L + 12, y + 8, { width: BODY_W - 24, lineGap: 2 })
     .restore();

  y += th + 8;
}

// ── Field table ───────────────────────────────────────────────────────────
function fieldTable(rows) {
  const cols = [120, 80, BODY_W - 200];
  const headers = ['Field', 'Type', 'Description'];
  const rowH = 22;

  ensureSpace(rowH * (rows.length + 1) + 10);
  sp(4);

  // Header row
  let rx = M_L;
  fillRect(rx, y, BODY_W, rowH, C.indigo);
  headers.forEach((h, i) => {
    doc.save().font('Helvetica-Bold').fontSize(9).fillColor(C.white)
       .text(h, rx + 6, y + 7, { width: cols[i] - 8 }).restore();
    rx += cols[i];
  });
  y += rowH;

  // Data rows
  rows.forEach((row, ri) => {
    const bg = ri % 2 === 0 ? C.white : C.indigoPale;
    fillRect(M_L, y, BODY_W, rowH, bg);

    // Measure row height
    doc.save().font('Helvetica').fontSize(9);
    const cellH = Math.max(rowH,
      doc.heightOfString(row[2] || '', { width: cols[2] - 8 }) + 10);
    doc.restore();

    if (cellH > rowH) {
      fillRect(M_L, y, BODY_W, cellH, bg);
    }

    rx = M_L;
    row.forEach((cell, ci) => {
      const font = ci === 0 ? 'Helvetica-Bold' : 'Helvetica';
      doc.save().font(font).fontSize(9).fillColor(C.greyDark)
         .text(cell, rx + 6, y + 6, { width: cols[ci] - 8 }).restore();
      rx += cols[ci];
    });

    // Grid lines
    doc.save().strokeColor('#d1d5f0').lineWidth(0.5)
       .rect(M_L, y, BODY_W, Math.max(rowH, cellH)).stroke().restore();

    y += Math.max(rowH, cellH);
  });

  sp(8);
}

// ── Simple table (2-col) ──────────────────────────────────────────────────
function simpleTable(headers, rows, widths) {
  const rowH = 22;
  ensureSpace(rowH * (rows.length + 1) + 10);
  sp(4);

  let rx = M_L;
  fillRect(M_L, y, BODY_W, rowH, C.indigo);
  headers.forEach((h, i) => {
    doc.save().font('Helvetica-Bold').fontSize(9).fillColor(C.white)
       .text(h, rx + 6, y + 7, { width: widths[i] - 8 }).restore();
    rx += widths[i];
  });
  y += rowH;

  rows.forEach((row, ri) => {
    const bg = ri % 2 === 0 ? C.white : C.indigoPale;

    // Calculate max height for this row
    let maxH = rowH;
    doc.save().font('Helvetica').fontSize(9);
    row.forEach((cell, ci) => {
      const h = doc.heightOfString(String(cell), { width: widths[ci] - 10 }) + 10;
      if (h > maxH) maxH = h;
    });
    doc.restore();

    fillRect(M_L, y, BODY_W, maxH, bg);
    rx = M_L;
    row.forEach((cell, ci) => {
      doc.save().font('Helvetica').fontSize(9).fillColor(C.greyDark)
         .text(String(cell), rx + 6, y + 6, { width: widths[ci] - 10 }).restore();
      rx += widths[ci];
    });
    doc.save().strokeColor('#d1d5f0').lineWidth(0.5)
       .rect(M_L, y, BODY_W, maxH).stroke().restore();
    y += maxH;
  });

  sp(8);
}

// ── Page footer ───────────────────────────────────────────────────────────
function drawFooter(pageNum) {
  doc.save()
     .strokeColor(C.indigoPale).lineWidth(0.5)
     .moveTo(M_L, PAGE_H - 44).lineTo(PAGE_W - M_R, PAGE_H - 44).stroke()
     .font('Helvetica').fontSize(7.5).fillColor(C.greyMid)
     .text('ChurchGeniusPro \u2014 User Manual', M_L, PAGE_H - 36, { align: 'left' })
     .text(`Page ${pageNum}`, M_L, PAGE_H - 36, { align: 'right', width: BODY_W })
     .restore();
}

// ═══════════════════════════════════════════════════════════════════════════
// COVER PAGE
// ═══════════════════════════════════════════════════════════════════════════
function coverPage() {
  // Deep blue gradient (simulated with layered rects)
  for (let i = 0; i < 40; i++) {
    const frac = i / 40;
    const r = Math.round(0x3f + (0x1a - 0x3f) * frac);
    const g = Math.round(0x45 + (0x1c - 0x45) * frac);
    const b = Math.round(0x68 + (0x3a - 0x68) * frac);
    const hex = '#' + [r,g,b].map(v => v.toString(16).padStart(2,'0')).join('');
    fillRect(0, PAGE_H * (i / 40), PAGE_W, PAGE_H / 40 + 2, hex);
  }

  // Decorative circles
  doc.save().fillColor('#ffffff08')
     .circle(PAGE_W * 0.85, PAGE_H * 0.25, 130).fill()
     .circle(PAGE_W * 0.1,  PAGE_H * 0.8,  90).fill()
     .circle(PAGE_W * 0.5,  PAGE_H * 0.08, 180).fill()
     .restore();

  // Gold accent bar
  fillRect(36, PAGE_H * 0.38, 5, PAGE_H * 0.26, C.gold);

  // Church icon
  doc.save().font('Helvetica-Bold').fontSize(54).fillColor(C.white)
     .text('\u26EA', 0, PAGE_H * 0.42, { width: PAGE_W, align: 'center' })
     .restore();

  // Main title
  doc.save().font('Helvetica-Bold').fontSize(38).fillColor(C.white)
     .text('ChurchGeniusPro', 0, PAGE_H * 0.52, { width: PAGE_W, align: 'center' })
     .restore();

  // Subtitle
  doc.save().font('Helvetica').fontSize(16).fillColor('#c5cae9')
     .text('Complete User Manual', 0, PAGE_H * 0.59, { width: PAGE_W, align: 'center' })
     .restore();

  // Divider
  const dw = PAGE_W * 0.4;
  doc.save().strokeColor(C.gold).lineWidth(1.5)
     .moveTo((PAGE_W - dw) / 2, PAGE_H * 0.64)
     .lineTo((PAGE_W + dw) / 2, PAGE_H * 0.64).stroke().restore();

  // Tagline
  doc.save().font('Helvetica').fontSize(11).fillColor('#9fa8da')
     .text('Church Management \u00b7 Financial Tracking \u00b7 Member Engagement',
           0, PAGE_H * 0.67, { width: PAGE_W, align: 'center' })
     .restore();

  // Version
  doc.save().font('Helvetica').fontSize(9).fillColor('#7986cb')
     .text('Version 1.0  \u00b7  2026', 0, PAGE_H * 0.84, { width: PAGE_W, align: 'center' })
     .restore();

  // Bottom strip
  fillRect(0, PAGE_H - 44, PAGE_W, 44, C.indigoDark);
  doc.save().font('Helvetica').fontSize(8).fillColor(C.indigoLight)
     .text('Confidential \u2014 For Authorized Users Only',
           0, PAGE_H - 28, { width: PAGE_W, align: 'center' })
     .restore();
}

// ═══════════════════════════════════════════════════════════════════════════
// TABLE OF CONTENTS
// ═══════════════════════════════════════════════════════════════════════════
function tocPage() {
  newPage();
  sp(10);
  doc.save().font('Helvetica-Bold').fontSize(20).fillColor(C.indigo)
     .text('Table of Contents', M_L, y, { width: BODY_W }).restore();
  y = doc.y + 6;
  hr(C.indigo, 2);
  sp(6);

  const entries = [
    ['1',  'Introduction & Overview',             false],
    ['2',  'User Roles & Permissions',            false],
    ['3',  'Getting Started',                     false],
    ['',   '  3.1  Signing Up',                   true ],
    ['',   '  3.2  Logging In',                   true ],
    ['',   '  3.3  Forgot Password',              true ],
    ['4',  'The Dashboard',                       false],
    ['',   '  4.1  Admin / SuperAdmin Dashboard', true ],
    ['',   '  4.2  Accountant Dashboard',         true ],
    ['',   '  4.3  Customizing the Dashboard',    true ],
    ['5',  'Member & Family Management',          false],
    ['',   '  5.1  Viewing Families',             true ],
    ['',   '  5.2  Adding / Editing a Member',    true ],
    ['',   '  5.3  Membership Requests',          true ],
    ['6',  'Events & Meetings',                   false],
    ['',   '  6.1  Creating Events',              true ],
    ['',   '  6.2  Event Calendar',               true ],
    ['',   '  6.3  Meetings',                     true ],
    ['7',  'Groups & Communications',             false],
    ['',   '  7.1  Groups',                       true ],
    ['',   '  7.2  Sending Email Notifications',  true ],
    ['',   '  7.3  Prayer Requests',              true ],
    ['8',  'Financial Management \u2014 Income',  false],
    ['',   '  8.1  Recording Income',             true ],
    ['',   '  8.2  Recurring (Starred) Transactions', true],
    ['9',  'Financial Management \u2014 Expense', false],
    ['',   '  9.1  Recording Expenses',           true ],
    ['',   '  9.2  Recurring Expenses',           true ],
    ['10', 'Accounting Reports',                  false],
    ['',   '  10.1 Income Report',                true ],
    ['',   '  10.2 Expense Report',               true ],
    ['',   '  10.3 Date Range Transactions',      true ],
    ['',   '  10.4 Year-End Tax Report',          true ],
    ['',   '  10.5 Financial Report',             true ],
    ['11', 'Settings & Administration',           false],
    ['12', 'Reminders',                           false],
    ['13', 'Public Screens',                      false],
    ['14', 'Frequently Asked Questions',          false],
  ];

  entries.forEach(([num, title, isSub]) => {
    ensureSpace(16);
    const font  = isSub ? 'Helvetica' : 'Helvetica-Bold';
    const size  = isSub ? 9  : 10.5;
    const color = isSub ? C.greyMid : C.greyDark;
    const label = num ? `${num}.  ${title}` : title;

    doc.save().font(font).fontSize(size).fillColor(color)
       .text(label, M_L, y, { width: BODY_W - 30, lineGap: 1 }).restore();

    y = doc.y + (isSub ? 3 : 5);
  });
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 1 — INTRODUCTION
// ═══════════════════════════════════════════════════════════════════════════
function ch1() {
  chapterBanner(1, 'Introduction & Overview', 'What is ChurchGeniusPro?');

  text('ChurchGeniusPro is a comprehensive, web-based church management platform designed to ' +
       'help churches of all sizes efficiently manage their congregation, finances, events, and ' +
       'communications \u2014 all in one secure, cloud-hosted application.');
  sp(4);
  text('The platform is accessible from any modern web browser. No software installation is required. ' +
       'Every user sees only the features relevant to their assigned role, keeping the experience focused.');
  sp(6);

  sectionHead('Core Feature Areas');

  const features = [
    ['\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66  Member & Family Management',
     'Maintain a complete directory of members organized by family unit. Track demographics, contact ' +
     'information, roles, and member types.'],
    ['\uD83D\uDCC5  Events & Meetings',
     'Schedule services, special events, and meetings. Send reminders automatically and allow attendees to register online.'],
    ['\uD83D\uDC65  Groups',
     'Organize members into ministry groups, committees, or Bible study classes. Send targeted emails to any group instantly.'],
    ['\uD83D\uDCB0  Financial Management',
     'Record all income (tithes, offerings, donations) and expenses with full audit trail. ' +
     'Define custom funds, purposes, and transaction types.'],
    ['\uD83D\uDCCA  Accounting Reports',
     'Generate income reports, expense reports, transaction histories, year-end tax summaries, ' +
     'and annual financial reports \u2014 all exportable as PDF.'],
    ['\uD83D\uDCE3  Communications',
     'Send email notifications, manage prayer requests, share daily promise verses, and configure automated reminders.'],
    ['\uD83D\uDCFA  Public Screens',
     'Push content to lobby or sanctuary display screens directly from the platform.'],
    ['\u2699\uFE0F  Administration',
     'Manage church logo, email configuration, member types, and all lookup tables through dedicated settings pages.'],
  ];

  features.forEach(([title, desc]) => {
    subHead(title);
    text(desc);
  });

  sp(6);
  infoBox('ChurchGeniusPro runs entirely in your web browser. Recommended browsers: ' +
          'Google Chrome (latest), Microsoft Edge, and Mozilla Firefox.', 'info');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 2 — ROLES
// ═══════════════════════════════════════════════════════════════════════════
function ch2() {
  chapterBanner(2, 'User Roles & Permissions', 'What each user can see and do');

  text('Every user in ChurchGeniusPro is assigned one of five roles. The role determines which ' +
       'menu sections and dashboard panels are visible. Roles are assigned by a SuperAdmin when ' +
       'creating or editing a user account.');
  sp(8);

  const roles = [
    ['SuperAdmin',  C.indigo,      'Full access to every feature. Sees both the Admin dashboard and the Accountant dashboard. Typically assigned to the senior administrator.'],
    ['Admin',       '#1565c0',     'Manages the congregation: members, families, groups, events, and communications. Does not have access to financial records or accounting reports.'],
    ['Accountant',  C.green,       'Full access to all financial features: income, expenses, reports, fund management, and the accountant dashboard. Does not see congregation administration pages.'],
    ['Church',      '#6a1b9a',     'Restricted role that only sees the Admin menu section. Intended for church-branch-level administrators.'],
    ['User',        C.greyMid,     'Basic access. Sees only the General section and Reminders. Suitable for general congregation members.'],
  ];

  roles.forEach(([role, color, desc]) => {
    ensureSpace(50);
    const boxH = 44;
    fillRect(M_L,           y, 90,             boxH, color);
    fillRect(M_L + 90,      y, BODY_W - 90,    boxH, C.greyLight);

    doc.save().font('Helvetica-Bold').fontSize(12).fillColor(C.white)
       .text(role, M_L + 6, y + 14, { width: 80, align: 'center' }).restore();

    doc.save().font('Helvetica').fontSize(9).fillColor(C.greyDark)
       .text(desc, M_L + 98, y + 8, { width: BODY_W - 106, lineGap: 2 }).restore();

    y += boxH + 6;
  });

  sp(10);
  sectionHead('Menu Access by Role');

  const headers2 = ['Menu Section', 'SuperAdmin', 'Admin', 'Accountant', 'Church', 'User'];
  const ww = [150, 72, 72, 80, 72, 58];
  const matrix = [
    ['Admin',              '\u2714','  \u2714','    \u2014','   \u2714','   \u2014'],
    ['Admin Settings',     '\u2714','  \u2714','    \u2014','   \u2014','   \u2014'],
    ['Accounting',         '\u2714','  \u2014','    \u2714','   \u2014','   \u2014'],
    ['Account Settings',   '\u2714','  \u2014','    \u2714','   \u2014','   \u2014'],
    ['Accounting Reports', '\u2714','  \u2014','    \u2714','   \u2014','   \u2014'],
    ['General',            '\u2714','  \u2714','    \u2714','   \u2014','   \u2714'],
    ['Reminders',          '\u2714','  \u2714','    \u2714','   \u2014','   \u2714'],
  ];

  const totalW = ww.reduce((a,b)=>a+b, 0);
  const rowH   = 22;

  ensureSpace(rowH * (matrix.length + 1) + 10);
  sp(4);

  let rx = M_L;
  fillRect(M_L, y, totalW, rowH, C.indigo);
  headers2.forEach((h, i) => {
    doc.save().font('Helvetica-Bold').fontSize(9).fillColor(C.white)
       .text(h, rx + 4, y + 7, { width: ww[i] - 6, align: i === 0 ? 'left' : 'center' }).restore();
    rx += ww[i];
  });
  y += rowH;

  matrix.forEach((row, ri) => {
    const bg = ri % 2 === 0 ? C.white : C.indigoPale;
    fillRect(M_L, y, totalW, rowH, bg);
    rx = M_L;
    row.forEach((cell, ci) => {
      const isCheck = cell.trim() === '\u2714';
      const fc = isCheck ? C.green : C.greyMid;
      const fn = isCheck ? 'Helvetica-Bold' : 'Helvetica';
      doc.save().font(fn).fontSize(10).fillColor(fc)
         .text(cell.trim(), rx + 4, y + 6, { width: ww[ci] - 6, align: ci === 0 ? 'left' : 'center' })
         .restore();
      rx += ww[ci];
    });
    doc.save().strokeColor('#d1d5f0').lineWidth(0.5)
       .rect(M_L, y, totalW, rowH).stroke().restore();
    y += rowH;
  });

  sp(8);
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 3 — GETTING STARTED
// ═══════════════════════════════════════════════════════════════════════════
function ch3() {
  chapterBanner(3, 'Getting Started', 'Sign up, log in, reset your password');

  sectionHead('3.1  Signing Up');
  text('New churches are registered through a multi-step onboarding flow. An existing SuperAdmin ' +
       'can invite users by sharing a unique invitation link.');
  sp(4);
  bullet('Step 1 \u2014 Enter your email: The system sends a 6-digit One-Time Password (OTP) to verify your email address.');
  bullet('Step 2 \u2014 Verify OTP: Enter the code from your email inbox. Codes expire after a few minutes. Request a new code if needed.');
  bullet('Step 3 \u2014 Create account: Set your display name and a strong password to complete registration.');
  sp(6);

  sectionHead('3.2  Logging In');
  text('Navigate to the application URL in your browser. The login page appears automatically.');
  sp(6);
  fieldTable([
    ['Username',          'Text',     'Your registered username or email address.'],
    ['Password',          'Password', 'Your account password. Click the eye (\uD83D\uDC41) icon to show or hide it.'],
    ['Remember Password', 'Checkbox', 'Keeps you logged in across browser sessions on this device.'],
  ]);
  sp(4);
  text('After a successful login you are redirected to the Dashboard (or to the Users page if your role is Church).');
  sp(4);
  infoBox('Sessions expire after 30 minutes of inactivity. You will be redirected to the login page automatically. Save your work frequently.', 'warning');
  sp(8);

  sectionHead('3.3  Forgot Password');
  text('Click the Forgot Password? link on the login page. Enter your registered email address and click ' +
       'Send Reset Link. A password-reset link will be emailed to you. The link expires after a short period. ' +
       'Click it, enter a new password, confirm it, and click Reset Password.');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 4 — DASHBOARD
// ═══════════════════════════════════════════════════════════════════════════
function ch4() {
  chapterBanner(4, 'The Dashboard', 'Your at-a-glance command center');

  text('The Dashboard is the first page you see after logging in. It is divided into sections that ' +
       'vary based on your role. Each section is a card displaying live data from the database.');
  sp(8);

  sectionHead('Sidebar Navigation');
  text('The left sidebar contains the full application menu. Click any menu group to expand or collapse ' +
       'its sub-items. Click \u2630 (top-left) to collapse the sidebar and gain more screen space. ' +
       'The sidebar shows your name and role at the bottom.');
  sp(4);
  infoBox('The church logo (top-left of the sidebar) is clickable. It takes you to the Logo management page where you can upload or change the logo.', 'note');
  sp(8);

  sectionHead('4.1  Admin / SuperAdmin Dashboard');
  text('Users with the Admin, SuperAdmin, or Church role see these dashboard sections:');
  sp(6);

  const adminSec = [
    ['Stats Overview',
     'A greeting banner with today\'s date and four headline stat cards: Total Members, Total Families, Events This Month, and Upcoming Meetings.'],
    ['Church Growth & Calendar',
     'Two side-by-side cards. Left: a line chart of new members added each month for the current year. Right: a mini monthly calendar with event dots; navigate months with \u2039 and \u203a arrows.'],
    ['Events & Distribution',
     'Left: the next scheduled meetings and services. Right: a Member Distribution bar chart showing counts and percentages for Male, Female, Adults, and Children.'],
  ];
  adminSec.forEach(([title, desc]) => {
    subHead(title);
    text(desc);
  });
  sp(8);

  sectionHead('4.2  Accountant Dashboard');
  text('Users with the Accountant or SuperAdmin role see these financial sections:');
  sp(6);

  const acctSec = [
    ['Financial Greeting',
     'A time-sensitive greeting with today\'s financial context. Visible to the Accountant role only.'],
    ['Net Balance',
     'Cards showing net balance (Income minus Expense) per main income category, plus a summary card with totals.'],
    ['Recurring Income  \uD83D\uDCCC (Pinned)',
     'Always visible. Lists income transactions marked as Recurring. Fill in the Date and Ref No, then click Add to record a new transaction instantly.'],
    ['Recurring Expense  \uD83D\uDCCC (Pinned)',
     'Always visible. Same quick-entry workflow for expense templates.'],
    ['Income & Expense Stats',
     'Two donut charts: left shows income by sub-category for the current year; right shows expenses by purpose for the current year. Each slice displays the amount and percentage.'],
    ['Recent Transactions & Chart',
     'Left: paginated table of the 5 most recent transactions (use \u2039 Prev / Next \u203a). Right: bar chart of monthly income vs. expense \u2014 hover over a bar to see the exact amount.'],
  ];
  acctSec.forEach(([title, desc]) => {
    subHead(title);
    text(desc);
  });
  sp(8);

  sectionHead('4.3  Customizing the Dashboard');
  text('Click the \u2699 Customize button (top-right of the dashboard) to open the Customize panel.');
  sp(4);
  bullet('Toggle sections on or off with the eye icon.');
  bullet('Drag sections up or down to change their order.');
  bullet('Sections marked with \uD83D\uDCCC are pinned and cannot be hidden.');
  bullet('Click \u21ba Reset to Default to restore the original layout.');
  sp(4);
  infoBox('Dashboard preferences are saved automatically and synced to the server, so your layout persists after logging out or switching devices.', 'tip');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 5 — MEMBERS
// ═══════════════════════════════════════════════════════════════════════════
function ch5() {
  chapterBanner(5, 'Member & Family Management', 'Building and maintaining your congregation directory');

  text('ChurchGeniusPro organizes the congregation around Family units. Each family has one primary ' +
       'member (Head of Household) and any number of additional members. Individual members can also ' +
       'be viewed and searched independently on the Members page.');
  sp(8);

  sectionHead('5.1  Viewing Families');
  text('Navigate to Admin \u203a Family. The page displays all active families in a sortable table.');
  sp(6);
  simpleTable(
    ['Column', 'Description'],
    [
      ['Photo',   'Thumbnail photo of the primary member (if uploaded).'],
      ['Primary', 'Full name of the head-of-household member. Click the \u2191\u2193 arrow in the column header to sort alphabetically.'],
      ['Members', 'Total count of members in the family unit.'],
      ['Status',  'Active / Inactive badge.'],
      ['Actions', 'Edit, View Members, and Delete buttons.'],
    ],
    [120, BODY_W - 120]
  );
  text('Click a family row to expand a detail panel listing all members with phone, email, member type, and role.');
  sp(8);

  sectionHead('5.2  Adding / Editing a Member');
  text('Click Add Family or the Edit button on an existing family to open the form.');
  sp(6);
  fieldTable([
    ['First Name',    'Text',   'Required. Member\'s first/given name.'],
    ['Last Name',     'Text',   'Required. Family surname.'],
    ['Middle Name',   'Text',   'Optional middle name.'],
    ['Gender',        'Select', 'Male or Female.'],
    ['Role',          'Select', 'Head of Household, Spouse, Child, Son, Daughter, etc.'],
    ['Member Type',   'Select', 'Classification defined in Admin Settings \u203a Member Type.'],
    ['Phone',         'Text',   'Primary contact phone number.'],
    ['Email',         'Text',   'Email address.'],
    ['Address',       'Text',   'Street address, city, state, ZIP, country.'],
    ['Birthday',      'Date',   'Day, month, and optional year.'],
    ['Anniversary',   'Date',   'Wedding anniversary day and month.'],
    ['Photo',         'Upload', 'Profile photo (JPEG/PNG).'],
    ['Inactive',      'Toggle', 'Mark as inactive without deleting.'],
    ['Include in Contributions', 'Toggle', 'Controls whether the member appears in contribution reports.'],
  ]);
  sp(8);

  sectionHead('5.3  Membership Requests');
  text('If your church uses the public-facing Membership Form, prospective members can submit their ' +
       'information online. Navigate to Admin \u203a Membership Requests to review pending applications. ' +
       'Click Approve (which creates a member record automatically) or Reject each request.');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 6 — EVENTS
// ═══════════════════════════════════════════════════════════════════════════
function ch6() {
  chapterBanner(6, 'Events & Meetings', 'Scheduling, calendars, and attendance');

  sectionHead('6.1  Creating Events');
  text('Navigate to General \u203a Event. Events are church activities open to the congregation ' +
       '(concerts, outreaches, baptisms, etc.).');
  sp(6);
  fieldTable([
    ['Event Title',    'Text',   'Required. Descriptive name of the event.'],
    ['Category',       'Select', 'Meeting/event type (configured in Admin Settings \u203a Category).'],
    ['Date',           'Date',   'The date the event takes place.'],
    ['Start / End Time','Time',  'Optional start and end times (24-hour format).'],
    ['Location / City','Text',   'Venue or city where the event is held.'],
    ['Description',    'Text',   'Optional additional details or notes.'],
  ]);
  infoBox('Saved events automatically appear on the Event Calendar and in the Upcoming Events list on the Admin dashboard.', 'tip');
  sp(8);

  sectionHead('6.2  Event Calendar');
  text('Navigate to General \u203a Event Calendar for a full-page calendar view of all scheduled events. ' +
       'Days with events display a colored dot. Click a day to see that date\'s events. A mini version ' +
       'of this calendar also appears on the Admin dashboard.');
  sp(8);

  sectionHead('6.3  Meetings');
  text('Navigate to General \u203a Meetings for recurring services and structured meetings (Sunday Service, ' +
       'Bible Study, Prayer Meeting, etc.).');
  sp(6);
  fieldTable([
    ['Title',        'Text',   'Name of the meeting.'],
    ['Meeting Type', 'Select', 'Preset type defined in Admin Settings \u203a Category.'],
    ['Date',         'Date',   'Meeting date.'],
    ['Time',         'Time',   'Start time of the meeting.'],
    ['End Time',     'Time',   'Optional end time.'],
    ['Location',     'Text',   'Venue or room.'],
    ['Notes',        'Text',   'Optional meeting notes or agenda summary.'],
  ]);
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 7 — GROUPS
// ═══════════════════════════════════════════════════════════════════════════
function ch7() {
  chapterBanner(7, 'Groups & Communications', 'Ministry groups, email, and prayer');

  sectionHead('7.1  Groups');
  text('Navigate to Admin \u203a Groups to create and manage ministry groups (e.g., Choir, Youth ' +
       'Ministry, Deacons, Ushers). Each group has a name, description, and a list of members.');
  sp(4);
  bullet('Click Add Group to create a new group.');
  bullet('Use the Add Members button to search and add congregation members to a group.');
  bullet('Click Remove next to a member to remove them from the group.');
  sp(8);

  sectionHead('7.2  Sending Email Notifications');
  text('Navigate to General \u203a Email to compose and send emails to selected groups or individuals. ' +
       'Before sending, ensure your SMTP settings are configured in Admin Settings \u203a Email Settings.');
  sp(6);
  fieldTable([
    ['Recipients',   'Select',   'Choose one or more groups, or select individual members.'],
    ['Subject',      'Text',     'Email subject line.'],
    ['Message',      'Textarea', 'Body of the email.'],
    ['Attachments',  'Upload',   'Optional file attachments.'],
  ]);
  infoBox('Emails are sent via the SMTP server configured in Email Settings. Test your settings before sending bulk communications.', 'warning');
  sp(8);

  sectionHead('7.3  Prayer Requests');
  text('Navigate to General \u203a Prayer Requests. Members can submit prayer requests grouped into ' +
       'Sections (e.g., Personal, Family, Healing). Administrators can send a notification email to ' +
       'the prayer team when a new request is received.');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 8 — INCOME
// ═══════════════════════════════════════════════════════════════════════════
function ch8() {
  chapterBanner(8, 'Financial Management \u2014 Income', 'Recording tithes, offerings, and donations');

  sectionHead('8.1  Recording Income');
  text('Navigate to Accounting \u203a Income. This page is the primary entry point for all money received ' +
       'by the church \u2014 tithes, offerings, special donations, and any other income.');
  sp(6);

  subHead('Understanding Funds (Main Category / Sub-Source)');
  text('Every income transaction is assigned to a Fund with two levels: a Main Category ' +
       '(e.g., "Tithes & Offerings") and a Sub-Source (e.g., "Sunday Morning Tithe"). ' +
       'The Sub-Source is selected in the form; the Main Category is shown automatically. ' +
       'Configure funds in Account Settings \u203a Fund.');
  sp(8);

  subHead('Income Form Fields');
  fieldTable([
    ['Contributor', 'Search',   'Type a member name to search. Leave blank for anonymous / general.'],
    ['Fund',        'Select',   'Required. Choose the Sub-Source. Displayed as "Main / Sub".'],
    ['Date',        'Date',     'Required. Defaults to today\'s date.'],
    ['Method',      'Select',   'Required. Payment method (Cash, Cheque, Online, etc.).'],
    ['Ref No',      'Text',     'Optional cheque or reference number (max 6 characters).'],
    ['Notes',       'Text',     'Optional short note (max 15 characters).'],
    ['Amount',      'Number',   'Required. Dollar amount (e.g., 100.00).'],
    ['Recurring',   'Checkbox', 'Mark as a recurring/starred template (see section 8.2).'],
  ]);

  subHead('Saving and Editing');
  text('Click Save Income to record the transaction. It immediately appears in the Recent Transactions table. ' +
       'To edit a record, click Edit in the table \u2014 the form re-opens pre-filled. Click \u2715 Clear ' +
       'to reset the form without saving. Click Load More \u2193 to load additional rows in batches of 10.');
  sp(4);
  infoBox('When you select a Contributor who has a previous transaction, the Fund, Method, Ref No, Amount, ' +
          'and Note fields auto-fill from their most recent record. Adjust as needed before saving.', 'tip');
  sp(8);

  sectionHead('8.2  Recurring (Starred) Transactions');
  text('The Recurring checkbox marks a transaction as a template. Recurring transactions appear in the ' +
       'Recurring Income panel on the Accountant/SuperAdmin dashboard, enabling fast re-entry.');
  sp(6);

  subHead('Using Recurring Income from the Dashboard');
  bullet('Step 1: Open the Dashboard and scroll to the Recurring Income panel.');
  bullet('Step 2: Each row shows a starred template with its fund, method, amount, and note.');
  bullet('Step 3: Verify or update the Date and Ref No fields for this entry.');
  bullet('Step 4: The Amount and Note fields are pre-filled from the template \u2014 edit if needed.');
  bullet('Step 5: Click \u2714 Add to record the transaction instantly.');
  bullet('Step 6: A success message confirms the entry. Fields reset to template defaults, ready for the next.');
  sp(4);
  infoBox('To remove a transaction from the Recurring panel, go to the Income page, click Edit, uncheck the Recurring checkbox, and save.', 'note');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 9 — EXPENSE
// ═══════════════════════════════════════════════════════════════════════════
function ch9() {
  chapterBanner(9, 'Financial Management \u2014 Expense', 'Recording and tracking church expenditures');

  sectionHead('9.1  Recording Expenses');
  text('Navigate to Accounting \u203a Expense to record all money paid out by the church \u2014 ' +
       'utilities, salaries, building costs, supplies, and any other expenditure.');
  sp(6);

  subHead('Understanding Purposes');
  text('Every expense is assigned a Purpose \u2014 the reason for the spending (e.g., "Electricity Bill", ' +
       '"Pastoral Salary", "Sound Equipment Repair"). Purposes are configured in Account Settings \u203a Purpose. ' +
       'The expense is also linked to a Fund (the account being debited).');
  sp(6);

  subHead('Expense Form Fields');
  fieldTable([
    ['Expense (Purpose)', 'Search', 'Required. Type to search for the expense purpose.'],
    ['Fund',              'Select', 'Required. Main source/fund being debited.'],
    ['Date',              'Date',   'Required. Defaults to today\'s date.'],
    ['Method',            'Select', 'Required. Payment method.'],
    ['Ref No',            'Text',   'Optional reference or cheque number (max 6 characters).'],
    ['Notes',             'Text',   'Optional short note (max 15 characters).'],
    ['Amount',            'Number', 'Required. Dollar amount.'],
    ['Recurring',         'Checkbox','Mark as a recurring/starred expense template.'],
  ]);

  text('Click Save Expense to record. Click Load More \u2193 to load additional rows in the table.');
  sp(8);

  sectionHead('9.2  Recurring Expenses');
  text('Checking the Recurring box marks an expense as a template. These templates appear in the ' +
       'Recurring Expense panel on the dashboard, allowing common periodic expenses (rent, utilities, ' +
       'payroll) to be recorded quickly. The workflow is identical to recurring income (see section 8.2).');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 10 — REPORTS
// ═══════════════════════════════════════════════════════════════════════════
function ch10() {
  chapterBanner(10, 'Accounting Reports', 'Income, expense, and financial summaries');

  text('All accounting reports are accessible under Accounting Reports in the sidebar, available to ' +
       'Accountant and SuperAdmin roles. Every report can be exported to PDF using the Download PDF button.');
  sp(8);

  sectionHead('10.1  Income Report');
  text('Provides a detailed breakdown of income by member and sub-source for any custom date range.');
  sp(4);
  subHead('Filters');
  bullet('Start Date / End Date \u2014 or choose a quick period (Last 1\u20135 months).');
  bullet('Member \u2014 filter to one or more specific contributors.');
  bullet('Sub-Category \u2014 filter to one or more income sub-sources.');
  sp(4);
  subHead('Report Structure');
  bullet('Members are listed as group headers (blue background).');
  bullet('Each sub-source contributed to is shown as an indented row with the amount.');
  bullet('A Member Total row appears when a member contributed to 2 or more sub-sources.');
  bullet('A Sub-Category Totals section sums each source across all members.');
  bullet('A Grand Total row appears at the bottom.');
  sp(8);

  sectionHead('10.2  Expense Report');
  text('Shows all expenses for a selected date range, grouped by Main Category with each individual ' +
       'expense entry (Purpose / Date / Amount / Method / Ref #) listed beneath.');
  sp(4);
  bullet('Optional Purpose filter \u2014 limit the report to a specific expense purpose.');
  bullet('Category Total rows summarize spending per fund.');
  bullet('A Grand Total appears at the bottom.');
  sp(8);

  sectionHead('10.3  Date Range Transactions');
  text('A combined ledger showing both income and expense transactions for a selected date range, ' +
       'sorted chronologically. Useful for bank reconciliation and auditing. ' +
       'Columns: Date, Type (Income/Expense), Description, Method, Ref #, Amount.');
  sp(8);

  sectionHead('10.4  Year-End Tax Report');
  text('Generates an annual giving summary per contributor, formatted for tax receipt purposes. ' +
       'Select the year and optionally filter by member. The report lists each contributor\'s ' +
       'total donations broken down by fund sub-source. Share this with contributors for their annual tax filing.');
  sp(8);

  sectionHead('10.5  Financial Report');
  text('An annual summary report covering the full financial year in two sections:');
  sp(4);
  bullet('Income Section \u2014 organized by Main Category. Each category shows its Sub-Sources with individual totals and a Category Total row. A Grand Total Income row closes the section.');
  bullet('Expense Section \u2014 organized by Main Category. Each category shows its Purposes with individual totals and a Category Total row. A Grand Total Expenses row closes the section.');
  bullet('Net Balance Box \u2014 displays Income minus Expenses in large type, color-coded green (surplus) or red (deficit).');
  sp(4);
  infoBox('The Financial Report PDF is suitable for presenting to the church board or congregation at an Annual General Meeting.', 'tip');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 11 — SETTINGS
// ═══════════════════════════════════════════════════════════════════════════
function ch11() {
  chapterBanner(11, 'Settings & Administration', 'Configuring funds, purposes, and church preferences');

  const sections = [
    ['11.1  Fund Management', 'Account Settings \u203a Fund',
     'Funds represent the income accounts of the church. Each fund has a Main Source ' +
     '(e.g., "Tithes & Offerings") and one or more Sub-Sources (e.g., "Sunday Tithe", ' +
     '"Wednesday Offering"). Sub-Sources appear in the Income form\'s Fund dropdown. ' +
     'Add, edit, or deactivate funds here.'],

    ['11.2  Purpose Management', 'Account Settings \u203a Purpose',
     'Purposes describe what expenses are for (e.g., "Staff Salaries", "Building Maintenance", ' +
     '"Office Supplies"). They appear in the Expense form and in expense reports. Add new ' +
     'purposes as your church\'s spending categories grow.'],

    ['11.3  Transaction Types', 'Account Settings \u203a Transaction Type',
     'Transaction types define payment methods (Cash, Cheque, Bank Transfer, Online). ' +
     'These appear in the Method dropdown on both the Income and Expense forms.'],

    ['11.4  Member Types', 'Admin Settings \u203a Member Type',
     'Member types classify congregation members (Regular Member, Associate Member, Visitor). ' +
     'Used for filtering and reporting across the system.'],

    ['11.5  Church Logo', 'Admin Settings \u203a Logo',
     'Upload your church logo (PNG or JPG, max 5 MB). The logo appears in the top-left of the ' +
     'sidebar and in PDF report headers. Click the logo in the sidebar at any time to return to ' +
     'this page. You can delete the current logo and upload a new one.'],

    ['11.6  Email Settings', 'Admin Settings \u203a Email Settings',
     'Configure the outgoing email (SMTP) server used for notifications and reminders. ' +
     'Required fields: SMTP Host, Port, Username, Password, and From Address. ' +
     'Click Send Test Email after saving to verify the configuration is working.'],

    ['11.7  Files & Notes', 'Admin \u203a Files & Notes',
     'Upload documents, spreadsheets, images, or any file for central storage and access. ' +
     'The Notes sub-section provides a simple text area for storing church policies or ' +
     'announcements accessible to administrators.'],
  ];

  sections.forEach(([title, location, desc]) => {
    sectionHead(title);
    doc.save().font('Helvetica-Oblique').fontSize(9).fillColor(C.greyMid)
       .text('Location: ' + location, M_L, y, { width: BODY_W }).restore();
    y = doc.y + 6;
    text(desc);
    sp(4);
  });
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 12 — REMINDERS
// ═══════════════════════════════════════════════════════════════════════════
function ch12() {
  chapterBanner(12, 'Reminders', 'Automated and scheduled notifications');

  text('ChurchGeniusPro can automatically send email reminders to congregation members. ' +
       'The Reminders menu contains four types:');
  sp(8);

  const reminders = [
    ['Event Reminders', 'Reminders \u203a Event Reminders',
     'Send a reminder email to registered attendees or all members a set number of days before ' +
     'a specific event. Configure the event, days-before count, and recipient group.'],

    ['Meeting Reminders', 'Reminders \u203a Meeting Reminders',
     'Similar to event reminders but linked to scheduled meetings. Useful for weekly service ' +
     'reminders sent Friday or Saturday.'],

    ['Auto Reminders', 'Reminders \u203a Auto Reminders',
     'Rule-based reminders triggered automatically by the system \u2014 for example, birthday ' +
     'greetings, anniversary wishes, or membership renewal notices. Define the trigger type, ' +
     'timing, and email template.'],

    ['One-Time Reminders', 'Reminders \u203a One-time Reminders',
     'Schedule a one-off email to be sent at a specific future date and time. Useful for special ' +
     'announcements or seasonal messages.'],
  ];

  reminders.forEach(([title, location, desc]) => {
    subHead(title);
    doc.save().font('Helvetica-Oblique').fontSize(9).fillColor(C.greyMid)
       .text('Location: ' + location, M_L, y, { width: BODY_W }).restore();
    y = doc.y + 4;
    text(desc);
    sp(6);
  });

  sp(4);
  infoBox('All reminders require a working email configuration in Admin Settings \u203a Email Settings. ' +
          'Test your SMTP settings before scheduling reminders.', 'warning');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 13 — PUBLIC SCREENS
// ═══════════════════════════════════════════════════════════════════════════
function ch13() {
  chapterBanner(13, 'Public Screens', 'Lobby and sanctuary display management');

  text('Navigate to General \u203a Public Screens to manage content displayed on lobby TV screens ' +
       'or sanctuary projectors connected to the system.');
  sp(6);
  text('You can configure which pages or content blocks cycle on each screen \u2014 for example, ' +
       'displaying upcoming events, the daily promise verse, or a welcome message. Each screen ' +
       'configuration has a name and a list of content pages in rotation order.');
  sp(6);
  infoBox('The display device must be pointed to the public screen URL provided in the application. ' +
          'Screens refresh automatically at the configured interval.', 'info');
}

// ═══════════════════════════════════════════════════════════════════════════
// CHAPTER 14 — FAQ
// ═══════════════════════════════════════════════════════════════════════════
function ch14() {
  chapterBanner(14, 'Frequently Asked Questions', 'Quick answers to common questions', C.green);

  const faqs = [
    ['I cannot log in. What should I do?',
     'Check that Caps Lock is off. If you have forgotten your password, use the Forgot Password? link on the login page. If the issue persists, contact your SuperAdmin to reset your account.'],
    ['How do I change my password?',
     'Use the Forgot Password flow from the login page. Enter your registered email and follow the link in the reset email.'],
    ['Why can\'t I see the Accounting menu?',
     'The Accounting section is only visible to users with the Accountant or SuperAdmin role. Ask your SuperAdmin to adjust your role if needed.'],
    ['How do I undo a deleted transaction?',
     'Deleted records are soft-deleted and not permanently removed immediately. Contact your system administrator to restore a recently deleted record.'],
    ['Can I import members from a spreadsheet?',
     'Bulk member import is available via database-level CSV import. Contact your system administrator to perform a bulk import using the psql \\copy command.'],
    ['How do I add a new fund sub-source?',
     'Go to Account Settings \u203a Fund. Expand the Main Source you want to add under, then click Add Sub-Source and provide a name.'],
    ['Why is a member not appearing in the Income Contributor dropdown?',
     'The member may be marked as Inactive, or the Include in Contributions flag may be unchecked on their profile. Edit the member in Admin \u203a Family and correct the flags.'],
    ['How do I export a report to PDF?',
     'On any report page, click the Download PDF button (green). The PDF is generated in your browser and downloaded automatically.'],
    ['The dashboard sections are in the wrong order. How do I fix it?',
     'Click the \u2699 Customize button on the dashboard, drag sections to the desired order, then close the panel. Changes save automatically.'],
    ['How do I remove a recurring transaction from the dashboard?',
     'Go to the Income (or Expense) page, find the transaction in the Recent Transactions table, click Edit, uncheck the Recurring checkbox, and click Save.'],
  ];

  faqs.forEach(([q, a]) => {
    ensureSpace(50);
    subHead('Q: ' + q);
    text('A: ' + a);
    sp(4);
  });
}

// ═══════════════════════════════════════════════════════════════════════════
// ASSEMBLE
// ═══════════════════════════════════════════════════════════════════════════
coverPage();
tocPage();
ch1();
ch2();
ch3();
ch4();
ch5();
ch6();
ch7();
ch8();
ch9();
ch10();
ch11();
ch12();
ch13();
ch14();

// ── Add footers to all non-cover pages ────────────────────────────────────
const range = doc.bufferedPageRange();
for (let i = 1; i < range.count; i++) {
  doc.switchToPage(range.start + i);
  drawFooter(i);
}

doc.end();
console.log('\u2705  Manual written to:', OUTPUT);
