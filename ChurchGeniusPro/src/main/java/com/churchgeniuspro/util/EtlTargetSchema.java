package com.churchgeniuspro.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical target schema for the ETL mapper: for each loadable table
 * ({@code family}, {@code income}, {@code expense}) the columns we can fill, each
 * with a semantic type and a synonym dictionary of likely source-column names.
 *
 * <p>This is the single source of truth the deterministic matcher and the AI
 * fallback both use, so suggestions stay consistent. Types here are the SEMANTIC
 * target types (see {@link TypeInference}); the suggested transform is derived
 * from the type and is a whitelisted name implemented in Phase 4.
 *
 * <p>Column names mirror the {@code staging_*} entity fields exactly, so a
 * confirmed mapping writes straight into staging without renaming.
 */
public final class EtlTargetSchema {

    private EtlTargetSchema() {}

    /**
     * One target column definition.
     *
     * @param required   a loadable row must carry a value (validation ERROR when blank; the
     *                   mapping review flags an unmapped required column before staging)
     * @param dictionary name of the value dictionary the column's text goes through at stage
     *                   time ({@code fund}, {@code method}, {@code category}, {@code gender},
     *                   {@code role}, {@code memberType}); null = free text
     * @param virtual    not a staging property itself — {@code birthDate} fills the three
     *                   birthday integers, {@code fullName} is split into first/last name
     */
    public record TargetColumn(String name, String type, List<String> synonyms,
                               boolean required, String dictionary, boolean virtual) {
        /** Compatibility constructor: optional free-text, non-virtual column. */
        public TargetColumn(String name, String type, List<String> synonyms) {
            this(name, type, synonyms, false, null, false);
        }
    }

    /** Names of the value dictionaries a run may edit (Phase 1; Phase 4 adds attendance status). */
    public static final List<String> DICTIONARIES = List.of("fund", "method", "category", "gender", "role", "memberType", "attendanceStatus");

    /**
     * Migration Phase 4: tables staged into the generic {@code staging_record} (typed values
     * as JSON) instead of a dedicated staging table. family / income / expense keep theirs.
     */
    public static final List<String> RECORD_TABLES = List.of(
        "pledge_campaign", "pledge", "event", "event_registration", "attendance",
        "group", "group_member", "km_child", "km_checkin");

    public static boolean isRecordTable(String table) { return table != null && RECORD_TABLES.contains(table); }

