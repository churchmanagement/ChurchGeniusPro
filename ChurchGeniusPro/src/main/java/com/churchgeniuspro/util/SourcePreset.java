package com.churchgeniuspro.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ETL Migration Phase 1 — source-application presets.
 *
 * <p>A preset describes what a church-management product's export files look
 * like, so the mapper can place a known header at confidence 1.0 before the
 * generic {@link MappingMatcher} scores the rest. A preset carries, per target
 * table, the <b>header aliases</b> for each target column, the <b>identifier
 * columns</b> (the source's own person / family / transaction ids, preserved as
 * {@code source_row_id} and used as the household key), the default
 * <b>date order</b>, the <b>list separators</b> a cell may contain, and
 * <b>value dictionaries</b> (e.g. Breeze "Head of Household" → our role).
 *
 * <p>Verification status is explicit: a preset is {@link #verified()} only once a
 * real export from that product has gone through the complete workflow. Until
 * then the UI labels it "Preset (unverified)". Aliases below are seeded from the
 * vendors' documented import templates and grid columns (see the ETL Import
 * Migration Plan, "Source applications"), never from guessed export headers.
 * Everything here is data; a header that matches no alias simply falls through
 * to the generic matcher, so a wrong alias can only cost a REVIEW, never a load.
 */
public enum SourcePreset {

    /** No preset — generic matcher only. */
    GENERIC("Generic CSV / Excel", "AUTO", false, Map.of(), Map.of(), Map.of()),

    BREEZE("Breeze ChMS", "MDY", false,
        Map.of(
            "family", aliases(
                "firstName",  "First Name",
                "lastName",   "Last Name",
                "otherName",  "Nickname",
                "gender",     "Gender",
                "memberType", "Status",
                "role",       "Family Role",
                "email",      "Email",
                "phone",      "Mobile",
                "address1",   "Street Address",
                "city",       "City",
                "state",      "State",
                "pinCode",    "Zip",
                "birthDate",  "Age;Birthdate;Birth Date;Date of Birth"),
            "income", aliases(
                "amount",        "Amount",
                "incomeDate",    "Date",
                "sourceName",    "Fund;Fund(s)",
                "method",        "Method;Payment Method",
                "referenceNo",   "Check Number",
                "notes",         "Note",
                "familyLink",    "Breeze ID;Person ID",
                "guestName",     "Name;Full Name")),
        Map.of("family", List.of("Breeze ID", "Family ID"),
               "income", List.of("Payment ID")),
        Map.of("role", Map.of("head of household", "Head of Household", "spouse", "Spouse", "adult", "Adult", "child", "Child"),
               "gender", Map.of("male", "Male", "female", "Female"))),

    CHMEETINGS("CHMeetings", "AUTO", false,
        Map.of(
            "family", aliases(
                "firstName",  "First Name",
                "lastName",   "Last Name",
                "otherName",  "Middle Name;Nickname",
                "gender",     "Gender",
                "memberType", "Status;Member Status;Membership Status",
                "role",       "Family Role",
                "email",      "Email;Email Address",
                "phone",      "Mobile Phone;Mobile;Phone",
                "address1",   "Address;Street Address",
                "city",       "City",
                "state",      "State",
                "country",    "Country",
                "pinCode",    "Zip;Zip Code;Postal Code",
                "birthDate",  "Birthdate;Birth Date;Date of Birth;DOB"),
            "income", aliases(
                "amount",        "Amount;Gross",
                "incomeDate",    "Date",
                "sourceName",    "Fund(s);Fund",
                "method",        "Payment Method",
                "referenceNo",   "Check Number;Transaction Id",
                "notes",         "Notes",
                "familyLink",    "Person Id",
                "guestName",     "Name")),
        Map.of("family", List.of("Person Id", "Family ID"),
               "income", List.of("Transaction Id")),
        Map.of("role", Map.of("primary", "Head of Household", "spouse", "Spouse", "child", "Child", "sibling", "Sibling",
                              "parent", "Parent", "grandparent", "Grandparent", "other", "Other"),
               "method", Map.of("card", "Card", "cash", "Cash", "check", "Check", "online", "Online",
                                "wire transfer", "Wire Transfer"))),

    GRACELY("Gracely", "AUTO", false,
        Map.of(
            "family", aliases(
                "fullName",   "Full Name;Name",
                "email",      "Email",
                "gender",     "Gender",
                "memberType", "Membership Type",
                "phone",      "Primary Telephone;Phone",
                "address1",   "Primary Address;Address",
                "birthDate",  "Birthday;Birthdate"),
            "income", aliases(
                "amount",        "Amount",
                "incomeDate",    "Date",
                "sourceName",    "Fund Name;Fund",
                "method",        "Payment Method",
                "referenceNo",   "Check Number / Note;Check Number",
                "guestName",     "Donor Name;Donor",
                "familyLink",    "Email")),
        Map.of(),
        Map.of("method", Map.of("cash", "Cash", "card", "Card", "envelope", "Envelope", "check", "Check",
                                "direct deposit", "Direct Deposit", "cashapp", "CashApp", "zelle", "Zelle",
                                "other", "Other"))),

