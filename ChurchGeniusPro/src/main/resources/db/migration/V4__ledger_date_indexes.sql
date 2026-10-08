-- ═══════════════════════════════════════════════════════════════════════════
-- V4 — Ledger scalability, part A: partial covering indexes on income / expense
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Every dashboard aggregate and every year-scoped report reads one church's rows
-- by date. The V2 tenant index (app_client_id, delete_flag) finds the church but
-- not the year, and once one church holds a large share of the table PostgreSQL
-- abandons it and scans everything. Benchmark (800,000 income / 120,000 expense
-- rows for one church, 21 churches total, warm cache):
--
--   income by month, one year      141 ms  ->  18 ms
--   income by fund x month, year   168 ms  ->  25 ms
--   tax report, one year           224 ms  ->  61 ms
--   income all-time SUM            125 ms  ->  71 ms   (index-only scan)
--   latest 10 income (LIMIT 10)   2.3 s    -> 0.7 ms   (with the LIMIT query)
--
-- WHERE delete_flag = false keeps soft-deleted rows out of the index, so it holds
-- exactly the rows every ledger query reads; INCLUDE adds the columns those
-- queries aggregate, so the SUM/GROUP BY queries are answered from the index
-- alone (index-only scans). Both are PostgreSQL 11+ features.
--
-- Built non-concurrently here (brief lock, instant on today's row counts). If a
-- table is ever large enough that the lock matters, run
-- db/window/V4_concurrent_indexes.sql by hand BEFORE deploying this release; the
-- IF NOT EXISTS below then finds the index already present and does nothing.
--
-- On a fresh database the tables do not exist yet (Flyway runs before Hibernate),
-- so this no-ops and Flyway still records V4 as applied. For that case the same two
-- statements are repeated in config/DatabaseIndexInitializer, which runs after
-- Hibernate's schema update on every startup (CREATE INDEX IF NOT EXISTS, so it is
-- a no-op wherever V4 already built them). Idempotent.

DO $$
BEGIN
    IF to_regclass('public.income') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_income_tenant_date
            ON income (app_client_id, income_date)
            INCLUDE (amount, sub_source_id)
            WHERE delete_flag = false;
    ELSE
        RAISE NOTICE 'V4: income not present - skipped (fresh database).';
    END IF;

    IF to_regclass('public.expense') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_expense_tenant_date
            ON expense (app_client_id, expense_date)
            INCLUDE (amount, main_source_id, purpose_id)
            WHERE delete_flag = false;
    ELSE
        RAISE NOTICE 'V4: expense not present - skipped (fresh database).';
    END IF;
END $$;
