package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.NtagLoginHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NtagLoginHistoryRepository extends JpaRepository<NtagLoginHistory, Long> {
    List<NtagLoginHistory> findByClientIdOrderByLoginTimeDesc(String clientId, Pageable pageable);
    List<NtagLoginHistory> findByCredentialIdOrderByLoginTimeDesc(Long credentialId, Pageable pageable);
}
