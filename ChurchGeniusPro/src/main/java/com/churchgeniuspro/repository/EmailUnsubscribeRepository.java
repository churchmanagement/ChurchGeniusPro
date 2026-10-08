package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EmailUnsubscribe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailUnsubscribeRepository extends JpaRepository<EmailUnsubscribe, Integer> {

    Optional<EmailUnsubscribe> findByEmailAndClientId(String email, String clientId);

    boolean existsByEmailAndClientId(String email, String clientId);

    List<EmailUnsubscribe> findByClientIdOrderByUnsubscribedAtDesc(String clientId);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    Optional<EmailUnsubscribe> findByIdAndClientId(Integer id, String clientId);
}
