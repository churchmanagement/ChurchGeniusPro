package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Workflow rules (AI Phase 6). Every finder is tenant-scoped. */
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, Long> {
    List<WorkflowDefinition> findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(String appClientId);
    List<WorkflowDefinition> findByAppClientIdAndTriggerAndEnabledTrueAndDeleteFlagFalse(String appClientId, String trigger);
    Optional<WorkflowDefinition> findByIdAndAppClientIdAndDeleteFlagFalse(Long id, String appClientId);
    long countByAppClientIdAndDeleteFlagFalse(String appClientId);
    boolean existsByAppClientIdAndNameIgnoreCaseAndDeleteFlagFalse(String appClientId, String name);
    /** Churches with at least one enabled rule for a trigger — the nightly pass works only for those. */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT d.appClientId FROM WorkflowDefinition d WHERE d.trigger = :trigger AND d.enabled = true AND d.deleteFlag = false")
    List<String> clientsWithEnabledTrigger(@org.springframework.data.repository.query.Param("trigger") String trigger);
}
