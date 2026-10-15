package com.churchgeniuspro.schema;

import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.MembershipFamily;
import jakarta.persistence.Column;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15__membership_request_status.sql — shape review against production (verified at V14,
 * 2026-10-14: membership_family has exactly id, app_client_id, created_date,
 * declaration_accepted, delete_flag, inactive, existing_family_id, signup_id).
 */
@DisplayName("V15__membership_request_status.sql — additive, guarded, idempotent, entity-aligned")
class V15MembershipRequestStatusMigrationTest {

    static String sql, code;

    @BeforeAll
    static void load() throws IOException {
        sql = Files.readString(Path.of("src/main/resources/db/migration/V15__membership_request_status.sql"));
        code = sql.toLowerCase(Locale.ROOT).replaceAll("--[^\\n]*", "");
    }

    @Test
    @DisplayName("nothing destructive; the only data write is the guarded status backfill of already-soft-deleted requests")
    void additiveOnly() {
        assertThat(code).doesNotContain("drop ", "delete ", "truncate ", " alter column ", "rename ", "insert ");
        Matcher upd = Pattern.compile("update\\s+(\\w+)").matcher(code);
        int n = 0;
        while (upd.find()) { n++; assertThat(upd.group(1)).isEqualTo("membership_family"); }
        assertThat(n).as("exactly one UPDATE, on membership_family").isEqualTo(1);
        // family / family_member rows are never changed by the backfill
        assertThat(code).doesNotContain("update family ", "update family_member");
        // backfill guard: only old soft-deleted rows still at the default, never rows deleted by staff after V15
        assertThat(code).contains("where delete_flag = true").contains("and status = 'pending_review'").contains("and deleted_at is null");
        assertThat(code).contains("set status = 'updated'");
    }

    @Test
    @DisplayName("every CREATE / ADD COLUMN is IF NOT EXISTS; the block no-ops on a fresh database")
    void idempotent() {
        Matcher add = Pattern.compile("add\\s+column\\s+(if not exists\\s+)?(\\w+)").matcher(code);
        while (add.find()) assertThat(add.group(1)).as("ADD COLUMN " + add.group(2) + " without IF NOT EXISTS").isNotNull();
        Matcher create = Pattern.compile("create\\s+(unique\\s+)?(table|sequence|index)\\s+(if not exists\\s+)?(\\S+)").matcher(code);
        while (create.find()) assertThat(create.group(3)).as("CREATE " + create.group(4)).isNotNull();
        assertThat(code).contains("if to_regclass('public.membership_family') is null or to_regclass('public.family') is null then").contains("return;");
    }

    @Test
    @DisplayName("the NOT NULL status column carries a DEFAULT (table has rows) and the entity mirrors it with @ColumnDefault")
    void statusDefault() throws Exception {
        assertThat(code).containsPattern("add column if not exists status\\s+varchar\\(24\\)\\s+not null default 'pending_review'");
        Field f = MembershipFamily.class.getDeclaredField("status");
        assertThat(f.getAnnotation(org.hibernate.annotations.ColumnDefault.class).value()).isEqualTo("'PENDING_REVIEW'");
        assertThat(f.getAnnotation(Column.class).nullable()).isFalse();
        // every other new column is nullable
        Matcher add = Pattern.compile("add column if not exists\\s+(\\w+)\\s+[^;]*;").matcher(code);
        while (add.find()) if (!add.group(1).equals("status")) assertThat(add.group()).as(add.group(1)).doesNotContain("not null");
    }

    @Test
    @DisplayName("every new @Column on MembershipFamily and Family.membership_renewed_at is created by V15; pre-existing columns untouched")
    void entityAlignment() {
        List<String> v15 = List.of("status", "status_changed_at", "status_changed_by", "applied_at", "applied_by", "applied_family_id", "deleted_at", "deleted_by");
        List<String> preExisting = List.of("id", "inactive", "existing_family_id", "declaration_accepted", "app_client_id", "signup_id", "delete_flag", "created_date");
        for (Field f : MembershipFamily.class.getDeclaredFields()) {
            Column c = f.getAnnotation(Column.class);
            if (c == null) continue;
            assertThat(v15.contains(c.name()) || preExisting.contains(c.name())).as("membership_family." + c.name() + " neither in V15 nor known in production").isTrue();
            if (v15.contains(c.name())) assertThat(code).containsPattern("alter table membership_family add column if not exists\\s+" + c.name() + "\\s");
        }
        for (String col : preExisting) assertThat(code).doesNotContainPattern("alter\\s+table\\s+membership_family\\s+(add|alter|drop)\\s+column\\s+(if not exists\\s+)?" + col + "\\s");
        assertThat(code).contains("alter table family add column if not exists membership_renewed_at timestamp");
        boolean found = false;
        for (Field f : Family.class.getDeclaredFields()) { Column c = f.getAnnotation(Column.class); if (c != null && c.name().equals("membership_renewed_at")) found = true; }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("the startup initializer re-runs exactly this file; V1–V14 are untouched")
    void initializerAndHistory() throws IOException {
        String init = Files.readString(Path.of("src/main/java/com/churchgeniuspro/config/MembershipRequestSchemaInitializer.java"));
        assertThat(init).contains("db/migration/V15__membership_request_status.sql").contains("@DependsOn(\"entityManagerFactory\")");
        for (int v = 2; v <= 14; v++) {
            final String prefix = "V" + v + "__";
            try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
                assertThat(files.map(p -> p.getFileName().toString()).anyMatch(n -> n.startsWith(prefix))).as(prefix).isTrue();
            }
        }
    }
}
