package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.PayrollRun;
import com.churchgeniuspro.payroll.model.PayrollRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {
    List<PayrollRun> findByAppClientIdOrderByPayDateDesc(String appClientId);
    List<PayrollRun> findByAppClientIdAndStatus(String appClientId, PayrollRunStatus status);
}
