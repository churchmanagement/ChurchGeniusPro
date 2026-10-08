package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.FamilyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link FamilyMember} entities.
 */
@Repository
public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Integer> {

    /**
     * Returns all non-deleted family members together with their parent family
     * (eager-fetched to avoid N+1), ordered by last name then first name.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false " +
           "ORDER BY m.lastName ASC, m.firstName ASC")
    List<FamilyMember> findAllWithFamily();

    /**
     * Returns all non-deleted family members (any role) who have their own address
     * (i.e. {

    /**
     * Active, non-deleted family members filtered by {@code memberType} who have a
     * non-blank email and have not opted out of alerts.  Used to resolve
     * "Church Guests" (type='Guest') and "Members" (type='Member') recipient lists.
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.memberType = :memberType " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "AND m.disableAlerts = false " +
           "AND m.email IS NOT NULL AND m.email <> '' " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findByMemberTypeWithEmail(@Param("memberType") String memberType);

    /**
     * All non-deleted family members who have {

    /**
     * Returns all non-deleted family members with parent family, filtered by appClientId.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE (m.deleteFlag = false OR m.deleteFlag IS NULL) " +
           "AND (m.inactive = false OR m.inactive IS NULL) " +
           "AND (m.family.deleteFlag = false OR m.family.deleteFlag IS NULL) " +
           "AND (m.family.inactive = false OR m.family.inactive IS NULL) " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.lastName ASC, m.firstName ASC")
    List<FamilyMember> findAllWithFamilyByAppUser(@Param("appClientId") String appClientId);

    /**
     * Like {@link #findAllWithFamilyByAppUser} but includes soft-deleted members.
     * Used when the "Show Deleted" filter is active on the member-search panel.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.lastName ASC, m.firstName ASC")
    List<FamilyMember> findAllWithFamilyByAppUserAll(@Param("appClientId") String appClientId);

    /**
     * Like {@link #searchByFilters} but includes soft-deleted members.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "AND (:name = '' OR LOWER(m.firstName)  LIKE LOWER(CONCAT('%', :name, '%')) " +
           "               OR LOWER(m.lastName)    LIKE LOWER(CONCAT('%', :name, '%')) " +
           "               OR LOWER(COALESCE(m.otherName,'')) LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "AND (:role = '' OR m.role = :role) " +
           "AND (:memberType = '' OR m.memberType = :memberType) " +
           "AND (:phone = '' OR m.phone LIKE CONCAT('%', :phone, '%')) " +
           "AND (:email = '' OR LOWER(m.email) LIKE LOWER(CONCAT('%', :email, '%'))) " +
           "ORDER BY m.lastName ASC, m.firstName ASC")
    List<FamilyMember> searchByFiltersAll(@Param("name")       String name,
                                          @Param("role")       String role,
                                          @Param("memberType") String memberType,
                                          @Param("phone")      String phone,
                                          @Param("email")      String email,
                                          @Param("appClientId") String appClientId);

    /**
     * Returns non-deleted members with own address, filtered by appClientId.
     */
    @Query("SELECT m FROM FamilyMember m JOIN FETCH m.family " +
           "WHERE m.sameAsFamilyAddress = false " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findAllMembersWithOwnAddressByAppUser(@Param("appClientId") String appClientId);

    /**
     * All non-deleted family members with email, filtered by appClientId.
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.email IS NOT NULL AND m.email <> '' " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findAllWithEmailByAppUser(@Param("appClientId") String appClientId);

    /**
     * Non-deleted family members by memberType with email, filtered by appClientId.
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.memberType = :memberType " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.email IS NOT NULL AND m.email <> '' " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findByMemberTypeWithEmailByAppUser(
            @Param("memberType")  String memberType,
            @Param("appClientId") String appClientId);

    /**
     * Non-deleted family members by memberType with a non-blank phone number,
     * filtered by appClientId.  Used by the WhatsApp/SMS scheduler.
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.memberType = :memberType " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.phone IS NOT NULL AND m.phone <> '' " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findByMemberTypeWithPhoneByAppUser(
            @Param("memberType")  String memberType,
            @Param("appClientId") String appClientId);

    /**
     * All non-deleted family members with a non-blank phone number, filtered by appClientId.
     * Used by the WhatsApp/SMS scheduler for "all recipients" (Members + Guests).
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.phone IS NOT NULL AND m.phone <> '' " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findAllWithPhoneByAppUser(@Param("appClientId") String appClientId);

    /**
     * All non-deleted family members with includeContributions=true, filtered by appClientId.
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.includeContributions = true " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findContributorsByAppUser(@Param("appClientId") String appClientId);

    /**
     * Non-deleted family members whose birthday falls on the given month+day,
     * filtered by appClientId.  Email is NOT required — used by the birthday
     * scheduler so that members/guests can still be notified even when the
     * celebrant has no email address.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.birthdayMonth = :month AND m.birthdayDay = :day " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findByBirthdayToday(
            @Param("month")       int month,
            @Param("day")         int day,
            @Param("appClientId") String appClientId);

    /**
     * Non-deleted family members whose birthday falls on the given month+day,
     * filtered by appClientId.  Only members WITH a non-blank email are returned.
     * Used by the monthly birthday summary (listing only).
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.birthdayMonth = :month AND m.birthdayDay = :day " +
           "AND m.email IS NOT NULL AND m.email <> '' " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findByBirthdayTodayWithEmail(
            @Param("month")      int month,
            @Param("day")        int day,
            @Param("appClientId") String appClientId);

    /**
     * Non-deleted family members whose wedding anniversary falls on the given month+day,
     * filtered by appClientId.  Used by the anniversary scheduler.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.anniversaryMonth = :month AND m.anniversaryDay = :day " +
           "AND m.email IS NOT NULL AND m.email <> '' " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findByAnniversaryTodayWithEmail(
            @Param("month")      int month,
            @Param("day")        int day,
            @Param("appClientId") String appClientId);

    /**
     * Non-deleted family members whose wedding anniversary falls on the given month+day,
     * filtered by appClientId.  No role restriction — the anniversary may be stored on
     * any member of the couple (Head of Household, Spouse, Wife, etc.).
     * Email is NOT required so that we can still build the couple name even when
     * a member has no email address.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.anniversaryMonth = :month AND m.anniversaryDay = :day " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.firstName ASC, m.lastName ASC")
    List<FamilyMember> findByAnniversaryToday(
            @Param("month")       int month,
            @Param("day")         int day,
            @Param("appClientId") String appClientId);

    /**
     * All non-deleted, active members of a given family.
     * Used by the anniversary scheduler to locate every member of the couple's family.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.family.id = :familyId " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "ORDER BY m.id ASC")
    List<FamilyMember> findActiveMembersByFamilyId(@Param("familyId") Integer familyId);

    /**
     * Returns the {@code app_client_id} from the {@code family} table for the given family ID.
     * Used as a safe fallback when {@code FamilyMember.appClientId} is null, avoiding
     * any risk of a LazyInitializationException from the {@code Family} proxy.
     */
    @Query(value = "SELECT f.app_client_id FROM family f WHERE f.id = :familyId", nativeQuery = true)
    String findFamilyAppClientId(@Param("familyId") Integer familyId);

    // ── AI Search — count queries ─────────────────────────────────────────

    /**
     * Total count of active (non-deleted) members, filtered by appClientId.
     * Used by the AI search members-count intent.
     */
    @Query(value =
           "SELECT COUNT(*) FROM family_member fm " +
           "JOIN family f ON f.id = fm.family_id " +
           "WHERE fm.delete_flag = false AND fm.inactive = false " +
           "AND f.delete_flag = false AND f.inactive = false " +
           "AND (:appClientId IS NULL OR f.app_client_id = :appClientId)",
           nativeQuery = true)
    Long countActiveMembers(@Param("appClientId") String appClientId);

    /**
     * Active family members with a child role (Child / Son / Daughter) —
     * used for the subscription plan's kids-portal limit.
     */
    @Query(value =
           "SELECT COUNT(*) FROM family_member fm " +
           "JOIN family f ON f.id = fm.family_id " +
           "WHERE fm.delete_flag = false AND fm.inactive = false " +
           "AND f.delete_flag = false AND f.inactive = false " +
           "AND LOWER(fm.role) IN ('child','son','daughter') " +
           "AND f.app_client_id = :appClientId",
           nativeQuery = true)
    long countChildRoleMembers(@Param("appClientId") String appClientId);

    /**
     * Count of members added (created) in the given month and year,
     * filtered by appClientId. Used for "how many new members?" queries.
     */
    @Query(value =
           "SELECT COUNT(*) FROM family_member fm " +
           "JOIN family f ON f.id = fm.family_id " +
           "WHERE fm.delete_flag = false AND fm.inactive = false " +
           "AND f.delete_flag = false AND f.inactive = false " +
           "AND (:appClientId IS NULL OR f.app_client_id = :appClientId) " +
           "AND EXTRACT(MONTH FROM fm.created_date) = :month " +
           "AND EXTRACT(YEAR  FROM fm.created_date) = :year",
           nativeQuery = true)
    Long countNewMembersInMonth(@Param("appClientId") String appClientId,
                                @Param("month")       int month,
                                @Param("year")        int year);

    // ── AI Search — name / date queries ──────────────────────────────────

    /**
     * Full-text name search: matches non-deleted members whose first OR last name
     * contains the given string (case-insensitive), filtered by appClientId.
     * Used by the AI search feature.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "AND (LOWER(m.firstName) LIKE LOWER(CONCAT('%', :name, '%')) " +
           "  OR LOWER(m.lastName)  LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "ORDER BY m.lastName ASC, m.firstName ASC")
    List<FamilyMember> searchByName(@Param("name") String name,
                                    @Param("appClientId") String appClientId);

    /**
     * Multi-filter member search supporting optional name, role, memberType, phone
     * and email criteria.  An empty string for any parameter disables that filter.
     * Used by the Member Search panel on the View Family page.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "AND (:name = '' OR LOWER(m.firstName)  LIKE LOWER(CONCAT('%', :name, '%')) " +
           "               OR LOWER(m.lastName)    LIKE LOWER(CONCAT('%', :name, '%')) " +
           "               OR LOWER(COALESCE(m.otherName,'')) LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "AND (:role = '' OR m.role = :role) " +
           "AND (:memberType = '' OR m.memberType = :memberType) " +
           "AND (:phone = '' OR m.phone LIKE CONCAT('%', :phone, '%')) " +
           "AND (:email = '' OR LOWER(m.email) LIKE LOWER(CONCAT('%', :email, '%'))) " +
           "ORDER BY m.lastName ASC, m.firstName ASC")
    List<FamilyMember> searchByFilters(@Param("name")       String name,
                                       @Param("role")       String role,
                                       @Param("memberType") String memberType,
                                       @Param("phone")      String phone,
                                       @Param("email")      String email,
                                       @Param("appClientId") String appClientId);

    /**
     * All non-deleted members with a birthday in the given month,
     * filtered by appClientId. Ordered by day then name.
     * Used by the AI search feature.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.birthdayMonth = :month " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.birthdayDay ASC, m.lastName ASC, m.firstName ASC")
    List<FamilyMember> findByBirthdayMonth(@Param("month") int month,
                                           @Param("appClientId") String appClientId);

    /**
     * All non-deleted members with a wedding anniversary in the given month,
     * filtered by appClientId. Ordered by day then name.
     * Used by the AI search feature.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.anniversaryMonth = :month " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId) " +
           "ORDER BY m.anniversaryDay ASC, m.lastName ASC, m.firstName ASC")
    List<FamilyMember> findByAnniversaryMonth(@Param("month") int month,
                                              @Param("appClientId") String appClientId);

    /**
     * Returns the email addresses of all active, alert-enabled, non-deleted
     * family members for the given organization that have a non-null / non-blank
     * email address.
     *
     * <p>Equivalent SQL:
     * <pre>
     * SELECT email FROM family_member
     * WHERE inactive = false
     *   AND disable_alerts = false
     *   AND delete_flag = false
     *   AND app_client_id = :appClientId
     *   AND email IS NOT NULL AND email &lt;&gt; ''
     * </pre>
     *
     * Used by the Prayer Request "Email All Members" notification.
     */
    @Query("SELECT m.email FROM FamilyMember m " +
           "WHERE m.deleteFlag = false " +
           "AND m.inactive = false " +
           "AND m.disableAlerts = false " +
           "AND m.appClientId = :appClientId " +
           "AND m.email IS NOT NULL AND m.email <> ''")
    List<String> findEmailsForPrayerNotification(@Param("appClientId") String appClientId);

    /**
     * Finds non-deleted family members whose phone exactly matches {@code query}
     * OR whose email matches {@code query} (case-insensitive), scoped to the
     * given {@code appClientId}.  Used by the public membership renewal lookup.
     */
    @Query("SELECT DISTINCT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.family.appClientId = :appClientId " +
           "AND (m.phone = :query OR LOWER(m.email) = LOWER(:query)) " +
           "ORDER BY m.lastName ASC, m.firstName ASC")
    List<FamilyMember> findByPhoneOrEmailAndClient(@Param("query")       String query,
                                                   @Param("appClientId") String appClientId);

    /** Find an active family member by email address (any org). Used for signup self-heal. */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE LOWER(m.email) = LOWER(:email) AND m.deleteFlag = false AND m.inactive = false " +
           "ORDER BY m.id ASC")
    List<FamilyMember> findActiveByEmail(@Param("email") String email);

    /**
     * Finds active family members whose phone number exactly matches {@code phone},
     * across all churches. Used by the SMS GIVE keyword to identify which church
     * a texting member belongs to when they have no sms_opt_in record.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.phone = :phone AND m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "ORDER BY m.id ASC")
    List<FamilyMember> findActiveByPhoneAnyChurch(@Param("phone") String phone);

    /**
     * Finds a non-deleted, active family member by their memberRef.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.memberRef = :memberRef AND m.deleteFlag = false AND m.inactive = false")
    java.util.Optional<FamilyMember> findByMemberRef(@Param("memberRef") String memberRef);

    /**
     * Finds non-deleted, active family members matching last name AND (email OR phone),
     * scoped to the given appClientId (via family.appClientId).
     * Used by the public member signup self-lookup.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE m.deleteFlag = false AND m.inactive = false " +
           "AND m.family.deleteFlag = false AND m.family.inactive = false " +
           "AND m.family.appClientId = :appClientId " +
           "AND LOWER(m.lastName) = LOWER(:lastName) " +
           "AND (:contact = '' OR LOWER(m.email) = LOWER(:contact) OR m.phone = :contact) " +
           "ORDER BY m.id ASC")
    List<FamilyMember> findByLastNameAndContact(@Param("lastName")    String lastName,
                                                @Param("contact")     String contact,
                                                @Param("appClientId") String appClientId);

    /**
     * Finds non-deleted, active child members matching first name + last name,
     * where the contact (email or phone) belongs to any adult in the same family.
     * "Adult" here means any member whose role is NOT Son, Daughter, or Child.
     * Used by the public member signup lookup to support children who have no
     * contact of their own — the parent's contact is provided instead.
     */
    @Query("SELECT DISTINCT child FROM FamilyMember child LEFT JOIN FETCH child.family " +
           "JOIN FamilyMember adult ON adult.family.id = child.family.id " +
           "WHERE child.deleteFlag = false AND child.inactive = false " +
           "AND child.family.deleteFlag = false AND child.family.inactive = false " +
           "AND child.family.appClientId = :appClientId " +
           "AND LOWER(child.firstName) = LOWER(:firstName) " +
           "AND LOWER(child.lastName)  = LOWER(:lastName) " +
           "AND (LOWER(child.role) IN ('son','daughter','child') " +
           "     OR child.role IS NULL) " +
           "AND adult.deleteFlag = false AND adult.inactive = false " +
           "AND (:contact = '' OR LOWER(adult.email) = LOWER(:contact) OR adult.phone = :contact) " +
           "AND (LOWER(adult.role) NOT IN ('son','daughter','child') OR adult.role IS NULL) " +
           "ORDER BY child.id ASC")
    List<FamilyMember> findChildByNameAndFamilyContact(@Param("firstName")   String firstName,
                                                       @Param("lastName")    String lastName,
                                                       @Param("contact")     String contact,
                                                       @Param("appClientId") String appClientId);

    /**
     * Finds the active Head of Household member in the same family
     * who already has a memberRef (i.e. has been linked to a signup account).
     * Used when a non-Head member registers to discover the Head's signup for churchId linking.
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.family.id = :familyId " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "AND (m.role = 'Head' OR m.role = 'Head of Household') " +
           "AND m.memberRef IS NOT NULL " +
           "ORDER BY m.id ASC")
    List<FamilyMember> findHeadWithMemberRefByFamilyId(@Param("familyId") Integer familyId);

    /**
     * Returns all active, non-deleted family members in the same family as the given member ID
     * (uses a subquery to look up the family_id from the memberId).
     * Used by Sunday School HoH endpoints.
     */
    @Query("SELECT m FROM FamilyMember m " +
           "WHERE m.family.id = (SELECT m2.family.id FROM FamilyMember m2 WHERE m2.id = :memberId) " +
           "AND m.deleteFlag = false AND m.inactive = false " +
           "ORDER BY m.id ASC")
    List<FamilyMember> findActiveMembersByMemberId(@Param("memberId") Integer memberId);

    /**
     * Returns the email of the Head of Household in the same family as the given family member ID.
     * Looks up via the family relationship. Returns null if none found.
     * Used to send HoH copy emails for Sunday School exam submissions.
     */
    @Query(value =
           "SELECT fm2.email FROM family_member fm2 " +
           "WHERE fm2.family_id = (SELECT fm1.family_id FROM family_member fm1 WHERE fm1.id = :familyMemberId) " +
           "AND fm2.delete_flag = false AND fm2.inactive = false " +
           "AND fm2.email IS NOT NULL AND fm2.email <> '' " +
           "AND (lower(fm2.role) IN ('head','head of household')) " +
           "ORDER BY fm2.id ASC " +
           "LIMIT 1",
           nativeQuery = true)
    String findHohEmailByFamilyMemberId(@Param("familyMemberId") Integer familyMemberId);

    /**
     * All non-deleted members for one tenant, used by the ETL validation stage to
     * build an in-memory dedupe index (by email and by name+phone). Tenant is the
     * family's {@code appClientId}, matching how the rest of the app scopes members.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN FETCH m.family " +
           "WHERE (m.deleteFlag = false OR m.deleteFlag IS NULL) " +
           "AND (:appClientId IS NULL OR m.family.appClientId = :appClientId)")
    List<FamilyMember> findActiveByTenantForEtl(@Param("appClientId") String appClientId);

    /**
     * Returns the single representative family-photo thumbnail (pre-computed,
     * optimized 60px JPEG data URI) for the family the given member belongs to.
     * Prefers the Head of Household's photo, then any other member that has a
     * thumbnail, so every member of the family shares the same picture in the
     * sidebar. Returns {@code null} when no member of the family has a photo.
     */
    @Query(value =
           "SELECT fm.photo_thumbnail FROM family_member fm " +
           "WHERE fm.family_id = (SELECT m2.family_id FROM family_member m2 WHERE m2.id = :memberId) " +
           "AND fm.delete_flag = false " +
           "AND fm.photo_thumbnail IS NOT NULL AND fm.photo_thumbnail <> '' " +
           "ORDER BY CASE WHEN LOWER(fm.role) IN ('head','head of household') THEN 0 ELSE 1 END, fm.id ASC " +
           "LIMIT 1",
           nativeQuery = true)
    String findFamilyThumbnailByMemberId(@Param("memberId") Integer memberId);

    /**
     * Returns the single representative family-photo thumbnail for the family that
     * contains a member with the given email address. Prefers the Head of
     * Household's photo, then any member with a thumbnail. Used to resolve the
     * shared family photo for a staff account (whose login email matches a
     * family member). Returns {

    /**
     * Tenant-scoped variant of {@link #findFamilyThumbnailByEmail}: the matching
     * member (and the family whose photo is returned) must belong to the church
     * identified by {@code appClientId}, so a login email that also exists in
     * another church can never surface that church's family photo.
     */
    @Query(value =
           "SELECT fm.photo_thumbnail FROM family_member fm " +
           "JOIN family f ON f.id = fm.family_id " +
           "WHERE fm.family_id = (SELECT m2.family_id FROM family_member m2 " +
           "    JOIN family f2 ON f2.id = m2.family_id " +
           "    WHERE LOWER(m2.email) = LOWER(:email) AND m2.delete_flag = false " +
           "    AND f2.app_client_id = :appClientId " +
           "    ORDER BY m2.id ASC LIMIT 1) " +
           "AND f.app_client_id = :appClientId " +
           "AND fm.delete_flag = false " +
           "AND fm.photo_thumbnail IS NOT NULL AND fm.photo_thumbnail <> '' " +
           "ORDER BY CASE WHEN LOWER(fm.role) IN ('head','head of household') THEN 0 ELSE 1 END, fm.id ASC " +
           "LIMIT 1",
           nativeQuery = true)
    String findFamilyThumbnailByEmailAndAppClientId(@Param("email") String email,
                                                    @Param("appClientId") String appClientId);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    /**
     * A member of the given church, by id. Tenant is the family's appClientId when
     * the member belongs to a family, otherwise the member's own. Returns empty for
     * an id that exists in another church, so callers cannot tell the difference.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN m.family f " +
           "WHERE m.id = :id AND m.deleteFlag = false " +
           "AND COALESCE(f.appClientId, m.appClientId) = :appClientId")
    java.util.Optional<FamilyMember> findByIdAndTenant(@Param("id") Integer id,
                                                       @Param("appClientId") String appClientId);

    /**
     * Active members of the given church whose email matches exactly
     * (case-insensitive). Deliberately tenant-scoped — unlike
     * {@link #findActiveByEmail}, which searches every church and exists
     * only for the signup self-heal flow — so an online donor's email can
     * never be matched against a different church's member. Returns every
     * match rather than one: the caller treats more than one hit (a shared
     * family inbox) the same as no hit, rather than guessing which member
     * to credit. Financial audit H9.
     */
    @Query("SELECT m FROM FamilyMember m LEFT JOIN m.family f " +
           "WHERE LOWER(m.email) = LOWER(:email) AND m.deleteFlag = false AND m.inactive = false " +
           "AND COALESCE(f.appClientId, m.appClientId) = :appClientId")
    List<FamilyMember> findActiveByEmailAndTenant(@Param("email") String email,
                                                  @Param("appClientId") String appClientId);
}
