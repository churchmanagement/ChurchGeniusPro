-- ═══════════════════════════════════════════════════════════════════════════
-- W1_orphan_disclosure.sql — every row W1 would delete, ROW BY ROW (READ ONLY)
-- ═══════════════════════════════════════════════════════════════════════════
-- Run this first, in the window, and REVIEW the output before running W1_orphan_cleanup.sql.
-- It lists one row per affected record with: the table, its primary key, its tenant id, the
-- reference that makes it an orphan, the reason, the proposed action, and the exact single-row
-- DELETE that would remove it. Nothing here changes data.
--
-- Two kinds of row appear:
--   * orphan            — a *_id points at a parent row that no longer exists (these block the
--                         foreign keys in W3). Proposed: DELETE after review.
--   * orphaned-tenant   — the row's tenant id is not a live service_client, i.e. it belonged to
--                         a removed demo/trial tenant. Proposed: REVIEW — do NOT assume every one
--                         is safe to delete merely because the tenant is gone; confirm the tenant
--                         was intentionally removed (and that the row is not needed for audit or
--                         accounting retention) before deleting.
-- A row can appear under both kinds; decide per row. Order is by table then pk.
SELECT * FROM (
    SELECT 'access_audit' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.temporary_access_id::text AS reference_id,
           'orphan: temporary_access.id = temporary_access_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_access_audit_temporary_access_id)' AS proposed_action,
           'DELETE FROM access_audit WHERE id = ' || x.id || ';' AS exact_sql
      FROM access_audit x WHERE x.temporary_access_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM temporary_access y WHERE y.id = x.temporary_access_id)
    UNION ALL
    SELECT 'attendance_record' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_attendance_record_family_member_id)' AS proposed_action,
           'DELETE FROM attendance_record WHERE id = ' || x.id || ';' AS exact_sql
      FROM attendance_record x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'attendance_record' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.visitor_id::text AS reference_id,
           'orphan: attendance_visitor.id = visitor_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_attendance_record_visitor_id)' AS proposed_action,
           'DELETE FROM attendance_record WHERE id = ' || x.id || ';' AS exact_sql
      FROM attendance_record x WHERE x.visitor_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM attendance_visitor y WHERE y.id = x.visitor_id)
    UNION ALL
    SELECT 'auto_reminder' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.reminder_type_id::text AS reference_id,
           'orphan: auto_reminder_types.id = reminder_type_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_auto_reminder_reminder_type_id)' AS proposed_action,
           'DELETE FROM auto_reminder WHERE id = ' || x.id || ';' AS exact_sql
      FROM auto_reminder x WHERE x.reminder_type_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM auto_reminder_types y WHERE y.id = x.reminder_type_id)
    UNION ALL
    SELECT 'bank_sync_trusted_device' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.app_user_id::text AS reference_id,
           'orphan: app_user.id = app_user_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_bank_sync_trusted_device_app_user_id)' AS proposed_action,
           'DELETE FROM bank_sync_trusted_device WHERE id = ' || x.id || ';' AS exact_sql
      FROM bank_sync_trusted_device x WHERE x.app_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user y WHERE y.id = x.app_user_id)
    UNION ALL
    SELECT 'bank_sync_verification' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.app_user_id::text AS reference_id,
           'orphan: app_user.id = app_user_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_bank_sync_verification_app_user_id)' AS proposed_action,
           'DELETE FROM bank_sync_verification WHERE id = ' || x.id || ';' AS exact_sql
      FROM bank_sync_verification x WHERE x.app_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user y WHERE y.id = x.app_user_id)
    UNION ALL
    SELECT 'connect_submission' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.assigned_member_id::text AS reference_id,
           'orphan: family_member.id = assigned_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_connect_submission_assigned_member_id)' AS proposed_action,
           'DELETE FROM connect_submission WHERE id = ' || x.id || ';' AS exact_sql
      FROM connect_submission x WHERE x.assigned_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.assigned_member_id)
    UNION ALL
    SELECT 'connect_submission' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.follow_up_id::text AS reference_id,
           'orphan: follow_up.id = follow_up_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_connect_submission_follow_up_id)' AS proposed_action,
           'DELETE FROM connect_submission WHERE id = ' || x.id || ';' AS exact_sql
      FROM connect_submission x WHERE x.follow_up_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM follow_up y WHERE y.id = x.follow_up_id)
    UNION ALL
    SELECT 'connect_submission' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.member_id::text AS reference_id,
           'orphan: family_member.id = member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_connect_submission_member_id)' AS proposed_action,
           'DELETE FROM connect_submission WHERE id = ' || x.id || ';' AS exact_sql
      FROM connect_submission x WHERE x.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.member_id)
    UNION ALL
    SELECT 'demo_reminder_log' AS table_name, x.id AS pk, NULL AS tenant_id, x.role_access_id::text AS reference_id,
           'orphan: demo_role_access.id = role_access_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_demo_reminder_log_role_access_id)' AS proposed_action,
           'DELETE FROM demo_reminder_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM demo_reminder_log x WHERE x.role_access_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM demo_role_access y WHERE y.id = x.role_access_id)
    UNION ALL
    SELECT 'demo_role_access' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.signup_id::text AS reference_id,
           'orphan: signup.id = signup_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_demo_role_access_signup_id)' AS proposed_action,
           'DELETE FROM demo_role_access WHERE id = ' || x.id || ';' AS exact_sql
      FROM demo_role_access x WHERE x.signup_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM signup y WHERE y.id = x.signup_id)
    UNION ALL
    SELECT 'event_registration' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.event_id::text AS reference_id,
           'orphan: church_event.id = event_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_registration_event_id)' AS proposed_action,
           'DELETE FROM event_registration WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_registration x WHERE x.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event y WHERE y.id = x.event_id)
    UNION ALL
    SELECT 'event_registration_reminder_contact' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.event_id::text AS reference_id,
           'orphan: church_event.id = event_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_registration_reminder_contact_event_id)' AS proposed_action,
           'DELETE FROM event_registration_reminder_contact WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_registration_reminder_contact x WHERE x.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event y WHERE y.id = x.event_id)
    UNION ALL
    SELECT 'event_registration_reminder_log' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.event_id::text AS reference_id,
           'orphan: church_event.id = event_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_registration_reminder_log_event_id)' AS proposed_action,
           'DELETE FROM event_registration_reminder_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_registration_reminder_log x WHERE x.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event y WHERE y.id = x.event_id)
    UNION ALL
    SELECT 'event_reminder' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.event_id::text AS reference_id,
           'orphan: church_event.id = event_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_reminder_event_id)' AS proposed_action,
           'DELETE FROM event_reminder WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_reminder x WHERE x.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event y WHERE y.id = x.event_id)
    UNION ALL
    SELECT 'event_volunteer' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.event_id::text AS reference_id,
           'orphan: church_event.id = event_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_volunteer_event_id)' AS proposed_action,
           'DELETE FROM event_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_volunteer x WHERE x.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event y WHERE y.id = x.event_id)
    UNION ALL
    SELECT 'event_volunteer' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_volunteer_family_member_id)' AS proposed_action,
           'DELETE FROM event_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_volunteer x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'guess_it_game' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.group_id::text AS reference_id,
           'orphan: guess_it_group.id = group_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_guess_it_game_group_id)' AS proposed_action,
           'DELETE FROM guess_it_game WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_game x WHERE x.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_group y WHERE y.id = x.group_id)
    UNION ALL
    SELECT 'guess_it_group_participant' AS table_name, x.id AS pk, NULL AS tenant_id, x.member_id::text AS reference_id,
           'orphan: family_member.id = member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_guess_it_group_participant_member_id)' AS proposed_action,
           'DELETE FROM guess_it_group_participant WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_group_participant x WHERE x.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.member_id)
    UNION ALL
    SELECT 'guess_it_participant' AS table_name, x.id AS pk, NULL AS tenant_id, x.member_id::text AS reference_id,
           'orphan: family_member.id = member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_guess_it_participant_member_id)' AS proposed_action,
           'DELETE FROM guess_it_participant WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_participant x WHERE x.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.member_id)
    UNION ALL
    SELECT 'import_audit' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.batch_id::text AS reference_id,
           'orphan: import_batch.id = batch_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_import_audit_batch_id)' AS proposed_action,
           'DELETE FROM import_audit WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_audit x WHERE x.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch y WHERE y.id = x.batch_id)
    UNION ALL
    SELECT 'import_audit' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_import_audit_run_id)' AS proposed_action,
           'DELETE FROM import_audit WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_audit x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'import_batch' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_import_batch_run_id)' AS proposed_action,
           'DELETE FROM import_batch WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_batch x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'import_source_profile' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_import_source_profile_run_id)' AS proposed_action,
           'DELETE FROM import_source_profile WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_source_profile x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'km_authorized_pickup' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.child_id::text AS reference_id,
           'orphan: km_child.id = child_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_authorized_pickup_child_id)' AS proposed_action,
           'DELETE FROM km_authorized_pickup WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_authorized_pickup x WHERE x.child_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_child y WHERE y.id = x.child_id)
    UNION ALL
    SELECT 'km_checkin' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.child_id::text AS reference_id,
           'orphan: km_child.id = child_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_checkin_child_id)' AS proposed_action,
           'DELETE FROM km_checkin WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_checkin x WHERE x.child_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_child y WHERE y.id = x.child_id)
    UNION ALL
    SELECT 'km_checkin' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.classroom_id::text AS reference_id,
           'orphan: km_classroom.id = classroom_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_checkin_classroom_id)' AS proposed_action,
           'DELETE FROM km_checkin WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_checkin x WHERE x.classroom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_classroom y WHERE y.id = x.classroom_id)
    UNION ALL
    SELECT 'km_checkin' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.guardian_member_id::text AS reference_id,
           'orphan: family_member.id = guardian_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_checkin_guardian_member_id)' AS proposed_action,
           'DELETE FROM km_checkin WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_checkin x WHERE x.guardian_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.guardian_member_id)
    UNION ALL
    SELECT 'km_child' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.classroom_id::text AS reference_id,
           'orphan: km_classroom.id = classroom_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_child_classroom_id)' AS proposed_action,
           'DELETE FROM km_child WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_child x WHERE x.classroom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_classroom y WHERE y.id = x.classroom_id)
    UNION ALL
    SELECT 'km_child' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_child_family_member_id)' AS proposed_action,
           'DELETE FROM km_child WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_child x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'km_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.classroom_id::text AS reference_id,
           'orphan: km_classroom.id = classroom_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_volunteer_classroom_id)' AS proposed_action,
           'DELETE FROM km_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_volunteer x WHERE x.classroom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_classroom y WHERE y.id = x.classroom_id)
    UNION ALL
    SELECT 'km_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_volunteer_family_member_id)' AS proposed_action,
           'DELETE FROM km_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_volunteer x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'km_volunteer_role_assignment' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.volunteer_id::text AS reference_id,
           'orphan: km_volunteer.id = volunteer_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_km_volunteer_role_assignment_volunteer_id)' AS proposed_action,
           'DELETE FROM km_volunteer_role_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_volunteer_role_assignment x WHERE x.volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM km_volunteer y WHERE y.id = x.volunteer_id)
    UNION ALL
    SELECT 'mapping_rule' AS table_name, x.id AS pk, NULL AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_mapping_rule_run_id)' AS proposed_action,
           'DELETE FROM mapping_rule WHERE id = ' || x.id || ';' AS exact_sql
      FROM mapping_rule x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'meeting' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.location_family_id::text AS reference_id,
           'orphan: family.id = location_family_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_meeting_location_family_id)' AS proposed_action,
           'DELETE FROM meeting WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting x WHERE x.location_family_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family y WHERE y.id = x.location_family_id)
    UNION ALL
    SELECT 'meeting' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.location_member_id::text AS reference_id,
           'orphan: family_member.id = location_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_meeting_location_member_id)' AS proposed_action,
           'DELETE FROM meeting WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting x WHERE x.location_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.location_member_id)
    UNION ALL
    SELECT 'meeting_reminder' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.meeting_id::text AS reference_id,
           'orphan: meeting.id = meeting_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_meeting_reminder_meeting_id)' AS proposed_action,
           'DELETE FROM meeting_reminder WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting_reminder x WHERE x.meeting_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM meeting y WHERE y.id = x.meeting_id)
    UNION ALL
    SELECT 'meeting_skip_date' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.meeting_id::text AS reference_id,
           'orphan: meeting.id = meeting_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_meeting_skip_date_meeting_id)' AS proposed_action,
           'DELETE FROM meeting_skip_date WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting_skip_date x WHERE x.meeting_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM meeting y WHERE y.id = x.meeting_id)
    UNION ALL
    SELECT 'member_attendance_code' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_member_attendance_code_family_member_id)' AS proposed_action,
           'DELETE FROM member_attendance_code WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_attendance_code x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'member_message' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.parent_id::text AS reference_id,
           'orphan: member_message.id = parent_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_member_message_parent_id)' AS proposed_action,
           'DELETE FROM member_message WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_message x WHERE x.parent_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM member_message y WHERE y.id = x.parent_id)
    UNION ALL
    SELECT 'member_message' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.recipient_member_id::text AS reference_id,
           'orphan: family_member.id = recipient_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_member_message_recipient_member_id)' AS proposed_action,
           'DELETE FROM member_message WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_message x WHERE x.recipient_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.recipient_member_id)
    UNION ALL
    SELECT 'member_message' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.sender_member_id::text AS reference_id,
           'orphan: family_member.id = sender_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_member_message_sender_member_id)' AS proposed_action,
           'DELETE FROM member_message WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_message x WHERE x.sender_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.sender_member_id)
    UNION ALL
    SELECT 'member_preference' AS table_name, x.id AS pk, NULL AS tenant_id, x.member_id::text AS reference_id,
           'orphan: family_member.id = member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_member_preference_member_id)' AS proposed_action,
           'DELETE FROM member_preference WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_preference x WHERE x.member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.member_id)
    UNION ALL
    SELECT 'membership_family' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.existing_family_id::text AS reference_id,
           'orphan: family.id = existing_family_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_membership_family_existing_family_id)' AS proposed_action,
           'DELETE FROM membership_family WHERE id = ' || x.id || ';' AS exact_sql
      FROM membership_family x WHERE x.existing_family_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family y WHERE y.id = x.existing_family_id)
    UNION ALL
    SELECT 'membership_family' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.signup_id::text AS reference_id,
           'orphan: signup.id = signup_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_membership_family_signup_id)' AS proposed_action,
           'DELETE FROM membership_family WHERE id = ' || x.id || ';' AS exact_sql
      FROM membership_family x WHERE x.signup_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM signup y WHERE y.id = x.signup_id)
    UNION ALL
    SELECT 'note' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.created_by_id::text AS reference_id,
           'orphan: app_user.id = created_by_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_note_created_by_id)' AS proposed_action,
           'DELETE FROM note WHERE id = ' || x.id || ';' AS exact_sql
      FROM note x WHERE x.created_by_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user y WHERE y.id = x.created_by_id)
    UNION ALL
    SELECT 'ntag_login_challenge' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.credential_id::text AS reference_id,
           'orphan: ntag_credential.id = credential_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ntag_login_challenge_credential_id)' AS proposed_action,
           'DELETE FROM ntag_login_challenge WHERE id = ' || x.id || ';' AS exact_sql
      FROM ntag_login_challenge x WHERE x.credential_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ntag_credential y WHERE y.id = x.credential_id)
    UNION ALL
    SELECT 'ntag_login_history' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.credential_id::text AS reference_id,
           'orphan: ntag_credential.id = credential_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ntag_login_history_credential_id)' AS proposed_action,
           'DELETE FROM ntag_login_history WHERE id = ' || x.id || ';' AS exact_sql
      FROM ntag_login_history x WHERE x.credential_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ntag_credential y WHERE y.id = x.credential_id)
    UNION ALL
    SELECT 'payroll_employee_deduction' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.definition_id::text AS reference_id,
           'orphan: payroll_deduction_definition.id = definition_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_employee_deduction_definition_id)' AS proposed_action,
           'DELETE FROM payroll_employee_deduction WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_employee_deduction x WHERE x.definition_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_deduction_definition y WHERE y.id = x.definition_id)
    UNION ALL
    SELECT 'payroll_employee_deduction' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.employee_id::text AS reference_id,
           'orphan: payroll_employee.id = employee_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_employee_deduction_employee_id)' AS proposed_action,
           'DELETE FROM payroll_employee_deduction WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_employee_deduction x WHERE x.employee_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_employee y WHERE y.id = x.employee_id)
    UNION ALL
    SELECT 'payroll_paystub' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.employee_id::text AS reference_id,
           'orphan: payroll_employee.id = employee_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_paystub_employee_id)' AS proposed_action,
           'DELETE FROM payroll_paystub WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_paystub x WHERE x.employee_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_employee y WHERE y.id = x.employee_id)
    UNION ALL
    SELECT 'payroll_paystub' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: payroll_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_paystub_run_id)' AS proposed_action,
           'DELETE FROM payroll_paystub WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_paystub x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'payroll_run' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.adjusts_run_id::text AS reference_id,
           'orphan: payroll_run.id = adjusts_run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_run_adjusts_run_id)' AS proposed_action,
           'DELETE FROM payroll_run WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_run x WHERE x.adjusts_run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_run y WHERE y.id = x.adjusts_run_id)
    UNION ALL
    SELECT 'payroll_state_bracket' AS table_name, x.id AS pk, NULL AS tenant_id, x.state_config_id::text AS reference_id,
           'orphan: payroll_state_tax_config.id = state_config_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_state_bracket_state_config_id)' AS proposed_action,
           'DELETE FROM payroll_state_bracket WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_state_bracket x WHERE x.state_config_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_state_tax_config y WHERE y.id = x.state_config_id)
    UNION ALL
    SELECT 'payroll_w4' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.employee_id::text AS reference_id,
           'orphan: payroll_employee.id = employee_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_w4_employee_id)' AS proposed_action,
           'DELETE FROM payroll_w4 WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_w4 x WHERE x.employee_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_employee y WHERE y.id = x.employee_id)
    UNION ALL
    SELECT 'plaid_item' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.created_by_user_id::text AS reference_id,
           'orphan: app_user.id = created_by_user_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_item_created_by_user_id)' AS proposed_action,
           'DELETE FROM plaid_item WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_item x WHERE x.created_by_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user y WHERE y.id = x.created_by_user_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.mapped_main_source_id::text AS reference_id,
           'orphan: main_source.id = mapped_main_source_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_transaction_staging_mapped_main_source_id)' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.mapped_main_source_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM main_source y WHERE y.id = x.mapped_main_source_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.mapped_purpose_id::text AS reference_id,
           'orphan: purpose.id = mapped_purpose_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_transaction_staging_mapped_purpose_id)' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.mapped_purpose_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM purpose y WHERE y.id = x.mapped_purpose_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.mapped_sub_source_id::text AS reference_id,
           'orphan: sub_source.id = mapped_sub_source_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_transaction_staging_mapped_sub_source_id)' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.mapped_sub_source_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sub_source y WHERE y.id = x.mapped_sub_source_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.mapped_transaction_type_id::text AS reference_id,
           'orphan: transaction_type.id = mapped_transaction_type_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_transaction_staging_mapped_transaction_type_id)' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.mapped_transaction_type_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM transaction_type y WHERE y.id = x.mapped_transaction_type_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.plaid_account_id::text AS reference_id,
           'orphan: plaid_account.id = plaid_account_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_transaction_staging_plaid_account_id)' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.plaid_account_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM plaid_account y WHERE y.id = x.plaid_account_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.promoted_expense_id::text AS reference_id,
           'orphan: expense.id = promoted_expense_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_transaction_staging_promoted_expense_id)' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.promoted_expense_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM expense y WHERE y.id = x.promoted_expense_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.promoted_income_id::text AS reference_id,
           'orphan: income.id = promoted_income_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_plaid_transaction_staging_promoted_income_id)' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.promoted_income_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM income y WHERE y.id = x.promoted_income_id)
    UNION ALL
    SELECT 'pledge_campaign' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.sub_source_id::text AS reference_id,
           'orphan: sub_source.id = sub_source_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_pledge_campaign_sub_source_id)' AS proposed_action,
           'DELETE FROM pledge_campaign WHERE id = ' || x.id || ';' AS exact_sql
      FROM pledge_campaign x WHERE x.sub_source_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sub_source y WHERE y.id = x.sub_source_id)
    UNION ALL
    SELECT 'pledge_member' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.campaign_id::text AS reference_id,
           'orphan: pledge_campaign.id = campaign_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_pledge_member_campaign_id)' AS proposed_action,
           'DELETE FROM pledge_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM pledge_member x WHERE x.campaign_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pledge_campaign y WHERE y.id = x.campaign_id)
    UNION ALL
    SELECT 'pledge_member' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_id::text AS reference_id,
           'orphan: family.id = family_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_pledge_member_family_id)' AS proposed_action,
           'DELETE FROM pledge_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM pledge_member x WHERE x.family_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family y WHERE y.id = x.family_id)
    UNION ALL
    SELECT 'pledge_member' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_pledge_member_family_member_id)' AS proposed_action,
           'DELETE FROM pledge_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM pledge_member x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'prayer_note' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.prayer_request_id::text AS reference_id,
           'orphan: prayer_request.id = prayer_request_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_prayer_note_prayer_request_id)' AS proposed_action,
           'DELETE FROM prayer_note WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_note x WHERE x.prayer_request_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_request y WHERE y.id = x.prayer_request_id)
    UNION ALL
    SELECT 'prayer_request' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.assigned_volunteer_id::text AS reference_id,
           'orphan: prayer_volunteer.id = assigned_volunteer_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_prayer_request_assigned_volunteer_id)' AS proposed_action,
           'DELETE FROM prayer_request WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_request x WHERE x.assigned_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_volunteer y WHERE y.id = x.assigned_volunteer_id)
    UNION ALL
    SELECT 'prayer_request' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.section_id::text AS reference_id,
           'orphan: prayer_section.id = section_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_prayer_request_section_id)' AS proposed_action,
           'DELETE FROM prayer_request WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_request x WHERE x.section_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_section y WHERE y.id = x.section_id)
    UNION ALL
    SELECT 'prayer_request_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.prayer_request_id::text AS reference_id,
           'orphan: prayer_request.id = prayer_request_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_prayer_request_volunteer_prayer_request_id)' AS proposed_action,
           'DELETE FROM prayer_request_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_request_volunteer x WHERE x.prayer_request_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_request y WHERE y.id = x.prayer_request_id)
    UNION ALL
    SELECT 'prayer_request_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.prayer_volunteer_id::text AS reference_id,
           'orphan: prayer_volunteer.id = prayer_volunteer_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_prayer_request_volunteer_prayer_volunteer_id)' AS proposed_action,
           'DELETE FROM prayer_request_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_request_volunteer x WHERE x.prayer_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_volunteer y WHERE y.id = x.prayer_volunteer_id)
    UNION ALL
    SELECT 'prayer_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_prayer_volunteer_family_member_id)' AS proposed_action,
           'DELETE FROM prayer_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_volunteer x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'public_prayer_note' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.public_prayer_id::text AS reference_id,
           'orphan: public_prayer_request.id = public_prayer_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_public_prayer_note_public_prayer_id)' AS proposed_action,
           'DELETE FROM public_prayer_note WHERE id = ' || x.id || ';' AS exact_sql
      FROM public_prayer_note x WHERE x.public_prayer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public_prayer_request y WHERE y.id = x.public_prayer_id)
    UNION ALL
    SELECT 'public_prayer_request' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.assigned_volunteer_id::text AS reference_id,
           'orphan: prayer_volunteer.id = assigned_volunteer_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_public_prayer_request_assigned_volunteer_id)' AS proposed_action,
           'DELETE FROM public_prayer_request WHERE id = ' || x.id || ';' AS exact_sql
      FROM public_prayer_request x WHERE x.assigned_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prayer_volunteer y WHERE y.id = x.assigned_volunteer_id)
    UNION ALL
    SELECT 'public_prayer_request' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.follow_up_id::text AS reference_id,
           'orphan: follow_up.id = follow_up_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_public_prayer_request_follow_up_id)' AS proposed_action,
           'DELETE FROM public_prayer_request WHERE id = ' || x.id || ';' AS exact_sql
      FROM public_prayer_request x WHERE x.follow_up_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM follow_up y WHERE y.id = x.follow_up_id)
    UNION ALL
    SELECT 'song' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.finalized_section_id::text AS reference_id,
           'orphan: finalized_section.id = finalized_section_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_song_finalized_section_id)' AS proposed_action,
           'DELETE FROM song WHERE id = ' || x.id || ';' AS exact_sql
      FROM song x WHERE x.finalized_section_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM finalized_section y WHERE y.id = x.finalized_section_id)
    UNION ALL
    SELECT 'song' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.section_id::text AS reference_id,
           'orphan: song_section.id = section_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_song_section_id)' AS proposed_action,
           'DELETE FROM song WHERE id = ' || x.id || ';' AS exact_sql
      FROM song x WHERE x.section_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM song_section y WHERE y.id = x.section_id)
    UNION ALL
    SELECT 'song_audit_log' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.song_id::text AS reference_id,
           'orphan: song.id = song_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_song_audit_log_song_id)' AS proposed_action,
           'DELETE FROM song_audit_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_audit_log x WHERE x.song_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM song y WHERE y.id = x.song_id)
    UNION ALL
    SELECT 'ss_exam' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.class_id::text AS reference_id,
           'orphan: ss_class.id = class_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_exam_class_id)' AS proposed_action,
           'DELETE FROM ss_exam WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_exam x WHERE x.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class y WHERE y.id = x.class_id)
    UNION ALL
    SELECT 'ss_file' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.class_id::text AS reference_id,
           'orphan: ss_class.id = class_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_file_class_id)' AS proposed_action,
           'DELETE FROM ss_file WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_file x WHERE x.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class y WHERE y.id = x.class_id)
    UNION ALL
    SELECT 'ss_lesson' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.class_id::text AS reference_id,
           'orphan: ss_class.id = class_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_lesson_class_id)' AS proposed_action,
           'DELETE FROM ss_lesson WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_lesson x WHERE x.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class y WHERE y.id = x.class_id)
    UNION ALL
    SELECT 'ss_lesson' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.student_id::text AS reference_id,
           'orphan: ss_student.id = student_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_lesson_student_id)' AS proposed_action,
           'DELETE FROM ss_lesson WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_lesson x WHERE x.student_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_student y WHERE y.id = x.student_id)
    UNION ALL
    SELECT 'ss_note' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.class_id::text AS reference_id,
           'orphan: ss_class.id = class_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_note_class_id)' AS proposed_action,
           'DELETE FROM ss_note WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_note x WHERE x.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class y WHERE y.id = x.class_id)
    UNION ALL
    SELECT 'ss_question' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.exam_id::text AS reference_id,
           'orphan: ss_exam.id = exam_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_question_exam_id)' AS proposed_action,
           'DELETE FROM ss_question WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_question x WHERE x.exam_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_exam y WHERE y.id = x.exam_id)
    UNION ALL
    SELECT 'ss_student' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.class_id::text AS reference_id,
           'orphan: ss_class.id = class_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_student_class_id)' AS proposed_action,
           'DELETE FROM ss_student WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_student x WHERE x.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class y WHERE y.id = x.class_id)
    UNION ALL
    SELECT 'ss_student' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_student_family_member_id)' AS proposed_action,
           'DELETE FROM ss_student WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_student x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'ss_student' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.teacher_id::text AS reference_id,
           'orphan: ss_teacher.id = teacher_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_student_teacher_id)' AS proposed_action,
           'DELETE FROM ss_student WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_student x WHERE x.teacher_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_teacher y WHERE y.id = x.teacher_id)
    UNION ALL
    SELECT 'ss_submission' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.exam_id::text AS reference_id,
           'orphan: ss_exam.id = exam_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_submission_exam_id)' AS proposed_action,
           'DELETE FROM ss_submission WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_submission x WHERE x.exam_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_exam y WHERE y.id = x.exam_id)
    UNION ALL
    SELECT 'ss_submission' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.student_id::text AS reference_id,
           'orphan: ss_student.id = student_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_submission_student_id)' AS proposed_action,
           'DELETE FROM ss_submission WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_submission x WHERE x.student_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_student y WHERE y.id = x.student_id)
    UNION ALL
    SELECT 'ss_teacher' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.class_id::text AS reference_id,
           'orphan: ss_class.id = class_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_teacher_class_id)' AS proposed_action,
           'DELETE FROM ss_teacher WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_teacher x WHERE x.class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_class y WHERE y.id = x.class_id)
    UNION ALL
    SELECT 'staging_expense' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.batch_id::text AS reference_id,
           'orphan: import_batch.id = batch_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_staging_expense_batch_id)' AS proposed_action,
           'DELETE FROM staging_expense WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_expense x WHERE x.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch y WHERE y.id = x.batch_id)
    UNION ALL
    SELECT 'staging_expense' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_staging_expense_run_id)' AS proposed_action,
           'DELETE FROM staging_expense WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_expense x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'staging_family' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.batch_id::text AS reference_id,
           'orphan: import_batch.id = batch_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_staging_family_batch_id)' AS proposed_action,
           'DELETE FROM staging_family WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_family x WHERE x.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch y WHERE y.id = x.batch_id)
    UNION ALL
    SELECT 'staging_family' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_staging_family_run_id)' AS proposed_action,
           'DELETE FROM staging_family WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_family x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'staging_income' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.batch_id::text AS reference_id,
           'orphan: import_batch.id = batch_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_staging_income_batch_id)' AS proposed_action,
           'DELETE FROM staging_income WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_income x WHERE x.batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch y WHERE y.id = x.batch_id)
    UNION ALL
    SELECT 'staging_income' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_staging_income_run_id)' AS proposed_action,
           'DELETE FROM staging_income WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_income x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'staging_raw' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.run_id::text AS reference_id,
           'orphan: import_run.id = run_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_staging_raw_run_id)' AS proposed_action,
           'DELETE FROM staging_raw WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_raw x WHERE x.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_run y WHERE y.id = x.run_id)
    UNION ALL
    SELECT 'uploaded_file' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.uploaded_by_id::text AS reference_id,
           'orphan: app_user.id = uploaded_by_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_uploaded_file_uploaded_by_id)' AS proposed_action,
           'DELETE FROM uploaded_file WHERE id = ' || x.id || ';' AS exact_sql
      FROM uploaded_file x WHERE x.uploaded_by_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user y WHERE y.id = x.uploaded_by_id)
    UNION ALL
    SELECT 'user_permissions' AS table_name, x.id AS pk, NULL AS tenant_id, x.app_user_id::text AS reference_id,
           'orphan: app_user.id = app_user_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_user_permissions_app_user_id)' AS proposed_action,
           'DELETE FROM user_permissions WHERE id = ' || x.id || ';' AS exact_sql
      FROM user_permissions x WHERE x.app_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM app_user y WHERE y.id = x.app_user_id)
    UNION ALL
    SELECT 'volunteer_assignment' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.event_id::text AS reference_id,
           'orphan: church_event.id = event_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_volunteer_assignment_event_id)' AS proposed_action,
           'DELETE FROM volunteer_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_assignment x WHERE x.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event y WHERE y.id = x.event_id)
    UNION ALL
    SELECT 'volunteer_assignment' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_volunteer_assignment_family_member_id)' AS proposed_action,
           'DELETE FROM volunteer_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_assignment x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'volunteer_assignment' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.role_id::text AS reference_id,
           'orphan: volunteer_role.id = role_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_volunteer_assignment_role_id)' AS proposed_action,
           'DELETE FROM volunteer_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_assignment x WHERE x.role_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_role y WHERE y.id = x.role_id)
    UNION ALL
    SELECT 'volunteer_attendance' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.assignment_id::text AS reference_id,
           'orphan: volunteer_assignment.id = assignment_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_volunteer_attendance_assignment_id)' AS proposed_action,
           'DELETE FROM volunteer_attendance WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_attendance x WHERE x.assignment_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_assignment y WHERE y.id = x.assignment_id)
    UNION ALL
    SELECT 'volunteer_profile' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.family_member_id::text AS reference_id,
           'orphan: family_member.id = family_member_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_volunteer_profile_family_member_id)' AS proposed_action,
           'DELETE FROM volunteer_profile WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_profile x WHERE x.family_member_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_member y WHERE y.id = x.family_member_id)
    UNION ALL
    SELECT 'volunteer_profile_role' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.role_id::text AS reference_id,
           'orphan: volunteer_role.id = role_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_volunteer_profile_role_role_id)' AS proposed_action,
           'DELETE FROM volunteer_profile_role WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_profile_role x WHERE x.role_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_role y WHERE y.id = x.role_id)
    UNION ALL
    SELECT 'volunteer_profile_role' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.volunteer_profile_id::text AS reference_id,
           'orphan: volunteer_profile.id = volunteer_profile_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_volunteer_profile_role_volunteer_profile_id)' AS proposed_action,
           'DELETE FROM volunteer_profile_role WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_profile_role x WHERE x.volunteer_profile_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM volunteer_profile y WHERE y.id = x.volunteer_profile_id)
    UNION ALL
    SELECT 'worship_assignment' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.group_id::text AS reference_id,
           'orphan: worship_group.id = group_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_worship_assignment_group_id)' AS proposed_action,
           'DELETE FROM worship_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_assignment x WHERE x.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_group y WHERE y.id = x.group_id)
    UNION ALL
    SELECT 'worship_assignment_member' AS table_name, x.id AS pk, NULL AS tenant_id, x.instrument_id::text AS reference_id,
           'orphan: worship_instrument.id = instrument_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_worship_assignment_member_instrument_id)' AS proposed_action,
           'DELETE FROM worship_assignment_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_assignment_member x WHERE x.instrument_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_instrument y WHERE y.id = x.instrument_id)
    UNION ALL
    SELECT 'worship_group_member' AS table_name, x.id AS pk, NULL AS tenant_id, x.instrument_id::text AS reference_id,
           'orphan: worship_instrument.id = instrument_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_worship_group_member_instrument_id)' AS proposed_action,
           'DELETE FROM worship_group_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_group_member x WHERE x.instrument_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_instrument y WHERE y.id = x.instrument_id)
    UNION ALL
    SELECT 'worship_instrument' AS table_name, x.id AS pk, NULL AS tenant_id, x.group_id::text AS reference_id,
           'orphan: worship_group.id = group_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_worship_instrument_group_id)' AS proposed_action,
           'DELETE FROM worship_instrument WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_instrument x WHERE x.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_group y WHERE y.id = x.group_id)
    UNION ALL
    SELECT 'worship_song' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.group_id::text AS reference_id,
           'orphan: worship_group.id = group_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_worship_song_group_id)' AS proposed_action,
           'DELETE FROM worship_song WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_song x WHERE x.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_group y WHERE y.id = x.group_id)
    UNION ALL
    SELECT 'church_event_day' AS table_name, x.id AS pk, NULL AS tenant_id, x.event_id::text AS reference_id,
           'orphan: church_event.id = event_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_church_event_day_event_id)' AS proposed_action,
           'DELETE FROM church_event_day WHERE id = ' || x.id || ';' AS exact_sql
      FROM church_event_day x WHERE x.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM church_event y WHERE y.id = x.event_id)
    UNION ALL
    SELECT 'event_registration_reminder_log' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.contact_id::text AS reference_id,
           'orphan: event_registration_reminder_contact.id = contact_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_registration_reminder_log_contact_id)' AS proposed_action,
           'DELETE FROM event_registration_reminder_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_registration_reminder_log x WHERE x.contact_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event_registration_reminder_contact y WHERE y.id = x.contact_id)
    UNION ALL
    SELECT 'event_volunteer_role' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.event_volunteer_id::text AS reference_id,
           'orphan: event_volunteer.id = event_volunteer_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_event_volunteer_role_event_volunteer_id)' AS proposed_action,
           'DELETE FROM event_volunteer_role WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_volunteer_role x WHERE x.event_volunteer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event_volunteer y WHERE y.id = x.event_volunteer_id)
    UNION ALL
    SELECT 'expense_check_image' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.expense_id::text AS reference_id,
           'orphan: expense.id = expense_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_expense_check_image_expense_id)' AS proposed_action,
           'DELETE FROM expense_check_image WHERE id = ' || x.id || ';' AS exact_sql
      FROM expense_check_image x WHERE x.expense_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM expense y WHERE y.id = x.expense_id)
    UNION ALL
    SELECT 'guess_it_group_participant' AS table_name, x.id AS pk, NULL AS tenant_id, x.group_id::text AS reference_id,
           'orphan: guess_it_group.id = group_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_guess_it_group_participant_group_id)' AS proposed_action,
           'DELETE FROM guess_it_group_participant WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_group_participant x WHERE x.group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_group y WHERE y.id = x.group_id)
    UNION ALL
    SELECT 'guess_it_participant' AS table_name, x.id AS pk, NULL AS tenant_id, x.game_id::text AS reference_id,
           'orphan: guess_it_game.id = game_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_guess_it_participant_game_id)' AS proposed_action,
           'DELETE FROM guess_it_participant WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_participant x WHERE x.game_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_game y WHERE y.id = x.game_id)
    UNION ALL
    SELECT 'guess_it_participant' AS table_name, x.id AS pk, NULL AS tenant_id, x.group_participant_id::text AS reference_id,
           'orphan: guess_it_group_participant.id = group_participant_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_guess_it_participant_group_participant_id)' AS proposed_action,
           'DELETE FROM guess_it_participant WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_participant x WHERE x.group_participant_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM guess_it_group_participant y WHERE y.id = x.group_participant_id)
    UNION ALL
    SELECT 'income_check_image' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.income_id::text AS reference_id,
           'orphan: income.id = income_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_income_check_image_income_id)' AS proposed_action,
           'DELETE FROM income_check_image WHERE id = ' || x.id || ';' AS exact_sql
      FROM income_check_image x WHERE x.income_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM income y WHERE y.id = x.income_id)
    UNION ALL
    SELECT 'payroll_paystub_item' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, x.paystub_id::text AS reference_id,
           'orphan: payroll_paystub.id = paystub_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_payroll_paystub_item_paystub_id)' AS proposed_action,
           'DELETE FROM payroll_paystub_item WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_paystub_item x WHERE x.paystub_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payroll_paystub y WHERE y.id = x.paystub_id)
    UNION ALL
    SELECT 'ss_answer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.question_id::text AS reference_id,
           'orphan: ss_question.id = question_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_answer_question_id)' AS proposed_action,
           'DELETE FROM ss_answer WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_answer x WHERE x.question_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_question y WHERE y.id = x.question_id)
    UNION ALL
    SELECT 'ss_answer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, x.submission_id::text AS reference_id,
           'orphan: ss_submission.id = submission_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_ss_answer_submission_id)' AS proposed_action,
           'DELETE FROM ss_answer WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_answer x WHERE x.submission_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ss_submission y WHERE y.id = x.submission_id)
    UNION ALL
    SELECT 'worship_assignment_member' AS table_name, x.id AS pk, NULL AS tenant_id, x.assignment_id::text AS reference_id,
           'orphan: worship_assignment.id = assignment_id does not exist' AS reason,
           'REVIEW then DELETE (orphan — parent row absent, unreachable, blocks FK fk_worship_assignment_member_assignment_id)' AS proposed_action,
           'DELETE FROM worship_assignment_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_assignment_member x WHERE x.assignment_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM worship_assignment y WHERE y.id = x.assignment_id)
    UNION ALL
    SELECT 'access_audit' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM access_audit WHERE id = ' || x.id || ';' AS exact_sql
      FROM access_audit x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'app_user' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM app_user WHERE id = ' || x.id || ';' AS exact_sql
      FROM app_user x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'attendance_record' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM attendance_record WHERE id = ' || x.id || ';' AS exact_sql
      FROM attendance_record x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'attendance_service_type' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM attendance_service_type WHERE id = ' || x.id || ';' AS exact_sql
      FROM attendance_service_type x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'app_group' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM app_group WHERE id = ' || x.id || ';' AS exact_sql
      FROM app_group x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'attendance_visitor' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM attendance_visitor WHERE id = ' || x.id || ';' AS exact_sql
      FROM attendance_visitor x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'auto_reminder' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM auto_reminder WHERE id = ' || x.id || ';' AS exact_sql
      FROM auto_reminder x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'bank_sync_trusted_device' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM bank_sync_trusted_device WHERE id = ' || x.id || ';' AS exact_sql
      FROM bank_sync_trusted_device x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'bank_sync_verification' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM bank_sync_verification WHERE id = ' || x.id || ';' AS exact_sql
      FROM bank_sync_verification x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'church_event' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM church_event WHERE id = ' || x.id || ';' AS exact_sql
      FROM church_event x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'church_logo' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM church_logo WHERE id = ' || x.id || ';' AS exact_sql
      FROM church_logo x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'church_plaid_setting' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM church_plaid_setting WHERE id = ' || x.id || ';' AS exact_sql
      FROM church_plaid_setting x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'church_voice_setting' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM church_voice_setting WHERE id = ' || x.id || ';' AS exact_sql
      FROM church_voice_setting x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'connect_submission' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM connect_submission WHERE id = ' || x.id || ';' AS exact_sql
      FROM connect_submission x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'dashboard_preference' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM dashboard_preference WHERE id = ' || x.id || ';' AS exact_sql
      FROM dashboard_preference x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'demo_deletion_audit' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM demo_deletion_audit WHERE id = ' || x.id || ';' AS exact_sql
      FROM demo_deletion_audit x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'demo_deletion_audit' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM demo_deletion_audit WHERE id = ' || x.id || ';' AS exact_sql
      FROM demo_deletion_audit x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'demo_role_access' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM demo_role_access WHERE id = ' || x.id || ';' AS exact_sql
      FROM demo_role_access x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'donation' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM donation WHERE id = ' || x.id || ';' AS exact_sql
      FROM donation x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'email_settings' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM email_settings WHERE id = ' || x.id || ';' AS exact_sql
      FROM email_settings x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'email_unsubscribe' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM email_unsubscribe WHERE id = ' || x.id || ';' AS exact_sql
      FROM email_unsubscribe x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'event_email_template' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM event_email_template WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_email_template x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'event_registration' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM event_registration WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_registration x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'event_registration_reminder_contact' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM event_registration_reminder_contact WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_registration_reminder_contact x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'event_registration_reminder_log' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM event_registration_reminder_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_registration_reminder_log x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'event_reminder' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM event_reminder WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_reminder x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'event_volunteer' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM event_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_volunteer x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'event_volunteer_role' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM event_volunteer_role WHERE id = ' || x.id || ';' AS exact_sql
      FROM event_volunteer_role x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'family' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM family WHERE id = ' || x.id || ';' AS exact_sql
      FROM family x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'expense_check_image' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM expense_check_image WHERE id = ' || x.id || ';' AS exact_sql
      FROM expense_check_image x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'expense' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM expense WHERE id = ' || x.id || ';' AS exact_sql
      FROM expense x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'finalized_section' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM finalized_section WHERE id = ' || x.id || ';' AS exact_sql
      FROM finalized_section x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'financial_report_letter' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM financial_report_letter WHERE id = ' || x.id || ';' AS exact_sql
      FROM financial_report_letter x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'follow_up' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM follow_up WHERE id = ' || x.id || ';' AS exact_sql
      FROM follow_up x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'guess_it_game' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM guess_it_game WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_game x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'guess_it_group' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM guess_it_group WHERE id = ' || x.id || ';' AS exact_sql
      FROM guess_it_group x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'help_audit_log' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM help_audit_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM help_audit_log x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'import_audit' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM import_audit WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_audit x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'family_member' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM family_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM family_member x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'group_member' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM group_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM group_member x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'import_batch' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM import_batch WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_batch x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'import_run' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM import_run WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_run x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'import_source_profile' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM import_source_profile WHERE id = ' || x.id || ';' AS exact_sql
      FROM import_source_profile x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'income_check_image' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM income_check_image WHERE id = ' || x.id || ';' AS exact_sql
      FROM income_check_image x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'issued_certificate' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM issued_certificate WHERE id = ' || x.id || ';' AS exact_sql
      FROM issued_certificate x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'km_authorized_pickup' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_authorized_pickup WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_authorized_pickup x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'km_checkin' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_checkin WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_checkin x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'km_child' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_child WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_child x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'km_child_setup' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_child_setup WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_child_setup x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'km_classroom' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_classroom WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_classroom x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'income' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM income WHERE id = ' || x.id || ';' AS exact_sql
      FROM income x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'km_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_volunteer x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'km_volunteer_role' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_volunteer_role WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_volunteer_role x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'km_volunteer_role_assignment' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM km_volunteer_role_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM km_volunteer_role_assignment x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'login_attempt_log' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM login_attempt_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM login_attempt_log x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'login_block' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM login_block WHERE id = ' || x.id || ';' AS exact_sql
      FROM login_block x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'meeting' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM meeting WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'meeting_message_template' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM meeting_message_template WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting_message_template x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'meeting_reminder' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM meeting_reminder WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting_reminder x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'meeting_skip_date' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM meeting_skip_date WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting_skip_date x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'main_source' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM main_source WHERE id = ' || x.id || ';' AS exact_sql
      FROM main_source x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'member_attendance_code' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM member_attendance_code WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_attendance_code x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'member_message' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM member_message WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_message x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'member_type' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM member_type WHERE id = ' || x.id || ';' AS exact_sql
      FROM member_type x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'mid_reg_meet' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM mid_reg_meet WHERE id = ' || x.id || ';' AS exact_sql
      FROM mid_reg_meet x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'mid_reg_meet_rsvp' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM mid_reg_meet_rsvp WHERE id = ' || x.id || ';' AS exact_sql
      FROM mid_reg_meet_rsvp x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'note' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM note WHERE id = ' || x.id || ';' AS exact_sql
      FROM note x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'ntag_credential' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ntag_credential WHERE id = ' || x.id || ';' AS exact_sql
      FROM ntag_credential x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'meeting_type' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM meeting_type WHERE id = ' || x.id || ';' AS exact_sql
      FROM meeting_type x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'membership_family' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM membership_family WHERE id = ' || x.id || ';' AS exact_sql
      FROM membership_family x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'ntag_landing_config' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ntag_landing_config WHERE id = ' || x.id || ';' AS exact_sql
      FROM ntag_landing_config x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ntag_landing_event' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ntag_landing_event WHERE id = ' || x.id || ';' AS exact_sql
      FROM ntag_landing_event x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ntag_login_challenge' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ntag_login_challenge WHERE id = ' || x.id || ';' AS exact_sql
      FROM ntag_login_challenge x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ntag_login_history' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ntag_login_history WHERE id = ' || x.id || ';' AS exact_sql
      FROM ntag_login_history x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'one_time_reminder' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM one_time_reminder WHERE id = ' || x.id || ';' AS exact_sql
      FROM one_time_reminder x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'openai_usage' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM openai_usage WHERE id = ' || x.id || ';' AS exact_sql
      FROM openai_usage x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'payroll_audit_log' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_audit_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_audit_log x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'payroll_deduction_definition' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_deduction_definition WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_deduction_definition x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'payroll_employee' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_employee WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_employee x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'payroll_employee_deduction' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_employee_deduction WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_employee_deduction x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'payroll_paystub' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_paystub WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_paystub x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'payroll_paystub_item' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_paystub_item WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_paystub_item x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'payroll_run' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_run WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_run x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'payroll_w4' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM payroll_w4 WHERE id = ' || x.id || ';' AS exact_sql
      FROM payroll_w4 x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'plaid_account' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM plaid_account WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_account x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'plaid_audit_log' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM plaid_audit_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_audit_log x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'plaid_item' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM plaid_item WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_item x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'plaid_transaction_staging' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM plaid_transaction_staging WHERE id = ' || x.id || ';' AS exact_sql
      FROM plaid_transaction_staging x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'pledge_campaign' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM pledge_campaign WHERE id = ' || x.id || ';' AS exact_sql
      FROM pledge_campaign x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'pledge_member' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM pledge_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM pledge_member x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'policy_acceptance' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM policy_acceptance WHERE id = ' || x.id || ';' AS exact_sql
      FROM policy_acceptance x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'prayer_note' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM prayer_note WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_note x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'prayer_request' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM prayer_request WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_request x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'prayer_request_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM prayer_request_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_request_volunteer x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'prayer_schedule' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM prayer_schedule WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_schedule x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'prayer_section' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM prayer_section WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_section x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'prayer_volunteer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM prayer_volunteer WHERE id = ' || x.id || ';' AS exact_sql
      FROM prayer_volunteer x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'private_access_log' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM private_access_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM private_access_log x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'private_access_setting' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM private_access_setting WHERE id = ' || x.id || ';' AS exact_sql
      FROM private_access_setting x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'private_network' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM private_network WHERE id = ' || x.id || ';' AS exact_sql
      FROM private_network x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'private_page_rule' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM private_page_rule WHERE id = ' || x.id || ';' AS exact_sql
      FROM private_page_rule x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'promise_verse' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM promise_verse WHERE id = ' || x.id || ';' AS exact_sql
      FROM promise_verse x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'public_prayer_note' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM public_prayer_note WHERE id = ' || x.id || ';' AS exact_sql
      FROM public_prayer_note x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'public_prayer_request' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM public_prayer_request WHERE id = ' || x.id || ';' AS exact_sql
      FROM public_prayer_request x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'public_screen_link' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM public_screen_link WHERE id = ' || x.id || ';' AS exact_sql
      FROM public_screen_link x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'push_notification_log' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM push_notification_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM push_notification_log x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'push_subscription' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM push_subscription WHERE id = ' || x.id || ';' AS exact_sql
      FROM push_subscription x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'reminder_sent_log' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM reminder_sent_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM reminder_sent_log x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'purpose' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM purpose WHERE id = ' || x.id || ';' AS exact_sql
      FROM purpose x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'sms_opt_in' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM sms_opt_in WHERE id = ' || x.id || ';' AS exact_sql
      FROM sms_opt_in x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'song' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song WHERE id = ' || x.id || ';' AS exact_sql
      FROM song x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'song_audit_log' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song_audit_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_audit_log x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'song_book' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song_book WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_book x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'song_book_access' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song_book_access WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_book_access x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'song_book_ad' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song_book_ad WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_book_ad x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'song_book_asset' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song_book_asset WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_book_asset x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'song_book_publish' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song_book_publish WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_book_publish x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'song_section' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM song_section WHERE id = ' || x.id || ';' AS exact_sql
      FROM song_section x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_answer' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_answer WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_answer x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_class' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_class WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_class x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_exam' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_exam WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_exam x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_file' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_file WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_file x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_lesson' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_lesson WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_lesson x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_note' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_note WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_note x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_question' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_question WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_question x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_student' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_student WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_student x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_submission' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_submission WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_submission x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'ss_teacher' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM ss_teacher WHERE id = ' || x.id || ';' AS exact_sql
      FROM ss_teacher x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'staging_expense' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM staging_expense WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_expense x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'staging_family' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM staging_family WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_family x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'staging_income' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM staging_income WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_income x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'staging_raw' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM staging_raw WHERE id = ' || x.id || ';' AS exact_sql
      FROM staging_raw x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'stripe_settings' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM stripe_settings WHERE id = ' || x.id || ';' AS exact_sql
      FROM stripe_settings x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'subscription_usage' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM subscription_usage WHERE id = ' || x.id || ';' AS exact_sql
      FROM subscription_usage x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'temporary_access' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM temporary_access WHERE id = ' || x.id || ';' AS exact_sql
      FROM temporary_access x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'sub_source' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM sub_source WHERE id = ' || x.id || ';' AS exact_sql
      FROM sub_source x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'transaction_type' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM transaction_type WHERE id = ' || x.id || ';' AS exact_sql
      FROM transaction_type x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'uploaded_file' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM uploaded_file WHERE id = ' || x.id || ';' AS exact_sql
      FROM uploaded_file x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'user_favorite' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM user_favorite WHERE id = ' || x.id || ';' AS exact_sql
      FROM user_favorite x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'verification_code' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM verification_code WHERE id = ' || x.id || ';' AS exact_sql
      FROM verification_code x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'vision_usage_log' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM vision_usage_log WHERE id = ' || x.id || ';' AS exact_sql
      FROM vision_usage_log x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'volunteer_assignment' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM volunteer_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_assignment x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'volunteer_attendance' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM volunteer_attendance WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_attendance x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'volunteer_profile' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM volunteer_profile WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_profile x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'volunteer_profile_role' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM volunteer_profile_role WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_profile_role x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'volunteer_role' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM volunteer_role WHERE id = ' || x.id || ';' AS exact_sql
      FROM volunteer_role x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
    UNION ALL
    SELECT 'whatsapp_settings' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM whatsapp_settings WHERE id = ' || x.id || ';' AS exact_sql
      FROM whatsapp_settings x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'worship_assignment' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM worship_assignment WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_assignment x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'worship_group' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM worship_group WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_group x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'worship_song' AS table_name, x.id AS pk, x.client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: client_id = ' || x.client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM worship_song WHERE id = ' || x.id || ';' AS exact_sql
      FROM worship_song x WHERE x.client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.client_id)
    UNION ALL
    SELECT 'membership_family_member' AS table_name, x.id AS pk, x.app_client_id::text AS tenant_id, NULL::text AS reference_id,
           'orphaned-tenant: app_client_id = ' || x.app_client_id || ' is not a live service_client' AS reason,
           'REVIEW — confirm the tenant was intentionally removed, then DELETE' AS proposed_action,
           'DELETE FROM membership_family_member WHERE id = ' || x.id || ';' AS exact_sql
      FROM membership_family_member x WHERE x.app_client_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM service_client s WHERE s.client_id = x.app_client_id)
) disclosure ORDER BY table_name, pk;
