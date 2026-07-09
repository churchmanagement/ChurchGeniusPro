package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.NtagCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NtagCredentialRepository extends JpaRepository<NtagCredential, Long> {
    List<NtagCredential> findByClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(String clientId);
    Optional<NtagCredential> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);
    Optional<NtagCredential> findByNtagSerialAndDeleteFlagFalse(String ntagSerial);
    boolean existsByNtagSerialAndDeleteFlagFalse(String ntagSerial);
}
