#!/usr/bin/env python3
"""
Build a timed voice-over from a record-demo.js narration-*.txt and mux it into the video.

  python3 build_vo.py <narration.txt> <video.webm> <out.mp4>

Lines are keyed by CAPTION TITLE (in order of appearance), not by timestamp, so a
re-recorded take with different timings is handled automatically. Unknown titles are
left silent; a line that will not fit its window is reported.
"""
import re, sys, os, subprocess, wave
import numpy as np

VOICE = os.environ.get('PIPER_VOICE', '/tmp/vo/voices/en_US-hfc_female-medium.onnx')
LENGTH_SCALE = '1.05'

# title -> spoken line(s). Repeated titles consume the list in order.
LINES = {
 'Sign in':                    ["Welcome to ChurchGenius Pro. In the next few minutes we'll create a Christmas event, set up its reminder, and open registration."],
 'SCENE1':                     ["Let's start by creating the event."],
 'Event Management':           ["Every event the church runs lives here. Upcoming, past, registrations, and check-in."],
 'Create Event':               ["The form is grouped into sections."],
 'Event Name':                 ["The event name is what everyone sees, on every page and reminder."],
 'Event ID / Code':            ["The event code is optional. Generate makes a unique one.", None],
 'Event Type':                 ["This is a one day event, not a multi-day one."],
 'Date':                       ["Christmas Eve. December twenty fourth, twenty twenty six."],
 'Start Time':                 ["The programme begins at six in the evening."],
 'End Time':                   ["And wraps up at ten."],
 'Registration End Date':      ["Registration closes automatically on the twentieth. Four days before the event."],
 'Registration Fee':           ["The fee is thirty dollars."],
 'Max Capacity':               ["And seating is capped at five hundred."],
 'Who else is attending?':     ["Registrants can see who else is attending."],
 'Allow "Maybe"':              ["Maybe is allowed, so undecided families still count."],
 'Unique Registration ID':     ["Each attendee gets a registration I D for the check-in desk."],
 'Unique QR Code':             ["Q R codes stay off for this one."],
 'Self Check-In':              ["And self check-in stays off as well."],
 'Location':                   ["Now, the venue."],
 'Address 1':                  ["The street address drives the map link in every reminder."],
 'City':                       ["The city."],
 'State':                      ["The state, New York."],
 'Country':                    ["The country is already set."],
 'PIN / Zip Code':             ["And the postal code, used for directions."],
 'Host Information':           ["Now, host details."],
 'Host Name':                  ["This is who guests contact. The organising church."],
 'Phone Number':               ["A phone number for questions."],
 'Email':                      ["And the email that registration replies go to.",
                                "They enter their email. And this is the part people like."],
 'Note':                       ["Host notes are internal, and can stay blank."],
 'Is Food Available?':         ["Food is on, so registrants get a dietary question."],
 'Food Options':               ["And these are the choices they pick from. Veg, pizza, or burger."],
 'Is Accommodation Available?':["Accommodation isn't needed for a one evening event."],
 'Event Note':                 ["The event note is the message every registrant sees. When to arrive, and what to expect."],
 'Event Image':                ["And the flyer. It appears on the event card, and at the top of the registration page."],
 'SCENE2':                     ["That's everything."],
 'Review':                     ["A quick look back before saving. Name, date, fee, venue, host, and the note."],
 'Save Event':                 ["Now we save."],
 'Event saved':                ["And Christmas in the Park is live."],
 'SCENE3':                     ["Reminders are deliberately a separate step. Saving an event never quietly messages anyone."],
 'Event Reminders':            ["The event is already selected."],
 'Event':                      [None],
 'Suggested schedule':         ["The app already suggests a schedule."],
 'Same Day':                   ["Same day, three days before, and one day after."],
 'Before Days':                ["We'll bring the advance reminder in to two days, while people can still register."],
 'Delivery Channels':          ["Email and S M S are both enabled."],
 'Templates':                  ["Each reminder has its own template, with placeholders that fill themselves in."],
 'Save':                       ["Save, and the reminder is scheduled against this event."],
 'Reminder saved':             [None],
 'SCENE4':                     ["Now, what a guest sees."],
 'Find the event':             ["Every event has a public registration link you can share by email, text, or Q R code."],
 'Register Page':              ["Opening it now."],
 'Public registration page':   ["The flyer, the date, the venue, and the note. All from the form we just filled in."],
 'Will you be attending?':     ["The guest answers first. Yes, maybe, or no."],
 'Fill Information':           ["Then the form opens."],
 'Auto-Populate':              ["ChurchGenius Pro looks up their earlier registration, or their family record,",
                                "and fills in the rest for them."],
 'OUTRO':                      ["Events, reminders, and registration. In one place."],
}

