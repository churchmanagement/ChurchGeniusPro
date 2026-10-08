-- ═══════════════════════════════════════════════════════════════════════════
-- V5 — Ledger scalability, part B: ledger_month_summary
-- ═══════════════════════════════════════════════════════════════════════════
--
-- WHAT: one row per (church, month, kind, fund) holding the total and count of
-- active ledger rows, maintained by AFTER triggers on income and expense in the
-- SAME transaction as every write. The Accountant dashboard's all-time and
-- year-scoped aggregates read this table (tens of rows) instead of scanning the
-- church's whole history. Benchmark, one church with 800,000 income / 120,000
-- expense rows: dashboard database time ≈0.5 s (after V4) → ≈60 ms, and flat as
-- years accumulate.
--
-- WHY TRIGGERS, NOT SERVICE CODE: the ledger has at least six write paths
-- (Income/Expense services, Bank Import, Plaid sync, the ETL importer,
-- TestDataService, and SQL run by hand). A trigger covers all of them, including
-- a row fixed in psql; service code would cover only the ones someone remembered.
--
-- WHAT IS AUTHORITATIVE: the income and expense rows, always. The summary is a
-- derived cache. If the two ever disagree, the rows win: ledger_summary_reconcile()
-- lists the differences and ledger_summary_rebuild(client) regenerates a church's
-- rows from scratch (all churches when called with NULL). The app runs reconcile
-- nightly (LedgerSummaryReconcileScheduler) and rebuilds any church that drifted.
-- After a database restore, run SELECT ledger_summary_rebuild(NULL).
--
-- KEY LAYOUT: income rows carry sub_source_id (main_source_id = -1; the fund is
-- resolved through sub_source at read time, so re-parenting a sub-source never
-- stales the summary); expense rows carry main_source_id + purpose_id
-- (sub_source_id = -1). Rows whose total and count both reach zero are removed.
-- Rows with a NULL app_client_id (legacy) are not summarised — the dashboard
-- cannot show them anyway.
--
-- IDEMPOTENT AND RE-RUNNABLE: everything is CREATE … IF NOT EXISTS / CREATE OR
-- REPLACE / create-trigger-if-absent, and the backfill runs only when the summary
-- table is empty. On a fresh database Flyway runs this before Hibernate has
-- created income/expense, so it no-ops; config/LedgerSummarySchemaInitializer
-- executes this same file again after Hibernate's schema update on every
-- startup, which is what builds it there. Requires PostgreSQL 11+.

