-- ═══════════════════════════════════════════════════════════════════════════
-- verify-W2-after.sql — run AFTER W2 (READ ONLY)
-- ═══════════════════════════════════════════════════════════════════════════
SELECT
    (SELECT count(*) FROM service_client      WHERE client_id IS NULL)                                   AS service_client_nulls,          -- 0
    (SELECT count(*) FROM (SELECT client_id FROM service_client GROUP BY client_id HAVING count(*)>1) d) AS service_client_dups,           -- 0
    (SELECT count(*) FROM church_registration WHERE client_id IS NULL)                                   AS church_reg_nulls,              -- 0
    (SELECT count(*) FROM (SELECT client_id FROM church_registration GROUP BY client_id HAVING count(*)>1) d) AS church_reg_dups,          -- 0
    (SELECT count(*) FROM app_user            WHERE client_id IS NULL)                                   AS app_user_nulls,                -- 0
    -- the constraints exist and are the right kind
    (SELECT count(*) FROM pg_constraint WHERE conname = 'uq_service_client_client_id'      AND contype='u') AS uq_service_client,          -- 1
    (SELECT count(*) FROM pg_constraint WHERE conname = 'uq_church_registration_client_id' AND contype='u') AS uq_church_reg,              -- 1
    (SELECT CASE WHEN attnotnull THEN 1 ELSE 0 END FROM pg_attribute
      WHERE attrelid='service_client'::regclass AND attname='client_id')                                AS service_client_client_id_notnull, -- 1
    (SELECT CASE WHEN attnotnull THEN 1 ELSE 0 END FROM pg_attribute
      WHERE attrelid='app_user'::regclass AND attname='client_id')                                      AS app_user_client_id_notnull;    -- 1
-- Application checks (not SQL) — confirm tenant resolution still works after W2:
--   * a church admin login resolves to exactly one service_client (no
--     IncorrectResultSizeDataAccessException in the logs)
--   * a NEW church self-registration / trial provisioning still completes and its
--     service_client + church_registration are created with a unique client_id
--   * a staff login and a member/child portal login each resolve to the right tenant
--   * SchemaDriftGuard will log service_client/church_registration/app_user.client_id as
--     nullability drift until the entity annotations are deployed (expected — see runbook)
