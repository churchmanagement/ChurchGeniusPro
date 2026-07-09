# Attendance Module — Phase 1

A new General-section page at **`/attendance`** with three tabs: Dashboard, Check-In, Records.

## What's in Phase 1

**Dashboard** — six live stats (this week's attendance, this month's attendance, first-time
visitors, returning visitors, members present, volunteers present) and four Chart.js charts
(weekly trend, monthly trend, attendance by service, attendance by ministry).

**Check-In** — pick a Service/Event, Status (Present/Late/Absent/Excused), optional Ministry
and Campus/Location, then check people in by one of four methods:
- **Manual** — search by name, phone, email, or member ID; check in members (and print their
  attendance QR/barcode code).
- **QR / Barcode** — a USB/Bluetooth scanner field (Enter submits) **and** a device-camera
  scanner (html5-qrcode) that reads Code 128 + QR.
- **Family** — load a family from any member and check in multiple members together.
- **Visitor** — capture name, phone, email, address, invited-by; classifies First/Second/Returning.

**Records** — searchable grid with filters (date range, service, status, ministry; member/family
filters available via the API), inline Edit, Delete, and **CSV / Excel / PDF export**.

**Reports** — a Reports tab with: **Member Attendance** (attendance %, attended/total days, last
attendance date, consecutive absences), **Family Attendance** (check-ins, members attended, last
date), **Ministry** and **Events** (counts + bar chart), **Visitors** (first-time, returning, and
follow-up lists), and **Trends** (weekly / monthly / yearly charts). Every report table has a
one-click **CSV export**. Endpoints: `GET /api/attendance/reports/{members|families|ministry|events|visitors}`
and `GET /api/attendance/trends/{weekly|monthly|yearly}`.

Each member gets a stable, unique **attendance code** (QR + Code 128) generated on demand;
scanning it marks attendance. Service/Event types are seeded (Sunday Worship, Bible Study,
Prayer Meeting, Youth, Children's, Choir, Volunteer Activities, Custom Event) and admins can add
more via the API; statuses are Present/Late/Absent/Excused. Campus/Location is a single free-text
field per record (single-campus model).

## Captured per record

Attendance date, check-in time, optional check-out time, member/visitor id, person name,
service/event, ministry, campus/location, status, and check-in method.

## Files

**Backend** (`com.churchgeniuspro`):
- `hibernate/AttendanceRecord.java`, `AttendanceVisitor.java`, `AttendanceServiceType.java`, `MemberAttendanceCode.java`
- `repository/Attendance*Repository.java`, `MemberAttendanceCodeRepository.java`
- `service/AttendanceService.java` (seed types, member code, search/scan, check-in/family/visitor, dashboard, trends, records query, CSV/XLSX/PDF export)
- `controller/AttendanceController.java` (REST) + `controller/AttendancePageController.java` (`/attendance` page route, gated `requireAdminOrUser` + `requirePermission("general.attendance")`)

**Frontend** (`src/main/resources/static`): `attendance.html`; nav item added under General in `shell.js`.

Tables auto-create on next start (Hibernate `ddl-auto=update`).

## Key endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/attendance/service-types` (+POST) | list/seed/add service types |
| GET | `/api/attendance/statuses` | status list |
| GET | `/api/attendance/dashboard` | six stats |
| GET | `/api/attendance/trends/{weekly\|monthly\|by-service\|by-ministry}` | chart data |
| GET | `/api/attendance/search?q=` | members + visitors |
| GET | `/api/attendance/scan?code=` | resolve a scanned member code |
| GET | `/api/attendance/member-code/{memberId}` | get/create a member's code |
| GET | `/api/attendance/family/{familyId}/members` | family members |
| POST | `/api/attendance/check-in` | member check-in (manual/QR/barcode) |
| POST | `/api/attendance/family-check-in` | multiple family members |
| POST | `/api/attendance/visitor-check-in` | visitor check-in |
| POST | `/api/attendance/{id}/check-out` | stamp check-out |
| GET/PUT/DELETE | `/api/attendance/records[/{id}]` | grid + edit + delete |
| GET | `/api/attendance/records/export?format=csv\|xlsx\|pdf` | export |

## Build / run

```
./mvnw clean package -DskipTests && ./mvnw spring-boot:run
```

Open `/attendance` as a staff user (Admin/User/SuperAdmin).

## Deferred to later phases (per agreed plan)

- A dedicated **Visitor management** screen (the Reports → Visitors tab already lists first-time /
  returning / follow-up; a full editable visitor profile screen is later).
- The **Attendance Settings** screen (manage statuses, service/event categories, QR/barcode
  settings, reminders). The data model (configurable service types) is already in place and types
  can be added via `POST /api/attendance/service-types`.
- Camera check-in requires HTTPS or localhost (browser constraint); the USB-scanner field works anywhere.
