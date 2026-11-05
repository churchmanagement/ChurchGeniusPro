package com.churchgeniuspro.workflow;

import com.churchgeniuspro.ai.write.AiWritingService;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.Note;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.NoteRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.WebPushService;
import com.churchgeniuspro.workflow.WorkflowSchema.Action;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Executes the closed set of workflow actions (AI Phase 6) — each a thin call into an existing
 * repository or service, under the rule OWNER's permissions, inside the engine's transaction.
 *
 * <p>Every recipient is a staff login of the same church (the owner or an {@code app_user} id);
 * members, visitors and donors are never messaged by a rule (plan: staff-facing actions only in v1).
 * {@link #check} is the permission gate the engine runs BEFORE anything executes: one refused action
 * refuses the whole run.
 */
@Component
public class WorkflowActions {

    public static final ZoneId CHURCH_ZONE = ZoneId.of("America/Chicago");

    private final FollowUpRepository followUps;
    private final NoteRepository notes;
    private final FamilyMemberRepository members;
    private final AppUserRepository users;
    private final WebPushService push;
    private final EmailService email;
    private final AiWritingService writing;
    private final AiAccessPolicy access;

    @Autowired
    public WorkflowActions(FollowUpRepository followUps, NoteRepository notes, FamilyMemberRepository members, AppUserRepository users,
                           @Autowired(required = false) WebPushService push, @Autowired(required = false) EmailService email,
                           @Autowired(required = false) AiWritingService writing, AiAccessPolicy access) {
        this.followUps = followUps; this.notes = notes; this.members = members; this.users = users;
        this.push = push; this.email = email; this.writing = writing; this.access = access;
    }

    /** Everything one execution needs. */
    public record Ctx(String cid, WorkflowEvent event, AppUser owner, String ownerUsername, AiAccess ownerAccess, Long definitionId, boolean dryRun) {}

    /** Null when the owner may run this action now, else the reason. */
    public String check(Ctx c, Action a) {
        WorkflowSchema.ActionType t = WorkflowSchema.ACTIONS.get(a.type());
        if (t == null) return "Unknown action " + a.type() + ".";
        AiAccess ac = c.ownerAccess();
        if (ac == null || ac.member() || ac.church() || ac.kids()) return "The rule owner is not a staff login.";
        if ("followUps".equals(t.needs()) && !ac.followUps()) return "The rule owner no longer has the Follow-ups page.";
        if ("members".equals(t.needs()) && !ac.members()) return "The rule owner no longer has the Families page.";
        if ("draft_email".equals(a.type()) && !access.feature(c.cid(), "aiWrite")) return "AI Writing is not part of this church's plan.";
        if (("notify_user".equals(a.type()) || "email_staff".equals(a.type()) || "create_follow_up".equals(a.type())) && a.param(recipientKey(a)) != null && !a.param(recipientKey(a)).isBlank()
                && !"owner".equalsIgnoreCase(a.param(recipientKey(a)).trim()) && recipient(c, a.param(recipientKey(a))) == null)
            return "The staff login named in " + a.type() + " is not an active staff account of this church.";
        return null;
    }

    private static String recipientKey(Action a) { return "create_follow_up".equals(a.type()) ? "assignTo" : "to"; }

    /** The owner, or an enabled staff login of the church by app_user id or username — never anyone else. */
    AppUser recipient(Ctx c, String to) {
        if (to == null || to.isBlank() || "owner".equalsIgnoreCase(to.trim())) return c.owner();
        String t = to.trim();
        AppUser u = null;
        try { u = users.findByIdAndClientId(Integer.parseInt(t), c.cid()).orElse(null); } catch (NumberFormatException ignore) { /* not an id */ }
        if (u == null) {
            for (AppUser x : users.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(c.cid()))
                if (t.equalsIgnoreCase(x.getUserId()) || t.equalsIgnoreCase(x.getEmail())) { u = x; break; }
        }
        if (u == null || u.isDeleteFlag() || !u.isEnabled() || !c.cid().equals(u.getClientId()) || !com.churchgeniuspro.mcp.PersonalAccessTokenService.isStaffRole(u.getRole())) return null;
        return u;
    }

    /** Runs one action; returns a one-line log entry. Throws to fail (and roll back) the run. */
    public String execute(Ctx c, Action a) {
        Map<String, Object> facts = c.event().facts();
        switch (a.type()) {
            case "create_follow_up": return createFollowUp(c, a, facts, WorkflowSchema.fill(a.param("title"), facts), WorkflowSchema.fill(a.param("description"), facts));
            case "notify_user": {
                AppUser to = recipient(c, a.param("to"));
                if (to == null) throw new IllegalStateException("No recipient.");
                String title = cut(WorkflowSchema.fill(a.param("title"), facts), 120), body = cut(WorkflowSchema.fill(a.param("message"), facts), 500);
                if (c.dryRun()) return "Would notify " + display(to) + ": " + title;
                if (push == null) return "Notification skipped: push is not configured on this server.";
                push.logAndSendToUser(to.getUserId(), c.cid(), "staff", title, body, "/followups", "cgp-workflow-" + c.definitionId());
                return "Notified " + display(to) + ": " + title;
            }
            case "email_staff": {
                AppUser to = recipient(c, a.param("to"));
                if (to == null) throw new IllegalStateException("No recipient.");
                if (to.getEmail() == null || to.getEmail().isBlank()) return "E-mail skipped: " + display(to) + " has no e-mail address on file.";
                String subject = cut(WorkflowSchema.fill(a.param("subject"), facts), 200), body = cut(WorkflowSchema.fill(a.param("body"), facts), 4000);
                if (c.dryRun()) return "Would e-mail " + display(to) + ": " + subject;
                if (email == null) return "E-mail skipped: mail is not configured on this server.";
                email.sendOrgEmail(to.getEmail(), subject, "<p>" + esc(body).replace("\n", "<br>") + "</p><p style=\"color:#888;font-size:12px\">Sent by the ChurchGeniusPro automation “" + esc(String.valueOf(facts.getOrDefault("_rule", "rule"))) + "”.</p>", c.cid());
                return "E-mailed " + display(to) + ": " + subject;
            }
            case "add_family_note": {
                Object mid = facts.get("memberId");
                if (mid == null) return "Note skipped: the event names no member.";
                FamilyMember m = members.findByIdAndTenant(((Number) mid).intValue(), c.cid()).orElse(null);
                if (m == null || m.getFamily() == null) return "Note skipped: the member or family was not found in this church.";
                String title = cut(WorkflowSchema.fill(a.param("title"), facts), 200), body = cut(WorkflowSchema.fill(a.param("body"), facts), 4000);
                if (c.dryRun()) return "Would add a note to the " + familyName(m) + " family: " + title;
                Note n = new Note();
                n.setAppClientId(c.cid()); n.setTitle("Family note — " + familyName(m) + ": " + title); n.setBody(body);
                n.setCreatedById(c.owner().getId()); n.setCreatedByName("Automation (" + c.ownerUsername() + ")");
                notes.save(n);
                return "Added a note to the " + familyName(m) + " family: " + title;
            }
            case "draft_email": {
                if (writing == null || !writing.isEnabled()) return "Draft skipped: the AI writing service is not configured on this server.";
                String prompt = cut(WorkflowSchema.fill(a.param("prompt"), facts), 2000);
                String tone = a.param("tone") == null || a.param("tone").isBlank() ? "warm" : a.param("tone").trim().toLowerCase(Locale.ROOT);
                if (c.dryRun()) return "Would draft an e-mail (" + tone + "): " + cut(prompt, 80);
                AiWritingService.Draft d = writing.write(new AiWritingService.Request("draft", "email", prompt, null, tone, "medium", "English", null, true));
                if (d == null) return "Draft skipped: the model did not answer.";
                String subject = d.subject() == null || d.subject().isBlank() ? "Draft e-mail" : d.subject();
                createFollowUp(c, new Action("create_follow_up", Map.of("title", "Draft: " + subject, "assignTo", "owner", "dueInDays", "3")), facts,
                               cut("Draft: " + subject, 200), cut("AI draft — review before sending:\n\n" + d.body(), 4000));
                return "Drafted an e-mail and saved it as a follow-up: " + subject;
            }
            default: throw new IllegalStateException("Unknown action " + a.type());
        }
    }

    private String createFollowUp(Ctx c, Action a, Map<String, Object> facts, String title, String description) {
        AppUser to = recipient(c, a.param("assignTo"));
        if (to == null) throw new IllegalStateException("No assignee.");
        String t = cut(title == null || title.isBlank() ? "Follow up" : title, 200);
        int days = a.param("dueInDays") == null || a.param("dueInDays").isBlank() ? 3 : Integer.parseInt(a.param("dueInDays").trim());
        if (c.dryRun()) return "Would create a follow-up for " + display(to) + " due in " + days + " day(s): " + t;
        FollowUp f = new FollowUp();
        f.setClientId(c.cid()); f.setTitle(t); f.setDescription(description == null ? null : cut(description, 4000));
        f.setStatus("PENDING");
        f.setPriority(a.param("priority") == null || a.param("priority").isBlank() ? "MEDIUM" : a.param("priority").trim().toUpperCase(Locale.ROOT));
        f.setAssignedTo(assigneeName(c, to));
        f.setCreatedBy("automation:" + c.ownerUsername());
        f.setDueDate(java.util.Date.from(LocalDate.now(CHURCH_ZONE).plusDays(days).atStartOfDay(CHURCH_ZONE).toInstant()));
        Object mid = facts.get("memberId");
        if (mid instanceof Number n) {
            FamilyMember m = members.findByIdAndTenant(n.intValue(), c.cid()).orElse(null);
            if (m != null) { f.setLinkedType("MEMBER"); f.setLinkedId(m.getId().longValue()); f.setLinkedLabel((m.getFirstName() + " " + m.getLastName()).trim()); }
        } else if (facts.get("visitorId") instanceof Number v) {
            f.setLinkedType("VISITOR"); f.setLinkedId(v.longValue()); f.setLinkedLabel(String.valueOf(facts.getOrDefault("name", "")));
        }
        followUps.save(f);
        return "Created follow-up #" + f.getId() + " for " + display(to) + " due in " + days + " day(s): " + t;
    }

    /** The follow-ups page shows assignees by the staff login's username: the owner's when it is the owner, else the login's first+last name. */
    private String assigneeName(Ctx c, AppUser to) {
        if (c.owner() != null && Objects.equals(to.getId(), c.owner().getId())) return c.ownerUsername();
        return display(to);
    }

    static String display(AppUser u) { return ((u.getFirstName() == null ? "" : u.getFirstName()) + " " + (u.getLastName() == null ? "" : u.getLastName())).trim(); }
    /** The family has no stored name (it is the primary member's); the member's last name labels the note. */
    static String familyName(FamilyMember m) { return m.getLastName() == null ? "" : m.getLastName(); }
    static String cut(String s, int max) { if (s == null) return null; s = s.trim(); return s.length() <= max ? s : s.substring(0, max); }
    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
