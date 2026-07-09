package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.FederalTaxBracket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FederalTaxBracketRepository extends JpaRepository<FederalTaxBracket, Long> {
    List<FederalTaxBracket> findByEffectiveYearOrderBySortOrderAsc(Integer effectiveYear);
    boolean existsByEffectiveYear(Integer effectiveYear);
}
