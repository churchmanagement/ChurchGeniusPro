package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.PayrollW4;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollW4Repository extends JpaRepository<PayrollW4, Long> {
    Optional<PayrollW4> findByEmployeeIdAndActiveTrue(Long employeeId);
    List<PayrollW4> findByEmployeeIdOrderByEffectiveDateDesc(Long employeeId);
}
