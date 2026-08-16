package com.churchgeniuspro.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-wide password policy, enforced on every flow that creates,
 * changes, or resets a password (signup, member signup, church registration,
 * forgot-password reset, service-admin change password).
 *
 * <p>Rules:
 * <ol>
 *   <li>At least 8 characters</li>
 *   <li>At least one lowercase letter (a-z)</li>
 *   <li>At least one uppercase letter (A-Z)</li>
 *   <li>At least one number (0-9)</li>
 *   <li>At least one special character (anything that is not a letter or digit,
 *       e.g. {@code ! @ # $ % ^ & * ( ) _ + - =})</li>
 * </ol>
 *
 * <p>Client-side screens display the same checklist; this class is the
 * authoritative server-side check.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    /** One-line summary suitable for API error messages and UI hints. */
    public static final String SUMMARY =
            "Password must be at least 8 characters and include an uppercase letter, "
          + "a lowercase letter, a number, and a special character (e.g. !@#$%^&*()_+-=).";

    /**
     * Validates the password against the policy.
     *
     * @return {@code null} when the password satisfies every rule; otherwise a
     *         human-readable message listing exactly which requirements failed.
     */
    public static String validate(String password) {
        if (password == null || password.isEmpty()) {
            return "Password is required. " + SUMMARY;
        }
        List<String> missing = new ArrayList<>();
        if (password.length() < 8)                 missing.add("at least 8 characters");
        if (!password.chars().anyMatch(Character::isLowerCase)) missing.add("a lowercase letter (a-z)");
        if (!password.chars().anyMatch(Character::isUpperCase)) missing.add("an uppercase letter (A-Z)");
        if (!password.chars().anyMatch(Character::isDigit))     missing.add("a number (0-9)");
        if (password.chars().allMatch(Character::isLetterOrDigit))
            missing.add("a special character (e.g. !@#$%^&*()_+-=)");

        if (missing.isEmpty()) return null;
        return "Password must contain " + String.join(", ", missing) + ".";
    }

    /** True when the password satisfies every rule. */
    public static boolean isValid(String password) {
        return validate(password) == null;
    }
}
