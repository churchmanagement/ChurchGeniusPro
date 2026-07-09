package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.FicaRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FicaRateRepository extends JpaRepository<FicaRate, Long> {
    Optional<FicaRate> findByEffectiveYear(Integer effectiveYear);
}
