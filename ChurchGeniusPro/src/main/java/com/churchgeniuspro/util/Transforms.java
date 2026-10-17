package com.churchgeniuspro.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whitelisted ETL transforms. A confirmed {@code mapping_rule} names exactly
 * one of these; {@link #apply} coerces a raw source string into the typed value
 * the target column expects, throwing {@link TransformException} on input that
 * cannot be coerced (so the row is flagged, never silently wrong).
 *
 * <p>Only names returned by {@link EtlTargetSchema#transforms()} are accepted —
 * an unknown transform is itself an error, preventing arbitrary code paths. Pure
 * and static for unit testing + JS parity.
 *
 * <p>Migration Phase 1 additions: a per-run {@link Context} (the date order the
 * source product uses — Breeze exports are M/D/YYYY, Planning Center ISO, others
 * undocumented), month-name and ISO-timestamp dates, Excel serial dates,
 * {@code € £ USD} amounts, {@code first_of_list} for Breeze's comma-separated
 * emails, and {@code split_name_first}/{@code split_name_last} for Gracely's single
 * "Full Name" column. The legacy {@link #apply(String, String)} keeps the original
 * behaviour (AUTO date order), so existing runs are unaffected.
 */
public final class Transforms {

    private Transforms() {}

    public static class TransformException extends RuntimeException {
        public TransformException(String m) { super(m); }
    }

    /** Per-run transform settings. {@code dateOrder}: AUTO | MDY | DMY | YMD. */
    public record Context(String dateOrder) {
        public static final Context DEFAULT = new Context("AUTO");
        public static Context of(String dateOrder) {
            String d = dateOrder == null ? "AUTO" : dateOrder.trim().toUpperCase(Locale.ROOT);
            return switch (d) { case "MDY", "DMY", "YMD" -> new Context(d); default -> DEFAULT; };
        }
    }

    private static final List<DateTimeFormatter> ISO_FORMATS = List.of(
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy.M.d"),
        DateTimeFormatter.ofPattern("yyyyMMdd"));
    private static final List<DateTimeFormatter> MDY_FORMATS = List.of(
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("M-d-yyyy"),
        DateTimeFormatter.ofPattern("M.d.yyyy"),
        DateTimeFormatter.ofPattern("M/d/yy"),
        DateTimeFormatter.ofPattern("M-d-yy"));
    private static final List<DateTimeFormatter> DMY_FORMATS = List.of(
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("d-M-yyyy"),
        DateTimeFormatter.ofPattern("d.M.yyyy"),
        DateTimeFormatter.ofPattern("d/M/yy"),
        DateTimeFormatter.ofPattern("d-M-yy"));
    /** Month names, either order, with or without a comma: "Jan 5, 2024", "5 January 2024", "January 05 2024". */
    private static final List<DateTimeFormatter> NAMED_FORMATS = List.of(
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM-d-yyyy", Locale.ENGLISH));

    /** "2024-03-05 10:30:00", "2024-03-05T10:30:00Z", "03/05/2024 10:30am" — date part only. */
    private static final Pattern TIME_TAIL = Pattern.compile("^(.+?\\d)[ T](\\d{1,2}:\\d{2}(:\\d{2})?(\\.\\d+)?\\s*([AaPp][Mm])?(Z|[+-]\\d{2}:?\\d{2})?)$");
    private static final Pattern EXCEL_SERIAL = Pattern.compile("^\\d{4,6}(\\.0+)?$");
    private static final Pattern SLASHED = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2}|\\d{4})$");

    /** Legacy entry point — AUTO date order; behaviour for existing runs is unchanged. */
    public static Object apply(String transform, String raw) {
        return apply(transform, raw, Context.DEFAULT);
    }

    /**
     * Apply a named transform to a raw value. {@code null}/blank input returns
     * {@code null} (an empty source cell stays empty — not an error). The returned
     * Object is typed for the target column (String, LocalDate, BigDecimal,
     * Integer, or Boolean).
     */
    public static Object apply(String transform, String raw, Context ctx) {
        String t = transform == null || transform.isBlank() ? "trim" : transform.trim();
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        Context c = ctx == null ? Context.DEFAULT : ctx;

        return switch (t) {
            case "trim"             -> v;
            case "titlecase"        -> titlecase(v);
            case "parse_date"       -> parseDate(v, c);
            case "parse_amount"     -> parseAmount(v);
            case "parse_int"        -> parseInt(v);
            case "parse_bool"       -> parseBool(v);
            case "normalize_phone"  -> normalizePhone(v);
            case "first_of_list"    -> firstOfList(v);
            case "split_name_first" -> splitName(v)[0];
            case "split_name_last"  -> splitName(v)[1];
            case "parse_datetime"   -> parseDateTime(v, c);   // Phase 4
            case "parse_time"       -> parseTime(v);          // Phase 4
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

    /** Legacy: AUTO order (ISO, then M/D/Y, then D.M.Y) — unchanged for existing runs. */
    public static LocalDate parseDate(String v) {
        return parseDate(v, Context.DEFAULT);
    }

    /**
     * Date in the run's order. AUTO keeps the historical preference (month first for
     * ambiguous slashed dates) but, unlike before, also accepts month names, ISO
     * timestamps, Excel serial numbers and an unambiguous day-first value such as
     * 25/12/2024. MDY / DMY resolve the ambiguous case the way the admin chose.
     */
    public static LocalDate parseDate(String v, Context ctx) {
        String s = v.trim();
        Matcher tail = TIME_TAIL.matcher(s);
        if (tail.matches()) s = tail.group(1).trim();            // drop a time component

        if (EXCEL_SERIAL.matcher(s).matches()) {                   // Excel serial (1900 system)
            long serial = Long.parseLong(s.contains(".") ? s.substring(0, s.indexOf('.')) : s);
            if (serial >= 1 && serial <= 2958465) return LocalDate.of(1899, 12, 30).plusDays(serial);
        }
        for (DateTimeFormatter f : ISO_FORMATS)   { LocalDate d = tryParse(s, f); if (d != null) return d; }
        for (DateTimeFormatter f : NAMED_FORMATS) { LocalDate d = tryParse(s, f); if (d != null) return d; }

        String order = ctx == null ? "AUTO" : ctx.dateOrder();
        Matcher m = SLASHED.matcher(s);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            boolean dotted = s.indexOf('.') > 0;                     // "5.3.2024" is day-first (legacy d.M.yyyy)
            boolean dayFirst = switch (order) {
                case "DMY" -> true;
                case "MDY" -> false;
                default    -> dotted || (a > 12 && b <= 12);          // AUTO: dotted, or unambiguous
            };
            List<DateTimeFormatter> first = dayFirst ? DMY_FORMATS : MDY_FORMATS;
            for (DateTimeFormatter f : first) { LocalDate d = tryParse(s, f); if (d != null) return d; }
            if ("AUTO".equals(order)) {                              // AUTO: fall through to the other order
                for (DateTimeFormatter f : (dayFirst ? MDY_FORMATS : DMY_FORMATS)) {
                    LocalDate d = tryParse(s, f); if (d != null) return d;
                }
            }
        }
        throw new TransformException("Not a recognizable date: '" + v + "'"
            + ("AUTO".equals(order) ? "" : " (expected " + order.charAt(0) + "/" + order.charAt(1) + "/" + order.charAt(2) + ")"));
    }

    private static final Pattern TIME_ONLY = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?(?::(\\d{2}))?(?:\\.\\d+)?\\s*([AaPp]\\.?[Mm]\\.?)?$");

    /**
     * Migration Phase 4: a time of day as "HH:mm" (the live column format) — accepts
     * "9:30", "09:30:00", "9:30 AM", "7pm", "19:05".
     */
    public static String parseTime(String v) {
        Matcher m = TIME_ONLY.matcher(v.trim());
        if (!m.matches()) throw new TransformException("Not a recognizable time: '" + v + "'");
        int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String ap = m.group(4) == null ? null : m.group(4).replace(".", "").toUpperCase(Locale.ROOT);
        if (ap != null) {
            if (h < 1 || h > 12) throw new TransformException("Not a recognizable time: '" + v + "'");
            if ("AM".equals(ap) && h == 12) h = 0;
            if ("PM".equals(ap) && h != 12) h += 12;
        } else if (m.group(2) == null) {
            throw new TransformException("Not a recognizable time: '" + v + "' (use HH:mm or 7pm)");
        }
        if (h > 23 || min > 59) throw new TransformException("Not a recognizable time: '" + v + "'");
        return String.format("%02d:%02d", h, min);
    }

    /**
     * Migration Phase 4: date + time ("2026-03-01 09:30", "3/1/2026 9:30 AM",
     * "2026-03-01T09:30:00Z"). The date part follows the run's date order exactly like
     * {@link #parseDate(String, Context)}; a value with no time is midnight. A zone or
     * offset suffix is dropped (check-in times are wall-clock times at the church).
     */
    public static java.time.LocalDateTime parseDateTime(String v, Context ctx) {
        String s = v.trim();
        Matcher tail = TIME_TAIL.matcher(s);
        if (tail.matches()) {
            LocalDate d = parseDate(tail.group(1).trim(), ctx);
            String time = tail.group(2).replaceAll("(Z|[+-]\\d{2}:?\\d{2})$", "").trim();
            String hm = parseTime(time);
            String sec = "00";
            Matcher secs = Pattern.compile("^\\d{1,2}:\\d{2}:(\\d{2})").matcher(time);
            if (secs.find()) sec = secs.group(1);
            return d.atTime(Integer.parseInt(hm.substring(0, 2)), Integer.parseInt(hm.substring(3)), Integer.parseInt(sec));
        }
        return parseDate(s, ctx).atStartOfDay();
    }

    private static LocalDate tryParse(String s, DateTimeFormatter f) {
        try { return LocalDate.parse(s, f); } catch (DateTimeParseException e) { return null; }
    }

    /**
     * Money: strips currency symbols/codes ({@code $ € £ ₹ USD EUR GBP}), thousands
     * separators and spaces; {@code (123.45)} and a trailing {@code -} or {@code CR}
     * mean negative. European "1.234,56" is recognised when the last separator is a
     * comma followed by exactly two digits.
     */
    public static BigDecimal parseAmount(String v) {
        String s = v.trim();
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length() - 1); }
        s = s.replaceAll("(?i)\\b(USD|EUR|GBP|CAD|AUD|INR)\\b", "").replaceAll("[$€£₹\\s]", "");
        if (s.endsWith("-")) { negative = true; s = s.substring(0, s.length() - 1); }
        if (s.toUpperCase(Locale.ROOT).endsWith("CR")) { negative = true; s = s.substring(0, s.length() - 2); }
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1); }
        if (s.startsWith("+")) s = s.substring(1);
        // European decimal comma: "1.234,56" or "1234,56"
        if (s.matches("^\\d{1,3}(\\.\\d{3})*,\\d{1,2}$") || s.matches("^\\d+,\\d{1,2}$")) {
            s = s.replace(".", "").replace(',', '.');
        } else {
            s = s.replace(",", "");
        }
        if (s.isEmpty() || !s.matches("^\\d*(\\.\\d+)?$") || s.equals("."))
            throw new TransformException("Not a numeric amount: '" + v + "'");
        BigDecimal out = new BigDecimal(s.startsWith(".") ? "0" + s : s);
        return negative ? out.negate() : out;
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
            case "true", "yes", "y", "t", "1", "x", "checked" -> Boolean.TRUE;
            case "false", "no", "n", "f", "0", "unchecked"    -> Boolean.FALSE;
            default -> throw new TransformException("Not a boolean: '" + v + "'");
        };
    }

    /** Keep a leading +, then digits only. Validates a plausible 7–15 digit length. */
    public static String normalizePhone(String v) {
        String one = firstOfList(v);                                  // "555-1234; 555-9876" → first
        boolean plus = one.trim().startsWith("+");
        String digits = one.replaceAll("\\D", "");
        if (digits.length() < 7 || digits.length() > 15)
            throw new TransformException("Not a valid phone number: '" + v + "'");
        return (plus ? "+" : "") + digits;
    }

    /** First value of a {@code ;} / {@code ,} / newline separated list (Breeze emails, checkbox fields). */
    public static String firstOfList(String v) {
        String[] parts = v.split("[;,\\n]");
        for (String p : parts) if (!p.trim().isEmpty()) return p.trim();
        return v.trim();
    }

    /**
     * {@code [first, last]} from one name cell: "Last, First" when a comma is present,
     * else "First [Middle…] Last"; a single word is the last name. Never throws.
     */
    public static String[] splitName(String v) {
        String s = v.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return new String[]{null, null};
        if (s.contains(",")) {
            String[] p = s.split(",", 2);
            String first = p[1].trim(), last = p[0].trim();
            return new String[]{first.isEmpty() ? null : first, last.isEmpty() ? null : last};
        }
        int sp = s.lastIndexOf(' ');
        if (sp < 0) return new String[]{null, s};
        return new String[]{s.substring(0, sp).trim(), s.substring(sp + 1).trim()};
    }
}
