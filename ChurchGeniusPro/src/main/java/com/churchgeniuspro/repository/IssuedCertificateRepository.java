package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.IssuedCertificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Issued certificate records (tenant-scoped by client_id). */
@Repository
public interface IssuedCertificateRepository extends JpaRepository<IssuedCertificate, Long> {

    List<IssuedCertificate> findByClientIdOrderByCreatedAtDesc(String clientId);

    List<IssuedCertificate> findByClientIdAndCertTypeOrderByCreatedAtDesc(String clientId, String certType);

    long countByClientId(String clientId);
}
