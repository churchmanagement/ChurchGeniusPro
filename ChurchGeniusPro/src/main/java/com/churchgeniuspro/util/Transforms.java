package com.churchgeniuspro.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The whitelisted ETL transforms (Phase 4). A confirmed {@code mapping_rule}
 * names exactly one of these; {@link #apply} coerces a raw source string into the
 * typed value the target column expects, throwing {@link TransformException} on
 * input that cannot be coerced (so the row is flagged, never silently wrong).
 *
 * <p>Only names returned by {@link EtlTargetSchema#suggestedTransform} (and the
 * extra {@code titlecase}) are accepted — an unknown transform is itself an error,
 * preventing arbitrary code paths. Pure and static for unit testing + JS parity.
 */
public final class Transforms {

    private Transforms() {}

    public static class TransformException extends RuntimeException {
        public TransformException(String m) { super(m); }
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("M-d-yyyy"),
        DateTimeFormatter.ofPattern("d.M.yyyy"),
        DateTimeFormatter.ofPattern("M/d/yy")
    );

    /**
     * Apply a named transform to a raw value. {@code null}/blank input returns
     * {@code null} (an empty source cell stays empty — not an error). The returned
     * Object is typed for the target column (String, LocalDate, BigDecimal,
     * Integer, or Boolean).
     */
    public static Object apply(String transform, String raw) {
        String t = transform == null || transform.isBlank() ? "trim" : transform.trim();
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;

        return switch (t) {
            case "trim"            -> v;
            case "titlecase"       -> titlecase(v);
            case "parse_date"      -> parseDate(v);
            case "parse_amount"    -> parseAmount(v);
            case "parse_int"       -> parseInt(v);
            case "parse_bool"      -> parseBool(v);
            case "normalize_phone" -> normalizePhone(v);
            default -> throw new TransformException("Unknown transform: " + transform);
        };
    }

    public static String titlecase(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        boolean start = true;
        for (char c : v.toLowerCase().toCharArray()) {
            if (Character.isWhitespace(c) || c == '-' || c == '\'') {
                start = true;
                sb.append(c);
            } else {
                sb.append(start ? Character.toUpperCase(c) : c);
                start = false;
            }
        }
        return sb.toString();
    }

    public static LocalDate parseDate(String v) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (Exception ignore) { /* try next */ }
        }
        throw new TransformException("Not a recognizable date: '" + v + "'");
    }

    public static BigDecimal parseAmount(String v) {
        // Strip currency symbols, thousands separators, spaces; keep sign + dot.
        String cleaned = v.replaceAll("[,$\\s]", "");
        boolean paren = cleaned.startsWith("(") && cleaned.endsWith(")");   // (123) → -123
        if (paren) cleaned = "-" + cleaned.substring(1, cleaned.length() - 1);
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new TransformException("Not a numeric amount: '" + v + "'");
        }
    }

    public static Integer parseInt(String v) {
        try {
            // tolerate "12.0" → 12
            if (v.matches("[+-]?\\d+\\.0+")) v = v.substring(0, v.indexOf('.'));
            return Integer.valueOf(v.replaceAll("[,\\s]", ""));
        } catch (NumberFormatException e) {
            throw new TransformException("Not an integer: '" + v + "'");
        }
    }

    public static Boolean parseBool(String v) {
        String s = v.trim().toLowerCase();
        return switch (s) {
            case "true", "yes", "y", "t", "1" -> Boolean.TRUE;
            case "false", "no", "n", "f", "0" -> Boolean.FALSE;
            default -> throw new TransformException("Not a boolean: '" + v + "'");
        };
    }

    /** Keep a leading +, then digits only. Validates a plausible 7–15 digit length. */
    public static String normalizePhone(String v) {
        boolean plus = v.trim().startsWith("+");
        String digits = v.replaceAll("\\D", "");
        if (digits.length() < 7 || digits.length() > 15)
            throw new TransformException("Not a valid phone number: '" + v + "'");
        return (plus ? "+" : "") + digits;
    }
}
