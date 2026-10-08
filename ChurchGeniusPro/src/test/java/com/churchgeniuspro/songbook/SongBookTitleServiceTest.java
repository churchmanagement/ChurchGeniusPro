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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Saving the Finalized Song Book title must never publish or alter the live snapshot. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Song Book — Finalized Song Book title")
class SongBookTitleServiceTest {

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

    @BeforeEach
    void setUp() {
        svc = new SongBookService(songRepo, sectionRepo, finalRepo, publishRepo, auditRepo,
                assetRepo, adRepo, extraction);
        when(publishRepo.save(any(SongBookPublish.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("unpublished book: creates the row with a token but stays unpublished")
    void savesTitleWithoutPublishing() {
        when(publishRepo.findByClientId(CID)).thenReturn(Optional.empty());
        SongBookPublish p = svc.saveBookTitle(CID, "Christmas Carols", "u", "Admin");
        assertThat(p.getBookTitle()).isEqualTo("Christmas Carols");
        assertThat(p.isPublished()).isFalse();
        assertThat(p.getSnapshot()).isNull();
        assertThat(p.getToken()).isNotBlank();
        assertThat(p.getClientId()).isEqualTo(CID);
    }

    @Test
    @DisplayName("published book: title changes, live snapshot and token untouched")
    void keepsLiveSnapshot() {
        SongBookPublish existing = new SongBookPublish();
        existing.setClientId(CID);
        existing.setToken("tok");
        existing.setPublished(true);
        existing.setSnapshot("{\"bookTitle\":\"Old\",\"sections\":[]}");
        existing.setBookTitle("Old");
        when(publishRepo.findByClientId(CID)).thenReturn(Optional.of(existing));

        SongBookPublish p = svc.saveBookTitle(CID, "New Title", "u", "Admin");
        assertThat(p.getBookTitle()).isEqualTo("New Title");
        assertThat(p.isPublished()).isTrue();
        assertThat(p.getToken()).isEqualTo("tok");
        assertThat(p.getSnapshot()).isEqualTo("{\"bookTitle\":\"Old\",\"sections\":[]}");
    }
}
