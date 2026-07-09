package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.FinalizedSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Finalized Song Book sections (tenant-scoped). */
@Repository
public interface FinalizedSectionRepository extends JpaRepository<FinalizedSection, Long> {

    List<FinalizedSection> findByClientIdOrderBySortOrderAsc(String clientId);

    Optional<FinalizedSection> findByIdAndClientId(Long id, String clientId);

    Optional<FinalizedSection> findFirstByClientIdAndNameIgnoreCase(String clientId, String name);

    long countByClientId(String clientId);
}
