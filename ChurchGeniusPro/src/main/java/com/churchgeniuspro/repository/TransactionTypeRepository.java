package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link TransactionType}.
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, Integer> {

    /** All non-deleted transaction types, sorted A-Z. */
    List<TransactionType> findByDeleteFlagFalseOrderByTypeNameAsc();

    /** All non-deleted transaction types, filtered by appClientId (null = no filter), sorted A-Z. */
    @Query("SELECT t FROM TransactionType t WHERE t.deleteFlag = false " +
           "AND (:appClientId IS NULL OR t.appClientId = :appClientId) " +
           "ORDER BY t.typeName ASC")
    List<TransactionType> findActiveByAppUser(@Param("appClientId") String appClientId);

    /** Duplicate-check on create — scoped to the same client. */
    boolean existsByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(String typeName, String appClientId);

    /** Duplicate-check on update (excluding the record being updated) — scoped to the same client. */
    boolean existsByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(String typeName, String appClientId, Integer id);

    /**
     * Finds an active transaction type by exact name (case-insensitive),
     * scoped to a church. Used to find-or-create a payment-method type (e.g.
     * "Card", "Bank Transfer") for a posted online donation. Financial
     * audit H9.
     */
    java.util.Optional<TransactionType> findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(
            String typeName, String appClientId);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<TransactionType> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
}
