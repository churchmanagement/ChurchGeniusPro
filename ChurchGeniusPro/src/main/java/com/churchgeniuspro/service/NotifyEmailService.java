package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Service for sending bulk notification emails.
 *
 * <h3>Recipient types</h3>
 * <ul>
 *   <li><b>members</b>      — all FamilyMembers with memberType = 'Member'</li>
 *   <li><b>event-guests</b> — registrants of the specified event (EventRegistration by eventId)</li>
 *   <li><b>church-guests</b>— all FamilyMembers with memberType = 'Guest'</li>
 *   <li><b>group-email</b>  — explicit comma/newline-separated list supplied by caller</li>
 * </ul>
 *
 * <p>Emails are sent BCC so individual recipients cannot see each other's addresses.
 */
@Service
public class NotifyEmailService {

    private final FamilyMemberRepository      familyMemberRepository;
    private final ChurchEventRepository       churchEventRepository;
    private final EventRegistrationRepository eventRegistrationRepository;
    private final JavaMailSender              mailSender;

    @Value("${app.mail.from:${spring.mail.username:noreply@churchgeniuspro.com}}")
    private String fromAddress;

    public NotifyEmailService(FamilyMemberRepository familyMemberRepository,
                              ChurchEventRepository churchEventRepository,
                              EventRegistrationRepository eventRegistrationRepository,
                              JavaMailSender mailSender) {
        this.familyMemberRepository      = familyMemberRepository;
        this.churchEventRepository       = churchEventRepository;
        this.eventRegistrationRepository = eventRegistrationRepository;
        this.mailSender                  = mailSender;
    }

    /**
     * The one authority on whether a tenant may send at all (Trial subscription,
     * demo tenant). This class holds its own JavaMailSender rather than going
     * through EmailService, so it must ask for itself.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.MessagingPolicy messagingPolicy;

    /** Test seam — supply the policy without a Spring context. */
    public void setMessagingPolicy(com.churchgeniuspro.service.MessagingPolicy p) { this.messagingPolicy = p; }

    /** Phase B: the verified Trial/Demo test address a blocked tenant's message is redirected to. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private TrialTestEmailService trialTestEmails;
    public void setTrialTestEmails(TrialTestEmailService s) { this.trialTestEmails = s; }

    /** The outcome of the last {@link #sendEmail} on this thread: null when it went to the real recipients. */
    private final ThreadLocal<String> lastTestAddress = new ThreadLocal<>();
    /** Masked test address the last send on this thread was redirected to, or null. */
    public String lastTestAddress() { return lastTestAddress.get(); }

    // ── Recipient resolution ──────────────────────────────────────────────

