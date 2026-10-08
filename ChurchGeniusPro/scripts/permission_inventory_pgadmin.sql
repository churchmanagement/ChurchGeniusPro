-- ============================================================================
-- pgAdmin 4 version of scripts/permission_inventory.sql (identical queries;
-- the psql-only \echo / \pset lines are comments here). READ-ONLY.
--
-- HOW TO RUN IN pgAdmin 4 (the Query Tool shows only the LAST statement's
-- result when several run together, so run this in steps, all in the SAME
-- Query Tool tab — the helper function is session-local):
--   1. Select STEP 0 (the CREATE FUNCTION block) → Execute (F5). Once per tab.
--   2. Select the SELECT of one section (A … G) → Execute → copy the grid
--      (right-click the results → Copy, or Download as CSV).
--   3. Repeat for D, E, F (and C). Each section is one statement.
-- ============================================================================
-- STEP 0 — run this first, once per Query Tool tab.
-- Session-local helper (pg_temp = gone when psql exits; works on PostgreSQL 12+):
-- returns NULL instead of failing when a stored value is not valid JSON.
CREATE OR REPLACE FUNCTION pg_temp.try_jsonb(t text) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  IF t IS NULL OR t = '' OR t = 'null' THEN RETURN NULL; END IF;
  RETURN t::jsonb;
EXCEPTION WHEN others THEN RETURN NULL;
END $$;

-- === A. Record counts ===
SELECT (SELECT count(*) FROM user_permissions)                                                   AS user_permissions_rows,
       (SELECT count(*) FROM app_user WHERE privileges IS NOT NULL AND privileges <> '' AND privileges <> 'null') AS app_user_legacy_privileges_rows,
       (SELECT count(*) FROM family_member WHERE member_privileges IS NOT NULL AND member_privileges <> '')      AS member_privileges_rows;


-- === B. Rows whose JSON does not parse (would be treated as "allowed") ===
SELECT 'user_permissions' AS src, app_user_id AS id, left(permissions, 80) AS sample
  FROM user_permissions WHERE permissions IS NOT NULL AND pg_temp.try_jsonb(permissions) IS NULL
UNION ALL
SELECT 'app_user', id, left(privileges, 80) FROM app_user
 WHERE privileges IS NOT NULL AND privileges <> '' AND pg_temp.try_jsonb(privileges) IS NULL
UNION ALL
SELECT 'family_member', id, left(member_privileges, 80) FROM family_member
 WHERE member_privileges IS NOT NULL AND member_privileges <> '' AND pg_temp.try_jsonb(member_privileges) IS NULL;


-- === C. Every distinct key in user_permissions: how many rows hold it, how many hold it FALSE ===
--     (keys not in the current PERM_TREE are the legacy generations)
SELECT k.key,
       count(*)                                        AS rows_with_key,
       count(*) FILTER (WHERE k.value = 'false')       AS rows_false
  FROM user_permissions up
  CROSS JOIN LATERAL jsonb_each_text(pg_temp.try_jsonb(up.permissions)) AS k(key, value)
 WHERE pg_temp.try_jsonb(up.permissions) IS NOT NULL
 GROUP BY k.key
 ORDER BY k.key;


-- === D. LEGACY keys stored FALSE — these are the rows the alias table now denies on page routes ===
--     legacy -> current key it maps to
WITH aliases(legacy, current) AS (VALUES
  ('reports.income','accounting.reports'),('reports.expense','accounting.reports'),('reports.daterange','accounting.reports'),
  ('reports.taxreport','accounting.reports'),('reports.financial','accounting.reports'),('accountingReports','accounting.reports'),
  ('accountingReports.income','accounting.reports'),('accountingReports.expense','accounting.reports'),('accountingReports.dateRange','accounting.reports'),
  ('accountingReports.taxReport','accounting.reports'),('accountingReports.financial','accounting.reports'),
  ('reminders','general.reminders'),('reminders.event','general.reminders'),('reminders.auto','general.reminders'),('reminders.onetime','general.reminders'),
  ('reminders.eventReminders','general.reminders'),('reminders.autoReminders','general.reminders'),('reminders.oneTimeReminders','general.reminders'),
  ('general.kidsministry','general.ministry.kids'),('general.sundayschool','general.ministry.kids'),
  ('general.worshipplanning','general.ministry.worship'),
  ('general.prayer','general.ministry.prayer'),('general.prayerRequests','general.ministry.prayer'),
  ('general.email.settings','general.emailsettings'),('general.event','general.events'),
  ('admin.membershipRequests','admin.membership'),('admin.unsubscribedList','admin.unsubscribed'),
  ('admin.email.delete','admin.email'),('admin.groups.email','admin.email'),
  ('accounting.donationReview','accounting.donation'),('accountSettings','accounting.settings'),
  ('general.certificates','more.certificates'),('general.publicScreens','more.publicscreens'),
  ('member.sundayschool','member.classes'))
