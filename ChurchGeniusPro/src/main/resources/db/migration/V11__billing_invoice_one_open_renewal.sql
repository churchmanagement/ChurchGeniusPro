-- ═══════════════════════════════════════════════════════════════════════════
-- V11 — One live renewal invoice per client per billing date
-- ═══════════════════════════════════════════════════════════════════════════
--
-- billing_invoice (entity BillingInvoice, created by Hibernate together with
-- client_charge and billing_invoice_line) holds platform invoices to client
-- churches. A RENEWAL invoice bills the client's next billing date (its end date),
-- stored in period_start when the invoice is created and never editable. The due
-- date CAN be edited in review, so it is deliberately NOT part of this rule.
--
-- BillingService returns the existing renewal invoice instead of creating a second
-- one; this partial unique index keeps that true when the Service Admin and the
-- billing-reminder job create one at the same moment. VOID invoices do not count, so
-- a voided renewal invoice can be replaced. MANUAL and REQUEST invoices are not
-- covered and may share any date with a renewal invoice.
--
-- Creates one index; reads and changes no data. Re-runnable. On a FRESH database
-- Flyway runs before Hibernate creates the table, so this no-ops and
-- config/BillingSchemaInitializer runs this same file again once the table exists.
-- If duplicates already exist (not possible through the application), a notice is
-- logged instead of failing and the index is not created.

DO $$
BEGIN
    IF to_regclass('public.billing_invoice') IS NULL THEN
        RAISE NOTICE 'V11: billing_invoice not present - skipped (fresh database; applied after startup).';
        RETURN;
    END IF;

    IF to_regclass('public.ux_billing_invoice_open_renewal') IS NULL THEN
        BEGIN
            CREATE UNIQUE INDEX ux_billing_invoice_open_renewal
                ON billing_invoice (client_id, period_start)
             WHERE kind = 'RENEWAL' AND status IN ('DRAFT', 'SENT', 'PAID');
        EXCEPTION WHEN unique_violation THEN
            RAISE NOTICE 'V11: duplicate renewal invoices exist - index not created yet (%).', SQLERRM;
        END;
    END IF;
END $$;
