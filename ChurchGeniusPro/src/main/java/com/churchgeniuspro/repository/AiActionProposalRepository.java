package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AiActionProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiActionProposalRepository extends JpaRepository<AiActionProposal, Long> {

    Optional<AiActionProposal> findByTokenAndAppClientIdAndStatus(String token, String appClientId, String status);

    List<AiActionProposal> findTop50ByAppClientIdOrderByCreatedAtDesc(String appClientId);

    List<AiActionProposal> findTop50ByAppClientIdAndUsernameOrderByCreatedAtDesc(String appClientId, String username);

    /** The caller's still-open proposal (for a spoken "yes"); newest first. */
    List<AiActionProposal> findByAppClientIdAndUsernameAndStatusOrderByCreatedAtDesc(String appClientId, String username, String status);

    /** Open proposals past their expiry become EXPIRED (housekeeping, all tenants). */
    @Modifying
    @Query("UPDATE AiActionProposal p SET p.status = 'EXPIRED', p.token = null, p.decidedAt = :now WHERE p.status = 'PROPOSED' AND p.expiresAt < :now")
    int expireOverdue(@Param("now") LocalDateTime now);
}
