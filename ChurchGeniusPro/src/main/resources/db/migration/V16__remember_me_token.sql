-- ═══════════════════════════════════════════════════════════════════════════
-- V16 — Per-device "Remember me" tokens (mobile Phase 1, item M2)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Before: signup.remember held ONE raw UUID per account — remembering a second
-- browser silently un-remembered the first, logout cleared every device, and the
-- value was stored in clear.
--
-- After: remember_me_token holds one row per browser/device with only the SHA-256
-- of the cookie value, a sliding 30-day expiry, rotation on use and per-row
-- revocation. signup.remember is NO LONGER WRITTEN but is kept (nullable) so a
-- cookie issued before this release still auto-logs in once and is migrated to a
-- row by the application; it can be dropped in a later release.
--
-- Data: purely additive — no existing row of any table is changed or removed.
-- Re-runnable (IF NOT EXISTS). No foreign key to signup: on a fresh database
-- Flyway runs before Hibernate creates signup.

CREATE TABLE IF NOT EXISTS remember_me_token (
    id              BIGSERIAL PRIMARY KEY,
    signup_id       INTEGER      NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    prev_token_hash VARCHAR(64),
    rotated_at      TIMESTAMP(6) WITH TIME ZONE,
    device_label    VARCHAR(200),
    platform        VARCHAR(16)  NOT NULL DEFAULT 'web',
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at    TIMESTAMP(6) WITH TIME ZONE,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    revoked_at      TIMESTAMP(6) WITH TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_remember_me_token_hash   ON remember_me_token (token_hash);
CREATE INDEX        IF NOT EXISTS idx_remember_me_token_signup  ON remember_me_token (signup_id);
CREATE INDEX        IF NOT EXISTS idx_remember_me_token_expires ON remember_me_token (expires_at);
