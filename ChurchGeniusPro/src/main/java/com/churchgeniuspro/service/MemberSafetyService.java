package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ContentReport;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MemberBlock;
import com.churchgeniuspro.hibernate.MemberMessage;
import com.churchgeniuspro.repository.ContentReportRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.MemberBlockRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Report & block for Members Chat (mobile Phase 2, M5): the user-generated-content
 * controls both app stores require. Members block other members and report messages;
 * church staff review reports in Follow-Ups → Reported Content.
 *
 * <p>Every method takes the caller's tenant and resolves every id inside it; a member
 * of another church is "not found" at every step.
 */
@Service
public class MemberSafetyService {

    private static final Logger log = LoggerFactory.getLogger(MemberSafetyService.class);

    public static final Set<String> REASONS = Set.of("HARASSMENT", "SPAM", "INAPPROPRIATE", "OTHER");
    public static final String BLOCKED_SEND_MESSAGE = "This member isn't accepting messages from you.";

    private final MemberBlockRepository   blocks;
    private final ContentReportRepository reports;
    private final MemberMessageRepository messages;
    private final FamilyMemberRepository  members;
    private final PublicSubmissionNotificationService notifications;

    public MemberSafetyService(MemberBlockRepository blocks, ContentReportRepository reports,
                               MemberMessageRepository messages, FamilyMemberRepository members,
                               PublicSubmissionNotificationService notifications) {
        this.blocks = blocks; this.reports = reports; this.messages = messages;
        this.members = members; this.notifications = notifications;
    }

    // ── Blocks ────────────────────────────────────────────────────────────────

    /** Ids the member has blocked — used to filter the inbox and refuse sends. */
    public Set<Integer> blockedBy(String appClientId, Integer memberId) {
        if (appClientId == null || memberId == null) return Set.of();
        return new HashSet<>(blocks.blockedIds(appClientId, memberId));
    }

    /** True when {@code recipientId} has blocked {@code senderId}: the send must be refused. */
    public boolean isBlocked(String appClientId, Integer senderId, Integer recipientId) {
        if (appClientId == null || senderId == null || recipientId == null) return false;
        return blocks.existsByAppClientIdAndBlockerMemberIdAndBlockedMemberId(appClientId, recipientId, senderId);
    }

    /** Blocks {@code targetId} for {@code selfId}. Idempotent. Empty = target not in this church. */
    @Transactional
    public Optional<Map<String, Object>> block(String appClientId, Integer selfId, Integer targetId) {
        if (selfId == null || targetId == null || selfId.equals(targetId)) return Optional.empty();
        FamilyMember target = members.findByIdAndTenant(targetId, appClientId).orElse(null);
        if (target == null) return Optional.empty();
        MemberBlock b = blocks.findByAppClientIdAndBlockerMemberIdAndBlockedMemberId(appClientId, selfId, targetId)
                .orElseGet(() -> {
                    MemberBlock nb = new MemberBlock();
                    nb.setAppClientId(appClientId);
                    nb.setBlockerMemberId(selfId);
                    nb.setBlockedMemberId(targetId);
                    nb.setCreatedAt(Instant.now());
                    return blocks.save(nb);
                });
        return Optional.of(blockMap(b, target));
    }

    @Transactional
    public boolean unblock(String appClientId, Integer selfId, Integer targetId) {
        if (selfId == null || targetId == null) return false;
        return blocks.findByAppClientIdAndBlockerMemberIdAndBlockedMemberId(appClientId, selfId, targetId)
                .map(b -> { blocks.delete(b); return true; }).orElse(false);
    }

    public List<Map<String, Object>> listBlocks(String appClientId, Integer selfId) {
        if (appClientId == null || selfId == null) return List.of();
        return blocks.findByAppClientIdAndBlockerMemberIdOrderByCreatedAtDesc(appClientId, selfId).stream()
                .map(b -> blockMap(b, members.findByIdAndTenant(b.getBlockedMemberId(), appClientId).orElse(null)))
                .toList();
    }

    private static Map<String, Object> blockMap(MemberBlock b, FamilyMember target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memberId", b.getBlockedMemberId());
        m.put("name", target == null ? "Member" : displayName(target));
        m.put("since", b.getCreatedAt() == null ? "" : b.getCreatedAt().toString());
        return m;
    }

    // ── Reports ───────────────────────────────────────────────────────────────

    public record ReportOutcome(boolean ok, int status, String error, Long reportId) {
        public static ReportOutcome fail(int status, String error) { return new ReportOutcome(false, status, error, null); }
    }

