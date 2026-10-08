-- ═══════════════════════════════════════════════════════════════════════════
-- V9 — One open subscription request per church
-- ═══════════════════════════════════════════════════════════════════════════
--
-- subscription_request (entity SubscriptionRequest, created by Hibernate) holds
-- requests from /subscriptionReq. A request is open while NEW or IN_PROGRESS.
-- SubscriptionRequestService refuses a second open request for a church; this
-- partial unique index keeps that true for two submits at the same moment.
--
-- Re-runnable. On a FRESH database Flyway runs before Hibernate creates the table,
-- so this no-ops and config/SubscriptionRequestSchemaInitializer runs this same file
-- again once the table exists. If duplicate open requests already exist (not
-- possible through the application), a notice is logged instead of failing.

DO $$
BEGIN
    IF to_regclass('public.subscription_request') IS NULL THEN
        RAISE NOTICE 'V9: subscription_request not present - skipped (fresh database; applied after startup).';
        RETURN;
    END IF;

    IF to_regclass('public.ux_subscription_request_open_client') IS NULL THEN
        BEGIN
            CREATE UNIQUE INDEX ux_subscription_request_open_client
                ON subscription_request (client_id)
             WHERE status IN ('NEW', 'IN_PROGRESS');
        EXCEPTION WHEN unique_violation THEN
            RAISE NOTICE 'V9: duplicate open subscription requests exist - index not created yet (%).', SQLERRM;
        END;
    END IF;
END $$;
