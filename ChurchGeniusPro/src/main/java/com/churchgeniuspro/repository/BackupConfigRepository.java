package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.BackupConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupConfigRepository extends JpaRepository<BackupConfig, Integer> {
}
