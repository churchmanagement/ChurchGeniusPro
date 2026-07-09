package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.PayrollAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollAuditLogRepository extends JpaRepository<PayrollAuditLog, Long> {
    List<PayrollAuditLog> findByAppClientIdAndEntityTypeAndEntityIdOrderByCreatedDesc(
            String appClientId, String entityType, Long entityId);
    List<PayrollAuditLog> findByAppClientIdOrderByCreatedDesc(String appClientId);
}
