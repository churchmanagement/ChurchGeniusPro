package com.churchgeniuspro.songbook;

import com.churchgeniuspro.hibernate.SongBookPublish;
import com.churchgeniuspro.repository.FinalizedSectionRepository;
import com.churchgeniuspro.repository.SongAuditLogRepository;
import com.churchgeniuspro.repository.SongBookAdRepository;
import com.churchgeniuspro.repository.SongBookAssetRepository;
import com.churchgeniuspro.repository.SongBookPublishRepository;
import com.churchgeniuspro.repository.SongRepository;
import com.churchgeniuspro.repository.SongSectionRepository;
import com.churchgeniuspro.service.SongBookService;
import com.churchgeniuspro.service.SongExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the "Now Singing" / "Next Song" selection logic on
 * {@link SongBookService}. Repositories are mocked; the service parses a real
 * published-snapshot JSON string (its own ObjectMapper), so this exercises the
 * real validation, versioning and title resolution without a database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Song Book — Now Singing / Next Song selection logic")
class SongBookLiveStateServiceTest {

    private static final String CID = "CHR-1";

    @Mock SongRepository songRepo;
    @Mock SongSectionRepository sectionRepo;
    @Mock FinalizedSectionRepository finalRepo;
    @Mock SongBookPublishRepository publishRepo;
    @Mock SongAuditLogRepository auditRepo;
    @Mock SongBookAssetRepository assetRepo;
    @Mock SongBookAdRepository adRepo;
    @Mock SongExtractionService extraction;

    SongBookService svc;

    /** Snapshot with songs 10 & 11 (Praise) and 20 (Worship). */
    private static final String SNAP =
        "{\"bookTitle\":\"Book\",\"sections\":[" +
          "{\"name\":\"Praise\",\"songs\":[" +
            "{\"id\":10,\"title\":\"Amazing Grace\",\"language\":\"English\",\"lyricsHtml\":\"\"}," +
            "{\"id\":11,\"title\":\"How Great Thou Art\",\"language\":\"English\",\"lyricsHtml\":\"\"}]}," +
          "{\"name\":\"Worship\",\"songs\":[" +
            "{\"id\":20,\"title\":\"Here I Am\",\"language\":\"English\",\"lyricsHtml\":\"\"}]}]}";

