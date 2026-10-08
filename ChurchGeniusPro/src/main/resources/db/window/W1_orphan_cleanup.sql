-- ═══════════════════════════════════════════════════════════════════════════
-- W1 (cleanup) — Database audit H3/H2: delete orphan and orphaned-tenant rows
-- ═══════════════════════════════════════════════════════════════════════════
-- STAGED — run by hand in a maintenance window, BEFORE W3_add_foreign_keys.sql.
-- Run inside an explicit transaction so you can review the NOTICES before COMMIT:
--     BEGIN;
--     \i W1_orphan_cleanup.sql
--     -- review the NOTICES, then:
--     COMMIT;   -- or ROLLBACK; to abort
--
-- Two kinds of unreachable row are removed:
--   (a) orphan rows — a *_id points at a parent row that no longer exists (production
--       16 Sep 2026: 30 such rows over 10 relationships).
--   (b) orphaned-tenant rows — the row's tenant id is not in service_client, i.e. it
--       belonged to a demo/trial tenant that was removed (production: ~290 rows).
-- Neither can be reached by any church through the application; deleting them affects
-- no live tenant. Scoped precisely (never a broad delete); idempotent.

DO $$
DECLARE
    n       bigint;
    v_total bigint := 0;
    r       RECORD;
BEGIN
    -- ── (a) orphan child rows, per logical relationship ──
    IF to_regclass('public.access_audit') IS NOT NULL AND to_regclass('public.temporary_access') IS NOT NULL THEN
        DELETE FROM access_audit c WHERE c.temporary_access_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM temporary_access p WHERE p.id = c.temporary_access_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'access_audit', 'temporary_access_id', 'temporary_access'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.attendance_record') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM attendance_record c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'attendance_record', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.attendance_record') IS NOT NULL AND to_regclass('public.attendance_visitor') IS NOT NULL THEN
        DELETE FROM attendance_record c WHERE c.visitor_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM attendance_visitor p WHERE p.id = c.visitor_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'attendance_record', 'visitor_id', 'attendance_visitor'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.auto_reminder') IS NOT NULL AND to_regclass('public.auto_reminder_types') IS NOT NULL THEN
        DELETE FROM auto_reminder c WHERE c.reminder_type_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM auto_reminder_types p WHERE p.id = c.reminder_type_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'auto_reminder', 'reminder_type_id', 'auto_reminder_types'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.bank_sync_trusted_device') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL THEN
        DELETE FROM bank_sync_trusted_device c WHERE c.app_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user p WHERE p.id = c.app_user_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'bank_sync_trusted_device', 'app_user_id', 'app_user'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.bank_sync_verification') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL THEN
        DELETE FROM bank_sync_verification c WHERE c.app_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user p WHERE p.id = c.app_user_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'bank_sync_verification', 'app_user_id', 'app_user'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.connect_submission') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM connect_submission c WHERE c.assigned_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.assigned_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'connect_submission', 'assigned_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.connect_submission') IS NOT NULL AND to_regclass('public.follow_up') IS NOT NULL THEN
        DELETE FROM connect_submission c WHERE c.follow_up_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM follow_up p WHERE p.id = c.follow_up_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'connect_submission', 'follow_up_id', 'follow_up'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.connect_submission') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM connect_submission c WHERE c.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'connect_submission', 'member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.demo_reminder_log') IS NOT NULL AND to_regclass('public.demo_role_access') IS NOT NULL THEN
        DELETE FROM demo_reminder_log c WHERE c.role_access_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM demo_role_access p WHERE p.id = c.role_access_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'demo_reminder_log', 'role_access_id', 'demo_role_access'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.demo_role_access') IS NOT NULL AND to_regclass('public.signup') IS NOT NULL THEN
        DELETE FROM demo_role_access c WHERE c.signup_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM signup p WHERE p.id = c.signup_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'demo_role_access', 'signup_id', 'signup'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_registration') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL THEN
        DELETE FROM event_registration c WHERE c.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event p WHERE p.id = c.event_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_registration', 'event_id', 'church_event'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_registration_reminder_contact') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL THEN
        DELETE FROM event_registration_reminder_contact c WHERE c.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event p WHERE p.id = c.event_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_registration_reminder_contact', 'event_id', 'church_event'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_registration_reminder_log') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL THEN
        DELETE FROM event_registration_reminder_log c WHERE c.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event p WHERE p.id = c.event_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_registration_reminder_log', 'event_id', 'church_event'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_reminder') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL THEN
        DELETE FROM event_reminder c WHERE c.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event p WHERE p.id = c.event_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_reminder', 'event_id', 'church_event'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_volunteer') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL THEN
        DELETE FROM event_volunteer c WHERE c.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event p WHERE p.id = c.event_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_volunteer', 'event_id', 'church_event'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_volunteer') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM event_volunteer c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_volunteer', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.guess_it_game') IS NOT NULL AND to_regclass('public.guess_it_group') IS NOT NULL THEN
        DELETE FROM guess_it_game c WHERE c.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_group p WHERE p.id = c.group_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'guess_it_game', 'group_id', 'guess_it_group'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.guess_it_group_participant') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM guess_it_group_participant c WHERE c.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'guess_it_group_participant', 'member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.guess_it_participant') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM guess_it_participant c WHERE c.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'guess_it_participant', 'member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.import_audit') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL THEN
        DELETE FROM import_audit c WHERE c.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch p WHERE p.id = c.batch_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'import_audit', 'batch_id', 'import_batch'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.import_audit') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM import_audit c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'import_audit', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.import_batch') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM import_batch c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'import_batch', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.import_source_profile') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM import_source_profile c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'import_source_profile', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_authorized_pickup') IS NOT NULL AND to_regclass('public.km_child') IS NOT NULL THEN
        DELETE FROM km_authorized_pickup c WHERE c.child_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_child p WHERE p.id = c.child_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_authorized_pickup', 'child_id', 'km_child'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_checkin') IS NOT NULL AND to_regclass('public.km_child') IS NOT NULL THEN
        DELETE FROM km_checkin c WHERE c.child_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_child p WHERE p.id = c.child_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_checkin', 'child_id', 'km_child'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_checkin') IS NOT NULL AND to_regclass('public.km_classroom') IS NOT NULL THEN
        DELETE FROM km_checkin c WHERE c.classroom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_classroom p WHERE p.id = c.classroom_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_checkin', 'classroom_id', 'km_classroom'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_checkin') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM km_checkin c WHERE c.guardian_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.guardian_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_checkin', 'guardian_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_child') IS NOT NULL AND to_regclass('public.km_classroom') IS NOT NULL THEN
        DELETE FROM km_child c WHERE c.classroom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_classroom p WHERE p.id = c.classroom_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_child', 'classroom_id', 'km_classroom'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_child') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM km_child c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_child', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_volunteer') IS NOT NULL AND to_regclass('public.km_classroom') IS NOT NULL THEN
        DELETE FROM km_volunteer c WHERE c.classroom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_classroom p WHERE p.id = c.classroom_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_volunteer', 'classroom_id', 'km_classroom'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_volunteer') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM km_volunteer c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_volunteer', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.km_volunteer_role_assignment') IS NOT NULL AND to_regclass('public.km_volunteer') IS NOT NULL THEN
        DELETE FROM km_volunteer_role_assignment c WHERE c.volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_volunteer p WHERE p.id = c.volunteer_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'km_volunteer_role_assignment', 'volunteer_id', 'km_volunteer'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.mapping_rule') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM mapping_rule c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'mapping_rule', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.meeting') IS NOT NULL AND to_regclass('public.family') IS NOT NULL THEN
        DELETE FROM meeting c WHERE c.location_family_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family p WHERE p.id = c.location_family_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'meeting', 'location_family_id', 'family'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.meeting') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM meeting c WHERE c.location_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.location_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'meeting', 'location_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.meeting_reminder') IS NOT NULL AND to_regclass('public.meeting') IS NOT NULL THEN
        DELETE FROM meeting_reminder c WHERE c.meeting_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM meeting p WHERE p.id = c.meeting_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'meeting_reminder', 'meeting_id', 'meeting'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.meeting_skip_date') IS NOT NULL AND to_regclass('public.meeting') IS NOT NULL THEN
        DELETE FROM meeting_skip_date c WHERE c.meeting_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM meeting p WHERE p.id = c.meeting_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'meeting_skip_date', 'meeting_id', 'meeting'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.member_attendance_code') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM member_attendance_code c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'member_attendance_code', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.member_message') IS NOT NULL AND to_regclass('public.member_message') IS NOT NULL THEN
        DELETE FROM member_message c WHERE c.parent_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM member_message p WHERE p.id = c.parent_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'member_message', 'parent_id', 'member_message'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.member_message') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM member_message c WHERE c.recipient_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.recipient_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'member_message', 'recipient_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.member_message') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM member_message c WHERE c.sender_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.sender_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'member_message', 'sender_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.member_preference') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM member_preference c WHERE c.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'member_preference', 'member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.membership_family') IS NOT NULL AND to_regclass('public.family') IS NOT NULL THEN
        DELETE FROM membership_family c WHERE c.existing_family_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family p WHERE p.id = c.existing_family_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'membership_family', 'existing_family_id', 'family'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.membership_family') IS NOT NULL AND to_regclass('public.signup') IS NOT NULL THEN
        DELETE FROM membership_family c WHERE c.signup_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM signup p WHERE p.id = c.signup_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'membership_family', 'signup_id', 'signup'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.note') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL THEN
        DELETE FROM note c WHERE c.created_by_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user p WHERE p.id = c.created_by_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'note', 'created_by_id', 'app_user'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ntag_login_challenge') IS NOT NULL AND to_regclass('public.ntag_credential') IS NOT NULL THEN
        DELETE FROM ntag_login_challenge c WHERE c.credential_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ntag_credential p WHERE p.id = c.credential_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ntag_login_challenge', 'credential_id', 'ntag_credential'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ntag_login_history') IS NOT NULL AND to_regclass('public.ntag_credential') IS NOT NULL THEN
        DELETE FROM ntag_login_history c WHERE c.credential_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ntag_credential p WHERE p.id = c.credential_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ntag_login_history', 'credential_id', 'ntag_credential'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_employee_deduction') IS NOT NULL AND to_regclass('public.payroll_deduction_definition') IS NOT NULL THEN
        DELETE FROM payroll_employee_deduction c WHERE c.definition_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_deduction_definition p WHERE p.id = c.definition_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_employee_deduction', 'definition_id', 'payroll_deduction_definition'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_employee_deduction') IS NOT NULL AND to_regclass('public.payroll_employee') IS NOT NULL THEN
        DELETE FROM payroll_employee_deduction c WHERE c.employee_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_employee p WHERE p.id = c.employee_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_employee_deduction', 'employee_id', 'payroll_employee'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_paystub') IS NOT NULL AND to_regclass('public.payroll_employee') IS NOT NULL THEN
        DELETE FROM payroll_paystub c WHERE c.employee_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_employee p WHERE p.id = c.employee_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_paystub', 'employee_id', 'payroll_employee'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_paystub') IS NOT NULL AND to_regclass('public.payroll_run') IS NOT NULL THEN
        DELETE FROM payroll_paystub c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_paystub', 'run_id', 'payroll_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_run') IS NOT NULL AND to_regclass('public.payroll_run') IS NOT NULL THEN
        DELETE FROM payroll_run c WHERE c.adjusts_run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_run p WHERE p.id = c.adjusts_run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_run', 'adjusts_run_id', 'payroll_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_state_bracket') IS NOT NULL AND to_regclass('public.payroll_state_tax_config') IS NOT NULL THEN
        DELETE FROM payroll_state_bracket c WHERE c.state_config_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_state_tax_config p WHERE p.id = c.state_config_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_state_bracket', 'state_config_id', 'payroll_state_tax_config'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_w4') IS NOT NULL AND to_regclass('public.payroll_employee') IS NOT NULL THEN
        DELETE FROM payroll_w4 c WHERE c.employee_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_employee p WHERE p.id = c.employee_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_w4', 'employee_id', 'payroll_employee'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_item') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL THEN
        DELETE FROM plaid_item c WHERE c.created_by_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user p WHERE p.id = c.created_by_user_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_item', 'created_by_user_id', 'app_user'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.main_source') IS NOT NULL THEN
        DELETE FROM plaid_transaction_staging c WHERE c.mapped_main_source_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM main_source p WHERE p.id = c.mapped_main_source_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_transaction_staging', 'mapped_main_source_id', 'main_source'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.purpose') IS NOT NULL THEN
        DELETE FROM plaid_transaction_staging c WHERE c.mapped_purpose_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM purpose p WHERE p.id = c.mapped_purpose_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_transaction_staging', 'mapped_purpose_id', 'purpose'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.sub_source') IS NOT NULL THEN
        DELETE FROM plaid_transaction_staging c WHERE c.mapped_sub_source_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sub_source p WHERE p.id = c.mapped_sub_source_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_transaction_staging', 'mapped_sub_source_id', 'sub_source'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.transaction_type') IS NOT NULL THEN
        DELETE FROM plaid_transaction_staging c WHERE c.mapped_transaction_type_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM transaction_type p WHERE p.id = c.mapped_transaction_type_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_transaction_staging', 'mapped_transaction_type_id', 'transaction_type'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.plaid_account') IS NOT NULL THEN
        DELETE FROM plaid_transaction_staging c WHERE c.plaid_account_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM plaid_account p WHERE p.id = c.plaid_account_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_transaction_staging', 'plaid_account_id', 'plaid_account'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.expense') IS NOT NULL THEN
        DELETE FROM plaid_transaction_staging c WHERE c.promoted_expense_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM expense p WHERE p.id = c.promoted_expense_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_transaction_staging', 'promoted_expense_id', 'expense'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.income') IS NOT NULL THEN
        DELETE FROM plaid_transaction_staging c WHERE c.promoted_income_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM income p WHERE p.id = c.promoted_income_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'plaid_transaction_staging', 'promoted_income_id', 'income'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.pledge_campaign') IS NOT NULL AND to_regclass('public.sub_source') IS NOT NULL THEN
        DELETE FROM pledge_campaign c WHERE c.sub_source_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sub_source p WHERE p.id = c.sub_source_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'pledge_campaign', 'sub_source_id', 'sub_source'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.pledge_member') IS NOT NULL AND to_regclass('public.pledge_campaign') IS NOT NULL THEN
        DELETE FROM pledge_member c WHERE c.campaign_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pledge_campaign p WHERE p.id = c.campaign_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'pledge_member', 'campaign_id', 'pledge_campaign'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.pledge_member') IS NOT NULL AND to_regclass('public.family') IS NOT NULL THEN
        DELETE FROM pledge_member c WHERE c.family_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family p WHERE p.id = c.family_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'pledge_member', 'family_id', 'family'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.pledge_member') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM pledge_member c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'pledge_member', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.prayer_note') IS NOT NULL AND to_regclass('public.prayer_request') IS NOT NULL THEN
        DELETE FROM prayer_note c WHERE c.prayer_request_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_request p WHERE p.id = c.prayer_request_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'prayer_note', 'prayer_request_id', 'prayer_request'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.prayer_request') IS NOT NULL AND to_regclass('public.prayer_volunteer') IS NOT NULL THEN
        DELETE FROM prayer_request c WHERE c.assigned_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_volunteer p WHERE p.id = c.assigned_volunteer_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'prayer_request', 'assigned_volunteer_id', 'prayer_volunteer'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.prayer_request') IS NOT NULL AND to_regclass('public.prayer_section') IS NOT NULL THEN
        DELETE FROM prayer_request c WHERE c.section_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_section p WHERE p.id = c.section_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'prayer_request', 'section_id', 'prayer_section'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.prayer_request_volunteer') IS NOT NULL AND to_regclass('public.prayer_request') IS NOT NULL THEN
        DELETE FROM prayer_request_volunteer c WHERE c.prayer_request_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_request p WHERE p.id = c.prayer_request_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'prayer_request_volunteer', 'prayer_request_id', 'prayer_request'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.prayer_request_volunteer') IS NOT NULL AND to_regclass('public.prayer_volunteer') IS NOT NULL THEN
        DELETE FROM prayer_request_volunteer c WHERE c.prayer_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_volunteer p WHERE p.id = c.prayer_volunteer_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'prayer_request_volunteer', 'prayer_volunteer_id', 'prayer_volunteer'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.prayer_volunteer') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM prayer_volunteer c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'prayer_volunteer', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.public_prayer_note') IS NOT NULL AND to_regclass('public.public_prayer_request') IS NOT NULL THEN
        DELETE FROM public_prayer_note c WHERE c.public_prayer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public_prayer_request p WHERE p.id = c.public_prayer_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'public_prayer_note', 'public_prayer_id', 'public_prayer_request'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.public_prayer_request') IS NOT NULL AND to_regclass('public.prayer_volunteer') IS NOT NULL THEN
        DELETE FROM public_prayer_request c WHERE c.assigned_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_volunteer p WHERE p.id = c.assigned_volunteer_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'public_prayer_request', 'assigned_volunteer_id', 'prayer_volunteer'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.public_prayer_request') IS NOT NULL AND to_regclass('public.follow_up') IS NOT NULL THEN
        DELETE FROM public_prayer_request c WHERE c.follow_up_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM follow_up p WHERE p.id = c.follow_up_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'public_prayer_request', 'follow_up_id', 'follow_up'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.song') IS NOT NULL AND to_regclass('public.finalized_section') IS NOT NULL THEN
        DELETE FROM song c WHERE c.finalized_section_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM finalized_section p WHERE p.id = c.finalized_section_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'song', 'finalized_section_id', 'finalized_section'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.song') IS NOT NULL AND to_regclass('public.song_section') IS NOT NULL THEN
        DELETE FROM song c WHERE c.section_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM song_section p WHERE p.id = c.section_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'song', 'section_id', 'song_section'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.song_audit_log') IS NOT NULL AND to_regclass('public.song') IS NOT NULL THEN
        DELETE FROM song_audit_log c WHERE c.song_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM song p WHERE p.id = c.song_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'song_audit_log', 'song_id', 'song'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_exam') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL THEN
        DELETE FROM ss_exam c WHERE c.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class p WHERE p.id = c.class_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_exam', 'class_id', 'ss_class'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_file') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL THEN
        DELETE FROM ss_file c WHERE c.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class p WHERE p.id = c.class_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_file', 'class_id', 'ss_class'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_lesson') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL THEN
        DELETE FROM ss_lesson c WHERE c.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class p WHERE p.id = c.class_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_lesson', 'class_id', 'ss_class'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_lesson') IS NOT NULL AND to_regclass('public.ss_student') IS NOT NULL THEN
        DELETE FROM ss_lesson c WHERE c.student_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_student p WHERE p.id = c.student_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_lesson', 'student_id', 'ss_student'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_note') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL THEN
        DELETE FROM ss_note c WHERE c.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class p WHERE p.id = c.class_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_note', 'class_id', 'ss_class'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_question') IS NOT NULL AND to_regclass('public.ss_exam') IS NOT NULL THEN
        DELETE FROM ss_question c WHERE c.exam_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_exam p WHERE p.id = c.exam_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_question', 'exam_id', 'ss_exam'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_student') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL THEN
        DELETE FROM ss_student c WHERE c.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class p WHERE p.id = c.class_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_student', 'class_id', 'ss_class'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_student') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM ss_student c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_student', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_student') IS NOT NULL AND to_regclass('public.ss_teacher') IS NOT NULL THEN
        DELETE FROM ss_student c WHERE c.teacher_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_teacher p WHERE p.id = c.teacher_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_student', 'teacher_id', 'ss_teacher'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_submission') IS NOT NULL AND to_regclass('public.ss_exam') IS NOT NULL THEN
        DELETE FROM ss_submission c WHERE c.exam_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_exam p WHERE p.id = c.exam_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_submission', 'exam_id', 'ss_exam'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_submission') IS NOT NULL AND to_regclass('public.ss_student') IS NOT NULL THEN
        DELETE FROM ss_submission c WHERE c.student_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_student p WHERE p.id = c.student_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_submission', 'student_id', 'ss_student'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_teacher') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL THEN
        DELETE FROM ss_teacher c WHERE c.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class p WHERE p.id = c.class_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_teacher', 'class_id', 'ss_class'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.staging_expense') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL THEN
        DELETE FROM staging_expense c WHERE c.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch p WHERE p.id = c.batch_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'staging_expense', 'batch_id', 'import_batch'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.staging_expense') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM staging_expense c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'staging_expense', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.staging_family') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL THEN
        DELETE FROM staging_family c WHERE c.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch p WHERE p.id = c.batch_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'staging_family', 'batch_id', 'import_batch'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.staging_family') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM staging_family c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'staging_family', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.staging_income') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL THEN
        DELETE FROM staging_income c WHERE c.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch p WHERE p.id = c.batch_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'staging_income', 'batch_id', 'import_batch'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.staging_income') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM staging_income c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'staging_income', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.staging_raw') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL THEN
        DELETE FROM staging_raw c WHERE c.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run p WHERE p.id = c.run_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'staging_raw', 'run_id', 'import_run'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.uploaded_file') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL THEN
        DELETE FROM uploaded_file c WHERE c.uploaded_by_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user p WHERE p.id = c.uploaded_by_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'uploaded_file', 'uploaded_by_id', 'app_user'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.user_permissions') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL THEN
        DELETE FROM user_permissions c WHERE c.app_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user p WHERE p.id = c.app_user_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'user_permissions', 'app_user_id', 'app_user'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.volunteer_assignment') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL THEN
        DELETE FROM volunteer_assignment c WHERE c.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event p WHERE p.id = c.event_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'volunteer_assignment', 'event_id', 'church_event'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.volunteer_assignment') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM volunteer_assignment c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'volunteer_assignment', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.volunteer_assignment') IS NOT NULL AND to_regclass('public.volunteer_role') IS NOT NULL THEN
        DELETE FROM volunteer_assignment c WHERE c.role_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_role p WHERE p.id = c.role_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'volunteer_assignment', 'role_id', 'volunteer_role'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.volunteer_attendance') IS NOT NULL AND to_regclass('public.volunteer_assignment') IS NOT NULL THEN
        DELETE FROM volunteer_attendance c WHERE c.assignment_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_assignment p WHERE p.id = c.assignment_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'volunteer_attendance', 'assignment_id', 'volunteer_assignment'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.volunteer_profile') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL THEN
        DELETE FROM volunteer_profile c WHERE c.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member p WHERE p.id = c.family_member_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'volunteer_profile', 'family_member_id', 'family_member'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.volunteer_profile_role') IS NOT NULL AND to_regclass('public.volunteer_role') IS NOT NULL THEN
        DELETE FROM volunteer_profile_role c WHERE c.role_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_role p WHERE p.id = c.role_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'volunteer_profile_role', 'role_id', 'volunteer_role'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.volunteer_profile_role') IS NOT NULL AND to_regclass('public.volunteer_profile') IS NOT NULL THEN
        DELETE FROM volunteer_profile_role c WHERE c.volunteer_profile_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_profile p WHERE p.id = c.volunteer_profile_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'volunteer_profile_role', 'volunteer_profile_id', 'volunteer_profile'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.worship_assignment') IS NOT NULL AND to_regclass('public.worship_group') IS NOT NULL THEN
        DELETE FROM worship_assignment c WHERE c.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_group p WHERE p.id = c.group_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'worship_assignment', 'group_id', 'worship_group'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.worship_assignment_member') IS NOT NULL AND to_regclass('public.worship_instrument') IS NOT NULL THEN
        DELETE FROM worship_assignment_member c WHERE c.instrument_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_instrument p WHERE p.id = c.instrument_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'worship_assignment_member', 'instrument_id', 'worship_instrument'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.worship_group_member') IS NOT NULL AND to_regclass('public.worship_instrument') IS NOT NULL THEN
        DELETE FROM worship_group_member c WHERE c.instrument_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_instrument p WHERE p.id = c.instrument_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'worship_group_member', 'instrument_id', 'worship_instrument'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.worship_instrument') IS NOT NULL AND to_regclass('public.worship_group') IS NOT NULL THEN
        DELETE FROM worship_instrument c WHERE c.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_group p WHERE p.id = c.group_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'worship_instrument', 'group_id', 'worship_group'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.worship_song') IS NOT NULL AND to_regclass('public.worship_group') IS NOT NULL THEN
        DELETE FROM worship_song c WHERE c.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_group p WHERE p.id = c.group_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'worship_song', 'group_id', 'worship_group'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.church_event_day') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL THEN
        DELETE FROM church_event_day c WHERE c.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event p WHERE p.id = c.event_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'church_event_day', 'event_id', 'church_event'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_registration_reminder_log') IS NOT NULL AND to_regclass('public.event_registration_reminder_contact') IS NOT NULL THEN
        DELETE FROM event_registration_reminder_log c WHERE c.contact_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event_registration_reminder_contact p WHERE p.id = c.contact_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_registration_reminder_log', 'contact_id', 'event_registration_reminder_contact'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.event_volunteer_role') IS NOT NULL AND to_regclass('public.event_volunteer') IS NOT NULL THEN
        DELETE FROM event_volunteer_role c WHERE c.event_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event_volunteer p WHERE p.id = c.event_volunteer_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'event_volunteer_role', 'event_volunteer_id', 'event_volunteer'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.expense_check_image') IS NOT NULL AND to_regclass('public.expense') IS NOT NULL THEN
        DELETE FROM expense_check_image c WHERE c.expense_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM expense p WHERE p.id = c.expense_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'expense_check_image', 'expense_id', 'expense'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.guess_it_group_participant') IS NOT NULL AND to_regclass('public.guess_it_group') IS NOT NULL THEN
        DELETE FROM guess_it_group_participant c WHERE c.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_group p WHERE p.id = c.group_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'guess_it_group_participant', 'group_id', 'guess_it_group'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.guess_it_participant') IS NOT NULL AND to_regclass('public.guess_it_game') IS NOT NULL THEN
        DELETE FROM guess_it_participant c WHERE c.game_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_game p WHERE p.id = c.game_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'guess_it_participant', 'game_id', 'guess_it_game'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.guess_it_participant') IS NOT NULL AND to_regclass('public.guess_it_group_participant') IS NOT NULL THEN
        DELETE FROM guess_it_participant c WHERE c.group_participant_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_group_participant p WHERE p.id = c.group_participant_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'guess_it_participant', 'group_participant_id', 'guess_it_group_participant'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.income_check_image') IS NOT NULL AND to_regclass('public.income') IS NOT NULL THEN
        DELETE FROM income_check_image c WHERE c.income_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM income p WHERE p.id = c.income_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'income_check_image', 'income_id', 'income'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.payroll_paystub_item') IS NOT NULL AND to_regclass('public.payroll_paystub') IS NOT NULL THEN
        DELETE FROM payroll_paystub_item c WHERE c.paystub_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_paystub p WHERE p.id = c.paystub_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'payroll_paystub_item', 'paystub_id', 'payroll_paystub'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_answer') IS NOT NULL AND to_regclass('public.ss_question') IS NOT NULL THEN
        DELETE FROM ss_answer c WHERE c.question_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_question p WHERE p.id = c.question_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_answer', 'question_id', 'ss_question'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.ss_answer') IS NOT NULL AND to_regclass('public.ss_submission') IS NOT NULL THEN
        DELETE FROM ss_answer c WHERE c.submission_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_submission p WHERE p.id = c.submission_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'ss_answer', 'submission_id', 'ss_submission'; v_total := v_total + n; END IF;
    END IF;
    IF to_regclass('public.worship_assignment_member') IS NOT NULL AND to_regclass('public.worship_assignment') IS NOT NULL THEN
        DELETE FROM worship_assignment_member c WHERE c.assignment_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_assignment p WHERE p.id = c.assignment_id);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphan row(s) from % (% -> %).', n, 'worship_assignment_member', 'assignment_id', 'worship_assignment'; v_total := v_total + n; END IF;
    END IF;

    -- ── (b) orphaned-tenant rows: tenant id absent from service_client ──
    -- service_client holds one row per church, keyed by client_id. Any tenant-scoped
    -- row whose tenant value is neither a live client_id nor NULL belongs to a removed
    -- tenant. (signup is excluded: its client_id column is overloaded — H2 — and its
    -- values are principals, not tenant ids.)
    FOR r IN
        SELECT c.table_name, c.column_name AS tcol
          FROM information_schema.columns c
          JOIN information_schema.tables t ON t.table_schema='public' AND t.table_name=c.table_name AND t.table_type='BASE TABLE'
         WHERE c.table_schema='public' AND c.column_name IN ('client_id','app_client_id')
           AND c.table_name NOT IN ('service_client','church_registration','signup','flyway_schema_history')
    LOOP
        EXECUTE format(
            'DELETE FROM %I x WHERE x.%I IS NOT NULL AND NOT EXISTS '
            '(SELECT 1 FROM service_client s WHERE s.client_id = x.%I)', r.table_name, r.tcol, r.tcol);
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE 'W1: deleted % orphaned-tenant row(s) from %.', n, r.table_name; v_total := v_total + n; END IF;
    END LOOP;

    RAISE NOTICE 'W1: % unreachable row(s) removed in total. Review, then COMMIT (or ROLLBACK).', v_total;
END $$;
