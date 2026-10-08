# ChurchGeniusPro — demo video recorder

Records a product-demo video by driving the **real** application in Chromium, so every
screen, label and value in the video is the actual product. Nothing is simulated.

| Scene | What it shows |
|---|---|
| 1 | Create Event — every field explained on screen, then filled |
| 2 | Review and Save, with the "Event saved" confirmation |
| 3 | Event Reminder for the new event, then Save |
| 4 | Public registration page → *Will you be attending?* → **Yes** → Fill Information → Auto-Populate |

Output: `output/churchgeniuspro-demo-<timestamp>.mp4` (or `.webm` if ffmpeg is not installed)
plus `output/narration-<timestamp>.txt` — the on-screen caption text with timings, ready to
read as a voiceover.

---

## 1. Prerequisites

* **Node.js 18+**
* ChurchGeniusPro running and reachable (default `http://localhost:8080`)
* An admin login that can create events

## 2. Install (once)

```bash
cd demo-video
npm install
npx playwright install chromium
```

## 3. Record

Windows PowerShell:

```powershell
$env:CGP_URL  = "http://localhost:8080"
$env:CGP_USER = "your-admin-username"
$env:CGP_PASS = "your-password"
npm run demo
```

macOS / Linux:

```bash
CGP_URL=http://localhost:8080 CGP_USER=admin CGP_PASS=secret npm run demo
```

The walkthrough runs headless — nothing appears on screen — and takes roughly **7 minutes**.
The video is written when it finishes.

**Delete the previous "Christmas in the Park" event *and its reminder* before each run.**
If the event still exists the reminder page opens the existing reminder for editing instead
of offering the suggested schedule, and the narration no longer matches what's on screen.

The script has been dry-run end to end against a stub of these screens: captions, the drawn
cursor, field highlights, typed dates and times, save, reminder and Auto-Populate all record
correctly at 1440×900.

**Without credentials:** run `npm run demo` with no `CGP_USER`/`CGP_PASS` and the script
waits at the login screen for you to sign in by hand, then carries on. Useful for SSO or MFA.

### Options

| Variable | Default | Meaning |
|---|---|---|
| `CGP_URL`  | `http://localhost:8080` | Base URL of the app |
| `CGP_USER` / `CGP_PASS` | — | Admin login; omit to sign in manually |
| `CGP_PACE` | `1` | Timing multiplier — `1.35` slower and more deliberate, `0.8` faster |
| `CGP_HEADED` | `0` | `1` shows the browser window. **Leave this off.** A window shorter than 900 px makes Playwright record the page into part of the canvas and pad the rest with flat grey — that grey is what hid the Save Event and Set Event Reminder buttons in the first cut. Headless always fills the frame. |

## 4. Auto-Populate (Scene 4)

Auto-Populate fires when the guest leaves the Email field: the app looks up
`event_registration` first, then `family_member`, for that church, and fills in name, phone
and party size.

For the video to show fields **being populated**, `thisisatest@gmail.com` must already exist
in one of those tables. Easiest: register that email once on any event (or add it as a family
member) before recording. If no record exists the script says so on screen and in the console
— it never fakes the result.

## 5. Data used

All fictional test data. The address `1234 Example Street, Sharine, New York 123456` is
invented, and the uploaded flyer (`assets/christmas-in-the-park-flyer.png`) is a generated
sample marked **SAMPLE / TEST FLYER**. No real person's information appears anywhere.

Event values live in the `EVENT` object at the top of `record-demo.js` — change them there to
film a different event.

## 6. Adding a voiceover

The on-screen caption cards are already the script. `output/narration-<timestamp>.txt` lists
each line with the timestamp it appeared at, so a voice track can be recorded straight against
the video in any editor. Keep or drop the caption cards to taste — they're drawn by the
overlay in `installOverlay()`.

## 7. If a step breaks

The script stops, writes `output/error.png`, and still saves the video up to that point. The
usual cause is a changed element id — the selectors it depends on are:

`#eventName #eventCode #eventDate #startTime #endTime #registrationEndDate #fee #maxCapacity
#showRegistrants #allowMaybeRsvp #generateRegistrationId #generateQrCode #selfCheckinEnabled
#address1 #city #state #country #pinCode #hostName #hostPhone #hostEmail #hostNote
#foodAvailable #accommodationAvailable #noteEditor #eventImage #saveBtn #reminderPrompt
#reminderPromptLink #eventSelect #sameDay #beforeDaysCheck #beforeDays #searchInput
#slideOptYes #step2 #email #firstName #lastName #phone`
