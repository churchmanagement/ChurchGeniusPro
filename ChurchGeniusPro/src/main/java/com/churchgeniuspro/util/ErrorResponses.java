package com.churchgeniuspro.util;

import org.slf4j.Logger;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one way a controller answers an unexpected failure (error-handling audit 2026-10-28, H4).
 *
 * <p>Before this, 56 endpoints wrote {@code e.getMessage()} into a 500 body — Hibernate/JDBC
 * text with the failing row's values, POI/PDFBox file paths, SMTP and Twilio replies, class
 * names. The log layer masks those; the HTTP layer did not. Now every such site logs the
 * throwable (stack trace included, through the masking layout) under a short reference and
 * returns only that reference, exactly as {@code GlobalExceptionHandler.handleAll} does.
 *
 * <p>Controllers keep their own user-facing prefix ("Could not read the file", "Failed to save
 * user") so the message still says what was being attempted; the detail lives in
 * {@code error-*.log} under the reference the user can quote.
 */
public final class ErrorResponses {

    private ErrorResponses() {}

    /** Eight hex characters: short enough to read over the phone, unique enough for a day's log. */
    public static String newRef() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Logs {@code ex} under a fresh reference and returns the user-facing sentence carrying it. */
    public static String message(Logger log, String what, Throwable ex) {
        String ref = newRef();
        log.error("{} [ref={}]: {}", what, ref, LogSafe.exception(ex), ex);
        String lead = (what == null || what.isBlank()) ? "An unexpected error occurred" : what.trim();
        if (lead.endsWith(".")) lead = lead.substring(0, lead.length() - 1);
        return lead + ". Please try again or contact support quoting reference " + ref + ".";
    }

    /** {@code {"error": "<what>. … reference X.", "ref": "X"}} — the body for a 500. */
    public static Map<String, Object> body(Logger log, String what, Throwable ex) {
        String msg = message(log, what, ex);
        String ref = msg.substring(msg.lastIndexOf(' ') + 1, msg.length() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", msg);
        out.put("ref", ref);
        return out;
    }

    /** A complete 500 response. */
    public static ResponseEntity<Map<String, Object>> internal(Logger log, String what, Throwable ex) {
        return ResponseEntity.status(500).body(body(log, what, ex));
    }
}
