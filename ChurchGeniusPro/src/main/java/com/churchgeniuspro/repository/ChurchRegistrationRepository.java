package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link ChurchRegistration} entities.
 *
 * <p>Provides full CRUD operations out-of-the-box via {@link JpaRepository}.
 * Custom query methods can be added here as needed.
 */
@Repository
public interface ChurchRegistrationRepository extends JpaRepository<ChurchRegistration, Integer> {

    /** Find a non-deleted church registration by its service-client ID. */
    java.util.Optional<ChurchRegistration> findByClientIdAndDeleteFlagFalse(String clientId);

    /** Find all non-deleted church registrations. */
    java.util.List<ChurchRegistration> findAllByDeleteFlagFalse();

    /** Find non-deleted church registrations by email (case-insensitive) — for username recovery. */
    java.util.List<ChurchRegistration> findByEmailIgnoreCaseAndDeleteFlagFalse(String email);
}
