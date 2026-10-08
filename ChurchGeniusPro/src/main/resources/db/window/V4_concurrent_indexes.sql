-- ═══════════════════════════════════════════════════════════════════════════
-- V4_concurrent_indexes.sql — OPTIONAL: build the V4 ledger indexes CONCURRENTLY
-- ═══════════════════════════════════════════════════════════════════════════
-- Same rationale as H6_concurrent_indexes.sql: Flyway V4 builds these two indexes
-- inside a transaction (brief ACCESS EXCLUSIVE lock per table). On today's row
-- counts that is instant. If income/expense are ever large enough that a write
-- lock during deployment matters, run THIS script by hand during normal operation
-- BEFORE deploying the release that contains V4; V4's CREATE INDEX IF NOT EXISTS
-- then finds each index already present and does nothing.
--
-- RUN WITH:   psql -h <host> -U <user> -d <db> -f V4_concurrent_indexes.sql
--   * NOT inside BEGIN/COMMIT and NOT in pgAdmin's Query Tool (transactional).
--   * If one fails midway PostgreSQL leaves an INVALID index; find it with the
--     query at the bottom, DROP it, then re-run.
-- Each statement is guarded by IF NOT EXISTS, so re-running is safe.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_income_tenant_date
    ON income (app_client_id, income_date)
    INCLUDE (amount, sub_source_id)
    WHERE delete_flag = false;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_expense_tenant_date
    ON expense (app_client_id, expense_date)
    INCLUDE (amount, main_source_id, purpose_id)
    WHERE delete_flag = false;

-- Any index left INVALID by an interrupted build:
SELECT i.indexrelid::regclass AS invalid_index
FROM   pg_index i
WHERE  NOT i.indisvalid
  AND  i.indexrelid::regclass::text IN ('idx_income_tenant_date', 'idx_expense_tenant_date');
