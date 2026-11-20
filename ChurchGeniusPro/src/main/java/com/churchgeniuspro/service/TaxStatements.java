package com.churchgeniuspro.service;

import com.churchgeniuspro.repository.IncomeRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Year-End Tax Report's figures: one bucket per contributor (a member on file, or a guest name) with the
 * tax-deductible gifts of one year. This is the ONE place they are worked out — the Tax Report page
 * ({@code GET /api/reports/tax}) and Genius Assistant's tax-report conversation both call it, so a statement shows
 * the same figures wherever it was asked for. Moved here unchanged from AccountingReportController (2026-11-20),
 * plus the contributor filter:
 *
 * <ul>
 *   <li>{@code members} — people on file whose type is Member (or blank);</li>
 *   <li>{@code nonmembers} — people on file whose type is anything else (Guest, Visitor…) and every contributor
 *       recorded by a guest name only;</li>
 *   <li>{@code all} / blank — everyone (as before).</li>
 * </ul>
 *
 * Every query is limited to the church passed in; nothing here writes.
 */
public final class TaxStatements {

    private TaxStatements() {}

    public static final String ALL = "all", MEMBERS = "members", NONMEMBERS = "nonmembers";

    /** all | members | nonmembers from what a caller sent; blank = all. Anything else is refused. */
    public static String contributorType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (t.isEmpty() || t.equals("all")) return ALL;
        if (t.equals("members") || t.equals("member") || t.equals("membersonly")) return MEMBERS;
        if (t.equals("nonmembers") || t.equals("nonmember") || t.equals("nonmembersonly") || t.equals("guests")) return NONMEMBERS;
        throw new IllegalArgumentException("Contributor type must be all, members or nonmembers.");
    }

    public static String contributorTypeLabel(String type) {
        return MEMBERS.equals(type) ? "Members only" : NONMEMBERS.equals(type) ? "Non-Members only" : "All (Members and Non-Members)";
    }

    /** A person on file counts as a member unless their type says otherwise (Guest, Visitor…). Blank = Member. */
    public static boolean isMemberType(String memberType) {
        String t = memberType == null ? "" : memberType.trim();
        return t.isEmpty() || "Member".equalsIgnoreCase(t);
    }

    /**
     * @param contributorType all | members | nonmembers (blank = all)
     * @return year, memberId, guestName, contributorType, churchName, members[], grandTotal, unattributedTotal,
     *         unattributedCount — the letter wording is added by the caller
     * @throws IllegalArgumentException for an unknown contributor type
     */
    public static Map<String, Object> build(IncomeRepository incomeRepo, String appClientId, String churchName, int year,
                                            Integer memberId, String guestName, String contributorType) {
        String type = contributorType(contributorType);
        // Normalise: blank string → null so SQL CAST(:x IS NULL) short-circuits correctly
        String guestNameFilter = (guestName != null && !guestName.isBlank()) ? guestName.trim() : null;

        // ── Grouped summary (contributions per sub-source) ─────────────────
        // [member_id (null for guests), first_name, last_name, guest_name,
        //  sub_name, main_name, total, cnt]
        List<Object[]> summaryRows = incomeRepo.reportAnnualIncomeByMemberFiltered(
                year, appClientId, memberId, guestNameFilter);

        // Key: memberId (for members) or "guest::<guestName>" (for guest rows)
        Map<String, Map<String, Object>> memberMap = new LinkedHashMap<>();

        // Financial audit M5: income with neither a matched member nor a guest
        // name (an unattributed lump sum — e.g. an uncounted plate-offering
        // total) has no one a contribution statement could be addressed to.
        // Tallied here and reported separately instead of being folded into a
        // "guest::" bucket keyed by an empty name.
        BigDecimal unattributedTotal = BigDecimal.ZERO;
        int        unattributedCount = 0;

        for (Object[] r : summaryRows) {
            boolean isGuest   = r[0] == null;
            String  firstName = str(r[1]);
            String  lastName  = str(r[2]);
            String  gName     = str(r[3]);
            String  subName   = str(r[4]);
            String  mainName  = str(r[5]);
            BigDecimal total  = toBD(r[6]);

            if (isGuest && gName.isBlank()) {
                unattributedTotal = unattributedTotal.add(total);
                unattributedCount++;
                continue;
            }

            String mapKey;
            if (!isGuest) {
                mapKey = "member::" + ((Number) r[0]).intValue();
            } else {
                mapKey = "guest::" + gName;
            }

            memberMap.computeIfAbsent(mapKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                if (!isGuest) {
                    m.put("id",        ((Number) r[0]).intValue());
                    m.put("name",      (lastName.isBlank() ? "" : lastName + ", ") + firstName);
                    m.put("firstName", firstName);
                    m.put("lastName",  lastName);
                } else {
                    m.put("id",        null);
                    m.put("name",      gName);
                    m.put("firstName", gName);
                    m.put("lastName",  "");
                    // Financial audit M5: a guest is matched by name text alone —
                    // the only identity signal a walk-in/anonymous gift carries,
                    // so two different people who happen to share a name are
                    // combined into one statement. This flag lets a reviewer (or
                    // a future UI) know to double-check before mailing rather
                    // than hiding that the match is unverified.
                    m.put("nameOnly",  true);
                }
                m.put("contributions", new ArrayList<Map<String, Object>>());
                m.put("entries",       new ArrayList<Map<String, Object>>());
                m.put("memberTotal",   BigDecimal.ZERO);
                m.put("address1",  "");
                m.put("address2",  "");
                m.put("city",      "");
                m.put("state",     "");
                m.put("pinCode",   "");
                return m;
            });
            Map<String, Object> member = memberMap.get(mapKey);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contribs = (List<Map<String, Object>>) member.get("contributions");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("mainCategory", mainName);
            c.put("subCategory",  subName);
            c.put("total",        total);
            contribs.add(c);
            member.put("memberTotal", ((BigDecimal) member.get("memberTotal")).add(total));
        }

        // 2026-11-20: who is a member. Read for the people in this report only, inside this church.
        Set<Integer> ids = new LinkedHashSet<>();
        for (Map<String, Object> m : memberMap.values()) if (m.get("id") instanceof Integer i) ids.add(i);
        Map<Integer, String> types = memberTypes(incomeRepo, appClientId, ids);
        memberMap.values().removeIf(m -> {
            boolean onFile = m.get("id") != null;
            String mt = onFile ? types.get((Integer) m.get("id")) : null;
            boolean member = onFile && isMemberType(mt);
            m.put("memberType", onFile ? (mt == null || mt.isBlank() ? "Member" : mt.trim()) : "Guest");
            m.put("nonMember", !member);
            return (MEMBERS.equals(type) && !member) || (NONMEMBERS.equals(type) && member);
        });

        // ── Individual entries (for letter-format PDF) ─────────────────────
        // [member_id (null for guests), first_name, last_name, guest_name,
        //  address1, address2, city, state, pin_code,
        //  income_date, main_name, sub_name, amount, method]
        List<Object[]> entryRows = incomeRepo.reportIncomeEntriesForTaxYear(
                year, appClientId, memberId, guestNameFilter);

        for (Object[] r : entryRows) {
            boolean isGuest = r[0] == null;
            String  gName   = str(r[3]);
            // Financial audit M5: same exclusion as the summary loop above —
            // an unattributed entry never got a memberMap bucket to land in.
            if (isGuest && gName.isBlank()) continue;
            String  mapKey  = isGuest ? "guest::" + gName
                                      : "member::" + ((Number) r[0]).intValue();

            Map<String, Object> member = memberMap.get(mapKey);
            if (member == null) continue;

            if ("".equals(member.get("address1"))) {
                member.put("address1", str(r[4]));
                member.put("address2", str(r[5]));
                member.put("city",     str(r[6]));
                member.put("state",    str(r[7]));
                member.put("pinCode",  str(r[8]));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) member.get("entries");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("date",         toDateStr(r[9]));
            e.put("mainCategory", str(r[10]));
            e.put("subCategory",  str(r[11]));
            e.put("amount",       toBD(r[12]));
            e.put("method",       str(r[13]));
            entries.add(e);
        }

        BigDecimal grandTotal = memberMap.values().stream()
                .map(m -> (BigDecimal) m.get("memberTotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("year",        year);
        res.put("memberId",    memberId);
        res.put("guestName",   guestNameFilter);
        res.put("churchName",  churchName);
        res.put("members",     new ArrayList<>(memberMap.values()));
        res.put("grandTotal",  grandTotal);
        // Financial audit M5: income excluded from the statements above because
        // it has neither a matched member nor a guest name — surfaced here so
        // it stays visible to staff instead of silently vanishing from the
        // report, or worse, becoming a letter addressed to nobody.
        res.put("unattributedTotal", unattributedTotal);
        res.put("unattributedCount", unattributedCount);
        res.put("contributorType", type);
        return res;
    }

    private static Map<Integer, String> memberTypes(IncomeRepository repo, String appClientId, Collection<Integer> ids) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (ids.isEmpty() || appClientId == null) return out;
        List<Object[]> rows = repo.findMemberTypesForTaxReport(appClientId, new ArrayList<>(ids));
        if (rows != null) for (Object[] r : rows) if (r != null && r.length >= 2 && r[0] instanceof Number n) out.put(n.intValue(), r[1] == null ? "" : r[1].toString());
        return out;
    }

    private static String toDateStr(Object obj) {
        if (obj == null) return "";
        if (obj instanceof java.time.LocalDate ld)    return ld.toString();
        if (obj instanceof java.sql.Date sd)           return sd.toLocalDate().toString();
        if (obj instanceof java.util.Date ud)          return new java.sql.Date(ud.getTime()).toLocalDate().toString();
        return obj.toString();
    }

    private static BigDecimal toBD(Object obj) { return obj != null ? new BigDecimal(obj.toString()) : BigDecimal.ZERO; }

    private static String str(Object obj) { return obj != null ? obj.toString() : ""; }
}
