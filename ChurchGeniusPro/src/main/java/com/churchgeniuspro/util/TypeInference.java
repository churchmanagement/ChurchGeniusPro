package com.churchgeniuspro.util;

import java.util.regex.Pattern;

/**
 * Deterministic, single-value type classification for the ETL profile stage.
 * The profiler classifies every non-null cell in a column, then the column's
 * inferred type is the dominant cell type (see {@code SourceProfileService}).
 *
 * <p>Order matters: more specific patterns (EMAIL, PHONE, DATE, BOOLEAN) are
 * checked before the numeric/string fallbacks. Pure and side-effect free so it
 * can be unit-tested in isolation and ported to JS for parity checks.
 */
public final class TypeInference {

    private TypeInference() {}

    public static final String EMPTY   = "EMPTY";
    public static final String BOOLEAN = "BOOLEAN";
    public static final String INTEGER = "INTEGER";
    public static final String DECIMAL = "DECIMAL";
    public static final String DATE    = "DATE";
    public static final String EMAIL   = "EMAIL";
    public static final String PHONE   = "PHONE";
    public static final String STRING  = "STRING";

    private static final Pattern EMAIL_RE =
        Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern INT_RE =
        Pattern.compile("^[+-]?\\d{1,15}$");
    private static final Pattern DECIMAL_RE =
        Pattern.compile("^[+-]?(\\$\\s?)?\\d{1,3}(,\\d{3})*(\\.\\d+)?$|^[+-]?(\\$\\s?)?\\d+(\\.\\d+)?$");
    // ISO, US, and dotted dates: 2026-06-12, 06/12/2026, 6-12-26, 12.06.2026
    private static final Pattern DATE_RE =
        Pattern.compile("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}$|^\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}$");
    // 7–15 digits, allowing +, spaces, dashes, dots, parens
    private static final Pattern PHONE_RE =
        Pattern.compile("^\\+?[0-9().\\-\\s]{7,20}$");

    private static final java.util.Set<String> BOOL_WORDS = java.util.Set.of(
        "true", "false", "yes", "no", "y", "n", "1", "0", "t", "f");

    /** Classify a single raw cell value. */
    public static String classify(String raw) {
        if (raw == null) return EMPTY;
        String v = raw.trim();
        if (v.isEmpty()) return EMPTY;

        String lower = v.toLowerCase();
        if (BOOL_WORDS.contains(lower) && !isPureNumber(v)) return BOOLEAN;

        if (EMAIL_RE.matcher(v).matches()) return EMAIL;
        if (DATE_RE.matcher(v).matches()) return DATE;

        if (INT_RE.matcher(v).matches()) {
            // long digit strings that look like phone numbers, not quantities
            if (v.replaceAll("[+\\-]", "").length() >= 10) return PHONE;
            return INTEGER;
        }
        if (DECIMAL_RE.matcher(v).matches()) return DECIMAL;

        // Phone only if it has phone punctuation or a leading + and enough digits.
        if (PHONE_RE.matcher(v).matches() && countDigits(v) >= 7
                && v.matches(".*[().\\-+\\s].*")) return PHONE;

        return STRING;
    }

    /** Reconcile a previously-seen column type with a new cell type. */
    public static String reconcile(String soFar, String cell) {
        if (soFar == null || EMPTY.equals(soFar)) return cell;
        if (EMPTY.equals(cell) || soFar.equals(cell)) return soFar;
        // INTEGER ⊂ DECIMAL — a column with both is DECIMAL.
        if ((soFar.equals(INTEGER) && cell.equals(DECIMAL))
                || (soFar.equals(DECIMAL) && cell.equals(INTEGER))) return DECIMAL;
        return "MIXED";
    }

    private static boolean isPureNumber(String v) {
        return INT_RE.matcher(v).matches() || DECIMAL_RE.matcher(v).matches();
    }

    private static int countDigits(String v) {
        int d = 0;
        for (int i = 0; i < v.length(); i++) if (Character.isDigit(v.charAt(i))) d++;
        return d;
    }
}
