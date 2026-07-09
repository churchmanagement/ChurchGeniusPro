package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsClassRepository extends JpaRepository<SsClass, Long> {
    List<SsClass> findByClientIdAndDeleteFlagFalse(String clientId);
}
