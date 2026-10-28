package com.churchgeniuspro.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone numbers for log lines: {@code +19135550100} / {@code (913) 555-0100} → {@code ***-***-0100}.
 *
 * <p>The last four digits are enough to match a log line to a record when investigating a
 * delivery problem; the full number is personal data and stays in the database (2026-10-28
 * logging audit H2). The masked form has no leading {@code +}, so the E.164 backstop in
 * {@code logging.SensitiveDataMasker} leaves it alone.
 */
public final class PhoneMask {
    private PhoneMask() {}

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern IN_TEXT = Pattern.compile("\\+?\\d[\\d\\s().-]{7,}\\d");

    /** One number. {@code null}/blank → {@code (no number)}; fewer than 4 digits → {@code ***}. */
    public static String mask(String phone) {
        if (phone == null || phone.isBlank()) return "(no number)";
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) return "***";
        return "***-***-" + digits.substring(digits.length() - 4);
    }

    /** Masks every phone-shaped run of digits inside free text (provider responses, exception messages). */
    public static String maskAll(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = IN_TEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // Only phone-sized runs (10–15 digits) — a timestamp or a short id is left alone.
            int digits = m.group().replaceAll("\\D", "").length();
            boolean phoneSized = digits >= 10 && digits <= 15 && !ISO_DATE.matcher(m.group()).find();
            m.appendReplacement(sb, Matcher.quoteReplacement(phoneSized ? mask(m.group()) : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
