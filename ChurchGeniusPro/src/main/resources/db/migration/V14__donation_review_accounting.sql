-- ═══════════════════════════════════════════════════════════════════════════
-- V14 — Donation Review posts online donations to accounting (Stripe fee aware)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Before: a successful online donation was posted to `income` the moment it was
-- saved, with the card label ("Visa •••• 6676") created as a transaction_type and
-- Stripe's processing fee never recorded — so recorded income exceeded the bank
-- deposit and the Income/Expense Method dropdowns filled with card numbers.
--
-- After: a donation waits on the Donation Review page until an authorised user
-- posts it. Posting creates the Income (gross) and an Expense (Stripe fee) under
-- per-church Donation Settings, and records the links back on the donation row.
--
-- This migration introduces that schema itself (the project's Flyway-first rule
-- for this change — nothing here is left for Hibernate to add on an existing
-- database):
--   1. donation_settings      — one row per church: funds, methods, note templates,
--                                date rule, fee rate used only when Stripe's own fee
--                                is unavailable.
--   2. donation (new columns) — the actual Stripe fee/net/balance-transaction id and
--                                whether the stored fee is Stripe's or an estimate;
--                                the reviewed contributor category and linked person;
--                                the posted income/expense ids and who posted when.
--
-- Data: additive only. No existing row is changed; every new donation column is
-- NULL for existing rows, which the application reads as "not posted" / "fee not
-- yet known". No Income or Expense row is created, altered or removed.
--
-- Re-runnable (IF NOT EXISTS throughout). When Flyway runs before Hibernate has
-- created `donation` (a fresh database) the `donation` part no-ops and
-- config/DonationAccountingSchemaInitializer runs this same file again after
-- startup. donation_settings is created unconditionally.

-- ── 1. donation_settings ─────────────────────────────────────────────────────
CREATE SEQUENCE IF NOT EXISTS donation_settings_id_seq;

CREATE TABLE IF NOT EXISTS donation_settings (
    id                           BIGINT        NOT NULL DEFAULT nextval('donation_settings_id_seq') PRIMARY KEY,
    client_id                    VARCHAR(255)  NOT NULL,
    accounting_mode              VARCHAR(32)   NOT NULL DEFAULT 'GROSS_WITH_FEE_EXPENSE',
    income_sub_source_id         INTEGER,
    income_transaction_type_id   INTEGER,
    income_note_template         TEXT,
    expense_purpose_id           INTEGER,
    expense_main_source_id       INTEGER,
    expense_transaction_type_id  INTEGER,
    expense_note_template        TEXT,
    date_mode                    VARCHAR(16)   NOT NULL DEFAULT 'DONATION_DATE',
    fee_percent                  NUMERIC(6,3),
    fee_fixed                    NUMERIC(8,2),
    updated_by                   VARCHAR(320),
    updated_date                 TIMESTAMP
);

-- One settings row per church. Partial on nothing — client_id is NOT NULL.
CREATE UNIQUE INDEX IF NOT EXISTS ux_donation_settings_client ON donation_settings (client_id);

-- ── 2. donation — fee, review and posting columns ───────────────────────────
DO $$
BEGIN
    IF to_regclass('public.donation') IS NULL THEN
        RAISE NOTICE 'V14: donation not present - skipped (fresh database; applied after startup).';
        RETURN;
    END IF;

    -- Stripe's own figures for this charge (dollars, exact from cents). NULL = not known.
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS stripe_fee                    NUMERIC(12,2);
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS stripe_net                    NUMERIC(12,2);
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS stripe_balance_transaction_id VARCHAR(500);
    -- 'STRIPE' (actual) or 'ESTIMATED' (church-configured rate); NULL while unknown.
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS fee_source                    VARCHAR(16);

    -- Reviewed contributor: Member / Guest / Visitor (Family page values) and, when the
    -- match was unambiguous or chosen by the reviewer, the family_member it posts to.
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS contributor_category          VARCHAR(20);
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS matched_member_id             INTEGER;

    -- Accounting posting (separate from review_status, which stays a review flag).
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS posted_income_id              INTEGER;
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS posted_expense_id             INTEGER;
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS posted_at                     TIMESTAMP;
    ALTER TABLE donation ADD COLUMN IF NOT EXISTS posted_by                     VARCHAR(320);

    -- The review page lists one church's donations by review state; posting looks up
    -- the back-link from an income row.
    CREATE INDEX IF NOT EXISTS idx_donation_client_review ON donation (client_id, review_status);
    CREATE INDEX IF NOT EXISTS idx_donation_posted_income ON donation (posted_income_id)
        WHERE posted_income_id IS NOT NULL;

    -- An income row can be the posting of at most one donation. Partial: NULL = not posted.
    IF to_regclass('public.ux_donation_posted_income') IS NULL THEN
        BEGIN
            CREATE UNIQUE INDEX ux_donation_posted_income ON donation (posted_income_id)
                WHERE posted_income_id IS NOT NULL;
        EXCEPTION WHEN unique_violation THEN
            RAISE NOTICE 'V14: two donations already point at one income row - index not created yet (%).', SQLERRM;
        END;
    END IF;
END $$;
