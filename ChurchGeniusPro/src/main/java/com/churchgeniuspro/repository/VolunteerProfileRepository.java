package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VolunteerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VolunteerProfileRepository extends JpaRepository<VolunteerProfile, Long> {

    List<VolunteerProfile> findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(String appClientId);

    Optional<VolunteerProfile> findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalse(String appClientId, Integer familyMemberId);
}
