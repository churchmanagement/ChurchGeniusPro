package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ImportFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportFileRepository extends JpaRepository<ImportFile, Long> {
    List<ImportFile> findByRunIdOrderByCreatedDateAsc(Long runId);
    List<ImportFile> findByClientIdAndSha256(String clientId, String sha256);
    List<ImportFile> findByRunIdAndSourceTable(Long runId, String sourceTable);
}
