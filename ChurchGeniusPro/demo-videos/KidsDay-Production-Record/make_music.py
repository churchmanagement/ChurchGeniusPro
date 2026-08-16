import numpy as np, wave

SR = 48000
BPM = 112.0
beat = 60.0 / BPM
DUR = 90.0
N = int(SR * DUR)
t = np.arange(N) / SR
out = np.zeros(N)

def note_freq(semi):  # semitones from A3=220
    return 220.0 * (2 ** (semi / 12.0))

# Chords: Am F C G (root positions, semitones from A3)
chords = [
    [0, 3, 7],        # A C E
    [-4, 0, 5],       # F A C
    [3, 7, 12],       # C E G
    [-2, 2, 7],       # G B D
]

bar = beat * 4
def env_ad(n, a, rel):
    e = np.ones(n)
    na = min(int(a * SR), n); nr = min(int(rel * SR), n)
    if na > 0: e[:na] = np.linspace(0, 1, na)
    if 0 < nr < n: e[-nr:] *= np.linspace(1, 0, nr)
    return e

# Pads
pos = 0.0
ci = 0
while pos < DUR:
    n0 = int(pos * SR); n1 = min(int((pos + bar) * SR), N)
    n = n1 - n0
    if n <= 0: break
    tt = np.arange(n) / SR
    seg = np.zeros(n)
    for semi in chords[ci % 4]:
        f = note_freq(semi) / 2  # low pads
        seg += 0.5 * np.sin(2 * np.pi * f * tt) + 0.25 * np.sin(2 * np.pi * f * 2 * tt + 0.3)
    seg *= env_ad(n, 0.8, 0.9) * 0.16
    out[n0:n1] += seg
    pos += bar; ci += 1

# Pluck arpeggio (8ths)
rng = np.random.default_rng(7)
pos = 0.0; ci = 0; k = 0
while pos < DUR:
    chord = chords[ci % 4]
    for e8 in range(8):
        p = pos + e8 * beat / 2
        if p >= DUR: break
        semi = chord[e8 % 3] + (12 if e8 % 4 == 3 else 12)
        f = note_freq(semi)
        n0 = int(p * SR)
        dur = 0.28
        n = min(int(dur * SR), N - n0)
        if n <= 0: continue
        tt = np.arange(n) / SR
        pl = np.sin(2 * np.pi * f * tt) * np.exp(-tt * 9.0)
        pl += 0.3 * np.sin(2 * np.pi * f * 2 * tt) * np.exp(-tt * 12.0)
        out[n0:n0 + n] += pl * 0.10
        k += 1
    pos += bar; ci += 1

# Soft kick on beats 1,3 ; hat on 8ths
pos = 0.0; b = 0
while pos < DUR:
    n0 = int(pos * SR)
    if b % 2 == 0:  # kick
        n = min(int(0.16 * SR), N - n0)
        if n > 0:
            tt = np.arange(n) / SR
            out[n0:n0 + n] += 0.22 * np.sin(2 * np.pi * (95 * np.exp(-tt * 18) + 42) * tt) * np.exp(-tt * 16)
    # hat
    for h in range(2):
        p = pos + h * beat / 2
        n0h = int(p * SR)
        n = min(int(0.05 * SR), N - n0h)
        if n > 0:
            tt = np.arange(n) / SR
            out[n0h:n0h + n] += 0.035 * rng.standard_normal(n) * np.exp(-tt * 70)
    pos += beat; b += 1

# Gentle master fade in/out
fade_in = int(1.0 * SR)
out[:fade_in] *= np.linspace(0, 1, fade_in)
fade_out = int(3.0 * SR)
out[-fade_out:] *= np.linspace(1, 0, fade_out)

out /= max(1e-9, np.abs(out).max())
out *= 0.85
pcm = (out * 32767).astype(np.int16)
stereo = np.repeat(pcm[:, None], 2, axis=1)
with wave.open("/root/kidsday/music.wav", "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(stereo.tobytes())
print("music written", DUR, "s")
