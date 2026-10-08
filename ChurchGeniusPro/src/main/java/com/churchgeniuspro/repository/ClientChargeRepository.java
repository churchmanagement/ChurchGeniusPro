package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ClientCharge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Additional charges. Every reservation and status change is a conditional update
 * that returns how many rows it changed, so the database — not a value read a moment
 * earlier — decides whether a charge was still free, unbilled, or on the expected
 * invoice. Each bumps {@code version} so stale entity saves are refused.
 */
@Repository
public interface ClientChargeRepository extends JpaRepository<ClientCharge, Long> {

    List<ClientCharge> findTop500ByOrderByCreatedAtDescIdDesc();

    /** Unbilled charges not on any invoice (manual invoices and the review screen). */
    List<ClientCharge> findByClientIdAndStatusAndInvoiceIdIsNullOrderByCreatedAtAscIdAsc(String clientId, String status);

    /** Unbilled charges not on any invoice, for billing month {@code maxPeriod} ("YYYY-MM") or earlier (renewals). */
    List<ClientCharge> findByClientIdAndStatusAndInvoiceIdIsNullAndBillingPeriodLessThanEqualOrderByCreatedAtAscIdAsc(
            String clientId, String status, String maxPeriod);

    List<ClientCharge> findByInvoiceId(Long invoiceId);

    /** Reserves a free unbilled charge for a draft invoice. 1 = this invoice got it; 0 = it was not free any more. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.invoiceId = :invoiceId, c.version = c.version + 1 "
         + "WHERE c.id = :id AND c.invoiceId IS NULL AND c.status = 'UNBILLED'")
    int claim(@Param("id") Long id, @Param("invoiceId") Long invoiceId);

    /** Releases a charge reserved by a draft (removed from its lines). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.invoiceId = NULL, c.version = c.version + 1 "
         + "WHERE c.id = :id AND c.invoiceId = :invoiceId AND c.status = 'UNBILLED'")
    int release(@Param("id") Long id, @Param("invoiceId") Long invoiceId);

    /** Send: the invoice's reserved charges become BILLED (same transaction as DRAFT → SENT). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.status = 'BILLED', c.version = c.version + 1, c.updatedAt = :at, c.updatedBy = :actor "
         + "WHERE c.invoiceId = :invoiceId AND c.status = 'UNBILLED'")
    int billForInvoice(@Param("invoiceId") Long invoiceId, @Param("at") LocalDateTime at, @Param("actor") String actor);

    /** Failed-send compensation: the invoice's charges go back to UNBILLED, still reserved by the draft. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.status = 'UNBILLED', c.version = c.version + 1, c.updatedAt = :at, c.updatedBy = :actor "
         + "WHERE c.invoiceId = :invoiceId AND c.status = 'BILLED'")
    int unbillForInvoice(@Param("invoiceId") Long invoiceId, @Param("at") LocalDateTime at, @Param("actor") String actor);

    /** Mark paid: the invoice's charges become PAID. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.status = 'PAID', c.paidDate = :paidDate, c.paymentMethod = :method, "
         + "c.version = c.version + 1, c.updatedAt = :at, c.updatedBy = :actor "
         + "WHERE c.invoiceId = :invoiceId AND c.status IN ('UNBILLED', 'BILLED')")
    int payForInvoice(@Param("invoiceId") Long invoiceId, @Param("paidDate") LocalDate paidDate, @Param("method") String method,
                      @Param("at") LocalDateTime at, @Param("actor") String actor);

    /** Void: the invoice's charges are unbilled again and free for the next invoice. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.status = 'UNBILLED', c.invoiceId = NULL, c.version = c.version + 1, "
         + "c.updatedAt = :at, c.updatedBy = :actor "
         + "WHERE c.invoiceId = :invoiceId AND c.status IN ('UNBILLED', 'BILLED')")
    int releaseForInvoice(@Param("invoiceId") Long invoiceId, @Param("at") LocalDateTime at, @Param("actor") String actor);

    /** Voids a free unbilled charge (not on any invoice). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.status = 'VOID', c.version = c.version + 1, c.updatedAt = :at, c.updatedBy = :actor "
         + "WHERE c.id = :id AND c.status = 'UNBILLED' AND c.invoiceId IS NULL")
    int voidFree(@Param("id") Long id, @Param("at") LocalDateTime at, @Param("actor") String actor);

    /** Marks a free unbilled charge paid on its own (not on any invoice). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ClientCharge c SET c.status = 'PAID', c.paidDate = :paidDate, c.paymentMethod = :method, "
         + "c.version = c.version + 1, c.updatedAt = :at, c.updatedBy = :actor "
         + "WHERE c.id = :id AND c.status = 'UNBILLED' AND c.invoiceId IS NULL")
    int payFree(@Param("id") Long id, @Param("paidDate") LocalDate paidDate, @Param("method") String method,
                @Param("at") LocalDateTime at, @Param("actor") String actor);
}
