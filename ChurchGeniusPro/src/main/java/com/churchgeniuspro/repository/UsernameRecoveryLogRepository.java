package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.UsernameRecoveryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

@Repository
public interface UsernameRecoveryLogRepository extends JpaRepository<UsernameRecoveryLog, Long> {

    /** Count attempts for a given identifier within a time window. */
    @Query("SELECT COUNT(l) FROM UsernameRecoveryLog l WHERE l.identifier = :identifier AND l.attemptedAt >= :since")
    long countByIdentifierSince(@Param("identifier") String identifier, @Param("since") LocalDateTime since);

    /** Count attempts from a given IP address within a time window. */
    @Query("SELECT COUNT(l) FROM UsernameRecoveryLog l WHERE l.ipAddress = :ip AND l.attemptedAt >= :since")
    long countByIpSince(@Param("ip") String ip, @Param("since") LocalDateTime since);
}
