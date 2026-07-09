package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.Paystub;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaystubRepository extends JpaRepository<Paystub, Long> {
    List<Paystub> findByRunId(Long runId);
    List<Paystub> findByAppClientIdAndEmployeeIdOrderByPayDateDesc(String appClientId, Long employeeId);
    List<Paystub> findByAppClientId(String appClientId);

    /** True if the employee has any payroll history (used to block hard-delete). */
    boolean existsByAppClientIdAndEmployeeId(String appClientId, Long employeeId);
}
