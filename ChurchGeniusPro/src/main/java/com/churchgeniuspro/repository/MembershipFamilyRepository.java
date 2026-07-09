package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MembershipFamily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipFamilyRepository extends JpaRepository<MembershipFamily, Integer> {

    List<MembershipFamily> findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(String appClientId);

    Optional<MembershipFamily> findByIdAndDeleteFlagFalse(Integer id);

    /** Finds any MembershipFamily row (active or soft-deleted) with the given signupId. */
    @Query("SELECT m FROM MembershipFamily m WHERE m.signupId = :signupId ORDER BY m.id DESC")
    MembershipFamily findTopBySignupId(@Param("signupId") Integer signupId);

    /** Finds an active (not deleted) MembershipFamily by signupId. */
    Optional<MembershipFamily> findBySignupIdAndDeleteFlagFalse(Integer signupId);
}
