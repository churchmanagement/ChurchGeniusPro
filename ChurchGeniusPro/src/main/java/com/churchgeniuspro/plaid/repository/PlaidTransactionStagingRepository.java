package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidTransactionStagingRepository extends JpaRepository<PlaidTransactionStaging, Integer> {

    /**
     * Financial audit M7: a Plaid {@code transaction_id} is not guaranteed unique
     * across tenants — most concretely, Trial/Demo signups routinely connect the
     * same shared Plaid sandbox test institution, whose transaction ids are
     * deterministic per institution rather than randomized per connection. Every
     * lookup that decides what to write must be scoped by {@code clientId}, or
     * one tenant's sync can silently overwrite another's staging row (replaces
     * the old unscoped {@code findByPlaidTransactionId}, which had no other
     * caller).
     */
    Optional<PlaidTransactionStaging> findByPlaidTransactionIdAndClientId(String plaidTransactionId, String clientId);

    /**
     * Atomically moves a row from a pre-review status (PENDING, or ERROR —
     * financial audit M8: Plaid sent an amount that couldn't be read, still
     * not a human decision, just PENDING's unreadable-amount twin) to a
     * terminal status — a single {@code UPDATE ... WHERE status IN (...)}
     * statement, so of two concurrent approve/reject requests for the same
     * row (a double-click, or two reviewers acting on it at once), at most
     * one can ever report a row updated. Also requires {@code removed =
     * false}, closing the same gap for a row the bank reports removed after
     * it was already loaded. The caller must check the return value (0 or 1)
     * before doing anything that can't be undone by the surrounding
     * transaction rolling back (financial audit M6).
     */
    @Modifying
    @Query("UPDATE PlaidTransactionStaging r SET r.status = :newStatus, r.reviewedBy = :actor, " +
           "r.reviewedDate = :reviewedDate, r.updatedDate = :reviewedDate " +
           "WHERE r.id = :id AND r.clientId = :clientId AND r.status IN ('PENDING', 'ERROR') AND r.removed = false")
    int claimPending(@Param("id") Integer id, @Param("clientId") String clientId,
                     @Param("newStatus") String newStatus, @Param("actor") String actor,
                     @Param("reviewedDate") Date reviewedDate);

    Optional<PlaidTransactionStaging> findByIdAndClientId(Integer id, String clientId);

    List<PlaidTransactionStaging> findByClientIdAndStatusOrderByTxnDateDesc(String clientId, String status);

    List<PlaidTransactionStaging> findByClientIdOrderByTxnDateDesc(String clientId);

    List<PlaidTransactionStaging> findByPlaidItemId(Integer plaidItemId);

    // Bank deletion: purge every staged (review-queue) row for a connection.
    long deleteByPlaidItemId(Integer plaidItemId);

    // Retention purge: remove old reviewed-rejected and bank-removed rows.
    long deleteByStatusAndReviewedDateBefore(String status, java.util.Date cutoff);

    long deleteByRemovedTrueAndUpdatedDateBefore(java.util.Date cutoff);
}
