package com.churchgeniuspro.util;

import java.util.List;
import java.util.Set;

/**
 * Which permission a STAFF session needs to call an API (2026-11-06).
 *
 * <p>The page routes ({@code /income}, {@code /expense}, …) already check their page key; the
 * data APIs behind them checked only the role (and the edit/delete keys on writes). So an Admin
 * without Income could still read every gift with {@code GET /api/income}. This table closes that:
 * {@code webfilter/PermissionApiFilter} refuses a request when the session holds none of the keys
 * listed for its path.
 *
 * <p>Rules are matched in order, first match wins. A rule lists ANY-OF keys because some lists
 * are shared: the contributor and fund drop-downs of {@code /api/income} are also used by
 * Pledges, Reports, Bank Import and Donation, so they stay open to any of those pages. Paths
 * not listed here are unchanged (role checks only). Church logins are exempt, as everywhere in
 * the permission system; Member Portal sessions are governed by {@code MemberApiScopeFilter}.
 */
public final class PermissionApiCatalog {

    private PermissionApiCatalog() {}

    /**
     * @param prefix   canonical path, matched as itself or a segment under it ("/api/income" matches
     *                 "/api/income/5" but not "/api/income-report")
     * @param getsOnly true = only GET/HEAD are checked (writes keep their own edit/delete key checks)
     * @param anyOf    the session needs at least one of these keys
     */
    public record Rule(String prefix, boolean getsOnly, List<String> anyOf) {
        boolean matches(String path, String method) {
            if (getsOnly && !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))) return false;
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
    }

    private static final String INC = "accounting.income", EXP = "accounting.expense", BANK = "accounting.bankimport",
            PLEDGE = "accounting.pledges", REP = "accounting.reports", DON = "accounting.donation", PAY = "accounting.payroll";

    public static final List<Rule> RULES = List.of(
        // shared drop-downs first (more specific than /api/income, /api/expense)
        new Rule("/api/income/contributors", true, List.of(INC, REP, PLEDGE, BANK)),
        new Rule("/api/income/sub-sources",  true, List.of(INC, REP, PLEDGE, BANK, DON)),
        new Rule("/api/expense/purposes",    true, List.of(EXP, REP, BANK, DON)),
        new Rule("/api/expense/funds",       true, List.of(EXP, REP, BANK, DON)),
        // Income / Expense data (lists, last gift, quick-add, check images, vision status)
        new Rule("/api/income",  true,  List.of(INC)),
        new Rule("/api/expense", true,  List.of(EXP)),
        // Scan Check reads a check for the Income / Expense form
        new Rule("/api/income/check-scan",   false, List.of(INC)),
        new Rule("/api/income/check-parse",  false, List.of(INC)),
        new Rule("/api/expense/check-scan",  false, List.of(EXP)),
        new Rule("/api/expense/check-parse", false, List.of(EXP)),
        // pages whose APIs belong to them alone
        new Rule("/api/reports",           true,  List.of(REP)),
        new Rule("/api/pledges",           false, List.of(PLEDGE)),
        new Rule("/api/bank-import",       false, List.of(BANK)),
        // Bank Sync (Plaid) sits next to Bank Import and has no key of its own: it follows
        // Bank Import. The verification steps (/gate, /verify-email) and Plaid's webhook stay open.
        new Rule("/api/plaid/review",      false, List.of(BANK)),
        new Rule("/api/plaid/items",       false, List.of(BANK)),
        new Rule("/api/plaid/link-token",  false, List.of(BANK)),
        new Rule("/api/plaid/exchange",    false, List.of(BANK)),
        new Rule("/api/donations",         false, List.of(DON)),
        new Rule("/api/donation-settings", false, List.of(DON)),
        new Rule("/api/payroll",           false, List.of(PAY)));

    /** The rule for this request, or null when the path is not permission-gated. */
    public static Rule ruleFor(String path, String method) {
        if (path == null) return null;
        for (Rule r : RULES) if (r.matches(path, method)) return r;
        return null;
    }

    /** Every key used here — each must be a Permissions-screen key (pinned by a test). */
    public static Set<String> keys() {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        RULES.forEach(r -> s.addAll(r.anyOf()));
        return s;
    }
}
