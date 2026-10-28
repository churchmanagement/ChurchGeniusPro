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

    private static final java.util.regex.Pattern ADDRESS =
            java.util.regex.Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * Masks every e-mail address found inside free text — exception messages, provider
     * responses, subjects ("Invalid Addresses: john@x.org" → "Invalid Addresses: jo***@x.org").
     * {@code null} in, {@code null} out; text without an address is returned as is.
     */
    public static String maskAll(String text) {
        if (text == null || text.indexOf('@') < 0) return text;
        java.util.regex.Matcher m = ADDRESS.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(mask(m.group())));
        m.appendTail(sb);
        return sb.toString();
    }
}
