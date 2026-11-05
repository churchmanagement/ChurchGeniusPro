package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Workflow run history (AI Phase 6). Every finder is tenant-scoped. */
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {
    boolean existsByDefinitionIdAndEventKey(Long definitionId, String eventKey);
    List<WorkflowRun> findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(String appClientId, Long definitionId);
    List<WorkflowRun> findTop50ByAppClientIdOrderByCreatedAtDesc(String appClientId);
    @Query("SELECT COUNT(r) FROM WorkflowRun r WHERE r.appClientId = :cid AND r.status = 'DONE' AND r.createdAt >= :since")
    long countDoneSince(@Param("cid") String appClientId, @Param("since") LocalDateTime since);
}
