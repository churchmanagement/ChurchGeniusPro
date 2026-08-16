package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongBookAd;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Advertisement / announcement pages for the Song Book (tenant/book-scoped). */
@Repository
public interface SongBookAdRepository extends JpaRepository<SongBookAd, Long> {

    List<SongBookAd> findByClientIdOrderBySortOrderAscIdAsc(String clientId);

    Optional<SongBookAd> findByIdAndClientId(Long id, String clientId);

    /** Purge ad pages for a (possibly book-scoped) client id. Call within a transaction. */
    void deleteByClientId(String clientId);
}
