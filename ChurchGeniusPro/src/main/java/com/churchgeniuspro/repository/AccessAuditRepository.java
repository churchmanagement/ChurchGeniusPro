package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AccessAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessAuditRepository extends JpaRepository<AccessAudit, Long> {

    List<AccessAudit> findByTemporaryAccessIdOrderByLoginTimeDesc(Long temporaryAccessId);
}
