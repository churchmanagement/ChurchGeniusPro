package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MappingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MappingRuleRepository extends JpaRepository<MappingRule, Long> {

    List<MappingRule> findByRunIdOrderByTargetTableAscTargetColumnAsc(Long runId);

    List<MappingRule> findByRunIdAndTargetTable(Long runId, String targetTable);

    List<MappingRule> findByRunIdAndStatus(Long runId, String status);

    Optional<MappingRule> findByRunIdAndTargetTableAndTargetColumn(Long runId, String targetTable, String targetColumn);
}
