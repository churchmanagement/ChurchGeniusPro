-- ═══════════════════════════════════════════════════════════════════════════
-- V12 — One invoice per Stripe PaymentIntent (Phase 6, platform card payments)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- billing_invoice.stripe_payment_intent_id (added by Hibernate with the Phase 6
-- entity change) records the card PaymentIntent of an invoice. A PaymentIntent must
-- never belong to two invoices. Partial unique index: NULL (no card payment started)
-- is allowed on any number of invoices.
--
-- Creates one index; reads and changes no data. Re-runnable. When Flyway runs before
-- Hibernate has added the column (or created the table), this no-ops and
-- config/BillingSchemaInitializer runs this same file again after startup. If
-- duplicates already exist (not possible through the application), a notice is
-- logged instead of failing and the index is not created.

DO $$
BEGIN
    IF to_regclass('public.billing_invoice') IS NULL
       OR NOT EXISTS (SELECT 1 FROM pg_attribute
                      WHERE attrelid = to_regclass('public.billing_invoice')
                        AND attname = 'stripe_payment_intent_id' AND NOT attisdropped) THEN
        RAISE NOTICE 'V12: billing_invoice.stripe_payment_intent_id not present - skipped (applied after startup).';
        RETURN;
    END IF;

    IF to_regclass('public.ux_billing_invoice_payment_intent') IS NULL THEN
        BEGIN
            CREATE UNIQUE INDEX ux_billing_invoice_payment_intent
                ON billing_invoice (stripe_payment_intent_id)
             WHERE stripe_payment_intent_id IS NOT NULL;
        EXCEPTION WHEN unique_violation THEN
            RAISE NOTICE 'V12: duplicate PaymentIntent ids exist - index not created yet (%).', SQLERRM;
        END;
    END IF;
END $$;
