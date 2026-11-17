-- 2026-11-16 — Pledge campaigns: "Use this Fund exclusively for this Campaign".
-- Off for every existing campaign, so their figures are calculated exactly as before.
-- Additive; guarded so it is a no-op on a brand-new database (Hibernate creates the table afterwards).
DO $$
BEGIN
  IF to_regclass('public.pledge_campaign') IS NOT NULL THEN
    ALTER TABLE pledge_campaign ADD COLUMN IF NOT EXISTS fund_exclusive BOOLEAN NOT NULL DEFAULT false;
  END IF;
END $$;
