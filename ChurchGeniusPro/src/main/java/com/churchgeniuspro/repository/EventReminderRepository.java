package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EventReminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventReminderRepository extends JpaRepository<EventReminder, Integer> {

    List<EventReminder> findByAppClientIdOrderByCreatedDateDesc(String appClientId);

    /** All enabled event reminders, across all organizations. Used by the scheduler. */
    List<EventReminder> findByDisabledFalse();

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<EventReminder> findByIdAndAppClientId(Integer id, String appClientId);
}