    /**
     * Returns a preview list of recipients for the given type.
     * For "group-email" the caller supplies the address list; this method
     * returns an empty list in that case (handled on the frontend).
     * For "event-guests", returns only registrants of the specified event.
     *
     * @param type      one of members | event-guests | church-guests | group-email
     * @param eventId   event ID — required for event-guests; ignored for other types
     * @param clientId  the session's tenant; every lookup is scoped to it
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecipients(String type, Integer eventId, String clientId) {
        if (clientId == null) return List.of();
        if ("event-guests".equalsIgnoreCase(type)) {
            // The event must belong to this tenant before its registrations are read.
            if (eventId == null
                    || churchEventRepository.findByIdAndAppClientIdAndDeleteFlagFalse(eventId, clientId).isEmpty()) {
                return List.of();
            }
            return eventRegistrationRepository.findByEventIdOrderByCreatedDateAsc(eventId)
                    .stream()
                    .filter(r -> r.getEmail() != null && !r.getEmail().isBlank())
                    .map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id",    r.getId());
                        row.put("name",  (r.getFirstName() != null ? r.getFirstName() : "") +
                                         " " + (r.getLastName() != null ? r.getLastName() : ""));
                        row.put("email", r.getEmail());
                        return row;
                    })
                    .collect(Collectors.toList());
        }
        List<FamilyMember> members = resolveMembers(type, clientId);
        return members.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",    m.getId());
            row.put("name",  m.getFirstName() + " " + m.getLastName());
            row.put("email", m.getEmail());
            return row;
        }).collect(Collectors.toList());
    }

    /**
     * Returns a summary list of church events for the Event Registrants event selector.
     *
     * @param appClientId  the org's clientId — if provided, only that org's events
     *                     are returned; otherwise all non-deleted events are returned.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getEvents(String appClientId) {
        List<ChurchEvent> events = (appClientId != null && !appClientId.isBlank())
                ? churchEventRepository.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId)
                : churchEventRepository.findAllByDeleteFlagFalseOrderByCreatedDateDesc();
        return events.stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",    e.getId());
                    row.put("label", e.getEventName()
                            + (e.getEventDate() != null ? " – " + e.getEventDate() : ""));
                    row.put("date",  e.getEventDate() != null ? e.getEventDate().toString() : null);
                    return row;
                })
                .collect(Collectors.toList());
    }

    // ── Email sending ─────────────────────────────────────────────────────

    /**
     * Resolves recipients then dispatches a BCC email with optional attachments.
     * Every recipient lookup is scoped to {@code clientId}, and the tenant is also
     * what lets a Trial or demo client be blocked from reaching real inboxes.
     * (The former tenant-less overload was removed: it resolved members across
     * all churches.)
     *
     * @param recipientType  one of members | event-guests | church-guests | group-email
     * @param eventId        event ID — required when recipientType = event-guests
     * @param manualEmails   comma/newline-separated addresses — used when type = group-email
     * @param subject        email subject line
     * @param htmlContent    HTML body
     * @param files          optional attachments (may be empty or null)
     * @param clientId       the session's tenant (required)
     * @return count of emails the message was dispatched to
     */
    @Transactional(readOnly = true)
    public int sendEmail(String recipientType,
                         Integer eventId,
                         String manualEmails,
                         String subject,
                         String htmlContent,
                         List<MultipartFile> files,
                         String clientId) throws Exception {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Not authenticated.");
        }
        lastTestAddress.remove();
        String testAddress = null;
        if (messagingPolicy != null) {
            String why = messagingPolicy.emailBlockReason(clientId);
            if (why != null) {
                // Phase B: one composed message is one action — it goes once to the
                // verified test address; without one the refusal stands.
                testAddress = trialTestEmails != null ? trialTestEmails.verifiedAddress(clientId) : null;
                if (testAddress == null) throw new IllegalStateException(why);
            }
        }

        List<String> emails;

        if ("group-email".equalsIgnoreCase(recipientType)) {
            emails = parseManualEmails(manualEmails);
        } else if ("event-guests".equalsIgnoreCase(recipientType)) {
            if (eventId == null) {
                throw new IllegalArgumentException("An event must be selected for Event Registrants.");
            }
            if (churchEventRepository.findByIdAndAppClientIdAndDeleteFlagFalse(eventId, clientId).isEmpty()) {
                throw new IllegalArgumentException("Event not found: " + eventId);
            }
            emails = eventRegistrationRepository.findByEventIdOrderByCreatedDateAsc(eventId)
                    .stream()
                    .map(EventRegistration::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            emails = resolveMembers(recipientType, clientId)
                    .stream()
                    .map(FamilyMember::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        }

        if (emails.isEmpty()) {
            throw new IllegalArgumentException("No recipient email addresses found.");
        }

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromAddress);
        if (testAddress != null) {
            // Real recipients are never mailed; the one test copy carries the notice.
            helper.setTo(testAddress);
            helper.setText(TrialTestEmailService.withNotice(htmlContent, emails.get(0), emails.size()), true);
            lastTestAddress.set(com.churchgeniuspro.util.EmailMask.mask(testAddress));
        } else {
            helper.setTo(fromAddress);               // visible "To" is the sender
            helper.setBcc(emails.toArray(new String[0]));
            helper.setText(htmlContent, true);        // treat as HTML
        }
        helper.setSubject(subject);

        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    String filename = Objects.requireNonNullElse(f.getOriginalFilename(), "attachment");
                    helper.addAttachment(filename, f);
                }
            }
        }

        mailSender.send(message);
        return emails.size();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private List<FamilyMember> resolveMembers(String type, String clientId) {
        // A null tenant would make the query match every church — refuse instead.
        if (type == null || clientId == null) return List.of();
        return switch (type.toLowerCase()) {
            case "members"       -> familyMemberRepository.findByMemberTypeWithEmailByAppUser("Member", clientId);
            case "church-guests" -> familyMemberRepository.findByMemberTypeWithEmailByAppUser("Guest", clientId);
            default              -> List.of();
        };
    }

    private List<String> parseManualEmails(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,\\n\\r]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.contains("@"))
                .distinct()
                .collect(Collectors.toList());
    }
}
