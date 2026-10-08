-- ═══════════════════════════════════════════════════════════════════════════
-- verify-V2-indexes.sql — Database audit H6 / Flyway V2 (READ ONLY)
-- ═══════════════════════════════════════════════════════════════════════════
-- Run in production after V2 has applied. For each of the 54 indexes V2 declares it shows:
--   present            — does an index of this exact name exist?
--   actual_columns     — the columns of that index as PostgreSQL stores them
--   columns_match      — do they match what V2 intended?
--   tenant_leading_ok  — for a tenant index, does it lead with the tenant column?
--   duplicate_of       — another (differently named) index on the same table+columns, if any
--                        (a pre-existing index V2's IF NOT EXISTS would have skipped)
WITH intended(idx, tbl, cols, is_tenant) AS (VALUES
    ('idx_income_tenant','income','app_client_id, delete_flag',true),
    ('idx_expense_tenant','expense','app_client_id, delete_flag',true),
    ('idx_event_registration_tenant','event_registration','client_id',true),
    ('idx_song_tenant','song','client_id',true),
    ('idx_app_user_tenant','app_user','client_id, delete_flag',true),
    ('idx_signup_tenant','signup','client_id',true),
    ('idx_km_checkin_tenant','km_checkin','client_id',true),
    ('idx_ss_student_tenant','ss_student','client_id, delete_flag',true),
    ('idx_meeting_tenant','meeting','app_client_id, delete_flag',true),
    ('idx_sub_source_tenant','sub_source','app_client_id, delete_flag',true),
    ('idx_church_event_tenant','church_event','app_client_id, delete_flag',true),
    ('idx_follow_up_tenant','follow_up','client_id, delete_flag',true),
    ('idx_group_member_tenant','group_member','app_client_id, delete_flag',true),
    ('idx_volunteer_assignment_tenant','volunteer_assignment','app_client_id, delete_flag',true),
    ('idx_km_child_tenant','km_child','client_id, delete_flag',true),
    ('idx_staging_raw_tenant','staging_raw','client_id',true),
    ('idx_auto_reminder_tenant','auto_reminder','app_client_id',true),
    ('idx_event_email_template_tenant','event_email_template','app_client_id, delete_flag',true),
    ('idx_main_source_tenant','main_source','app_client_id, delete_flag',true),
    ('idx_meeting_message_template_tenant','meeting_message_template','app_client_id, delete_flag',true),
    ('idx_meeting_skip_date_tenant','meeting_skip_date','app_client_id',true),
    ('idx_service_client_tenant','service_client','client_id, delete_flag',true),
    ('idx_ss_teacher_tenant','ss_teacher','client_id, delete_flag',true),
    ('idx_transaction_type_tenant','transaction_type','app_client_id, delete_flag',true),
    ('idx_app_group_tenant','app_group','app_client_id, delete_flag',true),
    ('idx_finalized_section_tenant','finalized_section','client_id',true),
    ('idx_meeting_type_tenant','meeting_type','app_client_id, delete_flag',true),
    ('idx_member_type_tenant','member_type','app_client_id, delete_flag',true),
    ('idx_payroll_paystub_tenant','payroll_paystub','app_client_id',true),
    ('idx_public_screen_link_tenant','public_screen_link','app_client_id',true),
    ('idx_purpose_tenant','purpose','app_client_id, delete_flag',true),
    ('idx_sms_opt_in_tenant','sms_opt_in','app_client_id',true),
    ('idx_ss_submission_tenant','ss_submission','client_id, delete_flag',true),
    ('idx_user_favorite_tenant','user_favorite','client_id',true),
    ('idx_income_member_id','income','member_id',false),
    ('idx_income_sub_source_id','income','sub_source_id',false),
    ('idx_income_transaction_type_id','income','transaction_type_id',false),
    ('idx_expense_main_source_id','expense','main_source_id',false),
    ('idx_expense_purpose_id','expense','purpose_id',false),
    ('idx_expense_transaction_type_id','expense','transaction_type_id',false),
    ('idx_event_registration_event_id','event_registration','event_id',false),
    ('idx_song_finalized_section_id','song','finalized_section_id',false),
    ('idx_song_section_id','song','section_id',false),
    ('idx_signup_church_id','signup','church_id',false),
    ('idx_km_checkin_guardian_member_id','km_checkin','guardian_member_id',false),
    ('idx_km_checkin_child_id','km_checkin','child_id',false),
    ('idx_km_checkin_classroom_id','km_checkin','classroom_id',false),
    ('idx_ss_student_family_member_id','ss_student','family_member_id',false),
    ('idx_ss_student_class_id','ss_student','class_id',false),
    ('idx_ss_student_teacher_id','ss_student','teacher_id',false),
    ('idx_guess_it_game_group_id','guess_it_game','group_id',false),
    ('idx_meeting_location_family_id','meeting','location_family_id',false),
    ('idx_meeting_location_member_id','meeting','location_member_id',false),
    ('idx_meeting_meeting_type_id','meeting','meeting_type_id',false)
),
actual AS (
    SELECT i.relname AS idx, t.relname AS tbl,
           pg_get_indexdef(i.oid) AS def,
           (regexp_match(pg_get_indexdef(i.oid), '\(([^)]*)\)'))[1] AS cols
      FROM pg_index x
      JOIN pg_class i ON i.oid = x.indexrelid
      JOIN pg_class t ON t.oid = x.indrelid
      JOIN pg_namespace n ON n.oid = t.relnamespace
     WHERE n.nspname = current_schema()
)
SELECT it.idx, it.tbl,
       (a.idx IS NOT NULL)                                             AS present,
       a.cols                                                          AS actual_columns,
       (replace(a.cols,' ','') = replace(it.cols,' ',''))             AS columns_match,
       CASE WHEN it.is_tenant
            THEN a.cols ~ ('^' || split_part(it.cols, ',', 1))
            ELSE NULL END                                              AS tenant_leading_ok,
       (SELECT string_agg(o.idx, ', ') FROM actual o
         WHERE o.tbl = it.tbl AND o.idx <> it.idx
           AND replace(o.cols,' ','') = replace(it.cols,' ',''))       AS duplicate_of
  FROM intended it
  LEFT JOIN actual a ON a.idx = it.idx
 ORDER BY present, it.tbl, it.idx;
