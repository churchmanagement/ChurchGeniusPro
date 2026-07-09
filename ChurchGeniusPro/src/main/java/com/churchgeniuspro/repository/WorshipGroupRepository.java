package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorshipGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorshipGroupRepository extends JpaRepository<WorshipGroup, Long> {
    List<WorshipGroup> findByClientIdAndDeleteFlagFalse(String clientId);
}
