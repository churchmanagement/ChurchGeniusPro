-- ═══════════════════════════════════════════════════════════════════════════
-- W3 — Database audit H3: add the missing foreign keys
-- ═══════════════════════════════════════════════════════════════════════════
-- STAGED — run by hand in a maintenance window, AFTER W1_orphan_cleanup.sql.
-- NOT part of db/migration and NOT applied automatically on deploy.
--
-- The schema declares many logical relationships (every *_id column with a parent) but only 13
-- real foreign keys. This adds 123 of them through guarded blocks (111 ON DELETE RESTRICT, 12
-- CASCADE); two more relationships are deliberately NOT enforced — song_audit_log.song_id (an
-- append-only historical reference) and song_book_access.member_id (a type mismatch) — both
-- explained at the tail. Referential integrity that was enforced only in application code — and
-- so not at all for the 50 native/JPQL DELETEs and 26 hard-delete services — becomes a database
-- guarantee.
--
-- ON DELETE: RESTRICT by default (the application soft-deletes almost everything, so a
-- hard delete of a referenced row is almost always a bug — RESTRICT surfaces it loudly
-- and never loses data). CASCADE only for the 12 pure composition/detail rows whose
-- existence is meaningless without their parent (listed in their own block below).
--
-- BEFORE YOU RUN THIS, in the window:
--   1. W1_orphan_cleanup.sql must have run (a FK refuses to be created while an orphan
--      exists — that is the point; each such case is reported here, not aborted).
--   2. Reconcile the hard-delete code paths: any service that hard-deletes a row now
--      covered by a RESTRICT FK will get a constraint error unless its children are gone
--      first. Decide per case: delete children first, switch to soft-delete, or (for a
--      true composition) move that FK to the CASCADE block.
-- Each ALTER is idempotent (skipped if the constraint already exists) and self-contained
-- (one failure reports and the rest continue). Re-running is safe.

DO $$
DECLARE
    v_added    int := 0;
    v_orphaned int := 0;
    v_skipped  int := 0;
