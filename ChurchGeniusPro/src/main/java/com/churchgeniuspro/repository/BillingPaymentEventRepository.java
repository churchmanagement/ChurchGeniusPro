package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.BillingPaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingPaymentEventRepository extends JpaRepository<BillingPaymentEvent, Long> {

    List<BillingPaymentEvent> findByInvoiceIdOrderByCreatedAtDescIdDesc(Long invoiceId);

    boolean existsByStripeEventId(String stripeEventId);
}
