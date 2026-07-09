package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidTransactionStagingRepository extends JpaRepository<PlaidTransactionStaging, Integer> {

    Optional<PlaidTransactionStaging> findByPlaidTransactionId(String plaidTransactionId);

    Optional<PlaidTransactionStaging> findByIdAndClientId(Integer id, String clientId);

    List<PlaidTransactionStaging> findByClientIdAndStatusOrderByTxnDateDesc(String clientId, String status);

    List<PlaidTransactionStaging> findByClientIdOrderByTxnDateDesc(String clientId);

    List<PlaidTransactionStaging> findByPlaidItemId(Integer plaidItemId);

    // Retention purge: remove old reviewed-rejected and bank-removed rows.
    long deleteByStatusAndReviewedDateBefore(String status, java.util.Date cutoff);

    long deleteByRemovedTrueAndUpdatedDateBefore(java.util.Date cutoff);
}
