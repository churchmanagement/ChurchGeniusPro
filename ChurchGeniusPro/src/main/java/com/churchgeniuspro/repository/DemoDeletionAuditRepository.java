package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.DemoDeletionAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Persistence for the demo-tenant deletion audit trail. */
@Repository
public interface DemoDeletionAuditRepository extends JpaRepository<DemoDeletionAudit, Long> {

    /** Most recent deletions first, capped for the Service-Admin viewer. */
    List<DemoDeletionAudit> findTop100ByOrderByDeletedAtDesc();
}
