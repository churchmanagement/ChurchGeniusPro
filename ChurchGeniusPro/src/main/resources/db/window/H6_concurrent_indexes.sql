-- ═══════════════════════════════════════════════════════════════════════════
-- H6_concurrent_indexes.sql — OPTIONAL: build the V2 indexes CONCURRENTLY
-- ═══════════════════════════════════════════════════════════════════════════
-- WHY THIS EXISTS: Flyway V2 builds the 54 H6 indexes inside a normal transaction
-- (a DO block), which takes a brief ACCESS EXCLUSIVE lock per table. On CGP's
-- production tables (hundreds of rows) that is instant and V2 is the simplest path.
-- But CREATE INDEX CONCURRENTLY cannot run inside a transaction or a PL/pgSQL block,
-- so it is impossible to put inside V2.
--
-- If any of these tables is ever large enough that a brief write lock matters, run
-- THIS script by hand (psql, NOT pgAdmin's transactional Query Tool — see below)
-- during normal operation BEFORE deploying the release that contains V2. Each index
-- is built with no write lock; V2's own CREATE INDEX IF NOT EXISTS then finds it
-- already present and does nothing.
--
-- RUN WITH:   psql -h <host> -U <user> -d <db> -f H6_concurrent_indexes.sql
--   * NOT inside BEGIN/COMMIT and NOT in pgAdmin's Query Tool (it wraps statements in
--     a transaction). Each statement must run in its own (auto-commit) transaction.
--   * If one fails midway PostgreSQL leaves an INVALID index; find it with the query
--     at the bottom and DROP it, then re-run — CONCURRENTLY is not automatically
--     rolled back.
-- Each statement is guarded by IF NOT EXISTS, so re-running is safe.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_income_tenant ON income (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_tenant ON expense (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_registration_tenant ON event_registration (client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_song_tenant ON song (client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_app_user_tenant ON app_user (client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_signup_tenant ON signup (client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_km_checkin_tenant ON km_checkin (client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ss_student_tenant ON ss_student (client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meeting_tenant ON meeting (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sub_source_tenant ON sub_source (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_church_event_tenant ON church_event (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_follow_up_tenant ON follow_up (client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_group_member_tenant ON group_member (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_volunteer_assignment_tenant ON volunteer_assignment (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_km_child_tenant ON km_child (client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_staging_raw_tenant ON staging_raw (client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_auto_reminder_tenant ON auto_reminder (app_client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_email_template_tenant ON event_email_template (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_main_source_tenant ON main_source (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meeting_message_template_tenant ON meeting_message_template (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meeting_skip_date_tenant ON meeting_skip_date (app_client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_service_client_tenant ON service_client (client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ss_teacher_tenant ON ss_teacher (client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_transaction_type_tenant ON transaction_type (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_app_group_tenant ON app_group (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_finalized_section_tenant ON finalized_section (client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meeting_type_tenant ON meeting_type (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_member_type_tenant ON member_type (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payroll_paystub_tenant ON payroll_paystub (app_client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_public_screen_link_tenant ON public_screen_link (app_client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_purpose_tenant ON purpose (app_client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sms_opt_in_tenant ON sms_opt_in (app_client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ss_submission_tenant ON ss_submission (client_id, delete_flag);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_favorite_tenant ON user_favorite (client_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_income_member_id ON income (member_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_income_sub_source_id ON income (sub_source_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_income_transaction_type_id ON income (transaction_type_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_main_source_id ON expense (main_source_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_purpose_id ON expense (purpose_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_transaction_type_id ON expense (transaction_type_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_registration_event_id ON event_registration (event_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_song_finalized_section_id ON song (finalized_section_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_song_section_id ON song (section_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_signup_church_id ON signup (church_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_km_checkin_guardian_member_id ON km_checkin (guardian_member_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_km_checkin_child_id ON km_checkin (child_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_km_checkin_classroom_id ON km_checkin (classroom_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ss_student_family_member_id ON ss_student (family_member_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ss_student_class_id ON ss_student (class_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ss_student_teacher_id ON ss_student (teacher_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_guess_it_game_group_id ON guess_it_game (group_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meeting_location_family_id ON meeting (location_family_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meeting_location_member_id ON meeting (location_member_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meeting_meeting_type_id ON meeting (meeting_type_id);

-- Find any INVALID index left by a failed CONCURRENTLY build (drop and rebuild it):
--   SELECT i.relname AS invalid_index, t.relname AS table
--     FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
--     JOIN pg_class t ON t.oid = x.indrelid
--    WHERE NOT x.indisvalid AND i.relname LIKE 'idx_%';
