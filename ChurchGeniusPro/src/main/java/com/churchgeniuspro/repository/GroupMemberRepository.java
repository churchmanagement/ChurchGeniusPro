package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link GroupMember}.
 */
@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Integer> {

    /** All non-deleted members for a given group, sorted by name A-Z. */
    List<GroupMember> findByGroup_IdAndDeleteFlagFalseOrderByFirstNameAscLastNameAsc(Integer groupId);

    /** Non-deleted members for a group, filtered by appClientId, sorted by name. */
    @Query("SELECT m FROM GroupMember m WHERE m.group.id = :groupId " +
           "AND m.deleteFlag = false " +
           "AND (:appClientId IS NULL OR m.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<GroupMember> findByGroupActiveByAppUser(
            @Param("groupId")    Integer groupId,
            @Param("appClientId") String appClientId);

    /** Member count for a given group (used in group list for badge display). */
    long countByGroup_IdAndDeleteFlagFalse(Integer groupId);

    /** Cascade soft-delete: mark all members of a group as deleted. */
    @Modifying
    @Query("UPDATE GroupMember m SET m.deleteFlag = true WHERE m.group = :group")
    void softDeleteByGroup(@Param("group") Group group);

    /** Duplicate-check on create (same email in same group). */
    boolean existsByEmailIgnoreCaseAndDeleteFlagFalseAndGroup_Id(String email, Integer groupId);

    /** Duplicate-check on update (same email in same group, excluding the record being updated). */
    boolean existsByEmailIgnoreCaseAndDeleteFlagFalseAndGroup_IdAndIdNot(
            String email, Integer groupId, Integer id);
}
