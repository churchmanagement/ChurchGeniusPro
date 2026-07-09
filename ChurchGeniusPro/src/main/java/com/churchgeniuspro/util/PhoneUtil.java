package com.churchgeniuspro.util;

/**
 * Utility methods for formatting phone numbers in a consistent, user-friendly way.
 */
public final class PhoneUtil {

    private PhoneUtil() {}

    /**
     * Formats a phone number string as {@code (XXX)XXX-XXXX} for display purposes.
     *
     * <p>Strips all non-digit characters first. If the result is exactly 10 digits,
     * returns the formatted string. If it is 11 digits and starts with {@code 1}
     * (North-American country code), the leading {@code 1} is dropped before
     * formatting. Any other length is returned as-is (the raw input), so
     * international or partial numbers are not mangled.
     *
     * <p>A {@code null} or blank input returns an empty string.
     *
     * @param phone raw phone string from the database or user input
     * @return formatted display string, or the original value when the format is unknown
     */
    public static String format(String phone) {
        if (phone == null || phone.isBlank()) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            return "(" + digits.substring(0, 3) + ")"
                       + digits.substring(3, 6) + "-"
                       + digits.substring(6);
        }
        return phone; // return original for non-standard lengths
    }
}
