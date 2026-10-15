-- ═══════════════════════════════════════════════════════════════════════════
-- V15 — Membership Requests: status, audit trail, delete, renewal date
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Before: a membership request had no status. "Pending" meant delete_flag = false;
-- approving it copied the rows into family / family_member and set delete_flag =
-- true, so an approved request and a removed one were indistinguishable, nobody
-- recorded who approved what, and no renewal date existed anywhere.
--
-- After:
--   membership_family.status            PENDING_REVIEW | APPROVED | UPDATED
--     APPROVED  = reviewed and accepted, family record NOT touched (staff update it by hand)
--     UPDATED   = "Approve and Update": the submitted changes were applied
--   membership_family.status_changed_at / status_changed_by   who moved it last, when
--   membership_family.applied_at / applied_by / applied_family_id
--                                        the successful Approve-and-Update, and the family it went to
--   membership_family.deleted_at / deleted_by
--                                        soft delete by staff (delete_flag = true now means deleted)
--   family.membership_renewed_at         set only by a successful Approve and Update
--
-- Data: additive. The ONE data change is the backfill below: requests that were
-- already soft-deleted when this ran had, by construction, been applied by the old
-- approve hook, so they are stamped UPDATED (idempotent — only rows still at the
-- default are touched, and only ones with delete_flag = true). No family or
-- family_member row is changed.
--
-- Re-runnable (IF NOT EXISTS / guarded UPDATE). On a fresh database Flyway runs
-- before Hibernate creates these tables; the block no-ops and
-- config/MembershipRequestSchemaInitializer runs this same file after startup.

DO $$
BEGIN
    IF to_regclass('public.membership_family') IS NULL OR to_regclass('public.family') IS NULL THEN
        RAISE NOTICE 'V15: membership_family / family not present - skipped (fresh database; applied after startup).';
        RETURN;
    END IF;

    -- ── membership_family: status + audit ────────────────────────────────
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS status             VARCHAR(24)  NOT NULL DEFAULT 'PENDING_REVIEW';
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS status_changed_at  TIMESTAMP;
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS status_changed_by  VARCHAR(320);
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS applied_at         TIMESTAMP;
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS applied_by         VARCHAR(320);
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS applied_family_id  INTEGER;
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS deleted_at         TIMESTAMP;
    ALTER TABLE membership_family ADD COLUMN IF NOT EXISTS deleted_by         VARCHAR(320);

    -- The list is one church's live requests, by status.
    CREATE INDEX IF NOT EXISTS idx_membership_family_client_status
        ON membership_family (app_client_id, status) WHERE delete_flag = false;

    -- Backfill (once): historically, delete_flag = true meant "approved and applied".
    -- Guarded so a re-run, or a row deleted AFTER this migration, is never re-stamped.
    UPDATE membership_family
       SET status = 'UPDATED',
           status_changed_at = COALESCE(status_changed_at, created_date),
           status_changed_by = COALESCE(status_changed_by, 'migration-v15'),
           applied_at        = COALESCE(applied_at, created_date),
           applied_family_id = COALESCE(applied_family_id, existing_family_id)
     WHERE delete_flag = true
       AND status = 'PENDING_REVIEW'
       AND deleted_at IS NULL;

    -- ── family: renewal date ─────────────────────────────────────────────
    ALTER TABLE family ADD COLUMN IF NOT EXISTS membership_renewed_at TIMESTAMP;
END $$;
