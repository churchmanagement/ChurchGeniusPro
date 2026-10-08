package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AttendanceRecord;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.VolunteerProfile;
import com.churchgeniuspro.repository.AttendanceRecordRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Attendance → "Email / SMS Volunteers".
 *
 * <p>Lists the church's volunteers ({@link VolunteerProfile} rows joined to their
 * active {@link FamilyMember}) with a "present this week" flag computed exactly as
 * {@link AttendanceService#dashboard} computes "Volunteers present", and sends a
 * message to a chosen subset through the services every other congregation message
 * already goes through: {@link EmailService#sendOrgEmailOrThrow} (unsubscribe list,
 * monthly email allowance, Trial/demo block, org branding) and
 * {@link SmsService#sendForClient} (SMS allowance, Trial/demo block, E.164).
 *
 * <p>Every recipient gets an explicit outcome — sent, or skipped/failed with the
 * reason — so the person who pressed Send is never shown success for a message
 * that was refused. Nothing here is scheduled or stored.
 */
@Service
public class AttendanceVolunteerNotifyService {

    private final VolunteerProfileRepository volunteerRepo;
    private final FamilyMemberRepository     memberRepo;
    private final AttendanceRecordRepository recordRepo;
    private final EmailService               emailService;
    private final SmsService                 smsService;

    public AttendanceVolunteerNotifyService(VolunteerProfileRepository volunteerRepo,
                                            FamilyMemberRepository memberRepo,
                                            AttendanceRecordRepository recordRepo,
                                            EmailService emailService,
                                            SmsService smsService) {
        this.volunteerRepo = volunteerRepo;
        this.memberRepo    = memberRepo;
        this.recordRepo    = recordRepo;
        this.emailService  = emailService;
        this.smsService    = smsService;
    }

    /** One row per volunteer whose member record is active in this church. */
    public List<Map<String, Object>> listVolunteers(String clientId) {
        return listVolunteers(clientId, LocalDate.now());
    }

    /** {@code today} is a parameter so the Monday–Sunday window can be tested. */
    public List<Map<String, Object>> listVolunteers(String clientId, LocalDate today) {
        Map<Integer, FamilyMember> members = new HashMap<>();
        for (FamilyMember m : memberRepo.findAllWithFamilyByAppUser(clientId)) {
            if (m.getId() != null) members.put(m.getId(), m);
        }

        LocalDate weekStart = today.with(DayOfWeek.MONDAY), weekEnd = weekStart.plusDays(6);
        Set<Integer> presentThisWeek = recordRepo
                .findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(clientId, weekStart, weekEnd).stream()
                .filter(a -> "MEMBER".equals(a.getPersonType()) && a.getFamilyMemberId() != null && isPresent(a))
                .map(AttendanceRecord::getFamilyMemberId).collect(Collectors.toSet());

        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (VolunteerProfile v : volunteerRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(clientId)) {
            Integer mid = v.getFamilyMemberId();
            if (mid == null || !seen.add(mid)) continue;
            FamilyMember m = members.get(mid);
            if (m == null) continue;                    // inactive / deleted member → not a reachable volunteer
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", mid);
            row.put("name", ((nz(m.getFirstName()) + " " + nz(m.getLastName())).trim()));
            row.put("email", blankToNull(m.getEmail()));
            row.put("phone", blankToNull(m.getPhone()));
            row.put("status", v.getStatus());
            row.put("presentThisWeek", presentThisWeek.contains(mid));
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> String.valueOf(r.get("name")).toLowerCase()));
        return out;
    }

    /**
     * Sends to the selected volunteers. Unknown ids (another church's, or not a
     * volunteer) are ignored, never resolved. Returns {@code sentEmail}, {@code sentSms}
     * and a per-recipient {@code results} list of {name, channel, status, reason}.
     */
    public Map<String, Object> notify(String clientId, Collection<Integer> memberIds,
                                      boolean viaEmail, boolean viaSms,
                                      String subject, String body) {
        if (!viaEmail && !viaSms) throw new IllegalArgumentException("Choose Email, SMS, or both.");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Message is required.");
        if (memberIds == null || memberIds.isEmpty()) throw new IllegalArgumentException("Select at least one volunteer.");
        String subj = (subject == null || subject.isBlank()) ? "A message from your church" : subject.trim();

        Set<Integer> wanted = new HashSet<>(memberIds);
        List<Map<String, Object>> recipients = listVolunteers(clientId).stream()
                .filter(r -> wanted.contains((Integer) r.get("memberId"))).collect(Collectors.toList());
        if (recipients.isEmpty()) throw new IllegalArgumentException("None of the selected people are volunteers of this church.");

        String html = "<p>" + escape(body).replace("\n", "<br>") + "</p>";
        boolean smsReady = !viaSms || smsService.isConfigured();

        List<Map<String, Object>> results = new ArrayList<>();
        int sentEmail = 0, sentSms = 0;
        // Phase B: a Trial/Demo tenant with a verified test address gets ONE test email
        // for this message; every volunteer is simulated, none is emailed.
        EmailService.Delivery delivery = emailService.delivery(clientId);
        com.churchgeniuspro.util.EmailActionScope scope =
                com.churchgeniuspro.util.EmailActionScope.begin("attendance-volunteers:" + clientId + ":" + System.nanoTime());
        try {
        for (Map<String, Object> r : recipients) {
            String name  = String.valueOf(r.get("name"));
            String email = (String) r.get("email");
            String phone = (String) r.get("phone");
            if (viaEmail) {
                if (email == null) {
                    results.add(outcome(name, "email", "skipped", "No email address"));
                } else {
                    try {
                        int before = scope.testEmailsSent();
                        emailService.sendOrgEmailOrThrow(email, subj, html, clientId);
                        if (delivery.test()) {
                            boolean copy = scope.testEmailsSent() > before;
                            results.add(outcome(name, "email", "skipped", copy
                                    ? "Trial/Demo test copy sent to " + delivery.testEmail() + " — not sent to this volunteer"
                                    : "Trial/Demo — simulated (one test email per message)"));
                        } else {
                            sentEmail++;
                            results.add(outcome(name, "email", "sent", null));
                        }
                    } catch (IllegalStateException refused) {
                        results.add(outcome(name, "email", "skipped", refused.getMessage()));
                    } catch (Exception failed) {
                        results.add(outcome(name, "email", "failed", "Mail server error"));
                    }
                }
            }
            if (viaSms) {
                if (!smsReady) {
                    results.add(outcome(name, "sms", "skipped", "SMS is not configured"));
                } else if (phone == null) {
                    results.add(outcome(name, "sms", "skipped", "No phone number"));
                } else {
                    try {
                        SmsService.SendOutcome o = smsService.sendForClient(clientId, phone, body);
                        if (o.sent()) { sentSms++; results.add(outcome(name, "sms", "sent", null)); }
                        else results.add(outcome(name, "sms", "failed", o.reason() == null ? "Not delivered" : o.reason()));
                    } catch (Exception failed) {
                        results.add(outcome(name, "sms", "failed", "SMS provider error"));
                    }
                }
            }
        }
        } finally {
            scope.close();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("recipients", recipients.size());
        out.put("sentEmail", sentEmail);
        out.put("sentSms", sentSms);
        if (delivery.test()) {
            out.put("testEmailsSent", scope.testEmailsSent());
            out.put("testEmail",      delivery.testEmail());
        }
        out.put("results", results);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean isPresent(AttendanceRecord a) {
        return "PRESENT".equals(a.getStatus()) || "LATE".equals(a.getStatus());
    }
    private static Map<String, Object> outcome(String name, String channel, String status, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("channel", channel); m.put("status", status);
        if (reason != null) m.put("reason", reason);
        return m;
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
