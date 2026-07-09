package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    List<ImportBatch> findByRunIdOrderByIdAsc(Long runId);

    Optional<ImportBatch> findByIdAndClientId(Long id, String clientId);

    List<ImportBatch> findByRunIdAndTargetTable(Long runId, String targetTable);
}
