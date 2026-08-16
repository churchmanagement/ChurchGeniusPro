package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SubscriptionUsage;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link SubscriptionUsage} counters.
 * Increments are atomic UPDATEs so concurrent sends cannot lose counts.
 */
@Repository
public interface SubscriptionUsageRepository extends JpaRepository<SubscriptionUsage, Long> {

    Optional<SubscriptionUsage> findByClientIdAndUsageMonth(String clientId, String usageMonth);

    @Modifying
    @Transactional
    @Query("UPDATE SubscriptionUsage u SET u.emailsSent = u.emailsSent + :n "
         + "WHERE u.clientId = :clientId AND u.usageMonth = :month")
    int addEmails(@Param("clientId") String clientId, @Param("month") String month, @Param("n") int n);

    @Modifying
    @Transactional
    @Query("UPDATE SubscriptionUsage u SET u.smsSent = u.smsSent + :n "
         + "WHERE u.clientId = :clientId AND u.usageMonth = :month")
    int addSms(@Param("clientId") String clientId, @Param("month") String month, @Param("n") int n);

    @Modifying
    @Transactional
    @Query("UPDATE SubscriptionUsage u SET u.givingCount = u.givingCount + :n "
         + "WHERE u.clientId = :clientId AND u.usageMonth = :month")
    int addGiving(@Param("clientId") String clientId, @Param("month") String month, @Param("n") int n);
}
