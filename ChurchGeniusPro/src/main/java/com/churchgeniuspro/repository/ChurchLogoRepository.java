package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ChurchLogo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ChurchLogo}.
 * At most one logo record per {@code clientId}.
 */
@Repository
public interface ChurchLogoRepository extends JpaRepository<ChurchLogo, Long> {

    /** Find the logo for the given organization. */
    Optional<ChurchLogo> findByClientId(String clientId);
}
