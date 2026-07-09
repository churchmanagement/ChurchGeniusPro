package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.FederalStandardDeduction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FederalStandardDeductionRepository extends JpaRepository<FederalStandardDeduction, Long> {
    List<FederalStandardDeduction> findByEffectiveYear(Integer effectiveYear);
}
