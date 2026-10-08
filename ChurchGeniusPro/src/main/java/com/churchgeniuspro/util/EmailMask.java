package com.churchgeniuspro.util;

/** {@code john.doe@example.com} → {@code jo***@exa***.com}, for notices that must not expose an address. */
public final class EmailMask {
    private EmailMask() {}

    public static String mask(String email) {
        if (email == null || email.isBlank()) return "(no address)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String user = email.substring(0, at), domain = email.substring(at + 1);
        String mUser = user.length() <= 2 ? user.charAt(0) + "***" : user.substring(0, 2) + "***";
        String[] parts = domain.split("\\.", 2);
        String host = parts[0], tld = parts.length > 1 ? "." + parts[1] : "";
        String mHost = host.length() <= 3 ? host : host.substring(0, 3) + "***";
        return mUser + "@" + mHost + tld;
    }
}
