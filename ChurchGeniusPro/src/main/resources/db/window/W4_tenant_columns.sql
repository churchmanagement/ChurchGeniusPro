-- ═══════════════════════════════════════════════════════════════════════════
-- W4 — Database audit H2: give the tenant-column-less tables a real tenant column
-- ═══════════════════════════════════════════════════════════════════════════
-- STAGED — run by hand in a maintenance window. NOT part of db/migration and NOT
-- applied automatically on deploy.
--
-- Ten tenant-owned tables carry no client_id / app_client_id column today; their
-- isolation rests entirely on the caller having resolved the parent under the right
-- tenant (they are exactly the tables TenantPurgePlanner has to scope through a parent
-- sub-select). This adds a real client_id to each, backfilled from its parent, plus a
-- tenant index. It is deliberately ADDITIVE and leaves the column NULLABLE: the matching
-- application change (entities map client_id; every create-path sets it) deploys with or
-- after this script, and only once new rows are populated does a follow-up flip the
-- column to NOT NULL (see "After the app is deployed" at the tail). Idempotent: the
-- column/index adds are IF NOT EXISTS and the backfill only touches rows still NULL, so
-- re-running is a no-op.
--
-- Order matters: worship_instrument is backfilled before worship_group_member, which
-- reads worship_instrument.client_id.

DO $$
DECLARE
    v_bf   int;
    v_null int;
