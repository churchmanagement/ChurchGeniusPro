-- ═══════════════════════════════════════════════════════════════════════════
-- verify-P7-before.sql — snapshot BEFORE Flyway V3 runs (READ ONLY)
-- ═══════════════════════════════════════════════════════════════════════════
-- Run this against production before deploying the release that contains V3, and
-- keep the result. It records exactly what V3 must reproduce in church_event_image.
SELECT
    (SELECT count(*) FROM church_event)                                                   AS total_events,
    (SELECT count(*) FROM church_event
      WHERE image_data IS NOT NULL AND image_data <> '')                                  AS events_with_image,
    (SELECT count(*) FROM church_event
      WHERE image_data IS NOT NULL AND image_data <> '' )                                 AS images_to_copy,
    (SELECT COALESCE(sum(octet_length(image_data)), 0) FROM church_event
      WHERE image_data IS NOT NULL AND image_data <> '')                                  AS total_image_bytes,
    (SELECT COALESCE(max(octet_length(image_data)), 0) FROM church_event
      WHERE image_data IS NOT NULL AND image_data <> '')                                  AS largest_image_bytes,
    pg_size_pretty(pg_total_relation_size('church_event'))                                AS church_event_size;

-- Per-event image sizes (keep this list to reconcile row-for-row after V3):
--   SELECT id, app_client_id, octet_length(image_data) AS image_bytes
--     FROM church_event WHERE image_data IS NOT NULL AND image_data <> '' ORDER BY id;
