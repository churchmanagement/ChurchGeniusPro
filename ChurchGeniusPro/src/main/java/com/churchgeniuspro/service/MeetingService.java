package com.churchgeniuspro.service;

import com.churchgeniuspro.common.States;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.model.MeetingBO;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingTypeRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service layer for {@link Meeting} persistence and retrieval.
 */
@Service
public class MeetingService {

    private final MeetingRepository        meetingRepository;
    private final MeetingTypeRepository    meetingTypeRepository;
    private final FamilyMemberRepository   familyMemberRepository;
    private final FamilyRepository         familyRepository;
    private final EmailService             emailService;
    private final WhatsAppSenderService    whatsAppSender;

    public MeetingService(MeetingRepository meetingRepository,
                          MeetingTypeRepository meetingTypeRepository,
                          FamilyMemberRepository familyMemberRepository,
                          FamilyRepository familyRepository,
                          EmailService emailService,
                          WhatsAppSenderService whatsAppSender) {
        this.meetingRepository      = meetingRepository;
        this.meetingTypeRepository  = meetingTypeRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.familyRepository       = familyRepository;
        this.emailService           = emailService;
        this.whatsAppSender         = whatsAppSender;
    }

    // ── Locations (for the dropdown) ──────────────────────────────────────

    /**
     * Returns all non-deleted family members who have their own address
     * (sameAsFamilyAddress = false), suitable for the Location dropdown.
     * Each entry carries a {@code locType} of {@code "member"} so the frontend
     * knows which ID to send back on save.
     * Results are sorted alphabetically by display name.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLocations(String appClientId) {
        List<Map<String, Object>> result = new ArrayList<>();

        // ── Members with own address ──────────────────────────────────────
        for (FamilyMember m : familyMemberRepository.findAllMembersWithOwnAddressByAppUser(appClientId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("locType",     "member");
            row.put("id",          m.getId());
            row.put("displayName", (m.getFirstName() != null ? m.getFirstName() : "")
                                   + " " + (m.getLastName() != null ? m.getLastName() : ""));
            row.put("address1",    m.getAddress1());
            row.put("address2",    m.getAddress2());
            row.put("city",        m.getCity());
            row.put("state",       m.getState());
            row.put("country",     m.getCountry());
            row.put("pinCode",     m.getPinCode());
            result.add(row);
        }

        return result;
    }

    // ── Last times by meeting type (for auto-fill) ────────────────────────

    /**
     * Returns the {@code startTime} and {@code endTime} from the most recent
     * non-deleted meeting of the given type.  Returns an empty map when no
     * previous meeting of that type exists.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLastTimesByMeetingType(Integer meetingTypeId, String appClientId) {
        List<Meeting> meetings = meetingRepository.findByMeetingTypeIdOrderByDateDescByAppUser(
                meetingTypeId, appClientId);
        if (meetings.isEmpty()) return Map.of();
        Meeting last = meetings.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startTime", last.getStartTime());
        result.put("endTime",   last.getEndTime());
        return result;
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAll(String appClientId) {
        return meetingRepository.findAllActiveByAppUserOrderByDateAsc(appClientId)
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    // ── Get by ID ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getById(Integer id, String appClientId) {
        Meeting m = findOrThrow(id, appClientId);
        return toMap(m);
    }

    // ── Save (create) ─────────────────────────────────────────────────────

    @Transactional
    public Meeting save(MeetingBO bo, String appClientId) {
        Meeting m = new Meeting();
        m.setAppClientId(appClientId);
        applyBO(bo, m, appClientId);
        return meetingRepository.save(m);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public Meeting update(Integer id, MeetingBO bo, String appClientId) {
        Meeting m = findOrThrow(id, appClientId);
        applyBO(bo, m, appClientId);
        return meetingRepository.save(m);
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id, String appClientId) {
        Meeting m = findOrThrow(id, appClientId);
        m.setDeleteFlag(true);
        meetingRepository.save(m);
    }

    // ── Bulk Soft-Delete ──────────────────────────────────────────────────

    /** Soft-deletes the given ids that belong to {@code appClientId}; foreign ids are silently skipped. */
    @Transactional
    public int deleteBulk(List<Integer> ids, String appClientId) {
        List<Meeting> toDelete = meetingRepository.findByIdInAndAppClientIdAndDeleteFlagFalse(ids, appClientId);
        toDelete.forEach(m -> m.setDeleteFlag(true));
        meetingRepository.saveAll(toDelete);
        return toDelete.size();
    }

