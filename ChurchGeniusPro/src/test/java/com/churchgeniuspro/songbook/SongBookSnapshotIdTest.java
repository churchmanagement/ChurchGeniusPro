package com.churchgeniuspro.songbook;

import com.churchgeniuspro.hibernate.Song;
import com.churchgeniuspro.hibernate.SongBookPublish;
import com.churchgeniuspro.hibernate.SongSection;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.SongBookService;
import com.churchgeniuspro.service.SongExtractionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Publishing must embed each song's id in the snapshot, and a snapshot published before
 * this feature existed (no ids) must be reported as such rather than silently yielding
 * unusable entries.
 *
 * <p>Regression: without ids every parsed song had {@code songId == null}, which made the
 * admin page's "is this song selected?" test true for every row (null matched null), so
 * every button rendered as already-selected and cleared the selection instead of setting it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SongBookSnapshotIdTest {

    private static final String CID = "CHR-1";

    @Mock SongRepository songRepo;
    @Mock SongSectionRepository sectionRepo;
    @Mock FinalizedSectionRepository finalRepo;
    @Mock SongBookPublishRepository publishRepo;
    @Mock SongAuditLogRepository auditRepo;
    @Mock SongBookAssetRepository assetRepo;
    @Mock SongBookAdRepository adRepo;
    @Mock SongExtractionService extraction;

    private Song song(long id, String title) {
        Song s = new Song(); s.setId(id); s.setClientId(CID); s.setTitle(title);
        s.setLanguage("English"); s.setWorkingOrder((int) id); return s;
    }

    @Test
    void publishEmbedsSongIds() throws Exception {
        SongBookService svc = new SongBookService(songRepo, sectionRepo, finalRepo, publishRepo,
                auditRepo, assetRepo, adRepo, extraction);

        SongSection sec = new SongSection(); sec.setId(7L); sec.setClientId(CID); sec.setName("Praise");
        when(sectionRepo.findByClientIdOrderBySortOrderAsc(CID)).thenReturn(List.of(sec));
        when(songRepo.findByClientIdAndSectionIdIsNull(CID)).thenReturn(List.of());
        when(songRepo.findByClientIdAndFinalizedTrueAndFinalizedSectionIdIsNull(CID)).thenReturn(List.of());
        when(finalRepo.findByClientIdOrderBySortOrderAsc(CID)).thenReturn(List.of());
        when(songRepo.findByClientIdAndSectionId(CID, 7L))
                .thenReturn(new java.util.ArrayList<>(List.of(song(101, "Gracefully broken"), song(102, "Trust in God"))));
        when(adRepo.findByClientIdOrderBySortOrderAscIdAsc(CID)).thenReturn(List.of());
        when(publishRepo.findByClientId(CID)).thenReturn(Optional.empty());
        when(publishRepo.save(any(SongBookPublish.class))).thenAnswer(i -> i.getArgument(0));
        when(assetRepo.existsByClientIdAndKind(eq(CID), anyString())).thenReturn(false);

        SongBookPublish p = svc.publish(CID, "Song Book", "tester", "FULL");

        assertThat(p.getSnapshot()).contains("\"id\":101").contains("\"id\":102");
        List<Map<String, Object>> songs = svc.snapshotSongs(p.getSnapshot());
        assertThat(songs).hasSize(2);
        assertThat(songs).allSatisfy(m -> assertThat(m.get("songId")).isNotNull());
        assertThat(songs.get(0).get("songId")).isEqualTo(101L);
        assertThat(songs.get(1).get("songId")).isEqualTo(102L);
    }

    @Test
    void legacySnapshotWithoutIdsParsesWithNullIdsSoItCanBeDetected() {
        SongBookService svc = new SongBookService(songRepo, sectionRepo, finalRepo, publishRepo,
                auditRepo, assetRepo, adRepo, extraction);
        // A snapshot written before song ids were embedded.
        String legacy = "{\"bookTitle\":\"B\",\"sections\":[{\"name\":\"Praise\",\"songs\":["
                + "{\"title\":\"Gracefully broken\",\"language\":\"English\",\"lyricsHtml\":\"\"},"
                + "{\"title\":\"Trust in God\",\"language\":\"English\",\"lyricsHtml\":\"\"}]}]}";
        List<Map<String, Object>> songs = svc.snapshotSongs(legacy);
        assertThat(songs).hasSize(2);
        assertThat(songs).allSatisfy(m -> assertThat(m.get("songId")).isNull());
        // titles still resolve, so the page can list them and explain a re-publish is needed
        assertThat(songs.get(0).get("title")).isEqualTo("Gracefully broken");
    }
}
