package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.DeductionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeductionDefinitionRepository extends JpaRepository<DeductionDefinition, Long> {
    List<DeductionDefinition> findByAppClientIdAndActiveTrue(String appClientId);
}
