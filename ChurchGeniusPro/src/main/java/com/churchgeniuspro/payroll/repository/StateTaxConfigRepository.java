package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.StateTaxConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StateTaxConfigRepository extends JpaRepository<StateTaxConfig, Long> {
    Optional<StateTaxConfig> findByEffectiveYearAndStateCode(Integer effectiveYear, String stateCode);
}
