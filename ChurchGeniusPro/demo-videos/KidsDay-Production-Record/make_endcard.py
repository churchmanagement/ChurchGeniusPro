from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math

W, H = 1080, 1920
img = Image.new("RGB", (W, H))
d = ImageDraw.Draw(img)

# Purple gradient background #54244e -> #4a1d44 -> #7a4470
c1 = (0x54, 0x24, 0x4E); c2 = (0x4A, 0x1D, 0x44); c3 = (0x7A, 0x44, 0x70)
for y in range(H):
    t = y / H
    if t < 0.5:
        a, b, tt = c1, c2, t / 0.5
    else:
        a, b, tt = c2, c3, (t - 0.5) / 0.5
    col = tuple(int(a[i] + (b[i] - a[i]) * tt) for i in range(3))
    d.line([(0, y), (W, y)], fill=col)

# Soft translucent circles
ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
od = ImageDraw.Draw(ov)
for (cx, cy, r, alpha) in [(150, 300, 260, 26), (950, 550, 200, 22), (200, 1500, 300, 24), (900, 1700, 240, 20), (550, 950, 380, 12)]:
    od.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, alpha))
ov = ov.filter(ImageFilter.GaussianBlur(40))
img = Image.alpha_composite(img.convert("RGBA"), ov).convert("RGB")
d = ImageDraw.Draw(img)

def font(sz, bold=True):
    p = "/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf" % ("-Bold" if bold else "")
    return ImageFont.truetype(p, sz)

# White rounded logo card
logo = Image.open("/root/kidsday/logo.png").convert("RGBA")
lw = 520
lh = int(logo.height * lw / logo.width)
logo = logo.resize((lw, lh), Image.LANCZOS)
card_w, card_h = 640, lh + 80
card = Image.new("RGBA", (card_w, card_h), (0, 0, 0, 0))
cd = ImageDraw.Draw(card)
cd.rounded_rectangle([0, 0, card_w, card_h], radius=48, fill=(255, 255, 255, 255))
card.paste(logo, ((card_w - lw) // 2, 40), logo)
cy0 = 330
img.paste(card, ((W - card_w) // 2, cy0), card)

def center(text, y, f, fill):
    bb = d.textbbox((0, 0), text, font=f)
    d.text(((W - (bb[2] - bb[0])) / 2, y), text, font=f, fill=fill)

y = cy0 + card_h + 90
center("ChurchGeniusPro", y, font(84), (255, 255, 255)); y += 150
center("Making Church Check-In", y, font(58), (0xF5, 0xC5, 0x42)); y += 78
center("Simple, Fast & Welcoming.", y, font(58), (0xF5, 0xC5, 0x42)); y += 130
center("AI-Powered Church Management Software", y, font(40, bold=False), (255, 255, 255)); y += 120

# Yellow pill CTA
pill_text = "Start Your Free Pro Trial Today!"
pf = font(46)
bb = d.textbbox((0, 0), pill_text, font=pf)
tw = bb[2] - bb[0]
pw, ph = tw + 120, 110
px, py = (W - pw) // 2, y
d.rounded_rectangle([px, py, px + pw, py + ph], radius=ph // 2, fill=(0xF5, 0xC5, 0x42))
d.text((px + 60, py + (ph - (bb[3] - bb[1])) / 2 - bb[1]), pill_text, font=pf, fill=(0x4A, 0x1D, 0x44))
y += ph + 110
center("ChurchGeniusPro.com", y, font(56), (255, 255, 255))

img.save("/root/kidsday/endcard.png")
print("saved", img.size)
