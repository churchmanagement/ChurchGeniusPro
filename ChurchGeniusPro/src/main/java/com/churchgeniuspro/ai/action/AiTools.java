package com.churchgeniuspro.ai.action;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.model.MeetingBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI Phase 4 — the six registered tools. Each resolves names to ids INSIDE the church, asks when a name is
 * ambiguous or missing, refuses obvious duplicates, and executes through the same service / repository the
 * feature's own page uses. Permission = the same rule as that page's write (checked again at confirm time).
 */
public final class AiTools {

    private AiTools() {}

    static final DateTimeFormatter D = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy");
    static String s(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? "" : String.valueOf(v).trim(); }
    static Long l(Map<String, Object> m, String k) { Object v = m.get(k); if (v == null) return null; try { return Long.valueOf(String.valueOf(v).trim()); } catch (Exception e) { return null; } }
    static List<String> labels(List<Hit> hits) { List<String> out = new ArrayList<>(); for (Hit h : hits) out.add(h.label()); return out; }
    static String cap(String t, int n) { return t.length() > n ? t.substring(0, n) : t; }

    /** One member by id (verified in this church) or by name; asks when none / several. */
    static AiTool.Resolution member(AiActionLookup lookup, String cid, Map<String, Object> raw, String key, String question, Map<String, Object> out, String idKey, String labelKey) {
        Long id = l(raw, idKey);
        if (id != null) {
            Hit h = lookup.memberById(cid, id);
            if (h == null) return AiTool.Resolution.refuse("That member does not exist in this church.");
            out.put(idKey, h.id()); out.put(labelKey, h.label());
            return null;
        }
        String name = s(raw, key);
        if (name.isBlank()) return AiTool.Resolution.ask(key, question, List.of());
        List<Hit> hits = lookup.members(cid, name);
        if (hits.isEmpty()) return AiTool.Resolution.ask(key, "I couldn't find a member called \"" + cap(name, 60) + "\". Who do you mean (first and last name)?", List.of());
        if (hits.size() > 1) return AiTool.Resolution.ask(key, "Several people match \"" + cap(name, 60) + "\" — which one?", labels(hits));
        out.put(idKey, hits.get(0).id()); out.put(labelKey, hits.get(0).label());
        return null;
    }

    /** A named thing (group / family / role / meeting type): by id when the resolved map already carries one (confirm-time re-check), else by name. */
    interface Finder { List<Hit> byName(String cid, String name); Hit byId(String cid, long id); }
    static AiTool.Resolution named(Finder f, String cid, Map<String, Object> raw, String key, String idKey, String labelKey, String what, Map<String, Object> out) {
        Long id = l(raw, idKey);
        if (id != null) {
            Hit h = f.byId(cid, id);
            if (h == null) return AiTool.Resolution.refuse("That " + what + " no longer exists in this church.");
            out.put(idKey, h.id()); out.put(labelKey, h.label()); return null;
        }
        String name = s(raw, key);
        if (name.isBlank()) return AiTool.Resolution.ask(key, "Which " + what + "?", List.of());
        List<Hit> hits = f.byName(cid, name);
        if (hits.isEmpty()) return AiTool.Resolution.ask(key, "I couldn't find a " + what + " called \"" + cap(name, 60) + "\". Which " + what + " do you mean?", List.of());
        if (hits.size() > 1) return AiTool.Resolution.ask(key, "Several " + what + "s match \"" + cap(name, 60) + "\" — which one?", labels(hits));
        out.put(idKey, hits.get(0).id()); out.put(labelKey, hits.get(0).label());
        return null;
    }

