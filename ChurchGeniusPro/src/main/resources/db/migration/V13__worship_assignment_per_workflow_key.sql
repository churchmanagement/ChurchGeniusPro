-- ═══════════════════════════════════════════════════════════════════════════
-- V13 — Manual and Automatic worship assignments own separate rows
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Before: worship_assignment was keyed by (client_id, group_id, assignment_date)
-- in the application's upsert, so a Manual save and an Automatic save for the
-- same group+date hit ONE row and overwrote each other's members and type.
--
-- After: the key includes assignment_type ('manual' | 'auto'), enforced by a
-- partial unique index over ACTIVE rows only (regenerating an automatic schedule
-- soft-deletes the old dates and re-creates them, so soft-deleted rows must not
-- block). A `skipped` flag lets the Manual workflow hide a date while KEEPING
-- its members. worship_song gains schedule_type so each workflow has its own
-- dated song lists; every pre-existing song keeps today's meaning ('auto').
--
-- Data: additive only. No row is deleted or re-keyed; NULL assignment_type
-- (none in production as of 2026-10-08) becomes 'auto', which is how the
-- Automatic tab has always read it.
--
-- Re-runnable. When Flyway runs before Hibernate has created the tables (fresh
-- database) this no-ops and config/WorshipSchemaInitializer runs the same file
-- again after startup. If active duplicates exist (none in production), the index
-- is NOT created and a notice is logged — resolve them, then restart.

DO $$
BEGIN
    IF to_regclass('public.worship_assignment') IS NULL THEN
        RAISE NOTICE 'V13: worship_assignment not present - skipped (applied after startup).';
    ELSE
        -- assignment_type becomes part of the key: never NULL, default 'manual'
        -- (the entity default the application has always used).
        UPDATE worship_assignment SET assignment_type = 'auto' WHERE assignment_type IS NULL;
        ALTER TABLE worship_assignment ALTER COLUMN assignment_type SET DEFAULT 'manual';
        ALTER TABLE worship_assignment ALTER COLUMN assignment_type SET NOT NULL;

        -- Manual workflow: "skipped" keeps the members, only hides the date.
        ALTER TABLE worship_assignment ADD COLUMN IF NOT EXISTS skipped BOOLEAN NOT NULL DEFAULT false;

        -- The old whole-table key (present only on databases Hibernate created from
        -- the former @UniqueConstraint; absent in production) would forbid a manual
        -- and an automatic row for the same date.
        ALTER TABLE worship_assignment DROP CONSTRAINT IF EXISTS uq_worship_assignment_client_group_date;

        IF to_regclass('public.ux_worship_assignment_active') IS NULL THEN
            BEGIN
                CREATE UNIQUE INDEX ux_worship_assignment_active
                    ON worship_assignment (client_id, group_id, assignment_date, assignment_type)
                 WHERE delete_flag = false;
            EXCEPTION WHEN unique_violation THEN
                RAISE NOTICE 'V13: active duplicate worship_assignment rows exist - index not created yet (%).', SQLERRM;
            END;
        END IF;
    END IF;

    IF to_regclass('public.worship_song') IS NULL THEN
        RAISE NOTICE 'V13: worship_song not present - skipped (applied after startup).';
    ELSE
        -- Every existing dated song list belongs to the Automatic schedule.
        ALTER TABLE worship_song ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(20) NOT NULL DEFAULT 'auto';
        CREATE INDEX IF NOT EXISTS idx_worship_song_schedule
            ON worship_song (client_id, group_id, schedule_type, service_date);
    END IF;
END $$;
