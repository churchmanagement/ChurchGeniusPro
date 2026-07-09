package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MemberType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link MemberType}.
 */
@Repository
public interface MemberTypeRepository extends JpaRepository<MemberType, Integer> {

    /** All non-deleted types, sorted A-Z. */
    List<MemberType> findByDeleteFlagFalseOrderByTypeNameAsc();

    /** All non-deleted member types, filtered by appClientId (null = no filter), sorted A-Z. */
    @Query("SELECT m FROM MemberType m WHERE m.deleteFlag = false " +
           "AND (:appClientId IS NULL OR m.appClientId = :appClientId) " +
           "ORDER BY m.typeName ASC")
    List<MemberType> findActiveByAppUser(@Param("appClientId") String appClientId);

    /**
     * Duplicate-check on create: same name within the same organization.
     * Allows the same name for a different appClientId (different church).
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM MemberType m " +
           "WHERE LOWER(m.typeName) = LOWER(:typeName) " +
           "AND m.deleteFlag = false " +
           "AND ((:appClientId IS NULL AND m.appClientId IS NULL) OR m.appClientId = :appClientId)")
    boolean existsByTypeNameAndClientId(@Param("typeName") String typeName,
                                        @Param("appClientId") String appClientId);

    /**
     * Duplicate-check on update: same name within the same organization, excluding self.
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM MemberType m " +
           "WHERE LOWER(m.typeName) = LOWER(:typeName) " +
           "AND m.deleteFlag = false " +
           "AND ((:appClientId IS NULL AND m.appClientId IS NULL) OR m.appClientId = :appClientId) " +
           "AND m.id <> :id")
    boolean existsByTypeNameAndClientIdAndIdNot(@Param("typeName") String typeName,
                                                @Param("appClientId") String appClientId,
                                                @Param("id") Integer id);
}