    PLANNING_CENTER("Planning Center People / Giving", "AUTO", false,
        Map.of(
            "family", aliases(
                "firstName",  "First Name",
                "lastName",   "Last Name",
                "otherName",  "Nickname",
                "gender",     "Gender",
                "memberType", "Membership;Status",
                "email",      "Home Email;Email",
                "phone",      "Mobile Phone Number;Home Phone Number",
                "address1",   "Home Address Street Line 1",
                "address2",   "Home Address Street Line 2",
                "city",       "Home Address City",
                "state",      "Home Address State",
                "pinCode",    "Home Address Zip Code",
                "country",    "Home Address Country Code",
                "birthDate",  "Birthdate"),
            "income", aliases(
                "amount",        "Amount",
                "incomeDate",    "Received At;Date",
                "sourceName",    "Fund;Fund Name",
                "method",        "Payment Method;Method",
                "referenceNo",   "Check Number",
                "familyLink",    "Person ID;Donor ID",
                "guestName",     "Donor Name;Name")),
        Map.of("family", List.of("Person ID", "Household ID"),
               "income", List.of("Donation ID")),
        Map.of("role", Map.of("yes", "Head of Household"))),   // "Household primary contact" = Yes/No in PCO exports

    CCB("Church Community Builder", "AUTO", false,
        Map.of(
            "family", aliases(
                "firstName",  "First Name",
                "lastName",   "Last Name",
                "gender",     "Gender",
                "role",       "Family Position",
                "email",      "Email",
                "phone",      "Mobile Phone;Home Phone",
                "address1",   "Mailing Street;Street",
                "city",       "Mailing City;City",
                "state",      "Mailing State;State",
                "pinCode",    "Mailing Zip;Zip",
                "birthDate",  "Birthday;Birth Date"),
            "income", aliases(
                "amount",        "Amount",
                "incomeDate",    "Date",
                "sourceName",    "COA Category;Fund",
                "method",        "Payment Type;Method",
                "referenceNo",   "Check Number",
                "familyLink",    "Individual ID")),
        Map.of("family", List.of("Individual ID", "Family ID"),
               "income", List.of("Transaction ID")),
        Map.of("role", Map.of("primary contact", "Head of Household", "spouse", "Spouse", "child", "Child", "other", "Other")));

    private final String label;
    private final String dateOrder;        // AUTO | MDY | DMY | YMD
    private final boolean verified;
    private final Map<String, Map<String, List<String>>> aliasesByTable;   // table → target column → aliases
    private final Map<String, List<String>> idColumnsByTable;              // table → id header names, in priority order
    private final Map<String, Map<String, String>> dictionaries;           // dictionary → lower(source) → target

    SourcePreset(String label, String dateOrder, boolean verified,
                 Map<String, Map<String, List<String>>> aliasesByTable,
                 Map<String, List<String>> idColumnsByTable,
                 Map<String, Map<String, String>> dictionaries) {
        this.label = label;
        this.dateOrder = dateOrder;
        this.verified = verified;
        this.aliasesByTable = aliasesByTable;
        this.idColumnsByTable = idColumnsByTable;
        this.dictionaries = dictionaries;
    }

    public String label() { return label; }

    /** Default date order for this product's exports (AUTO when undocumented). */
    public String dateOrder() { return dateOrder; }

    /** True only after a real export has passed the end-to-end workflow. */
    public boolean verified() { return verified; }

    /** Aliases for one target column of one table (empty when the preset has none). */
    public List<String> aliases(String table, String targetColumn) {
        return aliasesByTable.getOrDefault(table, Map.of()).getOrDefault(targetColumn, List.of());
    }

    /** Target columns this preset knows about for a table. */
    public Map<String, List<String>> aliasesFor(String table) {
        return Collections.unmodifiableMap(aliasesByTable.getOrDefault(table, Map.of()));
    }

    /** The source's own identifier columns for a table, highest priority first. */
    public List<String> idColumns(String table) {
        return idColumnsByTable.getOrDefault(table, List.of());
    }

    /** Preset value dictionary (lower-cased source value → our value); empty when none. */
    public Map<String, String> dictionary(String name) {
        return dictionaries.getOrDefault(name, Map.of());
    }

    /**
     * Whether a source header is one of this preset's aliases for the target column.
     * Comparison ignores case, spaces and punctuation ("Family ID" == "family_id").
     */
    public boolean matches(String table, String targetColumn, String sourceHeader) {
        String n = norm(sourceHeader);
        if (n.isEmpty()) return false;
        for (String a : aliases(table, targetColumn)) if (norm(a).equals(n)) return true;
        return false;
    }

    /** First header in {@code headers} that is one of this preset's id columns for the table, or null. */
    public String idColumnIn(String table, List<String> headers) {
        if (headers == null) return null;
        for (String id : idColumns(table)) {
            String want = norm(id);
            for (String h : headers) if (norm(h).equals(want)) return h;
        }
        return null;
    }

    /** Parse a stored code; null/blank/unknown → GENERIC (never throws). */
    public static SourcePreset of(String code) {
        if (code == null || code.isBlank()) return GENERIC;
        try { return valueOf(code.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return GENERIC; }
    }

    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Builder helper: pairs of (targetColumn, "alias;alias;…"). */
    private static Map<String, List<String>> aliases(String... pairs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], List.of(pairs[i + 1].split(";")));
        }
        return Collections.unmodifiableMap(m);
    }
}
