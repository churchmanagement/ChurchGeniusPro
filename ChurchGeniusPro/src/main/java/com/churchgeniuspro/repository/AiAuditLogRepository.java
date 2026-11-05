package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AiAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** {@code ai_audit_log} — every read is tenant-scoped; paging is keyset (newest first). */
@Repository
public interface AiAuditLogRepository extends JpaRepository<AiAuditLog, Long> {

    @Query("SELECT l FROM AiAuditLog l WHERE l.clientId = :clientId ORDER BY l.createdAt DESC, l.id DESC")
    List<AiAuditLog> findNewestPage(@Param("clientId") String clientId, Pageable pageable);

    @Query("SELECT l FROM AiAuditLog l WHERE l.clientId = :clientId "
         + "AND (l.createdAt < :beforeTs OR (l.createdAt = :beforeTs AND l.id < :beforeId)) "
         + "ORDER BY l.createdAt DESC, l.id DESC")
    List<AiAuditLog> findPageBefore(@Param("clientId") String clientId,
                                    @Param("beforeTs") LocalDateTime beforeTs,
                                    @Param("beforeId") Long beforeId,
                                    Pageable pageable);

    /** Per-channel counts and token totals for one church since {@code since}. */
    @Query("SELECT l.channel, COUNT(l), COALESCE(SUM(l.promptTokens),0), COALESCE(SUM(l.completionTokens),0), "
         + "SUM(CASE WHEN l.outcome <> 'OK' THEN 1 ELSE 0 END) "
         + "FROM AiAuditLog l WHERE l.clientId = :clientId AND l.createdAt >= :since GROUP BY l.channel ORDER BY l.channel")
    List<Object[]> summaryByChannel(@Param("clientId") String clientId, @Param("since") LocalDateTime since);

    /** Service Admin: per-church counts and token totals since {@code since} (every tenant, read-only). */
    @Query("SELECT l.clientId, COUNT(l), COALESCE(SUM(l.promptTokens),0), COALESCE(SUM(l.completionTokens),0), "
         + "SUM(CASE WHEN l.outcome = 'RATE_LIMITED' THEN 1 ELSE 0 END), MAX(l.createdAt) "
         + "FROM AiAuditLog l WHERE l.createdAt >= :since GROUP BY l.clientId ORDER BY COUNT(l) DESC")
    List<Object[]> summaryByTenant(@Param("since") LocalDateTime since);

    /** Rows one actor made for one church since {@code since} — the per-user rate-limit fallback count. */
    long countByClientIdAndActorAndCreatedAtGreaterThanEqual(String clientId, String actor, LocalDateTime since);

    long countByClientIdAndCreatedAtGreaterThanEqual(String clientId, LocalDateTime since);
}
