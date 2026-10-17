package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.StagingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Migration Phase 4: staged rows of the record data types (see {@link StagingRecord}). */
public interface StagingRecordRepository extends JpaRepository<StagingRecord, Long> {
    List<StagingRecord> findByRunIdAndTargetTableOrderByIdAsc(Long runId, String targetTable);
    List<StagingRecord> findByRunIdAndTargetTableAndRowStatus(Long runId, String targetTable, String rowStatus);
    List<StagingRecord> findByBatchIdOrderByIdAsc(Long batchId);
    List<StagingRecord> findByRunId(Long runId);
    Optional<StagingRecord> findByIdAndClientId(Long id, String clientId);
    List<StagingRecord> findByIdInAndClientId(java.util.Collection<Long> ids, String clientId);

    @Modifying
    @Query("delete from StagingRecord r where r.runId = :runId and r.targetTable = :table")
    void deleteByRunIdAndTargetTable(@Param("runId") Long runId, @Param("table") String table);
}
