package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MemberPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberPreferenceRepository extends JpaRepository<MemberPreference, Integer> {

    Optional<MemberPreference> findByMemberId(Integer memberId);
}
