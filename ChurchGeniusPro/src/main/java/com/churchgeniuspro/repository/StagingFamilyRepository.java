package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.StagingFamily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StagingFamilyRepository extends JpaRepository<StagingFamily, Long> {

    List<StagingFamily> findByRunId(Long runId);

    List<StagingFamily> findByRunIdAndRowStatus(Long runId, String rowStatus);

    List<StagingFamily> findByBatchId(Long batchId);

    long countByRunId(Long runId);

    long countByRunIdAndRowStatus(Long runId, String rowStatus);

    void deleteByRunId(Long runId);
}
