package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.StagingIncome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StagingIncomeRepository extends JpaRepository<StagingIncome, Long> {

    List<StagingIncome> findByRunId(Long runId);

    List<StagingIncome> findByRunIdAndRowStatus(Long runId, String rowStatus);

    List<StagingIncome> findByBatchId(Long batchId);

    long countByRunId(Long runId);

    long countByRunIdAndRowStatus(Long runId, String rowStatus);

    void deleteByRunId(Long runId);
}