    // ── Image ─────────────────────────────────────────────────────────────

    @Transactional
    public void saveImage(Integer meetingId, byte[] data, String contentType, String appClientId) {
        Meeting m = findOrThrow(meetingId, appClientId);
        m.setImageData(data);
        m.setImageContentType(contentType);
        meetingRepository.save(m);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getImage(Integer meetingId, String appClientId) {
        return meetingRepository.findByIdAndAppClientIdAndDeleteFlagFalse(meetingId, appClientId)
                .filter(m -> m.getImageData() != null && m.getImageData().length > 0)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                m.getImageContentType() != null ? m.getImageContentType() : "image/jpeg"))
                        .body(m.getImageData()))
                .orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    public void deleteImage(Integer meetingId, String appClientId) {
        Meeting m = findOrThrow(meetingId, appClientId);
        m.setImageData(null);
        m.setImageContentType(null);
        meetingRepository.save(m);
    }

    // ── Auto-Purge (meetings > 90 days, not flagged) ──────────────────────

    @Transactional
    public int autoPurgeOldMeetings(String appClientId) {
        LocalDate cutoff = LocalDate.now().minusDays(90);
        List<Meeting> candidates = meetingRepository.findAutoPurgeCandidates(cutoff, appClientId);
        candidates.forEach(m -> m.setDeleteFlag(true));
        meetingRepository.saveAll(candidates);
        return candidates.size();
    }

    // ── Manual Notification ───────────────────────────────────────────────────

    /**
     * Sends an on-demand Email and/or SMS for a single meeting to the requested
     * recipient groups ("Members", "Guests").
     *
     * @param meetingId  DB id of the meeting
     * @param channels   list containing "Email" and/or "SMS"
     * @param recipients list containing "Members" and/or "Guests"
     * @param appClientId organization client id from session
     * @return map with counts: { emailsSent, smsSent }
     */
    /**
     * Resolved inside a read-only transaction; delivered outside it. The meeting's
     * type is lazy and open-in-view is off, so the lookup needs a transaction — but
     * sending must not run in it: the first email of a month makes
     * {@code SubscriptionService} INSERT the month's usage row, PostgreSQL refuses a
     * write in a read-only transaction, and every later statement in that
     * transaction then fails with "current transaction is aborted".
     */
    private record Prepared(String subject, String htmlBody, Set<String> emails,
                            String smsBody, String recipientsToken, Set<String> phones) {}

    /** Test seam / Spring wiring: the transaction manager (null in unit tests → no transaction). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager tm) {
        this.txTemplate = tm == null ? null : new org.springframework.transaction.support.TransactionTemplate(tm);
        if (this.txTemplate != null) this.txTemplate.setReadOnly(true);
    }
    private org.springframework.transaction.support.TransactionTemplate txTemplate;

    private Prepared prepareManualNotification(Integer meetingId, List<String> channels,
                                               List<String> recipients, String appClientId) {
        // Tenant check first: a meeting from another church must not be resolved,
        // let alone have its details mailed/texted to this church's members.
        Meeting meeting = findOrThrow(meetingId, appClientId);

        String typeName = meeting.getMeetingType() != null
                ? meeting.getMeetingType().getTypeName() : "Meeting";
        String subject  = "📋 Meeting Reminder: " + typeName;

        String htmlBody = null;
        Set<String> emails = new LinkedHashSet<>();
        if (channels.contains("Email")) {
            htmlBody = buildManualNotificationEmailBody(meeting, typeName);
            if (recipients.contains("Members")) {
                familyMemberRepository.findByMemberTypeWithEmailByAppUser("Member", appClientId)
                        .stream()
                        .filter(m -> m.getEmail() != null && !m.getEmail().isBlank())
                        .forEach(m -> emails.add(m.getEmail().trim().toLowerCase()));
            }
            if (recipients.contains("Guests")) {
                familyMemberRepository.findByMemberTypeWithEmailByAppUser("Guest", appClientId)
                        .stream()
                        .filter(m -> m.getEmail() != null && !m.getEmail().isBlank())
                        .forEach(m -> emails.add(m.getEmail().trim().toLowerCase()));
            }
        }

        String smsBody = null;
        Set<String> phones = new LinkedHashSet<>();
        if (channels.contains("SMS")) {
            smsBody = buildManualNotificationSmsBody(meeting, typeName);
            // Count approximate sends (resolve same way as sendToRecipientsMultiChannel)
            if (recipients.contains("Members")) {
                familyMemberRepository.findByMemberTypeWithPhoneByAppUser("Member", appClientId)
                        .stream()
                        .map(FamilyMember::getPhone)
                        .filter(p -> p != null && !p.isBlank())
                        .forEach(phones::add);
            }
            if (recipients.contains("Guests")) {
                familyMemberRepository.findByMemberTypeWithPhoneByAppUser("Guest", appClientId)
                        .stream()
                        .map(FamilyMember::getPhone)
                        .filter(p -> p != null && !p.isBlank())
                        .forEach(phones::add);
            }
        }
        return new Prepared(subject, htmlBody, emails, smsBody, String.join(",", recipients), phones);
    }

    /**
     * Sends an on-demand Email and/or SMS for a single meeting to the requested
     * recipient groups ("Members", "Guests").
     *
     * <p>Not transactional: see {@link Prepared}. Email is delivered one recipient at
     * a time; when the tenant's plan or demo settings block congregation mail, nothing
     * is attempted and the count comes back as {@code emailsBlocked} with the reason,
     * so the screen never reports a dropped message as sent.
     *
     * @param meetingId  DB id of the meeting
     * @param channels   list containing "Email" and/or "SMS"
     * @param recipients list containing "Members" and/or "Guests"
     * @param appClientId organization client id from session
     * @return map with counts: { emailsSent, emailsBlocked, blockReason?, smsSent }
     */
    public Map<String, Object> sendManualNotification(Integer meetingId,
                                                      List<String> channels,
                                                      List<String> recipients,
                                                      String appClientId) {
        Prepared p = txTemplate != null
                ? txTemplate.execute(status -> prepareManualNotification(meetingId, channels, recipients, appClientId))
                : prepareManualNotification(meetingId, channels, recipients, appClientId);

        int emailsSent = 0, emailsBlocked = 0, testEmailsSent = 0, simulated = 0;
        String blockReason = null, testEmail = null;
        if (p.htmlBody() != null) {
            EmailService.Delivery d = emailService.delivery(appClientId);
            if (d.blocked()) {
                blockReason = d.reason();
                emailsBlocked = p.emails().size();
            } else if (d.test()) {
                // Phase B: one action → one test email; the rest are simulated.
                testEmail = d.testEmail();
                try (com.churchgeniuspro.util.EmailActionScope scope =
                             com.churchgeniuspro.util.EmailActionScope.begin("meeting-notify:" + meetingId)) {
                    for (String email : p.emails()) {
                        emailService.sendOrgEmail(email, p.subject(), p.htmlBody(), appClientId);
                    }
                    testEmailsSent = scope.testEmailsSent();
                    simulated      = scope.simulated();
                }
            } else {
                for (String email : p.emails()) {
                    emailService.sendOrgEmail(email, p.subject(), p.htmlBody(), appClientId);
                    emailsSent++;
                }
            }
        }

        int smsSent = 0;
        if (p.smsBody() != null) {
            whatsAppSender.sendToRecipientsMultiChannel(p.recipientsToken(), p.smsBody(),
                    appClientId, false, true);
            smsSent = p.phones().size();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("emailsSent",    emailsSent);
        result.put("emailsBlocked", emailsBlocked);
        if (blockReason != null) result.put("blockReason", blockReason);
        if (testEmail != null) {
            result.put("testEmailsSent", testEmailsSent);
            result.put("simulated",      simulated);
            result.put("testEmail",      testEmail);
        }
        result.put("smsSent",       smsSent);
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** Tenant-scoped, non-deleted lookup; unknown and foreign ids fail identically. */
    private Meeting findOrThrow(Integer id, String appClientId) {
        return meetingRepository.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found: " + id));
    }

    private void applyBO(MeetingBO bo, Meeting m, String appClientId) {
        if (bo.getMeetingTypeId() != null) {
            MeetingType mt = meetingTypeRepository
                    .findByIdAndAppClientIdAndDeleteFlagFalse(bo.getMeetingTypeId(), appClientId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Meeting type not found: " + bo.getMeetingTypeId()));
            m.setMeetingType(mt);
        } else {
            m.setMeetingType(null);
        }
        // Location foreign keys must belong to this church before they are stored.
        if (bo.getLocationMemberId() != null) {
            familyMemberRepository.findByIdAndTenant(bo.getLocationMemberId(), appClientId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Location member not found: " + bo.getLocationMemberId()));
        }
        if (bo.getLocationFamilyId() != null) {
            familyRepository.findByIdAndAppClientIdAndDeleteFlagFalse(bo.getLocationFamilyId(), appClientId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Location family not found: " + bo.getLocationFamilyId()));
        }
        m.setLocationMemberId(bo.getLocationMemberId());
        m.setLocationFamilyId(bo.getLocationFamilyId());
        m.setMeetingDate(parseDate(bo.getMeetingDate()));
        m.setStartTime(bo.getStartTime());
        m.setEndTime(bo.getEndTime());
        m.setAddress1(bo.getAddress1());
        m.setAddress2(bo.getAddress2());
        m.setCity(bo.getCity());
        m.setState(bo.getState());
        m.setCountry(bo.getCountry());
        m.setPinCode(bo.getPinCode());
        m.setNote(bo.getNote());
        m.setOccurrence(bo.getOccurrence());
        m.setEndDate(parseDate(bo.getEndDate()));
        m.setDoNotAutoDelete(bo.isDoNotAutoDelete());
        m.setWeekDays(intsToString(bo.getWeekDays()));
        m.setMonthMonths(intsToString(bo.getMonthMonths()));
        m.setMonthDayOfMonth(bo.getMonthDayOfMonth());
        m.setMonthWeekOrdinal(bo.getMonthWeekOrdinal());
        m.setMonthWeekDay(bo.getMonthWeekDay());
    }

    private Map<String, Object> toMap(Meeting m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",               m.getId());
        row.put("meetingTypeId",    m.getMeetingType() != null ? m.getMeetingType().getId()       : null);
        row.put("meetingTypeName",  m.getMeetingType() != null ? m.getMeetingType().getTypeName() : "");
        row.put("locationMemberId", m.getLocationMemberId());
        row.put("locationFamilyId", m.getLocationFamilyId());
        row.put("meetingDate",      m.getMeetingDate()  != null ? m.getMeetingDate().toString()  : null);
        row.put("startTime",        m.getStartTime());
        row.put("endTime",          m.getEndTime());
        row.put("address1",         m.getAddress1());
        row.put("address2",         m.getAddress2());
        row.put("city",             m.getCity());
        row.put("state",            m.getState());
        row.put("country",          m.getCountry());
        row.put("pinCode",          m.getPinCode());
        row.put("note",             m.getNote());
        row.put("occurrence",        m.getOccurrence());
        row.put("endDate",           m.getEndDate() != null ? m.getEndDate().toString() : null);
        row.put("doNotAutoDelete",   m.isDoNotAutoDelete());
        row.put("weekDays",          stringToInts(m.getWeekDays()));
        row.put("monthMonths",       stringToInts(m.getMonthMonths()));
        row.put("monthDayOfMonth",   m.getMonthDayOfMonth());
        row.put("monthWeekOrdinal",  m.getMonthWeekOrdinal());
        row.put("monthWeekDay",      m.getMonthWeekDay());
        row.put("hasImage",          m.getImageData() != null && m.getImageData().length > 0);
        return row;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try { return LocalDate.parse(dateStr.trim()); }
        catch (Exception e) { return null; }
    }

    /** Converts an int[] to a comma-separated string (e.g. [0,3,5] → "0,3,5"). Returns null for null/empty. */
    private String intsToString(int[] arr) {
        if (arr == null || arr.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    /** Converts a comma-separated string back to an int[] (e.g. "0,3,5" → [0,3,5]). Returns null for null/blank. */
    private int[] stringToInts(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    // ── Notification body builders ────────────────────────────────────────────

    private String buildManualNotificationEmailBody(Meeting meeting, String typeName) {
        StringBuilder content = new StringBuilder();
        content.append("<p style='font-size:15px;color:#333;margin:0 0 20px;'>")
               .append("Here are the details for the upcoming <strong>")
               .append(esc(typeName)).append("</strong>:</p>");

        content.append("<table style='font-size:14px;color:#444;border-collapse:collapse;width:100%;'>");

        // Date & Time
        if (meeting.getMeetingDate() != null) {
            String dateStr = meeting.getMeetingDate()
                    .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
            String timeStr = "";
            if (meeting.getStartTime() != null && !meeting.getStartTime().isBlank()) {
                timeStr = " @ " + formatTime12h(meeting.getStartTime());
                if (meeting.getEndTime() != null && !meeting.getEndTime().isBlank()) {
                    timeStr += " – " + formatTime12h(meeting.getEndTime());
                }
            }
            content.append("<tr>")
                   .append("<td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;white-space:nowrap;'>📅 Date &amp; Time:</td>")
                   .append("<td style='padding:8px 0;'>").append(esc(dateStr + timeStr)).append("</td>")
                   .append("</tr>");
        }

        // Location
        String locationName = resolveMeetingLocationNameInternal(meeting);
        String fullAddress  = buildMeetingFullAddressInternal(meeting);
        if (!locationName.isEmpty() || !fullAddress.isEmpty()) {
            String locationLine = locationName
                    + (!locationName.isEmpty() && !fullAddress.isEmpty() ? ", " : "")
                    + fullAddress;
            content.append("<tr>")
                   .append("<td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;white-space:nowrap;'>📍 Location:</td>")
                   .append("<td style='padding:8px 0;'>").append(esc(locationLine)).append("</td>")
                   .append("</tr>");
        }

        // Phone
        if (meeting.getLocationMemberId() != null) {
            familyMemberRepository.findById(meeting.getLocationMemberId())
                    .filter(fm -> fm.getPhone() != null && !fm.getPhone().isBlank())
                    .ifPresent(fm -> content.append("<tr>")
                            .append("<td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;white-space:nowrap;'>📞 Phone:</td>")
                            .append("<td style='padding:8px 0;'>").append(esc(fm.getPhone().trim())).append("</td>")
                            .append("</tr>"));
        }

        // Notes
        if (meeting.getNote() != null && !meeting.getNote().isBlank()) {
            content.append("<tr>")
                   .append("<td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;white-space:nowrap;'>📝 Notes:</td>")
                   .append("<td style='padding:8px 0;'>").append(esc(meeting.getNote())).append("</td>")
                   .append("</tr>");
        }

        content.append("</table>");

        // Directions button
        if (!fullAddress.isEmpty()) {
            try {
                String mapsUrl = "https://www.google.com/maps/dir/?api=1&destination="
                        + URLEncoder.encode(fullAddress, StandardCharsets.UTF_8.name());
                content.append("<div style='margin-top:20px;'>")
                       .append("<a href='").append(mapsUrl).append("' target='_blank' ")
                       .append("style='display:inline-block;padding:10px 22px;background:#34A853;")
                       .append("color:#fff;font-size:13px;font-weight:600;border-radius:6px;")
                       .append("text-decoration:none;'>📍 View Location Map</a></div>");
            } catch (Exception ignored) {}
        }

        // Wrap in branded card
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'/>"
             + "<style>"
             + "body{margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}"
             + ".wrap{max-width:520px;margin:40px auto;background:#fff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,.08);overflow:hidden;}"
             + ".body{padding:32px 40px;font-size:14px;color:#333;line-height:1.7;}"
             + "</style></head>"
             + "<body><div class='wrap'>"
             + "<div class='body'>"
             + "<h2 style='margin-top:0;color:#3f4568;'>📋 Reminder: " + esc(typeName) + "</h2>"
             + content
             + "</div>"
             + "</div></body></html>";
    }

    private String buildManualNotificationSmsBody(Meeting meeting, String typeName) {
        StringBuilder sb = new StringBuilder();
        sb.append("🗓️ Meeting Reminder: ").append(typeName).append("\n");

        if (meeting.getMeetingDate() != null) {
            String dateStr = meeting.getMeetingDate()
                    .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
            sb.append("📅 ").append(dateStr);
            if (meeting.getStartTime() != null && !meeting.getStartTime().isBlank()) {
                sb.append(" at ").append(formatTime12h(meeting.getStartTime()));
            }
            sb.append("\n");
        }

        String locationName = resolveMeetingLocationNameInternal(meeting);
        String fullAddress  = buildMeetingFullAddressInternal(meeting);
        if (!locationName.isEmpty() || !fullAddress.isEmpty()) {
            sb.append("\n📍 Location:\n");
            if (!locationName.isEmpty()) sb.append(locationName).append("\n");
            if (!fullAddress.isEmpty())  sb.append(fullAddress).append("\n");
        }

        if (!fullAddress.isEmpty()) {
            try {
                String mapsUrl = "https://www.google.com/maps/dir/?api=1&destination="
                        + URLEncoder.encode(fullAddress, StandardCharsets.UTF_8.name());
                sb.append("\n🗺️ Directions: ").append(mapsUrl);
            } catch (Exception ignored) {}
        }

        return sb.toString();
    }

    /** Resolves the display name for a meeting's location (member or family name). */
    private String resolveMeetingLocationNameInternal(Meeting meeting) {
        if (meeting.getLocationMemberId() != null) {
            return familyMemberRepository.findById(meeting.getLocationMemberId())
                    .map(fm -> ((fm.getFirstName() != null ? fm.getFirstName() : "")
                               + " " + (fm.getLastName() != null ? fm.getLastName() : "")).trim())
                    .orElse("");
        }
        if (meeting.getLocationFamilyId() != null) {
            return familyRepository.findByIdWithMembers(meeting.getLocationFamilyId())
                    .map(f -> {
                        FamilyMember p = f.getMembers().stream()
                                .filter(fm -> !fm.isDeleteFlag())
                                .filter(fm -> "Head".equalsIgnoreCase(fm.getRole())
                                          || "Head of Household".equalsIgnoreCase(fm.getRole()))
                                .findFirst()
                                .orElse(f.getMembers().stream().filter(fm -> !fm.isDeleteFlag()).findFirst().orElse(null));
                        String ln = p != null && p.getLastName()  != null ? p.getLastName()  : "";
                        String fn = p != null && p.getFirstName() != null ? p.getFirstName() : "";
                        return (ln.isBlank() ? fn : ln + " Family").trim();
                    })
                    .orElse("");
        }
        return "";
    }

    /** Builds a single-line address string from meeting address fields. */
    private String buildMeetingFullAddressInternal(Meeting meeting) {
        List<String> parts = new ArrayList<>();
        if (meeting.getAddress1() != null && !meeting.getAddress1().isBlank())
            parts.add(meeting.getAddress1().trim());
        if (meeting.getAddress2() != null && !meeting.getAddress2().isBlank())
            parts.add(meeting.getAddress2().trim());
        if (meeting.getCity() != null && !meeting.getCity().isBlank())
            parts.add(meeting.getCity().trim());
        StringBuilder statePart = new StringBuilder();
        if (meeting.getState() != null && !meeting.getState().isBlank()) {
            String stateVal = meeting.getState().trim();
            try {
                String abbr = States.getCode(Integer.parseInt(stateVal));
                if (abbr != null) stateVal = abbr;
            } catch (NumberFormatException ignored) {}
            statePart.append(stateVal);
        }
        if (meeting.getPinCode() != null && !meeting.getPinCode().isBlank()) {
            if (statePart.length() > 0) statePart.append(" ");
            statePart.append(meeting.getPinCode().trim());
        }
        if (statePart.length() > 0) parts.add(statePart.toString());
        return String.join(", ", parts);
    }

    /** Converts "HH:mm" to "h:mm a" (e.g. "14:30" → "2:30 PM"). */
    private String formatTime12h(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return "";
        try {
            String[] p = hhmm.split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            String ampm = h < 12 ? "AM" : "PM";
            h = h % 12;
            if (h == 0) h = 12;
            return String.format("%d:%02d %s", h, m, ampm);
        } catch (Exception e) { return hhmm; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
