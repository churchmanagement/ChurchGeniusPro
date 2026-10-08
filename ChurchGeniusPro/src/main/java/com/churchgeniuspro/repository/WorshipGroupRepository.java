package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorshipGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorshipGroupRepository extends JpaRepository<WorshipGroup, Long> {
    List<WorshipGroup> findByClientIdAndDeleteFlagFalse(String clientId);

    /**
     * Tenant-scoped lookup. Instruments and group members carry no clientId of
     * their own — their tenant is resolved through this group.
     */
    Optional<WorshipGroup> findByIdAndClientId(Long id, String clientId);
}
