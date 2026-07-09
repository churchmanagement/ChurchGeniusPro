package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VisionUsageLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Repository for per-call {@link VisionUsageLog} audit/reporting rows. */
@Repository
public interface VisionUsageLogRepository extends JpaRepository<VisionUsageLog, Long> {

    /** Most-recent calls for a church (cap with a Pageable). */
    List<VisionUsageLog> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);

    /** All calls for a church since a cut-off, newest first (for summary totals). */
    List<VisionUsageLog> findByClientIdAndCreatedAtAfterOrderByCreatedAtDesc(String clientId, LocalDateTime since);
}
