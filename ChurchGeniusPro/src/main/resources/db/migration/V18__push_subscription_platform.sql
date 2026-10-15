-- ═══════════════════════════════════════════════════════════════════════════
-- V18 — Mobile Phase 5: native push (FCM) rows share the push_subscription table
-- ═══════════════════════════════════════════════════════════════════════════
--
-- push_subscription.platform     'web' (existing VAPID browser rows) | 'android' | 'ios'
--                                Native rows store the FCM registration token in
--                                `endpoint` (unique, like a browser endpoint) and the
--                                placeholder '-' in p256dh/auth, which are NOT NULL and
--                                meaningless outside Web Push.
-- push_subscription.device_name  optional, e.g. "Pixel 8" / "iPhone 15" — shown in the
--                                per-device session list that Phase 6 adds.
--
-- Data: purely additive — two columns with defaults; every existing row becomes
-- platform='web', which is exactly what it is. Re-runnable (IF NOT EXISTS). On a fresh
-- database Flyway runs before Hibernate creates push_subscription, so the ALTER is
-- guarded; ddl-auto adds the columns there.

DO $$
BEGIN
    IF to_regclass('public.push_subscription') IS NOT NULL THEN
        ALTER TABLE push_subscription ADD COLUMN IF NOT EXISTS platform    VARCHAR(16)  NOT NULL DEFAULT 'web';
        ALTER TABLE push_subscription ADD COLUMN IF NOT EXISTS device_name VARCHAR(120);
        CREATE INDEX IF NOT EXISTS idx_push_sub_platform ON push_subscription (platform);
    END IF;
END $$;
