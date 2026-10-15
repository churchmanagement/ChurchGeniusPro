package com.churchgeniuspro.util;

import java.util.List;
import java.util.Locale;

/**
 * Family roles — the one vocabulary new records are written with.
 *
 * <p>{@code family_member.role} is free text and production carries two generations of
 * values: the legacy set (Head, Wife, Son, Daughter, Father, Mother, …-in-Law) and the
 * canonical four the Family page offers. Existing rows are never rewritten; readers
 * accept both. Anything that <em>writes</em> a role — the public Membership Form, the
 * request approval — normalises through {@link #canonical(String)} so the two vocabularies
 * stop growing.
 */
public final class FamilyRoles {

    public static final String HEAD = "Head of Household";
    public static final String SPOUSE = "Spouse";
    public static final String ADULT = "Adult";
    public static final String CHILD = "Child";

    /** The options every role dropdown offers (same order as family.html). */
    public static final List<String> CANONICAL = List.of(HEAD, SPOUSE, ADULT, CHILD);

    private FamilyRoles() {}

    /**
     * Maps any stored or submitted role to one of the four canonical values.
     * {@code null}/blank → {@link #ADULT}; unknown words → {@link #ADULT}
     * (what the Family page has always done on load).
     */
    public static String canonical(String role) {
        if (role == null) return ADULT;
        String r = role.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty()) return ADULT;
        if (r.startsWith("head") || r.equals("primary") || r.equals("primary/head")) return HEAD;
        if (r.equals("spouse") || r.equals("wife") || r.equals("husband") || r.equals("partner")) return SPOUSE;
        if (r.equals("child") || r.equals("son") || r.equals("daughter") || r.startsWith("kid") || r.equals("minor")) return CHILD;
        return ADULT;   // adult, parent, father, mother, *-in-law, sibling, other, …
    }

    /** True for either spelling of the head of the household. */
    public static boolean isHead(String role) {
        return role != null && role.trim().toLowerCase(Locale.ROOT).startsWith("head");
    }

    /** True for either spelling of the spouse. */
    public static boolean isSpouse(String role) {
        return SPOUSE.equals(canonical(role)) ;
    }
}