BEGIN
    -- ── RESTRICT (default): the parent may not be hard-deleted while children exist ──
    -- access_audit.temporary_access_id -> temporary_access.id  (ON DELETE RESTRICT)
    IF to_regclass('public.access_audit') IS NOT NULL AND to_regclass('public.temporary_access') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_access_audit_temporary_access_id') THEN
        BEGIN
            ALTER TABLE access_audit ADD CONSTRAINT fk_access_audit_temporary_access_id
                FOREIGN KEY (temporary_access_id) REFERENCES temporary_access (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_access_audit_temporary_access_id', 'access_audit.temporary_access_id', 'temporary_access';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_access_audit_temporary_access_id', 'access_audit.temporary_access_id', 'temporary_access', SQLERRM;
        END;
    END IF;
    -- attendance_record.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.attendance_record') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_attendance_record_family_member_id') THEN
        BEGIN
            ALTER TABLE attendance_record ADD CONSTRAINT fk_attendance_record_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_attendance_record_family_member_id', 'attendance_record.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_attendance_record_family_member_id', 'attendance_record.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- attendance_record.visitor_id -> attendance_visitor.id  (ON DELETE RESTRICT)
    IF to_regclass('public.attendance_record') IS NOT NULL AND to_regclass('public.attendance_visitor') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_attendance_record_visitor_id') THEN
        BEGIN
            ALTER TABLE attendance_record ADD CONSTRAINT fk_attendance_record_visitor_id
                FOREIGN KEY (visitor_id) REFERENCES attendance_visitor (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_attendance_record_visitor_id', 'attendance_record.visitor_id', 'attendance_visitor';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_attendance_record_visitor_id', 'attendance_record.visitor_id', 'attendance_visitor', SQLERRM;
        END;
    END IF;
    -- auto_reminder.reminder_type_id -> auto_reminder_types.id  (ON DELETE RESTRICT)
    IF to_regclass('public.auto_reminder') IS NOT NULL AND to_regclass('public.auto_reminder_types') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auto_reminder_reminder_type_id') THEN
        BEGIN
            ALTER TABLE auto_reminder ADD CONSTRAINT fk_auto_reminder_reminder_type_id
                FOREIGN KEY (reminder_type_id) REFERENCES auto_reminder_types (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_auto_reminder_reminder_type_id', 'auto_reminder.reminder_type_id', 'auto_reminder_types';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_auto_reminder_reminder_type_id', 'auto_reminder.reminder_type_id', 'auto_reminder_types', SQLERRM;
        END;
    END IF;
    -- bank_sync_trusted_device.app_user_id -> app_user.id  (ON DELETE RESTRICT)
    IF to_regclass('public.bank_sync_trusted_device') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_bank_sync_trusted_device_app_user_id') THEN
        BEGIN
            ALTER TABLE bank_sync_trusted_device ADD CONSTRAINT fk_bank_sync_trusted_device_app_user_id
                FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_bank_sync_trusted_device_app_user_id', 'bank_sync_trusted_device.app_user_id', 'app_user';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_bank_sync_trusted_device_app_user_id', 'bank_sync_trusted_device.app_user_id', 'app_user', SQLERRM;
        END;
    END IF;
    -- bank_sync_verification.app_user_id -> app_user.id  (ON DELETE RESTRICT)
    IF to_regclass('public.bank_sync_verification') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_bank_sync_verification_app_user_id') THEN
        BEGIN
            ALTER TABLE bank_sync_verification ADD CONSTRAINT fk_bank_sync_verification_app_user_id
                FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_bank_sync_verification_app_user_id', 'bank_sync_verification.app_user_id', 'app_user';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_bank_sync_verification_app_user_id', 'bank_sync_verification.app_user_id', 'app_user', SQLERRM;
        END;
    END IF;
    -- connect_submission.assigned_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.connect_submission') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_connect_submission_assigned_member_id') THEN
        BEGIN
            ALTER TABLE connect_submission ADD CONSTRAINT fk_connect_submission_assigned_member_id
                FOREIGN KEY (assigned_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_connect_submission_assigned_member_id', 'connect_submission.assigned_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_connect_submission_assigned_member_id', 'connect_submission.assigned_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- connect_submission.follow_up_id -> follow_up.id  (ON DELETE RESTRICT)
    IF to_regclass('public.connect_submission') IS NOT NULL AND to_regclass('public.follow_up') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_connect_submission_follow_up_id') THEN
        BEGIN
            ALTER TABLE connect_submission ADD CONSTRAINT fk_connect_submission_follow_up_id
                FOREIGN KEY (follow_up_id) REFERENCES follow_up (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_connect_submission_follow_up_id', 'connect_submission.follow_up_id', 'follow_up';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_connect_submission_follow_up_id', 'connect_submission.follow_up_id', 'follow_up', SQLERRM;
        END;
    END IF;
    -- connect_submission.member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.connect_submission') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_connect_submission_member_id') THEN
        BEGIN
            ALTER TABLE connect_submission ADD CONSTRAINT fk_connect_submission_member_id
                FOREIGN KEY (member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_connect_submission_member_id', 'connect_submission.member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_connect_submission_member_id', 'connect_submission.member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- demo_reminder_log.role_access_id -> demo_role_access.id  (ON DELETE RESTRICT)
    IF to_regclass('public.demo_reminder_log') IS NOT NULL AND to_regclass('public.demo_role_access') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_demo_reminder_log_role_access_id') THEN
        BEGIN
            ALTER TABLE demo_reminder_log ADD CONSTRAINT fk_demo_reminder_log_role_access_id
                FOREIGN KEY (role_access_id) REFERENCES demo_role_access (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_demo_reminder_log_role_access_id', 'demo_reminder_log.role_access_id', 'demo_role_access';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_demo_reminder_log_role_access_id', 'demo_reminder_log.role_access_id', 'demo_role_access', SQLERRM;
        END;
    END IF;
    -- demo_role_access.signup_id -> signup.id  (ON DELETE RESTRICT)
    IF to_regclass('public.demo_role_access') IS NOT NULL AND to_regclass('public.signup') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_demo_role_access_signup_id') THEN
        BEGIN
            ALTER TABLE demo_role_access ADD CONSTRAINT fk_demo_role_access_signup_id
                FOREIGN KEY (signup_id) REFERENCES signup (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_demo_role_access_signup_id', 'demo_role_access.signup_id', 'signup';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_demo_role_access_signup_id', 'demo_role_access.signup_id', 'signup', SQLERRM;
        END;
    END IF;
    -- event_registration.event_id -> church_event.id  (ON DELETE RESTRICT)
    IF to_regclass('public.event_registration') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_registration_event_id') THEN
        BEGIN
            ALTER TABLE event_registration ADD CONSTRAINT fk_event_registration_event_id
                FOREIGN KEY (event_id) REFERENCES church_event (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_registration_event_id', 'event_registration.event_id', 'church_event';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_registration_event_id', 'event_registration.event_id', 'church_event', SQLERRM;
        END;
    END IF;
    -- event_registration_reminder_contact.event_id -> church_event.id  (ON DELETE RESTRICT)
    IF to_regclass('public.event_registration_reminder_contact') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_registration_reminder_contact_event_id') THEN
        BEGIN
            ALTER TABLE event_registration_reminder_contact ADD CONSTRAINT fk_event_registration_reminder_contact_event_id
                FOREIGN KEY (event_id) REFERENCES church_event (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_registration_reminder_contact_event_id', 'event_registration_reminder_contact.event_id', 'church_event';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_registration_reminder_contact_event_id', 'event_registration_reminder_contact.event_id', 'church_event', SQLERRM;
        END;
    END IF;
    -- event_registration_reminder_log.event_id -> church_event.id  (ON DELETE RESTRICT)
    IF to_regclass('public.event_registration_reminder_log') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_registration_reminder_log_event_id') THEN
        BEGIN
            ALTER TABLE event_registration_reminder_log ADD CONSTRAINT fk_event_registration_reminder_log_event_id
                FOREIGN KEY (event_id) REFERENCES church_event (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_registration_reminder_log_event_id', 'event_registration_reminder_log.event_id', 'church_event';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_registration_reminder_log_event_id', 'event_registration_reminder_log.event_id', 'church_event', SQLERRM;
        END;
    END IF;
    -- event_reminder.event_id -> church_event.id  (ON DELETE RESTRICT)
    IF to_regclass('public.event_reminder') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_reminder_event_id') THEN
        BEGIN
            ALTER TABLE event_reminder ADD CONSTRAINT fk_event_reminder_event_id
                FOREIGN KEY (event_id) REFERENCES church_event (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_reminder_event_id', 'event_reminder.event_id', 'church_event';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_reminder_event_id', 'event_reminder.event_id', 'church_event', SQLERRM;
        END;
    END IF;
    -- event_volunteer.event_id -> church_event.id  (ON DELETE RESTRICT)
    IF to_regclass('public.event_volunteer') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_volunteer_event_id') THEN
        BEGIN
            ALTER TABLE event_volunteer ADD CONSTRAINT fk_event_volunteer_event_id
                FOREIGN KEY (event_id) REFERENCES church_event (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_volunteer_event_id', 'event_volunteer.event_id', 'church_event';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_volunteer_event_id', 'event_volunteer.event_id', 'church_event', SQLERRM;
        END;
    END IF;
    -- event_volunteer.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.event_volunteer') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_volunteer_family_member_id') THEN
        BEGIN
            ALTER TABLE event_volunteer ADD CONSTRAINT fk_event_volunteer_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_volunteer_family_member_id', 'event_volunteer.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_volunteer_family_member_id', 'event_volunteer.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- guess_it_game.group_id -> guess_it_group.id  (ON DELETE RESTRICT)
    IF to_regclass('public.guess_it_game') IS NOT NULL AND to_regclass('public.guess_it_group') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_guess_it_game_group_id') THEN
        BEGIN
            ALTER TABLE guess_it_game ADD CONSTRAINT fk_guess_it_game_group_id
                FOREIGN KEY (group_id) REFERENCES guess_it_group (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_guess_it_game_group_id', 'guess_it_game.group_id', 'guess_it_group';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_guess_it_game_group_id', 'guess_it_game.group_id', 'guess_it_group', SQLERRM;
        END;
    END IF;
    -- guess_it_group_participant.member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.guess_it_group_participant') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_guess_it_group_participant_member_id') THEN
        BEGIN
            ALTER TABLE guess_it_group_participant ADD CONSTRAINT fk_guess_it_group_participant_member_id
                FOREIGN KEY (member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_guess_it_group_participant_member_id', 'guess_it_group_participant.member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_guess_it_group_participant_member_id', 'guess_it_group_participant.member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- guess_it_participant.member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.guess_it_participant') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_guess_it_participant_member_id') THEN
        BEGIN
            ALTER TABLE guess_it_participant ADD CONSTRAINT fk_guess_it_participant_member_id
                FOREIGN KEY (member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_guess_it_participant_member_id', 'guess_it_participant.member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_guess_it_participant_member_id', 'guess_it_participant.member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- import_audit.batch_id -> import_batch.id  (ON DELETE RESTRICT)
    IF to_regclass('public.import_audit') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_import_audit_batch_id') THEN
        BEGIN
            ALTER TABLE import_audit ADD CONSTRAINT fk_import_audit_batch_id
                FOREIGN KEY (batch_id) REFERENCES import_batch (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_import_audit_batch_id', 'import_audit.batch_id', 'import_batch';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_import_audit_batch_id', 'import_audit.batch_id', 'import_batch', SQLERRM;
        END;
    END IF;
    -- import_audit.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.import_audit') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_import_audit_run_id') THEN
        BEGIN
            ALTER TABLE import_audit ADD CONSTRAINT fk_import_audit_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_import_audit_run_id', 'import_audit.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_import_audit_run_id', 'import_audit.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- import_batch.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.import_batch') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_import_batch_run_id') THEN
        BEGIN
            ALTER TABLE import_batch ADD CONSTRAINT fk_import_batch_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_import_batch_run_id', 'import_batch.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_import_batch_run_id', 'import_batch.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- import_source_profile.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.import_source_profile') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_import_source_profile_run_id') THEN
        BEGIN
            ALTER TABLE import_source_profile ADD CONSTRAINT fk_import_source_profile_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_import_source_profile_run_id', 'import_source_profile.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_import_source_profile_run_id', 'import_source_profile.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- km_authorized_pickup.child_id -> km_child.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_authorized_pickup') IS NOT NULL AND to_regclass('public.km_child') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_authorized_pickup_child_id') THEN
        BEGIN
            ALTER TABLE km_authorized_pickup ADD CONSTRAINT fk_km_authorized_pickup_child_id
                FOREIGN KEY (child_id) REFERENCES km_child (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_authorized_pickup_child_id', 'km_authorized_pickup.child_id', 'km_child';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_authorized_pickup_child_id', 'km_authorized_pickup.child_id', 'km_child', SQLERRM;
        END;
    END IF;
    -- km_checkin.child_id -> km_child.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_checkin') IS NOT NULL AND to_regclass('public.km_child') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_checkin_child_id') THEN
        BEGIN
            ALTER TABLE km_checkin ADD CONSTRAINT fk_km_checkin_child_id
                FOREIGN KEY (child_id) REFERENCES km_child (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_checkin_child_id', 'km_checkin.child_id', 'km_child';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_checkin_child_id', 'km_checkin.child_id', 'km_child', SQLERRM;
        END;
    END IF;
    -- km_checkin.classroom_id -> km_classroom.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_checkin') IS NOT NULL AND to_regclass('public.km_classroom') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_checkin_classroom_id') THEN
        BEGIN
            ALTER TABLE km_checkin ADD CONSTRAINT fk_km_checkin_classroom_id
                FOREIGN KEY (classroom_id) REFERENCES km_classroom (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_checkin_classroom_id', 'km_checkin.classroom_id', 'km_classroom';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_checkin_classroom_id', 'km_checkin.classroom_id', 'km_classroom', SQLERRM;
        END;
    END IF;
    -- km_checkin.guardian_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_checkin') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_checkin_guardian_member_id') THEN
        BEGIN
            ALTER TABLE km_checkin ADD CONSTRAINT fk_km_checkin_guardian_member_id
                FOREIGN KEY (guardian_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_checkin_guardian_member_id', 'km_checkin.guardian_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_checkin_guardian_member_id', 'km_checkin.guardian_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- km_child.classroom_id -> km_classroom.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_child') IS NOT NULL AND to_regclass('public.km_classroom') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_child_classroom_id') THEN
        BEGIN
            ALTER TABLE km_child ADD CONSTRAINT fk_km_child_classroom_id
                FOREIGN KEY (classroom_id) REFERENCES km_classroom (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_child_classroom_id', 'km_child.classroom_id', 'km_classroom';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_child_classroom_id', 'km_child.classroom_id', 'km_classroom', SQLERRM;
        END;
    END IF;
    -- km_child.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_child') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_child_family_member_id') THEN
        BEGIN
            ALTER TABLE km_child ADD CONSTRAINT fk_km_child_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_child_family_member_id', 'km_child.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_child_family_member_id', 'km_child.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- km_volunteer.classroom_id -> km_classroom.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_volunteer') IS NOT NULL AND to_regclass('public.km_classroom') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_volunteer_classroom_id') THEN
        BEGIN
            ALTER TABLE km_volunteer ADD CONSTRAINT fk_km_volunteer_classroom_id
                FOREIGN KEY (classroom_id) REFERENCES km_classroom (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_volunteer_classroom_id', 'km_volunteer.classroom_id', 'km_classroom';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_volunteer_classroom_id', 'km_volunteer.classroom_id', 'km_classroom', SQLERRM;
        END;
    END IF;
    -- km_volunteer.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_volunteer') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_volunteer_family_member_id') THEN
        BEGIN
            ALTER TABLE km_volunteer ADD CONSTRAINT fk_km_volunteer_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_volunteer_family_member_id', 'km_volunteer.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_volunteer_family_member_id', 'km_volunteer.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- km_volunteer_role_assignment.volunteer_id -> km_volunteer.id  (ON DELETE RESTRICT)
    IF to_regclass('public.km_volunteer_role_assignment') IS NOT NULL AND to_regclass('public.km_volunteer') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_km_volunteer_role_assignment_volunteer_id') THEN
        BEGIN
            ALTER TABLE km_volunteer_role_assignment ADD CONSTRAINT fk_km_volunteer_role_assignment_volunteer_id
                FOREIGN KEY (volunteer_id) REFERENCES km_volunteer (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_km_volunteer_role_assignment_volunteer_id', 'km_volunteer_role_assignment.volunteer_id', 'km_volunteer';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_km_volunteer_role_assignment_volunteer_id', 'km_volunteer_role_assignment.volunteer_id', 'km_volunteer', SQLERRM;
        END;
    END IF;
    -- mapping_rule.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.mapping_rule') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_mapping_rule_run_id') THEN
        BEGIN
            ALTER TABLE mapping_rule ADD CONSTRAINT fk_mapping_rule_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_mapping_rule_run_id', 'mapping_rule.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_mapping_rule_run_id', 'mapping_rule.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- meeting.location_family_id -> family.id  (ON DELETE RESTRICT)
    IF to_regclass('public.meeting') IS NOT NULL AND to_regclass('public.family') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_meeting_location_family_id') THEN
        BEGIN
            ALTER TABLE meeting ADD CONSTRAINT fk_meeting_location_family_id
                FOREIGN KEY (location_family_id) REFERENCES family (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_meeting_location_family_id', 'meeting.location_family_id', 'family';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_meeting_location_family_id', 'meeting.location_family_id', 'family', SQLERRM;
        END;
    END IF;
    -- meeting.location_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.meeting') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_meeting_location_member_id') THEN
        BEGIN
            ALTER TABLE meeting ADD CONSTRAINT fk_meeting_location_member_id
                FOREIGN KEY (location_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_meeting_location_member_id', 'meeting.location_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_meeting_location_member_id', 'meeting.location_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- meeting_reminder.meeting_id -> meeting.id  (ON DELETE RESTRICT)
    IF to_regclass('public.meeting_reminder') IS NOT NULL AND to_regclass('public.meeting') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_meeting_reminder_meeting_id') THEN
        BEGIN
            ALTER TABLE meeting_reminder ADD CONSTRAINT fk_meeting_reminder_meeting_id
                FOREIGN KEY (meeting_id) REFERENCES meeting (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_meeting_reminder_meeting_id', 'meeting_reminder.meeting_id', 'meeting';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_meeting_reminder_meeting_id', 'meeting_reminder.meeting_id', 'meeting', SQLERRM;
        END;
    END IF;
    -- meeting_skip_date.meeting_id -> meeting.id  (ON DELETE RESTRICT)
    IF to_regclass('public.meeting_skip_date') IS NOT NULL AND to_regclass('public.meeting') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_meeting_skip_date_meeting_id') THEN
        BEGIN
            ALTER TABLE meeting_skip_date ADD CONSTRAINT fk_meeting_skip_date_meeting_id
                FOREIGN KEY (meeting_id) REFERENCES meeting (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_meeting_skip_date_meeting_id', 'meeting_skip_date.meeting_id', 'meeting';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_meeting_skip_date_meeting_id', 'meeting_skip_date.meeting_id', 'meeting', SQLERRM;
        END;
    END IF;
    -- member_attendance_code.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.member_attendance_code') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_member_attendance_code_family_member_id') THEN
        BEGIN
            ALTER TABLE member_attendance_code ADD CONSTRAINT fk_member_attendance_code_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_member_attendance_code_family_member_id', 'member_attendance_code.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_member_attendance_code_family_member_id', 'member_attendance_code.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- member_message.parent_id -> member_message.id  (ON DELETE RESTRICT)
    IF to_regclass('public.member_message') IS NOT NULL AND to_regclass('public.member_message') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_member_message_parent_id') THEN
        BEGIN
            ALTER TABLE member_message ADD CONSTRAINT fk_member_message_parent_id
                FOREIGN KEY (parent_id) REFERENCES member_message (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_member_message_parent_id', 'member_message.parent_id', 'member_message';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_member_message_parent_id', 'member_message.parent_id', 'member_message', SQLERRM;
        END;
    END IF;
    -- member_message.recipient_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.member_message') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_member_message_recipient_member_id') THEN
        BEGIN
            ALTER TABLE member_message ADD CONSTRAINT fk_member_message_recipient_member_id
                FOREIGN KEY (recipient_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_member_message_recipient_member_id', 'member_message.recipient_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_member_message_recipient_member_id', 'member_message.recipient_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- member_message.sender_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.member_message') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_member_message_sender_member_id') THEN
        BEGIN
            ALTER TABLE member_message ADD CONSTRAINT fk_member_message_sender_member_id
                FOREIGN KEY (sender_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_member_message_sender_member_id', 'member_message.sender_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_member_message_sender_member_id', 'member_message.sender_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- member_preference.member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.member_preference') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_member_preference_member_id') THEN
        BEGIN
            ALTER TABLE member_preference ADD CONSTRAINT fk_member_preference_member_id
                FOREIGN KEY (member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_member_preference_member_id', 'member_preference.member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_member_preference_member_id', 'member_preference.member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- membership_family.existing_family_id -> family.id  (ON DELETE RESTRICT)
    IF to_regclass('public.membership_family') IS NOT NULL AND to_regclass('public.family') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_membership_family_existing_family_id') THEN
        BEGIN
            ALTER TABLE membership_family ADD CONSTRAINT fk_membership_family_existing_family_id
                FOREIGN KEY (existing_family_id) REFERENCES family (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_membership_family_existing_family_id', 'membership_family.existing_family_id', 'family';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_membership_family_existing_family_id', 'membership_family.existing_family_id', 'family', SQLERRM;
        END;
    END IF;
    -- membership_family.signup_id -> signup.id  (ON DELETE RESTRICT)
    IF to_regclass('public.membership_family') IS NOT NULL AND to_regclass('public.signup') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_membership_family_signup_id') THEN
        BEGIN
            ALTER TABLE membership_family ADD CONSTRAINT fk_membership_family_signup_id
                FOREIGN KEY (signup_id) REFERENCES signup (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_membership_family_signup_id', 'membership_family.signup_id', 'signup';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_membership_family_signup_id', 'membership_family.signup_id', 'signup', SQLERRM;
        END;
    END IF;
    -- note.created_by_id -> app_user.id  (ON DELETE RESTRICT)
    IF to_regclass('public.note') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_note_created_by_id') THEN
        BEGIN
            ALTER TABLE note ADD CONSTRAINT fk_note_created_by_id
                FOREIGN KEY (created_by_id) REFERENCES app_user (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_note_created_by_id', 'note.created_by_id', 'app_user';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_note_created_by_id', 'note.created_by_id', 'app_user', SQLERRM;
        END;
    END IF;
    -- ntag_login_challenge.credential_id -> ntag_credential.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ntag_login_challenge') IS NOT NULL AND to_regclass('public.ntag_credential') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ntag_login_challenge_credential_id') THEN
        BEGIN
            ALTER TABLE ntag_login_challenge ADD CONSTRAINT fk_ntag_login_challenge_credential_id
                FOREIGN KEY (credential_id) REFERENCES ntag_credential (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ntag_login_challenge_credential_id', 'ntag_login_challenge.credential_id', 'ntag_credential';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ntag_login_challenge_credential_id', 'ntag_login_challenge.credential_id', 'ntag_credential', SQLERRM;
        END;
    END IF;
    -- ntag_login_history.credential_id -> ntag_credential.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ntag_login_history') IS NOT NULL AND to_regclass('public.ntag_credential') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ntag_login_history_credential_id') THEN
        BEGIN
            ALTER TABLE ntag_login_history ADD CONSTRAINT fk_ntag_login_history_credential_id
                FOREIGN KEY (credential_id) REFERENCES ntag_credential (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ntag_login_history_credential_id', 'ntag_login_history.credential_id', 'ntag_credential';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ntag_login_history_credential_id', 'ntag_login_history.credential_id', 'ntag_credential', SQLERRM;
        END;
    END IF;
    -- payroll_employee_deduction.definition_id -> payroll_deduction_definition.id  (ON DELETE RESTRICT)
    IF to_regclass('public.payroll_employee_deduction') IS NOT NULL AND to_regclass('public.payroll_deduction_definition') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_employee_deduction_definition_id') THEN
        BEGIN
            ALTER TABLE payroll_employee_deduction ADD CONSTRAINT fk_payroll_employee_deduction_definition_id
                FOREIGN KEY (definition_id) REFERENCES payroll_deduction_definition (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_employee_deduction_definition_id', 'payroll_employee_deduction.definition_id', 'payroll_deduction_definition';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_employee_deduction_definition_id', 'payroll_employee_deduction.definition_id', 'payroll_deduction_definition', SQLERRM;
        END;
    END IF;
    -- payroll_employee_deduction.employee_id -> payroll_employee.id  (ON DELETE RESTRICT)
    IF to_regclass('public.payroll_employee_deduction') IS NOT NULL AND to_regclass('public.payroll_employee') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_employee_deduction_employee_id') THEN
        BEGIN
            ALTER TABLE payroll_employee_deduction ADD CONSTRAINT fk_payroll_employee_deduction_employee_id
                FOREIGN KEY (employee_id) REFERENCES payroll_employee (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_employee_deduction_employee_id', 'payroll_employee_deduction.employee_id', 'payroll_employee';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_employee_deduction_employee_id', 'payroll_employee_deduction.employee_id', 'payroll_employee', SQLERRM;
        END;
    END IF;
    -- payroll_paystub.employee_id -> payroll_employee.id  (ON DELETE RESTRICT)
    IF to_regclass('public.payroll_paystub') IS NOT NULL AND to_regclass('public.payroll_employee') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_paystub_employee_id') THEN
        BEGIN
            ALTER TABLE payroll_paystub ADD CONSTRAINT fk_payroll_paystub_employee_id
                FOREIGN KEY (employee_id) REFERENCES payroll_employee (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_paystub_employee_id', 'payroll_paystub.employee_id', 'payroll_employee';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_paystub_employee_id', 'payroll_paystub.employee_id', 'payroll_employee', SQLERRM;
        END;
    END IF;
    -- payroll_paystub.run_id -> payroll_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.payroll_paystub') IS NOT NULL AND to_regclass('public.payroll_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_paystub_run_id') THEN
        BEGIN
            ALTER TABLE payroll_paystub ADD CONSTRAINT fk_payroll_paystub_run_id
                FOREIGN KEY (run_id) REFERENCES payroll_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_paystub_run_id', 'payroll_paystub.run_id', 'payroll_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_paystub_run_id', 'payroll_paystub.run_id', 'payroll_run', SQLERRM;
        END;
    END IF;
    -- payroll_run.adjusts_run_id -> payroll_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.payroll_run') IS NOT NULL AND to_regclass('public.payroll_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_run_adjusts_run_id') THEN
        BEGIN
            ALTER TABLE payroll_run ADD CONSTRAINT fk_payroll_run_adjusts_run_id
                FOREIGN KEY (adjusts_run_id) REFERENCES payroll_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_run_adjusts_run_id', 'payroll_run.adjusts_run_id', 'payroll_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_run_adjusts_run_id', 'payroll_run.adjusts_run_id', 'payroll_run', SQLERRM;
        END;
    END IF;
    -- payroll_state_bracket.state_config_id -> payroll_state_tax_config.id  (ON DELETE RESTRICT)
    IF to_regclass('public.payroll_state_bracket') IS NOT NULL AND to_regclass('public.payroll_state_tax_config') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_state_bracket_state_config_id') THEN
        BEGIN
            ALTER TABLE payroll_state_bracket ADD CONSTRAINT fk_payroll_state_bracket_state_config_id
                FOREIGN KEY (state_config_id) REFERENCES payroll_state_tax_config (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_state_bracket_state_config_id', 'payroll_state_bracket.state_config_id', 'payroll_state_tax_config';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_state_bracket_state_config_id', 'payroll_state_bracket.state_config_id', 'payroll_state_tax_config', SQLERRM;
        END;
    END IF;
    -- payroll_w4.employee_id -> payroll_employee.id  (ON DELETE RESTRICT)
    IF to_regclass('public.payroll_w4') IS NOT NULL AND to_regclass('public.payroll_employee') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_w4_employee_id') THEN
        BEGIN
            ALTER TABLE payroll_w4 ADD CONSTRAINT fk_payroll_w4_employee_id
                FOREIGN KEY (employee_id) REFERENCES payroll_employee (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_w4_employee_id', 'payroll_w4.employee_id', 'payroll_employee';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_w4_employee_id', 'payroll_w4.employee_id', 'payroll_employee', SQLERRM;
        END;
    END IF;
    -- plaid_item.created_by_user_id -> app_user.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_item') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_item_created_by_user_id') THEN
        BEGIN
            ALTER TABLE plaid_item ADD CONSTRAINT fk_plaid_item_created_by_user_id
                FOREIGN KEY (created_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_item_created_by_user_id', 'plaid_item.created_by_user_id', 'app_user';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_item_created_by_user_id', 'plaid_item.created_by_user_id', 'app_user', SQLERRM;
        END;
    END IF;
    -- plaid_transaction_staging.mapped_main_source_id -> main_source.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.main_source') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_transaction_staging_mapped_main_source_id') THEN
        BEGIN
            ALTER TABLE plaid_transaction_staging ADD CONSTRAINT fk_plaid_transaction_staging_mapped_main_source_id
                FOREIGN KEY (mapped_main_source_id) REFERENCES main_source (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_transaction_staging_mapped_main_source_id', 'plaid_transaction_staging.mapped_main_source_id', 'main_source';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_transaction_staging_mapped_main_source_id', 'plaid_transaction_staging.mapped_main_source_id', 'main_source', SQLERRM;
        END;
    END IF;
    -- plaid_transaction_staging.mapped_purpose_id -> purpose.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.purpose') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_transaction_staging_mapped_purpose_id') THEN
        BEGIN
            ALTER TABLE plaid_transaction_staging ADD CONSTRAINT fk_plaid_transaction_staging_mapped_purpose_id
                FOREIGN KEY (mapped_purpose_id) REFERENCES purpose (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_transaction_staging_mapped_purpose_id', 'plaid_transaction_staging.mapped_purpose_id', 'purpose';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_transaction_staging_mapped_purpose_id', 'plaid_transaction_staging.mapped_purpose_id', 'purpose', SQLERRM;
        END;
    END IF;
    -- plaid_transaction_staging.mapped_sub_source_id -> sub_source.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.sub_source') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_transaction_staging_mapped_sub_source_id') THEN
        BEGIN
            ALTER TABLE plaid_transaction_staging ADD CONSTRAINT fk_plaid_transaction_staging_mapped_sub_source_id
                FOREIGN KEY (mapped_sub_source_id) REFERENCES sub_source (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_transaction_staging_mapped_sub_source_id', 'plaid_transaction_staging.mapped_sub_source_id', 'sub_source';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_transaction_staging_mapped_sub_source_id', 'plaid_transaction_staging.mapped_sub_source_id', 'sub_source', SQLERRM;
        END;
    END IF;
    -- plaid_transaction_staging.mapped_transaction_type_id -> transaction_type.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.transaction_type') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_transaction_staging_mapped_transaction_type_id') THEN
        BEGIN
            ALTER TABLE plaid_transaction_staging ADD CONSTRAINT fk_plaid_transaction_staging_mapped_transaction_type_id
                FOREIGN KEY (mapped_transaction_type_id) REFERENCES transaction_type (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_transaction_staging_mapped_transaction_type_id', 'plaid_transaction_staging.mapped_transaction_type_id', 'transaction_type';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_transaction_staging_mapped_transaction_type_id', 'plaid_transaction_staging.mapped_transaction_type_id', 'transaction_type', SQLERRM;
        END;
    END IF;
    -- plaid_transaction_staging.plaid_account_id -> plaid_account.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.plaid_account') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_transaction_staging_plaid_account_id') THEN
        BEGIN
            ALTER TABLE plaid_transaction_staging ADD CONSTRAINT fk_plaid_transaction_staging_plaid_account_id
                FOREIGN KEY (plaid_account_id) REFERENCES plaid_account (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_transaction_staging_plaid_account_id', 'plaid_transaction_staging.plaid_account_id', 'plaid_account';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_transaction_staging_plaid_account_id', 'plaid_transaction_staging.plaid_account_id', 'plaid_account', SQLERRM;
        END;
    END IF;
    -- plaid_transaction_staging.promoted_expense_id -> expense.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.expense') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_transaction_staging_promoted_expense_id') THEN
        BEGIN
            ALTER TABLE plaid_transaction_staging ADD CONSTRAINT fk_plaid_transaction_staging_promoted_expense_id
                FOREIGN KEY (promoted_expense_id) REFERENCES expense (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_transaction_staging_promoted_expense_id', 'plaid_transaction_staging.promoted_expense_id', 'expense';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_transaction_staging_promoted_expense_id', 'plaid_transaction_staging.promoted_expense_id', 'expense', SQLERRM;
        END;
    END IF;
    -- plaid_transaction_staging.promoted_income_id -> income.id  (ON DELETE RESTRICT)
    IF to_regclass('public.plaid_transaction_staging') IS NOT NULL AND to_regclass('public.income') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plaid_transaction_staging_promoted_income_id') THEN
        BEGIN
            ALTER TABLE plaid_transaction_staging ADD CONSTRAINT fk_plaid_transaction_staging_promoted_income_id
                FOREIGN KEY (promoted_income_id) REFERENCES income (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_plaid_transaction_staging_promoted_income_id', 'plaid_transaction_staging.promoted_income_id', 'income';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_plaid_transaction_staging_promoted_income_id', 'plaid_transaction_staging.promoted_income_id', 'income', SQLERRM;
        END;
    END IF;
    -- pledge_campaign.sub_source_id -> sub_source.id  (ON DELETE RESTRICT)
    IF to_regclass('public.pledge_campaign') IS NOT NULL AND to_regclass('public.sub_source') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pledge_campaign_sub_source_id') THEN
        BEGIN
            ALTER TABLE pledge_campaign ADD CONSTRAINT fk_pledge_campaign_sub_source_id
                FOREIGN KEY (sub_source_id) REFERENCES sub_source (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_pledge_campaign_sub_source_id', 'pledge_campaign.sub_source_id', 'sub_source';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_pledge_campaign_sub_source_id', 'pledge_campaign.sub_source_id', 'sub_source', SQLERRM;
        END;
    END IF;
    -- pledge_member.campaign_id -> pledge_campaign.id  (ON DELETE RESTRICT)
    IF to_regclass('public.pledge_member') IS NOT NULL AND to_regclass('public.pledge_campaign') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pledge_member_campaign_id') THEN
        BEGIN
            ALTER TABLE pledge_member ADD CONSTRAINT fk_pledge_member_campaign_id
                FOREIGN KEY (campaign_id) REFERENCES pledge_campaign (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_pledge_member_campaign_id', 'pledge_member.campaign_id', 'pledge_campaign';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_pledge_member_campaign_id', 'pledge_member.campaign_id', 'pledge_campaign', SQLERRM;
        END;
    END IF;
    -- pledge_member.family_id -> family.id  (ON DELETE RESTRICT)
    IF to_regclass('public.pledge_member') IS NOT NULL AND to_regclass('public.family') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pledge_member_family_id') THEN
        BEGIN
            ALTER TABLE pledge_member ADD CONSTRAINT fk_pledge_member_family_id
                FOREIGN KEY (family_id) REFERENCES family (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_pledge_member_family_id', 'pledge_member.family_id', 'family';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_pledge_member_family_id', 'pledge_member.family_id', 'family', SQLERRM;
        END;
    END IF;
    -- pledge_member.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.pledge_member') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pledge_member_family_member_id') THEN
        BEGIN
            ALTER TABLE pledge_member ADD CONSTRAINT fk_pledge_member_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_pledge_member_family_member_id', 'pledge_member.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_pledge_member_family_member_id', 'pledge_member.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- prayer_note.prayer_request_id -> prayer_request.id  (ON DELETE RESTRICT)
    IF to_regclass('public.prayer_note') IS NOT NULL AND to_regclass('public.prayer_request') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_prayer_note_prayer_request_id') THEN
        BEGIN
            ALTER TABLE prayer_note ADD CONSTRAINT fk_prayer_note_prayer_request_id
                FOREIGN KEY (prayer_request_id) REFERENCES prayer_request (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_prayer_note_prayer_request_id', 'prayer_note.prayer_request_id', 'prayer_request';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_prayer_note_prayer_request_id', 'prayer_note.prayer_request_id', 'prayer_request', SQLERRM;
        END;
    END IF;
    -- prayer_request.assigned_volunteer_id -> prayer_volunteer.id  (ON DELETE RESTRICT)
    IF to_regclass('public.prayer_request') IS NOT NULL AND to_regclass('public.prayer_volunteer') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_prayer_request_assigned_volunteer_id') THEN
        BEGIN
            ALTER TABLE prayer_request ADD CONSTRAINT fk_prayer_request_assigned_volunteer_id
                FOREIGN KEY (assigned_volunteer_id) REFERENCES prayer_volunteer (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_prayer_request_assigned_volunteer_id', 'prayer_request.assigned_volunteer_id', 'prayer_volunteer';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_prayer_request_assigned_volunteer_id', 'prayer_request.assigned_volunteer_id', 'prayer_volunteer', SQLERRM;
        END;
    END IF;
    -- prayer_request.section_id -> prayer_section.id  (ON DELETE RESTRICT)
    IF to_regclass('public.prayer_request') IS NOT NULL AND to_regclass('public.prayer_section') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_prayer_request_section_id') THEN
        BEGIN
            ALTER TABLE prayer_request ADD CONSTRAINT fk_prayer_request_section_id
                FOREIGN KEY (section_id) REFERENCES prayer_section (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_prayer_request_section_id', 'prayer_request.section_id', 'prayer_section';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_prayer_request_section_id', 'prayer_request.section_id', 'prayer_section', SQLERRM;
        END;
    END IF;
    -- prayer_request_volunteer.prayer_request_id -> prayer_request.id  (ON DELETE RESTRICT)
    IF to_regclass('public.prayer_request_volunteer') IS NOT NULL AND to_regclass('public.prayer_request') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_prayer_request_volunteer_prayer_request_id') THEN
        BEGIN
            ALTER TABLE prayer_request_volunteer ADD CONSTRAINT fk_prayer_request_volunteer_prayer_request_id
                FOREIGN KEY (prayer_request_id) REFERENCES prayer_request (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_prayer_request_volunteer_prayer_request_id', 'prayer_request_volunteer.prayer_request_id', 'prayer_request';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_prayer_request_volunteer_prayer_request_id', 'prayer_request_volunteer.prayer_request_id', 'prayer_request', SQLERRM;
        END;
    END IF;
    -- prayer_request_volunteer.prayer_volunteer_id -> prayer_volunteer.id  (ON DELETE RESTRICT)
    IF to_regclass('public.prayer_request_volunteer') IS NOT NULL AND to_regclass('public.prayer_volunteer') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_prayer_request_volunteer_prayer_volunteer_id') THEN
        BEGIN
            ALTER TABLE prayer_request_volunteer ADD CONSTRAINT fk_prayer_request_volunteer_prayer_volunteer_id
                FOREIGN KEY (prayer_volunteer_id) REFERENCES prayer_volunteer (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_prayer_request_volunteer_prayer_volunteer_id', 'prayer_request_volunteer.prayer_volunteer_id', 'prayer_volunteer';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_prayer_request_volunteer_prayer_volunteer_id', 'prayer_request_volunteer.prayer_volunteer_id', 'prayer_volunteer', SQLERRM;
        END;
    END IF;
    -- prayer_volunteer.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.prayer_volunteer') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_prayer_volunteer_family_member_id') THEN
        BEGIN
            ALTER TABLE prayer_volunteer ADD CONSTRAINT fk_prayer_volunteer_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_prayer_volunteer_family_member_id', 'prayer_volunteer.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_prayer_volunteer_family_member_id', 'prayer_volunteer.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- public_prayer_note.public_prayer_id -> public_prayer_request.id  (ON DELETE RESTRICT)
    IF to_regclass('public.public_prayer_note') IS NOT NULL AND to_regclass('public.public_prayer_request') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_public_prayer_note_public_prayer_id') THEN
        BEGIN
            ALTER TABLE public_prayer_note ADD CONSTRAINT fk_public_prayer_note_public_prayer_id
                FOREIGN KEY (public_prayer_id) REFERENCES public_prayer_request (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_public_prayer_note_public_prayer_id', 'public_prayer_note.public_prayer_id', 'public_prayer_request';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_public_prayer_note_public_prayer_id', 'public_prayer_note.public_prayer_id', 'public_prayer_request', SQLERRM;
        END;
    END IF;
    -- public_prayer_request.assigned_volunteer_id -> prayer_volunteer.id  (ON DELETE RESTRICT)
    IF to_regclass('public.public_prayer_request') IS NOT NULL AND to_regclass('public.prayer_volunteer') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_public_prayer_request_assigned_volunteer_id') THEN
        BEGIN
            ALTER TABLE public_prayer_request ADD CONSTRAINT fk_public_prayer_request_assigned_volunteer_id
                FOREIGN KEY (assigned_volunteer_id) REFERENCES prayer_volunteer (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_public_prayer_request_assigned_volunteer_id', 'public_prayer_request.assigned_volunteer_id', 'prayer_volunteer';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_public_prayer_request_assigned_volunteer_id', 'public_prayer_request.assigned_volunteer_id', 'prayer_volunteer', SQLERRM;
        END;
    END IF;
    -- public_prayer_request.follow_up_id -> follow_up.id  (ON DELETE RESTRICT)
    IF to_regclass('public.public_prayer_request') IS NOT NULL AND to_regclass('public.follow_up') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_public_prayer_request_follow_up_id') THEN
        BEGIN
            ALTER TABLE public_prayer_request ADD CONSTRAINT fk_public_prayer_request_follow_up_id
                FOREIGN KEY (follow_up_id) REFERENCES follow_up (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_public_prayer_request_follow_up_id', 'public_prayer_request.follow_up_id', 'follow_up';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_public_prayer_request_follow_up_id', 'public_prayer_request.follow_up_id', 'follow_up', SQLERRM;
        END;
    END IF;
    -- song.finalized_section_id -> finalized_section.id  (ON DELETE RESTRICT)
    IF to_regclass('public.song') IS NOT NULL AND to_regclass('public.finalized_section') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_song_finalized_section_id') THEN
        BEGIN
            ALTER TABLE song ADD CONSTRAINT fk_song_finalized_section_id
                FOREIGN KEY (finalized_section_id) REFERENCES finalized_section (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_song_finalized_section_id', 'song.finalized_section_id', 'finalized_section';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_song_finalized_section_id', 'song.finalized_section_id', 'finalized_section', SQLERRM;
        END;
    END IF;
    -- song.section_id -> song_section.id  (ON DELETE RESTRICT)
    IF to_regclass('public.song') IS NOT NULL AND to_regclass('public.song_section') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_song_section_id') THEN
        BEGIN
            ALTER TABLE song ADD CONSTRAINT fk_song_section_id
                FOREIGN KEY (section_id) REFERENCES song_section (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_song_section_id', 'song.section_id', 'song_section';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_song_section_id', 'song.section_id', 'song_section', SQLERRM;
        END;
    END IF;
    -- song_audit_log.song_id -> song.id : INTENTIONALLY NOT ENFORCED (M7 reconciliation).
    -- The Song Book audit log is append-only and deliberately keeps song_id AFTER a song is
    -- deleted: SongBookService.delete() records the DELETE action with the removed song's id.
    -- A RESTRICT key would block deleting any song that has audit history AND reject the
    -- deletion's own audit row; a CASCADE key would erase the history (and still reject the
    -- post-delete DELETE row). So song_id here is a historical reference, not a foreign key.
    -- (Verified on PostgreSQL: with the key present, DELETE FROM song raises
    --  foreign_key_violation on 'fk_song_audit_log_song_id'.)
    -- ss_exam.class_id -> ss_class.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_exam') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_exam_class_id') THEN
        BEGIN
            ALTER TABLE ss_exam ADD CONSTRAINT fk_ss_exam_class_id
                FOREIGN KEY (class_id) REFERENCES ss_class (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_exam_class_id', 'ss_exam.class_id', 'ss_class';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_exam_class_id', 'ss_exam.class_id', 'ss_class', SQLERRM;
        END;
    END IF;
    -- ss_file.class_id -> ss_class.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_file') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_file_class_id') THEN
        BEGIN
            ALTER TABLE ss_file ADD CONSTRAINT fk_ss_file_class_id
                FOREIGN KEY (class_id) REFERENCES ss_class (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_file_class_id', 'ss_file.class_id', 'ss_class';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_file_class_id', 'ss_file.class_id', 'ss_class', SQLERRM;
        END;
    END IF;
    -- ss_lesson.class_id -> ss_class.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_lesson') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_lesson_class_id') THEN
        BEGIN
            ALTER TABLE ss_lesson ADD CONSTRAINT fk_ss_lesson_class_id
                FOREIGN KEY (class_id) REFERENCES ss_class (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_lesson_class_id', 'ss_lesson.class_id', 'ss_class';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_lesson_class_id', 'ss_lesson.class_id', 'ss_class', SQLERRM;
        END;
    END IF;
    -- ss_lesson.student_id -> ss_student.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_lesson') IS NOT NULL AND to_regclass('public.ss_student') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_lesson_student_id') THEN
        BEGIN
            ALTER TABLE ss_lesson ADD CONSTRAINT fk_ss_lesson_student_id
                FOREIGN KEY (student_id) REFERENCES ss_student (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_lesson_student_id', 'ss_lesson.student_id', 'ss_student';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_lesson_student_id', 'ss_lesson.student_id', 'ss_student', SQLERRM;
        END;
    END IF;
    -- ss_note.class_id -> ss_class.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_note') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_note_class_id') THEN
        BEGIN
            ALTER TABLE ss_note ADD CONSTRAINT fk_ss_note_class_id
                FOREIGN KEY (class_id) REFERENCES ss_class (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_note_class_id', 'ss_note.class_id', 'ss_class';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_note_class_id', 'ss_note.class_id', 'ss_class', SQLERRM;
        END;
    END IF;
    -- ss_question.exam_id -> ss_exam.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_question') IS NOT NULL AND to_regclass('public.ss_exam') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_question_exam_id') THEN
        BEGIN
            ALTER TABLE ss_question ADD CONSTRAINT fk_ss_question_exam_id
                FOREIGN KEY (exam_id) REFERENCES ss_exam (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_question_exam_id', 'ss_question.exam_id', 'ss_exam';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_question_exam_id', 'ss_question.exam_id', 'ss_exam', SQLERRM;
        END;
    END IF;
    -- ss_student.class_id -> ss_class.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_student') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_student_class_id') THEN
        BEGIN
            ALTER TABLE ss_student ADD CONSTRAINT fk_ss_student_class_id
                FOREIGN KEY (class_id) REFERENCES ss_class (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_student_class_id', 'ss_student.class_id', 'ss_class';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_student_class_id', 'ss_student.class_id', 'ss_class', SQLERRM;
        END;
    END IF;
    -- ss_student.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_student') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_student_family_member_id') THEN
        BEGIN
            ALTER TABLE ss_student ADD CONSTRAINT fk_ss_student_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_student_family_member_id', 'ss_student.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_student_family_member_id', 'ss_student.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- ss_student.teacher_id -> ss_teacher.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_student') IS NOT NULL AND to_regclass('public.ss_teacher') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_student_teacher_id') THEN
        BEGIN
            ALTER TABLE ss_student ADD CONSTRAINT fk_ss_student_teacher_id
                FOREIGN KEY (teacher_id) REFERENCES ss_teacher (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_student_teacher_id', 'ss_student.teacher_id', 'ss_teacher';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_student_teacher_id', 'ss_student.teacher_id', 'ss_teacher', SQLERRM;
        END;
    END IF;
    -- ss_submission.exam_id -> ss_exam.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_submission') IS NOT NULL AND to_regclass('public.ss_exam') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_submission_exam_id') THEN
        BEGIN
            ALTER TABLE ss_submission ADD CONSTRAINT fk_ss_submission_exam_id
                FOREIGN KEY (exam_id) REFERENCES ss_exam (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_submission_exam_id', 'ss_submission.exam_id', 'ss_exam';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_submission_exam_id', 'ss_submission.exam_id', 'ss_exam', SQLERRM;
        END;
    END IF;
    -- ss_submission.student_id -> ss_student.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_submission') IS NOT NULL AND to_regclass('public.ss_student') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_submission_student_id') THEN
        BEGIN
            ALTER TABLE ss_submission ADD CONSTRAINT fk_ss_submission_student_id
                FOREIGN KEY (student_id) REFERENCES ss_student (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_submission_student_id', 'ss_submission.student_id', 'ss_student';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_submission_student_id', 'ss_submission.student_id', 'ss_student', SQLERRM;
        END;
    END IF;
    -- ss_teacher.class_id -> ss_class.id  (ON DELETE RESTRICT)
    IF to_regclass('public.ss_teacher') IS NOT NULL AND to_regclass('public.ss_class') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_teacher_class_id') THEN
        BEGIN
            ALTER TABLE ss_teacher ADD CONSTRAINT fk_ss_teacher_class_id
                FOREIGN KEY (class_id) REFERENCES ss_class (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_teacher_class_id', 'ss_teacher.class_id', 'ss_class';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_teacher_class_id', 'ss_teacher.class_id', 'ss_class', SQLERRM;
        END;
    END IF;
    -- staging_expense.batch_id -> import_batch.id  (ON DELETE RESTRICT)
    IF to_regclass('public.staging_expense') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staging_expense_batch_id') THEN
        BEGIN
            ALTER TABLE staging_expense ADD CONSTRAINT fk_staging_expense_batch_id
                FOREIGN KEY (batch_id) REFERENCES import_batch (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_staging_expense_batch_id', 'staging_expense.batch_id', 'import_batch';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_staging_expense_batch_id', 'staging_expense.batch_id', 'import_batch', SQLERRM;
        END;
    END IF;
    -- staging_expense.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.staging_expense') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staging_expense_run_id') THEN
        BEGIN
            ALTER TABLE staging_expense ADD CONSTRAINT fk_staging_expense_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_staging_expense_run_id', 'staging_expense.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_staging_expense_run_id', 'staging_expense.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- staging_family.batch_id -> import_batch.id  (ON DELETE RESTRICT)
    IF to_regclass('public.staging_family') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staging_family_batch_id') THEN
        BEGIN
            ALTER TABLE staging_family ADD CONSTRAINT fk_staging_family_batch_id
                FOREIGN KEY (batch_id) REFERENCES import_batch (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_staging_family_batch_id', 'staging_family.batch_id', 'import_batch';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_staging_family_batch_id', 'staging_family.batch_id', 'import_batch', SQLERRM;
        END;
    END IF;
    -- staging_family.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.staging_family') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staging_family_run_id') THEN
        BEGIN
            ALTER TABLE staging_family ADD CONSTRAINT fk_staging_family_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_staging_family_run_id', 'staging_family.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_staging_family_run_id', 'staging_family.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- staging_income.batch_id -> import_batch.id  (ON DELETE RESTRICT)
    IF to_regclass('public.staging_income') IS NOT NULL AND to_regclass('public.import_batch') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staging_income_batch_id') THEN
        BEGIN
            ALTER TABLE staging_income ADD CONSTRAINT fk_staging_income_batch_id
                FOREIGN KEY (batch_id) REFERENCES import_batch (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_staging_income_batch_id', 'staging_income.batch_id', 'import_batch';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_staging_income_batch_id', 'staging_income.batch_id', 'import_batch', SQLERRM;
        END;
    END IF;
    -- staging_income.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.staging_income') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staging_income_run_id') THEN
        BEGIN
            ALTER TABLE staging_income ADD CONSTRAINT fk_staging_income_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_staging_income_run_id', 'staging_income.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_staging_income_run_id', 'staging_income.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- staging_raw.run_id -> import_run.id  (ON DELETE RESTRICT)
    IF to_regclass('public.staging_raw') IS NOT NULL AND to_regclass('public.import_run') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_staging_raw_run_id') THEN
        BEGIN
            ALTER TABLE staging_raw ADD CONSTRAINT fk_staging_raw_run_id
                FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_staging_raw_run_id', 'staging_raw.run_id', 'import_run';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_staging_raw_run_id', 'staging_raw.run_id', 'import_run', SQLERRM;
        END;
    END IF;
    -- uploaded_file.uploaded_by_id -> app_user.id  (ON DELETE RESTRICT)
    IF to_regclass('public.uploaded_file') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_uploaded_file_uploaded_by_id') THEN
        BEGIN
            ALTER TABLE uploaded_file ADD CONSTRAINT fk_uploaded_file_uploaded_by_id
                FOREIGN KEY (uploaded_by_id) REFERENCES app_user (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_uploaded_file_uploaded_by_id', 'uploaded_file.uploaded_by_id', 'app_user';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_uploaded_file_uploaded_by_id', 'uploaded_file.uploaded_by_id', 'app_user', SQLERRM;
        END;
    END IF;
    -- user_permissions.app_user_id -> app_user.id  (ON DELETE RESTRICT)
    IF to_regclass('public.user_permissions') IS NOT NULL AND to_regclass('public.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_permissions_app_user_id') THEN
        BEGIN
            ALTER TABLE user_permissions ADD CONSTRAINT fk_user_permissions_app_user_id
                FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_user_permissions_app_user_id', 'user_permissions.app_user_id', 'app_user';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_user_permissions_app_user_id', 'user_permissions.app_user_id', 'app_user', SQLERRM;
        END;
    END IF;
    -- volunteer_assignment.event_id -> church_event.id  (ON DELETE RESTRICT)
    IF to_regclass('public.volunteer_assignment') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_volunteer_assignment_event_id') THEN
        BEGIN
            ALTER TABLE volunteer_assignment ADD CONSTRAINT fk_volunteer_assignment_event_id
                FOREIGN KEY (event_id) REFERENCES church_event (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_volunteer_assignment_event_id', 'volunteer_assignment.event_id', 'church_event';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_volunteer_assignment_event_id', 'volunteer_assignment.event_id', 'church_event', SQLERRM;
        END;
    END IF;
    -- volunteer_assignment.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.volunteer_assignment') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_volunteer_assignment_family_member_id') THEN
        BEGIN
            ALTER TABLE volunteer_assignment ADD CONSTRAINT fk_volunteer_assignment_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_volunteer_assignment_family_member_id', 'volunteer_assignment.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_volunteer_assignment_family_member_id', 'volunteer_assignment.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- volunteer_assignment.role_id -> volunteer_role.id  (ON DELETE RESTRICT)
    IF to_regclass('public.volunteer_assignment') IS NOT NULL AND to_regclass('public.volunteer_role') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_volunteer_assignment_role_id') THEN
        BEGIN
            ALTER TABLE volunteer_assignment ADD CONSTRAINT fk_volunteer_assignment_role_id
                FOREIGN KEY (role_id) REFERENCES volunteer_role (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_volunteer_assignment_role_id', 'volunteer_assignment.role_id', 'volunteer_role';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_volunteer_assignment_role_id', 'volunteer_assignment.role_id', 'volunteer_role', SQLERRM;
        END;
    END IF;
    -- volunteer_attendance.assignment_id -> volunteer_assignment.id  (ON DELETE RESTRICT)
    IF to_regclass('public.volunteer_attendance') IS NOT NULL AND to_regclass('public.volunteer_assignment') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_volunteer_attendance_assignment_id') THEN
        BEGIN
            ALTER TABLE volunteer_attendance ADD CONSTRAINT fk_volunteer_attendance_assignment_id
                FOREIGN KEY (assignment_id) REFERENCES volunteer_assignment (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_volunteer_attendance_assignment_id', 'volunteer_attendance.assignment_id', 'volunteer_assignment';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_volunteer_attendance_assignment_id', 'volunteer_attendance.assignment_id', 'volunteer_assignment', SQLERRM;
        END;
    END IF;
    -- volunteer_profile.family_member_id -> family_member.id  (ON DELETE RESTRICT)
    IF to_regclass('public.volunteer_profile') IS NOT NULL AND to_regclass('public.family_member') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_volunteer_profile_family_member_id') THEN
        BEGIN
            ALTER TABLE volunteer_profile ADD CONSTRAINT fk_volunteer_profile_family_member_id
                FOREIGN KEY (family_member_id) REFERENCES family_member (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_volunteer_profile_family_member_id', 'volunteer_profile.family_member_id', 'family_member';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_volunteer_profile_family_member_id', 'volunteer_profile.family_member_id', 'family_member', SQLERRM;
        END;
    END IF;
    -- volunteer_profile_role.role_id -> volunteer_role.id  (ON DELETE RESTRICT)
    IF to_regclass('public.volunteer_profile_role') IS NOT NULL AND to_regclass('public.volunteer_role') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_volunteer_profile_role_role_id') THEN
        BEGIN
            ALTER TABLE volunteer_profile_role ADD CONSTRAINT fk_volunteer_profile_role_role_id
                FOREIGN KEY (role_id) REFERENCES volunteer_role (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_volunteer_profile_role_role_id', 'volunteer_profile_role.role_id', 'volunteer_role';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_volunteer_profile_role_role_id', 'volunteer_profile_role.role_id', 'volunteer_role', SQLERRM;
        END;
    END IF;
    -- volunteer_profile_role.volunteer_profile_id -> volunteer_profile.id  (ON DELETE RESTRICT)
    IF to_regclass('public.volunteer_profile_role') IS NOT NULL AND to_regclass('public.volunteer_profile') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_volunteer_profile_role_volunteer_profile_id') THEN
        BEGIN
            ALTER TABLE volunteer_profile_role ADD CONSTRAINT fk_volunteer_profile_role_volunteer_profile_id
                FOREIGN KEY (volunteer_profile_id) REFERENCES volunteer_profile (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_volunteer_profile_role_volunteer_profile_id', 'volunteer_profile_role.volunteer_profile_id', 'volunteer_profile';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_volunteer_profile_role_volunteer_profile_id', 'volunteer_profile_role.volunteer_profile_id', 'volunteer_profile', SQLERRM;
        END;
    END IF;
    -- worship_assignment.group_id -> worship_group.id  (ON DELETE RESTRICT)
    IF to_regclass('public.worship_assignment') IS NOT NULL AND to_regclass('public.worship_group') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_worship_assignment_group_id') THEN
        BEGIN
            ALTER TABLE worship_assignment ADD CONSTRAINT fk_worship_assignment_group_id
                FOREIGN KEY (group_id) REFERENCES worship_group (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_worship_assignment_group_id', 'worship_assignment.group_id', 'worship_group';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_worship_assignment_group_id', 'worship_assignment.group_id', 'worship_group', SQLERRM;
        END;
    END IF;
    -- worship_assignment_member.instrument_id -> worship_instrument.id  (ON DELETE RESTRICT)
    IF to_regclass('public.worship_assignment_member') IS NOT NULL AND to_regclass('public.worship_instrument') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_worship_assignment_member_instrument_id') THEN
        BEGIN
            ALTER TABLE worship_assignment_member ADD CONSTRAINT fk_worship_assignment_member_instrument_id
                FOREIGN KEY (instrument_id) REFERENCES worship_instrument (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_worship_assignment_member_instrument_id', 'worship_assignment_member.instrument_id', 'worship_instrument';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_worship_assignment_member_instrument_id', 'worship_assignment_member.instrument_id', 'worship_instrument', SQLERRM;
        END;
    END IF;
    -- worship_group_member.instrument_id -> worship_instrument.id  (ON DELETE RESTRICT)
    IF to_regclass('public.worship_group_member') IS NOT NULL AND to_regclass('public.worship_instrument') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_worship_group_member_instrument_id') THEN
        BEGIN
            ALTER TABLE worship_group_member ADD CONSTRAINT fk_worship_group_member_instrument_id
                FOREIGN KEY (instrument_id) REFERENCES worship_instrument (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_worship_group_member_instrument_id', 'worship_group_member.instrument_id', 'worship_instrument';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_worship_group_member_instrument_id', 'worship_group_member.instrument_id', 'worship_instrument', SQLERRM;
        END;
    END IF;
    -- worship_instrument.group_id -> worship_group.id  (ON DELETE RESTRICT)
    IF to_regclass('public.worship_instrument') IS NOT NULL AND to_regclass('public.worship_group') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_worship_instrument_group_id') THEN
        BEGIN
            ALTER TABLE worship_instrument ADD CONSTRAINT fk_worship_instrument_group_id
                FOREIGN KEY (group_id) REFERENCES worship_group (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_worship_instrument_group_id', 'worship_instrument.group_id', 'worship_group';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_worship_instrument_group_id', 'worship_instrument.group_id', 'worship_group', SQLERRM;
        END;
    END IF;
    -- worship_song.group_id -> worship_group.id  (ON DELETE RESTRICT)
    IF to_regclass('public.worship_song') IS NOT NULL AND to_regclass('public.worship_group') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_worship_song_group_id') THEN
        BEGIN
            ALTER TABLE worship_song ADD CONSTRAINT fk_worship_song_group_id
                FOREIGN KEY (group_id) REFERENCES worship_group (id) ON DELETE RESTRICT;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_worship_song_group_id', 'worship_song.group_id', 'worship_group';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_worship_song_group_id', 'worship_song.group_id', 'worship_group', SQLERRM;
        END;
    END IF;

    -- ── CASCADE: pure detail rows, removed with their parent ──
    -- church_event_day.event_id -> church_event.id  (ON DELETE CASCADE)
    IF to_regclass('public.church_event_day') IS NOT NULL AND to_regclass('public.church_event') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_church_event_day_event_id') THEN
        BEGIN
            ALTER TABLE church_event_day ADD CONSTRAINT fk_church_event_day_event_id
                FOREIGN KEY (event_id) REFERENCES church_event (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_church_event_day_event_id', 'church_event_day.event_id', 'church_event';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_church_event_day_event_id', 'church_event_day.event_id', 'church_event', SQLERRM;
        END;
    END IF;
    -- event_registration_reminder_log.contact_id -> event_registration_reminder_contact.id  (ON DELETE CASCADE)
    IF to_regclass('public.event_registration_reminder_log') IS NOT NULL AND to_regclass('public.event_registration_reminder_contact') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_registration_reminder_log_contact_id') THEN
        BEGIN
            ALTER TABLE event_registration_reminder_log ADD CONSTRAINT fk_event_registration_reminder_log_contact_id
                FOREIGN KEY (contact_id) REFERENCES event_registration_reminder_contact (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_registration_reminder_log_contact_id', 'event_registration_reminder_log.contact_id', 'event_registration_reminder_contact';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_registration_reminder_log_contact_id', 'event_registration_reminder_log.contact_id', 'event_registration_reminder_contact', SQLERRM;
        END;
    END IF;
    -- event_volunteer_role.event_volunteer_id -> event_volunteer.id  (ON DELETE CASCADE)
    IF to_regclass('public.event_volunteer_role') IS NOT NULL AND to_regclass('public.event_volunteer') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_event_volunteer_role_event_volunteer_id') THEN
        BEGIN
            ALTER TABLE event_volunteer_role ADD CONSTRAINT fk_event_volunteer_role_event_volunteer_id
                FOREIGN KEY (event_volunteer_id) REFERENCES event_volunteer (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_event_volunteer_role_event_volunteer_id', 'event_volunteer_role.event_volunteer_id', 'event_volunteer';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_event_volunteer_role_event_volunteer_id', 'event_volunteer_role.event_volunteer_id', 'event_volunteer', SQLERRM;
        END;
    END IF;
    -- expense_check_image.expense_id -> expense.id  (ON DELETE CASCADE)
    IF to_regclass('public.expense_check_image') IS NOT NULL AND to_regclass('public.expense') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_expense_check_image_expense_id') THEN
        BEGIN
            ALTER TABLE expense_check_image ADD CONSTRAINT fk_expense_check_image_expense_id
                FOREIGN KEY (expense_id) REFERENCES expense (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_expense_check_image_expense_id', 'expense_check_image.expense_id', 'expense';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_expense_check_image_expense_id', 'expense_check_image.expense_id', 'expense', SQLERRM;
        END;
    END IF;
    -- guess_it_group_participant.group_id -> guess_it_group.id  (ON DELETE CASCADE)
    IF to_regclass('public.guess_it_group_participant') IS NOT NULL AND to_regclass('public.guess_it_group') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_guess_it_group_participant_group_id') THEN
        BEGIN
            ALTER TABLE guess_it_group_participant ADD CONSTRAINT fk_guess_it_group_participant_group_id
                FOREIGN KEY (group_id) REFERENCES guess_it_group (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_guess_it_group_participant_group_id', 'guess_it_group_participant.group_id', 'guess_it_group';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_guess_it_group_participant_group_id', 'guess_it_group_participant.group_id', 'guess_it_group', SQLERRM;
        END;
    END IF;
    -- guess_it_participant.game_id -> guess_it_game.id  (ON DELETE CASCADE)
    IF to_regclass('public.guess_it_participant') IS NOT NULL AND to_regclass('public.guess_it_game') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_guess_it_participant_game_id') THEN
        BEGIN
            ALTER TABLE guess_it_participant ADD CONSTRAINT fk_guess_it_participant_game_id
                FOREIGN KEY (game_id) REFERENCES guess_it_game (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_guess_it_participant_game_id', 'guess_it_participant.game_id', 'guess_it_game';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_guess_it_participant_game_id', 'guess_it_participant.game_id', 'guess_it_game', SQLERRM;
        END;
    END IF;
    -- guess_it_participant.group_participant_id -> guess_it_group_participant.id  (ON DELETE CASCADE)
    IF to_regclass('public.guess_it_participant') IS NOT NULL AND to_regclass('public.guess_it_group_participant') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_guess_it_participant_group_participant_id') THEN
        BEGIN
            ALTER TABLE guess_it_participant ADD CONSTRAINT fk_guess_it_participant_group_participant_id
                FOREIGN KEY (group_participant_id) REFERENCES guess_it_group_participant (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_guess_it_participant_group_participant_id', 'guess_it_participant.group_participant_id', 'guess_it_group_participant';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_guess_it_participant_group_participant_id', 'guess_it_participant.group_participant_id', 'guess_it_group_participant', SQLERRM;
        END;
    END IF;
    -- income_check_image.income_id -> income.id  (ON DELETE CASCADE)
    IF to_regclass('public.income_check_image') IS NOT NULL AND to_regclass('public.income') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_income_check_image_income_id') THEN
        BEGIN
            ALTER TABLE income_check_image ADD CONSTRAINT fk_income_check_image_income_id
                FOREIGN KEY (income_id) REFERENCES income (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_income_check_image_income_id', 'income_check_image.income_id', 'income';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_income_check_image_income_id', 'income_check_image.income_id', 'income', SQLERRM;
        END;
    END IF;
    -- payroll_paystub_item.paystub_id -> payroll_paystub.id  (ON DELETE CASCADE)
    IF to_regclass('public.payroll_paystub_item') IS NOT NULL AND to_regclass('public.payroll_paystub') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payroll_paystub_item_paystub_id') THEN
        BEGIN
            ALTER TABLE payroll_paystub_item ADD CONSTRAINT fk_payroll_paystub_item_paystub_id
                FOREIGN KEY (paystub_id) REFERENCES payroll_paystub (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_payroll_paystub_item_paystub_id', 'payroll_paystub_item.paystub_id', 'payroll_paystub';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_payroll_paystub_item_paystub_id', 'payroll_paystub_item.paystub_id', 'payroll_paystub', SQLERRM;
        END;
    END IF;
    -- ss_answer.question_id -> ss_question.id  (ON DELETE CASCADE)
    IF to_regclass('public.ss_answer') IS NOT NULL AND to_regclass('public.ss_question') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_answer_question_id') THEN
        BEGIN
            ALTER TABLE ss_answer ADD CONSTRAINT fk_ss_answer_question_id
                FOREIGN KEY (question_id) REFERENCES ss_question (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_answer_question_id', 'ss_answer.question_id', 'ss_question';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_answer_question_id', 'ss_answer.question_id', 'ss_question', SQLERRM;
        END;
    END IF;
    -- ss_answer.submission_id -> ss_submission.id  (ON DELETE CASCADE)
    IF to_regclass('public.ss_answer') IS NOT NULL AND to_regclass('public.ss_submission') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ss_answer_submission_id') THEN
        BEGIN
            ALTER TABLE ss_answer ADD CONSTRAINT fk_ss_answer_submission_id
                FOREIGN KEY (submission_id) REFERENCES ss_submission (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_ss_answer_submission_id', 'ss_answer.submission_id', 'ss_submission';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_ss_answer_submission_id', 'ss_answer.submission_id', 'ss_submission', SQLERRM;
        END;
    END IF;
    -- worship_assignment_member.assignment_id -> worship_assignment.id  (ON DELETE CASCADE)
    IF to_regclass('public.worship_assignment_member') IS NOT NULL AND to_regclass('public.worship_assignment') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_worship_assignment_member_assignment_id') THEN
        BEGIN
            ALTER TABLE worship_assignment_member ADD CONSTRAINT fk_worship_assignment_member_assignment_id
                FOREIGN KEY (assignment_id) REFERENCES worship_assignment (id) ON DELETE CASCADE;
            v_added := v_added + 1;
        EXCEPTION
            WHEN foreign_key_violation THEN
                v_orphaned := v_orphaned + 1;
                RAISE WARNING 'H3: % still has orphan rows (% -> %); run W1_orphan_cleanup.sql first. Skipped.', 'fk_worship_assignment_member_assignment_id', 'worship_assignment_member.assignment_id', 'worship_assignment';
            WHEN others THEN
                v_skipped := v_skipped + 1;
                RAISE WARNING 'H3: could not add % (% -> %): %', 'fk_worship_assignment_member_assignment_id', 'worship_assignment_member.assignment_id', 'worship_assignment', SQLERRM;
        END;
    END IF;

    RAISE NOTICE 'H3: % foreign key(s) added, % still blocked by orphans, % otherwise skipped.', v_added, v_orphaned, v_skipped;
END $$;

-- ── Deliberately not added (2) ──────────────────────────────────────────────────
-- SKIP song_book_access.member_id (bigint) -> family_member.id (integer): type mismatch, must be aligned first (H3/M-type)
-- SKIP song_audit_log.song_id -> song.id: intentionally NOT a foreign key (M7). The Song Book
--      audit log is append-only and keeps song_id after a song is deleted (the DELETE action is
--      itself recorded against the removed song). Enforcing it — RESTRICT or CASCADE — would block
--      song deletion and reject the deletion's own audit row. song_id is a historical reference.
