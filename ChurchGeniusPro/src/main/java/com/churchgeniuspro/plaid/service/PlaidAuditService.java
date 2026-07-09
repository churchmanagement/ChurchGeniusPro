package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidAuditLog;
import com.churchgeniuspro.plaid.repository.PlaidAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes the Plaid financial audit trail. Best-effort: an audit failure must
 * never break the underlying financial action, so writes are wrapped and logged.
 * Never record secrets or access tokens here.
 */
@Service
public class PlaidAuditService {

    private static final Logger log = LoggerFactory.getLogger(PlaidAuditService.class);

    private final PlaidAuditLogRepository repo;

    public PlaidAuditService(PlaidAuditLogRepository repo) {
        this.repo = repo;
    }

    public void record(String clientId, String actor, String action, String targetRef, String detail) {
        try {
            PlaidAuditLog row = new PlaidAuditLog();
            row.setClientId(clientId);
            row.setActor(actor != null ? actor : "SYSTEM");
            row.setAction(action);
            row.setTargetRef(targetRef);
            row.setDetail(detail);
            repo.save(row);
        } catch (Exception e) {
            log.warn("Plaid audit write failed for action={} target={}: {}", action, targetRef, e.getMessage());
        }
    }
}
