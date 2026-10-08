package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Purpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link Purpose}.
 */
@Repository
public interface PurposeRepository extends JpaRepository<Purpose, Integer> {

    /** All non-deleted purposes, sorted A-Z. */
    List<Purpose> findByDeleteFlagFalseOrderByPurposeNameAsc();

    /** All non-deleted purposes, filtered by appClientId (null = no filter), sorted A-Z. */
    @Query("SELECT p FROM Purpose p WHERE p.deleteFlag = false " +
           "AND (:appClientId IS NULL OR p.appClientId = :appClientId) " +
           "ORDER BY p.purposeName ASC")
    List<Purpose> findActiveByAppUser(@Param("appClientId") String appClientId);

    /** Duplicate-check on create — scoped to the same church (appClientId). */
    boolean existsByPurposeNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(
            String purposeName, String appClientId);

    /** Duplicate-check on update — scoped to the same church, excluding the record being updated. */
    boolean existsByPurposeNameIgnoreCaseAndDeleteFlagFalseAndAppClientIdAndIdNot(
            String purposeName, String appClientId, Integer id);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<Purpose> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
}
