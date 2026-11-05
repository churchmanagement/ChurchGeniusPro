package com.churchgeniuspro.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The one door domain services use to announce an event (AI Phase 6). Publishing never throws and does
 * nothing when the engine is switched off ({@code workflow.enabled=false}): the hooks in
 * VisitorCheckInService, AttendanceService, FollowUpController, PrayerRequestController and IncomeService
 * stay inert. Delivery is Spring's event bus; the engine listens AFTER COMMIT so a rule never sees a row
 * that was rolled back.
 */
@Component
public class WorkflowEvents {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEvents.class);
    private final ApplicationEventPublisher publisher;
    private final boolean enabled;

    public WorkflowEvents(ApplicationEventPublisher publisher, @Value("${workflow.enabled:true}") boolean enabled) {
        this.publisher = publisher; this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public void publish(WorkflowEvent event) {
        if (!enabled || event == null || publisher == null) return;
        if (event.clientId() == null || event.clientId().isBlank() || !WorkflowSchema.TRIGGERS.containsKey(event.trigger())) return;
        try { publisher.publishEvent(event); }
        catch (RuntimeException e) { log.warn("[workflow] publish failed for {}: {}", event.trigger(), e.getClass().getSimpleName()); }
    }

    /** Null-safe call for services that hold the publisher as an optional field. */
    public static void publish(WorkflowEvents events, WorkflowEvent event) { if (events != null) events.publish(event); }
}
