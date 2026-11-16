-- ═══════════════════════════════════════════════════════════════════════════
-- V24 — One live discount per client (promotions phase 2)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- client_promotion (created by Hibernate with the phase 2 entity) holds the
-- discounts the Service Admin gives customers. A client may hold at most one that is
-- SCHEDULED or ACTIVE; ClientPromotionService checks this, and this partial unique
-- index makes the database refuse a second one saved at the same moment. EXPIRED and
-- CANCELLED rows are unlimited — they are the history.
--
-- Creates one index; reads and changes no data. Re-runnable. When Flyway runs before
-- Hibernate has created the table this no-ops, and config/PromotionSchemaInitializer
-- runs this same file again after startup.

DO $$
BEGIN
    IF to_regclass('public.client_promotion') IS NULL THEN
        RAISE NOTICE 'V24: client_promotion not present - skipped (applied after startup).';
        RETURN;
    END IF;

    IF to_regclass('public.ux_client_promotion_one_live') IS NULL THEN
        BEGIN
            CREATE UNIQUE INDEX ux_client_promotion_one_live
                ON client_promotion (client_id)
             WHERE status IN ('SCHEDULED', 'ACTIVE');
        EXCEPTION WHEN unique_violation THEN
            RAISE NOTICE 'V24: a client already has two live discounts - index not created yet (%).', SQLERRM;
        END;
    END IF;
END $$;
