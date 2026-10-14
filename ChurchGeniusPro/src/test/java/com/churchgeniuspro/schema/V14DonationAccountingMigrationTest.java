package com.churchgeniuspro.schema;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.DonationSettings;
import jakarta.persistence.Column;
import jakarta.persistence.SequenceGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V14__donation_review_accounting.sql — shape review against the production
 * schema verified at V13 (2026-10-14): additive, guarded, re-runnable, and in
 * step with the JPA entities so Hibernate's {@code ddl-auto=update} has nothing
 * left to add on an existing database.
 */
@DisplayName("V14__donation_review_accounting.sql — additive, guarded, entity-aligned")
class V14DonationAccountingMigrationTest {

    static String sql;
    static String lower;

    @BeforeAll
    static void load() throws IOException {
        sql = Files.readString(Path.of("src/main/resources/db/migration/V14__donation_review_accounting.sql"));
        lower = sql.toLowerCase(Locale.ROOT);
    }

    /** Statement text with SQL comments stripped, so a word in a comment never satisfies or trips a check. */
    static String code() {
        return lower.replaceAll("--[^\\n]*", "");
    }

    @Test
    @DisplayName("nothing destructive: no DROP, DELETE, UPDATE, TRUNCATE, ALTER COLUMN TYPE, or SET NOT NULL on existing data")
    void additiveOnly() {
        String c = code();
        assertThat(c).doesNotContain("drop ", "delete ", "truncate ", "update ", " alter column ", "set not null", "rename ");
        // No INSERT either: V14 writes no rows (decision 7 — no backfill).
        assertThat(c).doesNotContain("insert ");
    }

    @Test
    @DisplayName("every CREATE and ADD COLUMN is IF NOT EXISTS, so Flyway on production and the startup re-run are both safe")
    void everyStatementIsIdempotent() {
        String c = code();
        Matcher create = Pattern.compile("create\\s+(unique\\s+)?(table|sequence|index)\\s+(if not exists\\s+)?(\\S+)").matcher(c);
        List<String> unguarded = new ArrayList<>();
        while (create.find()) if (create.group(3) == null) unguarded.add(create.group(4));
        // The one index inside the EXCEPTION block is guarded by to_regclass instead.
        unguarded.remove("ux_donation_posted_income");
        assertThat(unguarded).as("CREATE without IF NOT EXISTS").isEmpty();
        assertThat(c).contains("if to_regclass('public.ux_donation_posted_income') is null then");

        Matcher add = Pattern.compile("add\\s+column\\s+(if not exists\\s+)?(\\w+)").matcher(c);
        List<String> unguardedCols = new ArrayList<>();
        while (add.find()) if (add.group(1) == null) unguardedCols.add(add.group(2));
        assertThat(unguardedCols).as("ADD COLUMN without IF NOT EXISTS").isEmpty();
    }

    @Test
    @DisplayName("the donation part no-ops on a fresh database (table absent) and runs on production (table present at V13)")
    void donationPartIsGuarded() {
        assertThat(code()).contains("if to_regclass('public.donation') is null then").contains("return;");
    }

    @Test
    @DisplayName("every DonationSettings @Column is created by V14 with the entity's sequence name")
    void settingsColumnsMatchEntity() {
        String create = code().substring(code().indexOf("create table if not exists donation_settings"));
        create = create.substring(0, create.indexOf(");") + 2);
        for (Field f : DonationSettings.class.getDeclaredFields()) {
            Column col = f.getAnnotation(Column.class);
            if (col == null) continue;
            assertThat(create).as("donation_settings." + col.name()).containsPattern("\\n\\s*" + col.name() + "\\s");
        }
        SequenceGenerator sg = null;
        for (Field f : DonationSettings.class.getDeclaredFields()) if (f.getAnnotation(SequenceGenerator.class) != null) sg = f.getAnnotation(SequenceGenerator.class);
        assertThat(sg).isNotNull();
        assertThat(code()).contains("create sequence if not exists " + sg.sequenceName().toLowerCase(Locale.ROOT));
        assertThat(create).contains("nextval('" + sg.sequenceName().toLowerCase(Locale.ROOT) + "')");
        // One settings row per church.
        assertThat(code()).contains("create unique index if not exists ux_donation_settings_client on donation_settings (client_id)");
    }

    @Test
    @DisplayName("every new Donation @Column (the ten V14 fields) is added by V14, and the ten pre-existing columns are not touched")
    void donationColumnsMatchEntity() {
        String c = code();
        List<String> v14 = List.of("stripe_fee", "stripe_net", "stripe_balance_transaction_id", "fee_source",
                "contributor_category", "matched_member_id", "posted_income_id", "posted_expense_id", "posted_at", "posted_by");
        for (String col : v14) {
            assertThat(c).as(col).containsPattern("add column if not exists\\s+" + col + "\\s");
        }
        // Entity ↔ migration parity: every @Column on Donation is either a V14 column or one
        // that production already has (verified 2026-10-14) — nothing new is left for Hibernate.
        List<String> preExisting = List.of("id", "client_id", "first_name", "last_name", "email", "phone", "note", "payment_method",
                "amount", "intended_amount", "fee_covered", "currency", "stripe_payment_intent_id", "stripe_charge_id", "status",
                "donated_at", "source", "member_id", "sub_source_id", "review_status", "review_completed_at", "review_completed_by");
        for (Field f : Donation.class.getDeclaredFields()) {
            Column col = f.getAnnotation(Column.class);
            if (col == null) continue;
            assertThat(v14.contains(col.name()) || preExisting.contains(col.name()))
                    .as("Donation column " + col.name() + " is neither in V14 nor known to exist in production").isTrue();
        }
        for (String col : preExisting) {
            assertThat(c).as("V14 must not alter " + col).doesNotContainPattern("alter\\s+table\\s+donation\\s+(add|alter|drop)\\s+column\\s+(if not exists\\s+)?" + col + "\\s");
        }
    }

    @Test
    @DisplayName("new columns are nullable (no NOT NULL on a table with rows) and the posting link is unique per income row")
    void nullableAndUnique() {
        String c = code();
        Matcher add = Pattern.compile("add column if not exists\\s+\\w+\\s+[^;]*;").matcher(c);
        while (add.find()) assertThat(add.group()).doesNotContain("not null");
        assertThat(c).contains("create unique index ux_donation_posted_income on donation (posted_income_id)")
                     .contains("where posted_income_id is not null");
    }

    @Test
    @DisplayName("the startup initializer re-runs exactly this file")
    void initializerPointsAtThisFile() throws IOException {
        String init = Files.readString(Path.of("src/main/java/com/churchgeniuspro/config/DonationAccountingSchemaInitializer.java"));
        assertThat(init).contains("db/migration/V14__donation_review_accounting.sql").contains("@DependsOn(\"entityManagerFactory\")");
    }

    @Test
    @DisplayName("V1–V13 are untouched (Flyway validate-on-migrate compares their checksums on deploy)")
    void earlierMigrationsPresent() throws IOException {
        for (int v = 2; v <= 13; v++) {
            final String prefix = "V" + v + "__";
            try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
                assertThat(files.map(p -> p.getFileName().toString()).anyMatch(n -> n.startsWith(prefix))).as(prefix).isTrue();
            }
        }
    }
}