    @BeforeEach
    void setUp() {
        svc = new SongBookService(songRepo, sectionRepo, finalRepo, publishRepo, auditRepo,
                assetRepo, adRepo, extraction);
        when(publishRepo.save(any(SongBookPublish.class))).thenAnswer(i -> i.getArgument(0));
        when(songRepo.findByIdAndClientId(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    private SongBookPublish published() {
        SongBookPublish p = new SongBookPublish();
        p.setClientId(CID);
        p.setToken("tok");
        p.setPublished(true);
        p.setSnapshot(SNAP);
        return p;
    }

    private void havePublish(SongBookPublish p) {
        when(publishRepo.findByClientId(CID)).thenReturn(Optional.ofNullable(p));
    }

    /* ── set / replace / only-one ─────────────────────────────────────────── */

    @Test
    @DisplayName("setting a Current Song stores it and bumps the version to 1")
    void setCurrent() {
        SongBookPublish p = published();
        havePublish(p);
        svc.setCurrentSong(CID, 10L, "u", "FULL");
        assertThat(p.getCurrentSongId()).isEqualTo(10L);
        assertThat(p.getCurrentSongVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("only one Current Song at a time — a new choice replaces the old and bumps the version")
    void setCurrentReplaces() {
        SongBookPublish p = published();
        p.setCurrentSongId(10L);
        p.setCurrentSongVersion(1L);
        havePublish(p);
        svc.setCurrentSong(CID, 11L, "u", "FULL");
        assertThat(p.getCurrentSongId()).isEqualTo(11L);
        assertThat(p.getCurrentSongVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("unsetting the Current Song clears it and bumps the version")
    void clearCurrent() {
        SongBookPublish p = published();
        p.setCurrentSongId(10L);
        p.setCurrentSongVersion(1L);
        havePublish(p);
        svc.clearCurrentSong(CID, "u", "FULL");
        assertThat(p.getCurrentSongId()).isNull();
        assertThat(p.getCurrentSongVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("only one Next Song at a time; Next is independent of Current and does not bump the current version")
    void nextIndependentOfCurrent() {
        SongBookPublish p = published();
        havePublish(p);
        svc.setCurrentSong(CID, 10L, "u", "FULL");   // version → 1
        svc.setNextSong(CID, 11L, "u", "FULL");
        svc.setNextSong(CID, 20L, "u", "FULL");      // replaces next
        assertThat(p.getCurrentSongId()).isEqualTo(10L);
        assertThat(p.getNextSongId()).isEqualTo(20L);
        assertThat(p.getCurrentSongVersion()).isEqualTo(1L);   // unchanged by next-song edits
        svc.clearNextSong(CID, "u", "FULL");
        assertThat(p.getNextSongId()).isNull();
        assertThat(p.getCurrentSongId()).isEqualTo(10L);        // clearing next never touches current
    }

    /* ── validation ───────────────────────────────────────────────────────── */

    @Test
    @DisplayName("a song that is not in the published snapshot is rejected")
    void rejectSongNotInSnapshot() {
        havePublish(published());
        assertThatThrownBy(() -> svc.setCurrentSong(CID, 999L, "u", "FULL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.setNextSong(CID, 999L, "u", "FULL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("choosing a song before the book is published is rejected")
    void rejectWhenNotPublished() {
        SongBookPublish p = published();
        p.setPublished(false);
        havePublish(p);
        assertThatThrownBy(() -> svc.setCurrentSong(CID, 10L, "u", "FULL"))
                .isInstanceOf(IllegalStateException.class);
        // no publish row at all
        when(publishRepo.findByClientId(CID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.setNextSong(CID, 10L, "u", "FULL"))
                .isInstanceOf(IllegalStateException.class);
    }

    /* ── live state resolution ────────────────────────────────────────────── */

    @Test
    @DisplayName("live state resolves current/next ids to their titles from the snapshot")
    @SuppressWarnings("unchecked")
    void liveStateResolvesTitles() {
        SongBookPublish p = published();
        p.setCurrentSongId(10L);
        p.setNextSongId(20L);
        p.setCurrentSongVersion(3L);
        Map<String, Object> ls = svc.liveState(p);
        assertThat(ls.get("currentVersion")).isEqualTo(3L);
        Map<String, Object> cur = (Map<String, Object>) ls.get("current");
        Map<String, Object> nxt = (Map<String, Object>) ls.get("next");
        assertThat(cur).containsEntry("songId", 10L).containsEntry("title", "Amazing Grace");
        assertThat(nxt).containsEntry("songId", 20L).containsEntry("title", "Here I Am");
    }

    @Test
    @DisplayName("a stored id that is no longer in the snapshot resolves to null (treated as unset)")
    void liveStateStaleIdIsNull() {
        SongBookPublish p = published();
        p.setCurrentSongId(999L);   // not in snapshot
        Map<String, Object> ls = svc.liveState(p);
        assertThat(ls.get("current")).isNull();
        assertThat(ls.get("next")).isNull();
    }

    @Test
    @DisplayName("snapshotSongs parses ids, titles, sections and positions in book order")
    void snapshotSongsParses() {
        var songs = svc.snapshotSongs(SNAP);
        assertThat(songs).hasSize(3);
        assertThat(songs.get(0)).containsEntry("songId", 10L).containsEntry("title", "Amazing Grace")
                .containsEntry("section", "Praise").containsEntry("sectionIndex", 0).containsEntry("songIndex", 0);
        assertThat(songs.get(2)).containsEntry("songId", 20L).containsEntry("section", "Worship")
                .containsEntry("sectionIndex", 1).containsEntry("songIndex", 0);
    }
}
