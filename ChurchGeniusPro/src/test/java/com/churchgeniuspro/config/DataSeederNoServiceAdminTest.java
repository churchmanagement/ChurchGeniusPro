package com.churchgeniuspro.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-readiness audit 2026-10-07, Phase 4.4: DataSeeder no longer inserts a
 * placeholder-hash {@code serviceadmin} row into {@code signup} (the Service Admin
 * console authenticates against the {@code serviceadmin} table, and the INSERT named
 * a {@code role} column the table does not have, so it failed on every boot).
 */
@DisplayName("DataSeeder — no placeholder serviceadmin login is seeded")
class DataSeederNoServiceAdminTest {

    @Test
    void noSignupInsertForServiceAdmin() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/churchgeniuspro/DataSeeder.java"));
        assertThat(src).doesNotContain("seedServiceAdmin");
        assertThat(src).doesNotContain("INSERT INTO signup");
        assertThat(src).doesNotContain("$2a$10$");
    }
}
