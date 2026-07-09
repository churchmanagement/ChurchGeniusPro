"""
ChurchGeniusPro User Manual Generator
Produces a professional PDF user manual using ReportLab.
"""

from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_RIGHT, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, HRFlowable, KeepTogether
)
from reportlab.platypus.flowables import Flowable
from reportlab.lib.colors import HexColor
import os

# ── Brand colours ────────────────────────────────────────────────────────────
INDIGO      = HexColor('#3f4568')
INDIGO_LIGHT= HexColor('#5c6bc0')
INDIGO_PALE = HexColor('#e8eaf6')
GREEN       = HexColor('#2e7d32')
GREEN_LIGHT = HexColor('#e8f5e9')
RED         = HexColor('#c62828')
RED_LIGHT   = HexColor('#ffebee')
GOLD        = HexColor('#f9a825')
GREY_DARK   = HexColor('#555555')
GREY_MID    = HexColor('#888888')
GREY_LIGHT  = HexColor('#f5f6fa')
WHITE       = colors.white
BLACK       = colors.black

# ── Output path ──────────────────────────────────────────────────────────────
OUTPUT = os.path.join(os.path.dirname(__file__),
                      'src', 'main', 'resources', 'static',
                      'ChurchGeniusPro_User_Manual.pdf')

# ─────────────────────────────────────────────────────────────────────────────
# Styles
# ─────────────────────────────────────────────────────────────────────────────
def build_styles():
    base = getSampleStyleSheet()

    def add(name, **kw):
        base.add(ParagraphStyle(name=name, **kw))

    # Cover
    add('CoverTitle',   fontName='Helvetica-Bold', fontSize=36,
        textColor=WHITE, alignment=TA_CENTER, spaceAfter=10, leading=44)
    add('CoverSub',     fontName='Helvetica', fontSize=16,
        textColor=HexColor('#c5cae9'), alignment=TA_CENTER, spaceAfter=6)
    add('CoverVer',     fontName='Helvetica', fontSize=11,
        textColor=HexColor('#9fa8da'), alignment=TA_CENTER, spaceAfter=0)

    # TOC
    add('TocTitle',     fontName='Helvetica-Bold', fontSize=20,
        textColor=INDIGO, spaceAfter=18, spaceBefore=10)
    add('TocEntry',     fontName='Helvetica', fontSize=11,
        textColor=GREY_DARK, spaceAfter=5, leftIndent=0)
    add('TocEntrySub',  fontName='Helvetica', fontSize=10,
        textColor=GREY_MID,  spaceAfter=3, leftIndent=16)

    # Body
    add('ChapterTitle', fontName='Helvetica-Bold', fontSize=22,
        textColor=WHITE, alignment=TA_LEFT, spaceAfter=0, spaceBefore=0,
        leading=28)
    add('SectionHead',  fontName='Helvetica-Bold', fontSize=14,
        textColor=INDIGO, spaceAfter=6, spaceBefore=14,
        borderPad=0)
    add('SubHead',      fontName='Helvetica-Bold', fontSize=11,
        textColor=INDIGO_LIGHT, spaceAfter=4, spaceBefore=8)
    add('Body',         fontName='Helvetica', fontSize=10,
        textColor=GREY_DARK, spaceAfter=5, leading=15, alignment=TA_JUSTIFY)
    add('BulletItem',   fontName='Helvetica', fontSize=10,
        textColor=GREY_DARK, spaceAfter=3, leading=14,
        leftIndent=18, bulletIndent=6)
    add('Note',         fontName='Helvetica-Oblique', fontSize=9,
        textColor=GREY_MID, spaceAfter=4, leading=13, leftIndent=14,
        borderPad=4)
    add('Tip',          fontName='Helvetica', fontSize=9,
        textColor=GREEN, spaceAfter=4, leading=13, leftIndent=14)
    add('TableHead',    fontName='Helvetica-Bold', fontSize=9,
        textColor=WHITE, alignment=TA_CENTER)
    add('TableCell',    fontName='Helvetica', fontSize=9,
        textColor=GREY_DARK, alignment=TA_LEFT, leading=12)
    add('TableCellC',   fontName='Helvetica', fontSize=9,
        textColor=GREY_DARK, alignment=TA_CENTER, leading=12)
    add('Caption',      fontName='Helvetica-Oblique', fontSize=8,
        textColor=GREY_MID, alignment=TA_CENTER, spaceAfter=8, spaceBefore=2)
    add('Footer',       fontName='Helvetica', fontSize=8,
        textColor=GREY_MID, alignment=TA_CENTER)

    return base

STYLES = build_styles()
S = STYLES  # shorthand

# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────
def P(text, style='Body'):
    return Paragraph(text, S[style])

def B(text):
    """Bold paragraph."""
    return Paragraph(f'<b>{text}</b>', S['Body'])

def bullet(text, indent=0):
    style = ParagraphStyle('_b', parent=S['BulletItem'],
                           leftIndent=18 + indent*16, bulletIndent=6 + indent*16)
    return Paragraph(f'\u2022  {text}', style)

def sp(h=8):
    return Spacer(1, h)

def hr(color=INDIGO_PALE, thickness=1):
    return HRFlowable(width='100%', thickness=thickness,
                      color=color, spaceAfter=6, spaceBefore=6)

def field_table(rows, col_widths=None):
    """Render a 3-column table: Field | Type | Description."""
    header = [
        P('<b>Field</b>', 'TableHead'),
        P('<b>Type</b>',  'TableHead'),
        P('<b>Description</b>', 'TableHead'),
    ]
    data = [header]
    for r in rows:
        data.append([P(r[0], 'TableCell'),
                     P(r[1], 'TableCellC'),
                     P(r[2], 'TableCell')])
    cw = col_widths or [1.6*inch, 1.1*inch, 4.0*inch]
    t = Table(data, colWidths=cw, repeatRows=1)
    t.setStyle(TableStyle([
        ('BACKGROUND',  (0,0), (-1,0),  INDIGO),
        ('ROWBACKGROUNDS',(0,1),(-1,-1),[WHITE, INDIGO_PALE]),
        ('GRID',        (0,0), (-1,-1), 0.5, HexColor('#d1d5f0')),
        ('TOPPADDING',  (0,0), (-1,-1), 5),
        ('BOTTOMPADDING',(0,0),(-1,-1), 5),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING',(0,0), (-1,-1), 6),
        ('VALIGN',      (0,0), (-1,-1), 'TOP'),
    ]))
    return t

def simple_table(header_row, data_rows, col_widths=None):
    """Generic table."""
    header = [P(f'<b>{h}</b>', 'TableHead') for h in header_row]
    body   = [[P(str(c), 'TableCell') for c in row] for row in data_rows]
    t = Table([header] + body, colWidths=col_widths, repeatRows=1)
    t.setStyle(TableStyle([
        ('BACKGROUND',  (0,0), (-1,0),  INDIGO),
        ('ROWBACKGROUNDS',(0,1),(-1,-1),[WHITE, INDIGO_PALE]),
        ('GRID',        (0,0), (-1,-1), 0.5, HexColor('#d1d5f0')),
        ('TOPPADDING',  (0,0), (-1,-1), 5),
        ('BOTTOMPADDING',(0,0),(-1,-1), 5),
        ('LEFTPADDING', (0,0), (-1,-1), 6),
        ('RIGHTPADDING',(0,0), (-1,-1), 6),
        ('VALIGN',      (0,0), (-1,-1), 'TOP'),
    ]))
    return t

