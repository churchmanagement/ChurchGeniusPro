-- ═══════════════════════════════════════════════════════════════════════════
-- W2 — Database audit H4: unique and mandatory tenant root keys
-- ═══════════════════════════════════════════════════════════════════════════
-- STAGED — run by hand in a maintenance window. NOT applied automatically on deploy.
--
-- service_client.client_id and church_registration.client_id are the tenant root keys,
-- yet both are nullable with no unique constraint, and app_user.client_id is nullable.
-- A second service_client row with the same client_id turns every login for that church
-- into IncorrectResultSizeDataAccessException; a NULL client_id belongs to no church and
-- is invisible to every scoped query. Production (16 Sep 2026) is clean — 0 duplicate,
-- 0 NULL on all three — so the constraints can be added safely.
--
-- The block first PROVES the data is clean and RAISES EXCEPTION (rolling itself back) if
-- it is not, naming what to fix — it never forces a constraint onto bad data. Idempotent.

DO $$
DECLARE
    n bigint;
BEGIN
    -- ── service_client.client_id: unique + NOT NULL ──
    IF to_regclass('public.service_client') IS NOT NULL THEN
        SELECT count(*) INTO n FROM service_client WHERE client_id IS NULL;
        IF n > 0 THEN RAISE EXCEPTION 'H4: % service_client row(s) have a NULL client_id — assign them before running this.', n; END IF;
        SELECT count(*) INTO n FROM (SELECT client_id FROM service_client GROUP BY client_id HAVING count(*) > 1) d;
        IF n > 0 THEN RAISE EXCEPTION 'H4: % duplicate service_client.client_id value(s) — resolve them before running this.', n; END IF;
        ALTER TABLE service_client ALTER COLUMN client_id SET NOT NULL;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_service_client_client_id') THEN
            ALTER TABLE service_client ADD CONSTRAINT uq_service_client_client_id UNIQUE (client_id);
        END IF;
        RAISE NOTICE 'H4: service_client.client_id is now UNIQUE and NOT NULL.';
    END IF;

    -- ── church_registration.client_id: unique + NOT NULL ──
    IF to_regclass('public.church_registration') IS NOT NULL THEN
        SELECT count(*) INTO n FROM church_registration WHERE client_id IS NULL;
        IF n > 0 THEN RAISE EXCEPTION 'H4: % church_registration row(s) have a NULL client_id — assign them before running this.', n; END IF;
        SELECT count(*) INTO n FROM (SELECT client_id FROM church_registration GROUP BY client_id HAVING count(*) > 1) d;
        IF n > 0 THEN RAISE EXCEPTION 'H4: % duplicate church_registration.client_id value(s) — resolve them before running this.', n; END IF;
        ALTER TABLE church_registration ALTER COLUMN client_id SET NOT NULL;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_church_registration_client_id') THEN
            ALTER TABLE church_registration ADD CONSTRAINT uq_church_registration_client_id UNIQUE (client_id);
        END IF;
        RAISE NOTICE 'H4: church_registration.client_id is now UNIQUE and NOT NULL.';
    END IF;

    -- ── app_user.client_id: NOT NULL (not unique — many users per church) ──
    IF to_regclass('public.app_user') IS NOT NULL THEN
        SELECT count(*) INTO n FROM app_user WHERE client_id IS NULL;
        IF n > 0 THEN RAISE EXCEPTION 'H4: % app_user row(s) have a NULL client_id — assign them before running this.', n; END IF;
        ALTER TABLE app_user ALTER COLUMN client_id SET NOT NULL;
        RAISE NOTICE 'H4: app_user.client_id is now NOT NULL.';
    END IF;
END $$;
