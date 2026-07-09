package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmChildSetup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KmChildSetupRepository extends JpaRepository<KmChildSetup, Long> {
    Optional<KmChildSetup> findByClientId(String clientId);
}
