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

    /** Tenant-scoped: an application id from another church resolves to empty. */
    Optional<MembershipFamily> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);

    /** Finds an active (not deleted) MembershipFamily by signupId. */
    Optional<MembershipFamily> findBySignupIdAndDeleteFlagFalse(Integer signupId);
}
