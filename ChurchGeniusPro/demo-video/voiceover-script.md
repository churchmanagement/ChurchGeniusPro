# Voice-over script — ChurchGeniusPro demo

**Final — locked for the avatar render (take 2026-09-03 00:18, 5:11).**

Voice: Piper `en_US-hfc_female-medium` (free, offline neural TTS), length_scale 1.05.
Rendered audio: `output/narration-final.wav` / `.mp3` — 5:11.7, 48 kHz mono, loudness-normalised.

Lines are keyed by the **caption title** shown on screen, not by timestamp. Every line is
verified to fit the gap before the next caption; the build reports any that don't.
`SCENE1`–`SCENE4` and `OUTRO` are the full-screen title cards.

Spelling notes: `I D`, `Q R`, `S M S` are spaced so the letters are read out rather than
pronounced as words, and numbers are spelled out for the same reason.

> Changing any line means re-rendering the avatar. Don't edit this after the HeyGen render.

| Caption on screen | Spoken line |
|---|---|
| Sign in | Welcome to ChurchGenius Pro. In the next few minutes we'll create a Christmas event, set up its reminder, and open registration. |
| SCENE1 | Let's start by creating the event. |
| Event Management | Every event the church runs lives here. Upcoming, past, registrations, and check-in. |
| Create Event | The form is grouped into sections. |
| Event Name | The event name is what everyone sees, on every page and reminder. |
| Event ID / Code  (1) | The event code is optional. Generate makes a unique one. |
| Event ID / Code  (2) | _(silent — no line)_ |
| Event Type | This is a one day event, not a multi-day one. |
| Date | Christmas Eve. December twenty fourth, twenty twenty six. |
| Start Time | The programme begins at six in the evening. |
| End Time | And wraps up at ten. |
| Registration End Date | Registration closes automatically on the twentieth. Four days before the event. |
| Registration Fee | The fee is thirty dollars. |
| Max Capacity | And seating is capped at five hundred. |
| Who else is attending? | Registrants can see who else is attending. |
| Allow "Maybe" | Maybe is allowed, so undecided families still count. |
| Unique Registration ID | Each attendee gets a registration I D for the check-in desk. |
| Unique QR Code | Q R codes stay off for this one. |
| Self Check-In | And self check-in stays off as well. |
| Location | Now, the venue. |
| Address 1 | The street address drives the map link in every reminder. |
| City | The city. |
| State | The state, New York. |
| Country | The country is already set. |
| PIN / Zip Code | And the postal code, used for directions. |
| Host Information | Now, host details. |
| Host Name | This is who guests contact. The organising church. |
| Phone Number | A phone number for questions. |
| Email  (1) | And the email that registration replies go to. |
| Email  (2) | They enter their email. And this is the part people like. |
| Note | Host notes are internal, and can stay blank. |
| Is Food Available? | Food is on, so registrants get a dietary question. |
| Food Options | And these are the choices they pick from. Veg, pizza, or burger. |
| Is Accommodation Available? | Accommodation isn't needed for a one evening event. |
| Event Note | The event note is the message every registrant sees. When to arrive, and what to expect. |
| Event Image | And the flyer. It appears on the event card, and at the top of the registration page. |
| SCENE2 | That's everything. |
| Review | A quick look back before saving. Name, date, fee, venue, host, and the note. |
| Save Event | Now we save. |
| Event saved | And Christmas in the Park is live. |
| SCENE3 | Reminders are deliberately a separate step. Saving an event never quietly messages anyone. |
| Event Reminders | The event is already selected. |
| Event | _(silent — no line)_ |
| Suggested schedule | The app already suggests a schedule. |
| Same Day | Same day, three days before, and one day after. |
| Before Days | We'll bring the advance reminder in to two days, while people can still register. |
| Delivery Channels | Email and S M S are both enabled. |
| Templates | Each reminder has its own template, with placeholders that fill themselves in. |
| Save | Save, and the reminder is scheduled against this event. |
| Reminder saved | _(silent — no line)_ |
| SCENE4 | Now, what a guest sees. |
| Find the event | Every event has a public registration link you can share by email, text, or Q R code. |
| Register Page | Opening it now. |
| Public registration page | The flyer, the date, the venue, and the note. All from the form we just filled in. |
| Will you be attending? | The guest answers first. Yes, maybe, or no. |
| Fill Information | Then the form opens. |
| Auto-Populate  (1) | ChurchGenius Pro looks up their earlier registration, or their family record, |
| Auto-Populate  (2) | and fills in the rest for them. |
| OUTRO | Events, reminders, and registration. In one place. |

## Rebuilding

```bash
python3 build_vo.py <narration-*.txt> <recording.webm> <out.mp4>
```

Needs `piper-tts` and the `en_US-hfc_female-medium` voice model.
