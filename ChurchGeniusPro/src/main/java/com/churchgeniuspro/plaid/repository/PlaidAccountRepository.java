package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidAccountRepository extends JpaRepository<PlaidAccount, Integer> {

    Optional<PlaidAccount> findByAccountId(String accountId);

    List<PlaidAccount> findByPlaidItemId(Integer plaidItemId);

    List<PlaidAccount> findByClientId(String clientId);
}
