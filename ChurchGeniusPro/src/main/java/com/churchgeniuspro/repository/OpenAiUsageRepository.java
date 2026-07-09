package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.OpenAiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Repository for per-church {@link OpenAiUsage} limits and counters. */
@Repository
public interface OpenAiUsageRepository extends JpaRepository<OpenAiUsage, Long> {

    Optional<OpenAiUsage> findByClientId(String clientId);
}
