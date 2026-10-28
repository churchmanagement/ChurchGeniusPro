package com.churchgeniuspro.integrity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data-integrity audit 2026-10-28, H2: one LIVE signup row per client-ID.
 * <ul>
 *   <li>{@code LoginRepository.findByClientId} must exclude deleted rows (the JPQL is pinned
 *       by text — the finder returns {@code Optional} and used to throw once a deleted and a
 *       live row shared a key).</li>
 *   <li>V23 adds a partial unique index over non-deleted rows, is guarded for a fresh
 *       database, refuses to run while live duplicates exist, and is mirrored in the
 *       production script.</li>
 * </ul>
 */
@DisplayName("signup.client_id — live rows unique (V23 + finder)")
class SignupLiveClientIdTest {

    static final Path V23 = Path.of("src/main/resources/db/migration/V23__signup_live_client_id_unique.sql");

    @Test
    @DisplayName("findByClientId excludes deleted rows")
    void finderExcludesDeleted() throws Exception {
        String repo = Files.readString(Path.of("src/main/java/com/churchgeniuspro/repository/LoginRepository.java"));
        Matcher m = Pattern.compile("@Query\\(\"([^\"]*)\"\\)\\s*\\n\\s*java\\.util\\.Optional<SignUp> findByClientId\\(").matcher(repo);
        assertThat(m.find()).as("findByClientId carries an explicit JPQL").isTrue();
        assertThat(m.group(1)).contains("s.clientId = :clientId").contains("s.deleted = false OR s.deleted IS NULL");
    }

    @Test
    @DisplayName("V23 is guarded, partial over non-deleted rows, refuses live duplicates, and is mirrored in migrate_production.sql")
    void v23Shape() throws Exception {
        String sql = Files.readString(V23);
        assertThat(sql).contains("to_regclass('public.signup')");
        assertThat(sql).contains("CREATE UNIQUE INDEX IF NOT EXISTS ux_signup_live_client_id");
        assertThat(sql).contains("WHERE deleted IS NOT TRUE AND client_id IS NOT NULL");
        assertThat(sql).contains("HAVING count(*) > 1");
        assertThat(sql.indexOf("HAVING count(*) > 1")).isLessThan(sql.indexOf("CREATE UNIQUE INDEX"));
        String prod = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        assertThat(prod).contains("ux_signup_live_client_id").contains("Mirrors Flyway V23");
    }

    @Test
    @DisplayName("the index semantics on a real SQL engine: a second live row is refused, a deleted one never blocks")
    void indexSemantics() {
        // H2 has no partial indexes; the same rule is expressed as a unique index over a
        // computed column that is NULL for deleted rows (NULLs never collide) — identical
        // semantics to the PostgreSQL partial index V23 creates.
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:signupidx;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE signup (id INT PRIMARY KEY, client_id VARCHAR(100), username VARCHAR(100), deleted BOOLEAN, "
                   + "live_client_id VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN deleted IS NOT TRUE THEN client_id END))");
        jdbc.execute("CREATE UNIQUE INDEX ux_signup_live_client_id ON signup (live_client_id)");

        jdbc.update("INSERT INTO signup (id, client_id, username, deleted) VALUES (1, 'MBR-a', 'ann', false)");
        jdbc.update("UPDATE signup SET deleted = true, client_id = 'MBR-a#deleted-1' WHERE id = 1");   // what deletion now does
        jdbc.update("INSERT INTO signup (id, client_id, username, deleted) VALUES (2, 'MBR-a', 'ann2', false)");   // re-registration: fine
        jdbc.update("INSERT INTO signup (id, client_id, username, deleted) VALUES (3, 'MBR-b', 'bob', true)");     // old-style deleted row keeps its key
        jdbc.update("INSERT INTO signup (id, client_id, username, deleted) VALUES (4, 'MBR-b', 'bob2', false)");   // fine: deleted rows are outside the index

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("INSERT INTO signup (id, client_id, username, deleted) VALUES (5, 'MBR-a', 'ann3', false)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM signup WHERE client_id = 'MBR-a' AND deleted IS NOT TRUE", Integer.class)).isEqualTo(1);
    }
}
