package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ReminderSentLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface ReminderSentLogRepository extends JpaRepository<ReminderSentLog, Long> {

    /**
     * Returns {@code true} when a log row already exists for this exact combination,
     * meaning the reminder was already sent today and should be skipped.
     */
    boolean existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
            String appClientId,
            String reminderType,
            String referenceKey,
            LocalDate sentDate);
}
