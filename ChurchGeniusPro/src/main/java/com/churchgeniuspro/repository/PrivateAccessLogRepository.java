package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrivateAccessLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PrivateAccessLogRepository extends JpaRepository<PrivateAccessLog, Long> {
    List<PrivateAccessLog> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);
    long countByClientIdAndStatus(String clientId, String status);

    /** Newest page of one church's access log (ties broken by id so the order is total). */
    @Query("SELECT l FROM PrivateAccessLog l WHERE l.clientId = :clientId ORDER BY l.createdAt DESC, l.id DESC")
    List<PrivateAccessLog> findNewestPage(@Param("clientId") String clientId, Pageable pageable);

    /**
     * The next (older) page after the last row the page already shows, identified by its
     * (createdAt, id). Keyset rather than offset: attempts logged while the admin reads
     * the list cannot shift the window, so nothing is shown twice or skipped.
     */
    @Query("SELECT l FROM PrivateAccessLog l WHERE l.clientId = :clientId "
         + "AND (l.createdAt < :beforeTs OR (l.createdAt = :beforeTs AND l.id < :beforeId)) "
         + "ORDER BY l.createdAt DESC, l.id DESC")
    List<PrivateAccessLog> findPageBefore(@Param("clientId") String clientId,
                                          @Param("beforeTs") LocalDateTime beforeTs,
                                          @Param("beforeId") Long beforeId,
                                          Pageable pageable);
}
