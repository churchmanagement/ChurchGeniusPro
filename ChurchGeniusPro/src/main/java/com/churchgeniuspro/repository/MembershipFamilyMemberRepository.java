package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MembershipFamilyMemberRepository extends JpaRepository<MembershipFamilyMember, Integer> {

    List<MembershipFamilyMember> findByMembershipFamily_IdAndDeleteFlagFalse(Integer membershipFamilyId);
}
