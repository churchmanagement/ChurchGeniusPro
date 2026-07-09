package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MainSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