# ─────────────────────────────────────────────────────────────────────────────
# Chapter Banner
# ─────────────────────────────────────────────────────────────────────────────
class ChapterBanner(Flowable):
    def __init__(self, num, title, subtitle='', color=INDIGO):
        Flowable.__init__(self)
        self.num      = num
        self.title    = title
        self.subtitle = subtitle
        self.color    = color
        self.width    = 6.5 * inch
        self.height   = 0.95 * inch

    def draw(self):
        c = self.canv
        # Background bar
        c.setFillColor(self.color)
        c.roundRect(0, 0, self.width, self.height, 6, fill=1, stroke=0)
        # Chapter number badge
        c.setFillColor(HexColor('#ffffff30'))
        c.circle(0.55*inch, self.height/2, 0.36*inch, fill=1, stroke=0)
        c.setFillColor(WHITE)
        c.setFont('Helvetica-Bold', 18)
        c.drawCentredString(0.55*inch, self.height/2 - 6, str(self.num))
        # Title
        c.setFont('Helvetica-Bold', 17)
        c.drawString(1.1*inch, self.height/2 + 2, self.title)
        # Subtitle
        if self.subtitle:
            c.setFont('Helvetica', 9)
            c.setFillColor(HexColor('#c5cae9'))
            c.drawString(1.1*inch, self.height/2 - 13, self.subtitle)

class InfoBox(Flowable):
    """Coloured info / tip / warning box."""
    def __init__(self, text, kind='info', width=6.5*inch):
        Flowable.__init__(self)
        cfg = {
            'info':    (HexColor('#e3f2fd'), HexColor('#1565c0'), 'ℹ  '),
            'tip':     (GREEN_LIGHT,          GREEN,               '✔  '),
            'warning': (HexColor('#fff8e1'),  GOLD,               '⚠  '),
            'note':    (INDIGO_PALE,          INDIGO_LIGHT,       '📌 '),
        }
        self.bg, self.border, self.icon = cfg.get(kind, cfg['info'])
        self.text  = text
        self.width = width
        self._para = Paragraph(self.icon + text,
                               ParagraphStyle('_ib', fontName='Helvetica',
                                              fontSize=9, textColor=GREY_DARK,
                                              leading=13, leftIndent=6))
        self._para.wrapOn(None, width - 24, 999)
        self.height = self._para.height + 16

    def draw(self):
        c = self.canv
        c.setFillColor(self.bg)
        c.setStrokeColor(self.border)
        c.setLineWidth(1.2)
        c.roundRect(0, 0, self.width, self.height, 4, fill=1, stroke=1)
        self._para.canv = c
        self._para.drawOn(c, 12, 8)

# ─────────────────────────────────────────────────────────────────────────────
# Page templates (header / footer)
# ─────────────────────────────────────────────────────────────────────────────
_chapter_title = ['']

def on_page(canvas, doc):
    canvas.saveState()
    w, h = letter
    # Footer line
    canvas.setStrokeColor(INDIGO_PALE)
    canvas.setLineWidth(0.5)
    canvas.line(0.75*inch, 0.65*inch, w - 0.75*inch, 0.65*inch)
    # Footer text
    canvas.setFont('Helvetica', 7.5)
    canvas.setFillColor(GREY_MID)
    canvas.drawString(0.75*inch, 0.48*inch, 'ChurchGeniusPro — User Manual')
    canvas.drawRightString(w - 0.75*inch, 0.48*inch, f'Page {doc.page}')
    canvas.restoreState()

def on_cover(canvas, doc):
    """Cover page — no header/footer."""
    pass

# ─────────────────────────────────────────────────────────────────────────────
# Cover Page
# ─────────────────────────────────────────────────────────────────────────────
def cover_page():
    w, h = letter
    story = []

    class Cover(Flowable):
        def __init__(self):
            Flowable.__init__(self)
            self.width  = w - 1.5*inch
            self.height = h - 1.5*inch

        def draw(self):
            c = self.canv
            W, H = self.width, self.height

            # Deep gradient background (simulated with stacked rectangles)
            steps = 40
            for i in range(steps):
                frac = i / steps
                r = int(0x3f + (0x1a - 0x3f)*frac)
                g = int(0x45 + (0x1c - 0x45)*frac)
                b = int(0x68 + (0x3a - 0x68)*frac)
                c.setFillColorRGB(r/255, g/255, b/255)
                c.rect(0, H*(1 - (i+1)/steps), W, H/steps+1, fill=1, stroke=0)

            # Decorative circles
            c.setFillColor(HexColor('#ffffff08'))
            c.circle(W*0.85, H*0.75, 1.8*inch, fill=1, stroke=0)
            c.circle(W*0.1,  H*0.2,  1.2*inch, fill=1, stroke=0)
            c.setFillColor(HexColor('#ffffff05'))
            c.circle(W*0.5,  H*0.9,  2.5*inch, fill=1, stroke=0)

            # Gold accent bar
            c.setFillColor(GOLD)
            c.rect(0.38*inch, H*0.52, 0.06*inch, H*0.28, fill=1, stroke=0)

            # Church icon
            c.setFont('Helvetica-Bold', 52)
            c.setFillColor(WHITE)
            c.drawCentredString(W/2, H*0.64, '⛪')

            # Main title
            c.setFont('Helvetica-Bold', 38)
            c.setFillColor(WHITE)
            c.drawCentredString(W/2, H*0.54, 'ChurchGeniusPro')

            # Subtitle
            c.setFont('Helvetica', 16)
            c.setFillColor(HexColor('#c5cae9'))
            c.drawCentredString(W/2, H*0.47, 'Complete User Manual')

            # Divider
            c.setStrokeColor(GOLD)
            c.setLineWidth(1.5)
            c.line(W*0.3, H*0.43, W*0.7, H*0.43)

            # Description
            c.setFont('Helvetica', 11)
            c.setFillColor(HexColor('#9fa8da'))
            c.drawCentredString(W/2, H*0.38,
                'Church Management · Financial Tracking · Member Engagement')

            # Version / date
            c.setFont('Helvetica', 9)
            c.setFillColor(HexColor('#7986cb'))
            c.drawCentredString(W/2, H*0.12, 'Version 1.0  ·  2026')

            # Bottom branding strip
            c.setFillColor(HexColor('#1a1c3a'))
            c.rect(0, 0, W, 0.55*inch, fill=1, stroke=0)
            c.setFont('Helvetica', 8)
            c.setFillColor(HexColor('#5c6bc0'))
            c.drawCentredString(W/2, 0.18*inch,
                'Confidential — For Authorized Users Only')

    story.append(Cover())
    return story

