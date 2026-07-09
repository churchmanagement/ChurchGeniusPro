package com.churchgeniuspro.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    // ── Members ───────────────────────────────────────────────────────────
    // NOTE: GET /api/members is handled by FamilyController (real data).

    @GetMapping("/members/facility")
    public Map<String, Object> membersFacility() {
        return response("Facility Members", List.of(
                member(1, "John Smith",   "Main Hall",    "Active"),
                member(2, "Mary Johnson", "Chapel",       "Active"),
                member(3, "Paul Adams",   "Youth Center", "Inactive")
        ));
    }

    @GetMapping("/members/online")
    public Map<String, Object> membersOnline() {
        return response("Online Members", Map.of(
                "currentlyOnline", 47,
                "peakToday", 134,
                "totalStreams", 89,
                "avgWatchTime", "42 min"
        ));
    }

    @GetMapping("/members/templates")
    public Map<String, Object> membersTemplates() {
        return response("Member Templates", List.of(
                template(1, "Welcome Letter",      "Email",   "Active"),
                template(2, "Membership Card",     "PDF",     "Active"),
                template(3, "Renewal Reminder",    "Email",   "Active"),
                template(4, "Visitor Follow-up",   "Email",   "Draft")
        ));
    }

    // ── Activities ────────────────────────────────────────────────────────

    @GetMapping("/activities")
    public Map<String, Object> activities() {
        return response("Activities", Map.of(
                "totalEvents", 15,
                "upcomingThisWeek", 4,
                "completedThisMonth", 8
        ));
    }

    @GetMapping("/activities/meetings")
    public Map<String, Object> meetings() {
        return response("Meetings", List.of(
                meeting(1, "Board Meeting",      "2026-03-15", "09:00 AM", "Conference Room A"),
                meeting(2, "Worship Planning",   "2026-03-17", "06:00 PM", "Sanctuary"),
                meeting(3, "Youth Leaders",      "2026-03-19", "07:00 PM", "Youth Hall"),
                meeting(4, "Finance Committee",  "2026-03-22", "10:00 AM", "Admin Office")
        ));
    }

    // ── Accounting ────────────────────────────────────────────────────────

    @GetMapping("/accounting")
    public Map<String, Object> accounting() {
        return response("Accounting Summary", Map.of(
                "totalIncome",  "$ 125,400",
                "totalExpense", "$ 98,700",
                "netBalance",   "$ 26,700",
                "currency",     "USD"
        ));
    }

    @GetMapping("/accounting/income")
    public Map<String, Object> accountingIncome() {
        return response("Income", Map.of(
                "year", 2026,
                "totalIncome", 125400,
                "categories", List.of(
                        entry("Tithes",    56430),
                        entry("Offerings", 37620),
                        entry("Donations", 18810),
                        entry("Other",     12540)
                )
        ));
    }

    @GetMapping("/accounting/expense")
    public Map<String, Object> accountingExpense() {
        return response("Expenses", Map.of(
                "year", 2026,
                "totalExpense", 98700,
                "categories", List.of(
                        entry("Staff",      39480),
                        entry("Facilities", 24675),
                        entry("Programs",   19740),
                        entry("Admin",      14805)
                )
        ));
    }

    // ── Other pages ───────────────────────────────────────────────────────

    @GetMapping("/dashboard/notes")
    public Map<String, Object> notes() {
        return response("Notes", List.of(
                note(1, "Sermon series planning for Q2",   "Pastor Mike",  "2026-03-01"),
                note(2, "Sound system maintenance needed", "Tech Team",    "2026-03-04"),
                note(3, "Easter event ideas",              "Events Board", "2026-03-05")
        ));
    }

    @GetMapping("/dashboard/files")
    public Map<String, Object> files() {
        return response("Files", List.of(
                file(1, "Annual Report 2025.pdf",    "PDF",  "2.4 MB", "2026-01-10"),
                file(2, "Budget Forecast 2026.xlsx", "XLSX", "1.1 MB", "2026-02-14"),
                file(3, "Membership Directory.docx", "DOCX", "540 KB", "2026-03-01"),
                file(4, "Facility Layout.png",       "PNG",  "3.2 MB", "2026-02-20")
        ));
    }

    @GetMapping("/quicksend")
    public Map<String, Object> quickSend() {
        return response("Quick Send", Map.of(
                "lastSent",       "Sunday Bulletin — 2026-03-08",
                "totalSentMonth", 14,
                "recipients",     312,
                "openRate",       "68%"
        ));
    }

    @GetMapping("/reminders")
    public Map<String, Object> reminders() {
        return response("Reminder Settings", Map.of(
                "emailReminders",    true,
                "smsReminders",      false,
                "reminderLeadDays",  3,
                "birthdayGreeting",  true,
                "anniversaryAlert",  true,
                "weeklyDigest",      "Sunday 08:00 AM"
        ));
    }

    // ── Builders ──────────────────────────────────────────────────────────

    private Map<String, Object> response(String page, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("page",   page);
        m.put("status", "ok");
        m.put("data",   data);
        return m;
    }

    private Map<String, Object> member(int id, String name, String location, String status) {
        return Map.of("id", id, "name", name, "location", location, "status", status);
    }

    private Map<String, Object> template(int id, String name, String type, String status) {
        return Map.of("id", id, "name", name, "type", type, "status", status);
    }

    private Map<String, Object> meeting(int id, String title, String date, String time, String room) {
        return Map.of("id", id, "title", title, "date", date, "time", time, "room", room);
    }

    private Map<String, Object> entry(String label, int amount) {
        return Map.of("category", label, "amount", amount);
    }

    private Map<String, Object> note(int id, String text, String author, String date) {
        return Map.of("id", id, "note", text, "author", author, "date", date);
    }

    private Map<String, Object> file(int id, String name, String type, String size, String date) {
        return Map.of("id", id, "name", name, "type", type, "size", size, "uploaded", date);
    }

}
