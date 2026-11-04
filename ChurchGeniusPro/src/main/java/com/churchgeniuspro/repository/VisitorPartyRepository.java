package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VisitorParty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface VisitorPartyRepository extends JpaRepository<VisitorParty, Long> {
    Optional<VisitorParty> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);
    List<VisitorParty> findByClientIdAndIdInAndDeleteFlagFalse(String clientId, Collection<Long> ids);
}
