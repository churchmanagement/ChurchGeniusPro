-- ═══════════════════════════════════════════════════════════════════════════
-- V10 — Monthly allowances reset every 30 days from the subscription start date
-- ═══════════════════════════════════════════════════════════════════════════
--
-- subscription_usage (entity SubscriptionUsage) was keyed by calendar month
-- (client_id, usage_month). It is now keyed by the start of the client's 30-day
-- usage period (client_id, period_start); periods run every 30 days from
-- service_client.start_date, in America/Chicago dates (SubscriptionService).
--
-- 1. period_start is added and back-filled with the first day of each existing
--    row's month, so every existing row is kept as history.
-- 2. The old unique key (client_id, usage_month) is replaced by
--    uq_subscription_usage_period (client_id, period_start).
-- 3. Each client that has usage this calendar month gets its CURRENT 30-day
--    period row, seeded with this month's counts, so the switch does not hand
--    anyone a fresh allowance for usage already made this month.
--
-- KNOWN LIMITATION — the first migrated period is approximate, by design.
--    The old rows are monthly aggregates with no daily dates, so the usage that
--    fell inside the current 30-day period cannot be reconstructed exactly. When
--    a client's current period began last month, its sends from the period start
--    to the end of last month stay in last month's row and are NOT counted in the
--    seeded period (that client may send that many more in this one period).
--    Seeding from last month as well was rejected: it would be an approximation
--    that can wrongly REDUCE an allowance. Historical rows are preserved unchanged
--    (only the new period_start column is filled). From the first normal 30-day
--    period after this migration, usage is counted exactly.
--
-- Re-runnable. On a FRESH database the table does not exist yet (Hibernate
-- creates it with the new key), so this no-ops.

DO $$
DECLARE
    today date := (now() AT TIME ZONE 'America/Chicago')::date;
BEGIN
    IF to_regclass('public.subscription_usage') IS NULL THEN
        RAISE NOTICE 'V10: subscription_usage not present - skipped (fresh database).';
        RETURN;
    END IF;

    ALTER TABLE subscription_usage ADD COLUMN IF NOT EXISTS period_start date;
    UPDATE subscription_usage
       SET period_start = to_date(usage_month || '-01', 'YYYY-MM-DD')
     WHERE period_start IS NULL;
    ALTER TABLE subscription_usage ALTER COLUMN period_start SET NOT NULL;

    ALTER TABLE subscription_usage DROP CONSTRAINT IF EXISTS uq_subscription_usage;
    DROP INDEX IF EXISTS uq_subscription_usage;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_subscription_usage_period') THEN
        ALTER TABLE subscription_usage
            ADD CONSTRAINT uq_subscription_usage_period UNIQUE (client_id, period_start);
    END IF;

    IF to_regclass('public.service_client') IS NULL
       OR to_regclass('public.subscription_usage_id_seq') IS NULL THEN
        RAISE NOTICE 'V10: service_client or subscription_usage_id_seq not present - current periods not seeded.';
        RETURN;
    END IF;

    INSERT INTO subscription_usage (id, client_id, usage_month, period_start,
                                    emails_sent, sms_sent, giving_count, created_date)
    SELECT nextval('subscription_usage_id_seq'), sc.client_id, to_char(p.ps, 'YYYY-MM'), p.ps,
           m.emails_sent, m.sms_sent, m.giving_count, now()
      FROM service_client sc
      CROSS JOIN LATERAL (
            SELECT CASE
                     WHEN sc.start_date IS NULL      THEN date_trunc('month', today)::date
                     WHEN today <= sc.start_date     THEN sc.start_date
                     ELSE sc.start_date + ((today - sc.start_date) / 30) * 30
                   END AS ps) p
      JOIN subscription_usage m
        ON m.client_id = sc.client_id
       AND m.usage_month = to_char(today, 'YYYY-MM')
       AND m.period_start = date_trunc('month', today)::date
     WHERE sc.client_id IS NOT NULL
       AND coalesce(sc.delete_flag, false) = false
       AND NOT EXISTS (SELECT 1 FROM subscription_usage x
                        WHERE x.client_id = sc.client_id AND x.period_start = p.ps);
END $$;
