package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ImportLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Migration Phase 3: persistent source-id → live-id links (and reference rows created by imports). */
public interface ImportLinkRepository extends JpaRepository<ImportLink, Long> {
    Optional<ImportLink> findByClientIdAndSourceSystemAndEntityAndSourceKey(String clientId, String sourceSystem, String entity, String sourceKey);
    List<ImportLink> findByClientIdAndSourceSystemAndEntity(String clientId, String sourceSystem, String entity);
    List<ImportLink> findByRunId(Long runId);
    List<ImportLink> findByBatchId(Long batchId);
    List<ImportLink> findByClientIdAndEntityAndTargetId(String clientId, String entity, Long targetId);
    void deleteByBatchId(Long batchId);
}
