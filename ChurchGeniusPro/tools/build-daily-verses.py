"""Builds daily-verses.json: 366 devotional verses, KJV (public domain).

Text comes verbatim from the bible-kjv dataset (MIT-packaged KJV), never from
memory. This script only CHOOSES references and strips the dataset's inline
markup; it never edits a word of the text.
"""
import json, os, re, sys

SRC = '/tmp/bible/package/dist'
books = json.load(open(os.path.join(SRC, 'content/books.json')))
NAME_TO_IDX = {b['name']: i + 1 for i, b in enumerate(books)}

def chapter(book, ch):
    idx = NAME_TO_IDX[book]
    with open(os.path.join(SRC, 'resources', str(idx), f'{ch}.json')) as f:
        return json.load(f)

def clean(raw):
    s = raw
    s = re.sub(r'<RF>.*?<Rf>', '', s, flags=re.S)   # footnotes
    s = re.sub(r'<FI>|<Fi>', '', s)                 # italics (supplied words)
    s = re.sub(r'<FR>|<Fr>', '', s)                 # red letter
    s = re.sub(r'<[^>]*>', '', s)                   # anything else
    s = s.replace('¶', ' ')                    # pilcrow
    return re.sub(r'\s+', ' ', s).strip()

# A KJV psalm's editorial heading ("A Psalm of David.", "To the chief Musician…")
# and Psalm 119's acrostic letters ("NUN.") sit inside verse 1. They are titles
# placed before the verse's own sentence, not words of it, so they are dropped
# for display. Nothing inside the sentence is ever touched.
PSALM_TITLE = re.compile(
    r'^(?:(?:To the chief Musician[^.]*|A Psalm[^.]*|A Song[^.]*|Maschil[^.]*|'
    r'Michtam[^.]*|A Prayer[^.]*|[A-Z]{2,8})\.\s+)+')

def verse(book, ch, v):
    t = clean(chapter(book, ch)[v - 1])
    if book == 'Psalms':
        t = PSALM_TITLE.sub('', t)
    return t

# ── Curated favourites, in a deliberate order ────────────────────────────────
CURATED = [
    ('John',3,16),('Jeremiah',29,11),('Philippians',4,13),('Psalms',23,1),
    ('Proverbs',3,5),('Proverbs',3,6),('Isaiah',41,10),('Romans',8,28),
    ('Joshua',1,9),('Psalms',46,1),('Matthew',11,28),('Isaiah',40,31),
    ('Philippians',4,6),('Philippians',4,7),('2 Corinthians',5,17),('Galatians',2,20),
    ('Ephesians',2,8),('Ephesians',2,9),('Hebrews',11,1),('James',1,2),
    ('James',1,3),('1 Peter',5,7),('1 John',1,9),('Revelation',21,4),
    ('Psalms',119,105),('Psalms',37,4),('Psalms',27,1),('Psalms',34,8),
    ('Matthew',6,33),('Matthew',5,16),('Luke',6,31),('John',14,6),
    ('John',15,5),('Acts',1,8),('Romans',12,2),('Romans',5,8),
    ('1 Corinthians',13,4),('1 Corinthians',13,13),('2 Timothy',1,7),('Hebrews',13,8),
    ('Psalms',91,1),('Psalms',91,2),('Deuteronomy',31,6),('Isaiah',26,3),
    ('Lamentations',3,22),('Lamentations',3,23),('Micah',6,8),('Zephaniah',3,17),
    ('Matthew',28,19),('Matthew',28,20),('Mark',10,27),('Luke',1,37),
    ('John',1,1),('John',8,32),('John',16,33),('Romans',10,9),
    ('Romans',15,13),('1 Corinthians',10,13),('2 Corinthians',12,9),('Galatians',5,22),
    ('Ephesians',4,32),('Ephesians',6,10),('Colossians',3,23),('1 Thessalonians',5,16),
    ('1 Thessalonians',5,17),('1 Thessalonians',5,18),('2 Timothy',3,16),('Titus',3,5),
    ('Hebrews',4,16),('Hebrews',12,1),('James',4,8),('1 Peter',2,9),
    ('2 Peter',3,9),('1 John',4,19),('Psalms',1,1),('Psalms',19,1),
    ('Psalms',51,10),('Psalms',103,1),('Psalms',121,1),('Psalms',121,2),
    ('Psalms',139,14),('Psalms',147,3),('Proverbs',16,3),('Proverbs',17,17),
    ('Proverbs',18,10),('Proverbs',22,6),('Ecclesiastes',3,1),('Isaiah',9,6),
    ('Isaiah',43,2),('Isaiah',53,5),('Isaiah',55,8),('Isaiah',55,9),
    ('Genesis',1,1),('Exodus',14,14),('Numbers',6,24),('Numbers',6,25),
    ('Numbers',6,26),('1 Samuel',16,7),('2 Chronicles',7,14),('Nehemiah',8,10),
    ('Job',19,25),('Daniel',3,17),('Jonah',2,9),('Habakkuk',3,19),
    ('Malachi',3,10),('Matthew',5,14),('Matthew',7,7),('Matthew',22,37),
    ('Mark',12,31),('Luke',12,34),('John',13,34),('John',10,10),
    ('Acts',2,38),('Acts',4,12),('Romans',6,23),('Romans',8,1),
    ('Romans',8,38),('Romans',8,39),('1 Corinthians',15,58),('2 Corinthians',4,16),
    ('2 Corinthians',9,7),('Galatians',6,9),('Ephesians',3,20),('Philippians',1,6),
    ('Philippians',2,3),('Philippians',4,8),('Colossians',3,2),('1 Timothy',4,12),
    ('2 Timothy',4,7),('Hebrews',10,24),('Hebrews',10,25),('James',1,5),
    ('James',1,22),('1 Peter',3,15),('1 John',3,1),('1 John',4,7),
    ('3 John',1,4),('Jude',1,24),('Revelation',3,20),('Psalms',5,3),
]

