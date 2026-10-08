package com.churchgeniuspro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds lookup tables that have fixed IDs and cannot rely on Hibernate
 * {@code ddl-auto=update} to populate data.
 *
 * <p>Uses {@code INSERT … ON CONFLICT DO NOTHING} so re-starts are idempotent.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JdbcTemplate jdbc;

    public DataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAutoReminderTypes();
        migrateRolesToHead();
        syncSequences();
    }

    /**
     * Re-synchronises Hibernate id sequences with the actual MAX(id) of their tables.
     *
     * <p>When rows are inserted with explicit ids outside the sequence (e.g. a
     * {@code pg_dump}/restore, a data import, or copying between environments) the
     * sequence is left behind, so the next {@code nextval} returns an id that already
     * exists — causing "duplicate key value violates unique constraint …_pkey" on the
     * next insert. This idempotent startup fixup advances each sequence to MAX(id)+1.
     */
    private void syncSequences() {
        resyncSequence("event_registration_id_seq",      "event_registration");
        resyncSequence("meeting_message_template_id_seq", "meeting_message_template");
        resyncSequence("event_email_template_id_seq",     "event_email_template");
    }

    /** Sets {@code seq} so the next id is MAX(id)+1 for {@code table} (no-op if the sequence is absent). */
    private void resyncSequence(String seq, String table) {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT CASE WHEN to_regclass(?) IS NULL THEN 0 ELSE 1 END", Integer.class, seq);
            if (exists == null || exists == 0) return;   // sequence not created yet
            // is_called=false → the next nextval() returns exactly this value.
            Long next = jdbc.queryForObject(
                    "SELECT setval(?, COALESCE((SELECT MAX(id) FROM " + table + "), 0) + 1, false)",
                    Long.class, seq);
            log.info("DataSeeder: re-synced sequence {} (next id = {}).", seq, next);
        } catch (Exception e) {
            log.warn("DataSeeder: could not re-sync sequence {} — {}", seq, e.getMessage());
        }
    }

    /**
     * Ensures the auto_reminder_types rows exist.
     * IDs are stable and must match the scheduler's type-ID constants.
     * (ID 15 is reserved for prayer-request reminders; ID 16 = Celebrants (Members Only).)
     */
    private void seedAutoReminderTypes() {
        String sql = "INSERT INTO auto_reminder_types (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING";

        Object[][] rows = {
            { 1,  "Meeting Reminder"              },
            { 2,  "Weekly Meeting Reminder"        },
            { 3,  "Birthday"                       },
            { 4,  "Wedding Anniversary"            },
            { 5,  "Birthdays & Anniversary"        },
            { 6,  "Monthly Statement"              },
            { 7,  "New Year"                       },
            { 8,  "Christmas"                      },
            { 9,  "US Independence"                },
            { 10, "Thanksgiving"                   },
            { 11, "Veterans Day"                   },
            { 12, "Presidents' Day"                },
            { 13, "Memorial Day"                   },
            { 14, "Labor Day"                      },
            { 16, "Celebrants (Members Only)"      },
        };

        for (Object[] row : rows) {
            jdbc.update(sql, row[0], row[1]);
        }
    }

    /**
     * One-time idempotent migration: normalises the family_member role column so
     * that all legacy "Primary" and "Primary/Head" values become "Head".
     * Safe to re-run on every startup — it only updates rows that still hold the
     * old values.
     */
    private void normaliseRoles() {
        try {
            int updated = jdbc.update(
                "UPDATE family_member SET role = 'Head' " +
                "WHERE role IN ('Primary', 'Primary/Head') AND delete_flag = false");
            if (updated > 0) {
                log.info("DataSeeder: normalised {} legacy role value(s) to 'Head'.", updated);
            }
        } catch (Exception e) {
            log.warn("DataSeeder: could not normalise roles — {}", e.getMessage());
        }
    }
    /**
     * Alias kept for call-site compatibility.
     * Delegates to normaliseRoles().
     */
    private void migrateRolesToHead() {
        normaliseRoles();
    }
}
