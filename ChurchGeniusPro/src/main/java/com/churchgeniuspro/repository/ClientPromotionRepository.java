package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ClientPromotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** Customer discounts. Rows are never deleted — a discount ends as EXPIRED or CANCELLED. */
public interface ClientPromotionRepository extends JpaRepository<ClientPromotion, Long> {

    List<ClientPromotion> findByClientIdOrderByIdDesc(String clientId);

    List<ClientPromotion> findByClientIdAndStatusIn(String clientId, Collection<String> statuses);

    List<ClientPromotion> findByStatusIn(Collection<String> statuses);

    /** Scheduled discounts whose start date has arrived. */
    List<ClientPromotion> findByStatusAndStartDateLessThanEqual(String status, LocalDate day);

    boolean existsByPromotionId(Long promotionId);

    boolean existsByPromotionCode(String promotionCode);
}
