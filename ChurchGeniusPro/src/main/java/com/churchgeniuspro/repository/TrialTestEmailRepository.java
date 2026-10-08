package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.TrialTestEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Tenant-scoped on purpose: the only finder takes the tenant. */
@Repository
public interface TrialTestEmailRepository extends JpaRepository<TrialTestEmail, Long> {
    Optional<TrialTestEmail> findByClientId(String clientId);
}
