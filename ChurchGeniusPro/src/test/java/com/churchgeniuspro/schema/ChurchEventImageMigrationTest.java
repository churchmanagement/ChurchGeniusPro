package com.churchgeniuspro.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit P7: the flyer externalisation runs as two migrations so the original data is
 * never deleted until the copy is verified — V3 copies but keeps {@code church_event.image_data};
 * the manual drop step runs only after proving the copy is complete and identical. Verified against PostgreSQL 16
 * (production-shaped church_event with a real flyer, a NULL and a blank: the flyer copied, the
 * others skipped, image_present set, column kept by V3; the drop step removed it after verification and
 * aborted when a copy was missing). This pins the shape so a hand-edit cannot lose the guards.
 */
@DisplayName("V3/V4 — church_event flyer externalisation, copy-then-verified-drop (DB audit P7)")
class ChurchEventImageMigrationTest {

    private static String v3;
    private static String v4;

    @BeforeAll
    static void load() throws IOException {
        v3 = Files.readString(Path.of("src/main/resources/db/migration/V3__p7_church_event_image.sql"));
        v4 = Files.readString(Path.of("src/main/resources/db/window/P7_drop_church_event_image_data.sql"));
    }

    @Test
    @DisplayName("both guarded for a fresh database (church_event / image_data absent) so they no-op before Hibernate")
    void guardedWhenAbsent() {
        assertThat(v3).contains("IF to_regclass('public.church_event') IS NULL THEN");
        assertThat(v4).contains("church_event.image_data already absent");
    }

    @Test
    @DisplayName("V3 creates the table and presence flag, the flag addable to a populated table")
    void v3CreatesTableAndFlag() {
        assertThat(v3).contains("CREATE TABLE IF NOT EXISTS church_event_image");
        assertThat(v3).contains("CONSTRAINT uq_church_event_image_event UNIQUE (event_id)");
        assertThat(v3).contains("ADD COLUMN IF NOT EXISTS image_present boolean NOT NULL DEFAULT false");
    }

    @Test
    @DisplayName("V3 copies only non-empty flyers, once, sets the flag, and does NOT drop the source column")
    void v3CopiesButKeeps() {
        assertThat(v3).contains("WHERE e.image_data IS NOT NULL AND e.image_data <> ''");
        assertThat(v3).contains("NOT EXISTS (SELECT 1 FROM church_event_image i WHERE i.event_id = e.id)");
        int copy = v3.indexOf("INSERT INTO church_event_image");
        int flag = v3.indexOf("SET image_present = EXISTS");
        assertThat(copy).isPositive();
        assertThat(flag).isGreaterThan(copy);
        assertThat(v3).doesNotContain("DROP COLUMN image_data");   // V4's job, after verification
    }

    @Test
    @DisplayName("the manual drop step removes the column only after proving every image is copied and identical; aborts otherwise")
    void v4DropsOnlyAfterVerifiedCopy() {
        // proves completeness and equality before the drop
        int uncopied = v4.indexOf("v_uncopied > 0");
        int mismatch = v4.indexOf("v_mismatch > 0");
        int drop = v4.indexOf("DROP COLUMN image_data");
        assertThat(uncopied).isPositive();
        assertThat(mismatch).isGreaterThan(uncopied);
        assertThat(drop).isGreaterThan(mismatch);
        assertThat(v4).contains("i.image_data IS DISTINCT FROM e.image_data");
        assertThat(v4).contains("RAISE EXCEPTION 'P7 drop:");   // aborts rather than dropping on a bad copy
    }
}
