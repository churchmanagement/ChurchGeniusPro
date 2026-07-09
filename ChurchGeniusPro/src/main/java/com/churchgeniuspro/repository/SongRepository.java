package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Song;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Songs in a church's working/finalized Song Book (tenant-scoped). */
@Repository
public interface SongRepository extends JpaRepository<Song, Long> {

    List<Song> findByClientIdAndFinalizedFalseOrderByWorkingOrderAsc(String clientId);

    List<Song> findByClientIdAndFinalizedTrueOrderByFinalizedOrderAsc(String clientId);

    List<Song> findByClientIdOrderByWorkingOrderAsc(String clientId);

    Optional<Song> findByIdAndClientId(Long id, String clientId);

    long countByClientIdAndFinalizedTrue(String clientId);

    /* ── section-aware ── */

    List<Song> findByClientIdAndSectionIdAndFinalizedFalseOrderByWorkingOrderAsc(String clientId, Long sectionId);

    List<Song> findByClientIdAndSectionId(String clientId, Long sectionId);

    List<Song> findByClientIdAndSectionIdIsNull(String clientId);

    long countByClientIdAndSectionIdAndFinalizedFalse(String clientId, Long sectionId);

    /* ── finalized sections ── */

    List<Song> findByClientIdAndFinalizedSectionIdOrderByFinalizedOrderAsc(String clientId, Long finalizedSectionId);

    List<Song> findByClientIdAndFinalizedSectionId(String clientId, Long finalizedSectionId);

    List<Song> findByClientIdAndFinalizedTrueAndFinalizedSectionIdIsNull(String clientId);

    long countByClientIdAndFinalizedSectionId(String clientId, Long finalizedSectionId);
}
