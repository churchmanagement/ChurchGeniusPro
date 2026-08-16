package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ConnectSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectSubmissionRepository extends JpaRepository<ConnectSubmission, Long> {

    List<ConnectSubmission> findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(String clientId);

    List<ConnectSubmission> findByClientIdAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(String clientId, String status);

    Optional<ConnectSubmission> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);

    long countByClientIdAndStatusAndDeleteFlagFalse(String clientId, String status);
}
