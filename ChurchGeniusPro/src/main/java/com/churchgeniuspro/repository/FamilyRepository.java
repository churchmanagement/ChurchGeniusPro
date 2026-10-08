package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Family;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// Note: pagination for findFamilyListProjection is done in the service layer
// (Java-side) because status/name filtering happens there too.  A raw SQL
// LIMIT/OFFSET before filtering would give wrong counts.

/**
 * Spring Data JPA repository for {@link Family} entities.
 *
 * <p>The {@code family_name} column has been removed from the {@code family}
 * table — the display name is now derived at the service layer from the primary
 * member's first / last name.  All queries that previously filtered or sorted by
 * {@code f.familyName} have been updated accordingly; name-based search is
 * now performed in Java after loading the member collection.
 *
 * <p>Status filtering (active / inactive / deleted) is intentionally kept in
 * the service layer so the predicate logic stays readable in Java rather than
 * being buried in JPQL.  Both list queries use {@code LEFT JOIN FETCH} with
 * {@code DISTINCT} to load the members collection in one round-trip and prevent
 * duplicate parent rows from the join.
 */
@Repository
public interface FamilyRepository extends JpaRepository<Family, Integer> {

    /**
     * Returns all families with their member collections, filtered by appClientId.
     * Name-based search is applied by the caller in Java (after the primary member
     * is identified from the loaded collection).
     * Pass {@code null} appClientId to skip that filter.
     */
    @Query("SELECT DISTINCT f FROM Family f LEFT JOIN FETCH f.members " +
           "WHERE (:appClientId IS NULL OR f.appClientId = :appClientId)")
    List<Family> findBySearchAndAppUserOrderByName(
            @Param("search")      String search,
            @Param("appClientId") String appClientId);

    /**
     * Fetches a single family together with its members collection in one query.
     * Used by the detail / view-mode / edit-mode endpoints.
     * DISTINCT prevents Spring Data from receiving multiple identical Family rows
     * when the LEFT JOIN produces one SQL row per member.
     */
    @Query("SELECT DISTINCT f FROM Family f LEFT JOIN FETCH f.members WHERE f.id = :id")
    Optional<Family> findByIdWithMembers(@Param("id") Integer id);

    /** Tenant-scoped variant of {@link #findByIdWithMembers} for the session-bound detail endpoint. */
    @Query("SELECT DISTINCT f FROM Family f LEFT JOIN FETCH f.members " +
           "WHERE f.id = :id AND f.appClientId = :appClientId")
    Optional<Family> findByIdAndAppClientIdWithMembers(@Param("id") Integer id,
                                                       @Param("appClientId") String appClientId);

    /** Tenant-scoped lookup regardless of delete flag (restore / soft-delete / inactive toggles). */
    Optional<Family> findByIdAndAppClientId(Integer id, String appClientId);

    /**
     * Returns all non-deleted, non-inactive families with their members eager-loaded, filtered by appClientId.
     * Members are needed to derive the display name and address from the primary member.
     */
    @Query("SELECT DISTINCT f FROM Family f LEFT JOIN FETCH f.members " +
           "WHERE f.deleteFlag = false AND f.inactive = false " +
           "AND (:appClientId IS NULL OR f.appClientId = :appClientId) " +
           "ORDER BY f.id ASC")
    List<Family> findAllActiveForLocationByAppUser(@Param("appClientId") String appClientId);

    Optional<Family> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);

    /**
     * Lightweight list query for /viewfamily: loads all family + member scalar columns
     * but explicitly EXCLUDES photo_data to keep the result set small.
     * Returns one row per family member (caller groups by family id).
     */
    @Query(value =
        "SELECT f.id AS familyId, f.inactive AS inactive, f.delete_flag AS deleteFlag, " +
        "       f.app_client_id AS appClientId, " +
        "       m.id AS memberId, m.first_name AS firstName, m.last_name AS lastName, " +
        "       m.role AS role, m.inactive AS memberInactive, m.delete_flag AS memberDeleteFlag " +
        "FROM family f " +
        "LEFT JOIN family_member m ON m.family_id = f.id " +
        "WHERE (:appClientId IS NULL OR f.app_client_id = :appClientId)",
        nativeQuery = true)
    List<Map<String, Object>> findFamilyListProjection(@Param("appClientId") String appClientId);

    /**
     * Fetches only (family_id, photo_thumbnail) for the primary member of each family
     * for the given org. Used by the list view to render small avatar thumbnails.
     * Falls back to photo_data when photo_thumbnail is null (members saved before
     * the thumbnail column was added).
     * "Primary" is defined as role IN ('Head','Head of Household'); if none exists,
     * the lowest member id is used.
     */
    @Query(value =
        "SELECT DISTINCT ON (m.family_id) m.family_id AS familyId, " +
        "  COALESCE(m.photo_thumbnail, m.photo_data) AS photoData " +
        "FROM family_member m " +
        "JOIN family f ON f.id = m.family_id " +
        "WHERE m.delete_flag = false " +
        "AND (:appClientId IS NULL OR f.app_client_id = :appClientId) " +
        "ORDER BY m.family_id, " +
        "  CASE WHEN lower(m.role) IN ('head','head of household') THEN 0 ELSE 1 END, " +
        "  m.id ASC",
        nativeQuery = true)
    List<Map<String, Object>> findPrimaryMemberPhotosByAppUser(@Param("appClientId") String appClientId);
}
