package com.churchgeniuspro.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads one section of {@code migrate_production.sql} for the structural tests. A section
 * starts at its "Database audit …" marker and ends at the next dated section heading
 * ({@code -- 2026-09 Database audit …}) or the end of the file, so a section appended
 * later never leaks into an earlier section's assertions.
 */
final class MigrationScript {

    private MigrationScript() {}

    static String section(String marker) throws IOException {
        String script = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        int start = script.indexOf(marker);
        assertThat(start).as("'%s' section present in migrate_production.sql", marker).isPositive();
        int end = script.indexOf("\n-- 2026-09 Database audit", start + marker.length());
        return end < 0 ? script.substring(start) : script.substring(start, end);
    }
}