BEGIN
    -- 1) worship_instrument <- worship_group (group_id)
    IF to_regclass('public.worship_instrument') IS NOT NULL THEN
        ALTER TABLE worship_instrument ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE worship_instrument c SET client_id = p.client_id
          FROM worship_group p WHERE p.id = c.group_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_worship_instrument_tenant ON worship_instrument (client_id);
        SELECT count(*) INTO v_null FROM worship_instrument WHERE client_id IS NULL;
        RAISE NOTICE 'worship_instrument: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 2) worship_group_member <- worship_instrument (instrument_id)  [after #1]
    IF to_regclass('public.worship_group_member') IS NOT NULL THEN
        ALTER TABLE worship_group_member ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE worship_group_member c SET client_id = p.client_id
          FROM worship_instrument p WHERE p.id = c.instrument_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_worship_group_member_tenant ON worship_group_member (client_id);
        SELECT count(*) INTO v_null FROM worship_group_member WHERE client_id IS NULL;
        RAISE NOTICE 'worship_group_member: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 3) worship_assignment_member <- worship_assignment (assignment_id)
    IF to_regclass('public.worship_assignment_member') IS NOT NULL THEN
        ALTER TABLE worship_assignment_member ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE worship_assignment_member c SET client_id = p.client_id
          FROM worship_assignment p WHERE p.id = c.assignment_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_worship_assignment_member_tenant ON worship_assignment_member (client_id);
        SELECT count(*) INTO v_null FROM worship_assignment_member WHERE client_id IS NULL;
        RAISE NOTICE 'worship_assignment_member: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 4) church_event_day <- church_event (event_id); parent tenant col is app_client_id
    IF to_regclass('public.church_event_day') IS NOT NULL THEN
        ALTER TABLE church_event_day ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE church_event_day c SET client_id = p.app_client_id
          FROM church_event p WHERE p.id = c.event_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_church_event_day_tenant ON church_event_day (client_id);
        SELECT count(*) INTO v_null FROM church_event_day WHERE client_id IS NULL;
        RAISE NOTICE 'church_event_day: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 5) member_preference <- family_member (member_id); parent tenant col is app_client_id
    IF to_regclass('public.member_preference') IS NOT NULL THEN
        ALTER TABLE member_preference ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE member_preference c SET client_id = p.app_client_id
          FROM family_member p WHERE p.id = c.member_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_member_preference_tenant ON member_preference (client_id);
        SELECT count(*) INTO v_null FROM member_preference WHERE client_id IS NULL;
        RAISE NOTICE 'member_preference: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 6) user_permissions <- app_user (app_user_id)
    IF to_regclass('public.user_permissions') IS NOT NULL THEN
        ALTER TABLE user_permissions ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE user_permissions c SET client_id = p.client_id
          FROM app_user p WHERE p.id = c.app_user_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_user_permissions_tenant ON user_permissions (client_id);
        SELECT count(*) INTO v_null FROM user_permissions WHERE client_id IS NULL;
        RAISE NOTICE 'user_permissions: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 7) demo_reminder_log <- demo_role_access (role_access_id)
    IF to_regclass('public.demo_reminder_log') IS NOT NULL THEN
        ALTER TABLE demo_reminder_log ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE demo_reminder_log c SET client_id = p.client_id
          FROM demo_role_access p WHERE p.id = c.role_access_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_demo_reminder_log_tenant ON demo_reminder_log (client_id);
        SELECT count(*) INTO v_null FROM demo_reminder_log WHERE client_id IS NULL;
        RAISE NOTICE 'demo_reminder_log: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 8) guess_it_group_participant <- guess_it_group (group_id)
    IF to_regclass('public.guess_it_group_participant') IS NOT NULL THEN
        ALTER TABLE guess_it_group_participant ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE guess_it_group_participant c SET client_id = p.client_id
          FROM guess_it_group p WHERE p.id = c.group_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_guess_it_group_participant_tenant ON guess_it_group_participant (client_id);
        SELECT count(*) INTO v_null FROM guess_it_group_participant WHERE client_id IS NULL;
        RAISE NOTICE 'guess_it_group_participant: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 9) guess_it_participant <- guess_it_game (game_id)
    IF to_regclass('public.guess_it_participant') IS NOT NULL THEN
        ALTER TABLE guess_it_participant ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE guess_it_participant c SET client_id = p.client_id
          FROM guess_it_game p WHERE p.id = c.game_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_guess_it_participant_tenant ON guess_it_participant (client_id);
        SELECT count(*) INTO v_null FROM guess_it_participant WHERE client_id IS NULL;
        RAISE NOTICE 'guess_it_participant: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    -- 10) mapping_rule <- import_run (run_id)
    IF to_regclass('public.mapping_rule') IS NOT NULL THEN
        ALTER TABLE mapping_rule ADD COLUMN IF NOT EXISTS client_id varchar(255);
        UPDATE mapping_rule c SET client_id = p.client_id
          FROM import_run p WHERE p.id = c.run_id AND c.client_id IS NULL;
        GET DIAGNOSTICS v_bf = ROW_COUNT;
        CREATE INDEX IF NOT EXISTS idx_mapping_rule_tenant ON mapping_rule (client_id);
        SELECT count(*) INTO v_null FROM mapping_rule WHERE client_id IS NULL;
        RAISE NOTICE 'mapping_rule: backfilled %, still NULL %', v_bf, v_null;
    END IF;

    RAISE NOTICE 'W4 complete. A non-zero "still NULL" for any table means rows whose parent '
               'is missing (orphans — run W1 first) or not yet created by the deployed app.';
END $$;

-- ── After the app is deployed and every create-path sets client_id ────────────────
-- Re-run this script (idempotent — backfills only what is still NULL), confirm every
-- "still NULL" is 0 (verify-W4-after.sql), then flip each column to NOT NULL:
--   ALTER TABLE worship_instrument          ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE worship_group_member        ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE worship_assignment_member   ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE church_event_day            ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE member_preference           ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE user_permissions            ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE demo_reminder_log           ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE guess_it_group_participant  ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE guess_it_participant        ALTER COLUMN client_id SET NOT NULL;
--   ALTER TABLE mapping_rule                ALTER COLUMN client_id SET NOT NULL;
-- Only then deploy the entities with @Column(name = "client_id", nullable = false).
-- (address is the eleventh column-less table but has no foreign key to a tenant table —
--  it is referenced BY tenant rows rather than owning a tenant; it needs its own analysis
--  and is intentionally out of scope here.)
