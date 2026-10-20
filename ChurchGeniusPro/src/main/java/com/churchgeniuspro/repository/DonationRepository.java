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

    // ── Accounting posting (V14) ───────────────────────────────────────────────

    /**
     * Transactions report → "Online Donations &amp; Contributions": this church's online
     * donations (public page and Member Portal) that are <b>saved to accounting</b>,
     * whose Income date falls in [startDate, endDate] (inclusive, as the rest of the
     * report). Read-only.
     *
     * <p>A donation is included when its Income row exists and is not deleted:
     * <ul>
     *   <li>posted by Donation Review — the stored {@code posted_income_id}; or</li>
     *   <li>posted by the former automatic hook (before V14) — matched by the Stripe
     *       PaymentIntent in {@code income.import_ref} — and only once a reviewer has
     *       marked the donation Completed.</li>
     * </ul>
     * Pending, never-saved, failed and deleted donations are therefore excluded.
     * The fee comes only from a stored fee Expense (by {@code posted_expense_id}, or
     * by the {@code <ref>:stripe-fee} import_ref) — never assumed.
     *
     * <p>Columns: [0 donation id, 1 first_name, 2 last_name, 3 email, 4 phone, 5 note,
     * 6 donation amount, 7 source, 8 income id, 9 income_date, 10 income amount,
     * 11 member first_name, 12 member last_name, 13 expense id, 14 expense amount,
     * 15 stripe_fee, 16 fee_source, 17 posted by review (boolean), 18 currency].
     */
    @Query(value =
           "SELECT d.id, d.first_name, d.last_name, d.email, d.phone, d.note, d.amount, d.source, " +
           "       i.id, i.income_date, i.amount, fm.first_name, fm.last_name, " +
           "       e.id, e.amount, d.stripe_fee, d.fee_source, (d.posted_income_id IS NOT NULL), d.currency " +
           "FROM   donation d " +
           "JOIN   income i ON i.app_client_id = d.client_id AND i.delete_flag = false " +
           "  AND ( i.id = d.posted_income_id " +
           "     OR ( d.posted_income_id IS NULL AND d.review_status = 'COMPLETED' " +
           "          AND i.import_ref = COALESCE(NULLIF(d.stripe_payment_intent_id, ''), 'donation:' || d.id) ) ) " +
           "LEFT JOIN family_member fm ON fm.id = i.member_id " +
           "LEFT JOIN expense e ON e.app_client_id = d.client_id AND e.delete_flag = false " +
           "  AND ( e.id = d.posted_expense_id " +
           "     OR ( d.posted_expense_id IS NULL AND e.import_ref = i.import_ref || ':stripe-fee' ) ) " +
           "WHERE  d.client_id = :clientId " +
           "  AND  i.income_date BETWEEN :startDate AND :endDate " +
           "ORDER  BY i.income_date DESC, d.id DESC",
           nativeQuery = true)
    List<Object[]> reportOnlineDonationsInRange(@Param("startDate") java.sql.Date startDate,
                                                @Param("endDate")   java.sql.Date endDate,
                                                @Param("clientId")  String clientId);

    /** This church's donations among {@code ids} (to label report rows with their DON- number). */
    List<Donation> findByClientIdAndIdIn(String clientId, Collection<Long> ids);

    /** This church's donations with one of these PaymentIntents (historical rows, by import_ref). */
    List<Donation> findByClientIdAndStripePaymentIntentIdIn(String clientId, Collection<String> paymentIntentIds);

    /** Read-only: this church's donations whose posted Income row is one of {@code rowIds}. */
    @Query("select d from Donation d where d.clientId = :clientId and d.postedIncomeId in :rowIds")
    List<Donation> findCreditPostedTo(@Param("clientId") String clientId, @Param("rowIds") Collection<Integer> rowIds);

    /** Read-only: this church's donations whose posted fee Expense row is one of {@code rowIds}. */
    @Query("select d from Donation d where d.clientId = :clientId and d.postedExpenseId in :rowIds")
    List<Donation> findFeePostedTo(@Param("clientId") String clientId, @Param("rowIds") Collection<Integer> rowIds);

    /** One of this church's donations, or empty — never another church's, whatever id is sent. */
    Optional<Donation> findByIdAndClientId(Long id, String clientId);

    /**
     * The same, with the row locked for the duration of the caller's transaction
     * ({@code SELECT … FOR UPDATE}). Two posts of one donation — a double click, a
     * retry, two tabs — serialise here: the second waits, then sees the first's
     * {@code postedIncomeId} and returns "already posted" instead of posting again.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Donation d where d.id = :id and d.clientId = :clientId")
    Optional<Donation> lockByIdAndClientId(@Param("id") Long id, @Param("clientId") String clientId);

    /** This church's donations, in review order (pending first, newest first), for the reconciliation report. */
    List<Donation> findByClientIdOrderByReviewStatusAscDonatedAtDesc(String clientId);
}
