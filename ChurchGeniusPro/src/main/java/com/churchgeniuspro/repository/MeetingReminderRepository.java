package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MeetingReminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingReminderRepository extends JpaRepository<MeetingReminder, Integer> {

    List<MeetingReminder> findByAppClientIdOrderByCreatedDateDesc(String appClientId);

    List<MeetingReminder> findByMeetingIdAndDisabledFalse(Integer meetingId);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<MeetingReminder> findByIdAndAppClientId(Integer id, String appClientId);
}
