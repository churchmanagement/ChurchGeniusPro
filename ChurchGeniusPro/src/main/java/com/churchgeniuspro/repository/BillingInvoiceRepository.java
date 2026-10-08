package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.BillingInvoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Platform invoices. Status changes are conditional updates ("… WHERE status = expected"):
 * a return value of 1 means this caller made the change, 0 means someone else already did.
 */
@Repository
public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, Long> {

    List<BillingInvoice> findTop500ByOrderByIdDesc();

    Optional<BillingInvoice> findByAccessTokenHash(String accessTokenHash);

    /**
     * The invoice row, locked FOR UPDATE until the surrounding transaction ends. Send,
     * draft edits and charge edits take this lock first, so they run one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM BillingInvoice i WHERE i.id = :id")
    Optional<BillingInvoice> lockById(@Param("id") Long id);

    /**
     * A client's invoices of one kind for one BILLING DATE ({@code period_start}, set when
     * the invoice is created and never editable) in the given statuses — the renewal
     * look-up. Never by due date: the due date can be changed in review.
     */
    List<BillingInvoice> findByClientIdAndKindAndPeriodStartAndStatusIn(String clientId, String kind, LocalDate periodStart,
                                                                         Collection<String> statuses);

    /** Any invoice of one kind for a client and billing date, whatever its status. */
    List<BillingInvoice> findByClientIdAndKindAndPeriodStart(String clientId, String kind, LocalDate periodStart);

    List<BillingInvoice> findBySubscriptionRequestIdAndStatusIn(Long subscriptionRequestId, Collection<String> statuses);

    /** DRAFT → SENT with frozen totals and a new link, only if nobody edited it since {@code version} was read. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.status = 'SENT', i.version = i.version + 1, "
         + "i.subtotal = :subtotal, i.discount = :discount, i.total = :total, "
         + "i.accessTokenHash = :hash, i.accessTokenExpires = :expires, i.issueDate = :issued, "
         + "i.sentAt = :at, i.sentBy = :actor, i.sendCount = 1, i.updatedAt = :at, i.updatedBy = :actor "
         + "WHERE i.id = :id AND i.status = 'DRAFT' AND i.version = :version")
    int markSent(@Param("id") Long id, @Param("version") Long version,
                 @Param("subtotal") BigDecimal subtotal, @Param("discount") BigDecimal discount,
                 @Param("total") BigDecimal total, @Param("hash") String hash,
                 @Param("expires") LocalDate expires, @Param("issued") LocalDate issued,
                 @Param("at") LocalDateTime at, @Param("actor") String actor);

    /** Undoes {@link #markSent} when the email could not be delivered (only the send that set {@code hash}). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.status = 'DRAFT', i.version = i.version + 1, "
         + "i.accessTokenHash = NULL, i.accessTokenExpires = NULL, i.issueDate = NULL, "
         + "i.sentAt = NULL, i.sentBy = NULL, i.sendCount = NULL "
         + "WHERE i.id = :id AND i.status = 'SENT' AND i.accessTokenHash = :hash")
    int revertSend(@Param("id") Long id, @Param("hash") String hash);

    /** Replaces the link of a SENT invoice (re-send / reminder); the old link stops working. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.accessTokenHash = :newHash, i.accessTokenExpires = :expires, "
         + "i.version = i.version + 1, i.sendCount = COALESCE(i.sendCount, 0) + :countDelta, "
         + "i.sentAt = :at, i.sentBy = :actor "
         + "WHERE i.id = :id AND i.status = 'SENT' AND i.accessTokenHash = :oldHash")
    int rotateToken(@Param("id") Long id, @Param("oldHash") String oldHash, @Param("newHash") String newHash,
                    @Param("expires") LocalDate expires, @Param("countDelta") int countDelta,
                    @Param("at") LocalDateTime at, @Param("actor") String actor);

    /** Attaches the FIRST PaymentIntent to a SENT invoice. 0 = another request attached one first. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.stripePaymentIntentId = :pi, i.version = i.version + 1 "
         + "WHERE i.id = :id AND i.status = 'SENT' AND i.stripePaymentIntentId IS NULL")
    int attachFirstPaymentIntent(@Param("id") Long id, @Param("pi") String pi);

    /** Replaces an unusable (cancelled) PaymentIntent, only if the invoice still holds {@code expected}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.stripePaymentIntentId = :pi, i.version = i.version + 1 "
         + "WHERE i.id = :id AND i.status = 'SENT' AND i.stripePaymentIntentId = :expected")
    int replacePaymentIntent(@Param("id") Long id, @Param("expected") String expected, @Param("pi") String pi);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.receiptSentAt = :at WHERE i.id = :id")
    int markReceiptSent(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** SENT → PAID. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.status = 'PAID', i.version = i.version + 1, i.paidDate = :paidDate, "
         + "i.paymentMethod = :method, i.paymentReference = :reference, i.paidRecordedBy = :actor, "
         + "i.paidRecordedAt = :at, i.updatedAt = :at, i.updatedBy = :actor "
         + "WHERE i.id = :id AND i.status = 'SENT'")
    int markPaid(@Param("id") Long id, @Param("paidDate") LocalDate paidDate, @Param("method") String method,
                 @Param("reference") String reference, @Param("actor") String actor, @Param("at") LocalDateTime at);

    /** DRAFT / SENT → VOID; the link is revoked. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE BillingInvoice i SET i.status = 'VOID', i.version = i.version + 1, i.accessTokenHash = NULL, "
         + "i.accessTokenExpires = NULL, i.voidedAt = :at, i.voidedBy = :actor, i.voidReason = :reason, "
         + "i.updatedAt = :at, i.updatedBy = :actor "
         + "WHERE i.id = :id AND i.status IN ('DRAFT', 'SENT')")
    int markVoid(@Param("id") Long id, @Param("reason") String reason, @Param("actor") String actor,
                 @Param("at") LocalDateTime at);
}
