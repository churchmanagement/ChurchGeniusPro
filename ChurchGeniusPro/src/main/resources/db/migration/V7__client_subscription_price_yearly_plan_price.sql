-- ═══════════════════════════════════════════════════════════════════════════
-- V7 — Yearly plan price; what each client actually pays
-- ═══════════════════════════════════════════════════════════════════════════
--
-- subscription_plan.yearly_price   numeric(10,2)  null = the plan is not offered yearly
--
-- service_client (entity: ServiceClient; written by SubscriptionLifecycleService):
--   subscription_type   widened varchar(20) -> varchar(40) to fit any plan code
--   billing_frequency   MONTHLY | YEARLY
--   subscription_price  the client's own price per billing period (copied from the
--                       plan when assigned, or a negotiated price)
--   price_overridden    true = negotiated price
--
-- subscription_change (append-only history) is created by Hibernate.
--
-- Back-fill only where NULL, so a re-run or an admin's later edit is never
-- overwritten. Existing clients get the plan's CURRENT list price as their price
-- (LIMITED/FULL map to STANDARD/PRO); yearly clients get the yearly price, which
-- no plan has yet, so theirs stays NULL ("not set") until the admin enters it.
-- No row is deleted and no existing value is changed.
--
-- Flyway runs before Hibernate: on a fresh database these tables do not exist yet
-- and each block no-ops; Hibernate then creates them with the new columns.

DO $$
BEGIN
    IF to_regclass('public.subscription_plan') IS NOT NULL THEN
        ALTER TABLE subscription_plan ADD COLUMN IF NOT EXISTS yearly_price numeric(10,2);
    ELSE
        RAISE NOTICE 'V7: subscription_plan not present - skipped (fresh database).';
    END IF;

    IF to_regclass('public.service_client') IS NULL THEN
        RAISE NOTICE 'V7: service_client not present - skipped (fresh database).';
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'service_client'
                  AND column_name = 'subscription_type'
                  AND character_maximum_length IS NOT NULL AND character_maximum_length < 40) THEN
        ALTER TABLE service_client ALTER COLUMN subscription_type TYPE varchar(40);
    END IF;

    ALTER TABLE service_client ADD COLUMN IF NOT EXISTS billing_frequency  varchar(10);
    ALTER TABLE service_client ADD COLUMN IF NOT EXISTS subscription_price numeric(10,2);
    ALTER TABLE service_client ADD COLUMN IF NOT EXISTS price_overridden   boolean;

    UPDATE service_client
       SET billing_frequency = CASE WHEN upper(coalesce(active_period_unit, '')) = 'YEARS' THEN 'YEARLY' ELSE 'MONTHLY' END
     WHERE billing_frequency IS NULL;

    IF to_regclass('public.subscription_plan') IS NOT NULL THEN
        UPDATE service_client sc
           SET subscription_price = CASE WHEN sc.billing_frequency = 'YEARLY'
                                         THEN p.yearly_price
                                         ELSE coalesce(p.monthly_price, 0) END
          FROM subscription_plan p
         WHERE sc.subscription_price IS NULL
           AND upper(p.plan_code) = CASE upper(btrim(coalesce(sc.subscription_type, '')))
                                        WHEN 'LIMITED' THEN 'STANDARD'
                                        WHEN 'FULL'    THEN 'PRO'
                                        ELSE upper(btrim(coalesce(sc.subscription_type, ''))) END;
    END IF;

    UPDATE service_client SET price_overridden = false WHERE price_overridden IS NULL;
END $$;
