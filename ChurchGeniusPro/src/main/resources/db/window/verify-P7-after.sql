-- ═══════════════════════════════════════════════════════════════════════════
-- verify-P7-after.sql — verify AFTER Flyway V3 (copy) and BEFORE V4 (drop). READ ONLY.
-- ═══════════════════════════════════════════════════════════════════════════
-- V3 copies the flyers and KEEPS church_event.image_data; V4 drops the column only
-- after this passes. Every column below must read as stated before you allow V4 to run.
SELECT
    -- every event that has an image must have been copied, byte-for-byte
    (SELECT count(*) FROM church_event e
       WHERE e.image_data IS NOT NULL AND e.image_data <> ''
         AND NOT EXISTS (SELECT 1 FROM church_event_image i WHERE i.event_id = e.id))     AS uncopied_images,          -- MUST be 0
    (SELECT count(*) FROM church_event e
       JOIN church_event_image i ON i.event_id = e.id
      WHERE e.image_data IS NOT NULL AND e.image_data <> ''
        AND i.image_data IS DISTINCT FROM e.image_data)                                   AS mismatched_copies,        -- MUST be 0
    -- church_event_image should hold exactly one row per event that has an image
    (SELECT count(*) FROM church_event WHERE image_data IS NOT NULL AND image_data <> '') AS events_with_image,
    (SELECT count(*) FROM church_event_image)                                             AS image_rows,               -- == events_with_image
    -- image_present must match actual availability
    (SELECT count(*) FROM church_event e
       WHERE e.image_present <> EXISTS (SELECT 1 FROM church_event_image i WHERE i.event_id = e.id)) AS image_present_wrong, -- MUST be 0
    -- the source column still exists (V4 has not run yet)
    (SELECT count(*) FROM information_schema.columns
      WHERE table_schema = current_schema() AND table_name = 'church_event' AND column_name = 'image_data') AS image_data_still_present; -- 1 until V4
-- Application checks (not SQL) — confirm before V4:
--   * open an event with a flyer in the admin detail view — the image renders
--   * GET /api/event-register/{token}/image for an event with a flyer returns the image
--   * trigger/preview an event reminder email — the flyer renders (CID attachment)
