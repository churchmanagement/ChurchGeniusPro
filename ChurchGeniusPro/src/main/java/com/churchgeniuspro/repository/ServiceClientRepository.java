package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ServiceClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ServiceClientRepository extends JpaRepository<ServiceClient, Integer> {

    List<ServiceClient> findAllByDeleteFlagFalseOrderByIdDesc();

    Optional<ServiceClient> findByClientIdAndStatusAndDeleteFlagFalse(String clientId, String status);

    Optional<ServiceClient> findByClientId(String clientId);

    /** Records the client's billing Stripe Customer id once; 0 when another request got there first. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ServiceClient sc SET sc.billingStripeCustomerId = :customerId "
         + "WHERE sc.clientId = :clientId AND sc.billingStripeCustomerId IS NULL")
    int setBillingStripeCustomerId(@Param("clientId") String clientId, @Param("customerId") String customerId);

    /** The client a "Request a subscription" link belongs to (token as issued). */
    Optional<ServiceClient> findBySubscriptionRequestTokenAndDeleteFlagFalse(String subscriptionRequestToken);

    /** Active clients whose subscription ends on the given date (expiry notifications). */
    List<ServiceClient> findByEndDateAndStatusAndDeleteFlagFalse(LocalDate endDate, String status);

    /** Clients whose end date (next billing date) falls in a range — Billing → Upcoming and billing reminders. */
    List<ServiceClient> findByEndDateBetweenAndStatusAndDeleteFlagFalseOrderByEndDateAscIdAsc(LocalDate from, LocalDate to,
                                                                                              String status);

    /**
     * Returns true if the service_client row for the given clientId is active:
     * status = 'Active', delete_flag = false, and end_date >= today.
     */
    @Query("SELECT COUNT(sc) > 0 FROM ServiceClient sc " +
           "WHERE sc.clientId = :clientId " +
           "AND sc.status = 'Active' " +
           "AND sc.deleteFlag = false " +
           "AND sc.endDate >= :today")
    boolean isActiveSubscription(@Param("clientId") String clientId,
                                 @Param("today") LocalDate today);
    /**
     * Client ids whose account is currently usable: {@code status = 'Active'},
     * not deleted, and {@code end_date > CURRENT_DATE}.
     *
     * <p>The same predicate the sign-in queries ({@code LoginRepository.countValid*Login})
     * and the reminder schedulers ({@code AutoReminderRepository.findActiveClientIds})
     * apply — strict {@code >}, so an account whose end date is today is already
     * inactive, and a NULL end date never matches. Read through
     * {@code SubscriptionService.activeAccountClientIds()}.
     */
    @Query("SELECT sc.clientId FROM ServiceClient sc " +
           "WHERE sc.deleteFlag = false " +
           "AND sc.status = 'Active' " +
           "AND sc.endDate > CURRENT_DATE " +
           "AND sc.clientId IS NOT NULL")
    java.util.List<String> findActiveAccountClientIds();

    java.util.Optional<ServiceClient> findByRegistrationTokenAndStatusAndDeleteFlagFalse(String registrationToken, String status);
}
