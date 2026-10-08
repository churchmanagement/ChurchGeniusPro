-- ============================================================================
-- Connect Submissions → Follow-Ups merge: PRE-FLIGHT (READ-ONLY, 2026-10-01)
--
-- /connectAdmin only checked the ROLE. Its replacement, /followups?tab=connect,
-- also requires the "Follow-ups" permission (more.followups) and the plan's
-- followUps feature. These queries list who would lose Connect access.
-- Nothing here writes. pgAdmin: run STEP 0 once, then each section on its own.
-- ============================================================================

-- STEP 0 — session-local helper: NULL instead of an error for unparseable JSON.
CREATE OR REPLACE FUNCTION pg_temp.try_jsonb(t text) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  IF t IS NULL OR t = '' OR t = 'null' THEN RETURN NULL; END IF;
  RETURN t::jsonb;
EXCEPTION WHEN others THEN RETURN NULL;
END $$;

-- === A. Staff (Admin / SuperAdmin / User) whose effective permissions deny Follow-ups ===
--     Effective record = user_permissions.permissions, else app_user.privileges (same as the app).
--     server_denied = page route refuses (/followups needs more.followups not false).
--     nav_hidden    = the More section or the Follow-Ups item is unticked (menu entry hidden).
SELECT u.user_id, u.role, u.first_name || ' ' || u.last_name AS name, u.email, u.client_id,
       (eff.j ->> 'more.followups') = 'false'                                    AS server_denied,
       ((eff.j ->> 'more') = 'false' OR (eff.j ->> 'more.followups') = 'false')  AS nav_hidden
  FROM app_user u
  LEFT JOIN user_permissions up ON up.app_user_id = u.id
  CROSS JOIN LATERAL (SELECT COALESCE(pg_temp.try_jsonb(up.permissions), pg_temp.try_jsonb(u.privileges)) AS j) eff
 WHERE u.delete_flag = false
   AND u.role IN ('Admin','SuperAdmin','User')
   AND ((eff.j ->> 'more.followups') = 'false' OR (eff.j ->> 'more') = 'false')
 ORDER BY u.client_id, u.role, name;

-- === B. Member-portal users whose member permissions deny Follow-ups ===
--     (Members could open /connectAdmin with no permission check at all; after the
--     merge they need more.followups. Expect few or none.)
SELECT fm.id AS family_member_id, fm.member_ref, fm.first_name || ' ' || fm.last_name AS name,
       fm.app_client_id,
       pg_temp.try_jsonb(fm.member_privileges) ->> 'more.followups' AS more_followups,
       pg_temp.try_jsonb(fm.member_privileges) ->> 'more'           AS more_section
  FROM family_member fm
 WHERE fm.delete_flag = false
   AND fm.member_ref IS NOT NULL
   AND ((pg_temp.try_jsonb(fm.member_privileges) ->> 'more.followups') = 'false'
     OR (pg_temp.try_jsonb(fm.member_privileges) ->> 'more') = 'false')
 ORDER BY fm.app_client_id, name;

-- === C. Churches whose subscription plan switches the followUps feature OFF ===
--     (/connectAdmin was not plan-gated; /followups is.) Plan resolution mirrors
--     SubscriptionService.toPlanCode: FREE→FREE, LIMITED→STANDARD, FULL→PRO, else the code itself.
SELECT sc.client_id, sc.church_name, sc.subscription_type, p.plan_code, sc.status, sc.end_date,
       (SELECT count(*) FROM connect_submission cs
         WHERE cs.client_id = sc.client_id AND cs.delete_flag = false) AS open_connect_submissions
  FROM service_client sc
  JOIN subscription_plan p
    ON upper(p.plan_code) = CASE upper(trim(sc.subscription_type))
                              WHEN 'FREE' THEN 'FREE' WHEN 'LIMITED' THEN 'STANDARD' WHEN 'FULL' THEN 'PRO'
                              ELSE upper(trim(sc.subscription_type)) END
 WHERE COALESCE(sc.delete_flag, false) = false
   AND p.active = true
   AND (pg_temp.try_jsonb(p.features_json) ->> 'followUps') = 'false'
 ORDER BY sc.church_name;
