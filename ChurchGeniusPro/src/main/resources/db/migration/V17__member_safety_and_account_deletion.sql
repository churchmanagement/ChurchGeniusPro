-- ═══════════════════════════════════════════════════════════════════════════
-- V17 — Mobile Phase 2: report & block in Members Chat (M5), member self-service
--       account deletion (M4)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- member_block    one member has blocked another (silent; inbox filter + refused sends)
-- content_report  a member's report of a Members Chat message, reviewed by staff in
--                 Follow-Ups → Reported Content (OPEN | REVIEWED | DISMISSED)
-- family_member.portal_account_deleted_at
--                 stamped when a member deletes their own portal LOGIN. The membership
--                 record and everything under it (giving, pledges, attendance,
--                 statements) is deliberately kept — it is the church's record.
--
-- Data: purely additive — two new tables and one nullable column; no existing row
-- is changed. Re-runnable (IF NOT EXISTS). On a fresh database Flyway runs before
-- Hibernate creates family_member, so the ALTER is guarded; ddl-auto adds the column.

CREATE TABLE IF NOT EXISTS member_block (
    id                BIGSERIAL PRIMARY KEY,
    app_client_id     VARCHAR(64) NOT NULL,
    blocker_member_id INTEGER     NOT NULL,
    blocked_member_id INTEGER     NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_member_block UNIQUE (app_client_id, blocker_member_id, blocked_member_id)
);
CREATE INDEX IF NOT EXISTS idx_member_block_blocker ON member_block (app_client_id, blocker_member_id);

CREATE TABLE IF NOT EXISTS content_report (
    id                 BIGSERIAL PRIMARY KEY,
    app_client_id      VARCHAR(64)  NOT NULL,
    content_type       VARCHAR(32)  NOT NULL,
    content_id         BIGINT       NOT NULL,
    content_excerpt    VARCHAR(2000),
    reporter_member_id INTEGER      NOT NULL,
    reporter_name      VARCHAR(160),
    reported_member_id INTEGER,
    reported_name      VARCHAR(160),
    reason             VARCHAR(32)  NOT NULL,
    details            VARCHAR(1000),
    status             VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    reviewed_at        TIMESTAMP(6) WITH TIME ZONE,
    reviewed_by        VARCHAR(150),
    action_note        VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_content_report_client_status ON content_report (app_client_id, status);
CREATE INDEX IF NOT EXISTS idx_content_report_content       ON content_report (content_type, content_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'family_member') THEN
        ALTER TABLE family_member ADD COLUMN IF NOT EXISTS portal_account_deleted_at TIMESTAMP(6) WITH TIME ZONE;
    END IF;
END $$;
