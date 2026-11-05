package com.churchgeniuspro.ai.filter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI Phase 1 (2026-11-04): a composable people filter — an ordered list of typed predicates that
 * are ANDed together. This is the ONLY thing the parser produces and the ONLY thing the query
 * builder consumes, so free text never reaches SQL: every predicate is one of the closed set
 * below, with validated parameters, and each maps to one fixed SQL fragment.
 *
 * <p>The same object round-trips as JSON (chips on the page, saved lists), through
 * {@link #toJson()} / {@link #fromJson(Map)}, which refuse unknown kinds and out-of-range values.
 *
 * <p>People are Families-page records (family_member rows of the church). By default only live
 * people (not deleted, not inactive — member or family) are considered; a {@link Status}
 * predicate widens that.
 */
public record PeopleFilter(List<Predicate> predicates) {

    public PeopleFilter {
        predicates = List.copyOf(predicates == null ? List.of() : predicates);
    }

    public static PeopleFilter of(Predicate... p) { return new PeopleFilter(List.of(p)); }

    public boolean isEmpty() { return predicates.isEmpty(); }

    /** The filter without the predicate at {@code index} (a chip's ✕). */
    public PeopleFilter without(int index) {
        List<Predicate> out = new ArrayList<>(predicates);
        if (index >= 0 && index < out.size()) out.remove(index);
        return new PeopleFilter(out);
    }

    /** The modules a filter reads (for permission gating): Attendance, Income, Groups, Volunteers, Pledges, Events, Members. */
    public java.util.Set<String> modules() {
        java.util.Set<String> m = new java.util.LinkedHashSet<>();
        m.add("Members");
        for (Predicate p : predicates) m.add(p.module());
        return m;
    }

    public List<Map<String, Object>> toJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Predicate p : predicates) out.add(p.toJson());
        return out;
    }

    /** Parses the JSON shape {@code [{kind:…, …}]}; unknown kinds, bad numbers or dates → IllegalArgumentException. */
    @SuppressWarnings("unchecked")
    public static PeopleFilter fromJson(List<?> list) {
        List<Predicate> out = new ArrayList<>();
        if (list == null) return new PeopleFilter(out);
        if (list.size() > 12) throw new IllegalArgumentException("Too many conditions (max 12).");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("Each condition must be an object.");
            out.add(Predicate.fromJson((Map<String, Object>) m));
        }
        return new PeopleFilter(out);
    }

    /* ═══════════════════════════ predicates ═══════════════════════════ */

    /** Negatable, labelled, module-tagged condition. */
    public sealed interface Predicate permits AgeBand, BirthMonth, Role, Gender, Status, MemberType,
            Attended, Gave, InGroup, Serving, InCampaign, RegisteredForEvent, HasContact {
        /** Short human label for the chip, e.g. "Age 18–30". */
        String label();
        /** The module whose page rule gates this predicate: Members, Attendance, Income, Groups, Volunteers, Pledges, Events. */
        String module();
        /** JSON form; {@code kind} is the record's simple name. */
        Map<String, Object> toJson();

        static Predicate fromJson(Map<String, Object> m) {
            String kind = str(m.get("kind"));
            return switch (kind) {
                case "AgeBand"   -> new AgeBand(intOrNull(m.get("min")), intOrNull(m.get("max")));
                case "BirthMonth"-> new BirthMonth(intReq(m.get("month"), 1, 12));
                case "Role"      -> new Role(oneOf(str(m.get("role")), "Head", "Spouse", "Adult", "Child"));
                case "Gender"    -> new Gender(oneOf(str(m.get("gender")), "Male", "Female"));
                case "Status"    -> new Status(oneOf(str(m.get("status")), "active", "inactive", "deleted", "any"));
                case "MemberType"-> new MemberType(oneOf(str(m.get("type")), "Member", "Guest", "Visitor", "NonMember"));
                case "Attended"  -> new Attended(bool(m.get("negated")), intReq(m.get("minDays"), 0, 1000), date(m.get("from")), date(m.get("to")), str(m.get("periodLabel")));
                case "Gave"      -> new Gave(bool(m.get("negated")), date(m.get("from")), date(m.get("to")), str(m.get("periodLabel")), strOrNull(m.get("fund")));
                case "InGroup"   -> new InGroup(bool(m.get("negated")), strOrNull(m.get("group")));
                case "Serving"   -> new Serving(bool(m.get("negated")), date(m.get("since")));
                case "InCampaign"-> new InCampaign(bool(m.get("negated")), strOrNull(m.get("campaign")));
                case "RegisteredForEvent" -> new RegisteredForEvent(bool(m.get("negated")), strOrNull(m.get("event")));
                case "HasContact"-> new HasContact(bool(m.get("negated")), oneOf(str(m.get("what")), "email", "phone"));
                default -> throw new IllegalArgumentException("Unknown condition: " + kind);
            };
        }
    }

    /** Age today between min and max inclusive (either may be null); people without a full birth date never match. */
    public record AgeBand(Integer min, Integer max) implements Predicate {
        public AgeBand {
            if (min != null && (min < 0 || min > 120)) throw new IllegalArgumentException("Age must be 0–120.");
            if (max != null && (max < 0 || max > 120)) throw new IllegalArgumentException("Age must be 0–120.");
            if (min == null && max == null) throw new IllegalArgumentException("An age range needs a lower or upper bound.");
            if (min != null && max != null && min > max) { Integer t = min; min = max; max = t; }
        }
        public String label() { return min != null && max != null ? "Age " + min + "–" + max : min != null ? "Age " + min + "+" : "Age under " + (max + 1); }
        public String module() { return "Members"; }
        public Map<String, Object> toJson() { return json("AgeBand", "min", min, "max", max); }
    }

    public record BirthMonth(int month) implements Predicate {
        public String label() { return "Birthday in " + java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US); }
        public String module() { return "Members"; }
        public Map<String, Object> toJson() { return json("BirthMonth", "month", month); }
    }

    /** Canonical family role (FamilyRoles): Head, Spouse, Adult, Child. */
    public record Role(String role) implements Predicate {
        public String label() { return switch (role) { case "Head" -> "Head of household"; case "Spouse" -> "Spouse"; case "Child" -> "Children (role)"; default -> "Adults (role)"; }; }
        public String module() { return "Members"; }
        public Map<String, Object> toJson() { return json("Role", "role", role); }
    }

    public record Gender(String gender) implements Predicate {
        public String label() { return "Male".equals(gender) ? "Men" : "Women"; }
        public String module() { return "Members"; }
        public Map<String, Object> toJson() { return json("Gender", "gender", gender); }
    }

    /** active (default), inactive, deleted, any. */
    public record Status(String status) implements Predicate {
        public String label() { return switch (status) { case "inactive" -> "Inactive"; case "deleted" -> "Deleted"; case "any" -> "Any status"; default -> "Active"; }; }
        public String module() { return "Members"; }
        public Map<String, Object> toJson() { return json("Status", "status", status); }
    }

    /** Member (blank or Member), Guest, Visitor, NonMember (= Guest or Visitor). */
    public record MemberType(String type) implements Predicate {
        public String label() { return switch (type) { case "Guest" -> "Guests"; case "Visitor" -> "Visitors"; case "NonMember" -> "Non-members"; default -> "Members"; }; }
        public String module() { return "Members"; }
        public Map<String, Object> toJson() { return json("MemberType", "type", type); }
    }

    /** Attended (Present/Late, distinct days) at least minDays in [from, to]; negated = no attendance at all in the window. */
    public record Attended(boolean negated, int minDays, LocalDate from, LocalDate to, String periodLabel) implements Predicate {
        public Attended {
            Objects.requireNonNull(from, "from"); Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) { LocalDate t = from; from = to; to = t; }
            if (!negated && minDays < 1) minDays = 1;
            if (periodLabel == null || periodLabel.isBlank()) periodLabel = from + " – " + to;
        }
        public String label() { return negated ? "Not attended " + periodLabel : (minDays > 1 ? "Attended " + minDays + "+ times " : "Attended ") + periodLabel; }
        public String module() { return "Attendance"; }
        public Map<String, Object> toJson() { return json("Attended", "negated", negated, "minDays", minDays, "from", from.toString(), "to", to.toString(), "periodLabel", periodLabel); }
    }

    /** Gave (any income row as the member) in [from, to], optionally to one fund; negated = gave nothing in the window. */
    public record Gave(boolean negated, LocalDate from, LocalDate to, String periodLabel, String fund) implements Predicate {
        public Gave {
            Objects.requireNonNull(from, "from"); Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) { LocalDate t = from; from = to; to = t; }
            if (periodLabel == null || periodLabel.isBlank()) periodLabel = from + " – " + to;
            if (fund != null && fund.isBlank()) fund = null;
        }
        public String label() { return (negated ? "Did not give " : "Gave ") + (fund != null ? "to " + fund + " " : "") + periodLabel; }
        public String module() { return "Income"; }
        public Map<String, Object> toJson() { return json("Gave", "negated", negated, "from", from.toString(), "to", to.toString(), "periodLabel", periodLabel, "fund", fund); }
    }

    /** Confirmed membership of a live group (member link, or an email only they use); optionally one named group. */
    public record InGroup(boolean negated, String group) implements Predicate {
        public InGroup { if (group != null && group.isBlank()) group = null; }
        public String label() { return (negated ? "Not in " : "In ") + (group != null ? "group “" + group + "”" : "a group"); }
        public String module() { return "Groups"; }
        public Map<String, Object> toJson() { return json("InGroup", "negated", negated, "group", group); }
    }

    /** A non-declined volunteer assignment dated on/after {@code since} (or undated). */
    public record Serving(boolean negated, LocalDate since) implements Predicate {
        public Serving { Objects.requireNonNull(since, "since"); }
        public String label() { return negated ? "Not serving" : "Serving"; }
        public String module() { return "Volunteers"; }
        public Map<String, Object> toJson() { return json("Serving", "negated", negated, "since", since.toString()); }
    }

    /** Has a live pledge in a campaign (optionally named). */
    public record InCampaign(boolean negated, String campaign) implements Predicate {
        public InCampaign { if (campaign != null && campaign.isBlank()) campaign = null; }
        public String label() { return (negated ? "No pledge" : "Pledged") + (campaign != null ? " — " + campaign : ""); }
        public String module() { return "Pledges"; }
        public Map<String, Object> toJson() { return json("InCampaign", "negated", negated, "campaign", campaign); }
    }

    /** Registered for a named event — matched by an email that belongs to this member alone (registrations carry no member id). */
    public record RegisteredForEvent(boolean negated, String event) implements Predicate {
        public RegisteredForEvent { if (event == null || event.isBlank()) throw new IllegalArgumentException("Which event?"); }
        public String label() { return (negated ? "Not registered for " : "Registered for ") + "“" + event + "”"; }
        public String module() { return "Events"; }
        public Map<String, Object> toJson() { return json("RegisteredForEvent", "negated", negated, "event", event); }
    }

    /** Has (or lacks) an email / phone on record. */
    public record HasContact(boolean negated, String what) implements Predicate {
        public String label() { return (negated ? "No " : "Has ") + what; }
        public String module() { return "Members"; }
        public Map<String, Object> toJson() { return json("HasContact", "negated", negated, "what", what); }
    }

    /* ═══════════════════════════ json helpers ═══════════════════════════ */

    private static Map<String, Object> json(String kind, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String strOrNull(Object o) { String s = str(o); return s.isEmpty() ? null : (s.length() > 120 ? s.substring(0, 120) : s); }
    private static boolean bool(Object o) { return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(str(o)); }
    private static Integer intOrNull(Object o) {
        if (o == null || str(o).isEmpty()) return null;
        try { return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Not a number: " + str(o)); }
    }
    private static int intReq(Object o, int lo, int hi) {
        Integer v = intOrNull(o);
        if (v == null || v < lo || v > hi) throw new IllegalArgumentException("Value out of range.");
        return v;
    }
    private static LocalDate date(Object o) {
        try { return LocalDate.parse(str(o)); } catch (Exception e) { throw new IllegalArgumentException("Not a date: " + str(o)); }
    }
    private static String oneOf(String v, String... allowed) {
        for (String a : allowed) if (a.equalsIgnoreCase(v)) return a;
        throw new IllegalArgumentException("Not allowed: " + v);
    }
}
