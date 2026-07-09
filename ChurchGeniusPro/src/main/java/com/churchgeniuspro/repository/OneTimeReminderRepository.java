package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.OneTimeReminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface OneTimeReminderRepository extends JpaRepository<OneTimeReminder, Integer> {

    List<OneTimeReminder> findByAppClientIdOrderByCreatedDateDesc(String appClientId);

    List<OneTimeReminder> findByAppClientIdAndDisabledFalse(String appClientId);

    /** All enabled one-time reminders whose event date matches today. Used by the scheduler. */
    List<OneTimeReminder> findByEventDateAndDisabledFalse(LocalDate eventDate);
}
