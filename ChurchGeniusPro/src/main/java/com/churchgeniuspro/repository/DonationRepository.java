package com.churchgeniuspro.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

import com.churchgeniuspro.hibernate.Donation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DonationRepository extends JpaRepository<Donation, Long> {

    /** All donations for an organization, newest first. */
    List<Donation> findByClientIdOrderByDonatedAtDesc(String clientId);

    /** Idempotency check — prevents double-saving the same PaymentIntent. */
    Optional<Donation> findByStripePaymentIntentId(String stripePaymentIntentId);

    // ── Donation Review status (Pending / Completed) ──────────────────────────
    // Every query is scoped by clientId in its WHERE clause, so a request can only
    // ever touch its own church's donations, whatever ids it sends.

    /** How many of {@code ids} belong to {@code clientId}. */
    long countByClientIdAndIdIn(String clientId, Collection<Long> ids);

    /** Marks this church's listed donations Completed. Already-Completed rows are left as they were. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Donation d set d.reviewStatus = 'COMPLETED', d.reviewCompletedAt = :at, d.reviewCompletedBy = :by "
         + "where d.clientId = :clientId and d.id in :ids "
         + "and (d.reviewStatus is null or d.reviewStatus <> 'COMPLETED')")
    int markReviewCompleted(@Param("clientId") String clientId, @Param("ids") Collection<Long> ids,
                            @Param("at") LocalDateTime at, @Param("by") String by);

    /** Returns this church's listed Completed donations to Pending (undo). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Donation d set d.reviewStatus = null, d.reviewCompletedAt = null, d.reviewCompletedBy = null "
         + "where d.clientId = :clientId and d.id in :ids and d.reviewStatus = 'COMPLETED'")
    int markReviewPending(@Param("clientId") String clientId, @Param("ids") Collection<Long> ids);
}
