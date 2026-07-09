package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.SubSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link SubSource}.
 */
@Repository
public interface SubSourceRepository extends JpaRepository<SubSource, Integer> {

    /** All non-deleted sub-sources for a given main source, sorted A-Z. */
    List<SubSource> findByMainSource_IdAndDeleteFlagFalseOrderBySourceNameAsc(Integer mainSourceId);

    /** All non-deleted sub-sources across all main sources, sorted A-Z. */
    List<SubSource> findByDeleteFlagFalseOrderBySourceNameAsc();

    /** All non-deleted sub-sources for a main source, filtered by appClientId, sorted A-Z. */
    @Query("SELECT s FROM SubSource s WHERE s.mainSource.id = :mainSourceId " +
           "AND s.deleteFlag = false " +
           "AND (:appClientId IS NULL OR s.appClientId = :appClientId) " +
           "ORDER BY s.sourceName ASC")
    List<SubSource> findByMainSourceActiveByAppUser(
            @Param("mainSourceId") Integer mainSourceId,
            @Param("appClientId")  String appClientId);

    /** All non-deleted sub-sources across all main sources, filtered by appClientId, sorted A-Z. */
    @Query("SELECT s FROM SubSource s WHERE s.deleteFlag = false " +
           "AND (:appClientId IS NULL OR s.appClientId = :appClientId) " +
           "ORDER BY s.sourceName ASC")
    List<SubSource> findAllActiveByAppUser(@Param("appClientId") String appClientId);

    /** Cascade soft-delete: mark all sub-sources of a main source as deleted. */
    @Modifying
    @Query("UPDATE SubSource s SET s.deleteFlag = true WHERE s.mainSource = :mainSource")
    void softDeleteByMainSource(@Param("mainSource") MainSource mainSource);

    /** Duplicate-check on create within the same main source. */
    boolean existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_Id(
            String sourceName, Integer mainSourceId);

    /** Duplicate-check on update (excluding the record being updated). */
    boolean existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_IdAndIdNot(
            String sourceName, Integer mainSourceId, Integer id);
}