    /**
     * A member reports a message they received (or sent, in a thread they are part of).
     * Creates the report, raises the staff bell. A second open report of the same
     * message by the same member is answered OK without a duplicate.
     */
    @Transactional
    public ReportOutcome reportMessage(String appClientId, Integer selfId, Long messageId,
                                       String reason, String details) {
        if (appClientId == null || selfId == null) return ReportOutcome.fail(401, "Not authenticated");
        String r = reason == null ? "" : reason.trim().toUpperCase();
        if (!REASONS.contains(r)) return ReportOutcome.fail(400, "Please choose a reason.");
        MemberMessage msg = messageId == null ? null : messages.findById(messageId).orElse(null);
        // Only a participant of the message, in this church, may report it.
        if (msg == null || !appClientId.equals(msg.getAppClientId())
                || !(selfId.equals(msg.getRecipientMemberId()) || selfId.equals(msg.getSenderMemberId()))) {
            return ReportOutcome.fail(404, "Message not found.");
        }
        if (selfId.equals(msg.getSenderMemberId())) return ReportOutcome.fail(400, "You cannot report your own message.");
        if (reports.existsByAppClientIdAndReporterMemberIdAndContentTypeAndContentIdAndStatus(
                appClientId, selfId, ContentReport.TYPE_MEMBER_MESSAGE, messageId, ContentReport.STATUS_OPEN)) {
            return new ReportOutcome(true, 200, null, null);   // already open — nothing more to do
        }
        FamilyMember reporter = members.findByIdAndTenant(selfId, appClientId).orElse(null);
        FamilyMember reported = members.findByIdAndTenant(msg.getSenderMemberId(), appClientId).orElse(null);

        ContentReport cr = new ContentReport();
        cr.setAppClientId(appClientId);
        cr.setContentType(ContentReport.TYPE_MEMBER_MESSAGE);
        cr.setContentId(messageId);
        cr.setContentExcerpt(clip(msg.getBody(), 2000));
        cr.setReporterMemberId(selfId);
        cr.setReporterName(reporter == null ? "Member" : displayName(reporter));
        cr.setReportedMemberId(msg.getSenderMemberId());
        cr.setReportedName(reported == null ? msg.getSenderName() : displayName(reported));
        cr.setReason(r);
        cr.setDetails(clip(details, 1000));
        cr.setStatus(ContentReport.STATUS_OPEN);
        cr.setCreatedAt(Instant.now());
        reports.save(cr);

        notifications.record(appClientId, PublicSubmissionNotificationService.Type.CONTENT_REPORT,
                "Message reported: " + r.charAt(0) + r.substring(1).toLowerCase(),
                cr.getReporterName() + " reported a Members Chat message from " + cr.getReportedName() + ".",
                cr.getId());
        log.info("Content report {} created in {} (reason {})", cr.getId(), appClientId, r);
        return new ReportOutcome(true, 200, null, cr.getId());
    }

    // ── Staff review ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> listReports(String appClientId, String status) {
        if (appClientId == null) return List.of();
        List<ContentReport> rows = (status == null || status.isBlank() || "All".equalsIgnoreCase(status))
                ? reports.findByAppClientIdOrderByCreatedAtDesc(appClientId)
                : reports.findByAppClientIdAndStatusOrderByCreatedAtDesc(appClientId, status.trim().toUpperCase());
        return rows.stream().map(this::reportMap).toList();
    }

    public Map<String, Long> reportCounts(String appClientId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String s : List.of(ContentReport.STATUS_OPEN, ContentReport.STATUS_REVIEWED, ContentReport.STATUS_DISMISSED)) {
            m.put(s, appClientId == null ? 0L : reports.countByAppClientIdAndStatus(appClientId, s));
        }
        return m;
    }

    /**
     * Staff action on a report. {@code action}: DISMISS, REVIEWED, DELETE_MESSAGE
     * (soft-deletes the message for both parties, then marks the report reviewed).
     */
    @Transactional
    public Optional<Map<String, Object>> resolve(String appClientId, Long reportId, String action,
                                                 String note, String staffUsername) {
        ContentReport cr = reports.findByIdAndAppClientId(reportId, appClientId).orElse(null);
        if (cr == null) return Optional.empty();
        String a = action == null ? "" : action.trim().toUpperCase();
        switch (a) {
            case "DISMISS" -> cr.setStatus(ContentReport.STATUS_DISMISSED);
            case "REVIEWED" -> cr.setStatus(ContentReport.STATUS_REVIEWED);
            case "DELETE_MESSAGE" -> {
                if (ContentReport.TYPE_MEMBER_MESSAGE.equals(cr.getContentType())) {
                    messages.findById(cr.getContentId())
                            .filter(m -> appClientId.equals(m.getAppClientId()))
                            .ifPresent(m -> {
                                m.setDeletedBySender(true);
                                m.setDeletedByRecipient(true);
                                messages.save(m);
                            });
                }
                cr.setStatus(ContentReport.STATUS_REVIEWED);
                if (note == null || note.isBlank()) note = "Message removed";
            }
            case "REOPEN" -> cr.setStatus(ContentReport.STATUS_OPEN);
            default -> { return Optional.empty(); }
        }
        cr.setReviewedAt(Instant.now());
        cr.setReviewedBy(clip(staffUsername, 150));
        cr.setActionNote(clip(note, 500));
        reports.save(cr);
        return Optional.of(reportMap(cr));
    }

    private Map<String, Object> reportMap(ContentReport cr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cr.getId());
        m.put("contentType", cr.getContentType());
        m.put("contentId", cr.getContentId());
        m.put("excerpt", cr.getContentExcerpt() == null ? "" : cr.getContentExcerpt());
        m.put("reporterMemberId", cr.getReporterMemberId());
        m.put("reporterName", cr.getReporterName());
        m.put("reportedMemberId", cr.getReportedMemberId());
        m.put("reportedName", cr.getReportedName());
        m.put("reason", cr.getReason());
        m.put("details", cr.getDetails() == null ? "" : cr.getDetails());
        m.put("status", cr.getStatus());
        m.put("createdAt", cr.getCreatedAt() == null ? "" : cr.getCreatedAt().toString());
        m.put("reviewedAt", cr.getReviewedAt() == null ? null : cr.getReviewedAt().toString());
        m.put("reviewedBy", cr.getReviewedBy());
        m.put("actionNote", cr.getActionNote());
        boolean messageGone = ContentReport.TYPE_MEMBER_MESSAGE.equals(cr.getContentType())
                && messages.findById(cr.getContentId()).map(x -> x.isDeletedBySender() && x.isDeletedByRecipient()).orElse(true);
        m.put("messageDeleted", messageGone);
        return m;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String displayName(FamilyMember m) {
        String f = m.getFirstName() == null ? "" : m.getFirstName().trim();
        String l = m.getLastName()  == null ? "" : m.getLastName().trim();
        String n = (f + " " + l).trim();
        return n.isEmpty() ? "Member" : n;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
