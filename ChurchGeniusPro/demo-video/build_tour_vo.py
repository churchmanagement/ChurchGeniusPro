#!/usr/bin/env python3
"""
Timed voice-over for record-tour.js output.

  python3 build_tour_vo.py <narration-tour-*.txt> <video.webm> <out.mp4>

The narration file has one line per segment: "[mm:ss] (NNs) text". Each line is
synthesised with Piper and placed at its timestamp. A line that would run past its
window is spoken slightly faster (length_scale down to 0.85); if it still does not
fit, that is reported and it overlaps the next segment by the excess.
"""
import os, re, sys, wave, subprocess, io
import numpy as np
from piper import PiperVoice

VOICE = os.environ.get('PIPER_VOICE', '/tmp/vo/voices/en_US-hfc_female-medium.onnx')
LEAD_IN = 0.6          # seconds after a segment starts before speech begins
TAIL    = 0.4          # keep this much silence at the end of a window
GAIN    = 0.9

# Pronunciation / phrasing tweaks for TTS only (the on-screen captions are untouched).
SAY = [
    ('ChurchGeniusPro', 'Church Genius Pro'),
    ('churchgeniuspro.com', 'church genius pro dot com'),
    ('NTag', 'N-Tag'),
    ('AI', 'A.I.'),
    (' — ', ', '), ('—', ', '),
]

# Spoken replacements for lines that cannot fit their window at a natural pace (keyed by prefix).
OVERRIDE = {
    'Behind the scenes:': "Behind the scenes: roles, users, temporary and private page access, linked accounts and church settings, plus Favourites, so your most-used screens are one click away.",
}

def tts(voice, text, length_scale):
    buf = io.BytesIO()
    with wave.open(buf, 'wb') as w:
        from piper import SynthesisConfig
        voice.synthesize_wav(text, w, syn_config=SynthesisConfig(length_scale=length_scale))
    buf.seek(0)
    with wave.open(buf, 'rb') as w:
        sr = w.getframerate()
        a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return sr, a

def scene_cuts(video, threshold=0.25):
    """Hard scene changes (page navigations) as seconds into the video."""
    out = subprocess.run(['ffmpeg', '-v', 'info', '-i', video, '-vf', f"select='gt(scene,{threshold})',showinfo", '-f', 'null', '-'],
                         capture_output=True, text=True).stderr
    return [float(x) for x in re.findall(r'pts_time:([0-9.]+)', out)]

def align(lines, video):
    """
    The script stamps a line when it STARTS loading a page; the page appears on screen
    a few seconds later (navigation + render), and the recording itself starts before
    the script clock. Snap every page line to the next hard cut in the video within
    2–12 s of its stamp; lines without a cut (presenter/panel cards fade, no hard cut)
    are shifted by the median page offset minus the ~1.4 s a page takes to render.
    Returns [(video_start, window, text)].
    """
    cuts = scene_cuts(video)
    used, offsets, starts = set(), [], []
    for t, win, text in lines:
        hit = next((c for c in cuts if c not in used and t + 2 <= c <= t + 12), None)
        if hit is not None:
            used.add(hit); offsets.append(hit - t); starts.append(hit)
        else:
            starts.append(None)
    page_off = float(np.median(offsets)) if offsets else 0.0
    card_off = max(0.0, page_off - 1.4)
    fixed = [s if s is not None else t + card_off for s, (t, _, _) in zip(starts, lines)]
    if fixed and cuts and fixed[0] > cuts[0] - 0.5 and lines[0][0] == 0:
        fixed[0] = cuts[0]                      # the title card's fade-in is the first cut
    out = []
    for i, (t, win, text) in enumerate(lines):
        nxt = fixed[i + 1] if i + 1 < len(fixed) else fixed[i] + win
        out.append((fixed[i], max(4.0, nxt - fixed[i]), text, t, starts[i] is not None))
    print(f'aligned: {len(offsets)} lines snapped to cuts (page offset {page_off:.1f}s, card offset {card_off:.1f}s)')
    return out

def main(narr, video, out):
    voice = PiperVoice.load(VOICE)
    lines = []
    for ln in open(narr, encoding='utf-8'):
        m = re.match(r'\[(\d+):(\d+)\]\s+\((\d+)s\)\s+(.*)', ln.strip())
        if m:
            lines.append((int(m[1]) * 60 + int(m[2]), int(m[3]), m[4]))
    dur = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', video]).decode().strip())
    aligned = align(lines, video)
    sr = 22050
    track = np.zeros(int((dur + 2) * sr), dtype=np.float32)
    report = []
    for start, win, text, stamp, snapped in aligned:
        spoken = next((v for k, v in OVERRIDE.items() if text.startswith(k)), text)
        for a, b in SAY: spoken = spoken.replace(a, b)
        budget = win - LEAD_IN - TAIL
        scale = 1.0
        while True:
            r, audio = tts(voice, spoken, scale)
            assert r == sr, r
            length = len(audio) / sr
            if length <= budget or scale <= 0.85: break
            scale = round(max(0.85, scale - 0.05), 2)
        at = int((start + LEAD_IN) * sr)
        end = min(len(track), at + len(audio))
        track[at:end] += audio[:end - at] * GAIN
        flag = '' if length <= budget else f'  ! over by {length - budget:.1f}s'
        report.append(f'[{int(stamp) // 60:02d}:{int(stamp) % 60:02d}] -> {start:6.2f}s {"cut " if snapped else "card"}  win {win:4.1f}s  speech {length:5.1f}s  scale {scale}{flag}')
    peak = np.max(np.abs(track)) or 1.0
    if peak > 0.98: track = track / peak * 0.98
    wav = out + '.vo.wav'
    with wave.open(wav, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((track * 32767).astype(np.int16).tobytes())
    print('\n'.join(report))
    subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-i', video, '-i', wav,
                           '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p',
                           '-c:a', 'aac', '-b:a', '160k', '-shortest', '-movflags', '+faststart', out])
    print('wrote', out, os.path.getsize(out), 'bytes')

if __name__ == '__main__':
    main(*sys.argv[1:4])
