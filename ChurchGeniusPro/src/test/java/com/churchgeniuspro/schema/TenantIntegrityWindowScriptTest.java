package com.churchgeniuspro.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit H2/H3/H4: the staged window scripts under {@code src/main/resources/db/window/}
 * are run by hand in a maintenance window, not by Flyway. They were verified end to end against
 * PostgreSQL 16 (orphan preview → cleanup → foreign keys added → RESTRICT rejects an orphan and a
 * hard delete → CASCADE removes a detail row; H4 keys added, idempotent, and self-aborting on a
 * duplicate). This pins the properties that keep them safe so a hand-edit cannot quietly break them.
 */
@DisplayName("db/window — staged tenant-integrity scripts (DB audit H2/H3/H4)")
class TenantIntegrityWindowScriptTest {

    private static final Path DIR = Path.of("src/main/resources/db/window");

    private static String read(String name) throws IOException {
        return Files.readString(DIR.resolve(name));
    }

    @Test
    @DisplayName("W3 adds exactly 123 foreign keys through guarded blocks, 12 ON DELETE CASCADE; two relationships are deliberately not enforced")
    void w3ForeignKeys() throws IOException {
        String sql = read("W3_add_foreign_keys.sql");
        Matcher add = Pattern.compile("ADD CONSTRAINT fk_\\w+").matcher(sql);
        int fks = 0; while (add.find()) fks++;
        assertThat(fks).as("foreign keys added").isEqualTo(123);

        // count the ON DELETE clause on the ALTER itself (each block also names it once in a comment)
        Matcher cas = Pattern.compile("REFERENCES \\w+ \\(id\\) ON DELETE CASCADE").matcher(sql);
        int cascade = 0; while (cas.find()) cascade++;
        Matcher res = Pattern.compile("REFERENCES \\w+ \\(id\\) ON DELETE RESTRICT").matcher(sql);
        int restrict = 0; while (res.find()) restrict++;
        assertThat(cascade).as("CASCADE keys (composition/detail only)").isEqualTo(12);
        assertThat(restrict).as("RESTRICT keys (the safe default)").isEqualTo(111);

        // every ALTER … ADD CONSTRAINT is inside the idempotent guard, never bare
        assertThat(sql).doesNotContainPattern("(?m)^ALTER TABLE ");
        assertThat(sql).contains("NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname =");
        assertThat(sql).contains("WHEN foreign_key_violation THEN");
        // the type mismatch the report named is skipped, not added
        assertThat(sql).contains("SKIP song_book_access.member_id");
        assertThat(sql).doesNotContain("ADD CONSTRAINT fk_song_book_access_member_id");
        // song_audit_log.song_id is an append-only historical reference (M7), deliberately not a FK:
        // enforcing it would block song deletion and reject the deletion's own audit row.
        assertThat(sql).doesNotContain("ADD CONSTRAINT fk_song_audit_log_song_id");
        assertThat(sql).contains("SKIP song_audit_log.song_id");
    }

    @Test
    @DisplayName("W1 preview is strictly read-only; W1 cleanup scopes the tenant sweep away from the root and overloaded tables")
    void w1Cleanup() throws IOException {
        String preview = read("W1_orphan_preview.sql");
        assertThat(preview.toUpperCase()).doesNotContain("DELETE ").doesNotContain("UPDATE ")
                .doesNotContain("INSERT ").doesNotContain("ALTER ").doesNotContain("DROP ");
        assertThat(preview).contains("orphan_rows > 0");

        String cleanup = read("W1_orphan_cleanup.sql");
        // the orphaned-tenant sweep must never touch the tenant roots or the overloaded signup table
        assertThat(cleanup).contains("NOT IN ('service_client','church_registration','signup','flyway_schema_history')");
        assertThat(cleanup).contains("FROM service_client s WHERE s.client_id = x.");
        // every delete is scoped by a WHERE that names the unreachable rows — never a bare table wipe
        int deletes = cleanup.split("DELETE FROM ", -1).length - 1;
        int scoped = cleanup.split("WHERE ", -1).length - 1;
        assertThat(scoped).as("every DELETE is scoped by a WHERE").isGreaterThanOrEqualTo(deletes);
    }

    @Test
    @DisplayName("W2 proves the data is clean before adding a root key, and covers all three root columns")
    void w2RootKeys() throws IOException {
        String sql = read("W2_tenant_root_keys.sql");
        assertThat(sql).contains("uq_service_client_client_id").contains("uq_church_registration_client_id");
        assertThat(sql).contains("ALTER TABLE app_user ALTER COLUMN client_id SET NOT NULL");
        // it aborts rather than forcing a constraint onto bad data
        assertThat(sql).contains("RAISE EXCEPTION 'H4:").contains("duplicate service_client.client_id");
        assertThat(sql).contains("IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_service_client_client_id')");
    }

    @Test
    @DisplayName("W4 adds a backfilled client_id + tenant index to all 10 column-less tables, additively, with NOT NULL deferred")
    void w4TenantColumns() throws IOException {
        String sql = read("W4_tenant_columns.sql");
        for (String t : List.of("worship_instrument", "worship_group_member", "worship_assignment_member",
                "church_event_day", "member_preference", "user_permissions", "demo_reminder_log",
                "guess_it_group_participant", "guess_it_participant", "mapping_rule")) {
            assertThat(sql).as("adds client_id to %s", t)
                    .contains("ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS client_id");
            assertThat(sql).as("tenant index for %s", t).contains("idx_" + t + "_tenant");
        }
        // additive + idempotent: guarded adds, backfill only touches still-NULL rows, nothing destructive
        assertThat(sql).contains("AND c.client_id IS NULL");
        assertThat(sql.toUpperCase()).doesNotContain("DROP COLUMN").doesNotContain("DROP TABLE");
        // NOT NULL is deliberately deferred to the post-deploy step — never executed inline here
        assertThat(sql).doesNotContainPattern("(?m)^\\s*ALTER TABLE \\w+ ALTER COLUMN client_id SET NOT NULL;");
        // the two-level worship chain is backfilled parent-first (instrument before group_member)
        assertThat(sql.indexOf("worship_instrument c SET client_id"))
                .isLessThan(sql.indexOf("worship_group_member c SET client_id"));
    }
}
