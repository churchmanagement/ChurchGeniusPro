-- ═══════════════════════════════════════════════════════════════════════════
-- V3 — Database audit P7: copy church_event flyer images to their own table
-- ═══════════════════════════════════════════════════════════════════════════
--
-- church_event.image_data was a TEXT column holding each flyer as an inline
-- data:image/…;base64,… URI (~2.7 MB per event). Hibernate eager-loads a basic
-- column, so every event list, calendar and reminder query pulled every flyer:
-- production's church_event was 51 MB for 19 rows with 31,987 sequential scans.
--
-- The image now lives in church_event_image (one row per event, fetched only when
-- a caller needs it), and church_event keeps a cheap image_present flag.
--
-- This migration is deliberately NON-DESTRUCTIVE: it creates the new table, adds the
-- flag, and COPIES every non-empty image across — but it does NOT drop the old
-- church_event.image_data column. The original data is retained as a safety net so it
-- can be verified in production (retrieval endpoint, reminder emails) before anything
-- is deleted. The column is dropped later, by V4, which re-verifies the copy first.
--
-- On a fresh database (Flyway runs before Hibernate, so church_event does not exist
-- yet) this no-ops and Hibernate builds both tables from the entities. Idempotent.
--
-- image_present is added with DEFAULT false so it can be added to a populated table;
-- the entity maps it NOT NULL with no default, which the existing default satisfies.

DO $$
DECLARE
    v_moved bigint;
BEGIN
    IF to_regclass('public.church_event') IS NULL THEN
        RAISE NOTICE 'P7: church_event not present - skipped (Hibernate builds church_event_image from the entity).';
        RETURN;
    END IF;

    -- 1. The new table (matches what Hibernate creates from ChurchEventImage).
    CREATE SEQUENCE IF NOT EXISTS church_event_image_id_seq START WITH 1 INCREMENT BY 1;
    CREATE TABLE IF NOT EXISTS church_event_image (
        id            integer      NOT NULL,
        event_id      integer      NOT NULL,
        app_client_id varchar(100),
        image_data    text,
        created_date  timestamp(6),
        CONSTRAINT church_event_image_pkey PRIMARY KEY (id),
        CONSTRAINT uq_church_event_image_event UNIQUE (event_id)
    );

    -- 2. The presence flag on the event row.
    ALTER TABLE church_event ADD COLUMN IF NOT EXISTS image_present boolean NOT NULL DEFAULT false;

    -- 3. Copy every non-empty flyer across, once (only if the old column is still here).
    --    The old column is kept — V4 drops it after the copy is verified.
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'church_event' AND column_name = 'image_data') THEN
        INSERT INTO church_event_image (id, event_id, app_client_id, image_data, created_date)
        SELECT nextval('church_event_image_id_seq'), e.id, e.app_client_id, e.image_data, now()
          FROM church_event e
         WHERE e.image_data IS NOT NULL AND e.image_data <> ''
           AND NOT EXISTS (SELECT 1 FROM church_event_image i WHERE i.event_id = e.id);
        GET DIAGNOSTICS v_moved = ROW_COUNT;

        -- 4. Set the flag from what the table now holds (the source column is untouched).
        UPDATE church_event e
           SET image_present = EXISTS (SELECT 1 FROM church_event_image i WHERE i.event_id = e.id);
        RAISE NOTICE 'P7: copied % flyer image(s) to church_event_image; church_event.image_data KEPT (the db/window drop step removes it after verification).', v_moved;
    ELSE
        RAISE NOTICE 'P7: church_event.image_data already removed - nothing to copy.';
    END IF;
END $$;
