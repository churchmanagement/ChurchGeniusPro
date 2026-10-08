-- ═══════════════════════════════════════════════════════════════════════════
-- V8 — One open Trial Request per email address
-- ═══════════════════════════════════════════════════════════════════════════
--
-- A request is "open" while it awaits its emailed code (PENDING_VERIFICATION) or
-- the Service Admin's decision (VERIFIED). TrialRequestService refuses a second
-- open request for the same email; this partial unique index makes that rule hold
-- even for two submits that arrive at the same moment (double click, two tabs).
--
-- 1. Unverified requests older than 24 hours become VERIFICATION_EXPIRED (the
--    service applies the same rule on every submit and listing), so they never
--    block the address.
-- 2. The index is created only if no duplicate open requests remain. If some do
--    (possible only from before this release), a notice is logged and the index is
--    left for the next startup; the service-level check applies meanwhile.
--
-- Re-runnable. trial_request is created by Hibernate, so on a FRESH database this
-- no-ops here and config/TrialRequestSchemaInitializer runs this same file again
-- once the table exists.

DO $$
BEGIN
    IF to_regclass('public.trial_request') IS NULL THEN
        RAISE NOTICE 'V8: trial_request not present - skipped (fresh database; applied after startup).';
        RETURN;
    END IF;

    UPDATE trial_request
       SET status = 'VERIFICATION_EXPIRED'
     WHERE status = 'PENDING_VERIFICATION'
       AND created_at < now() - interval '24 hours';

    IF to_regclass('public.ux_trial_request_open_email') IS NULL THEN
        BEGIN
            CREATE UNIQUE INDEX ux_trial_request_open_email
                ON trial_request (lower(email))
             WHERE status IN ('PENDING_VERIFICATION', 'VERIFIED');
        EXCEPTION WHEN unique_violation THEN
            RAISE NOTICE 'V8: duplicate open trial requests exist - index not created yet (%).', SQLERRM;
        END;
    END IF;
END $$;