DO $do$
BEGIN
    IF to_regclass('public.income') IS NULL OR to_regclass('public.expense') IS NULL THEN
        RAISE NOTICE 'V5: income/expense not present - skipped (fresh database; LedgerSummarySchemaInitializer applies it after Hibernate).';
        RETURN;
    END IF;

    -- ── 1. The table ───────────────────────────────────────────────────────
    CREATE TABLE IF NOT EXISTS ledger_month_summary (
        app_client_id  varchar(255)  NOT NULL,
        period_month   date          NOT NULL,          -- first day of the month
        kind           char(1)       NOT NULL,          -- 'I' income / 'E' expense
        main_source_id integer       NOT NULL DEFAULT -1,
        sub_source_id  integer       NOT NULL DEFAULT -1,
        purpose_id     integer       NOT NULL DEFAULT -1,
        total_amount   numeric(15,2) NOT NULL DEFAULT 0,
        txn_count      integer       NOT NULL DEFAULT 0,
        updated_at     timestamp     NOT NULL DEFAULT now(),
        CONSTRAINT ledger_month_summary_pkey
            PRIMARY KEY (app_client_id, period_month, kind, main_source_id, sub_source_id, purpose_id),
        CONSTRAINT ledger_month_summary_kind_chk CHECK (kind IN ('I', 'E'))
    );

    -- ── 2. Apply one delta to one summary row ──────────────────────────────
    CREATE OR REPLACE FUNCTION ledger_summary_apply(
        p_client   varchar, p_month date, p_kind char,
        p_main integer, p_sub integer, p_purpose integer,
        p_amount numeric, p_count integer)
    RETURNS void LANGUAGE plpgsql AS $fn$
    BEGIN
        INSERT INTO ledger_month_summary AS s
               (app_client_id, period_month, kind, main_source_id, sub_source_id, purpose_id, total_amount, txn_count)
        VALUES (p_client, p_month, p_kind, p_main, p_sub, p_purpose, p_amount, p_count)
        ON CONFLICT ON CONSTRAINT ledger_month_summary_pkey DO UPDATE
            SET total_amount = s.total_amount + EXCLUDED.total_amount,
                txn_count    = s.txn_count    + EXCLUDED.txn_count,
                updated_at   = now();
        DELETE FROM ledger_month_summary
         WHERE app_client_id = p_client AND period_month = p_month AND kind = p_kind
           AND main_source_id = p_main AND sub_source_id = p_sub AND purpose_id = p_purpose
           AND total_amount = 0 AND txn_count = 0;
    END
    $fn$;

    -- ── 3. Row triggers: subtract the old state, add the new state ─────────
    -- One rule covers every transition: insert (+new), hard delete (-old), amount
    -- or date or fund edit (-old +new, possibly different rows), soft delete
    -- (delete_flag false→true: -old only), un-delete (true→false: +new only).
    CREATE OR REPLACE FUNCTION ledger_income_sync() RETURNS trigger LANGUAGE plpgsql AS $fn$
    BEGIN
        IF TG_OP = 'UPDATE'
           AND OLD.amount        IS NOT DISTINCT FROM NEW.amount
           AND OLD.income_date   IS NOT DISTINCT FROM NEW.income_date
           AND OLD.sub_source_id IS NOT DISTINCT FROM NEW.sub_source_id
           AND OLD.delete_flag   IS NOT DISTINCT FROM NEW.delete_flag
           AND OLD.app_client_id IS NOT DISTINCT FROM NEW.app_client_id THEN
            RETURN NULL;                                  -- nothing the summary tracks changed
        END IF;
        IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.delete_flag = false AND OLD.app_client_id IS NOT NULL THEN
            PERFORM ledger_summary_apply(OLD.app_client_id, date_trunc('month', OLD.income_date)::date, 'I',
                                         -1, OLD.sub_source_id, -1, -OLD.amount, -1);
        END IF;
        IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.delete_flag = false AND NEW.app_client_id IS NOT NULL THEN
            PERFORM ledger_summary_apply(NEW.app_client_id, date_trunc('month', NEW.income_date)::date, 'I',
                                         -1, NEW.sub_source_id, -1, NEW.amount, 1);
        END IF;
        RETURN NULL;
    END
    $fn$;

    CREATE OR REPLACE FUNCTION ledger_expense_sync() RETURNS trigger LANGUAGE plpgsql AS $fn$
    BEGIN
        IF TG_OP = 'UPDATE'
           AND OLD.amount         IS NOT DISTINCT FROM NEW.amount
           AND OLD.expense_date   IS NOT DISTINCT FROM NEW.expense_date
           AND OLD.main_source_id IS NOT DISTINCT FROM NEW.main_source_id
           AND OLD.purpose_id     IS NOT DISTINCT FROM NEW.purpose_id
           AND OLD.delete_flag    IS NOT DISTINCT FROM NEW.delete_flag
           AND OLD.app_client_id  IS NOT DISTINCT FROM NEW.app_client_id THEN
            RETURN NULL;
        END IF;
        IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.delete_flag = false AND OLD.app_client_id IS NOT NULL THEN
            PERFORM ledger_summary_apply(OLD.app_client_id, date_trunc('month', OLD.expense_date)::date, 'E',
                                         OLD.main_source_id, -1, OLD.purpose_id, -OLD.amount, -1);
        END IF;
        IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.delete_flag = false AND NEW.app_client_id IS NOT NULL THEN
            PERFORM ledger_summary_apply(NEW.app_client_id, date_trunc('month', NEW.expense_date)::date, 'E',
                                         NEW.main_source_id, -1, NEW.purpose_id, NEW.amount, 1);
        END IF;
        RETURN NULL;
    END
    $fn$;

    -- Created only when absent: this file re-runs at every startup (see header), and
    -- CREATE OR REPLACE FUNCTION above already refreshes the trigger bodies in place.
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ledger_income_sync'
                                             AND tgrelid = 'public.income'::regclass) THEN
        CREATE TRIGGER trg_ledger_income_sync
            AFTER INSERT OR UPDATE OR DELETE ON income
            FOR EACH ROW EXECUTE FUNCTION ledger_income_sync();
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ledger_expense_sync'
                                             AND tgrelid = 'public.expense'::regclass) THEN
        CREATE TRIGGER trg_ledger_expense_sync
            AFTER INSERT OR UPDATE OR DELETE ON expense
            FOR EACH ROW EXECUTE FUNCTION ledger_expense_sync();
    END IF;

    -- ── 4. Rebuild from the ledger rows (one church, or all with NULL) ─────
    CREATE OR REPLACE FUNCTION ledger_summary_rebuild(p_client varchar DEFAULT NULL)
    RETURNS integer LANGUAGE plpgsql AS $fn$
    DECLARE v_rows integer := 0; v_n integer;
    BEGIN
        DELETE FROM ledger_month_summary WHERE p_client IS NULL OR app_client_id = p_client;

        INSERT INTO ledger_month_summary
               (app_client_id, period_month, kind, main_source_id, sub_source_id, purpose_id, total_amount, txn_count)
        SELECT i.app_client_id, date_trunc('month', i.income_date)::date, 'I', -1, i.sub_source_id, -1,
               SUM(i.amount), COUNT(*)
        FROM   income i
        WHERE  i.delete_flag = false AND i.app_client_id IS NOT NULL
          AND  (p_client IS NULL OR i.app_client_id = p_client)
        GROUP  BY i.app_client_id, date_trunc('month', i.income_date), i.sub_source_id;
        GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;

        INSERT INTO ledger_month_summary
               (app_client_id, period_month, kind, main_source_id, sub_source_id, purpose_id, total_amount, txn_count)
        SELECT e.app_client_id, date_trunc('month', e.expense_date)::date, 'E', e.main_source_id, -1, e.purpose_id,
               SUM(e.amount), COUNT(*)
        FROM   expense e
        WHERE  e.delete_flag = false AND e.app_client_id IS NOT NULL
          AND  (p_client IS NULL OR e.app_client_id = p_client)
        GROUP  BY e.app_client_id, date_trunc('month', e.expense_date), e.main_source_id, e.purpose_id;
        GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;

        RETURN v_rows;
    END
    $fn$;

    -- ── 5. Reconcile: every summary key whose stored figures differ from the rows ──
    -- A missing side counts as zero, so both a stale row and a missing row show up.
    CREATE OR REPLACE FUNCTION ledger_summary_reconcile()
    RETURNS TABLE (app_client_id varchar, period_month date, kind char,
                   main_source_id integer, sub_source_id integer, purpose_id integer,
                   summary_amount numeric, actual_amount numeric,
                   summary_count integer, actual_count integer)
    LANGUAGE sql STABLE AS $fn$
        WITH actual AS (
            SELECT i.app_client_id, date_trunc('month', i.income_date)::date AS period_month, 'I'::char AS kind,
                   -1 AS main_source_id, i.sub_source_id, -1 AS purpose_id,
                   SUM(i.amount) AS total_amount, COUNT(*)::integer AS txn_count
            FROM   income i
            WHERE  i.delete_flag = false AND i.app_client_id IS NOT NULL
            GROUP  BY 1, 2, i.sub_source_id
            UNION ALL
            SELECT e.app_client_id, date_trunc('month', e.expense_date)::date, 'E'::char,
                   e.main_source_id, -1, e.purpose_id,
                   SUM(e.amount), COUNT(*)::integer
            FROM   expense e
            WHERE  e.delete_flag = false AND e.app_client_id IS NOT NULL
            GROUP  BY 1, 2, e.main_source_id, e.purpose_id
        )
        SELECT COALESCE(s.app_client_id,  a.app_client_id),
               COALESCE(s.period_month,   a.period_month),
               COALESCE(s.kind,           a.kind),
               COALESCE(s.main_source_id, a.main_source_id),
               COALESCE(s.sub_source_id,  a.sub_source_id),
               COALESCE(s.purpose_id,     a.purpose_id),
               COALESCE(s.total_amount, 0), COALESCE(a.total_amount, 0),
               COALESCE(s.txn_count, 0),    COALESCE(a.txn_count, 0)
        FROM   ledger_month_summary s
        FULL OUTER JOIN actual a
          ON  a.app_client_id = s.app_client_id AND a.period_month = s.period_month AND a.kind = s.kind
          AND a.main_source_id = s.main_source_id AND a.sub_source_id = s.sub_source_id AND a.purpose_id = s.purpose_id
        WHERE  s.app_client_id IS NULL OR a.app_client_id IS NULL
           OR  s.total_amount <> a.total_amount OR s.txn_count <> a.txn_count
        ORDER  BY 1, 2, 3, 4, 5, 6
    $fn$;

    -- ── 6. Backfill, only when the table is empty ──────────────────────────
    IF NOT EXISTS (SELECT 1 FROM ledger_month_summary LIMIT 1) THEN
        RAISE NOTICE 'V5: ledger_month_summary backfilled with % rows.', ledger_summary_rebuild(NULL);
    END IF;
END
$do$;
