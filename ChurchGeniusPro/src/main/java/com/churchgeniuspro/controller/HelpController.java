package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.HelpAssistantService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
public class HelpController {

    private final HelpAssistantService helpAssistant;

    public HelpController(HelpAssistantService helpAssistant) {
        this.helpAssistant = helpAssistant;
    }

    // ── Session helpers ─────────────────────────────────────────────────────

    private String sa(HttpServletRequest req, String key) {
        HttpSession s = req.getSession(false);
        Object v = s == null ? null : s.getAttribute(key);
        return v == null ? null : String.valueOf(v);
    }
    private boolean isChurch(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && Boolean.TRUE.equals(s.getAttribute("church"));
    }
    private String clientId(HttpServletRequest req) {
        String c = sa(req, "appClientId");
        return c != null ? c : sa(req, "clientId");
    }
    private boolean noSession(HttpServletRequest req) {
        return sa(req, "username") == null && clientId(req) == null;
    }

    // ── Page routes ───────────────────────────────────────────────────────

    @GetMapping("/help")
    public String helpPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        return "forward:/help.html";
    }

    @GetMapping("/helpCenter")
    public String helpCenterPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "more.helpcenter");
        if (deny != null) return deny;
        return "forward:/helpCenter.html";
    }

    // ── Help search API ───────────────────────────────────────────────────

    @PostMapping("/api/help/search")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> helpSearch(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {

        var session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) {
            return ResponseEntity.status(401).build();
        }
        String query = body != null ? body.getOrDefault("query", "") : "";
        Map<String, Object> result = matchHelpEntry(query.toLowerCase().trim());
        return ResponseEntity.ok(result);
    }

    // ── Permission-aware Help articles + AI assistant ───────────────────────

    /** Articles the current user is allowed to see (for the article list + search). */
    @GetMapping("/api/help/articles")
    @ResponseBody
    public ResponseEntity<?> listArticles(HttpServletRequest req) {
        if (noSession(req)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(helpAssistant.articles(sa(req, "privileges"), sa(req, "role"), isChurch(req)));
    }

    /** Full article if permitted; otherwise a permission-denied message (both audited). */
    @GetMapping("/api/help/articles/{id}")
    @ResponseBody
    public ResponseEntity<?> getArticle(@PathVariable String id, HttpServletRequest req) {
        if (noSession(req)) return ResponseEntity.status(401).build();
        Map<String, Object> m = helpAssistant.article(
                sa(req, "privileges"), sa(req, "role"), isChurch(req), id, clientId(req), sa(req, "username"));
        if (m == null) return ResponseEntity.notFound().build();
        if (Boolean.TRUE.equals(m.get("denied"))) return ResponseEntity.status(403).body(m);
        return ResponseEntity.ok(m);
    }

    /** Conversational/troubleshooting answer, scoped to the user's permitted features. */
    @PostMapping("/api/help/assist")
    @ResponseBody
    public ResponseEntity<?> assist(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        if (noSession(req)) return ResponseEntity.status(401).build();
        String q = body == null ? "" : String.valueOf(body.getOrDefault("query", body.getOrDefault("question", "")));
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Empty question."));
        String history = body == null || body.get("history") == null ? null : String.valueOf(body.get("history"));
        boolean voice = body != null && Boolean.TRUE.equals(body.get("voice"));
        Map<String, Object> m = helpAssistant.assist(
                sa(req, "privileges"), sa(req, "role"), isChurch(req), q.trim(), history, voice,
                clientId(req), sa(req, "username"));
        return ResponseEntity.ok(m);
    }

    /** Recent Help-assistant audit (administrators only). */
    @GetMapping("/api/help/audit")
    @ResponseBody
    public ResponseEntity<?> audit(@RequestParam(defaultValue = "100") int limit, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Administrators only."));
        return ResponseEntity.ok(helpAssistant.recentAudit(clientId(req), limit));
    }

    // ── Knowledge base ────────────────────────────────────────────────────

    private static final List<HelpEntry> KB = buildKb();

    private Map<String, Object> matchHelpEntry(String q) {
        if (q.isBlank()) return noMatch(q);
        HelpEntry best = null;
        int bestScore = 0;
        for (HelpEntry e : KB) {
            int s = e.score(q);
            if (s > bestScore) { bestScore = s; best = e; }
        }
        if (best == null || bestScore < 2) return noMatch(q);
        return best.toMap();
    }

    private Map<String, Object> noMatch(String q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent",  "help_nomatch");
        m.put("title",   "Help Center");
        m.put("answer",  "I couldn't find a specific help article for that query. Try the Help Center for step-by-step guides on every module.");
        m.put("links",   List.of(lnk("Help Center", "/helpCenter", "Browse all help articles and guides", "📚")));
        m.put("steps",   List.of());
        m.put("related", List.of());
        return m;
    }

    // ── Knowledge-base builder ────────────────────────────────────────────

    private static List<HelpEntry> buildKb() {
        var kb = new ArrayList<HelpEntry>();

        kb.add(new HelpEntry("add_child",
            new String[]{"add child","register child","new child","enroll child","child registration","how do i add a child","how to register a child"},
            "Register a Child",
            "Children are registered in Kids Ministry. Navigate to General → Ministry → Kids Ministry, then the Children tab.",
            new String[]{"Go to General → Ministry → Kids Ministry","Click the Children tab","Click + Add Child","Enter name, date of birth, grade, gender, and parent contact","Click Save"},
            new Map[]{lnk("Kids Ministry", "/ministry", "Register and manage children", "⛪")},
            new String[]{"sunday school class","assign teacher","check-in child"}));

        kb.add(new HelpEntry("create_event",
            new String[]{"create event","add event","new event","make event","how to create event","how do i create an event"},
            "Create an Event",
            "Events are managed under General → Events. Create multi-day events, enable QR check-in, collect registrations, and assign volunteers.",
            new String[]{"Go to General → Events","Click + New Event","Enter event name, date, time, and location","Enable Show Registrants if tracking attendance","Optionally enable QR Code for self check-in","Click Save"},
            new Map[]{lnk("Events", "/events", "View and manage all events", "🎉"), lnk("Calendar", "/eventcalendar", "View event calendar", "📆")},
            new String[]{"assign volunteers","event reminder","qr code check-in"}));

        kb.add(new HelpEntry("assign_volunteers",
            new String[]{"assign volunteer","add volunteer","volunteer assignment","how to volunteer","volunteer role","how do i assign volunteers"},
            "Assign Volunteers to an Event",
            "Volunteer roles are managed per event. Open the event, go to the Volunteers tab, create roles, then assign members.",
            new String[]{"Go to General → Events and open your event","Click the Volunteers tab","Click + Add Role to define a volunteer position (e.g. Usher, Sound)","Click + Assign next to the role and search for a member","The member receives an email notification of their assignment"},
            new Map[]{lnk("Events", "/events", "Open events to assign volunteers", "🎉")},
            new String[]{"create event","worship planning"}));

        kb.add(new HelpEntry("view_contributions",
            new String[]{"view contributions","see contributions","where is income","member giving","tithes","offering","find contribution","how do i view contributions"},
            "View Contributions",
            "Contributions (income) are recorded and viewed under Accounting → Income. Filter by member, date range, or fund.",
            new String[]{"Go to Accounting → Income","Use the search/filter bar to find contributions by member name, date, or fund","Click a row to see full details"},
            new Map[]{lnk("Income", "/income", "View and record income", "💰"), lnk("Reports", "/accountingReports", "View accounting reports", "📊")},
            new String[]{"record contribution","pledge campaign","tax report"}));

        kb.add(new HelpEntry("record_contribution",
            new String[]{"record contribution","add income","add contribution","tithe entry","record tithe","record offering","how do i record a contribution"},
            "Record a Contribution",
            "Go to Accounting → Income to add a new contribution for any member.",
            new String[]{"Go to Accounting → Income","Click + Add Income","Select the member, fund, and transaction type","Enter the amount and date","Add any notes and click Save"},
            new Map[]{lnk("Income", "/income", "Record income / contributions", "💰")},
            new String[]{"view contributions","pledge campaign"}));

        kb.add(new HelpEntry("pledge_campaign",
            new String[]{"pledge campaign","create pledge","add pledge","pledge","fundraising campaign","how do i create a pledge"},
            "Create a Pledge Campaign",
            "Pledge campaigns track member commitments toward a fundraising goal. Manage them under Accounting → Pledges.",
            new String[]{"Go to Accounting → Pledges","Click + New Campaign","Enter campaign name, target amount, fund, and end date","Add members as pledgers and enter pledge amounts","Track fulfillment as contributions come in"},
            new Map[]{lnk("Pledges", "/pledges", "Manage pledge campaigns", "🤝")},
            new String[]{"view contributions","income report"}));

        kb.add(new HelpEntry("add_member",
            new String[]{"add member","new member","register member","create member","how to add member","add a person","how do i add a member"},
            "Add a Church Member",
            "Members are added within families. Go to Admin → Families, open or create a family, then add the member.",
            new String[]{"Go to Admin → Families","Click + Add Family (or open an existing family)","Click + Add Member inside the family","Enter First Name, Last Name, Role, Email, Phone, and Birthday","Click Save"},
            new Map[]{lnk("Families", "/viewfamily", "View all families", "👨‍👩‍👧"), lnk("Add Family", "/family", "Create a new family", "➕")},
            new String[]{"search member","member portal"}));

        kb.add(new HelpEntry("sunday_school_class",
            new String[]{"sunday school class","create class","ss class","setup class","new class","how to setup sunday school","how do i create a class"},
            "Set Up a Sunday School Class",
            "Sunday School classes are managed under Kids Ministry. Create a class, add a teacher, enroll students, then publish exams.",
            new String[]{"Go to General → Ministry → Kids Ministry","Click the Sunday School tab","Click + New Class and enter the class name","Click + Add Teacher and link to a member record","Click + Add Student to enroll children","Create exams and set status to Published"},
            new Map[]{lnk("Sunday School", "/sundaySchool", "Manage Sunday School", "📖"), lnk("Kids Ministry", "/ministry", "Kids Ministry hub", "⛪")},
            new String[]{"assign teacher","add child","exam grading"}));

        kb.add(new HelpEntry("assign_teacher",
            new String[]{"assign teacher","add teacher","link teacher","teacher assignment","how do i add a teacher","how to assign teacher"},
            "Assign a Teacher to a Sunday School Class",
            "Teachers are added per class inside Sunday School. Link them to a member record so they can view the class in the Member Portal.",
            new String[]{"Go to Kids Ministry → Sunday School","Open the class","Click + Add Teacher","Enter the teacher's name and email","Optionally link to a member using their Member Ref","The teacher will see the class under MY PROFILE → Classes"},
            new Map[]{lnk("Sunday School", "/sundaySchool", "Sunday School management", "📖")},
            new String[]{"sunday school class","member portal classes"}));

        kb.add(new HelpEntry("check_in_child",
            new String[]{"check in","check-in child","children check in","kids checkin","check in kids","how do i check in a child"},
            "Check In a Child",
            "Use the Kids Ministry Check-In screen to check children in and out by name search or parent QR code.",
            new String[]{"Go to General → Ministry → Kids Ministry","Click the Check-In tab","Search for the child by name or scan the parent's QR code","Click Check-In","At pickup, search again and click Check-Out"},
            new Map[]{lnk("Kids Ministry", "/ministry", "Kids Ministry check-in", "⛪")},
            new String[]{"register child","authorized pickup"}));

        kb.add(new HelpEntry("tax_report",
            new String[]{"tax report","year end report","annual report","donor statement","generate tax","how do i generate a tax report"},
            "Generate a Year-End Tax Report",
            "Year-end tax reports (donor acknowledgement letters) are generated under Accounting Reports → Tax Report.",
            new String[]{"Go to Accounting → Reports → Tax Report","Select the tax year","Click Generate","Download or print individual donor statements"},
            new Map[]{lnk("Tax Report", "/tax-report", "Generate year-end tax reports", "🧾"), lnk("Reports", "/accountingReports", "All accounting reports", "📊")},
            new String[]{"view contributions","income report"}));

        kb.add(new HelpEntry("bulk_email",
            new String[]{"send email","bulk email","email members","notify members","compose email","how do i send an email","how to email"},
            "Send a Bulk Email",
            "Use General → Compose Email to send emails to individuals, groups, or your entire congregation.",
            new String[]{"Go to General → Compose Email","Select recipients: All Members, a Group, or individual members","Enter the subject and compose your message","Attach files if needed","Click Send"},
            new Map[]{lnk("Compose Email", "/notifyEmail", "Send bulk emails", "✉️")},
            new String[]{"email settings","group email","reminders"}));

        kb.add(new HelpEntry("event_reminder",
            new String[]{"event reminder","set reminder","reminder","automatic reminder","auto reminder","notify before event","how do i set a reminder"},
            "Set Up an Event Reminder",
            "Event reminders automatically email attendees before an event. Set them up under General → Reminders.",
            new String[]{"Go to General → Reminders → Event Reminders","Click + New Reminder","Select the event and enter how many hours/days before to send","Compose the email subject and body","Click Save — it will send automatically at the scheduled time"},
            new Map[]{lnk("Event Reminders", "/eventReminders", "Manage event reminders", "🔔")},
            new String[]{"create event","email settings"}));

        kb.add(new HelpEntry("add_user",
            new String[]{"add user","create user","add staff","new staff account","how do i add a user","how to create a user"},
            "Add a Staff User",
            "Staff accounts are managed under Admin → View Users. Assign roles to control access.",
            new String[]{"Go to Admin → View Users","Click + Add User","Enter name, email, and select a role (SuperAdmin / Admin / Accountant / User)","Set a temporary password","Click Save"},
            new Map[]{lnk("View Users", "/viewusers", "Manage staff accounts", "🧑‍💼")},
            new String[]{"roles permissions","reset password"}));

        kb.add(new HelpEntry("create_group",
            new String[]{"create group","add group","new group","manage group","how to create a group","how do i create a group"},
            "Create a Group",
            "Groups organize members into teams, ministries, or small groups. Manage them under Admin → Groups.",
            new String[]{"Go to Admin → Groups","Click + New Group","Enter the group name and description","Click Add Members to search and add church members","Use Send Email to communicate with the group"},
            new Map[]{lnk("Groups", "/groups", "Manage groups", "👥")},
            new String[]{"group email","ministry team"}));

        kb.add(new HelpEntry("help_center",
            new String[]{"help","help center","guide","documentation","how to use","tutorial","instructions","how do i find","navigate","where do i go","where is","where can i"},
            "Help Center",
            "The Help Center has step-by-step guides, FAQs, and navigation links for every module in Church Genius Pro.",
            new String[]{"Click More → Help Center in the sidebar","Browse modules by category using the filter pills","Click on a module card to expand step-by-step instructions","Use the search bar to find specific answers instantly"},
            new Map[]{lnk("Help Center", "/helpCenter", "Full Help Center with all guides", "📚")},
            new String[]{"add member","create event","view contributions"}));

        return kb;
    }

    private static Map<String, String> lnk(String label, String url, String desc, String icon) {
        var m = new LinkedHashMap<String, String>();
        m.put("label", label); m.put("url", url);
        m.put("description", desc); m.put("icon", icon);
        return m;
    }

    // ── HelpEntry ─────────────────────────────────────────────────────────

    private static class HelpEntry {
        final String id;
        final String[] keywords;
        final String title;
        final String answer;
        final String[] steps;
        final Map<String, String>[] links;
        final String[] related;

        @SuppressWarnings("unchecked")
        HelpEntry(String id, String[] keywords, String title, String answer,
                  String[] steps, Map[] links, String[] related) {
            this.id = id; this.keywords = keywords; this.title = title;
            this.answer = answer; this.steps = steps;
            this.links = (Map<String, String>[]) links;
            this.related = related;
        }

        int score(String q) {
            int s = 0;
            for (String kw : keywords) {
                if (q.contains(kw))      { s += 10; continue; }
                if (kw.contains(q))      { s += 6;  continue; }
                String[] qw = q.split("\\s+");
                String[] kw2 = kw.split("\\s+");
                for (String w : qw) {
                    if (w.length() < 3) continue;
                    for (String k : kw2) { if (k.contains(w) || w.contains(k)) s += 2; }
                }
            }
            return s;
        }

        Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("intent",  "help");
            m.put("id",      id);
            m.put("title",   title);
            m.put("answer",  answer);
            m.put("steps",   Arrays.asList(steps));
            m.put("links",   Arrays.asList(links));
            m.put("related", Arrays.asList(related));
            return m;
        }
    }
}