    /* ─────────────────────────── add_follow_up ─────────────────────────── */
    @Component
    public static class AddFollowUp implements AiTool {
        private final AiActionLookup lookup; private final FollowUpRepository repo;
        public AddFollowUp(AiActionLookup lookup, FollowUpRepository repo) { this.lookup = lookup; this.repo = repo; }
        public String name() { return "add_follow_up"; }
        public String label() { return "Add follow-up"; }
        public String description() { return "Create a follow-up task (title, optional member it concerns, optional due date, optional assignee by username)."; }
        public Map<String, String> parameters() { return Map.of("title", "what to do", "member", "the person it concerns (first and last name), optional", "dueDate", "YYYY-MM-DD, optional", "assignedTo", "staff username, optional"); }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return a.followUps(); }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            String title = cap(s(raw, "title"), 255);
            if (title.isBlank()) return Resolution.ask("title", "What should the follow-up say?", List.of());
            out.put("title", title);
            if (!s(raw, "member").isBlank() || raw.get("memberId") != null) {
                Resolution r = member(lookup, cid, raw, "member", "Who is the follow-up about?", out, "memberId", "memberLabel");
                if (r != null) return r;
            }
            String due = s(raw, "dueDate");
            if (!due.isBlank()) { try { out.put("dueDate", LocalDate.parse(due).toString()); } catch (Exception e) { return Resolution.ask("dueDate", "Which due date (for example 2026-11-20)?", List.of()); } }
            if (!s(raw, "assignedTo").isBlank()) out.put("assignedTo", cap(s(raw, "assignedTo"), 150));
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) {
            return "Add the follow-up \"" + r.get("title") + "\"" + (r.get("memberLabel") != null ? " for " + r.get("memberLabel") : "")
                    + (r.get("dueDate") != null ? ", due " + LocalDate.parse((String) r.get("dueDate")).format(D) : "") + (r.get("assignedTo") != null ? ", assigned to " + r.get("assignedTo") : "") + ".";
        }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            FollowUp f = new FollowUp();
            f.setClientId(cid); f.setTitle((String) r.get("title")); f.setStatus("PENDING"); f.setCreatedBy(username);
            if (r.get("dueDate") != null) f.setDueDate(java.sql.Date.valueOf(LocalDate.parse((String) r.get("dueDate"))));
            if (r.get("assignedTo") != null) f.setAssignedTo((String) r.get("assignedTo"));
            if (r.get("memberId") != null) { f.setLinkedType("MEMBER"); f.setLinkedId(((Number) r.get("memberId")).longValue()); f.setLinkedLabel((String) r.get("memberLabel")); }
            f = repo.save(f);
            return Map.of("followUpId", f.getId(), "link", Map.of("label", "Open Follow-ups", "url", "/followups"));
        }
    }

    /* ─────────────────────────── complete_follow_up ─────────────────────────── */
    @Component
    public static class CompleteFollowUp implements AiTool {
        private final AiActionLookup lookup; private final FollowUpRepository repo;
        public CompleteFollowUp(AiActionLookup lookup, FollowUpRepository repo) { this.lookup = lookup; this.repo = repo; }
        public String name() { return "complete_follow_up"; }
        public String label() { return "Complete follow-up"; }
        public String description() { return "Mark an open follow-up task as completed, found by words from its title or the person it concerns."; }
        public Map<String, String> parameters() { return Map.of("match", "words from the task title or the person's name", "followUpId", "the task id, when known"); }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return a.followUps(); }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            Long id = l(raw, "followUpId");
            if (id != null) {
                FollowUp f = repo.findByIdAndClientIdAndDeleteFlagFalse(id, cid).orElse(null);
                if (f == null) return Resolution.refuse("That follow-up does not exist in this church.");
                if ("COMPLETED".equals(f.getStatus())) return Resolution.refuse("\"" + f.getTitle() + "\" is already completed.");
                out.put("followUpId", f.getId()); out.put("title", f.getTitle()); return Resolution.ok(out);
            }
            String words = s(raw, "match");
            if (words.isBlank()) return Resolution.ask("match", "Which follow-up should I mark as done? Give a few words from its title or the person's name.", List.of());
            List<Hit> hits = lookup.openFollowUps(cid, words, null);
            if (hits.isEmpty()) return Resolution.ask("match", "I couldn't find an open follow-up matching \"" + cap(words, 60) + "\". Which one do you mean?", List.of());
            if (hits.size() > 1) return Resolution.ask("match", "Several open follow-ups match — which one?", labels(hits));
            out.put("followUpId", hits.get(0).id()); out.put("title", hits.get(0).label());
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) { return "Mark the follow-up \"" + r.get("title") + "\" as completed."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            FollowUp f = repo.findByIdAndClientIdAndDeleteFlagFalse(((Number) r.get("followUpId")).longValue(), cid).orElseThrow(() -> new IllegalStateException("Follow-up not found"));
            f.setStatus("COMPLETED");
            repo.save(f);
            return Map.of("followUpId", f.getId(), "link", Map.of("label", "Open Follow-ups", "url", "/followups"));
        }
    }

    /* ─────────────────────────── add_family_note ─────────────────────────── */
    @Component
    public static class AddFamilyNote implements AiTool {
        private final AiActionLookup lookup; private final NoteRepository repo;
        public AddFamilyNote(AiActionLookup lookup, NoteRepository repo) { this.lookup = lookup; this.repo = repo; }
        public String name() { return "add_family_note"; }
        public String label() { return "Add family note"; }
        public String description() { return "Add a note about a family to the church's Notes (title 'Family note — <family>'); the family is found by its name."; }
        public Map<String, String> parameters() { return Map.of("family", "the family's name, e.g. Smith or the Smiths", "note", "the note text"); }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return a.members(); }   // the Families page rule
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            String note = cap(s(raw, "note"), 4000);
            Resolution r = named(new Finder() { public List<Hit> byName(String c, String n) { return lookup.families(c, n); } public Hit byId(String c, long id) { return lookup.familyById(c, id); } },
                    cid, raw, "family", "familyId", "familyLabel", "family", out);
            if (r != null) return r;
            if (note.isBlank()) return Resolution.ask("note", "What should the note say?", List.of());
            out.put("note", note);
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) { String n = (String) r.get("note"); return "Add a note to the " + r.get("familyLabel") + ": \"" + cap(n, 120) + (n.length() > 120 ? "…" : "") + "\""; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            Note n = new Note();
            n.setAppClientId(cid); n.setTitle(cap("Family note — " + r.get("familyLabel"), 250)); n.setBody((String) r.get("note"));
            n.setCreatedByName(username); n.setStarred(false);
            n = repo.save(n);
            return Map.of("noteId", n.getId(), "link", Map.of("label", "Open Notes", "url", "/notes"));
        }
    }

    /* ─────────────────────────── add_group_member ─────────────────────────── */
    @Component
    public static class AddGroupMember implements AiTool {
        private final AiActionLookup lookup; private final GroupMemberService groups;
        public AddGroupMember(AiActionLookup lookup, GroupMemberService groups) { this.lookup = lookup; this.groups = groups; }
        public String name() { return "add_group_member"; }
        public String label() { return "Add to group"; }
        public String description() { return "Add an existing church member to a group, both found by name."; }
        public Map<String, String> parameters() { return Map.of("member", "the person's first and last name", "group", "the group's name"); }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) {
            return x.groups() && RoleGuard.requirePermission(req, "admin.groups.edit") == null;
        }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            Resolution r = member(lookup, cid, raw, "member", "Who should I add (first and last name)?", out, "memberId", "memberLabel");
            if (r != null) return r;
            r = named(new Finder() { public List<Hit> byName(String c, String n) { return lookup.groups(c, n); } public Hit byId(String c, long id) { return lookup.groupById(c, id); } },
                    cid, raw, "group", "groupId", "groupLabel", "group", out);
            if (r != null) return r;
            if (lookup.inGroup(cid, (Long) out.get("groupId"), (Long) out.get("memberId"))) return Resolution.refuse(out.get("memberLabel") + " is already in " + out.get("groupLabel") + ".");
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) { return "Add " + r.get("memberLabel") + " to the group " + r.get("groupLabel") + "."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            long memberId = ((Number) r.get("memberId")).longValue();
            String label = (String) r.get("memberLabel");
            int sp = label.lastIndexOf(' ');
            String first = sp > 0 ? label.substring(0, sp) : label, last = sp > 0 ? label.substring(sp + 1) : "-";
            GroupMember gm = groups.addMember(((Number) r.get("groupId")).intValue(), first, last, lookup.memberEmail(cid, memberId), cid, (int) memberId);
            return Map.of("groupMemberId", gm.getId(), "link", Map.of("label", "Open Groups", "url", "/groups"));
        }
    }

    /* ─────────────────────────── create_meeting ─────────────────────────── */
    @Component
    public static class CreateMeeting implements AiTool {
        private final AiActionLookup lookup; private final MeetingTypeRepository types; private final MeetingRepository meetings; private final MeetingService service;
        public CreateMeeting(AiActionLookup lookup, MeetingTypeRepository types, MeetingRepository meetings, MeetingService service) { this.lookup = lookup; this.types = types; this.meetings = meetings; this.service = service; }
        public String name() { return "create_meeting"; }
        public String label() { return "Create meeting"; }
        public String description() { return "Create a one-time meeting of an existing meeting category on a date, with optional start/end time, location text and note."; }
        public Map<String, String> parameters() { return Map.of("category", "the meeting category name", "date", "YYYY-MM-DD", "startTime", "HH:MM, optional", "endTime", "HH:MM, optional", "location", "address or place text, optional", "note", "optional"); }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return a.meetingsEdit(); }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            Resolution r = named(new Finder() { public List<Hit> byName(String c, String n) { return lookup.meetingTypes(c, n); } public Hit byId(String c, long id) { return lookup.meetingTypeById(c, id); } },
                    cid, raw, "category", "typeId", "typeLabel", "meeting category", out);
            if (r != null) return r;
            LocalDate date;
            try { date = LocalDate.parse(s(raw, "date")); } catch (Exception e) { return Resolution.ask("date", "On which date (for example 2026-11-20)?", List.of()); }
            out.put("date", date.toString());
            for (String k : List.of("startTime", "endTime")) {
                String t = s(raw, k);
                if (!t.isBlank()) { try { out.put(k, LocalTime.parse(t).toString().substring(0, 5)); } catch (Exception e) { return Resolution.ask(k, "At what time (for example 19:00)?", List.of()); } }
            }
            if (!s(raw, "location").isBlank()) out.put("location", cap(s(raw, "location"), 255));
            if (!s(raw, "note").isBlank()) out.put("note", cap(s(raw, "note"), 1000));
            if (meetings.aiCountSameSlot(cid, ((Long) out.get("typeId")).intValue(), date, out.get("startTime") == null ? "" : (String) out.get("startTime")) > 0)
                return Resolution.refuse("A " + out.get("typeLabel") + " meeting on " + date.format(D) + (out.get("startTime") == null ? "" : " at " + out.get("startTime")) + " already exists.");
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) {
            return "Create a one-time " + r.get("typeLabel") + " meeting on " + LocalDate.parse((String) r.get("date")).format(D)
                    + (r.get("startTime") != null ? " at " + r.get("startTime") + (r.get("endTime") != null ? "–" + r.get("endTime") : "") : "")
                    + (r.get("location") != null ? " at " + r.get("location") : "") + (r.get("note") != null ? " (note: " + r.get("note") + ")" : "") + ".";
        }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            MeetingType mt = types.findByIdAndAppClientIdAndDeleteFlagFalse(((Number) r.get("typeId")).intValue(), cid).orElseThrow(() -> new IllegalStateException("Meeting category not found"));
            MeetingBO bo = new MeetingBO();
            bo.setMeetingTypeId(mt.getId()); bo.setMeetingDate((String) r.get("date"));
            bo.setStartTime((String) r.get("startTime")); bo.setEndTime((String) r.get("endTime"));
            bo.setNote((String) r.get("note")); bo.setOccurrence("One-time"); bo.setDoNotAutoDelete(false);
            bo.setAddress1((String) r.get("location"));
            Meeting saved = service.save(bo, cid);
            if (saved == null || saved.getId() == null) throw new IllegalStateException("The meeting could not be saved");
            return Map.of("meetingId", saved.getId(), "link", Map.of("label", "Open this meeting", "url", "/meetings?meetingId=" + saved.getId()));
        }
    }

    /* ─────────────────────────── assign_volunteer ─────────────────────────── */
    @Component
    public static class AssignVolunteer implements AiTool {
        private final AiActionLookup lookup; private final VolunteerAssignmentRepository repo;
        public AssignVolunteer(AiActionLookup lookup, VolunteerAssignmentRepository repo) { this.lookup = lookup; this.repo = repo; }
        public String name() { return "assign_volunteer"; }
        public String label() { return "Assign volunteer"; }
        public String description() { return "Create a pending volunteer assignment for a member: a volunteer role (by name), a date and an optional label such as the service or event."; }
        public Map<String, String> parameters() { return Map.of("member", "first and last name", "role", "volunteer role name, e.g. usher", "date", "YYYY-MM-DD", "label", "what for, e.g. Sunday 9 am service, optional"); }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) {
            return a.volunteers() && ("SuperAdmin".equals(a.role()) || "Admin".equals(a.role()));   // POST /api/volunteers/assignments = requireAdmin
        }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            Resolution r = member(lookup, cid, raw, "member", "Who should I assign (first and last name)?", out, "memberId", "memberLabel");
            if (r != null) return r;
            if (!s(raw, "role").isBlank() || raw.get("roleId") != null) {
                r = named(new Finder() { public List<Hit> byName(String c, String n) { return lookup.volunteerRoles(c, n); } public Hit byId(String c, long id) { return lookup.volunteerRoleById(c, id); } },
                        cid, raw, "role", "roleId", "roleLabel", "volunteer role", out);
                if (r != null) return r;
            }
            LocalDate date;
            try { date = LocalDate.parse(s(raw, "date")); } catch (Exception e) { return Resolution.ask("date", "For which date (for example 2026-11-23)?", List.of()); }
            out.put("date", date.toString());
            if (!s(raw, "label").isBlank()) out.put("label", cap(s(raw, "label"), 300));
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) {
            return "Assign " + r.get("memberLabel") + (r.get("roleLabel") != null ? " as " + r.get("roleLabel") : " as a volunteer") + " on " + LocalDate.parse((String) r.get("date")).format(D)
                    + (r.get("label") != null ? " (" + r.get("label") + ")" : "") + " — status pending.";
        }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            VolunteerAssignment a = new VolunteerAssignment();
            a.setAppClientId(cid); a.setFamilyMemberId(((Number) r.get("memberId")).intValue());
            if (r.get("roleId") != null) a.setRoleId(((Number) r.get("roleId")).longValue());
            a.setEventDate(LocalDate.parse((String) r.get("date")));
            a.setEventLabel(r.get("label") != null ? (String) r.get("label") : (r.get("roleLabel") != null ? (String) r.get("roleLabel") : "Volunteer"));
            a.setAssignmentStatus("pending"); a.setCreatedAt(LocalDateTime.now());
            a = repo.save(a);
            return Map.of("assignmentId", a.getId(), "link", Map.of("label", "Open Volunteers", "url", "/volunteers"));
        }
    }
}
