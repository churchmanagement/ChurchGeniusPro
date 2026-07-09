package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserKeyAndActiveTrue(String userKey);

    List<PushSubscription> findByAppClientIdAndUserTypeAndActiveTrue(String appClientId, String userType);

    List<PushSubscription> findByAppClientIdAndActiveTrue(String appClientId);

    List<PushSubscription> findByUserTypeAndActiveTrue(String userType);

    @Transactional
    @Modifying
    @Query("UPDATE PushSubscription p SET p.active = false WHERE p.endpoint = :endpoint")
    void deactivateByEndpoint(@Param("endpoint") String endpoint);
}
