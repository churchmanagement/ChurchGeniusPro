package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.service.AiAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-11-11 — things Genius Assistant does not do in the conversation but a page does: adding a family, and
 * anything to do with Bank Import, Bank Sync, Payroll, Events, Ministry, Certificates, Public Screens, Ticketing
 * and Account &amp; Privacy. A request to DO one of these ("add a family", "run payroll", "create an event",
 * "import a bank statement") is answered with one sentence and a link to the page — and the link only when this
 * login may open that page.
 *
 * <p>The access answer is {@link AiAccessPolicy#navAccess} — the same map the side menu is built from (the
 * route's role guard, the page permission with the main-menu rule, the plan feature) — so the assistant never
 * offers a page that would answer Access Denied. Adding a family follows the user's rule: the link to the Add
 * Family page is shown when the login may open the Families page. Nothing is read or written here.
 */
@Service
public class AiPageLinkService {

    private final AiAccessPolicy access;

    public AiPageLinkService(AiAccessPolicy access) { this.access = access; }

    /** One linked area: how it is recognised, the path whose access decides, the page opened, and the wording. */
    record Target(String key, Pattern words, String accessPath, String url, String label, String page, String can) {}

    private static final String VERBS = "create|add|make|new|set\\s?up|start|register|enter|record|run|do|process|generate|print|issue|import|upload|sync|synch?roni[sz]e|connect|link|open\\s+a|raise|submit|log|file|report|"
            + "manage|configure|delete|remove|close|edit|change|update|schedule|plan|organi[sz]e|publish|share|download|export";
    private static final String LEAD = "(?:please\\s+|can you\\s+|could you\\s+|can i\\s+|i\\s+(?:want|need|would like)\\s+to\\s+|i'?d like to\\s+|let'?s\\s+|help me\\s+(?:to\\s+)?)?";   // "how do I …" stays a help question
    private static final String ART = "(?:an?\\s+|the\\s+|my\\s+|our\\s+|another\\s+|some\\s+|this\\s+)?(?:new\\s+)?";

    private static Pattern req(String target) {
        return Pattern.compile("(?i)^\\s*" + LEAD + "(?:" + VERBS + ")\\s+" + ART + "(?:" + target + ")\\b");
    }

    static final List<Target> TARGETS = List.of(
        new Target("family", req("famil(?:y|ies)|households?|(?:church\\s+)?members?(?!\\s+(?:to|into|in)\\b)(?!\\s+portal)"),
            "/viewfamily", "/family", "Add a family", "Add Family",
            "A family (and its members) is added on the Add Family page, where the full form — address, contact details, each family member — is filled in."),
        new Target("bankImport", req("bank\\s+(?:statement\\s+)?imports?|bank\\s+statements?|statement\\s+import|(?:csv|ofx|qfx)\\s+(?:file|import)"),
            "/bank-import", "/bank-import", "Open Bank Import", "Bank Import",
            "Bank statements are imported and reviewed line by line on the Bank Import page."),
        new Target("bankSync", req("bank\\s+sync|bank\\s+(?:account\\s+)?(?:connection|link)s?|bank\\s+accounts?|bank\\s+(?:feed|transactions?)|plaid|banks?"),
            "/bankSync", "/bankSync", "Open Bank Sync", "Bank Sync",
            "Bank accounts are connected and synchronised on the Bank Sync page."),
        new Target("payroll", req("payroll|pay\\s?runs?|pay\\s?checks?|pay\\s?stubs?|employees?|w-?2s?|salary|salaries|wages?"),
            "/payroll", "/payroll", "Open Payroll", "Payroll",
            "Employees, pay runs and pay stubs are handled on the Payroll page."),
        new Target("events", req("(?:church\\s+)?events?(?!\\s+(?:reminders?|calendar|check-?in))|event\\s+registrations?|registration\\s+forms?"),
            "/events", "/events", "Open Events", "Events",
            "Events — with registration, fees, check-in and reminders — are created on the Events page. Its “Describe your event” button can fill the form for you."),
        new Target("ministry", req("ministr(?:y|ies)(?!\\s+report)|kids\\s+ministry|children'?s?\\s+ministry|sunday\\s+school(?:\\s+class(?:es)?)?|class(?:es)?|child(?:ren)?(?:'s)?\\s+registrations?"),
            "/ministry", "/ministry", "Open Ministry", "Ministry",
            "Kids Ministry and Sunday School are managed from the Ministry page."),
        new Target("certificates", req("certificates?|baptism\\s+certificates?|membership\\s+certificates?"),
            "/certificates", "/certificates", "Open Certificates", "Certificates",
            "Certificates are generated and printed on the Certificates page."),
        new Target("publicScreens", req("public\\s+(?:screens?|links?|pages?)|public\\s+calendar|donation\\s+(?:page|link)|giving\\s+(?:page|link)|sign-?up\\s+link"),
            "/publicScreens", "/publicScreens", "Open Public Screens", "Public Screens",
            "Public links — calendar, prayer requests, kids check-in, membership form, donation page — are set up on the Public Screens page."),
        new Target("tickets", req("(?:support\\s+)?tickets?|support\\s+requests?|bug(?:\\s+report)?|problem|issue"),
            "/tickets", "/tickets", "Open Ticketing", "Ticketing",
            "Support tickets to the ChurchGeniusPro team are created on the Ticketing page."),
        new Target("accountPrivacy", req("(?:my\\s+)?(?:account|login)(?:\\s+(?:deletion|and\\s+privacy|privacy))?(?!\\s+settings)|account\\s*(?:&|and)\\s*privacy|privacy(?:\\s+(?:settings|policy))?|church\\s+account"),
            "/accountPrivacy", "/accountPrivacy", "Open Account & Privacy", "Account & Privacy",
            "Privacy links and deleting a login or the church account are on the Account & Privacy page.")
    );

    /** Words that mean the request belongs to a conversation Genius CAN carry out — never redirected. */
    private static final Pattern NOT_OURS = Pattern.compile("(?i)\\b(meeting|income|expense|donation|offering|tithe|pledge|campaign|group|e-?mail|follow-?up|note|fund|purpose|transaction\\s+type|payment\\s+method|reminder)\\b");

    public static boolean isLinkRequest(String q) { return q != null && target(q) != null; }

    static Target target(String q) {
        for (Target t : TARGETS) {
            Matcher m = t.words().matcher(q);
            if (!m.find()) continue;
            // "add a member to the Youth group", "add an event reminder", "create an account settings fund" belong elsewhere
            String head = q.substring(0, m.end()), tail = q.substring(m.end());
            if (NOT_OURS.matcher(head).find()) continue;
            if (t.key().equals("family") && NOT_OURS.matcher(tail).find() && tail.matches("(?i)^\\s+(to|into|in|for)\\b.*")) continue;
            if (t.key().equals("tickets") && !q.toLowerCase(java.util.Locale.ROOT).matches(".*\\b(ticket|support|bug|churchgeniuspro|software|app|system|page|screen|error)\\b.*")) continue;
            return t;
        }
        return null;
    }

    /**
     * Staff logins: the side menu's own answer. The church owner login has no permission map (its menu is built
     * from the catalogue's "church" flag), so that flag decides for it. Every other session gets no link.
     */
    private boolean mayOpen(HttpServletRequest req, HttpSession session, String clientId, String path) {
        Object ch = session.getAttribute("church");
        if (Boolean.TRUE.equals(ch) || "true".equalsIgnoreCase(String.valueOf(ch))) {
            for (com.churchgeniuspro.service.AiNavigationCatalog.Page p : com.churchgeniuspro.service.AiNavigationCatalog.PAGES)
                if (p.url().equals(path) && p.rule().memberKey() == null) return p.rule().church();
            return false;
        }
        return Boolean.TRUE.equals(access.navAccess(req, clientId).get(path));
    }

    /** Null = not one of these requests. {@code draftOpen}: another conversation is waiting — it is kept. */
    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question, boolean draftOpen) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        Target t = target(question.trim());
        if (t == null) return null;
        boolean open = mayOpen(req, session, clientId, t.accessPath());
        String answer = open
            ? t.can() + " I can't do that from this chat, so here is the page."
            : t.can() + " Your login doesn't have access to " + ("family".equals(t.key()) ? "the Families pages" : "the " + t.page() + " page")
              + ", so please ask your church administrator.";
        Map<String, Object> out = AiGuidedRecordService.base("page.link", answer, t.page());
        out.put("readOnly", true); out.put("page", t.key());
        if (open) out.put("link", new LinkedHashMap<>(Map.of("label", t.label(), "url", t.url())));
        else out.put("noAccess", true);
        if (draftOpen) {
            out.put("answer", answer + " Your unsaved entry here is still open — answer the last question to carry on, or say “cancel”.");
            out.put("pending", true);
        }
        return out;
    }
}
