package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.StagingExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StagingExpenseRepository extends JpaRepository<StagingExpense, Long> {

    List<StagingExpense> findByRunId(Long runId);

    List<StagingExpense> findByRunIdAndRowStatus(Long runId, String rowStatus);

    List<StagingExpense> findByBatchId(Long batchId);

    long countByRunId(Long runId);

    long countByRunIdAndRowStatus(Long runId, String rowStatus);

    void deleteByRunId(Long runId);
}
