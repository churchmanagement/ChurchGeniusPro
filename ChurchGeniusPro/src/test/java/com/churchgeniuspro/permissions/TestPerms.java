package com.churchgeniuspro.permissions;

/**
 * Saved permission maps for tests (2026-11-06). An Admin whose permissions were never saved now
 * carries the Admin defaults — no Accounting — so a test that needs an Admin with Accounting
 * must give it the grant explicitly, exactly as a church administrator would on the Permissions
 * screen. Keys not listed stay "missing = allowed" (saved-map semantics).
 */
public final class TestPerms {
    private TestPerms() {}

    /** Admin granted every Accounting page (and its edit/delete actions). */
    public static final String ADMIN_WITH_ACCOUNTING = "{\"accounting\":true,\"accounting.income\":true,"
            + "\"accounting.income.edit\":true,\"accounting.income.delete\":true,\"accounting.expense\":true,"
            + "\"accounting.expense.edit\":true,\"accounting.expense.delete\":true,\"accounting.bankimport\":true,"
            + "\"accounting.pledges\":true,\"accounting.pledges.edit\":true,\"accounting.pledges.delete\":true,"
            + "\"accounting.reports\":true,\"accounting.donation\":true,\"accounting.payroll\":true,\"accounting.settings\":true}";
}
