# ChurchGeniusPro — 5-minute feature tour
**Target runtime 5:46 · 13 features demonstrated + 21 named · DEMO- tenant (fictional sample data)**

Produced by `record-tour.js` (real app, Playwright) + Google Flow presenter clips,
narrated by Piper via `build_vo.py`, assembled with ffmpeg.

Columns: **Sec** = segment length · **At** = running time at segment start.

| # | At | Sec | Source | On screen | Narration (Piper) |
|---|---|---|---|---|---|
| 0 | 0:00 | 14 | **Flow** | Presenter, full frame → shrinks to PiP. Title card over brand plum. | "Running a church means running a small organisation — people, money, events, volunteers. ChurchGeniusPro puts all of it in one place, with AI built in throughout. Here's a five-minute look." |
| 1 | 0:14 | 18 | Recorder | Log in → Dashboard. Drag a section upward; it settles in its new position. | "This is your dashboard. Every section can be dragged where you want it, so each staff member sees what matters to them first." |
| 2 | 0:32 | 14 | Recorder | Families → Add Family → fill name, members → Save. | "Membership starts with families, not just individuals — so parents, children and contact details stay together." |
| 3 | 0:46 | 18 | Recorder | Attendance → mark a service → Check-in screen. | "Take attendance for a service in seconds, or let people check themselves in on a tablet at the door." |
| 4 | 1:04 | 18 | Recorder | Kids Ministry → registration → check-in → security label. | "Kids ministry has its own check-in, with matching guardian and child labels so no child leaves with the wrong adult." |
| 5 | 1:22 | 24 | Recorder | Events → create event → save → public registration page → Auto-Populate fills a returning guest. | "Create an event once and it publishes its own registration page. Returning guests are recognised automatically — their details fill themselves in." |
| 6 | 1:46 | 18 | Recorder | Donations → member contributions list → a contribution record. | "Donations and member contributions are recorded against the right person automatically, ready for year-end statements." |
| 7 | 2:04 | 20 | Recorder | Bank Sync → connected bank → transactions arriving in the review queue. | "Connect the church bank account and transactions arrive on their own. Every one lands in a review queue first — nothing posts to your books unapproved." |
| 8 | 2:24 | 20 | Recorder | Reports → Financial Report → Tax Report → Transaction Report. | "Income, expense, transaction, tax and full financial reports are built in — the numbers your treasurer and your board actually ask for." |
| 9 | 2:44 | 12 | Recorder | Payroll → run → pay record. | "Payroll for your staff runs inside the same system, so salaries land in the same books as everything else." |
| 10 | 2:56 | 16 | Recorder | Worship Planning → Assignments → drag a member onto an instrument. | "Plan worship weeks ahead. Assign people automatically on a rotation, or place them by hand — drag a name where you want it." |
| 11 | 3:12 | 16 | Recorder | Reminders → event / periodic / one-time → Compose Email. | "Reminders go out by email and text — for an event, on a schedule, or as a one-off. And you can write the whole church a message from here." |
| 12 | 3:28 | 22 | Recorder | AI Assistant panel → ask a question → it answers and offers an action. | "The AI assistant works throughout the application. Ask it in plain language — it understands your church's own data, and helps you fill forms by voice, by photo, or just by describing what you want." |
| 13 | 3:50 | 20 | Recorder | AI Search → type a natural question → answer with the records it drew from. | "And AI Search answers real questions about your church. Ask who gave last month, or which families haven't attended since Easter, and it reads your data to tell you." |
| 14 | 4:10 | 8 | **Flow** | Presenter back to full frame, app window shrinks to a card beside her. | "That's the heart of it — but it is nowhere near all of it. Here's what else is waiting for you inside." |
| 15 | 4:18 | 14 | Recorder + overlay | **Panel: Money & giving.** Items slide in as she names them. | "On the money side: Income tracking, Bank Import for statements, and Pledges for your fundraising campaigns." |
| 16 | 4:32 | 16 | Recorder + overlay | **Panel: People & ministry.** | "For your people: Groups, Meetings, Volunteers, Prayer Ministry, Follow-ups, Certificates — and a Songbook for your worship team." |
| 17 | 4:48 | 16 | Recorder + overlay | **Panel: Your church online.** | "Seven public pages your church can share: an event calendar, upcoming events, prayer requests, kids check-in, member sign-up, a membership form and a donation form — plus your own NTag landing page." |
| 18 | 5:04 | 10 | Recorder + overlay | **Panel: Running the system.** | "Behind the scenes: role assignment, adding users, temporary access, private page access, linked accounts, church settings — and Favourites, so your team's most-used screens are one click away." |
| 19 | 5:14 | 8 | Recorder + overlay | **Panel: Help when you need it.** | "And if you get stuck, there's a built-in Help Center and ticketing — plus free migration assistance when you move your data across." |
| 20 | 5:22 | 24 | **Flow + flyer** | Presenter full frame, then the pricing flyer fills the screen and holds. | "Free for small churches. Twenty dollars a month for Standard, forty for Pro with advanced AI. Start with two months free — every Pro feature included, no credit card required. Visit churchgeniuspro.com." |

**Total 5:46**

---

## Rules carried over from `record-demo.js`

- Fictional data only. A DEMO- tenant is seeded by `TestDataService.provisionTenant`; no real
  church or member appears. Trial/Demo tenants never email real recipients.
- Nothing is simulated. If a feature will not demonstrate (as Auto-Populate will not without a
  seeded email), the script says so on screen rather than faking it.
- Record headless at 1440x900. `CGP_HEADED=1` pads the frame with grey and hides buttons.
- Pace with `CGP_PACE`; segment lengths above assume `1`.

## The feature roll (segments 15-19)

All 21 remaining features are named, grouped into five themed panels so they are digestible
rather than a flat list. The presenter reads each group while its items animate in beside her.

| Panel | Features |
|---|---|
| Money & giving | Income · Bank Import · Pledges |
| People & ministry | Groups · Meetings · Volunteers · Prayer Ministry · Follow-ups · Certificates · Songbook |
| Your church online | Event Calendar · Upcoming Events · Prayer Request Form · Kids Check-in · Member Sign-up · Membership Form · Donation Form · NTag landing page |
| Running the system | Role Assignment · Add User · Temporary Access · Private Page Access · Link Accounts · Church Settings · Favorites |
| Help when you need it | Help Center · Ticketing |

Nothing from the original brief is omitted: 13 demonstrated on screen, 21 named in the roll.

## The closing flyer

`assets/churchgeniuspro-flyer.png`, 1920x1080, rendered from HTML — **not** generated by Flow.
Generative video garbles small text, and a flyer with a misspelt web address or a wrong price
would undo the video. Prices verified against churchgeniuspro.com on 2026-10-09: Free $0,
Standard $20, Pro $40. Plan names match the website, not the brief's informal labels.
