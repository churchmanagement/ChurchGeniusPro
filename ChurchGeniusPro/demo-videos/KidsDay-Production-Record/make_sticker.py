from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1080, 1920

bg = Image.open("/root/kidsday/sticker_bg.jpg").convert("RGB").resize((W, H), Image.LANCZOS)
bg = bg.filter(ImageFilter.GaussianBlur(14))
bg = Image.eval(bg, lambda v: int(v * 0.82))

def font(sz, bold=True):
    p = "/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf" % ("-Bold" if bold else "")
    return ImageFont.truetype(p, sz)

sw, sh = 880, 1180
sc = 2
purple = (0x4A, 0x1D, 0x44, 255)
teal = (0x1F, 0x8A, 0x8C, 255)
gray = (120, 118, 122, 255)

st = Image.new("RGBA", (sw * sc, sh * sc), (0, 0, 0, 0))
sd = ImageDraw.Draw(st)
sd.rounded_rectangle([0, 0, sw * sc - 1, sh * sc - 1], radius=60 * sc, fill=(253, 253, 250, 255), outline=(210, 210, 205, 255), width=3 * sc)

# Header band (rounded top only)
hdr = 190 * sc
sd.rounded_rectangle([0, 0, sw * sc - 1, hdr + 60 * sc], radius=60 * sc, fill=(0x54, 0x24, 0x4E, 255))
sd.rectangle([0, hdr - 40 * sc, sw * sc - 1, hdr], fill=(0x54, 0x24, 0x4E, 255))
sd.rectangle([0, hdr, sw * sc - 1, hdr + 60 * sc], fill=(253, 253, 250, 255))

def center(text, y, f, fill):
    bb = sd.textbbox((0, 0), text, font=f)
    sd.text(((sw * sc - (bb[2] - bb[0])) / 2, y - bb[1]), text, font=f, fill=fill)
    return bb[3] - bb[1]

hf = font(56 * sc)
bb = sd.textbbox((0, 0), "KIDS CHECK-IN", font=hf)
sd.text(((sw * sc - (bb[2] - bb[0])) / 2, (hdr - (bb[3] - bb[1])) / 2 - bb[1]), "KIDS CHECK-IN", font=hf, fill=(255, 255, 255, 255))

y = 250 * sc
center("SAM", y, font(140 * sc), purple); y += 190 * sc
sd.line([(110 * sc, y), (sw * sc - 110 * sc, y)], fill=(222, 220, 216, 255), width=3 * sc); y += 40 * sc
center("CHECK-IN CODE", y, font(38 * sc, bold=False), gray); y += 80 * sc
center("K-4827", y, font(185 * sc), teal); y += 250 * sc
sd.line([(110 * sc, y), (sw * sc - 110 * sc, y)], fill=(222, 220, 216, 255), width=3 * sc); y += 45 * sc
center("Classroom A", y, font(70 * sc), purple); y += 115 * sc
center("Check-In: 9:12 AM", y, font(52 * sc, bold=False), (90, 88, 94, 255))

st = st.resize((sw, sh), Image.LANCZOS)
st = st.rotate(-3, expand=True, resample=Image.BICUBIC)

shadow = Image.new("RGBA", st.size, (0, 0, 0, 0))
alpha = st.split()[3].point(lambda a: int(a * 0.5))
shadow.paste((0, 0, 0, 130), (0, 0), alpha)
shadow = shadow.filter(ImageFilter.GaussianBlur(24))
sh_img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px, py = (W - st.width) // 2, (H - st.height) // 2 - 40
sh_img.paste(shadow, (px + 18, py + 26), shadow)

out = Image.alpha_composite(bg.convert("RGBA"), sh_img)
out.paste(st, (px, py), st)
out.convert("RGB").save("/root/kidsday/sticker_insert.png")
print("ok")
