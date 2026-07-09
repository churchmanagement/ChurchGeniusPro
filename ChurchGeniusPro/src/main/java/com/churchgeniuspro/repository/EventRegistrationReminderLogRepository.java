package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EventRegistrationReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link EventRegistrationReminderLog} entities.
 */
@Repository
public interface EventRegistrationReminderLogRepository
        extends JpaRepository<EventRegistrationReminderLog, Long> {

    /**
     * Duplicate-prevention check: true when a message of this type (REMINDER /
     * INVITE) was already SENT to this normalized recipient over this channel
     * for this event. Scoped per message type so a sent invitation never
     * blocks the later register-reminder, and vice versa.
     */
    boolean existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
            Integer eventId, String messageType, String channel, String recipientNorm, String status);

    /** Full attempt history for an event, newest first (for admin display / audit). */
    List<EventRegistrationReminderLog> findByEventIdOrderByCreatedDateDesc(Integer eventId);
}
