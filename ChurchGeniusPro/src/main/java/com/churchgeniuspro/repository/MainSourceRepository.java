package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MainSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link MainSource}.
 */
@Repository
public interface MainSourceRepository extends JpaRepository<MainSource, Integer> {

    /** All non-deleted main sources, sorted A-Z. */
    List<MainSource> findByDeleteFlagFalseOrderBySourceNameAsc();

    /** All non-deleted main sources, filtered by appClientId (null = no filter), sorted A-Z. */
    @Query("SELECT ms FROM MainSource ms WHERE ms.deleteFlag = false " +
           "AND (:appClientId IS NULL OR ms.appClientId = :appClientId) " +
           "ORDER BY ms.sourceName ASC")
    List<MainSource> findActiveByAppUser(@Param("appClientId") String appClientId);

    /** Duplicate-check on create — scoped to the same church (appClientId). */
    boolean existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(
            String sourceName, String appClientId);

    /** Duplicate-check on update — scoped to the same church, excluding the record being updated. */
    boolean existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientIdAndIdNot(
            String sourceName, String appClientId, Integer id);

    /**
     * Finds an active main source by exact name (case-insensitive), scoped to
     * a church. Used to find-or-create the default "Online Giving" category
     * a posted donation is filed under. Financial audit H9.
     */
    Optional<MainSource> findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(
            String sourceName, String appClientId);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<MainSource> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
}
