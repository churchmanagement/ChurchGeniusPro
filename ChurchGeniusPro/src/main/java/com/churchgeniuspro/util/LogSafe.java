package com.churchgeniuspro.util;

import com.churchgeniuspro.logging.SensitiveDataMasker;

/**
 * One-line summaries of exceptions for log statements that used to print {@code e.getMessage()}
 * or the whole throwable (2026-10-28 logging audit H3/H4): provider and mail exceptions echo the
 * recipient address or number, PostgreSQL echoes the rejected row. Nothing here is ever thrown.
 */
public final class LogSafe {
    private LogSafe() {}

    /** {@code ClassName: message} with e-mail addresses, phone numbers and credentials masked. */
    public static String exception(Throwable t) {
        if (t == null) return "(no exception)";
        String msg = t.getMessage();
        String summary = t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + text(msg));
        return summary.length() > 500 ? summary.substring(0, 500) + "…" : summary;
    }

    /** Free text (a provider response body, a subject) with addresses, numbers and credentials masked and length-capped. */
    public static String text(String s) {
        if (s == null) return null;
        String out = SensitiveDataMasker.mask(PhoneMask.maskAll(EmailMask.maskAll(s)));
        return out.length() > 500 ? out.substring(0, 500) + "…" : out;
    }
}
