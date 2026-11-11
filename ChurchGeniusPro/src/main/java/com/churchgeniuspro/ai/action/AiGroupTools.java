package com.churchgeniuspro.ai.action;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiActionLookup.Person;
import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.repository.GroupRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.GroupService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 2026-11-11 — Groups from the Genius Assistant conversation: create a group, then add people to it (someone
 * from the church directory, or a new person by name and e-mail — exactly what the Groups page's "Add member"
 * stores). Both tools are {@link AiTool#guidedOnly() guided-only}: never offered to the model, never reachable
 * from an external (MCP) client; the user's explicit Save runs {@code AiActionService.confirm} (one-time token,
 * permission re-check, re-resolution by id) and the SAME service methods the Groups page uses
 * ({@link GroupService#create}, {@link GroupMemberService#addMember}).
 *
 * <p>Permission = the Groups page's own write rule ({@code GroupController.groupsPageGuard(…, "admin.groups.edit")}
 * for a staff login): SuperAdmin / Admin AND {@code admin.groups.edit}. Member Portal, Kids Portal and church
 * owner logins are refused by the conversation before a tool is reached.
 */
public final class AiGroupTools {

    private AiGroupTools() {}

    public static final int MAX_NAME = 100;
    static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,24}$");

    /** The Groups page's write rule for a staff login. */
    public static boolean mayEditGroups(HttpServletRequest req) {
        HttpSession s = req == null ? null : req.getSession(false);
        if (s == null || s.getAttribute("memberId") != null) return false;
        return RoleGuard.requireAdmin(req) == null && RoleGuard.requirePermission(req, "admin.groups.edit") == null;
    }

    /** A usable e-mail address (lower-cased), "" when blank, null when it is not an address. */
    public static String cleanEmail(Object raw) {
        if (raw == null) return "";
        String e = String.valueOf(raw).trim().replaceAll("[.,;]+$", "");
        if (e.isEmpty()) return "";
        if (e.length() > 254 || !EMAIL.matcher(e).matches()) return null;
        return e.toLowerCase(java.util.Locale.ROOT);
    }

    /** A person's name part: letters, spaces, apostrophes, hyphens and dots, 1–60 characters; null otherwise. */
    public static String cleanPersonName(Object raw) {
        if (raw == null) return null;
        String n = String.valueOf(raw).replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (n.isEmpty() || n.length() > 60 || !n.matches("[\\p{L}][\\p{L}\\p{M} .'’-]*")) return null;
        return n;
    }

    abstract static class Base implements AiTool {
        @Override public boolean guidedOnly() { return true; }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return mayEditGroups(req); }
    }

    /* ─────────────────────────── create_group ─────────────────────────── */
    @Component
    public static class CreateGroup extends Base {
        private final GroupRepository repo; private final GroupService service;
        public CreateGroup(GroupRepository repo, GroupService service) { this.repo = repo; this.service = service; }
        public String name() { return "create_group"; }
        public String label() { return "Create a group"; }
        public String description() { return "Create a new ministry group on the Groups page."; }
        public Map<String, String> parameters() { return Map.of("name", "the new group's name"); }

        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            if (AiSettingsTools.blank(cid)) return Resolution.refuse("No church is selected.");
            String name = AiSettingsTools.cleanName(raw.get("name"));
            if (name == null) return Resolution.refuse("A group name is needed (up to " + MAX_NAME + " characters).");
            if (repo.existsByGroupNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, cid)) return Resolution.refuse("A group called “" + name + "” already exists.");
            Map<String, Object> r = new LinkedHashMap<>(); r.put("name", name);
            return Resolution.ok(r);
        }
        public String summary(Map<String, Object> r) { return "Create the group “" + r.get("name") + "”."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            Group g = service.create(String.valueOf(r.get("name")), cid);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("groupId", g.getId()); out.put("groupName", g.getGroupName());
            out.put("answer", "The group “" + g.getGroupName() + "” was created.");
            out.put("link", new LinkedHashMap<>(Map.of("label", "Open Groups", "url", "/groups")));
            return out;
        }
    }

    /* ─────────────────────────── add_group_person ─────────────────────────── */
    @Component
    public static class AddGroupPerson extends Base {
        private final AiActionLookup lookup; private final GroupMemberService members;
        public AddGroupPerson(AiActionLookup lookup, GroupMemberService members) { this.lookup = lookup; this.members = members; }
        public String name() { return "add_group_person"; }
        public String label() { return "Add a person to a group"; }
        public String description() { return "Add a directory member, or a new person by name and e-mail, to a group."; }
        public Map<String, String> parameters() { return Map.of("groupId", "the group", "memberId", "a directory member (optional)", "firstName", "first name", "lastName", "last name", "email", "e-mail (optional)"); }

        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            if (AiSettingsTools.blank(cid)) return Resolution.refuse("No church is selected.");
            Long gid = AiSettingsTools.l(raw, "groupId");
            Hit group = gid == null ? null : lookup.groupById(cid, gid);
            if (group == null) return Resolution.refuse("That group no longer exists.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("groupId", group.id()); r.put("groupName", group.label());
            Long mid = AiSettingsTools.l(raw, "memberId");
            if (mid != null) {                                              // someone from the directory
                Person p = lookup.personById(cid, mid);
                if (p == null) return Resolution.refuse("That person is no longer in the church directory.");
                if (lookup.inGroup(cid, group.id(), p.id())) return Resolution.refuse(p.name() + " is already in “" + group.label() + "”.");
                String fn = p.firstName().isBlank() ? p.lastName() : p.firstName();
                String ln = p.firstName().isBlank() ? "" : p.lastName();
                r.put("memberId", p.id()); r.put("firstName", fn); r.put("lastName", ln);
                String em = cleanEmail(p.email()); r.put("email", em == null ? "" : em);
                r.put("label", p.name());
                return Resolution.ok(r);
            }
            String fn = cleanPersonName(raw.get("firstName")), ln = cleanPersonName(raw.get("lastName"));
            if (fn == null || ln == null) return Resolution.refuse("A first name and a last name are needed.");
            String em = cleanEmail(raw.get("email"));
            if (em == null) return Resolution.refuse("That e-mail address doesn't look right.");
            if (lookup.nameInGroup(cid, group.id(), fn, ln, em)) return Resolution.refuse(fn + " " + ln + " is already in “" + group.label() + "”.");
            r.put("firstName", fn); r.put("lastName", ln); r.put("email", em); r.put("label", fn + " " + ln);
            return Resolution.ok(r);
        }
        public String summary(Map<String, Object> r) { return "Add " + r.get("label") + " to “" + r.get("groupName") + "”."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            Long mid = AiSettingsTools.l(r, "memberId");
            int gid = AiSettingsTools.l(r, "groupId").intValue();
            members.addMember(gid, String.valueOf(r.get("firstName")), String.valueOf(r.get("lastName")),
                    String.valueOf(r.getOrDefault("email", "")), cid, mid == null ? null : mid.intValue());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("groupId", gid); out.put("groupName", r.get("groupName")); out.put("label", r.get("label"));
            out.put("answer", r.get("label") + " has been added to “" + r.get("groupName") + "”.");
            return out;
        }
    }
}
