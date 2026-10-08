package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.BillingInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingInvoiceLineRepository extends JpaRepository<BillingInvoiceLine, Long> {

    List<BillingInvoiceLine> findByInvoiceIdOrderBySortOrderAscIdAsc(Long invoiceId);
}
