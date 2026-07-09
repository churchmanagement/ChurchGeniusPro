package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrivateAccessLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrivateAccessLogRepository extends JpaRepository<PrivateAccessLog, Long> {
    List<PrivateAccessLog> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);
    long countByClientIdAndStatus(String clientId, String status);
}
