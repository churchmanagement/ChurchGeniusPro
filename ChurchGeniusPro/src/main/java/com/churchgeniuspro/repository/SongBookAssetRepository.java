package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongBookAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Uploaded custom cover / last page images for the Song Book (tenant-scoped). */
@Repository
public interface SongBookAssetRepository extends JpaRepository<SongBookAsset, Long> {

    Optional<SongBookAsset> findByClientIdAndKind(String clientId, String kind);

    boolean existsByClientIdAndKind(String clientId, String kind);

    /** Purge uploaded pages for a (possibly book-scoped) client id. Call within a transaction. */
    void deleteByClientId(String clientId);
}
