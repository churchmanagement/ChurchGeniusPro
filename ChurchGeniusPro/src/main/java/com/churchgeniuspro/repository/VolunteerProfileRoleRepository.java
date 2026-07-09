package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VolunteerProfileRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VolunteerProfileRoleRepository extends JpaRepository<VolunteerProfileRole, Long> {

    /** All active role assignments for a volunteer profile. */
    List<VolunteerProfileRole> findByAppClientIdAndVolunteerProfileIdAndDeleteFlagFalse(
            String appClientId, Long volunteerProfileId);

    /** All active role assignments for a given role (to find volunteers with that role). */
    List<VolunteerProfileRole> findByAppClientIdAndRoleIdAndDeleteFlagFalse(
            String appClientId, Long roleId);

    /** Find a specific profile+role pairing (for duplicate-check). */
    Optional<VolunteerProfileRole> findByAppClientIdAndVolunteerProfileIdAndRoleIdAndDeleteFlagFalse(
            String appClientId, Long volunteerProfileId, Long roleId);

    /** All active role assignments for a list of volunteer profile IDs (bulk load). */
    List<VolunteerProfileRole> findByAppClientIdAndVolunteerProfileIdInAndDeleteFlagFalse(
            String appClientId, List<Long> volunteerProfileIds);
}
