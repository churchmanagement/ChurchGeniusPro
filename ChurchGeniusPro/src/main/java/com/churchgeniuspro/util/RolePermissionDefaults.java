package com.churchgeniuspro.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The permissions a STAFF login has before anyone saved permissions for it (2026-11-06).
 *
 * <p>Until now a staff login whose permissions were never saved had NO map, and "no map" meant
 * "everything allowed" in {@link RoleGuard} and in the side navigation. The Permissions screen,
 * however, shows such a login with its role's defaults ticked ({@code viewUsers.html
 * ROLE_DEFAULTS}) — e.g. an Admin with Accounting unticked — so what the screen showed and what
 * the login could open disagreed: a never-saved Admin could type {@code /income} and use it.
 *
 * <p>Now (for Admin — see {@link #APPLY_WHEN_NOTHING_SAVED}) sign-in and {@link com.churchgeniuspro.service.PermissionRefresher} put the role's default
 * map on the session when nothing is saved, so the menu, the page guards and the API checks all
 * read the same map the screen shows. Nothing is written to the database; saving on the
 * Permissions screen stores the admin's choice as before.
 *
 * <p>{@link #ROLE_DEFAULTS} mirrors {@code viewUsers.html ROLE_DEFAULTS} exactly (pinned by
 * {@code RolePermissionDefaultsTest}). The keys are those of {@link PermissionCatalog}.
 */
public final class RolePermissionDefaults {

    private RolePermissionDefaults() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Section/key prefixes enabled by default per role; '*' = everything. Mirror of viewUsers.html. */
    public static final Map<String, List<String>> ROLE_DEFAULTS = Map.of(
            "SuperAdmin", List.of("*"),
            "Admin",      List.of("favorites", "admin", "general", "more", "activity", "member"),
            "Accountant", List.of("favorites", "accounting", "general", "more", "activity", "member"),
            "User",       List.of("favorites", "general", "more", "activity", "member"),
            "Limited",    List.of("favorites", "general", "more", "activity", "member"));

    /** True for the staff roles that have a defaults entry. */
    public static boolean isStaffRole(String role) {
        return role != null && ROLE_DEFAULTS.containsKey(role);
    }

    /**
     * Roles whose never-saved login gets the defaults instead of "everything allowed". Admin only
     * (2026-11-06): it is the one role where the old rule exposed pages the screen shows unticked
     * (Accounting). For the other roles the role guards already match the screen's defaults —
     * except Accountant + Family, which is kept as it is (the side menu shows Family to Accountants)
     * until the church decides otherwise. SuperAdmin's defaults are "everything" anyway.
     */
    public static final java.util.Set<String> APPLY_WHEN_NOTHING_SAVED = java.util.Set.of("Admin");

    /** True when a never-saved login of this role gets the role defaults. */
    public static boolean appliesTo(String role) {
        return role != null && APPLY_WHEN_NOTHING_SAVED.contains(role);
    }

    /**
     * Every catalogue key → its default for the role. A key is on when a default prefix equals it
     * or is a dotted prefix of it; a parent key (section or sub-section) is on when any key under
     * it is on — the same derivation as {@code getDefaultPermissions} on the Permissions screen.
     * Unknown role → every key off.
     */
    public static Map<String, Boolean> map(String role) {
        List<String> prefixes = ROLE_DEFAULTS.getOrDefault(role, List.of());
        boolean all = prefixes.contains("*");
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : PermissionCatalog.keys()) {
            out.put(k, all || prefixes.stream().anyMatch(p -> k.equals(p) || k.startsWith(p + ".")));
        }
        for (String k : PermissionCatalog.keys()) {
            if (out.get(k)) continue;
            String prefix = k + ".";
            boolean anyChild = out.entrySet().stream().anyMatch(e -> e.getKey().startsWith(prefix) && e.getValue());
            if (anyChild) out.put(k, true);
        }
        return out;
    }

    /** {@link #map} as the JSON string stored in the session's {@code privileges} attribute. */
    public static String json(String role) {
        try { return JSON.writeValueAsString(map(role)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /**
     * The map a staff session should carry: the saved one when there is one, otherwise the role's
     * defaults for the roles in {@link #APPLY_WHEN_NOTHING_SAVED}. Every other role is returned
     * unchanged (nothing saved = as before).
     */
    public static String effective(String role, String saved) {
        if (saved != null && !saved.isBlank() && !"null".equals(saved)) return saved;
        return appliesTo(role) ? json(role) : saved;
    }
}
