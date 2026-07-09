package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Append-only Song Book audit log (tenant-scoped). */
@Repository
public interface SongAuditLogRepository extends JpaRepository<SongAuditLog, Long> {

    List<SongAuditLog> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);
}
