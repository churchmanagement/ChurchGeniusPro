package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.EmployeeDeduction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeDeductionRepository extends JpaRepository<EmployeeDeduction, Long> {
    List<EmployeeDeduction> findByEmployeeIdAndActiveTrue(Long employeeId);
    List<EmployeeDeduction> findByEmployeeId(Long employeeId);
}
