package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollEmployeeRepository extends JpaRepository<PayrollEmployee, Long> {
    List<PayrollEmployee> findByAppClientIdAndActiveTrue(String appClientId);
    List<PayrollEmployee> findByAppClientId(String appClientId);
}
