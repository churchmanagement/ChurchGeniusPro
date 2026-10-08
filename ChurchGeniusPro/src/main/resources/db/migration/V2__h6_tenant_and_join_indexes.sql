-- ═══════════════════════════════════════════════════════════════════════════
-- V2 — Database audit H6: tenant-leading and join-column indexes
-- ═══════════════════════════════════════════════════════════════════════════
--
-- 117 of 154 tenant-scoped tables had no index leading with the tenant column, so
-- every dashboard, list and report ran a sequential scan filtered by
-- app_client_id / client_id = ? — a cost that grows with EVERY church's data, not
-- the requesting church's (production 16 Sep 2026: income 9,843 seq scans vs 36
-- index scans, group_member 10,425 vs 18, event_registration 9,766 vs 544).
--
-- This adds the indexes for the tables that actually carry query traffic: a
-- tenant-leading index (with delete_flag second where the table soft-deletes) for
-- every tenant table used by 5+ repository methods, and a join-column index for the
-- foreign-key columns of the busiest tables (10+ methods). 34 tenant + 20 join = 54.
--
-- Each is guarded by to_regclass and created IF NOT EXISTS: on an existing database
-- (production) it is applied; on a brand-new database, where Flyway runs before
-- Hibernate has created the tables, it no-ops and Hibernate builds the (still tiny)
-- schema — the indexes matter only once data accumulates. Idempotent on re-run.
-- Kept non-CONCURRENT (transactional) because production's tables are small; for a
-- large environment, build these CONCURRENTLY out-of-band before deploying.

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT * FROM (VALUES
            -- tenant-leading indexes (34)
            ('idx_income_tenant', 'income', 'app_client_id, delete_flag'),
            ('idx_expense_tenant', 'expense', 'app_client_id, delete_flag'),
            ('idx_event_registration_tenant', 'event_registration', 'client_id'),
            ('idx_song_tenant', 'song', 'client_id'),
            ('idx_app_user_tenant', 'app_user', 'client_id, delete_flag'),
            ('idx_signup_tenant', 'signup', 'client_id'),
            ('idx_km_checkin_tenant', 'km_checkin', 'client_id'),
            ('idx_ss_student_tenant', 'ss_student', 'client_id, delete_flag'),
            ('idx_meeting_tenant', 'meeting', 'app_client_id, delete_flag'),
            ('idx_sub_source_tenant', 'sub_source', 'app_client_id, delete_flag'),
            ('idx_church_event_tenant', 'church_event', 'app_client_id, delete_flag'),
            ('idx_follow_up_tenant', 'follow_up', 'client_id, delete_flag'),
            ('idx_group_member_tenant', 'group_member', 'app_client_id, delete_flag'),
            ('idx_volunteer_assignment_tenant', 'volunteer_assignment', 'app_client_id, delete_flag'),
            ('idx_km_child_tenant', 'km_child', 'client_id, delete_flag'),
            ('idx_staging_raw_tenant', 'staging_raw', 'client_id'),
            ('idx_auto_reminder_tenant', 'auto_reminder', 'app_client_id'),
            ('idx_event_email_template_tenant', 'event_email_template', 'app_client_id, delete_flag'),
            ('idx_main_source_tenant', 'main_source', 'app_client_id, delete_flag'),
            ('idx_meeting_message_template_tenant', 'meeting_message_template', 'app_client_id, delete_flag'),
            ('idx_meeting_skip_date_tenant', 'meeting_skip_date', 'app_client_id'),
            ('idx_service_client_tenant', 'service_client', 'client_id, delete_flag'),
            ('idx_ss_teacher_tenant', 'ss_teacher', 'client_id, delete_flag'),
            ('idx_transaction_type_tenant', 'transaction_type', 'app_client_id, delete_flag'),
            ('idx_app_group_tenant', 'app_group', 'app_client_id, delete_flag'),
            ('idx_finalized_section_tenant', 'finalized_section', 'client_id'),
            ('idx_meeting_type_tenant', 'meeting_type', 'app_client_id, delete_flag'),
            ('idx_member_type_tenant', 'member_type', 'app_client_id, delete_flag'),
            ('idx_payroll_paystub_tenant', 'payroll_paystub', 'app_client_id'),
            ('idx_public_screen_link_tenant', 'public_screen_link', 'app_client_id'),
            ('idx_purpose_tenant', 'purpose', 'app_client_id, delete_flag'),
            ('idx_sms_opt_in_tenant', 'sms_opt_in', 'app_client_id'),
            ('idx_ss_submission_tenant', 'ss_submission', 'client_id, delete_flag'),
            ('idx_user_favorite_tenant', 'user_favorite', 'client_id'),

            -- join-column indexes on the busiest tables (20)
            ('idx_income_member_id', 'income', 'member_id'),
            ('idx_income_sub_source_id', 'income', 'sub_source_id'),
            ('idx_income_transaction_type_id', 'income', 'transaction_type_id'),
            ('idx_expense_main_source_id', 'expense', 'main_source_id'),
            ('idx_expense_purpose_id', 'expense', 'purpose_id'),
            ('idx_expense_transaction_type_id', 'expense', 'transaction_type_id'),
            ('idx_event_registration_event_id', 'event_registration', 'event_id'),
            ('idx_song_finalized_section_id', 'song', 'finalized_section_id'),
            ('idx_song_section_id', 'song', 'section_id'),
            ('idx_signup_church_id', 'signup', 'church_id'),
            ('idx_km_checkin_guardian_member_id', 'km_checkin', 'guardian_member_id'),
            ('idx_km_checkin_child_id', 'km_checkin', 'child_id'),
            ('idx_km_checkin_classroom_id', 'km_checkin', 'classroom_id'),
            ('idx_ss_student_family_member_id', 'ss_student', 'family_member_id'),
            ('idx_ss_student_class_id', 'ss_student', 'class_id'),
            ('idx_ss_student_teacher_id', 'ss_student', 'teacher_id'),
            ('idx_guess_it_game_group_id', 'guess_it_game', 'group_id'),
            ('idx_meeting_location_family_id', 'meeting', 'location_family_id'),
            ('idx_meeting_location_member_id', 'meeting', 'location_member_id'),
            ('idx_meeting_meeting_type_id', 'meeting', 'meeting_type_id')
        ) AS v(idx, tbl, cols)
    LOOP
        IF to_regclass('public.' || r.tbl) IS NULL THEN
            CONTINUE;                                  -- table not created yet (fresh DB, pre-Hibernate)
        END IF;
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (%s)', r.idx, r.tbl, r.cols);
    END LOOP;
END $$;
