package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ChurchDeletionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChurchDeletionRequestRepository extends JpaRepository<ChurchDeletionRequest, Long> {
    Optional<ChurchDeletionRequest> findFirstByChurchClientIdAndStatusInOrderByIdDesc(String churchClientId, Collection<String> statuses);
    Optional<ChurchDeletionRequest> findFirstByChurchClientIdOrderByIdDesc(String churchClientId);
    Optional<ChurchDeletionRequest> findByTokenHash(String tokenHash);
    List<ChurchDeletionRequest> findTop200ByOrderByIdDesc();
}
