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
}
