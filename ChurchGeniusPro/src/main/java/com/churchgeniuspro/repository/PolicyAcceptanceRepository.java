package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PolicyAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link PolicyAcceptance} audit records.
 */
@Repository
public interface PolicyAcceptanceRepository extends JpaRepository<PolicyAcceptance, Long> {

    List<PolicyAcceptance> findByClientIdOrderByAcceptedAtDesc(String clientId);

    List<PolicyAcceptance> findByClientIdAndPolicyTypeOrderByAcceptedAtDesc(String clientId, String policyType);
}
