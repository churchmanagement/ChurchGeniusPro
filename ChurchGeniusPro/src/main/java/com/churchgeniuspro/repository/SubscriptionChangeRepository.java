package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SubscriptionChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionChangeRepository extends JpaRepository<SubscriptionChange, Long> {
    List<SubscriptionChange> findByClientIdOrderByChangedAtDescIdDesc(String clientId);
}
