package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrivatePageRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrivatePageRuleRepository extends JpaRepository<PrivatePageRule, Long> {
    List<PrivatePageRule> findByClientId(String clientId);
    Optional<PrivatePageRule> findByClientIdAndPageKey(String clientId, String pageKey);
}
