package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.Song;
import com.churchgeniuspro.hibernate.SongBookPublish;
import com.churchgeniuspro.repository.SongBookPublishRepository;
import com.churchgeniuspro.repository.SongRepository;
import com.churchgeniuspro.service.SongBookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end "Now Singing" / "Next Song" behaviour against a real PostgreSQL
 * database with two tenants — proving tenant isolation, resolution by public
 * token, and re-validation of the selections across a republish.
 *
 * <p>Requires Docker; auto-skipped when absent, runs on {@code verify} in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Song Book — Now Singing / Next Song (two tenants, real DB)")
class SongBookLiveStateIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String A = "CGP-SBA";
    private static final String B = "CGP-SBB";

    @Autowired SongBookService svc;
    @Autowired SongRepository songRepo;
    @Autowired SongBookPublishRepository publishRepo;

    private Song song(String clientId, String title) {
        Song s = new Song();
        s.setClientId(clientId);
        s.setTitle(title);
        s.setLanguage("English");
        s.setWorkingOrder(0);
        s.setFinalized(false);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return songRepo.save(s);
    }

    @Test
    @DisplayName("selections are tenant-isolated, resolvable by token, and re-validated on republish")
    void tenantIsolationAndLifecycle() throws Exception {
        Song a1 = song(A, "A One");
        Song a2 = song(A, "A Two");
        Song b1 = song(B, "B One");

        SongBookPublish pa = svc.publish(A, "Book A", "tester", "FULL");
        svc.publish(B, "Book B", "tester", "FULL");

        // Song ids are embedded in each book's snapshot.
        assertThat(svc.snapshotSongs(pa.getSnapshot()))
                .extracting(m -> m.get("songId")).contains(a1.getId(), a2.getId());

        // ── Set Current + Next on tenant A ───────────────────────────────────
        svc.setCurrentSong(A, a1.getId(), "t", "FULL");
        svc.setNextSong(A, a2.getId(), "t", "FULL");
        Map<String, Object> lsA = svc.liveState(publishRepo.findByClientId(A).orElseThrow());
        assertThat(((Map<?, ?>) lsA.get("current")).get("title")).isEqualTo("A One");
        assertThat(((Map<?, ?>) lsA.get("next")).get("title")).isEqualTo("A Two");

        // ── Tenant B is completely unaffected ────────────────────────────────
        Map<String, Object> lsB = svc.liveState(publishRepo.findByClientId(B).orElseThrow());
        assertThat(lsB.get("current")).isNull();
        assertThat(lsB.get("next")).isNull();

        // ── Tenant isolation: neither church can point at the other's song ───
        assertThatThrownBy(() -> svc.setCurrentSong(A, b1.getId(), "t", "FULL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.setCurrentSong(B, a1.getId(), "t", "FULL"))
                .isInstanceOf(IllegalArgumentException.class);
        // A's selection is unchanged by the rejected attempt.
        assertThat(publishRepo.findByClientId(A).orElseThrow().getCurrentSongId()).isEqualTo(a1.getId());

        // ── Public-by-token resolves to the right book + live state ──────────
        SongBookPublish byToken = svc.publicByToken(pa.getToken());
        assertThat(byToken).isNotNull();
        assertThat(byToken.getClientId()).isEqualTo(A);
        assertThat(((Map<?, ?>) svc.liveState(byToken).get("current")).get("songId")).isEqualTo(a1.getId());

        // ── Republish re-validation: delete the Current song, republish ──────
        songRepo.delete(a1);
        SongBookPublish pa2 = svc.publish(A, "Book A", "tester", "FULL");
        assertThat(pa2.getCurrentSongId()).as("removed song is dropped from Current").isNull();
        assertThat(pa2.getNextSongId()).as("still-present song is preserved as Next").isEqualTo(a2.getId());

        // ── Unset Next ───────────────────────────────────────────────────────
        svc.clearNextSong(A, "t", "FULL");
        assertThat(publishRepo.findByClientId(A).orElseThrow().getNextSongId()).isNull();
    }
}