def parse(path):
    lines = open(path, encoding='utf-8').read().split('\n')
    cues = []
    for i, l in enumerate(lines):
        m = re.match(r'\[(\d\d):(\d\d)\]\s+(.*)', l)
        if not m: continue
        t = int(m.group(1)) * 60 + int(m.group(2))
        title = m.group(3).strip()
        if title.startswith('══'):
            inner = title.strip('═ ').strip()
            if inner.startswith('Scene'):  title = 'SCENE' + inner.split()[1]
            else:                          title = 'OUTRO'
        cues.append((t, title))
    return cues

def main():
    narr, video, out = sys.argv[1], sys.argv[2], sys.argv[3]
    cues = parse(narr)
    dur = float(subprocess.run(['ffprobe','-v','error','-show_entries','format=duration',
                                '-of','csv=p=0', video], capture_output=True, text=True).stdout)
    pos = {}
    plan, unknown = [], []
    for t, title in cues:
        if title not in LINES:
            unknown.append(title); continue
        k = pos.get(title, 0); pos[title] = k + 1
        variants = LINES[title]
        text = variants[k] if k < len(variants) else None
        if text: plan.append([t, title, text, 0.0])

    os.makedirs('/tmp/vo/pipeline/clips', exist_ok=True)
    for i, row in enumerate(plan):
        f = f'/tmp/vo/pipeline/clips/{i:02d}.wav'
        subprocess.run(['piper','-m',VOICE,'-f',f,'--length_scale',LENGTH_SCALE,
                        '--sentence_silence','0.28'], input=row[2].encode(), capture_output=True)
        w = wave.open(f); row[3] = w.getnframes()/w.getframerate(); SR = w.getframerate(); w.close()

    # the closing line is allowed to run to the end of the video
    if plan: plan[-1][0] = min(plan[-1][0], dur - plan[-1][3] - 0.2)

    over = []
    for i, row in enumerate(plan):
        nxt = plan[i+1][0] if i+1 < len(plan) else dur
        room = nxt - row[0]
        if row[3] > room - 0.15:
            over.append((row[1], round(row[3],1), round(room,1)))

    master = np.zeros(int((dur + 1) * SR), dtype=np.float32)
    for i, row in enumerate(plan):
        w = wave.open(f'/tmp/vo/pipeline/clips/{i:02d}.wav')
        a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)/32768; w.close()
        f = int(0.015*SR); a[:f] *= np.linspace(0,1,f); a[-f:] *= np.linspace(1,0,f)
        s = int(row[0]*SR); e = min(s+len(a), len(master)); master[s:e] += a[:e-s]
    master = master/max(np.max(np.abs(master)), 1e-6)*0.89
    o = wave.open('/tmp/vo/pipeline/vo.wav','wb'); o.setnchannels(1); o.setsampwidth(2); o.setframerate(SR)
    o.writeframes((master*32767).astype(np.int16).tobytes()); o.close()

    subprocess.run(['ffmpeg','-hide_banner','-v','error','-y','-i','/tmp/vo/pipeline/vo.wav',
                    '-af','loudnorm=I=-16:TP=-1.5:LRA=11,highpass=f=70','-ar','48000',
                    '/tmp/vo/pipeline/vo_norm.wav'], check=True)
    subprocess.run(['ffmpeg','-hide_banner','-v','error','-y','-i',video,'-i','/tmp/vo/pipeline/vo_norm.wav',
                    '-map','0:v','-map','1:a','-c:v','libx264','-preset','veryfast','-crf','21',
                    '-pix_fmt','yuv420p','-tune','stillimage','-c:a','aac','-b:a','160k',
                    '-shortest','-movflags','+faststart', out], check=True)

    print(f'cues={len(cues)}  spoken={len(plan)}  video={dur:.1f}s')
    if unknown: print('NO LINE FOR (silent):', sorted(set(unknown)))
    if over:    print('OVERRUNS:', over)
    else:       print('all lines fit their windows')
    print('written:', out)

main()
