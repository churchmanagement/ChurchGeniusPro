package com.churchgeniuspro.payroll.repository;

import com.churchgeniuspro.payroll.entity.PaystubItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaystubItemRepository extends JpaRepository<PaystubItem, Long> {
    List<PaystubItem> findByPaystubIdOrderBySortOrderAsc(Long paystubId);
}
