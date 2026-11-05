package com.churchgeniuspro.mcp;

import com.churchgeniuspro.util.EmailMask;
import com.churchgeniuspro.util.PhoneMask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PII redaction for tool results handed to an external model (AI Phase 5; the TouchPoint model:
 * a token can be issued without contact details). Applied to every result unless the token
 * carries the {@code contact} scope.
 *
 * <p>Two passes over the whole result tree: (1) any value under a key that names contact data
 * (e-mail, phone, mobile, cell, address, street, zip, postal) is replaced by a mask; (2) every
 * other string has e-mail addresses and phone-shaped digit runs masked in place, so a contact
 * detail inside a note or a sentence does not leak either. The structure, counts and names are
 * untouched — the client's model can still reason over who, not how to reach them.
 */
public final class McpRedactor {

    static final Pattern CONTACT_KEY = Pattern.compile("(?i)^(e-?mail\\w*|phone\\w*|mobile\\w*|cell\\w*|address\\w*|street\\w*|zip\\w*|postal\\w*|addr\\d*|contact)$");
    public static final String ADDRESS_MASK = "[address withheld]";

    private McpRedactor() {}

    @SuppressWarnings("unchecked")
    public static Object redact(Object o) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                out.put(k, CONTACT_KEY.matcher(k).matches() ? maskValue(k, e.getValue()) : redact(e.getValue()));
            }
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(redact(x));
            return out;
        }
        if (o instanceof Object[] arr) { List<Object> out = new ArrayList<>(arr.length); for (Object x : arr) out.add(redact(x)); return out; }
        if (o instanceof String s) return maskText(s);
        return o;   // numbers, booleans, dates
    }

    private static Object maskValue(String key, Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> || v instanceof List<?>) return redact(v);
        String s = String.valueOf(v);
        if (s.isBlank()) return s;
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("e") ) return EmailMask.mask(s);
        if (k.startsWith("phone") || k.startsWith("mobile") || k.startsWith("cell")) return PhoneMask.mask(s);
        return ADDRESS_MASK;
    }

    /** E-mail addresses and phone-shaped digit runs inside free text. */
    public static String maskText(String s) {
        if (s == null || s.isEmpty()) return s;
        String t = s.indexOf('@') >= 0 ? EmailMask.maskAll(s) : s;
        return PhoneMask.maskAll(t);
    }
}