# ─────────────────────────────────────────────────────────────────────────────
# Table of Contents
# ─────────────────────────────────────────────────────────────────────────────
def toc_page():
    story = [PageBreak()]
    story.append(sp(20))
    story.append(P('<b>Table of Contents</b>', 'TocTitle'))
    story.append(hr(INDIGO, 2))
    story.append(sp(8))

    chapters = [
        ('1', 'Introduction & Overview', '4'),
        ('2', 'User Roles & Permissions', '5'),
        ('3', 'Getting Started', '6'),
        ('',  '  3.1  Signing Up', '6'),
        ('',  '  3.2  Logging In', '6'),
        ('',  '  3.3  Forgot Password', '7'),
        ('4', 'The Dashboard', '7'),
        ('',  '  4.1  Admin / SuperAdmin Dashboard', '8'),
        ('',  '  4.2  Accountant Dashboard', '9'),
        ('',  '  4.3  Customizing the Dashboard', '10'),
        ('5', 'Member & Family Management', '10'),
        ('',  '  5.1  Viewing Families', '11'),
        ('',  '  5.2  Adding / Editing a Member', '11'),
        ('',  '  5.3  Membership Requests', '12'),
        ('6', 'Events & Meetings', '12'),
        ('',  '  6.1  Creating Events', '12'),
        ('',  '  6.2  Event Calendar', '13'),
        ('',  '  6.3  Meetings', '13'),
        ('7', 'Groups & Communications', '14'),
        ('',  '  7.1  Groups', '14'),
        ('',  '  7.2  Sending Email Notifications', '14'),
        ('',  '  7.3  Prayer Requests', '15'),
        ('8', 'Financial Management — Income', '15'),
        ('',  '  8.1  Recording Income', '15'),
        ('',  '  8.2  Recurring (Starred) Transactions', '16'),
        ('9', 'Financial Management — Expense', '17'),
        ('',  '  9.1  Recording Expenses', '17'),
        ('',  '  9.2  Recurring Expenses', '18'),
        ('10','Accounting Reports', '18'),
        ('',  '  10.1 Income Report', '18'),
        ('',  '  10.2 Expense Report', '19'),
        ('',  '  10.3 Date Range Transactions', '19'),
        ('',  '  10.4 Year-End Tax Report', '20'),
        ('',  '  10.5 Financial Report', '20'),
        ('11','Settings & Administration', '21'),
        ('',  '  11.1 Fund Management', '21'),
        ('',  '  11.2 Purpose Management', '21'),
        ('',  '  11.3 Transaction Types', '22'),
        ('',  '  11.4 Member Types', '22'),
        ('',  '  11.5 Church Logo', '22'),
        ('',  '  11.6 Email Settings', '22'),
        ('',  '  11.7 Files & Notes', '23'),
        ('12','Reminders', '23'),
        ('13','Public Screens', '24'),
        ('14','Frequently Asked Questions', '24'),
    ]

    for num, title, page in chapters:
        is_chapter = bool(num)
        style = 'TocEntry' if is_chapter else 'TocEntrySub'
        weight = '<b>' if is_chapter else ''
        weight_end = '</b>' if is_chapter else ''
        prefix = f'{num}.  ' if num else ''
        dots_count = 60 - len(prefix) - len(title)
        dots = '.' * max(dots_count, 3)
        story.append(P(
            f'{weight}{prefix}{title}{weight_end}'
            f'<font color="#bbbbbb"> {dots} </font>'
            f'{weight}{page}{weight_end}',
            style
        ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 1 — Introduction
# ─────────────────────────────────────────────────────────────────────────────
def chapter_intro():
    story = [PageBreak()]
    story.append(ChapterBanner(1, 'Introduction & Overview',
                               'What is ChurchGeniusPro?'))
    story.append(sp(14))

    story.append(P(
        '<b>ChurchGeniusPro</b> is a comprehensive, web-based church management platform '
        'designed to help churches of all sizes efficiently manage their congregation, '
        'finances, events, and communications — all in one secure, cloud-hosted application.'
    ))
    story.append(sp(6))
    story.append(P(
        'The platform is accessible from any modern web browser. No software installation '
        'is required. Every user sees only the features relevant to their assigned role, '
        'keeping the experience focused and clutter-free.'
    ))
    story.append(sp(10))

    story.append(P('<b>Core Feature Areas</b>', 'SectionHead'))
    story.append(hr())

    features = [
        ('👨‍👩‍👧‍👦  Member & Family Management',
         'Maintain a complete directory of members organized by family unit. Track demographics, '
         'contact information, roles, and member types.'),
        ('📅  Events & Meetings',
         'Schedule services, special events, and meetings. Send reminders automatically. '
         'Allow attendees to register for events online.'),
        ('👥  Groups',
         'Organize members into ministry groups, committees, or Bible study classes. '
         'Send targeted emails to any group instantly.'),
        ('💰  Financial Management',
         'Record all income (tithes, offerings, donations) and expenses with full audit trail. '
         'Define custom funds, purposes, and transaction types.'),
        ('📊  Accounting Reports',
         'Generate income reports, expense reports, transaction histories, year-end tax summaries, '
         'and annual financial reports — all exportable as PDF.'),
        ('📣  Communications',
         'Send email notifications, manage prayer requests, share daily promise verses, '
         'and configure automated reminders.'),
        ('📺  Public Screens',
         'Push content to lobby or sanctuary display screens directly from the platform.'),
        ('⚙️  Administration',
         'Manage church logo, email configuration, member types, and all lookup tables '
         'through dedicated settings pages.'),
    ]

    for title, desc in features:
        story.append(KeepTogether([
            P(f'<b>{title}</b>', 'SubHead'),
            P(desc),
            sp(4),
        ]))

    story.append(sp(8))
    story.append(InfoBox(
        'ChurchGeniusPro runs entirely in your web browser. The recommended browsers are '
        'Google Chrome (latest), Microsoft Edge, and Mozilla Firefox.',
        kind='info'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 2 — Roles
# ─────────────────────────────────────────────────────────────────────────────
def chapter_roles():
    story = [PageBreak()]
    story.append(ChapterBanner(2, 'User Roles & Permissions',
                               'What each user can see and do'))
    story.append(sp(14))

    story.append(P(
        'Every user in ChurchGeniusPro is assigned one of five roles. The role determines '
        'which menu sections and dashboard panels are visible. Roles are assigned by a '
        '<b>SuperAdmin</b> when creating or editing a user account.'
    ))
    story.append(sp(10))

    roles = [
        ('SuperAdmin',
         'Full access to every feature. Sees both the Admin dashboard (congregation statistics, '
         'church growth, event calendar) and the Accountant dashboard (financial overview, '
         'recurring transactions, reports). Typically assigned to the senior administrator.'),
        ('Admin',
         'Manages the congregation — members, families, groups, events, meetings, and general '
         'communications. Does <i>not</i> have access to financial records or accounting reports.'),
        ('Accountant',
         'Full access to all financial features: income, expenses, reports, fund management, '
         'and the accountant dashboard. Does not see congregation administration pages.'),
        ('Church',
         'A restricted administrative role that only sees the Admin menu section (Family, '
         'Members, Groups, Users). Intended for church-branch-level administrators.'),
        ('User',
         'Basic access. Sees only the General section (Meetings, Events, Calendar, Email) '
         'and Reminders. Suitable for general church members.'),
    ]

    role_colors = [INDIGO, HexColor('#1565c0'), GREEN, HexColor('#6a1b9a'), GREY_MID]

    for (role, desc), col in zip(roles, role_colors):
        box_data = [[
            Paragraph(f'<b>{role}</b>',
                      ParagraphStyle('_rh', fontName='Helvetica-Bold',
                                     fontSize=11, textColor=WHITE)),
            Paragraph(desc,
                      ParagraphStyle('_rb', fontName='Helvetica',
                                     fontSize=9, textColor=GREY_DARK, leading=13)),
        ]]
        box = Table(box_data, colWidths=[1.2*inch, 5.1*inch])
        box.setStyle(TableStyle([
            ('BACKGROUND',   (0,0), (0,0),  col),
            ('BACKGROUND',   (1,0), (1,0),  GREY_LIGHT),
            ('VALIGN',       (0,0), (-1,-1),'MIDDLE'),
            ('LEFTPADDING',  (0,0), (-1,-1), 10),
            ('RIGHTPADDING', (0,0), (-1,-1), 10),
            ('TOPPADDING',   (0,0), (-1,-1), 10),
            ('BOTTOMPADDING',(0,0), (-1,-1), 10),
            ('ROUNDEDCORNERS', [4]),
        ]))
        story.append(box)
        story.append(sp(6))

    story.append(sp(8))

    # Permissions matrix
    story.append(P('<b>Menu Access by Role</b>', 'SectionHead'))
    story.append(hr())
    story.append(sp(4))

    headers = ['Menu Section', 'SuperAdmin', 'Admin', 'Accountant', 'Church', 'User']
    check = '✔'
    dash  = '—'
    matrix = [
        ['Admin',                check, check, dash,  check, dash],
        ['Admin Settings',       check, check, dash,  dash,  dash],
        ['Accounting',           check, dash,  check, dash,  dash],
        ['Account Settings',     check, dash,  check, dash,  dash],
        ['Accounting Reports',   check, dash,  check, dash,  dash],
        ['General',              check, check, check, dash,  check],
        ['Reminders',            check, check, check, dash,  check],
    ]

    def make_cell(v):
        color = GREEN if v == check else GREY_MID
        return Paragraph(f'<font color="{color.hexval() if hasattr(color,"hexval") else "#888888"}">{v}</font>',
                         ParagraphStyle('_mc', fontName='Helvetica-Bold' if v==check else 'Helvetica',
                                        fontSize=10, alignment=TA_CENTER))

    tdata = [[Paragraph(f'<b>{h}</b>', ParagraphStyle('_mh', fontName='Helvetica-Bold',
                         fontSize=9, textColor=WHITE, alignment=TA_CENTER))
              for h in headers]]
    for row in matrix:
        tdata.append([Paragraph(row[0], ParagraphStyle('_ms', fontName='Helvetica',
                                fontSize=9, textColor=GREY_DARK))] +
                     [make_cell(v) for v in row[1:]])

    mt = Table(tdata, colWidths=[2.0*inch, 0.9*inch, 0.8*inch, 1.0*inch, 0.8*inch, 0.75*inch])
    mt.setStyle(TableStyle([
        ('BACKGROUND',    (0,0), (-1,0),  INDIGO),
        ('ROWBACKGROUNDS',(0,1), (-1,-1), [WHITE, INDIGO_PALE]),
        ('GRID',          (0,0), (-1,-1), 0.5, HexColor('#d1d5f0')),
        ('TOPPADDING',    (0,0), (-1,-1), 6),
        ('BOTTOMPADDING', (0,0), (-1,-1), 6),
        ('LEFTPADDING',   (0,0), (-1,-1), 8),
        ('VALIGN',        (0,0), (-1,-1), 'MIDDLE'),
    ]))
    story.append(mt)

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 3 — Getting Started
# ─────────────────────────────────────────────────────────────────────────────
def chapter_getting_started():
    story = [PageBreak()]
    story.append(ChapterBanner(3, 'Getting Started',
                               'Sign up, log in, reset your password'))
    story.append(sp(14))

    # Sign-up
    story.append(P('3.1  Signing Up', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'New churches are registered through a multi-step onboarding flow. An existing '
        '<b>SuperAdmin</b> can invite users by sharing a unique invitation link.'
    ))
    story.append(sp(4))
    steps = [
        ('Step 1 — Enter your email', 'The system sends a 6-digit One-Time Password (OTP) to verify ownership.'),
        ('Step 2 — Verify OTP', 'Enter the code from your email. Codes expire after a few minutes.'),
        ('Step 3 — Create account', 'Set your display name and a strong password to complete registration.'),
    ]
    for s, d in steps:
        story.append(bullet(f'<b>{s}:</b>  {d}'))
    story.append(sp(8))

    # Login
    story.append(P('3.2  Logging In', 'SectionHead'))
    story.append(hr())
    story.append(P('Navigate to the application URL in your browser. The login page appears automatically.'))
    story.append(sp(6))
    story.append(field_table([
        ('Username',          'Text',     'Your registered username or email address.'),
        ('Password',          'Password', 'Your account password. Click the 👁 icon to reveal it.'),
        ('Remember Password', 'Checkbox', 'Keeps you logged in across browser sessions on this device.'),
    ]))
    story.append(sp(6))
    story.append(P(
        'After a successful login, you are redirected to the <b>Dashboard</b> (or to the '
        'Users page if your role is <i>Church</i>).'
    ))
    story.append(sp(4))
    story.append(InfoBox(
        'Sessions expire after 30 minutes of inactivity. You will be redirected to the '
        'login page automatically. Your unsaved work will be lost, so save frequently.',
        kind='warning'
    ))
    story.append(sp(8))

    # Forgot password
    story.append(P('3.3  Forgot Password', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Click the <b>Forgot Password?</b> link on the login page. Enter your registered '
        'email address and click <b>Send Reset Link</b>. A password-reset link will be '
        'emailed to you. The link expires after a short period. Click it, enter a new '
        'password, confirm it, and click <b>Reset Password</b>.'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 4 — Dashboard
# ─────────────────────────────────────────────────────────────────────────────
def chapter_dashboard():
    story = [PageBreak()]
    story.append(ChapterBanner(4, 'The Dashboard',
                               'Your at-a-glance command center'))
    story.append(sp(14))

    story.append(P(
        'The Dashboard is the first page you see after logging in. It is divided into '
        '<b>sections</b> that vary based on your role. Each section is a card displaying '
        'live data from the database.'
    ))
    story.append(sp(10))

    # Sidebar
    story.append(P('<b>Sidebar Navigation</b>', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'The left sidebar contains the full menu. Click any menu group to expand or collapse '
        'its sub-items. Click ☰ (top-left) to collapse the sidebar and gain more screen space. '
        'The sidebar shows your name and role at the bottom, and a <b>Log Out</b> button at the top.'
    ))
    story.append(sp(6))
    story.append(InfoBox(
        'The church logo (top-left of the sidebar) is clickable. It takes you to the '
        'Logo management page where you can upload or change the logo.',
        kind='note'
    ))
    story.append(sp(10))

    # Admin dashboard
    story.append(P('4.1  Admin / SuperAdmin Dashboard', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Users with the <b>Admin</b>, <b>SuperAdmin</b>, or <b>Church</b> role see the '
        'following sections on their dashboard:'
    ))
    story.append(sp(6))

    admin_sections = [
        ('Stats Overview',
         'A greeting banner with the current date and four headline stat cards: '
         'Total Members, Total Families, Events This Month, and Upcoming Meetings.'),
        ('Church Growth & Calendar',
         'Side-by-side cards. The left card shows a line chart of new members added '
         'each month for the current year. The right card shows a mini monthly calendar '
         'with event dots; navigate months with ‹ and › arrows.'),
        ('Events & Distribution',
         'Left: a list of the next scheduled meetings and services. Right: a Member '
         'Distribution bar chart showing counts and percentages for Male, Female, '
         'Adults, and Children.'),
    ]
    for title, desc in admin_sections:
        story.append(KeepTogether([
            P(f'<b>{title}</b>', 'SubHead'),
            P(desc),
            sp(4),
        ]))

    story.append(sp(8))

    # Accountant dashboard
    story.append(P('4.2  Accountant Dashboard', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Users with the <b>Accountant</b> or <b>SuperAdmin</b> role see the following '
        'financial sections:'
    ))
    story.append(sp(6))

    acct_sections = [
        ('Financial Greeting',
         'A time-sensitive greeting ("Good morning/afternoon/evening") with today\'s '
         'financial context. Visible to Accountant role only.'),
        ('Net Balance',
         'Cards showing the net balance (Income minus Expense) for each main income '
         'category. A summary card shows total income, total expense, and overall net balance.'),
        ('Recurring Income  📌',
         'PINNED — always visible. Lists all income transactions marked as Recurring. '
         'Each row shows the template details. Fill in the Date and Ref No, then click '
         'Add to record a new transaction instantly without navigating to the Income page.'),
        ('Recurring Expense  📌',
         'PINNED — always visible. Same quick-entry workflow as Recurring Income, '
         'but for expense templates.'),
        ('Income & Expense Stats',
         'Two donut charts side-by-side. Left: Income breakdown by sub-category for '
         'the current year (with amounts and percentages). Right: Expense breakdown by '
         'purpose for the current year.'),
        ('Recent Transactions & Chart',
         'Left: a paginated table of the 5 most recent transactions (both income and '
         'expense). Use ‹ Prev / Next › to navigate. Right: a bar chart showing monthly '
         'income vs. expense for the current year — hover over any bar to see the exact amount.'),
    ]
    for title, desc in acct_sections:
        story.append(KeepTogether([
            P(f'<b>{title}</b>', 'SubHead'),
            P(desc),
            sp(4),
        ]))

    story.append(sp(8))

    # Customize
    story.append(P('4.3  Customizing the Dashboard', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Click the <b>⚙ Customize</b> button (top-right of the dashboard) to open the '
        'Customize panel. You can:'
    ))
    story.append(bullet('Toggle individual sections on or off with the eye icon.'))
    story.append(bullet('Drag sections up or down to reorder them.'))
    story.append(bullet('Sections marked with 📌 are <b>pinned</b> and cannot be hidden.'))
    story.append(bullet('Click <b>↺ Reset to Default</b> to restore the original layout.'))
    story.append(sp(4))
    story.append(InfoBox(
        'Dashboard preferences are saved automatically and synced to the server, '
        'so your layout is preserved even after logging out or switching devices.',
        kind='tip'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 5 — Members & Families
# ─────────────────────────────────────────────────────────────────────────────
def chapter_members():
    story = [PageBreak()]
    story.append(ChapterBanner(5, 'Member & Family Management',
                               'Building and maintaining your congregation directory'))
    story.append(sp(14))

    story.append(P(
        'ChurchGeniusPro organizes the congregation around <b>Family units</b>. Each family '
        'has one <i>primary</i> member (the Head of Household) and any number of additional '
        'family members. Individual members can also be viewed and searched independently '
        'on the Members page.'
    ))
    story.append(sp(10))

    story.append(P('5.1  Viewing Families', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>Admin › Family</b> in the sidebar to open the View Family page. '
        'The page displays all active families in a sortable table.'
    ))
    story.append(sp(6))

    story.append(simple_table(
        ['Column', 'Description'],
        [
            ['Photo',   'Thumbnail photo of the primary member (if uploaded).'],
            ['Primary', 'Full name of the primary/head-of-household member. Click the ↑↓ arrow in the column header to sort alphabetically.'],
            ['Members', 'Total count of members in the family.'],
            ['Status',  'Active / Inactive badge.'],
            ['Actions', 'Edit, View Members, and Delete buttons.'],
        ],
        col_widths=[1.2*inch, 5.1*inch]
    ))
    story.append(sp(6))
    story.append(P(
        'Click a family row to expand a detail panel listing all members with their phone, '
        'email, member type, and role.'
    ))
    story.append(sp(10))

    story.append(P('5.2  Adding / Editing a Member', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Click <b>Add Family</b> or the Edit button on an existing family to open the '
        'Family form. Key fields include:'
    ))
    story.append(sp(6))

    story.append(field_table([
        ('First Name',       'Text',   'Required. Member\'s first/given name.'),
        ('Last Name',        'Text',   'Required. Family surname.'),
        ('Middle Name',      'Text',   'Optional middle name.'),
        ('Gender',           'Select', 'Male or Female.'),
        ('Role',             'Select', 'Head of Household, Spouse, Child, Son, Daughter, etc.'),
        ('Member Type',      'Select', 'Classification defined in Admin Settings › Member Type.'),
        ('Phone',            'Text',   'Primary contact phone number.'),
        ('Email',            'Text',   'Email address.'),
        ('Address',          'Text',   'Street address, city, state, ZIP / postal code, country.'),
        ('Birthday',         'Date',   'Day, month, and year (year is optional).'),
        ('Anniversary',      'Date',   'Wedding anniversary day and month.'),
        ('Photo',            'Upload', 'Member profile photo (JPEG/PNG).'),
        ('Inactive',         'Toggle', 'Mark a member as inactive without deleting them.'),
        ('Include in Contributions', 'Toggle', 'Controls whether the member appears in contribution reports.'),
    ]))
    story.append(sp(10))

    story.append(P('5.3  Membership Requests', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'If your church uses the public-facing <b>Membership Form</b>, prospective members '
        'can submit their information online. Navigate to <b>Admin › Membership Requests</b> '
        'to review pending applications. You can <b>Approve</b> (which creates a family '
        'member record automatically) or <b>Reject</b> each request.'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 6 — Events & Meetings
# ─────────────────────────────────────────────────────────────────────────────
def chapter_events():
    story = [PageBreak()]
    story.append(ChapterBanner(6, 'Events & Meetings',
                               'Scheduling, calendars, and attendance'))
    story.append(sp(14))

    story.append(P('6.1  Creating Events', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>General › Event</b>. Events are church activities open to the '
        'congregation (concerts, outreaches, baptisms, etc.).'
    ))
    story.append(sp(6))
    story.append(field_table([
        ('Event Title',   'Text',   'Required. Descriptive name of the event.'),
        ('Category',      'Select', 'Meeting/event type (configured in Admin Settings › Category).'),
        ('Date',          'Date',   'The date the event takes place.'),
        ('Start Time',    'Time',   'Optional start time (24-hour format).'),
        ('End Time',      'Time',   'Optional end time.'),
        ('Location/City', 'Text',   'Venue or city where the event is held.'),
        ('Description',   'Text',   'Optional additional details.'),
    ]))
    story.append(sp(6))
    story.append(InfoBox(
        'Saved events automatically appear on the Event Calendar and in the Upcoming Events '
        'list on the Admin dashboard.',
        kind='tip'
    ))
    story.append(sp(10))

    story.append(P('6.2  Event Calendar', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>General › Event Calendar</b> for a full-page calendar view of all '
        'scheduled events. Days with events display a colored dot. Click a day to see a '
        'list of events for that date. A mini version of this calendar also appears on the '
        'Admin dashboard for quick reference.'
    ))
    story.append(sp(10))

    story.append(P('6.3  Meetings', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>General › Meetings</b> for recurring services and structured '
        'meetings (Sunday Service, Bible Study, Prayer Meeting, etc.).'
    ))
    story.append(sp(6))
    story.append(field_table([
        ('Title',         'Text',   'Name of the meeting.'),
        ('Meeting Type',  'Select', 'Preset type defined in Admin Settings › Category.'),
        ('Date',          'Date',   'Meeting date.'),
        ('Time',          'Time',   'Start time of the meeting.'),
        ('End Time',      'Time',   'Optional end time.'),
        ('Location',      'Text',   'Venue or room.'),
        ('Notes',         'Text',   'Optional meeting notes or agenda summary.'),
    ]))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 7 — Groups & Communications
# ─────────────────────────────────────────────────────────────────────────────
def chapter_groups():
    story = [PageBreak()]
    story.append(ChapterBanner(7, 'Groups & Communications',
                               'Ministry groups, email, and prayer'))
    story.append(sp(14))

    story.append(P('7.1  Groups', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>Admin › Groups</b> to create and manage ministry groups '
        '(e.g., Choir, Youth Ministry, Deacons, Ushers). Each group has a name, '
        'description, and a list of members.'
    ))
    story.append(sp(4))
    story.append(bullet('Click <b>Add Group</b> to create a new group.'))
    story.append(bullet('Use the <b>Add Members</b> button on a group to search and add congregation members.'))
    story.append(bullet('Click <b>Remove</b> next to a member to remove them from the group.'))
    story.append(sp(10))

    story.append(P('7.2  Sending Email Notifications', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>General › Email</b> to compose and send emails to selected '
        'groups or individuals. Before sending, ensure your SMTP settings are configured '
        'in <b>Admin Settings › Email Settings</b>.'
    ))
    story.append(sp(6))
    story.append(field_table([
        ('Recipients',  'Select',   'Choose one or more groups, or select individual members.'),
        ('Subject',     'Text',     'Email subject line.'),
        ('Message',     'Textarea', 'The body of the email (plain text).'),
        ('Attachments', 'Upload',   'Optional file attachments.'),
    ]))
    story.append(sp(6))
    story.append(InfoBox(
        'Emails are sent via the SMTP server configured in Email Settings. '
        'Test your settings before sending bulk communications.',
        kind='warning'
    ))
    story.append(sp(10))

    story.append(P('7.3  Prayer Requests', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>General › Prayer Requests</b> (if enabled for your role). Members '
        'can submit prayer requests which are visible to administrators. Prayer requests '
        'can be grouped into <b>Sections</b> (e.g., Personal, Family, Healing). '
        'Administrators can send a notification email to designated prayer team members '
        'when a new request is received.'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 8 — Income
# ─────────────────────────────────────────────────────────────────────────────
def chapter_income():
    story = [PageBreak()]
    story.append(ChapterBanner(8, 'Financial Management — Income',
                               'Recording tithes, offerings, and donations'))
    story.append(sp(14))

    story.append(P('8.1  Recording Income', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>Accounting › Income</b>. This page is the primary entry point '
        'for all money received by the church — tithes, offerings, special donations, '
        'and any other income.'
    ))
    story.append(sp(8))

    story.append(P('<b>Understanding Funds (Main Category / Sub-Source)</b>', 'SubHead'))
    story.append(P(
        'Every income transaction is assigned to a <b>Fund</b>, which has two levels: '
        'a <b>Main Category</b> (e.g., "Tithes &amp; Offerings") and a <b>Sub-Source</b> '
        '(e.g., "Sunday Morning Tithe"). The Sub-Source is what you select in the form; '
        'the Main Category is shown automatically. Configure funds in '
        '<b>Account Settings › Fund</b>.'
    ))
    story.append(sp(8))

    story.append(P('<b>Income Form Fields</b>', 'SubHead'))
    story.append(field_table([
        ('Contributor',  'Search',   'Type a member name to search. Leave blank for anonymous / general.'),
        ('Fund',         'Select',   'Required. Choose the Sub-Source. Displayed as "Main / Sub".'),
        ('Date',         'Date',     'Required. Defaults to today\'s date.'),
        ('Method',       'Select',   'Required. Payment method (Cash, Cheque, Online, etc.).'),
        ('Ref No',       'Text',     'Optional cheque or reference number (max 6 characters).'),
        ('Notes',        'Text',     'Optional short note (max 15 characters).'),
        ('Amount',       'Number',   'Required. Dollar amount (e.g., 100.00).'),
        ('Recurring',    'Checkbox', 'Mark as a recurring/starred template (see section 8.2).'),
    ]))
    story.append(sp(8))

    story.append(P('<b>Saving and Editing</b>', 'SubHead'))
    story.append(P(
        'Click <b>💾 Save Income</b> to record the transaction. The entry immediately '
        'appears in the <b>Recent Transactions</b> table below the form. '
        'To edit an existing record, click the <b>Edit</b> button in the table — the form '
        're-opens pre-filled with that transaction\'s data. Click <b>✕ Clear</b> at any '
        'time to reset the form without saving.'
    ))
    story.append(sp(6))
    story.append(P(
        'The table initially shows <b>10 rows</b>. Click <b>Load More ↓</b> at the bottom '
        'of the table to load the next 10 records.'
    ))
    story.append(sp(6))
    story.append(InfoBox(
        'When you select a Contributor who has a previous transaction, the Fund, Method, '
        'Ref No, Amount, and Note fields auto-fill from their most recent record. '
        'Adjust as needed before saving.',
        kind='tip'
    ))
    story.append(sp(10))

    story.append(P('8.2  Recurring (Starred) Transactions', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'The <b>🔁 Recurring</b> checkbox marks a transaction as a <i>template</i>. '
        'Recurring transactions appear in the <b>Recurring Income</b> panel on the '
        'Accountant/SuperAdmin dashboard, enabling fast re-entry without navigating '
        'to the Income page.'
    ))
    story.append(sp(6))

    story.append(P('<b>Using Recurring Income from the Dashboard</b>', 'SubHead'))
    steps_rec = [
        'Open the Dashboard and scroll to the <b>Recurring Income</b> panel.',
        'Each row shows a starred income template with its fund, method, amount, and note.',
        'Verify or update the <b>Date</b> and <b>Ref No</b> fields for this entry.',
        'The <b>Amount</b> and <b>Note</b> fields are pre-filled from the template — edit if needed.',
        'Click <b>✔ Add</b> to record the transaction instantly.',
        'A success message confirms the entry. The Date and Ref No reset; Amount and Note restore to template defaults, ready for the next entry.',
    ]
    for i, s in enumerate(steps_rec, 1):
        story.append(bullet(f'<b>Step {i}:</b>  {s}'))

    story.append(sp(6))
    story.append(InfoBox(
        'To remove a transaction from the Recurring panel, go to the Income page, click '
        'Edit on that transaction, uncheck the Recurring checkbox, and save.',
        kind='note'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 9 — Expense
# ─────────────────────────────────────────────────────────────────────────────
def chapter_expense():
    story = [PageBreak()]
    story.append(ChapterBanner(9, 'Financial Management — Expense',
                               'Recording and tracking church expenditures'))
    story.append(sp(14))

    story.append(P('9.1  Recording Expenses', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Navigate to <b>Accounting › Expense</b> to record all money paid out by the church — '
        'utilities, salaries, building costs, supplies, and any other expenditure.'
    ))
    story.append(sp(8))

    story.append(P('<b>Understanding Purposes</b>', 'SubHead'))
    story.append(P(
        'Every expense is assigned a <b>Purpose</b> — the reason for the spending '
        '(e.g., "Electricity Bill", "Pastoral Salary", "Sound Equipment Repair"). '
        'Purposes are configured in <b>Account Settings › Purpose</b>. '
        'The expense is also linked to a <b>Fund</b> (the account being debited).'
    ))
    story.append(sp(8))

    story.append(P('<b>Expense Form Fields</b>', 'SubHead'))
    story.append(field_table([
        ('Expense (Purpose)', 'Search', 'Required. Type to search for the expense purpose.'),
        ('Fund',              'Select', 'Required. Main income source/fund being debited.'),
        ('Date',              'Date',   'Required. Defaults to today\'s date.'),
        ('Method',            'Select', 'Required. Payment method.'),
        ('Ref No',            'Text',   'Optional reference or cheque number (max 6 characters).'),
        ('Notes',             'Text',   'Optional short note (max 15 characters).'),
        ('Amount',            'Number', 'Required. Dollar amount.'),
        ('Recurring',         'Checkbox','Mark as a recurring/starred expense template.'),
    ]))
    story.append(sp(6))
    story.append(P(
        'Click <b>💾 Save Expense</b> to record. The table below shows recent expenses. '
        'Click <b>Load More ↓</b> to load additional records 10 at a time.'
    ))
    story.append(sp(10))

    story.append(P('9.2  Recurring Expenses', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Checking the <b>🔁 Recurring</b> box marks an expense as a template. These '
        'templates appear in the <b>Recurring Expense</b> panel on the dashboard, '
        'allowing common periodic expenses (rent, utilities, payroll) to be recorded '
        'quickly without refilling the entire form each time. The workflow is identical '
        'to recurring income (see section 8.2).'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 10 — Reports
# ─────────────────────────────────────────────────────────────────────────────
def chapter_reports():
    story = [PageBreak()]
    story.append(ChapterBanner(10, 'Accounting Reports',
                               'Income, expense, and financial summaries'))
    story.append(sp(14))
    story.append(P(
        'All accounting reports are accessible under <b>Accounting Reports</b> in the sidebar. '
        'They are available to users with the <b>Accountant</b> or <b>SuperAdmin</b> role. '
        'Every report can be exported to a <b>PDF file</b> using the Download PDF button.'
    ))
    story.append(sp(10))

    # Income Report
    story.append(P('10.1  Income Report', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Provides a detailed breakdown of income by <b>member and sub-source</b> for any '
        'custom date range.'
    ))
    story.append(sp(4))
    story.append(P('<b>Filters:</b>', 'SubHead'))
    story.append(bullet('<b>Start Date / End Date</b> — or choose a quick period (Last 1–5 months).'))
    story.append(bullet('<b>Member</b> — filter to one or more specific contributors.'))
    story.append(bullet('<b>Sub-Category</b> — filter to one or more income sub-sources.'))
    story.append(sp(4))
    story.append(P('<b>Report Structure:</b>', 'SubHead'))
    story.append(bullet('Members are listed as group headers.'))
    story.append(bullet('Each sub-source contributed to is shown as an indented row with the amount.'))
    story.append(bullet('A Member Total row appears when a member contributed to 2 or more sub-sources.'))
    story.append(bullet('A Sub-Category Totals section sums each source across all members.'))
    story.append(bullet('A Grand Total row appears at the bottom.'))
    story.append(sp(10))

    # Expense Report
    story.append(P('10.2  Expense Report', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Shows all expenses for a selected date range, grouped by <b>Main Category</b> '
        'with each individual expense entry (Purpose / Date / Amount / Method / Ref #) listed beneath.'
    ))
    story.append(sp(4))
    story.append(bullet('<b>Optional Purpose filter</b> — limit the report to a specific expense purpose.'))
    story.append(bullet('Category Total rows summarize spending per fund.'))
    story.append(bullet('A Grand Total appears at the bottom.'))
    story.append(sp(10))

    # Transactions
    story.append(P('10.3  Date Range Transactions', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'A combined ledger showing both income and expense transactions for a selected date range, '
        'sorted chronologically. Useful for bank reconciliation and auditing. '
        'Columns: Date, Type (Income/Expense), Description, Method, Ref #, Amount.'
    ))
    story.append(sp(10))

    # Tax Report
    story.append(P('10.4  Year-End Tax Report', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'Generates an annual giving summary per contributor, formatted for tax receipt purposes. '
        'Select the <b>year</b> and optionally filter by member. The report lists each '
        'contributor\'s total donations, broken down by fund sub-source. This is the document '
        'to share with contributors for their annual tax filing.'
    ))
    story.append(sp(10))

    # Financial Report
    story.append(P('10.5  Financial Report', 'SectionHead'))
    story.append(hr())
    story.append(P(
        'An annual summary report covering the full financial year in two sections:'
    ))
    story.append(sp(4))
    story.append(bullet(
        '<b>Income Section</b> — organized by Main Category. Each category shows its '
        'Sub-Sources with individual totals and a Category Total row. A Grand Total '
        'Income row closes the section.'
    ))
    story.append(bullet(
        '<b>Expense Section</b> — organized by Main Category. Each category shows its '
        'Purposes with individual totals and a Category Total row. A Grand Total '
        'Expenses row closes the section.'
    ))
    story.append(bullet(
        '<b>Net Balance Box</b> — displays Income minus Expenses in large type, '
        'color-coded green (surplus) or red (deficit).'
    ))
    story.append(sp(4))
    story.append(InfoBox(
        'The Financial Report PDF is suitable for presenting to the church board or '
        'congregation at an Annual General Meeting.',
        kind='tip'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 11 — Settings
# ─────────────────────────────────────────────────────────────────────────────
def chapter_settings():
    story = [PageBreak()]
    story.append(ChapterBanner(11, 'Settings & Administration',
                               'Configuring funds, purposes, and church preferences'))
    story.append(sp(14))

    settings = [
        ('11.1  Fund Management',
         'Account Settings › Fund',
         'Funds represent the income accounts of the church. Each fund has a '
         '<b>Main Source</b> (e.g., "Tithes &amp; Offerings") and one or more '
         '<b>Sub-Sources</b> (e.g., "Sunday Tithe", "Wednesday Offering"). '
         'Sub-Sources are the options that appear in the Income form\'s Fund dropdown. '
         'Add, edit, or deactivate funds here.'),
        ('11.2  Purpose Management',
         'Account Settings › Purpose',
         'Purposes describe what expenses are for (e.g., "Staff Salaries", '
         '"Building Maintenance", "Office Supplies"). They appear in the Expense '
         'form and in expense reports. Add new purposes as your church\'s spending '
         'categories grow.'),
        ('11.3  Transaction Types',
         'Account Settings › Transaction Type',
         'Transaction types define payment methods (e.g., "Cash", "Cheque", '
         '"Bank Transfer", "Online"). These appear in the Method dropdown on '
         'both the Income and Expense forms.'),
        ('11.4  Member Types',
         'Admin Settings › Member Type',
         'Member types classify congregation members (e.g., "Regular Member", '
         '"Associate Member", "Visitor"). Used for filtering and reporting.'),
        ('11.5  Church Logo',
         'Admin Settings › Logo',
         'Upload your church logo (PNG or JPG, max 5 MB). The logo appears in '
         'the top-left of the sidebar and in PDF report headers. Click the logo '
         'in the sidebar at any time to return to this page. You can also '
         'delete the current logo and upload a new one.'),
        ('11.6  Email Settings',
         'Admin Settings › Email Settings',
         'Configure the outgoing email (SMTP) server used for notifications '
         'and reminders. Required fields: SMTP Host, Port, Username, Password, '
         'and From Address. Click <b>Send Test Email</b> after saving to verify '
         'the configuration is working.'),
        ('11.7  Files &amp; Notes',
         'Admin › Files &amp; Notes',
         'Upload documents, spreadsheets, images, or any file for central storage '
         'and access. The Notes sub-section provides a simple rich-text area for '
         'storing church notes, policies, or announcements accessible to admins.'),
    ]

    for title, location, desc in settings:
        story.append(KeepTogether([
            P(title, 'SectionHead'),
            hr(),
            Paragraph(f'<i>Location: <b>{location}</b></i>',
                      ParagraphStyle('_loc', fontName='Helvetica-Oblique',
                                     fontSize=9, textColor=GREY_MID, spaceAfter=6)),
            P(desc),
            sp(8),
        ]))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 12 — Reminders
# ─────────────────────────────────────────────────────────────────────────────
def chapter_reminders():
    story = [PageBreak()]
    story.append(ChapterBanner(12, 'Reminders',
                               'Automated and scheduled notifications'))
    story.append(sp(14))

    story.append(P(
        'ChurchGeniusPro can automatically send email reminders to congregation members. '
        'The Reminders menu contains four types:'
    ))
    story.append(sp(8))

    reminders = [
        ('Event Reminders',
         'Reminders › Event Reminders',
         'Send a reminder email to registered attendees or all members a set number '
         'of days before a specific event. Configure the event, days-before count, '
         'and recipient group.'),
        ('Meeting Reminders',
         'Reminders › Meeting Reminders',
         'Similar to event reminders but linked to scheduled meetings. '
         'Useful for weekly service reminders sent Friday or Saturday.'),
        ('Auto Reminders',
         'Reminders › Auto Reminders',
         'Rule-based reminders triggered automatically by the system — for example, '
         'birthday greetings, anniversary wishes, or membership renewal notices. '
         'Define the trigger type, timing, and email template.'),
        ('One-Time Reminders',
         'Reminders › One-time Reminders',
         'Schedule a one-off email to be sent at a specific future date and time. '
         'Useful for special announcements or seasonal messages.'),
    ]

    for title, location, desc in reminders:
        story.append(KeepTogether([
            P(f'<b>{title}</b>', 'SubHead'),
            Paragraph(f'<i>Location: {location}</i>',
                      ParagraphStyle('_rl', fontName='Helvetica-Oblique',
                                     fontSize=9, textColor=GREY_MID, spaceAfter=4)),
            P(desc),
            sp(8),
        ]))

    story.append(InfoBox(
        'All reminders require a working email configuration in Admin Settings › Email Settings. '
        'Test your SMTP settings before scheduling reminders.',
        kind='warning'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 13 — Public Screens
# ─────────────────────────────────────────────────────────────────────────────
def chapter_screens():
    story = [PageBreak()]
    story.append(ChapterBanner(13, 'Public Screens',
                               'Lobby and sanctuary display management'))
    story.append(sp(14))

    story.append(P(
        'Navigate to <b>General › Public Screens</b> to manage content displayed on '
        'lobby TV screens or sanctuary projectors connected to the system.'
    ))
    story.append(sp(6))
    story.append(P(
        'You can configure which <b>pages</b> or content blocks cycle on each screen — '
        'for example, displaying upcoming events, the daily promise verse, or a '
        'welcome message. Each screen configuration has a name and a list of content '
        'pages in rotation order.'
    ))
    story.append(sp(6))
    story.append(InfoBox(
        'The display device must be pointed to the public screen URL provided in the '
        'application. Screens refresh automatically at the configured interval.',
        kind='info'
    ))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# Chapter 14 — FAQ
# ─────────────────────────────────────────────────────────────────────────────
def chapter_faq():
    story = [PageBreak()]
    story.append(ChapterBanner(14, 'Frequently Asked Questions',
                               'Quick answers to common questions', GREEN))
    story.append(sp(14))

    faqs = [
        ('I cannot log in. What should I do?',
         'First, check that Caps Lock is off. If you have forgotten your password, use the '
         'Forgot Password? link on the login page. If the issue persists, contact your '
         'SuperAdmin to reset your account.'),
        ('How do I change my password?',
         'Use the Forgot Password flow from the login page. There is no in-app password change '
         'form at this time — email-based reset is the supported method.'),
        ('Why can\'t I see the Accounting menu?',
         'The Accounting section is only visible to users with the Accountant or SuperAdmin '
         'role. Ask your SuperAdmin to adjust your role if needed.'),
        ('How do I undo a deleted transaction?',
         'Deleted income and expense records are soft-deleted (marked with a delete flag) '
         'and not permanently removed immediately. Contact your system administrator to '
         'restore a recently deleted record via the database.'),
        ('Can I import members from a spreadsheet?',
         'Bulk member import is available via database-level CSV import. Contact your '
         'system administrator to perform a bulk import using the psql \\copy command.'),
        ('How do I add a new fund sub-source?',
         'Go to Account Settings › Fund. Expand the Main Source you want to add under, '
         'then click Add Sub-Source and provide a name.'),
        ('Why is a member not appearing in the Income Contributor dropdown?',
         'The member may be marked as Inactive, or the Include in Contributions flag may '
         'be unchecked on their profile. Check Admin › Family, edit the member, and '
         'ensure both flags are correctly set.'),
        ('How do I export a report to PDF?',
         'On any report page, click the Download PDF button (green). The PDF is generated '
         'in your browser and downloaded automatically to your computer.'),
        ('The dashboard sections are in the wrong order. How do I fix it?',
         'Click the ⚙ Customize button on the dashboard, drag sections to the desired '
         'order, then close the panel. Changes save automatically.'),
        ('How do I remove a recurring transaction from the dashboard?',
         'Go to the Income (or Expense) page, find the transaction in the Recent '
         'Transactions table, click Edit, uncheck the Recurring checkbox, and click Save.'),
    ]

    for q, a in faqs:
        story.append(KeepTogether([
            P(f'<b>Q: {q}</b>', 'SubHead'),
            P(f'A: {a}'),
            sp(8),
        ]))

    return story

# ─────────────────────────────────────────────────────────────────────────────
# BUILD
# ─────────────────────────────────────────────────────────────────────────────
def build_pdf():
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)

    doc = SimpleDocTemplate(
        OUTPUT,
        pagesize=letter,
        leftMargin=0.75*inch,
        rightMargin=0.75*inch,
        topMargin=0.75*inch,
        bottomMargin=0.85*inch,
        title='ChurchGeniusPro User Manual',
        author='ChurchGeniusPro',
        subject='Complete User Manual',
    )

    story = []
    story += cover_page()
    story += toc_page()
    story += chapter_intro()
    story += chapter_roles()
    story += chapter_getting_started()
    story += chapter_dashboard()
    story += chapter_members()
    story += chapter_events()
    story += chapter_groups()
    story += chapter_income()
    story += chapter_expense()
    story += chapter_reports()
    story += chapter_settings()
    story += chapter_reminders()
    story += chapter_screens()
    story += chapter_faq()

    doc.build(
        story,
        onFirstPage=on_cover,
        onLaterPages=on_page,
    )
    print(f'✅  Manual written to: {OUTPUT}')

if __name__ == '__main__':
    build_pdf()
