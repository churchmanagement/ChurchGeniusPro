package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.HelpAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HelpAuditLogRepository extends JpaRepository<HelpAuditLog, Long> {

    List<HelpAuditLog> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);

    List<HelpAuditLog> findByClientIdAndKindOrderByCreatedAtDesc(String clientId, String kind, Pageable pageable);
}
