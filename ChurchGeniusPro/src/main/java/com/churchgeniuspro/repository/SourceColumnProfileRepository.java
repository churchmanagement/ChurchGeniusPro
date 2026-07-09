package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SourceColumnProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SourceColumnProfileRepository extends JpaRepository<SourceColumnProfile, Long> {

    List<SourceColumnProfile> findByRunIdOrderBySourceTableAscOrdinalAsc(Long runId);

    List<SourceColumnProfile> findByRunIdAndSourceTableOrderByOrdinalAsc(Long runId, String sourceTable);

    void deleteByRunId(Long runId);

    void deleteByRunIdAndSourceTable(Long runId, String sourceTable);
}
