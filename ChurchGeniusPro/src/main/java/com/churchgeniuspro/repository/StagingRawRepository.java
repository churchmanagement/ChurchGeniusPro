package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.StagingRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StagingRawRepository extends JpaRepository<StagingRaw, Long> {

    List<StagingRaw> findByRunId(Long runId);

    List<StagingRaw> findByRunIdAndSourceTable(Long runId, String sourceTable);

    long countByRunId(Long runId);

    long countByRunIdAndSourceTable(Long runId, String sourceTable);

    /** Distinct source tables captured under a run. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT DISTINCT r.sourceTable FROM StagingRaw r WHERE r.runId = :runId ORDER BY r.sourceTable")
    List<String> findDistinctSourceTables(@org.springframework.data.repository.query.Param("runId") Long runId);

    /** Re-staging after a mapping change clears prior raw capture for the run. */
    void deleteByRunId(Long runId);

    /** Re-extracting one source table replaces only that table's rows. */
    void deleteByRunIdAndSourceTable(Long runId, String sourceTable);
}
