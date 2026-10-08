package com.churchgeniuspro.integration;

import com.churchgeniuspro.config.SchemaDriftGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit C3 / P5: {@code migrate_production.sql} used to define 30 tables by hand,
 * 18 of them with NOT NULL columns the application never writes — a database the script
 * created first could not accept a single insert into them. Every CREATE TABLE block is
 * now generated from the entity model. This proves it the way it will be used: the script
 * runs first, on an empty PostgreSQL; the application then starts against it with
 * {@code ddl-auto=update}; the result must be indistinguishable from a database Hibernate
 * created on its own. Requires Docker; runs on {@code verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationScriptIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final Path SCRIPT = Path.of("scripts/sql/migrate_production.sql");
    static String firstRunErrors;

    /** Starts the database and runs the script on it BEFORE the application context exists. */
    @DynamicPropertySource
    static void scriptFirst(DynamicPropertyRegistry registry) throws Exception {
        POSTGRES.start();
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(SCRIPT), "/tmp/migrate_production.sql");
        firstRunErrors = runScript(false);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** psql inside the container; returns the ERROR lines. */
    static String runScript(boolean stopOnError) throws Exception {
        Container.ExecResult r = POSTGRES.execInContainer("psql", "-X", "-q", "-v", "ON_ERROR_STOP=" + (stopOnError ? "1" : "0"),
                "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-f", "/tmp/migrate_production.sql");
        String errors = r.getStderr().lines().filter(l -> l.contains("ERROR")).reduce("", (a, b) -> a + b + "\n");
        if (stopOnError) {
            assertThat(r.getExitCode()).as("psql exit code, stderr: %s", r.getStderr()).isZero();
        }
        return errors;
    }

    @Autowired SchemaDriftGuard driftGuard;
    @Autowired JdbcTemplate jdbc;

    static Set<String> tablesTheScriptCreates() throws IOException {
        Set<String> tables = new TreeSet<>();
        Matcher m = Pattern.compile("^CREATE TABLE IF NOT EXISTS (\\w+) \\(", Pattern.MULTILINE).matcher(Files.readString(SCRIPT));
        while (m.find()) tables.add(m.group(1));
        return tables;
    }

    @Test
    @DisplayName("on an empty database the only errors are the core-table ALTERs the script has always assumed exist — never one of its own tables")
    void scriptRunsOnAnEmptyDatabase() throws IOException {
        Set<String> created = tablesTheScriptCreates();
        assertThat(created).hasSizeGreaterThan(40);
        for (String line : firstRunErrors.split("\n")) {
            if (line.isBlank()) continue;
            assertThat(line).matches(".*relation \"\\w+\" does not exist.*");
            for (String t : created) {
                assertThat(line).as("no error may involve a table the script itself creates").doesNotContain("\"" + t + "\"");
            }
        }
    }

    @Test
    @DisplayName("script first, then ddl-auto=update: every table the script created has exactly the entity's columns — no dead column, no drift")
    void scriptFirstThenHibernateEqualsHibernateAlone() throws IOException {
        Map<String, Map<String, Boolean>> mapped = driftGuard.mappedColumns();
        Set<String> created = tablesTheScriptCreates();
        List<String> live = jdbc.queryForList("SELECT table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = ANY (?) ORDER BY 1",
                String.class, (Object) created.toArray(new String[0]));

        Set<String> expected = new TreeSet<>();
        for (String t : created) {
            assertThat(mapped).as("%s is an entity table", t).containsKey(t);
            mapped.get(t).keySet().forEach(c -> expected.add(t + "." + c));
        }
        assertThat(new TreeSet<>(live)).as("live columns of the script's tables == entity columns").isEqualTo(expected);

        // and the guard that runs at every start sees nothing to report, for any table
        List<SchemaDriftGuard.DbColumn> all = jdbc.query("SELECT table_name, column_name, is_nullable, column_default, is_identity "
                + "FROM information_schema.columns WHERE table_schema = current_schema()",
                (rs, i) -> new SchemaDriftGuard.DbColumn(rs.getString(1), rs.getString(2), "YES".equals(rs.getString(3)),
                        rs.getString(4) != null || "YES".equals(rs.getString(5))));
        assertThat(SchemaDriftGuard.diff(mapped, all)).isEmpty();
    }

    @Test
    @DisplayName("the script is idempotent: a second run on the complete database ends with exit code 0 and no ERROR")
    void secondRunIsClean() throws Exception {
        assertThat(runScript(true)).isEmpty();
    }
}
