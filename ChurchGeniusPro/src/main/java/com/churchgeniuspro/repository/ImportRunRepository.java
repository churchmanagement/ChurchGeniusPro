package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ImportRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportRunRepository extends JpaRepository<ImportRun, Long> {

    /** Tenant-scoped listing — never list across clients. */
    List<ImportRun> findByClientIdOrderByCreatedDateDesc(String clientId);

    /** Tenant-scoped fetch — guards against IDOR (id from one tenant, data from another). */
    Optional<ImportRun> findByIdAndClientId(Long id, String clientId);

    List<ImportRun> findByClientIdAndStatusOrderByCreatedDateDesc(String clientId, String status);

    /**
     * Migration Phase 5: the Service Admin home card — the most recently touched runs across
     * ALL churches. Service-admin only (the endpoint sits behind ServiceAdminAuthFilter);
     * never used on a church-facing path.
     */
    List<ImportRun> findTop50ByStatusInOrderByUpdatedDateDesc(java.util.Collection<String> statuses);
}
