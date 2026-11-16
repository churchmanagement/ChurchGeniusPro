package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PromotionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Insert and read only — promotion history is never updated or deleted. */
public interface PromotionHistoryRepository extends JpaRepository<PromotionHistory, Long> {

    List<PromotionHistory> findByPromotionIdOrderByOccurredAtDescIdDesc(Long promotionId);

    List<PromotionHistory> findByClientIdOrderByOccurredAtDescIdDesc(String clientId);
}
