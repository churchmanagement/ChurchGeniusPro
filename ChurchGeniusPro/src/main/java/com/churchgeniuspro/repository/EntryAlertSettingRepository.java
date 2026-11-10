package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EntryAlertSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** A login's own Income / Expense alert thresholds (one row per church + login + role + kind). */
public interface EntryAlertSettingRepository extends JpaRepository<EntryAlertSetting, Long> {

    Optional<EntryAlertSetting> findByAppClientIdAndUsernameAndRoleAndKind(String appClientId, String username, String role, String kind);

    /** Removes every alert setting of a login (used when the login is deleted). */
    @Modifying
    @Transactional
    @Query("DELETE FROM EntryAlertSetting s WHERE s.appClientId = :cid AND s.username = :username")
    int deleteForLogin(String cid, String username);
}