# ── Fill pool: devotional books only, so no genealogy or battle narrative ────
POOL_CHAPTERS = (
    [('Psalms', c) for c in list(range(1, 151))]
    + [('Proverbs', c) for c in range(1, 32)]
    + [('Isaiah', c) for c in range(40, 67)]
    + [('Matthew', c) for c in [5, 6, 7]]
    + [('John', c) for c in [14, 15, 16, 17]]
    + [('Romans', c) for c in [5, 8, 12]]
    + [('1 Corinthians', c) for c in [12, 13]]
    + [('2 Corinthians', c) for c in [4, 5, 9]]
    + [('Galatians', c) for c in [5, 6]]
    + [('Ephesians', c) for c in [1, 2, 3, 4, 5, 6]]
    + [('Philippians', c) for c in [1, 2, 3, 4]]
    + [('Colossians', c) for c in [1, 2, 3]]
    + [('1 Thessalonians', c) for c in [4, 5]]
    + [('2 Timothy', c) for c in [1, 2, 3, 4]]
    + [('Hebrews', c) for c in [4, 10, 11, 12, 13]]
    + [('James', c) for c in [1, 2, 3, 4, 5]]
    + [('1 Peter', c) for c in [1, 2, 3, 4, 5]]
    + [('1 John', c) for c in [1, 2, 3, 4, 5]]
)

BAD_WORDS = re.compile(
    r'\b(begat|smote|slew|slay|concubine|foreskin|dung|whoredom|harlot|bastard|'
    r'leprosy|menstruous|pisseth|circumcis|vengeance|wrath|destroy|destruction|'
    r'perish|cursed|abomination|lusts|adulter|fornicat|drunkard|wicked|ungodly|'
    r'enemies|liar|lieth|Selah)\b', re.I)
BAD_START = re.compile(
    r'^(And|But|Then|For|So|Neither|Nor|Yea|Also|Because|Therefore|Which|Who|That|'
    r'Thus|Among|Wherefore|Moreover|Now|When|If|As|Unto|Howbeit|Behold, I)\b')
# Psalm and Proverb superscriptions live inside verse 1 in the KJV, so a "verse"
# there is really a title. Skipped rather than trimmed — trimming would be editing.
SUPERSCRIPTION = re.compile(
    r'(chief Musician|A Psalm|Psalm of|A Song|Maschil|Michtam|proverbs of Solomon|'
    r'words of the Preacher|Hallelujah)', re.I)
# At least one of these, so a filled day still reads as a daily verse rather than
# a fragment of narrative.
DEVOTIONAL = re.compile(
    r'\b(LORD|God|Christ|Jesus|love|faith|hope|trust|peace|joy|merc|grace|bless|'
    r'righteous|wisdom|heart|spirit|salvation|saved|holy|pray|praise|thank|'
    r'strength|refuge|light|truth|good|glory|rejoice|comfort|forgive)\b', re.I)

CAP = {'Psalms': 105, 'Proverbs': 40, 'Isaiah': 32}

def usable(book, ch, v, t):
    if not (60 <= len(t) <= 230):        return False
    if BAD_WORDS.search(t):              return False
    if BAD_START.match(t):               return False
    if SUPERSCRIPTION.search(t):         return False
    if not DEVOTIONAL.search(t):         return False
    if v == 1 and book in ('Psalms', 'Proverbs', 'Ecclesiastes'): return False
    if t.count(',') > 4:                 return False
    if not t.endswith(('.', '!', '?')):  return False
    return True

seen  = set()
out   = []

def add(book, ch, v, text=None):
    key = (book, ch, v)
    if key in seen: return False
    t = text if text is not None else verse(book, ch, v)
    if not t: return False
    seen.add(key)
    out.append({'reference': f'{book} {ch}:{v}', 'text': t})
    return True

for b, c, v in CURATED:
    try:
        add(b, c, v)
    except Exception as e:
        print('MISSING CURATED', b, c, v, e, file=sys.stderr)

# Fill, round-robin across the pool so no book dominates a stretch of days, and
# capped per book so the year does not become the Psalter.
from collections import Counter
used_by_book = Counter(r['reference'].rsplit(' ', 1)[0] for r in out)
cursor = {}
rounds = 0
while len(out) < 366 and rounds < 60:
    rounds += 1
    progressed = False
    for book, ch in POOL_CHAPTERS:
        if len(out) >= 366: break
        if used_by_book[book] >= CAP.get(book, 24): continue
        try: vs = chapter(book, ch)
        except Exception: continue
        start = cursor.get((book, ch), 0)
        for i in range(start, len(vs)):
            t = clean(vs[i])
            if book == 'Psalms': t = PSALM_TITLE.sub('', t)
            if usable(book, ch, i + 1, t) and (book, ch, i + 1) not in seen:
                add(book, ch, i + 1, t)
                used_by_book[book] += 1
                cursor[(book, ch)] = i + 1
                progressed = True
                break
        else:
            cursor[(book, ch)] = len(vs)
    if not progressed:
        for b in CAP: CAP[b] += 15          # loosen rather than come up short
        for b in list(used_by_book): pass

print('collected', len(out), file=sys.stderr)
data = [{'day': i + 1, 'reference': r['reference'], 'text': r['text']}
        for i, r in enumerate(out[:366])]
json.dump(data, open('/tmp/claude-0/-home-claude/41bd101d-aa26-5663-816c-f76e217d575f/scratchpad/daily-verses.json','w'),
          indent=1, ensure_ascii=False)
print('wrote', len(data), file=sys.stderr)