    /** Human label per table (UI tiles, messages). */
    public static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("family", "People & households"), Map.entry("income", "Income / donations"), Map.entry("expense", "Expenses"),
        Map.entry("pledge_campaign", "Pledge campaigns"), Map.entry("pledge", "Pledges"),
        Map.entry("event", "Events"), Map.entry("event_registration", "Event registrations"), Map.entry("attendance", "Attendance"),
        Map.entry("group", "Groups"), Map.entry("group_member", "Group memberships"),
        Map.entry("km_child", "Kids Ministry children"), Map.entry("km_checkin", "Kids Ministry check-ins"));

    /**
     * Migration Phase 4: header names that are the ROW's own id for a table (normalised:
     * lower-case alphanumerics). Used only when the admin said what the file contains, so a
     * registration file's "Event ID" (a link) is never mistaken for the registration's id.
     */
    private static final Map<String, List<String>> ROW_ID_COLUMNS = Map.of(
        "pledge_campaign",    List.of("campaignid", "pledgecampaignid", "id"),
        "pledge",             List.of("pledgeid", "commitmentid", "id"),
        "event",              List.of("eventid", "id"),
        "event_registration", List.of("registrationid", "rsvpid", "attendeeid", "id"),
        "attendance",         List.of("attendanceid", "attendancerecordid", "id"),
        "group",              List.of("groupid", "tagid", "id"),
        "group_member",       List.of("membershipid", "groupmemberid", "id"),
        "km_child",           List.of("childid", "kidid", "id"),
        "km_checkin",         List.of("checkinid", "kidscheckinid", "id"));

    public static List<String> rowIdColumns(String table) { return ROW_ID_COLUMNS.getOrDefault(table, List.of()); }

    private static final Map<String, List<TargetColumn>> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("family", List.of(
            col("firstName",  "STRING",  "first_name","first","fname","given_name","givenname","forename","christian_name"),
            col("lastName",   "STRING",  "last_name","last","lname","surname","family_name","familyname"),
            virt("fullName",  "STRING",  "full_name","fullname","name","member_name","person_name","contact_name","display_name"),
            col("otherName",  "STRING",  "nickname","nick","other_name","alias","preferred_name","goes_by","known_as"),
            col("email",      "EMAIL",   "email","email_address","e_mail","mail","emailid","email_id"),
            col("phone",      "PHONE",   "phone","phone_number","mobile","cell","cellphone","telephone","contact_number","contact","mobile_number","mobile_phone","cell_phone","home_phone"),
            dict("gender",     "STRING", "gender", "gender","sex"),
            dict("memberType", "STRING", "memberType", "member_type","membership","membership_type","member_status","status","type"),
            dict("role",       "STRING", "role", "role","relationship","relation","position","title","family_role","family_position"),
            col("address1",   "STRING",  "address","address1","address_line_1","addressline1","street","street_address","addr","addr1","line1"),
            col("address2",   "STRING",  "address2","address_line_2","addressline2","addr2","apt","unit","suite","line2"),
            col("city",       "STRING",  "city","town","city_name"),
            col("state",      "STRING",  "state","province","region","state_name"),
            col("country",    "STRING",  "country","nation","country_name"),
            col("pinCode",    "STRING",  "pin_code","pin","zip","zipcode","zip_code","postal_code","postcode","postalcode"),
            col("birthdayMonth","INTEGER","birthday_month","bday_month","dob_month","birth_month"),
            col("birthdayDay",  "INTEGER","birthday_day","bday_day","dob_day","birth_day"),
            col("birthdayYear", "INTEGER","birthday_year","bday_year","dob_year","birth_year"),
            virt("birthDate",   "DATE",   "birth_date","birthdate","dob","date_of_birth","birthday","born"),
            col("phonePrivate","BOOLEAN", "phone_private","private_phone","hide_phone"),
            col("emailPrivate","BOOLEAN", "email_private","private_email","hide_email"),
            col("addressPrivate","BOOLEAN","address_private","private_address","hide_address")
        ));

        TABLES.put("income", List.of(
            req("amount",       "DECIMAL","amount","value","total","sum","contribution","gift","donation","gift_amount","contribution_amount","gross"),
            req("incomeDate",   "DATE",   "date","income_date","gift_date","transaction_date","contribution_date","received_date","donation_date","received_at"),
            dict("sourceName",  "STRING", "fund", "source","source_name","fund","fund_name","funds","account","category","income_source","designation"),
            dict("subSourceName","STRING","fund", "sub_source","subsource","sub_fund","subfund","subcategory","sub_category"),
            dict("method",      "STRING", "method", "method","payment_method","pay_method","payment_type","mode","payment_mode","tender"),
            col("referenceNo",  "STRING", "reference","reference_no","ref","ref_no","check_no","check_number","cheque_no","cheque_number","transaction_id","txn_id"),
            col("notes",        "STRING", "note","notes","comment","comments","description","remarks"),
            col("familyLink",   "STRING", "family_key","family_id","household_id","member_id","contributor_id","donor_id","person_id","giver_id","breeze_id","individual_id"),
            col("guestName",    "STRING", "guest_name","donor_name","contributor_name","contributor","donor","giver","name","full_name")
        ));

        TABLES.put("expense", List.of(
            req("amount",      "DECIMAL","amount","value","total","sum","cost","expense_amount"),
            req("expenseDate", "DATE",   "date","expense_date","paid_date","transaction_date","payment_date","posted_date"),
            dict("category",   "STRING", "category", "category","type","account","expense_category","expense_type"),
            dict("purposeName","STRING", "category", "purpose","designation","memo","fund","intent"),
            col("payee",       "STRING", "payee","vendor","paid_to","recipient","merchant","supplier","vendor_name"),
            dict("method",     "STRING", "method", "method","payment_method","pay_method","payment_type","mode","payment_mode","tender"),
            col("referenceNo", "STRING", "reference","reference_no","ref","ref_no","check_no","check_number","invoice_no","invoice_number","transaction_id","txn_id"),
            col("notes",       "STRING", "note","notes","comment","comments","description","remarks")
        ));

        // ── Migration Phase 4: new data types (staged into staging_record) ──
        // *Link columns hold ANOTHER table's source id (Person Id, Campaign Id, Event Id …);
        // the names next to them are the fallback when the file has no ids.

        TABLES.put("pledge_campaign", List.of(
            req("campaignName", "STRING",  "campaign","campaign_name","name","pledge_campaign","drive","campaign_title","title"),
            dict("fund",        "STRING",  "fund", "fund","fund_name","designation","income_fund","sub_source"),
            col("targetAmount", "DECIMAL", "goal","target","target_amount","goal_amount","campaign_goal"),
            col("startDate",    "DATE",    "start_date","starts","start","begin_date","created","created_date","campaign_start"),
            col("endDate",      "DATE",    "end_date","ends","end","deadline","close_date","campaign_end"),
            col("description",  "STRING",  "description","details","notes","note","purpose"),
            col("status",       "STRING",  "status","state","campaign_status"),
            col("otherGifts",   "STRING",  "other_gifts","gifts","in_kind","other")
        ));

        TABLES.put("pledge", List.of(
            col("campaignLink",  "STRING",  "campaign_id","pledge_campaign_id","campaignid"),
            col("campaignName",  "STRING",  "campaign","campaign_name","pledge_campaign","drive","fund_drive"),
            req("pledgeAmount",  "DECIMAL", "pledge_amount","amount","pledged","pledge","commitment","total_pledge","pledge_total"),
            col("monthlyAmount", "DECIMAL", "monthly_amount","monthly","per_month","installment"),
            col("personLink",    "STRING",  "person_id","member_id","donor_id","contributor_id","individual_id","breeze_id","giver_id"),
            col("guestName",     "STRING",  "name","full_name","donor","donor_name","pledger","contributor","giver"),
            col("amountCollected","DECIMAL", "collected","amount_collected","paid","amount_paid","received"),
            col("gifts",         "STRING",  "gifts","in_kind","other_gifts"),
            col("notes",         "STRING",  "note","notes","comment","comments","remarks")
        ));

        TABLES.put("event", List.of(
            req("eventName",   "STRING",  "event","event_name","name","title","event_title"),
            req("eventDate",   "DATE",    "date","event_date","start_date","starts","start","day"),
            col("startTime",   "TIME",    "start_time","time","begins","from_time"),
            col("endTime",     "TIME",    "end_time","ends_at","until","to_time"),
            col("eventType",   "STRING",  "event_type","type","kind"),
            col("address1",    "STRING",  "location","address","venue","address1","street","place"),
            col("city",        "STRING",  "city","town"),
            col("state",       "STRING",  "state","province","region"),
            col("pinCode",     "STRING",  "zip","zip_code","postal_code","postcode","pin_code"),
            col("fee",         "STRING",  "fee","cost","price","ticket_price"),
            col("maxCapacity", "INTEGER", "capacity","max_capacity","limit","max_attendees","seats")
        ));

        TABLES.put("event_registration", List.of(
            col("eventLink",  "STRING",  "event_id","eventid"),
            col("eventName",  "STRING",  "event","event_name","event_title"),
            col("eventDate",  "DATE",    "event_date","date"),
            col("personLink", "STRING",  "person_id","member_id","individual_id","breeze_id"),
            col("firstName",  "STRING",  "first_name","first","given_name"),
            col("lastName",   "STRING",  "last_name","last","surname","family_name"),
            virt("fullName",  "STRING",  "name","full_name","attendee","registrant","attendee_name"),
            col("email",      "EMAIL",   "email","email_address","e_mail"),
            col("phone",      "PHONE",   "phone","mobile","cell","phone_number"),
            col("adults",     "INTEGER", "adults","adult_count","number_of_adults","guests"),
            col("kids",       "INTEGER", "kids","children","child_count","number_of_children"),
            col("attending",  "BOOLEAN", "attending","rsvp","going","response"),
            col("checkedIn",  "BOOLEAN", "checked_in","attended","present","checkedin"),
            col("note",       "STRING",  "note","notes","comment","comments")
        ));

        TABLES.put("attendance", List.of(
            req("attendanceDate", "DATE",     "date","attendance_date","service_date","event_date","day"),
            col("serviceType",    "STRING",   "service","service_type","service_name","event","event_name","meeting","worship_service"),
            col("personLink",     "STRING",   "person_id","member_id","individual_id","breeze_id"),
            col("firstName",      "STRING",   "first_name","first","given_name"),
            col("lastName",       "STRING",   "last_name","last","surname"),
            virt("fullName",      "STRING",   "name","full_name","person","attendee","member_name","person_name"),
            dict("status",        "STRING",   "attendanceStatus", "status","attendance_status","present","attended"),
            col("checkInTime",    "DATETIME", "check_in","check_in_time","checkin_time","checked_in_at","time_in"),
            col("checkOutTime",   "DATETIME", "check_out","check_out_time","checkout_time","time_out"),
            col("ministry",       "STRING",   "ministry","department"),
            col("campus",         "STRING",   "campus","location","site")
        ));

        TABLES.put("group", List.of(
            req("groupName", "STRING", "group","group_name","name","tag","tag_name","small_group","ministry_group","title")
        ));

        TABLES.put("group_member", List.of(
            col("groupLink",  "STRING", "group_id","groupid","tag_id"),
            col("groupName",  "STRING", "group","group_name","tag","tag_name","small_group"),
            col("personLink", "STRING", "person_id","member_id","individual_id","breeze_id"),
            col("firstName",  "STRING", "first_name","first","given_name"),
            col("lastName",   "STRING", "last_name","last","surname"),
            virt("fullName",  "STRING", "name","full_name","member","member_name","person_name"),
            col("email",      "EMAIL",  "email","email_address","e_mail")
        ));

        TABLES.put("km_child", List.of(
            req("firstName",             "STRING", "first_name","first","child_first_name","given_name","kid_first_name"),
            req("lastName",              "STRING", "last_name","last","child_last_name","surname","kid_last_name"),
            col("dob",                   "DATE",   "dob","birth_date","birthdate","date_of_birth","birthday"),
            dict("gender",               "STRING", "gender", "gender","sex"),
            col("grade",                 "STRING", "grade","school_grade","class_year"),
            col("classroom",             "STRING", "classroom","class","room","age_group","class_name"),
            col("allergies",             "STRING", "allergies","allergy","food_allergies"),
            col("medicalNotes",          "STRING", "medical_notes","medical","medical_info","special_needs","health_notes"),
            col("parentLink",            "STRING", "parent_id","guardian_id","parent_person_id","household_head_id"),
            col("parentName",            "STRING", "parent","parent_name","guardian","guardian_name"),
            col("parentPhone",           "PHONE",  "parent_phone","guardian_phone","phone","mobile"),
            col("parentEmail",           "EMAIL",  "parent_email","guardian_email","email"),
            col("emergencyContactName",  "STRING", "emergency_contact","emergency_contact_name","emergency_name"),
            col("emergencyContactPhone", "PHONE",  "emergency_phone","emergency_contact_phone")
        ));

        TABLES.put("km_checkin", List.of(
            col("childLink",      "STRING",   "child_id","kid_id","childid"),
            col("childFirstName", "STRING",   "first_name","child_first_name","first"),
            col("childLastName",  "STRING",   "last_name","child_last_name","last"),
            col("childDob",       "DATE",     "dob","birth_date","birthdate","date_of_birth"),
            req("checkinTime",    "DATETIME", "check_in","check_in_time","checkin_time","checked_in_at","time_in","date"),
            col("checkoutTime",   "DATETIME", "check_out","check_out_time","checkout_time","checked_out_at","time_out"),
            col("classroom",      "STRING",   "classroom","class","room","age_group"),
            col("checkedOutBy",   "STRING",   "checked_out_by","picked_up_by","pickup_person"),
            col("notes",          "STRING",   "note","notes","comment","comments")
        ));
    }

    private static TargetColumn col(String name, String type, String... synonyms) {
        return new TargetColumn(name, type, List.of(synonyms), false, null, false);
    }

    private static TargetColumn req(String name, String type, String... synonyms) {
        return new TargetColumn(name, type, List.of(synonyms), true, null, false);
    }

    private static TargetColumn dict(String name, String type, String dictionary, String... synonyms) {
        return new TargetColumn(name, type, List.of(synonyms), false, dictionary, false);
    }

    private static TargetColumn virt(String name, String type, String... synonyms) {
        return new TargetColumn(name, type, List.of(synonyms), false, null, true);
    }

    /** One column by name, or null. */
    public static TargetColumn column(String table, String name) {
        for (TargetColumn c : columns(table)) if (c.name().equals(name)) return c;
        return null;
    }

    /** Required columns of a table (a loadable row must carry each). */
    public static List<TargetColumn> required(String table) {
        return columns(table).stream().filter(TargetColumn::required).toList();
    }

    /** Loadable target tables. */
    public static List<String> tables() {
        return List.copyOf(TABLES.keySet());
    }

    public static boolean isTable(String table) {
        return table != null && TABLES.containsKey(table);
    }

    /** Target columns for a table, in load order. */
    public static List<TargetColumn> columns(String table) {
        return TABLES.getOrDefault(table, List.of());
    }

    /** Whitelisted transform suggested for a semantic target type (implemented in Phase 4). */
    public static String suggestedTransform(String type) {
        return switch (type == null ? "" : type) {
            case "DATE"    -> "parse_date";
            case "DECIMAL" -> "parse_amount";
            case "INTEGER" -> "parse_int";
            case "BOOLEAN" -> "parse_bool";
            case "PHONE"   -> "normalize_phone";
            case "DATETIME" -> "parse_datetime";   // Phase 4
            case "TIME"    -> "parse_time";        // Phase 4
            case "EMAIL"   -> "first_of_list";   // Breeze exports "a@x.org, b@x.org"; a single address is unchanged
            case "STRING"  -> "trim";
            default        -> "trim";
        };
    }

    /** Every transform name a mapping rule may carry (see {@link Transforms}). */
    public static List<String> transforms() {
        return List.of("trim", "titlecase", "parse_date", "parse_amount", "parse_int", "parse_bool",
                       "normalize_phone", "first_of_list", "split_name_first", "split_name_last");
    }
}
