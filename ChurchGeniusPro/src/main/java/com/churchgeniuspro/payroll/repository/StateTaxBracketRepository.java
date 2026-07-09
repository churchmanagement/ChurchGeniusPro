package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.StateTaxBracket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StateTaxBracketRepository extends JpaRepository<StateTaxBracket, Long> {
    List<StateTaxBracket> findByStateConfigIdOrderBySortOrderAsc(Long stateConfigId);
}
