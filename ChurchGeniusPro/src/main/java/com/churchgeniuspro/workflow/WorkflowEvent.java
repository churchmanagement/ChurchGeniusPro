package com.churchgeniuspro.workflow;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened in a church (AI Phase 6). {@code trigger} is a {@link WorkflowSchema#TRIGGERS}
 * key, {@code facts} the fields that trigger declares, {@code eventKey} the idempotency key (the same
 * occurrence published twice has the same key → one run per rule), {@code subject} a short label for
 * the run history. Facts hold names, counts, codes and dates — never contact details or notes.
 */
public record WorkflowEvent(String trigger, String clientId, String eventKey, String subject, Map<String, Object> facts, LocalDateTime occurredAt) {

    public static WorkflowEvent of(String trigger, String clientId, String eventKey, String subject, Map<String, Object> facts) {
        return new WorkflowEvent(trigger, clientId, eventKey, subject, Collections.unmodifiableMap(new LinkedHashMap<>(facts)), LocalDateTime.now());
    }

    /** Builder-ish helper for call sites: {@code facts("name", n, "amount", a)}. */
    public static Map<String, Object> facts(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
