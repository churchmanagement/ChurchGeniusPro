package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidItemRepository extends JpaRepository<PlaidItem, Integer> {

    /**
     * Deliberately global: a bare Plaid webhook carries only an {@code item_id},
     * with no tenant context to scope by — discovering which tenant it belongs
     * to is the whole point of this lookup ({@code PlaidWebhookService}). Never
     * use this to decide what to write; see {@link
     * #findByItemIdAndClientId(String, String)} for that.
     */
    Optional<PlaidItem> findByItemId(String itemId);

    /**
     * Financial audit M7: linking (or re-linking) a bank must never silently
     * adopt another tenant's existing item row — the encrypted access token
     * would then point that tenant's own sync at a different church's bank
     * data. {@code PlaidLinkService} already knows the tenant it's linking for,
     * so it must look up (and decide new-vs-existing) scoped by it, not by
     * {@code item_id} alone.
     */
    Optional<PlaidItem> findByItemIdAndClientId(String itemId, String clientId);

    Optional<PlaidItem> findByIdAndClientId(Integer id, String clientId);

    List<PlaidItem> findByClientIdAndDeleteFlagFalse(String clientId);

    List<PlaidItem> findByDeleteFlagFalse();
}
