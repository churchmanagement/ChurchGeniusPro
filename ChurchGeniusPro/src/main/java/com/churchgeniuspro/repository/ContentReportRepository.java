package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ContentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    List<ContentReport> findByAppClientIdOrderByCreatedAtDesc(String appClientId);

    List<ContentReport> findByAppClientIdAndStatusOrderByCreatedAtDesc(String appClientId, String status);

    Optional<ContentReport> findByIdAndAppClientId(Long id, String appClientId);

    long countByAppClientIdAndStatus(String appClientId, String status);

    boolean existsByAppClientIdAndReporterMemberIdAndContentTypeAndContentIdAndStatus(
            String appClientId, Integer reporterMemberId, String contentType, Long contentId, String status);
}
