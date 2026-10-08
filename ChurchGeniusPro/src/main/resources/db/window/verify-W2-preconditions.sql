-- ═══════════════════════════════════════════════════════════════════════════
-- verify-W2-preconditions.sql — run BEFORE W2 (READ ONLY)
-- ═══════════════════════════════════════════════════════════════════════════
-- W2 adds UNIQUE + NOT NULL to the tenant root keys. It aborts itself if the data is
-- not clean, but run this first so you SEE the state and can fix it in advance. Every
-- "*_blocking" count must be 0 before W2 will succeed.
SELECT
    (SELECT count(*) FROM service_client      WHERE client_id IS NULL)                                   AS service_client_null_blocking,      -- must be 0
    (SELECT count(*) FROM (SELECT client_id FROM service_client GROUP BY client_id HAVING count(*)>1) d) AS service_client_dup_blocking,       -- must be 0
    (SELECT count(*) FROM church_registration WHERE client_id IS NULL)                                   AS church_reg_null_blocking,          -- must be 0
    (SELECT count(*) FROM (SELECT client_id FROM church_registration GROUP BY client_id HAVING count(*)>1) d) AS church_reg_dup_blocking,      -- must be 0
    (SELECT count(*) FROM app_user            WHERE client_id IS NULL)                                   AS app_user_null_blocking,            -- must be 0
    -- context (not blocking): service_client rows with no matching church_registration
    (SELECT count(*) FROM service_client sc WHERE NOT EXISTS
        (SELECT 1 FROM church_registration cr WHERE cr.client_id = sc.client_id))                        AS service_client_without_registration;
-- If a *_dup_blocking is > 0, list the offenders before deciding how to merge:
--   SELECT client_id, count(*) FROM service_client GROUP BY client_id HAVING count(*) > 1;
--   SELECT client_id, count(*) FROM church_registration GROUP BY client_id HAVING count(*) > 1;
-- If a *_null_blocking is > 0, list them:
--   SELECT id, * FROM service_client WHERE client_id IS NULL;
