package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SsClassRepository extends JpaRepository<SsClass, Long> {
    List<SsClass> findByClientIdAndDeleteFlagFalse(String clientId);
    Optional<SsClass> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);
}
