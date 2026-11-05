package com.churchgeniuspro.util;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side list of every permission key the Permissions screen can grant (AI Phase 0,
 * 2026-11-04). Until now the keys lived only in {@code viewUsers.html PERM_TREE}; a new
 * server-side feature that needs a key (the AI phases add several) had nowhere to declare it
 * and nothing checked that the page and the server agreed.
 *
 * <p>This catalogue is that declaration. {@code viewUsers.html} stays the <em>rendering</em> of
 * the tree (labels, grouping, plan filtering) — it is not rendered from here, so the page is
 * unchanged for users — and {@code security/PermissionCatalogSyncTest} fails the build when the
 * two sets of keys drift apart. Served read-only at {@code GET /api/permissions/catalog}.
 */
public final class PermissionCatalog {

    private PermissionCatalog() {}

    /** One key: dotted id, human label, the section it renders under. */
    public record Key(String key, String label, String section) {}

    public static final List<Key> KEYS = List.of(
        k("favorites", "Favorites", "favorites"),

        k("admin", "Admin", "admin"),
        k("admin.family", "Family", "admin"),
        k("admin.family.edit", "Family — Add or Edit", "admin"),
        k("admin.family.delete", "Family — Delete", "admin"),
        k("admin.family.inactive", "Family — Mark Inactive", "admin"),
        k("admin.groups", "Groups", "admin"),
        k("admin.groups.edit", "Groups — Add or Edit", "admin"),
        k("admin.groups.delete", "Groups — Delete", "admin"),
        k("admin.email", "Group Email", "admin"),
        k("admin.membership", "Membership Requests", "admin"),
        k("admin.membership.delete", "Membership Requests — Delete request", "admin"),
        k("admin.unsubscribed", "Unsubscribed", "admin"),

        k("accounting", "Accounting", "accounting"),
        k("accounting.income", "Income", "accounting"),
        k("accounting.income.edit", "Income — Add or Edit", "accounting"),
        k("accounting.income.delete", "Income — Delete", "accounting"),
        k("accounting.expense", "Expense", "accounting"),
        k("accounting.expense.edit", "Expense — Add or Edit", "accounting"),
        k("accounting.expense.delete", "Expense — Delete", "accounting"),
        k("accounting.bankimport", "Bank Import", "accounting"),
        k("accounting.pledges", "Pledges", "accounting"),
        k("accounting.pledges.edit", "Pledges — Add or Edit", "accounting"),
        k("accounting.pledges.delete", "Pledges — Delete", "accounting"),
        k("accounting.reports", "Reports", "accounting"),
        k("accounting.donation", "Donation Review", "accounting"),
        k("accounting.payroll", "Payroll", "accounting"),
        k("accounting.settings", "Accounting Settings", "accounting"),

        k("general", "General", "general"),
        k("general.meetings", "Meetings", "general"),
        k("general.meetings.edit", "Meetings — Add or Edit", "general"),
        k("general.meetings.delete", "Meetings — Delete", "general"),
        k("general.meetings.notify", "Meetings — Notify", "general"),
        k("general.meetings.category", "Meetings — Categories", "general"),
        k("general.attendance", "Attendance", "general"),
        k("general.attendance.edit", "Attendance — Add or Edit", "general"),
        k("general.attendance.delete", "Attendance — Delete", "general"),
        k("general.attendance.emailsms", "Attendance — Email / SMS", "general"),
        k("general.events", "Events", "general"),
        k("general.events.edit", "Events — Add or Edit", "general"),
        k("general.events.delete", "Events — Delete", "general"),
        k("general.events.emailsms", "Events — Email / SMS", "general"),
        k("general.ministry", "Ministry", "general"),
        k("general.ministry.kids", "Kids Ministry", "general"),
        k("general.ministry.worship", "Worship Planning", "general"),
        k("general.ministry.prayer", "Prayer Ministry", "general"),
        k("general.email", "Email", "general"),
        k("general.emailsettings", "Email Settings", "general"),
        k("general.reminders", "Reminders", "general"),

        k("activity", "Activity Corner", "activity"),
        k("activity.guessit", "Guess It", "activity"),

        k("more", "More", "more"),
        k("more.certificates", "Certificates", "more"),
        k("more.publicscreens", "Public Screens", "more"),
        k("more.followups", "Follow-ups", "more"),
        k("more.helpcenter", "Help Center", "more"),
        k("more.ticketing", "Ticketing", "more"),
        k("more.aiassistant", "AI Assistant", "more"),

        k("member", "Member Portal", "member"),
        k("member.family", "My Family", "member"),
        k("member.contributions", "My Contributions", "member"),
        k("member.groups", "My Groups", "member"),
        k("member.classes", "My Classes", "member"),
        k("member.volunteer", "Volunteering", "member"),
        k("member.directory", "Member Directory", "member")
    );

    private static Key k(String key, String label, String section) { return new Key(key, label, section); }

    public static Set<String> keys() { return KEYS.stream().map(Key::key).collect(Collectors.toCollection(java.util.LinkedHashSet::new)); }

    public static boolean isKnown(String key) { return key != null && keys().contains(key); }
}
