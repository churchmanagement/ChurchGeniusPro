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

    /** One target column definition. */
    public record TargetColumn(String name, String type, List<String> synonyms) {}

    private static final Map<String, List<TargetColumn>> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("family", List.of(
            col("firstName",  "STRING",  "first_name","first","fname","given_name","givenname","forename","christian_name"),
            col("lastName",   "STRING",  "last_name","last","lname","surname","family_name","familyname"),
            col("otherName",  "STRING",  "nickname","nick","other_name","alias","preferred_name","goes_by","known_as"),
            col("email",      "EMAIL",   "email","email_address","e_mail","mail","emailid","email_id"),
            col("phone",      "PHONE",   "phone","phone_number","mobile","cell","cellphone","telephone","contact_number","contact","mobile_number"),
            col("gender",     "STRING",  "gender","sex"),
            col("memberType", "STRING",  "member_type","membership","membership_type","member_status","status","type"),
            col("role",       "STRING",  "role","relationship","relation","position","title","family_role"),
            col("address1",   "STRING",  "address","address1","address_line_1","addressline1","street","street_address","addr","addr1","line1"),
            col("address2",   "STRING",  "address2","address_line_2","addressline2","addr2","apt","unit","suite","line2"),
            col("city",       "STRING",  "city","town","city_name"),
            col("state",      "STRING",  "state","province","region","state_name"),
            col("country",    "STRING",  "country","nation","country_name"),
            col("pinCode",    "STRING",  "pin_code","pin","zip","zipcode","zip_code","postal_code","postcode","postalcode"),
            col("birthdayMonth","INTEGER","birthday_month","bday_month","dob_month","birth_month"),
            col("birthdayDay",  "INTEGER","birthday_day","bday_day","dob_day","birth_day"),
            col("birthdayYear", "INTEGER","birthday_year","bday_year","dob_year","birth_year"),
            col("phonePrivate","BOOLEAN", "phone_private","private_phone","hide_phone"),
            col("emailPrivate","BOOLEAN", "email_private","private_email","hide_email"),
            col("addressPrivate","BOOLEAN","address_private","private_address","hide_address")
        ));

        TABLES.put("income", List.of(
            col("amount",       "DECIMAL","amount","value","total","sum","contribution","gift","donation","gift_amount","contribution_amount"),
            col("incomeDate",   "DATE",   "date","income_date","gift_date","transaction_date","contribution_date","received_date","donation_date"),
            col("sourceName",   "STRING", "source","source_name","fund","fund_name","account","category","income_source"),
            col("subSourceName","STRING", "sub_source","subsource","sub_fund","subfund","subcategory","sub_category"),
            col("purposeName",  "STRING", "purpose","designation","memo","fund_purpose","intent"),
            col("method",       "STRING", "method","payment_method","pay_method","payment_type","mode","payment_mode","tender"),
            col("referenceNo",  "STRING", "reference","reference_no","ref","ref_no","check_no","check_number","cheque_no","cheque_number","transaction_id","txn_id"),
            col("notes",        "STRING", "note","notes","comment","comments","description","remarks"),
            col("familyLink",   "STRING", "family_key","family_id","household_id","member_id","contributor_id","donor_id","person_id","giver_id")
        ));

        TABLES.put("expense", List.of(
            col("amount",      "DECIMAL","amount","value","total","sum","cost","expense_amount"),
            col("expenseDate", "DATE",   "date","expense_date","paid_date","transaction_date","payment_date","posted_date"),
            col("category",    "STRING", "category","type","account","expense_category","expense_type"),
            col("purposeName", "STRING", "purpose","designation","memo","fund","intent"),
            col("payee",       "STRING", "payee","vendor","paid_to","recipient","merchant","supplier","vendor_name"),
            col("method",      "STRING", "method","payment_method","pay_method","payment_type","mode","payment_mode","tender"),
            col("referenceNo", "STRING", "reference","reference_no","ref","ref_no","check_no","check_number","invoice_no","invoice_number","transaction_id","txn_id"),
            col("notes",       "STRING", "note","notes","comment","comments","description","remarks"),
            col("familyLink",  "STRING", "member_id","payee_id","vendor_id","person_id")
        ));
    }

    private static TargetColumn col(String name, String type, String... synonyms) {
        return new TargetColumn(name, type, List.of(synonyms));
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
            case "EMAIL", "STRING" -> "trim";
            default        -> "trim";
        };
    }
}
