package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link Group}.
 */
@Repository
public interface GroupRepository extends JpaRepository<Group, Integer> {

    /** All non-deleted groups, sorted A-Z. */
    List<Group> findByDeleteFlagFalseOrderByGroupNameAsc();

    /** All non-deleted groups, filtered by appClientId (null = no filter), sorted A-Z. */
    @Query("SELECT g FROM Group g WHERE g.deleteFlag = false " +
           "AND (:appClientId IS NULL OR g.appClientId = :appClientId) " +
           "ORDER BY g.groupName ASC")
    List<Group> findActiveByAppUser(@Param("appClientId") String appClientId);

    /** Duplicate-check on create — scoped to the same org. */
    boolean existsByGroupNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(String groupName, String appClientId);

    /** Duplicate-check on update — scoped to the same org, excluding the record being updated. */
    boolean existsByGroupNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(String groupName, String appClientId, Integer id);
}
