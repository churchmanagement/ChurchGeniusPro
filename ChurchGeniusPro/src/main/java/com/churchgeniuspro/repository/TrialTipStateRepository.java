package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.TrialTipState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrialTipStateRepository extends JpaRepository<TrialTipState, Long> {
    List<TrialTipState> findByClientIdAndUsername(String clientId, String username);
    Optional<TrialTipState> findByClientIdAndUsernameAndTipKey(String clientId, String username, String tipKey);
    long countByClientIdAndUsername(String clientId, String username);
}
