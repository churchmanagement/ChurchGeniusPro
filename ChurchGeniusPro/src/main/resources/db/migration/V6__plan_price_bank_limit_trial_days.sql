-- ═══════════════════════════════════════════════════════════════════════════
-- V6 — Subscription plans: monthly price, Bank Sync account limit, trial duration
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Three new columns on subscription_plan (entity: SubscriptionPlan):
--   monthly_price     numeric(10,2)  null → shown as $0/month
--   max_bank_accounts integer        null = unlimited, 0 = none (counts plaid_account rows)
--   trial_days        integer        TRIAL plan only; read by TrialPolicy, the one
--                                    source of truth for every trial-creating flow
--
-- Flyway runs before Hibernate, so on an existing database the columns are added
-- here; on a fresh database the table does not exist yet, this no-ops, and
-- SubscriptionPlanSeeder writes the same defaults when it creates the rows.
--
-- Back-fill, applied only where the value is still NULL so an admin's later edit
-- is never overwritten on redeploy:
--   FREE      $0      no Bank Sync (0)
--   STANDARD  $14.99  up to 3 accounts (and its bankSync feature flag switched on)
--   PRO       $34.99  unlimited
--   TRIAL     $0      unlimited, trial_days = 30 (today's hard-coded length)
--
-- The TRIAL description used to START with the literal "30-day free trial · ".
-- That prefix is now rendered from trial_days, so it is stripped from the stored
-- text here to stop it appearing twice. Idempotent.

DO $$
BEGIN
    IF to_regclass('public.subscription_plan') IS NULL THEN
        RAISE NOTICE 'V6: subscription_plan not present - skipped (fresh database; seeder applies defaults).';
        RETURN;
    END IF;

    ALTER TABLE subscription_plan ADD COLUMN IF NOT EXISTS monthly_price     numeric(10,2);
    ALTER TABLE subscription_plan ADD COLUMN IF NOT EXISTS max_bank_accounts integer;
    ALTER TABLE subscription_plan ADD COLUMN IF NOT EXISTS trial_days        integer;

    UPDATE subscription_plan SET monthly_price = 0      WHERE upper(plan_code) = 'FREE'     AND monthly_price IS NULL;
    UPDATE subscription_plan SET monthly_price = 14.99  WHERE upper(plan_code) = 'STANDARD' AND monthly_price IS NULL;
    UPDATE subscription_plan SET monthly_price = 34.99  WHERE upper(plan_code) = 'PRO'      AND monthly_price IS NULL;
    UPDATE subscription_plan SET monthly_price = 0      WHERE upper(plan_code) = 'TRIAL'    AND monthly_price IS NULL;

    -- max_bank_accounts: NULL already means unlimited (PRO, TRIAL); only the capped plans need a value.
    UPDATE subscription_plan SET max_bank_accounts = 0  WHERE upper(plan_code) = 'FREE'     AND max_bank_accounts IS NULL;
    UPDATE subscription_plan SET max_bank_accounts = 3  WHERE upper(plan_code) = 'STANDARD' AND max_bank_accounts IS NULL;

    UPDATE subscription_plan SET trial_days = 30        WHERE upper(plan_code) = 'TRIAL'    AND trial_days IS NULL;

    -- Standard was seeded with the bankSync feature switched OFF, which blocks every
    -- Bank Sync page and API before the account limit is ever consulted. Standard now
    -- includes Bank Sync up to 3 accounts, so remove that one key (a missing key
    -- means enabled). Runs once; any other feature flag on the plan is untouched.
    BEGIN
        UPDATE subscription_plan
           SET features_json = (features_json::jsonb - 'bankSync')::text
         WHERE upper(plan_code) = 'STANDARD'
           AND features_json IS NOT NULL AND btrim(features_json) <> ''
           AND (features_json::jsonb ->> 'bankSync') = 'false';
    EXCEPTION WHEN others THEN
        RAISE NOTICE 'V6: STANDARD features_json not valid JSON - Bank Sync flag left as is (%).', SQLERRM;
    END;

    UPDATE subscription_plan
       SET description = regexp_replace(description, '^\s*\d+-day free trial\s*·\s*', '', 'i')
     WHERE upper(plan_code) = 'TRIAL'
       AND description ~* '^\s*\d+-day free trial\s*·';
END $$;
