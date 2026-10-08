package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link AppUser} entities.
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Integer> {

    /** All non-deleted users ordered alphabetically by last name then first name. */
    List<AppUser> findByDeleteFlagFalseOrderByLastNameAscFirstNameAsc();

    /**
     * Duplicate-check on create: same email + role + clientId combination.
     * Allows the same email+role for a different organization (clientId).
     * NULL clientId is treated as a distinct value (IS NULL comparison).
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN TRUE ELSE FALSE END FROM AppUser u " +
           "WHERE LOWER(u.email) = LOWER(:email) " +
           "AND u.role = :role " +
           "AND u.deleteFlag = false " +
           "AND ((:clientId IS NULL AND u.clientId IS NULL) OR u.clientId = :clientId)")
    boolean existsByEmailRoleClientIdAndNotDeleted(@Param("email") String email,
                                                   @Param("role")  String role,
                                                   @Param("clientId") String clientId);

    /**
     * Duplicate-check on update: same email + role + clientId, excluding the record being edited.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN TRUE ELSE FALSE END FROM AppUser u " +
           "WHERE LOWER(u.email) = LOWER(:email) " +
           "AND u.role = :role " +
           "AND u.deleteFlag = false " +
           "AND ((:clientId IS NULL AND u.clientId IS NULL) OR u.clientId = :clientId) " +
           "AND u.id <> :id")
    boolean existsByEmailRoleClientIdAndNotDeletedAndIdNot(@Param("email") String email,
                                                           @Param("role")  String role,
                                                           @Param("clientId") String clientId,
                                                           @Param("id") Integer id);

    /** Find a non-deleted user by their unique signup token (used for signup link validation). */
    java.util.Optional<AppUser> findByUserIdAndDeleteFlagFalse(String userId);

    /** Find a non-deleted user by their organization client-ID (used for org-level lookups). */
    java.util.Optional<AppUser> findByClientIdAndDeleteFlagFalse(String clientId);

    /** All non-deleted users for a specific organization (church), ordered alphabetically. */
    List<AppUser> findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(String clientId);

    /** Users the church currently has — the same rows /viewusers lists (not deleted). */
    long countByClientIdAndDeleteFlagFalse(String clientId);

    /**
     * Tenant-scoped single-row lookup. Every management operation on a staff user
     * must load through this so an id from another church resolves to "not found"
     * rather than to that church's user.
     */
    java.util.Optional<AppUser> findByIdAndClientId(Integer id, String clientId);

    /** All non-deleted users sharing the same link-group (used for role switching). */
    List<AppUser> findByLinkGroupAndDeleteFlagFalse(String linkGroup);

    /** All non-deleted Admin and SuperAdmin users for an organization (used for admin notifications). */
    @Query("SELECT u FROM AppUser u WHERE u.clientId = :clientId " +
           "AND u.deleteFlag = false AND u.enabled = true " +
           "AND u.role IN ('Admin', 'SuperAdmin') " +
           "AND u.email IS NOT NULL AND u.email <> ''")
    List<AppUser> findAdminsByClientId(@Param("clientId") String clientId);

    /** Look up an active user by email address (for notification routing by createdBy username). */
    java.util.Optional<AppUser> findFirstByEmailIgnoreCaseAndDeleteFlagFalse(String email);

    /** Find all active staff users by email (case-insensitive) — used by username recovery. */
    @Query("SELECT u FROM AppUser u WHERE LOWER(u.email) = LOWER(:email) AND u.deleteFlag = false AND u.enabled = true")
    java.util.List<AppUser> findActiveByEmail(@Param("email") String email);

    /** Find all active staff users by phone (normalised) — used by username recovery. */
    @Query("SELECT u FROM AppUser u WHERE REPLACE(REPLACE(REPLACE(u.phone,' ',''),'-',''),'(','') LIKE CONCAT('%', :phone, '%') AND u.deleteFlag = false AND u.enabled = true")
    java.util.List<AppUser> findActiveByPhone(@Param("phone") String phone);
    java.util.Optional<AppUser> findByInviteTokenAndDeleteFlagFalse(String inviteToken);
}
