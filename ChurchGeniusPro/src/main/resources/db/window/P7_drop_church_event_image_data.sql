-- ═══════════════════════════════════════════════════════════════════════════
-- P7 (manual, post-verification) — drop church_event.image_data AFTER verifying the copy
-- ═══════════════════════════════════════════════════════════════════════════
-- STAGED — run by hand, NOT a Flyway migration. It lives in db/window/ (which Flyway
-- ignores) precisely so it does NOT run on the same deploy as V3: the original data
-- must survive a production verification step (verify-P7-after.sql) before it is dropped.
--
-- V3 copied every flyer into church_event_image but deliberately kept the original
-- church_event.image_data column, so the copy could be verified in production before
-- anything was deleted. V4 removes the column — but only after PROVING the copy is
-- complete: for every event that still holds a non-empty image_data, there must be a
-- matching church_event_image row whose image_data is byte-for-byte identical. If even
-- one is missing or differs, V4 raises an exception and drops nothing, so a bad or
-- partial copy can never lose an image.
--
-- Run this only AFTER V3 has deployed and the production verification has passed:
--   * verify-P7-after.sql shows every image copied and the counts match, and
--   * the event detail view, the public /api/event-register/{token}/image endpoint,
--     and a reminder-email flyer all still render.
--
-- On a database where the column is already gone, this no-ops.
-- (When you next cut a release you may instead promote this to a Flyway V4 migration;
--  the DROP COLUMN is guarded so it stays a no-op once applied.)

DO $$
DECLARE
    v_uncopied bigint;
    v_mismatch bigint;
BEGIN
    IF to_regclass('public.church_event') IS NULL
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'public' AND table_name = 'church_event' AND column_name = 'image_data') THEN
        RAISE NOTICE 'P7 drop: church_event.image_data already absent - nothing to drop.';
        RETURN;
    END IF;

    -- Every event with a non-empty flyer must have been copied.
    SELECT count(*) INTO v_uncopied
      FROM church_event e
     WHERE e.image_data IS NOT NULL AND e.image_data <> ''
       AND NOT EXISTS (SELECT 1 FROM church_event_image i WHERE i.event_id = e.id);
    IF v_uncopied > 0 THEN
        RAISE EXCEPTION 'P7 drop: % event(s) still have an image_data that is NOT in church_event_image — run V3 (copy) and re-verify before dropping. Nothing dropped.', v_uncopied;
    END IF;

    -- …and the copy must be identical, not merely present.
    SELECT count(*) INTO v_mismatch
      FROM church_event e
      JOIN church_event_image i ON i.event_id = e.id
     WHERE e.image_data IS NOT NULL AND e.image_data <> ''
       AND i.image_data IS DISTINCT FROM e.image_data;
    IF v_mismatch > 0 THEN
        RAISE EXCEPTION 'P7 drop: % copied image(s) differ from the original — investigate before dropping. Nothing dropped.', v_mismatch;
    END IF;

    ALTER TABLE church_event DROP COLUMN image_data;
    RAISE NOTICE 'P7 drop: copy verified complete and identical; church_event.image_data dropped.';
END $$;
