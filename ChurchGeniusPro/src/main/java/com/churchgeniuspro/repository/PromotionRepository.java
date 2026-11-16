package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    /** Active deals first, then by code and frequency. */
    List<Promotion> findAllByOrderByActiveDescCodeAscBillingFrequencyAsc();

    Optional<Promotion> findByCodeAndBillingFrequency(String code, String billingFrequency);

    /** Every deal sharing a code (at most one per billing frequency). */
    List<Promotion> findByCode(String code);
}
