package com.churchgeniuspro.service;

import com.churchgeniuspro.common.States;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.util.FamilyRoles;
import com.churchgeniuspro.util.MemberNameUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Families page → Report: the search / filter report over a church's families and
 * members ({@code /familySearchReport}, {@code GET /api/family-report}).
 *
 * <p>Two report types share one set of criteria:
 * <ul>
 *   <li><b>members</b> — one row per person. Birthday month, anniversary month, gender,
 *       family role and member type apply to the person; city / state / ZIP apply to the
 *       person's own address fields.</li>
 *   <li><b>families</b> — one row per family (never duplicated, however many of its
 *       members match). Anniversary month, city / state / ZIP and type apply to the
 *       family's primary member (the Head of Household, exactly as the family name is
 *       derived elsewhere); a name, gender, role or birthday-month criterion matches the
 *       family when <em>any</em> of its listed members matches. Each row carries the
 *       primary member, the spouse (when any) and every listed member.</li>
 * </ul>
 *
 * <p><b>Tenant isolation.</b> The only data access is
 * {@link FamilyMemberRepository#findAllWithFamilyByAppUserAll(String)}, which filters by
 * the family's {@code app_client_id} in JPQL. The client id comes from the caller (the
 * session — never a request parameter) and a blank id yields an empty report rather than
 * an unscoped query. Nothing is ever written.
 *
 * <p><b>Privacy.</b> With {@code maskPrivate} (member-portal sessions) a phone, e-mail or
 * address the member marked private is removed from the row <em>and</em> ignored by the
 * filters, so a location search cannot reveal where a member with a private address lives.
 *
 * <p>Months are compared on the stored month column alone, so a birthday or anniversary
 * matches "regardless of the year" and also when the year was never entered.
 */
@Service
public class FamilyReportService {

    public static final String TYPE_MEMBERS  = "members";
    public static final String TYPE_FAMILIES = "families";

    /** Everything the report can be narrowed by. Blank / null / 0 = not applied. */
    public record Criteria(String reportType,
                           Integer birthdayMonth,
                           Integer anniversaryMonth,
                           String city,
                           String state,
                           String zip,
                           String gender,
                           String role,
                           String memberType,
                           String name,
                           boolean showDeleted,
                           boolean showInactive) {

        public boolean isFamilies() { return TYPE_FAMILIES.equalsIgnoreCase(reportType); }

        boolean hasLocation() { return nb(city) || nb(state) || nb(zip); }
    }

    private final FamilyMemberRepository familyMemberRepository;

