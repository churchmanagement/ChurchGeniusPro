package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidAccountRepository extends JpaRepository<PlaidAccount, Integer> {

    /**
     * Financial audit M7: a Plaid {@code account_id} is not guaranteed unique
     * across tenants (see the identical note on {@code
     * PlaidTransactionStagingRepository.findByPlaidTransactionIdAndClientId}).
     * Replaces the old unscoped {@code findByAccountId}, which had no other
     * caller.
     */
    Optional<PlaidAccount> findByAccountIdAndClientId(String accountId, String clientId);

    List<PlaidAccount> findByPlaidItemId(Integer plaidItemId);

    // Bank deletion: remove account rows for a connection.
    long deleteByPlaidItemId(Integer plaidItemId);

    List<PlaidAccount> findByClientId(String clientId);
}
