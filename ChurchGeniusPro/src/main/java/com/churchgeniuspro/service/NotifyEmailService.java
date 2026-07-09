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

    // ── Recipient resolution ──────────────────────────────────────────────

    /**
     * Returns a preview list of recipients for the given type.
     * For "group-email" the caller supplies the address list; this method
     * returns an empty list in that case (handled on the frontend).
     * For "event-guests", returns only registrants of the specified event.
     *
     * @param type     one of members | event-guests | church-guests | group-email
     * @param eventId  event ID — required for event-guests; ignored for other types
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecipients(String type, Integer eventId) {
        if ("event-guests".equalsIgnoreCase(type)) {
            if (eventId == null) return List.of();
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
        List<FamilyMember> members = resolveMembers(type);
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
     *
     * @param recipientType  one of members | event-guests | church-guests | group-email
     * @param eventId        event ID — required when recipientType = event-guests
     * @param manualEmails   comma/newline-separated addresses — used when type = group-email
     * @param subject        email subject line
     * @param htmlContent    HTML body
     * @param files          optional attachments (may be empty or null)
     * @return count of emails the message was dispatched to
     */
    @Transactional(readOnly = true)
    public int sendEmail(String recipientType,
                         Integer eventId,
                         String manualEmails,
                         String subject,
                         String htmlContent,
                         List<MultipartFile> files) throws Exception {

        List<String> emails;

        if ("group-email".equalsIgnoreCase(recipientType)) {
            emails = parseManualEmails(manualEmails);
        } else if ("event-guests".equalsIgnoreCase(recipientType)) {
            if (eventId == null) {
                throw new IllegalArgumentException("An event must be selected for Event Registrants.");
            }
            emails = eventRegistrationRepository.findByEventIdOrderByCreatedDateAsc(eventId)
                    .stream()
                    .map(EventRegistration::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            emails = resolveMembers(recipientType)
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
        helper.setTo(fromAddress);               // visible "To" is the sender
        helper.setBcc(emails.toArray(new String[0]));
        helper.setSubject(subject);
        helper.setText(htmlContent, true);        // treat as HTML

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

    private List<FamilyMember> resolveMembers(String type) {
        if (type == null) return List.of();
        return switch (type.toLowerCase()) {
            case "members"       -> familyMemberRepository.findByMemberTypeWithEmail("Member");
            case "church-guests" -> familyMemberRepository.findByMemberTypeWithEmail("Guest");
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
