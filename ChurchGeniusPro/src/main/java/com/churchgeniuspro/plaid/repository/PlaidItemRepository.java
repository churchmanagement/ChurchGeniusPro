package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidItemRepository extends JpaRepository<PlaidItem, Integer> {

    Optional<PlaidItem> findByItemId(String itemId);

    Optional<PlaidItem> findByIdAndClientId(Integer id, String clientId);

    List<PlaidItem> findByClientIdAndDeleteFlagFalse(String clientId);

    List<PlaidItem> findByDeleteFlagFalse();
}
