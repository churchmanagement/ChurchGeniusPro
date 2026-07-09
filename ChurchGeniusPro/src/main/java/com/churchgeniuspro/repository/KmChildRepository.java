package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmChild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface KmChildRepository extends JpaRepository<KmChild, Long> {

    List<KmChild> findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(String clientId);

    List<KmChild> findByClientIdAndDeleteFlagFalseAndInactiveFalseOrderByLastNameAscFirstNameAsc(String clientId);

    /**
     * Find active children by parent phone for check-in lookup.
     *
     * <p>Stored {@code parent_phone} values can be formatted in any style
     * ("555-0103", "(555) 0103", "+1 555 0103"), and the public check-in
     * page sends the user's input stripped to digits. To make the match
     * format-agnostic, we strip non-digits from the stored value at query
     * time with {@code regexp_replace}, then LIKE-match against the
     * already-stripped input.
     *
     * <p>Native query because JPQL has no regexp_replace; works on Postgres.
     */
    @Query(value =
        "SELECT * FROM km_child c " +
        "WHERE c.client_id = :clientId " +
        "  AND c.delete_flag = false " +
        "  AND c.inactive = false " +
        "  AND regexp_replace(COALESCE(c.parent_phone, ''), '[^0-9]', '', 'g') " +
        "      LIKE CONCAT('%', :phone, '%')",
        nativeQuery = true)
    List<KmChild> findByClientIdAndParentPhone(@Param("clientId") String clientId,
                                               @Param("phone") String phone);

    /** De-duplication: match an active child by parent/guardian email (case-insensitive). */
    @Query("SELECT c FROM KmChild c WHERE c.clientId = :clientId " +
           "AND LOWER(c.parentEmail) = LOWER(:email) AND c.deleteFlag = false")
    List<KmChild> findByClientIdAndParentEmailIgnoreCase(@Param("clientId") String clientId,
                                                         @Param("email") String email);

    /** Find active children assigned to a classroom */
    List<KmChild> findByClientIdAndClassroomIdAndDeleteFlagFalseAndInactiveFalse(
            String clientId, Long classroomId);

    long countByClientIdAndClassroomIdAndDeleteFlagFalseAndInactiveFalse(
            String clientId, Long classroomId);
}