    public FamilyReportService(FamilyMemberRepository familyMemberRepository) {
        this.familyMemberRepository = familyMemberRepository;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Runs the report for one church.
     *
     * @param c           the criteria (already validated by the controller)
     * @param appClientId the session's client id; blank → empty report
     * @param maskPrivate hide fields the member marked private (member-portal viewers)
     * @return {@code reportType, title, filterSummary, generatedAt, total, rows}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> run(Criteria c, String appClientId, boolean maskPrivate) {
        List<Map<String, Object>> rows;
        if (appClientId == null || appClientId.isBlank()) {
            rows = List.of();                       // never an unscoped query
        } else {
            List<FamilyMember> all = familyMemberRepository.findAllWithFamilyByAppUserAll(appClientId);
            // Defence in depth: drop anything whose family is not ours (or has no family).
            all = all.stream()
                    .filter(m -> m.getFamily() != null && appClientId.equals(m.getFamily().getAppClientId()))
                    .collect(Collectors.toList());
            rows = c.isFamilies() ? familyRows(all, c, maskPrivate) : memberRows(all, c, maskPrivate);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportType",    c.isFamilies() ? TYPE_FAMILIES : TYPE_MEMBERS);
        out.put("title",         title(c));
        out.put("filterSummary", filterSummary(c));
        out.put("generatedAt",   LocalDateTime.now(ZoneId.of("America/Chicago"))
                                     .format(DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a")));
        out.put("total",         rows.size());
        out.put("rows",          rows);
        return out;
    }

    // ── Members report ────────────────────────────────────────────────────────

    private List<Map<String, Object>> memberRows(List<FamilyMember> all, Criteria c, boolean mask) {
        Map<Integer, String> familyNames = familyNames(all);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FamilyMember m : all) {
            if (!statusVisible(m, c)) continue;
            if (!memberMatches(m, c, mask)) continue;
            if (!locationMatches(m, c, mask)) continue;
            rows.add(memberRow(m, familyNames.get(m.getFamily().getId()), mask, false));
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> str(r.get("lastName")), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(r -> str(r.get("firstName")), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Person-level criteria: name, birthday month, anniversary month, gender, role, member type. */
    static boolean memberMatches(FamilyMember m, Criteria c, boolean mask) {
        if (nb(c.name())) {
            String q = c.name().trim().toLowerCase(Locale.ROOT);
            if (!(contains(m.getFirstName(), q) || contains(m.getLastName(), q) || contains(m.getOtherName(), q)
                    || contains(fullName(m), q))) return false;
        }
        if (c.birthdayMonth() != null && c.birthdayMonth() > 0
                && !c.birthdayMonth().equals(m.getBirthdayMonth())) return false;
        if (c.anniversaryMonth() != null && c.anniversaryMonth() > 0
                && !c.anniversaryMonth().equals(m.getAnniversaryMonth())) return false;
        if (nb(c.gender()) && !c.gender().trim().equalsIgnoreCase(nz(m.getGender()).trim())) return false;
        if (nb(c.role()) && !FamilyRoles.canonical(c.role()).equals(FamilyRoles.canonical(m.getRole()))) return false;
        if (nb(c.memberType()) && !FamilyService.familyTypeMatches(m.getMemberType(), c.memberType())) return false;
        return true;
    }

    /** City / state / ZIP against the member's own address (a private address never matches for masked viewers). */
    static boolean locationMatches(FamilyMember m, Criteria c, boolean mask) {
        if (!c.hasLocation()) return true;
        if (mask && Boolean.TRUE.equals(m.getAddressPrivate())) return false;
        if (nb(c.city()) && !contains(m.getCity(), c.city().trim().toLowerCase(Locale.ROOT))) return false;
        if (nb(c.state()) && !stateMatches(m.getState(), c.state())) return false;
        if (nb(c.zip()) && !zipMatches(m.getPinCode(), c.zip())) return false;
        return true;
    }

    // ── Families report ───────────────────────────────────────────────────────

    private List<Map<String, Object>> familyRows(List<FamilyMember> all, Criteria c, boolean mask) {
        // Group by family, keeping the repository's (last name, first name) order inside each family.
        Map<Integer, List<FamilyMember>> byFamily = new LinkedHashMap<>();
        for (FamilyMember m : all) byFamily.computeIfAbsent(m.getFamily().getId(), k -> new ArrayList<>()).add(m);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<FamilyMember> members : byFamily.values()) {
            Family f = members.get(0).getFamily();
            if (f.isDeleteFlag() && !c.showDeleted())  continue;
            if (f.isInactive()   && !c.showInactive()) continue;

            // Members listed under the family follow the same status rule as the members report.
            List<FamilyMember> listed = members.stream().filter(m -> statusVisible(m, c)).collect(Collectors.toList());
            if (listed.isEmpty()) continue;

            FamilyMember primary = primaryOf(listed);
            FamilyMember spouse  = listed.stream()
                    .filter(m -> m != primary && FamilyRoles.isSpouse(m.getRole()))
                    .findFirst().orElse(null);
            FamilyMember annivSource = anniversarySource(primary, spouse);

            // ── Family-level criteria ──
            if (c.anniversaryMonth() != null && c.anniversaryMonth() > 0) {
                if (annivSource == null || !c.anniversaryMonth().equals(annivSource.getAnniversaryMonth())) continue;
            }
            if (!locationMatches(primary, c, mask)) continue;
            if (nb(c.memberType()) && !FamilyService.familyTypeMatches(primary.getMemberType(), c.memberType())) continue;
            // ── Any-member criteria (name, gender, role, birthday month) ──
            Criteria perMember = new Criteria(c.reportType(), c.birthdayMonth(), null, null, null, null,
                                              c.gender(), c.role(), null, c.name(), c.showDeleted(), c.showInactive());
            boolean anyMemberCriteria = nb(c.name()) || nb(c.gender()) || nb(c.role())
                                        || (c.birthdayMonth() != null && c.birthdayMonth() > 0);
            if (anyMemberCriteria && listed.stream().noneMatch(m -> memberMatches(m, perMember, mask))) continue;

            rows.add(familyRow(f, primary, spouse, annivSource, listed, mask));
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> str(r.get("familyName")), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(r -> str(r.get("primaryName")), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Head of Household first (active, then any), else the first listed member — as the family name is derived. */
    static FamilyMember primaryOf(List<FamilyMember> listed) {
        return listed.stream().filter(m -> !m.isDeleteFlag() && !m.isInactive() && FamilyRoles.isHead(m.getRole())).findFirst()
                .orElseGet(() -> listed.stream().filter(m -> FamilyRoles.isHead(m.getRole())).findFirst()
                .orElseGet(() -> listed.stream().filter(m -> !m.isDeleteFlag()).findFirst()
                .orElse(listed.get(0))));
    }

    /** The primary's anniversary when entered, else the spouse's, else none. */
    static FamilyMember anniversarySource(FamilyMember primary, FamilyMember spouse) {
        if (primary != null && primary.getAnniversaryMonth() != null) return primary;
        if (spouse  != null && spouse.getAnniversaryMonth()  != null) return spouse;
        return null;
    }

    private Map<String, Object> familyRow(Family f, FamilyMember primary, FamilyMember spouse,
                                          FamilyMember annivSource, List<FamilyMember> listed, boolean mask) {
        boolean addrHidden = mask && Boolean.TRUE.equals(primary.getAddressPrivate());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("familyId",     f.getId());
        row.put("familyName",   familyName(primary));
        String famType = FamilyService.familyTypeOf(primary.getMemberType());
        row.put("familyType",   famType);
        row.put("nonMember",    FamilyService.isNonMemberFamilyType(famType));
        row.put("status",       f.isDeleteFlag() ? "Deleted" : f.isInactive() ? "Inactive" : "Active");
        row.put("primaryId",    primary.getId());
        row.put("primaryName",  displayName(primary));
        row.put("primaryRole",  FamilyRoles.canonical(primary.getRole()));
        row.put("primaryPhone", mask && Boolean.TRUE.equals(primary.getPhonePrivate()) ? null : primary.getPhone());
        row.put("primaryEmail", mask && Boolean.TRUE.equals(primary.getEmailPrivate()) ? null : primary.getEmail());
        row.put("phoneHidden",  mask && Boolean.TRUE.equals(primary.getPhonePrivate()));
        row.put("emailHidden",  mask && Boolean.TRUE.equals(primary.getEmailPrivate()));
        row.put("spouseId",     spouse == null ? null : spouse.getId());
        row.put("spouseName",   spouse == null ? null : displayName(spouse));
        row.put("anniversary",  annivSource == null ? null
                : formatDate(annivSource.getAnniversaryMonth(), annivSource.getAnniversaryDay(), annivSource.getAnniversaryYear()));
        row.put("anniversaryMonth", annivSource == null ? null : annivSource.getAnniversaryMonth());
        row.put("anniversaryDay",   annivSource == null ? null : annivSource.getAnniversaryDay());
        row.put("anniversaryYear",  annivSource == null ? null : annivSource.getAnniversaryYear());
        row.put("anniversaryFrom",  annivSource == null ? null : (annivSource == primary ? "primary" : "spouse"));
        putAddress(row, primary, addrHidden);
        row.put("memberCount",  listed.size());
        // Primary first, then the spouse, then everyone else by last / first name.
        List<FamilyMember> ordered = new ArrayList<>();
        ordered.add(primary);
        if (spouse != null) ordered.add(spouse);
        listed.stream().filter(m -> m != primary && m != spouse)
              .sorted(Comparator.comparing((FamilyMember m) -> nz(m.getLastName()), String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(m -> nz(m.getFirstName()), String.CASE_INSENSITIVE_ORDER))
              .forEach(ordered::add);
        List<Map<String, Object>> mrows = new ArrayList<>();
        for (FamilyMember m : ordered) mrows.add(memberRow(m, familyName(primary), mask, m == primary));
        row.put("members", mrows);
        return row;
    }

    // ── Row builders ──────────────────────────────────────────────────────────

    private Map<String, Object> memberRow(FamilyMember m, String familyName, boolean mask, boolean primary) {
        boolean famDeleted  = m.getFamily().isDeleteFlag();
        boolean famInactive = m.getFamily().isInactive();
        boolean phoneHidden = mask && Boolean.TRUE.equals(m.getPhonePrivate());
        boolean emailHidden = mask && Boolean.TRUE.equals(m.getEmailPrivate());
        boolean addrHidden  = mask && Boolean.TRUE.equals(m.getAddressPrivate());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",           m.getId());
        row.put("firstName",    m.getFirstName());
        row.put("lastName",     m.getLastName());
        row.put("nickname",     m.getOtherName());
        row.put("name",         displayName(m));
        row.put("role",         FamilyRoles.canonical(m.getRole()));
        row.put("primary",      primary);
        row.put("memberType",   FamilyService.familyTypeOf(m.getMemberType()));
        row.put("gender",       m.getGender());
        row.put("birthday",     formatDate(m.getBirthdayMonth(), m.getBirthdayDay(), m.getBirthdayYear()));
        row.put("birthdayMonth", m.getBirthdayMonth());
        row.put("birthdayDay",   m.getBirthdayDay());
        row.put("birthdayYear",  m.getBirthdayYear());
        row.put("anniversary",  formatDate(m.getAnniversaryMonth(), m.getAnniversaryDay(), m.getAnniversaryYear()));
        row.put("phone",        phoneHidden ? null : m.getPhone());
        row.put("email",        emailHidden ? null : m.getEmail());
        row.put("phoneHidden",  phoneHidden);
        row.put("emailHidden",  emailHidden);
        putAddress(row, m, addrHidden);
        row.put("familyId",     m.getFamily().getId());
        row.put("familyName",   familyName);
        row.put("status",       FamilyService.memberStatus(m.isDeleteFlag(), famDeleted, m.isInactive(), famInactive));
        row.put("statusReason", FamilyService.memberStatusReason(m.isDeleteFlag(), famDeleted, m.isInactive(), famInactive));
        return row;
    }

    private static void putAddress(Map<String, Object> row, FamilyMember m, boolean hidden) {
        row.put("address1", hidden ? null : m.getAddress1());
        row.put("address2", hidden ? null : m.getAddress2());
        row.put("city",     hidden ? null : m.getCity());
        row.put("state",    hidden ? null : stateDisplay(m.getState()));
        row.put("zip",      hidden ? null : m.getPinCode());
        row.put("country",  hidden ? null : m.getCountry());
        row.put("addressHidden", hidden);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Member (or family) deleted → only with Show Deleted; inactive → only with Show Inactive. */
    static boolean statusVisible(FamilyMember m, Criteria c) {
        boolean deleted  = m.isDeleteFlag() || m.getFamily().isDeleteFlag();
        boolean inactive = m.isInactive()   || m.getFamily().isInactive();
        if (deleted  && !c.showDeleted())  return false;
        if (inactive && !c.showInactive()) return false;
        return true;
    }

    private static Map<Integer, String> familyNames(List<FamilyMember> all) {
        Map<Integer, List<FamilyMember>> byFamily = new HashMap<>();
        for (FamilyMember m : all) byFamily.computeIfAbsent(m.getFamily().getId(), k -> new ArrayList<>()).add(m);
        Map<Integer, String> names = new HashMap<>();
        byFamily.forEach((fid, members) -> names.put(fid, familyName(primaryOf(members))));
        return names;
    }

    static String familyName(FamilyMember primary) {
        if (primary == null) return "";
        String ln = nz(primary.getLastName()).trim();
        return ln.isEmpty() ? nz(primary.getFirstName()).trim() : ln + " Family";
    }

    static String displayName(FamilyMember m) {
        return MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName());
    }

    private static String fullName(FamilyMember m) {
        return (nz(m.getFirstName()) + " " + nz(m.getLastName())).trim();
    }

    /** "January 15, 1980" · "January 15" (no year) · "January" (month only) · null when no month. */
    static String formatDate(Integer month, Integer day, Integer year) {
        if (month == null || month < 1 || month > 12) return null;
        String name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (day == null || day < 1) return year == null ? name : name + " " + year;
        return year == null ? name + " " + day : name + " " + day + ", " + year;
    }

    /** Numeric state codes (the Family form's values) → abbreviation; anything else as stored. */
    static String stateDisplay(String stored) {
        if (stored == null) return null;
        String s = stored.trim();
        if (s.isEmpty()) return null;
        try {
            String abbr = States.getCode(Integer.parseInt(s));
            if (abbr != null) return abbr;
        } catch (NumberFormatException ignored) { }
        return s;
    }

    /**
     * A stored state (numeric code, abbreviation or full name — imports store any of them)
     * matches a filter given in any of those forms.
     */
    static boolean stateMatches(String stored, String filter) {
        String a = stateAbbr(stored), b = stateAbbr(filter);
        if (a != null && b != null) return a.equals(b);
        return nz(stored).trim().equalsIgnoreCase(nz(filter).trim()) && !nz(filter).trim().isEmpty();
    }

    private static String stateAbbr(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        try {
            String abbr = States.getCode(Integer.parseInt(s));
            if (abbr != null) return abbr;
        } catch (NumberFormatException ignored) { }
        if (s.length() == 2 && States.getCodeByAbbreviation(s) > 0) return s.toUpperCase(Locale.ROOT);
        String byName = STATE_NAMES.get(s.toLowerCase(Locale.ROOT));
        return byName;
    }

    private static final Map<String, String> STATE_NAMES = Map.ofEntries(
        Map.entry("alabama","AL"), Map.entry("alaska","AK"), Map.entry("arizona","AZ"), Map.entry("arkansas","AR"),
        Map.entry("california","CA"), Map.entry("colorado","CO"), Map.entry("connecticut","CT"), Map.entry("delaware","DE"),
        Map.entry("florida","FL"), Map.entry("georgia","GA"), Map.entry("hawaii","HI"), Map.entry("idaho","ID"),
        Map.entry("illinois","IL"), Map.entry("indiana","IN"), Map.entry("iowa","IA"), Map.entry("kansas","KS"),
        Map.entry("kentucky","KY"), Map.entry("louisiana","LA"), Map.entry("maine","ME"), Map.entry("maryland","MD"),
        Map.entry("massachusetts","MA"), Map.entry("michigan","MI"), Map.entry("minnesota","MN"), Map.entry("mississippi","MS"),
        Map.entry("missouri","MO"), Map.entry("montana","MT"), Map.entry("nebraska","NE"), Map.entry("nevada","NV"),
        Map.entry("new hampshire","NH"), Map.entry("new jersey","NJ"), Map.entry("new mexico","NM"), Map.entry("new york","NY"),
        Map.entry("north carolina","NC"), Map.entry("north dakota","ND"), Map.entry("ohio","OH"), Map.entry("oklahoma","OK"),
        Map.entry("oregon","OR"), Map.entry("pennsylvania","PA"), Map.entry("rhode island","RI"), Map.entry("south carolina","SC"),
        Map.entry("south dakota","SD"), Map.entry("tennessee","TN"), Map.entry("texas","TX"), Map.entry("utah","UT"),
        Map.entry("vermont","VT"), Map.entry("virginia","VA"), Map.entry("washington","WA"), Map.entry("west virginia","WV"),
        Map.entry("wisconsin","WI"), Map.entry("wyoming","WY"));

    /** ZIP / postal code: ignoring spaces and case, the stored value starts with the filter ("66062" matches "66062-1234"). */
    static boolean zipMatches(String stored, String filter) {
        String a = nz(stored).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String b = nz(filter).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return !b.isEmpty() && a.startsWith(b);
    }

    // ── Headings ──────────────────────────────────────────────────────────────

    /** The report heading, reflecting the type and the strongest filter — e.g. "Members with Birthdays in January". */
    static String title(Criteria c) {
        String month;
        if (c.isFamilies()) {
            if ((month = monthName(c.anniversaryMonth())) != null) return "Families with Anniversaries in " + month;
            if ((month = monthName(c.birthdayMonth())) != null)    return "Families with Birthdays in " + month;
            if (c.hasLocation())                                   return "Families by " + locationLabel(c);
            if (nb(c.memberType()))                                return typeLabel(c.memberType()) + " Families";
            if (nb(c.role()))                                      return "Families with " + FamilyRoles.canonical(c.role()) + " Members";
            if (nb(c.gender()))                                    return "Families with " + c.gender().trim() + " Members";
            if (nb(c.name()))                                      return "Family Search: \"" + c.name().trim() + "\"";
            return "Family Directory Report";
        }
        if ((month = monthName(c.birthdayMonth())) != null)        return "Members with Birthdays in " + month;
        if ((month = monthName(c.anniversaryMonth())) != null)     return "Members with Anniversaries in " + month;
        if (c.hasLocation())                                       return "Members by " + locationLabel(c);
        if (nb(c.gender()))                                        return c.gender().trim() + " Members";
        if (nb(c.role()))                                          return "Members by Role: " + FamilyRoles.canonical(c.role());
        if (nb(c.memberType()))                                    return typeLabel(c.memberType()) + " Members";
        if (nb(c.name()))                                          return "Member Search: \"" + c.name().trim() + "\"";
        return "Member Directory Report";
    }

    /** Every selected criterion, for the sub-heading and the printed / downloaded report. */
    static String filterSummary(Criteria c) {
        List<String> parts = new ArrayList<>();
        String m;
        if ((m = monthName(c.birthdayMonth()))    != null) parts.add("Birthday month: " + m);
        if ((m = monthName(c.anniversaryMonth())) != null) parts.add("Anniversary month: " + m);
        if (nb(c.city()))       parts.add("City: " + c.city().trim());
        if (nb(c.state()))      parts.add("State: " + nz(stateDisplay(c.state())));
        if (nb(c.zip()))        parts.add("ZIP/Postal code: " + c.zip().trim());
        if (nb(c.gender()))     parts.add("Gender: " + c.gender().trim());
        if (nb(c.role()))       parts.add("Family role: " + FamilyRoles.canonical(c.role()));
        if (nb(c.memberType())) parts.add((c.isFamilies() ? "Family type: " : "Member type: ") + typeLabel(c.memberType()));
        if (nb(c.name()))       parts.add("Name contains: \"" + c.name().trim() + "\"");
        if (c.showInactive())   parts.add("Including inactive");
        if (c.showDeleted())    parts.add("Including deleted");
        return parts.isEmpty() ? "All records" : String.join(" · ", parts);
    }

    private static String locationLabel(Criteria c) {
        List<String> l = new ArrayList<>();
        if (nb(c.city()))  l.add("City");
        if (nb(c.state())) l.add("State");
        if (nb(c.zip()))   l.add("ZIP/Postal Code");
        return l.size() == 3 ? "City, State and ZIP/Postal Code" : String.join(" and ", l);
    }

    private static String typeLabel(String memberType) {
        String t = nz(memberType).trim();
        if (FamilyService.FAMILY_TYPE_NONMEMBER.equalsIgnoreCase(t)) return "Non-Member";
        if (FamilyService.FAMILY_TYPE_MEMBER.equalsIgnoreCase(t))    return "Member";
        return t.isEmpty() ? "" : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    static String monthName(Integer month) {
        if (month == null || month < 1 || month > 12) return null;
        return Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static boolean nb(String s) { return s != null && !s.isBlank(); }
    private static String  nz(String s) { return s == null ? "" : s; }
    private static String  str(Object o) { return o == null ? "" : o.toString(); }
}
