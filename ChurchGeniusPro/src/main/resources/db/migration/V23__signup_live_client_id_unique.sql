-- ═══════════════════════════════════════════════════════════════════════════
-- V23 — one LIVE signup row per client-ID (data-integrity audit 2026-10-28, H2)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- signup.client_id (MBR… member ref / USR… staff id / church id) is the login's key, but it
-- had no unique constraint. A member who deleted their portal account and registered again
-- got a second row with the same key, and the Optional finder then threw for every login.
--
-- Partial unique index over non-deleted rows only, so soft-deleted history never blocks a
-- re-registration. Created only when the live rows are already unique; otherwise the
-- duplicates are listed in a NOTICE and the index is skipped (re-run after cleaning up:
--   SELECT client_id, count(*) FROM signup WHERE deleted IS NOT TRUE GROUP BY client_id HAVING count(*) > 1;)
-- Re-runnable; on a fresh database Flyway runs before Hibernate creates signup, so guarded.
DO $$
DECLARE dup_count integer;
BEGIN
    IF to_regclass('public.signup') IS NULL THEN
        RAISE NOTICE 'V23: signup not created yet — index will be added on a later start';
        RETURN;
    END IF;
    SELECT count(*) INTO dup_count FROM (
        SELECT client_id FROM signup
        WHERE deleted IS NOT TRUE AND client_id IS NOT NULL
        GROUP BY client_id HAVING count(*) > 1) d;
    IF dup_count > 0 THEN
        RAISE NOTICE 'V23: % client_id value(s) have more than one live signup row — ux_signup_live_client_id NOT created', dup_count;
        RETURN;
    END IF;
    CREATE UNIQUE INDEX IF NOT EXISTS ux_signup_live_client_id
        ON signup (client_id) WHERE deleted IS NOT TRUE AND client_id IS NOT NULL;
END $$;
