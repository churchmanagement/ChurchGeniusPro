package com.churchgeniuspro.util;

import com.churchgeniuspro.service.SmsService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a batch of {@link SmsService.SendOutcome}s into an honest response body
 * (error-handling audit 2026-10-28, M7): {@code sent} counts only accepted messages,
 * {@code failed[]} names each refused recipient with the reason, and
 * {@link #providerFault} says whether the batch failed for a reason on OUR side
 * (credentials, unregistered sending number, carrier/provider outage) rather than
 * the recipient's (invalid number, STOP, no consent) — the former is a 502.
 */
public final class SmsOutcomes {
    private SmsOutcomes() {}

    /** Twilio codes that describe the recipient, not the sender. */
    static final Set<Integer> RECIPIENT_CODES = Set.of(21211, 21408, 21610, 21614, 30003, 30004, 30005, 30006);

    /** Recipient-level failure (invalid number / replied STOP / landline / no consent). */
    public static boolean recipientFault(SmsService.SendOutcome o) {
        if (o == null || o.sent()) return false;
        if (o.errorCode() != null) return RECIPIENT_CODES.contains(o.errorCode());
        String r = o.reason() == null ? "" : o.reason().toLowerCase();
        return r.contains("opt") || r.contains("consent") || r.contains("not a usable") || r.contains("invalid");
    }

    /** Sender/provider-level failure: a code outside the recipient set, or no code at all with no recipient wording. */
    public static boolean providerFault(SmsService.SendOutcome o) {
        return o != null && !o.sent() && !recipientFault(o);
    }

    /** One batch result. */
    public static final class Batch {
        public int sent;
        public final List<Map<String, Object>> failed = new ArrayList<>();
        private boolean anyProviderFault;

        public void record(String name, String to, SmsService.SendOutcome o) {
            if (o != null && o.sent()) { sent++; return; }
            Map<String, Object> f = new LinkedHashMap<>();
            if (name != null && !name.isBlank()) f.put("name", name);
            if (to != null) f.put("to", to);
            f.put("reason", o == null || o.reason() == null ? "Could not be sent" : o.reason());
            if (o != null && o.errorCode() != null) f.put("code", o.errorCode());
            failed.add(f);
            if (providerFault(o)) anyProviderFault = true;
        }

        /** True when nothing went out and at least one failure was the provider's/sender's fault → 502. */
        public boolean noneSentForProviderReason() { return sent == 0 && !failed.isEmpty() && anyProviderFault; }

        public Map<String, Object> body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", sent > 0 || failed.isEmpty());
            m.put("sent", sent);
            if (!failed.isEmpty()) m.put("failed", failed);
            if (sent == 0 && !failed.isEmpty()) {
                m.put("error", noneSentForProviderReason()
                        ? "No text messages could be sent: " + failed.get(0).get("reason")
                        : "No text messages were sent — see the failed recipients.");
            }
            return m;
        }
    }
}
