package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link SubscriptionPlan} entities.
 */
@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {

    Optional<SubscriptionPlan> findByPlanCodeIgnoreCase(String planCode);

    boolean existsByPlanCodeIgnoreCase(String planCode);

    List<SubscriptionPlan> findAllByOrderBySortOrderAscIdAsc();
}
