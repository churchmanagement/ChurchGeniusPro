package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.BackupLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BackupLogRepository extends JpaRepository<BackupLog, Integer> {

    /** Most-recent 20 backup log entries, newest first. */
    @Query("SELECT l FROM BackupLog l ORDER BY l.runAt DESC LIMIT 20")
    List<BackupLog> findRecent();
}
