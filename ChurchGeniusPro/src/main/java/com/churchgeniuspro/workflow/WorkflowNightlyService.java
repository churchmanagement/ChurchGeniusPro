package com.churchgeniuspro.workflow;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.MemberEngagementSnapshot;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.MemberEngagementSnapshotRepository;
import com.churchgeniuspro.repository.WorkflowDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The two nightly triggers (AI Phase 6): {@code ENGAGEMENT_SIGNAL_CHANGED} (a member's latest snapshot
 * signal differs from the previous snapshot day's — runs after the 02:30 snapshot) and
 * {@code FOLLOW_UP_OVERDUE} (open follow-ups past their due date; once per task per day). Only churches
 * with at least one ENABLED rule for the trigger are visited, so the pass costs nothing for the rest.
 * Event keys carry the date, so the engine's idempotency rule makes a re-run harmless.
 */
@Service
public class WorkflowNightlyService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNightlyService.class);
    public static final ZoneId CHURCH_ZONE = ZoneId.of("America/Chicago");

    private final WorkflowDefinitionRepository definitions;
    private final MemberEngagementSnapshotRepository snapshots;
    private final FamilyMemberRepository members;
    private final FollowUpRepository followUps;
    private final WorkflowEvents events;

    public WorkflowNightlyService(WorkflowDefinitionRepository definitions, MemberEngagementSnapshotRepository snapshots,
                                  FamilyMemberRepository members, FollowUpRepository followUps, WorkflowEvents events) {
        this.definitions = definitions; this.snapshots = snapshots; this.members = members; this.followUps = followUps; this.events = events;
    }

    /** 03:20 Chicago, after the engagement snapshot (02:30). */
    @Scheduled(cron = "${workflow.nightly-cron:0 20 3 * * *}", zone = "America/Chicago")
    public void nightly() {
        if (events == null || !events.enabled()) return;
        LocalDate today = LocalDate.now(CHURCH_ZONE);
        int n = 0;
        for (String cid : safe(() -> definitions.clientsWithEnabledTrigger("ENGAGEMENT_SIGNAL_CHANGED"))) n += signalChanges(cid, today);
        for (String cid : safe(() -> definitions.clientsWithEnabledTrigger("FOLLOW_UP_OVERDUE"))) n += overdue(cid, today);
        log.info("[workflow] nightly pass published {} event(s)", n);
    }

    /** Members whose latest snapshot signal differs from the previous snapshot day's. */
    public int signalChanges(String cid, LocalDate today) {
        LocalDate latest = snapshots.latestDateOnOrBefore(cid, today);
        if (latest == null) return 0;
        LocalDate previous = snapshots.latestDateOnOrBefore(cid, latest.minusDays(1));
        if (previous == null) return 0;
        Map<Integer, String> before = new HashMap<>();
        for (MemberEngagementSnapshot s : snapshots.findByAppClientIdAndSnapshotDate(cid, previous)) before.put(s.getMemberId(), s.getSignal());
        int n = 0;
        for (MemberEngagementSnapshot s : snapshots.findByAppClientIdAndSnapshotDate(cid, latest)) {
            String prev = before.get(s.getMemberId());
            if (prev == null || prev.equals(s.getSignal())) continue;
            FamilyMember m = members.findByIdAndTenant(s.getMemberId(), cid).orElse(null);
            if (m == null) continue;
            String name = ((m.getFirstName() == null ? "" : m.getFirstName()) + " " + (m.getLastName() == null ? "" : m.getLastName())).trim();
            events.publish(WorkflowEvent.of("ENGAGEMENT_SIGNAL_CHANGED", cid, "signal:" + s.getMemberId() + ":" + latest, name + " → " + s.getSignal(),
                    WorkflowEvent.facts("name", name, "memberId", s.getMemberId(), "familyId", m.getFamily() == null ? null : m.getFamily().getId(),
                                        "signal", s.getSignal(), "previousSignal", prev, "date", latest.toString())));
            n++;
        }
        return n;
    }

    /** Open (PENDING / MISSED) follow-ups past their due date — one event per task per day. */
    public int overdue(String cid, LocalDate today) {
        int n = 0;
        for (String status : List.of("PENDING", "MISSED")) {
            for (FollowUp f : followUps.findByClientIdAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(cid, status)) {
                if (f.getDueDate() == null) continue;
                LocalDate due = f.getDueDate().toInstant().atZone(CHURCH_ZONE).toLocalDate();
                if (!due.isBefore(today)) continue;
                long days = ChronoUnit.DAYS.between(due, today);
                events.publish(WorkflowEvent.of("FOLLOW_UP_OVERDUE", cid, "overdue:" + f.getId() + ":" + today, f.getTitle(),
                        WorkflowEvent.facts("title", f.getTitle(), "assignedTo", f.getAssignedTo() == null ? "" : f.getAssignedTo(), "daysOverdue", days,
                                            "linkedType", f.getLinkedType() == null ? "" : f.getLinkedType(), "linkedLabel", f.getLinkedLabel() == null ? "" : f.getLinkedLabel(),
                                            "priority", f.getPriority() == null ? "" : f.getPriority(), "followUpId", f.getId())));
                n++;
            }
        }
        return n;
    }

    private static List<String> safe(java.util.function.Supplier<List<String>> s) {
        try { List<String> l = s.get(); return l == null ? List.of() : l; } catch (RuntimeException e) { log.warn("[workflow] nightly lookup failed: {}", e.getClass().getSimpleName()); return List.of(); }
    }
}
