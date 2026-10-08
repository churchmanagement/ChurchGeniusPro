package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.PaystubItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaystubItemRepository extends JpaRepository<PaystubItem, Long> {
    List<PaystubItem> findByPaystubIdOrderBySortOrderAsc(Long paystubId);

    /**
     * Every item across a set of paystubs matching one category + label — used to
     * total a specific deduction's year-to-date amount so far (financial audit
     * M12a), since a {@link PaystubItem} carries no FK back to the
     * {@code DeductionDefinition} it came from, only the category/label it was
     * created with.
     */
    List<PaystubItem> findByPaystubIdInAndCategoryAndLabel(
            List<Long> paystubIds, PaystubItem.Category category, String label);
}
