package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Song Book sections / categories (tenant-scoped). */
@Repository
public interface SongSectionRepository extends JpaRepository<SongSection, Long> {

    List<SongSection> findByClientIdOrderBySortOrderAsc(String clientId);

    Optional<SongSection> findByIdAndClientId(Long id, String clientId);

    long countByClientId(String clientId);

    /** Purge all sections for a (possibly book-scoped) client id. Call within a transaction. */
    void deleteByClientId(String clientId);
}