SELECT a.legacy, a.current,
       count(*)                                                                           AS rows_legacy_false,
       count(*) FILTER (WHERE (pg_temp.try_jsonb(up.permissions) ->> a.current) = 'true')             AS of_which_current_key_TRUE,
       string_agg(u.user_id || ' (' || u.role || ', ' || u.first_name || ' ' || u.last_name || ')', ', ' ORDER BY u.user_id) AS users
  FROM user_permissions up
  JOIN aliases a ON (pg_temp.try_jsonb(up.permissions) ->> a.legacy) = 'false'
  JOIN app_user u ON u.id = up.app_user_id AND u.delete_flag = false
 WHERE pg_temp.try_jsonb(up.permissions) IS NOT NULL
 GROUP BY a.legacy, a.current
 ORDER BY a.current, a.legacy;
--     of_which_current_key_TRUE > 0 = rows where the new checkbox is ticked but an old key still says false.
--     Those are exactly the rows the Phase 3 save-time alignment fixes the next time that user is saved.


-- === E. A7 PRE-FLIGHT — staff whose saved record has accounting.payroll = false ===
--     Admin/SuperAdmin/Accountant rows here LOSE /payroll until the Payroll box is ticked (User/Limited never had it).
SELECT u.id, u.user_id, u.role, u.first_name, u.last_name, u.client_id,
       pg_temp.try_jsonb(up.permissions) ->> 'accounting'         AS accounting_section,
       pg_temp.try_jsonb(up.permissions) ->> 'accounting.payroll' AS payroll_key,
       up.updated_date
  FROM user_permissions up
  JOIN app_user u ON u.id = up.app_user_id
 WHERE pg_temp.try_jsonb(up.permissions) IS NOT NULL
   AND (pg_temp.try_jsonb(up.permissions) ->> 'accounting.payroll') = 'false'
   AND u.delete_flag = false
 ORDER BY u.role, u.client_id, u.user_id;


-- === F. Same check on the legacy app_user.privileges column (used only when no user_permissions row exists) ===
SELECT u.id, u.user_id, u.role, u.first_name, u.last_name, u.client_id,
       pg_temp.try_jsonb(u.privileges) ->> 'accounting.payroll' AS payroll_key
  FROM app_user u
  LEFT JOIN user_permissions up ON up.app_user_id = u.id
 WHERE up.id IS NULL
   AND u.privileges IS NOT NULL AND u.privileges <> '' AND pg_temp.try_jsonb(u.privileges) IS NOT NULL
   AND (pg_temp.try_jsonb(u.privileges) ->> 'accounting.payroll') = 'false'
   AND u.delete_flag = false
 ORDER BY u.role, u.client_id;


-- === G. Member-portal records holding the legacy member.sundayschool key ===
SELECT count(*)                                                        AS rows_with_key,
       count(*) FILTER (WHERE (pg_temp.try_jsonb(member_privileges) ->> 'member.sundayschool') = 'false') AS rows_false,
       count(*) FILTER (WHERE (pg_temp.try_jsonb(member_privileges) ->> 'member.sundayschool') = 'false'
                          AND (pg_temp.try_jsonb(member_privileges) ->> 'member.classes') = 'true')      AS false_but_classes_true
  FROM family_member
 WHERE member_privileges IS NOT NULL AND member_privileges <> '' AND pg_temp.try_jsonb(member_privileges) IS NOT NULL
   AND pg_temp.try_jsonb(member_privileges) ? 'member.sundayschool';
