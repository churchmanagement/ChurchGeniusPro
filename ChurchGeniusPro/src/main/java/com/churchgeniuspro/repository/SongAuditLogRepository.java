package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Append-only Song Book audit log (tenant-scoped). */
@Repository
public interface SongAuditLogRepository extends JpaRepository<SongAuditLog, Long> {

    List<SongAuditLog> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);

    /**
     * First page of the Song Book Access "Activity Log" — newest first. The id
     * tie-break makes the order total, so keyset paging never skips or repeats a row
     * when two entries share a timestamp.
     */
    @Query("SELECT l FROM SongAuditLog l WHERE l.clientId = :clientId " +
           "ORDER BY l.createdAt DESC, l.id DESC")
    List<SongAuditLog> findNewestPage(@Param("clientId") String clientId, Pageable pageable);

    /**
     * Next (older) page after the last row the client already shows, identified by its
     * (createdAt, id). Keyset rather than offset: entries logged while the admin is on
     * the page (e.g. an access change) cannot shift the window and cause duplicates.
     */
    @Query("SELECT l FROM SongAuditLog l WHERE l.clientId = :clientId " +
           "AND (l.createdAt < :beforeTs OR (l.createdAt = :beforeTs AND l.id < :beforeId)) " +
           "ORDER BY l.createdAt DESC, l.id DESC")
    List<SongAuditLog> findPageBefore(@Param("clientId") String clientId,
                                      @Param("beforeTs") Instant beforeTs,
                                      @Param("beforeId") Long beforeId,
                                      Pageable pageable);

    /** Remove a (book-)scoped client's audit rows — used when a whole Song Book scope is purged. */
    void deleteByClientId(String clientId);
}
