package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ImportAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportAuditRepository extends JpaRepository<ImportAudit, Long> {

    List<ImportAudit> findByRunIdOrderByCreatedDateAsc(Long runId);

    List<ImportAudit> findByRunIdAndClientIdOrderByCreatedDateAsc(Long runId, String clientId);

    List<ImportAudit> findByBatchIdOrderByCreatedDateAsc(Long batchId);
}
