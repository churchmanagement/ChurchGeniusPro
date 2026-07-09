package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.PlaidAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaidAuditLogRepository extends JpaRepository<PlaidAuditLog, Integer> {

    List<PlaidAuditLog> findByClientIdOrderByCreatedDateDesc(String clientId);
}
