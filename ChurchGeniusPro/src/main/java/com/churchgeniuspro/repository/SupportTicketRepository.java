package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    /** A church's own tickets, newest first. */
    List<SupportTicket> findByClientIdOrderByCreatedAtDesc(String clientId);

    /** Tenant-scoped detail lookup — a ticket id from another church resolves to empty. */
    Optional<SupportTicket> findByIdAndClientId(Long id, String clientId);

    boolean existsByReference(String reference);

    /** Every church's tickets for the Service Admin, newest first. */
    List<SupportTicket> findAllByOrderByCreatedAtDesc();

    List<SupportTicket> findByStatusOrderByCreatedAtDesc(String status);
}
