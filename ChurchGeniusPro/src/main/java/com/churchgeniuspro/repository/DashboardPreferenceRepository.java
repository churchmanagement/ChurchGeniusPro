package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.DashboardPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DashboardPreference}.
 */
@Repository
public interface DashboardPreferenceRepository extends JpaRepository<DashboardPreference, Integer> {

    /**
     * Finds the preference row for a specific user within a specific organization.
     *
     * @param username the login username (email) of the user
     * @param clientId the organization's client-ID (e.g. {@code CGP-00001})
     * @return the stored preferences, or {@link Optional#empty()} if none saved yet
     */
    Optional<DashboardPreference> findByUsernameAndClientId(String username, String clientId);
}
