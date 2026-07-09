package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaidWebhookEventRepository extends JpaRepository<PlaidWebhookEvent, Integer> {
}
