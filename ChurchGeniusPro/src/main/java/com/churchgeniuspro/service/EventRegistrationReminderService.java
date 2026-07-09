package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.ChurchEventDay;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.EventRegistrationReminderContact;
import com.churchgeniuspro.hibernate.EventRegistrationReminderLog;
import com.churchgeniuspro.hibernate.ReminderSentLog;
import com.churchgeniuspro.repository.AutoReminderRepository;
import com.churchgeniuspro.repository.ChurchEventDayRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventRegistrationReminderContactRepository;
import com.churchgeniuspro.repository.EventRegistrationReminderLogRepository;
import com.churchgeniuspro.repository.EventRegistrationRepository;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.util.EncryptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.CHANNEL_EMAIL;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.CHANNEL_SMS;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.STATUS_FAILED;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.STATUS_SENT;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.STATUS_SKIPPED;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.TYPE_INVITE;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.TYPE_REMINDER;

/**
 * Scheduled job for the per-event <em>Registration Reminder</em> feature.
 *
 * <p>Administrators can attach a list of email/phone contacts to an event and
 * enable "send reminder N days before the event". On that date this job, for
 * each contact:
 * <ol>
 *   <li>Checks whether the email (case-insensitive) or the phone (normalized —
 *       {@code 9133330704}, {@code (913) 333-0704}, {@code +1 913-333-0704} all
 *       compare equal) already belongs to a registration <em>for that event</em>.</li>
 *   <li>If either matches, nothing is sent for that contact.</li>
 *   <li>Otherwise sends an email when an email address is present and an SMS
 *       when a phone number is present (both when both are present).</li>
 * </ol>
 *
 * <p>Duplicate protection is two-layered:
 * <ul>
 *   <li>A {@link ReminderSentLog} row (unique constraint) gates each
 *       event + days-before schedule to a single evaluation per day, so the
 *       half-hourly/hourly cron cannot re-process the same schedule.</li>
 *   <li>Every send is recorded in {@link EventRegistrationReminderLog}; a SENT
 *       row for the same event + channel + normalized recipient blocks any
 *       future duplicate to that person for this event.</li>
 * </ul>
 *
 * <p>All attempts — sent or skipped — are logged with a reason
 * (already registered, duplicate reminder, invalid email, invalid phone, …).
 */
@Service
public class EventRegistrationReminderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventRegistrationReminderService.class);

    /** Must match the zone used by the other reminder crons. */
    private static final ZoneId SCHED_ZONE = ZoneId.of("America/Chicago");

    /** Lightweight sanity check — not full RFC 5322, just "looks like an email". */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** {@link ReminderSentLog#getReminderType()} value for this feature. */
    private static final String SENT_LOG_TYPE = "EVENT_REG_REMINDER";

    @Value("${app.base-url}")
    private String baseUrl;

    private final ChurchEventRepository                      eventRepo;
    private final ChurchEventDayRepository                   eventDayRepo;
    private final EventRegistrationRepository                registrationRepo;
    private final EventRegistrationReminderContactRepository contactRepo;
    private final EventRegistrationReminderLogRepository     logRepo;
    private final ReminderSentLogRepository                  sentLogRepo;
    private final AutoReminderRepository                     autoReminderRepo;
    private final EmailService                               emailService;
    private final SmsService                                 smsService;
    private final WhatsAppSenderService                      whatsAppSender;

    public EventRegistrationReminderService(
            ChurchEventRepository                      eventRepo,
            ChurchEventDayRepository                   eventDayRepo,
            EventRegistrationRepository                registrationRepo,
            EventRegistrationReminderContactRepository contactRepo,
            EventRegistrationReminderLogRepository     logRepo,
            ReminderSentLogRepository                  sentLogRepo,
            AutoReminderRepository                     autoReminderRepo,
            EmailService                               emailService,
            SmsService                                 smsService,
            WhatsAppSenderService                      whatsAppSender) {
        this.eventRepo        = eventRepo;
        this.eventDayRepo     = eventDayRepo;
        this.registrationRepo = registrationRepo;
        this.contactRepo      = contactRepo;
        this.logRepo          = logRepo;
        this.sentLogRepo      = sentLogRepo;
        this.autoReminderRepo = autoReminderRepo;
        this.emailService     = emailService;
        this.smsService       = smsService;
        this.whatsAppSender   = whatsAppSender;
    }

    // =========================================================================
    // Scheduled entry point
    // =========================================================================

    /**
     * Outcome of one event evaluation — used for scheduler logging and as the
     * response of the manual "send now" admin endpoint.
     */
    public static final class RunResult {
        /** {@code null} when the event was processed; otherwise why it was skipped. */
        public String skipReason;
        public int emailsSent, smsSent, skipped, failed;
        public boolean processed() { return skipReason == null; }
    }

    /**
     * Runs on the {@code scheduler.job.registration-reminders} cron.
     * Scans all events with the feature enabled and processes those whose
     * reminder date is today. The {@link ReminderSentLog} gate makes repeated
     * invocations within the same day no-ops. Every trigger and every
     * skip reason is logged so the job is never silent.
     */
    @Scheduled(cron = "${scheduler.job.registration-reminders:0 0 * * * *}", zone = "America/Chicago")
    public void runRegistrationReminders() {
        LocalDate today = LocalDate.now(SCHED_ZONE);
        List<ChurchEvent> enabled = eventRepo.findByRegistrationReminderEnabledTrueAndDeleteFlagFalse();
        LOGGER.info("Registration reminder job triggered — today={}, {} event(s) have the feature enabled",
                today, enabled.size());
        if (enabled.isEmpty()) return;

        Set<String> activeClients = Set.copyOf(autoReminderRepo.findActiveClientIds());
        for (ChurchEvent event : enabled) {
            try {
                RunResult r = processEvent(event, today, activeClients, false);
                if (r.processed()) {
                    LOGGER.info("Registration reminders: event {} done — {} email(s) sent, {} SMS sent, {} skipped, {} failed",
                            event.getId(), r.emailsSent, r.smsSent, r.skipped, r.failed);
                } else {
                    LOGGER.info("Registration reminders: event {} ({}) not processed — {}",
                            event.getId(), event.getEventName(), r.skipReason);
                }
            } catch (Exception e) {
                LOGGER.error("Registration reminder failed for event {} — {}",
                        event.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Manual "send now" for one event (admin test endpoint). With
     * {@code force = true} the date match and the once-per-day gate are
     * bypassed; the already-registered check and the per-recipient duplicate
     * protection ALWAYS stay in force, so no one can ever be double-messaged.
     */
    public RunResult runNowForEvent(ChurchEvent event, boolean force) {
        LocalDate today = LocalDate.now(SCHED_ZONE);
        Set<String> activeClients = Set.copyOf(autoReminderRepo.findActiveClientIds());
        LOGGER.info("Registration reminders: manual run for event {} (force={})", event.getId(), force);
        return processEvent(event, today, activeClients, force);
    }

    // =========================================================================
    // Event invitations (admin-triggered "Send Invite")
    // =========================================================================

    /**
     * Sends an event invitation with an RSVP link to every valid contact on
     * the event's contact list — email when an address is present, SMS when a
     * phone is present, both when both are. Duplicate protection: each
     * normalized recipient receives at most one invitation per event/channel
     * (re-clicking Send Invite only reaches newly added contacts). Every
     * attempt is logged as message type {@code INVITE}.
     */
    public RunResult sendInvitations(ChurchEvent event) {
        RunResult r = new RunResult();

        String clientId = event.getAppClientId();
        Set<String> activeClients = Set.copyOf(autoReminderRepo.findActiveClientIds());
        if (clientId == null || !activeClients.contains(clientId)) {
            r.skipReason = "church subscription is not active";
            return r;
        }

        List<EventRegistrationReminderContact> contacts =
                contactRepo.findByEventIdOrderByIdAsc(event.getId());
        if (contacts.isEmpty()) {
            r.skipReason = "no contacts configured — add contacts and save first";
            return r;
        }

        LOGGER.info("Event invitations: sending for event {} ({} contact(s))",
                event.getId(), contacts.size());

        LocalDate eventDate = effectiveEventDate(event);
        String emailSubject = "You're Invited: " + safe(event.getEventName());
        String emailBody    = buildInviteEmailBody(event, eventDate, clientId);
        String smsBody      = buildInviteSmsBody(event, eventDate);

        Set<String> emailedThisRun = new HashSet<>();
        Set<String> textedThisRun  = new HashSet<>();

        for (EventRegistrationReminderContact contact : contacts) {
            String rawEmail  = trimToNull(contact.getEmail());
            String rawPhone  = trimToNull(contact.getPhone());
            String normEmail = normalizeEmail(rawEmail);
            String normPhone = normalizePhone(rawPhone);

            // ── Email channel ────────────────────────────────────────────────
            if (rawEmail != null) {
                if (normEmail == null) {
                    log(r, event, contact, TYPE_INVITE, CHANNEL_EMAIL, rawEmail, null,
                            STATUS_SKIPPED, "Invalid email address", null);
                } else if (!emailedThisRun.add(normEmail)
                        || logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                                event.getId(), TYPE_INVITE, CHANNEL_EMAIL, normEmail, STATUS_SENT)) {
                    log(r, event, contact, TYPE_INVITE, CHANNEL_EMAIL, rawEmail, normEmail,
                            STATUS_SKIPPED, "Duplicate — invitation already sent to this email", null);
                } else {
                    emailService.sendOrgEmail(rawEmail, emailSubject, emailBody, clientId);
                    log(r, event, contact, TYPE_INVITE, CHANNEL_EMAIL, rawEmail, normEmail,
                            STATUS_SENT, null, null);
                }
            }

            // ── SMS channel ──────────────────────────────────────────────────
            if (rawPhone != null) {
                if (normPhone == null) {
                    log(r, event, contact, TYPE_INVITE, CHANNEL_SMS, rawPhone, null,
                            STATUS_SKIPPED, "Invalid phone number", null);
                } else if (!textedThisRun.add(normPhone)
                        || logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                                event.getId(), TYPE_INVITE, CHANNEL_SMS, normPhone, STATUS_SENT)) {
                    log(r, event, contact, TYPE_INVITE, CHANNEL_SMS, rawPhone, normPhone,
                            STATUS_SKIPPED, "Duplicate — invitation already sent to this phone", null);
                } else if (!smsService.isConfigured()) {
                    log(r, event, contact, TYPE_INVITE, CHANNEL_SMS, rawPhone, normPhone,
                            STATUS_FAILED, "SMS provider not configured", null);
                } else {
                    whatsAppSender.sendToPhoneMultiChannel(normPhone, smsBody, clientId, false, true);
                    log(r, event, contact, TYPE_INVITE, CHANNEL_SMS, rawPhone, normPhone,
                            STATUS_SENT, null, null);
                }
            }
        }
        return r;
    }

    private RunResult processEvent(ChurchEvent event, LocalDate today,
                                   Set<String> activeClients, boolean force) {
        RunResult r = new RunResult();

        if (!event.isRegistrationReminderEnabled()) {
            r.skipReason = "reminder is disabled for this event";
            return r;
        }

        Integer daysBefore = event.getRegistrationReminderDays();
        if (daysBefore == null || daysBefore < 0) {
            r.skipReason = "days-before value is not configured";
            return r;
        }

        String clientId = event.getAppClientId();
        if (clientId == null || !activeClients.contains(clientId)) {
            r.skipReason = "church subscription is not active";
            return r;
        }

        LocalDate eventDate = effectiveEventDate(event);
        if (eventDate == null) {
            r.skipReason = "event has no date";
            return r;
        }

        // Fire only on the configured reminder date (unless forced).
        LocalDate reminderDate = eventDate.minusDays(daysBefore);
        if (!force && !reminderDate.equals(today)) {
            r.skipReason = "reminder date is " + reminderDate + ", today is " + today;
            return r;
        }

        List<EventRegistrationReminderContact> contacts =
                contactRepo.findByEventIdOrderByIdAsc(event.getId());
        if (contacts.isEmpty()) {
            r.skipReason = "no reminder contacts configured";
            return r;
        }

        // ── Once-per-schedule gate (race-safe via unique DB constraint) ──────
        if (!force) {
            String refKey = event.getId() + "_BEFORE_" + daysBefore;
            if (sentLogRepo.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
                    clientId, SENT_LOG_TYPE, refKey, today)
                    || !markSent(clientId, refKey, today)) {
                r.skipReason = "already processed today (reminder_sent_log gate) — "
                        + "delete today's EVENT_REG_REMINDER row to re-run, or use force";
                return r;
            }
        }

        LOGGER.info("Registration reminders: processing event {} ({} contact(s), {} day(s) before {})",
                event.getId(), contacts.size(), daysBefore, eventDate);

        // ── Registered emails / phones for THIS event only ───────────────────
        Set<String> registeredEmails = new HashSet<>();
        Set<String> registeredPhones = new HashSet<>();
        for (EventRegistration reg : registrationRepo.findByEventIdOrderByCreatedDateAsc(event.getId())) {
            String e = normalizeEmail(reg.getEmail());
            if (e != null) registeredEmails.add(e);
            String p = normalizePhone(reg.getPhone());
            if (p != null) registeredPhones.add(p);
        }

        // In-run dedupe: two contact rows listing the same email/phone → one message.
        Set<String> emailedThisRun = new HashSet<>();
        Set<String> textedThisRun  = new HashSet<>();

        String emailSubject = "Reminder: RSVP for " + safe(event.getEventName());
        String emailBody    = buildReminderEmailBody(event, eventDate);
        String smsBody      = buildReminderSmsBody(event, eventDate);

        for (EventRegistrationReminderContact contact : contacts) {
            String rawEmail  = trimToNull(contact.getEmail());
            String rawPhone  = trimToNull(contact.getPhone());
            String normEmail = normalizeEmail(rawEmail);
            String normPhone = normalizePhone(rawPhone);

            // ── Registration check: either identifier registered → skip entry ──
            boolean emailRegistered = normEmail != null && registeredEmails.contains(normEmail);
            boolean phoneRegistered = normPhone != null && registeredPhones.contains(normPhone);
            if (emailRegistered || phoneRegistered) {
                String reason = "Already registered for this event ("
                        + (emailRegistered ? "email" : "phone") + " match)";
                if (rawEmail != null) log(r, event, contact, TYPE_REMINDER, CHANNEL_EMAIL, rawEmail, normEmail,
                        STATUS_SKIPPED, reason, daysBefore);
                if (rawPhone != null) log(r, event, contact, TYPE_REMINDER, CHANNEL_SMS, rawPhone, normPhone,
                        STATUS_SKIPPED, reason, daysBefore);
                continue;
            }

            // ── Email channel ────────────────────────────────────────────────
            if (rawEmail != null) {
                if (normEmail == null) {
                    log(r, event, contact, TYPE_REMINDER, CHANNEL_EMAIL, rawEmail, null,
                            STATUS_SKIPPED, "Invalid email address", daysBefore);
                } else if (!emailedThisRun.add(normEmail)
                        || logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                                event.getId(), TYPE_REMINDER, CHANNEL_EMAIL, normEmail, STATUS_SENT)) {
                    log(r, event, contact, TYPE_REMINDER, CHANNEL_EMAIL, rawEmail, normEmail,
                            STATUS_SKIPPED, "Duplicate reminder — email already sent for this event", daysBefore);
                } else {
                    emailService.sendOrgEmail(rawEmail, emailSubject, emailBody, clientId);
                    log(r, event, contact, TYPE_REMINDER, CHANNEL_EMAIL, rawEmail, normEmail,
                            STATUS_SENT, null, daysBefore);
                }
            }

            // ── SMS channel ──────────────────────────────────────────────────
            if (rawPhone != null) {
                if (normPhone == null) {
                    log(r, event, contact, TYPE_REMINDER, CHANNEL_SMS, rawPhone, null,
                            STATUS_SKIPPED, "Invalid phone number", daysBefore);
                } else if (!textedThisRun.add(normPhone)
                        || logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                                event.getId(), TYPE_REMINDER, CHANNEL_SMS, normPhone, STATUS_SENT)) {
                    log(r, event, contact, TYPE_REMINDER, CHANNEL_SMS, rawPhone, normPhone,
                            STATUS_SKIPPED, "Duplicate reminder — SMS already sent for this event", daysBefore);
                } else if (!smsService.isConfigured()) {
                    log(r, event, contact, TYPE_REMINDER, CHANNEL_SMS, rawPhone, normPhone,
                            STATUS_FAILED, "SMS provider not configured", daysBefore);
                } else {
                    // 10-digit form; WhatsAppSenderService normalizes to E.164 and
                    // adds the church-name prefix + opt-out suffix automatically.
                    whatsAppSender.sendToPhoneMultiChannel(normPhone, smsBody, clientId, false, true);
                    log(r, event, contact, TYPE_REMINDER, CHANNEL_SMS, rawPhone, normPhone,
                            STATUS_SENT, null, daysBefore);
                }
            }
        }
        return r;
    }

    // =========================================================================
    // Normalization helpers
    // =========================================================================

    /** Lower-cased, trimmed email — or {@code null} when blank or not email-shaped. */
    static String normalizeEmail(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty() || !EMAIL_PATTERN.matcher(e).matches()) return null;
        return e;
    }

    /**
     * Canonical 10-digit US phone for comparison: strips every non-digit and
     * drops a leading country-code {@code 1} from 11-digit numbers, so
     * {@code 9133330704}, {@code (913) 333-0704}, {@code +1 913-333-0704} and
     * {@code 913-333-0704} all normalize to {@code 9133330704}.
     * Returns {@code null} for blank or non-10-digit results.
     */
    static String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        return digits.length() == 10 ? digits : null;
    }

    // =========================================================================
    // Content builders
    // =========================================================================

    /** Public registration URL: the event's stored link, else the encrypted-token route. */
    private String registrationUrl(ChurchEvent event) {
        if (event.getRegistrationLink() != null && !event.getRegistrationLink().isBlank()) {
            return event.getRegistrationLink().trim();
        }
        try {
            String token = EncryptionUtil.encrypt(String.valueOf(event.getId()));
            return baseUrl + "/event-register/" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return baseUrl + "/events";
        }
    }

    /** "5:00 PM – 6:30 PM" for the event (first day for multi-day events); empty when unset. */
    private String eventTimeRange(ChurchEvent event) {
        String start = event.getStartTime(), end = event.getEndTime();
        if ((start == null || start.isBlank()) && "Multiple Days".equals(event.getEventType())) {
            ChurchEventDay first = firstDay(event);
            if (first != null) { start = first.getStartTime(); end = first.getEndTime(); }
        }
        if (start == null || start.isBlank()) return "";
        String s = formatTime(start);
        if (end != null && !end.isBlank() && !end.equals(start)) s += " – " + formatTime(end);
        return s;
    }

    /** The date the reminder counts down to: eventDate, or the earliest day of a multi-day event. */
    private LocalDate effectiveEventDate(ChurchEvent event) {
        if (event.getEventDate() != null) return event.getEventDate();
        ChurchEventDay first = firstDay(event);
        return first != null ? first.getEventDate() : null;
    }

    private ChurchEventDay firstDay(ChurchEvent event) {
        return eventDayRepo.findByEventIdOrderByDayOrderAsc(event.getId()).stream()
                .filter(d -> d.getEventDate() != null)
                .min(Comparator.comparing(ChurchEventDay::getEventDate))
                .orElse(null);
    }

    /**
     * Branded HTML email: event name in the header, date/time/location details,
     * plain-text description and image when available, an RSVP button, and a
     * "we are waiting for your response" message. (Church identity is added by
     * {@link EmailService}'s org footer.)
     */
    private String buildReminderEmailBody(ChurchEvent event, LocalDate eventDate) {
        String regUrl     = registrationUrl(event);
        String dateStr    = formatDate(eventDate);
        String timeStr    = eventTimeRange(event);
        String location   = buildFullAddress(event);
        String deadline   = event.getRegistrationEndDate() != null
                ? formatDate(event.getRegistrationEndDate()) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'/>")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'/>")
          .append("<title>Registration Reminder</title></head>")
          .append("<body style='margin:0;padding:0;background-color:#f5f6fa;")
          .append("font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>")
          .append("<table width='100%' cellpadding='0' cellspacing='0' ")
          .append("style='background-color:#f5f6fa;padding:40px 20px;'><tr><td align='center'>")
          .append("<table width='100%' cellpadding='0' cellspacing='0' ")
          .append("style='max-width:520px;background:#ffffff;border-radius:16px;")
          .append("box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>");

        // Header band — event name
        sb.append("<tr><td style='background-color:#673147;padding:28px 40px;text-align:center;'>")
          .append("<p style='margin:0;font-size:21px;font-weight:700;color:#ffffff;letter-spacing:0.5px;'>")
          .append(esc(safe(event.getEventName()))).append("</p>")
          .append("<p style='margin:8px 0 0;font-size:13px;color:rgba(255,255,255,0.75);'>")
          .append("RSVP Reminder</p></td></tr>");

        // Body
        sb.append("<tr><td style='padding:32px 40px;'>")
          .append("<p style='margin:0 0 18px;font-size:14px;color:#555;line-height:1.7;'>")
          .append("We are waiting for your response. Please RSVP here")
          .append(deadline != null ? " before <strong>" + esc(deadline) + "</strong>." : ".")
          .append("</p>");

        // Details table (event name lives in the header — not repeated here)
        sb.append("<table style='font-size:13px;color:#555;margin:0 0 18px;'>");
        if (!dateStr.isEmpty())  sb.append(detailRow("Date:", dateStr));
        if (!timeStr.isEmpty())  sb.append(detailRow("Time:", timeStr));
        if (!location.isEmpty()) sb.append(detailRow("Location:", location));
        sb.append("</table>");

        // Description (event note), when available — stored value may contain
        // HTML markup; render its plain text so tags are never shown literally.
        String description = htmlToPlainText(event.getNote());
        if (!description.isEmpty()) {
            sb.append("<p style='margin:0 0 18px;font-size:13px;color:#666;line-height:1.7;'>")
              .append(esc(description).replace("\n", "<br/>"))
              .append("</p>");
        }

        // Event image (data URI → converted to CID attachment by EmailService)
        if (event.getImageData() != null && !event.getImageData().isBlank()) {
            sb.append("<div style='margin:0 0 22px;'><img src='").append(event.getImageData())
              .append("' alt='Event' style='max-width:100%;border-radius:8px;'/></div>");
        }

        // Register button
        sb.append("<table cellpadding='0' cellspacing='0' style='margin:0 auto 24px;'><tr>")
          .append("<td style='background-color:#673147;border-radius:8px;'>")
          .append("<a href='").append(regUrl).append("' ")
          .append("style='display:inline-block;padding:14px 36px;font-size:15px;font-weight:600;")
          .append("color:#ffffff;text-decoration:none;letter-spacing:0.3px;'>RSVP Now</a>")
          .append("</td></tr></table>");

        // Fallback link
        sb.append("<p style='margin:0 0 6px;font-size:12px;color:#aaa;'>")
          .append("If the button doesn't work, copy and paste this link into your browser:</p>")
          .append("<p style='margin:0;font-size:12px;word-break:break-all;'>")
          .append("<a href='").append(regUrl).append("' style='color:#3a7bd5;'>")
          .append(esc(regUrl)).append("</a></p>");

        sb.append("</td></tr></table></td></tr></table></body></html>");
        return sb.toString();
    }

    /**
     * Short SMS body — church-name prefix and the "Reply STOP to opt out"
     * suffix are appended automatically by {@link WhatsAppSenderService}.
     */
    /**
     * Invitation email: church name in the header, "You're Invited" banner,
     * event name/date/time/location, description and image when available,
     * an RSVP Now button, and the raw RSVP link as a fallback.
     */
    private String buildInviteEmailBody(ChurchEvent event, LocalDate eventDate, String clientId) {
        String churchName = emailService.getChurchName(clientId);
        String regUrl     = registrationUrl(event);
        String dateStr    = formatDate(eventDate);
        String timeStr    = eventTimeRange(event);
        String location   = buildFullAddress(event);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'/>")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'/>")
          .append("<title>You're Invited</title></head>")
          .append("<body style='margin:0;padding:0;background-color:#f5f6fa;")
          .append("font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>")
          .append("<table width='100%' cellpadding='0' cellspacing='0' ")
          .append("style='background-color:#f5f6fa;padding:40px 20px;'><tr><td align='center'>")
          .append("<table width='100%' cellpadding='0' cellspacing='0' ")
          .append("style='max-width:520px;background:#ffffff;border-radius:16px;")
          .append("box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>");

        // Header band — church name + invitation banner
        sb.append("<tr><td style='background-color:#673147;padding:28px 40px;text-align:center;'>")
          .append("<p style='margin:0;font-size:21px;font-weight:700;color:#ffffff;letter-spacing:0.5px;'>")
          .append(esc(churchName)).append("</p>")
          .append("<p style='margin:8px 0 0;font-size:13px;color:rgba(255,255,255,0.75);'>")
          .append("You're Invited</p></td></tr>");

        // Body
        sb.append("<tr><td style='padding:32px 40px;'>")
          .append("<p style='margin:0 0 14px;font-size:17px;color:#1a1a2e;font-weight:700;'>")
          .append(esc(safe(event.getEventName()))).append("</p>")
          .append("<p style='margin:0 0 18px;font-size:14px;color:#555;line-height:1.7;'>")
          .append("You are invited to this event. We would love to have you join us!")
          .append("</p>");

        // Details table
        sb.append("<table style='font-size:13px;color:#555;margin:0 0 18px;'>");
        if (!dateStr.isEmpty())  sb.append(detailRow("Date:", dateStr));
        if (!timeStr.isEmpty())  sb.append(detailRow("Time:", timeStr));
        if (!location.isEmpty()) sb.append(detailRow("Location:", location));
        sb.append("</table>");

        // Description (event note), plain text — tags never shown literally
        String description = htmlToPlainText(event.getNote());
        if (!description.isEmpty()) {
            sb.append("<p style='margin:0 0 18px;font-size:13px;color:#666;line-height:1.7;'>")
              .append(esc(description).replace("\n", "<br/>"))
              .append("</p>");
        }

        // Event image (data URI → converted to CID attachment by EmailService)
        if (event.getImageData() != null && !event.getImageData().isBlank()) {
            sb.append("<div style='margin:0 0 22px;'><img src='").append(event.getImageData())
              .append("' alt='Event' style='max-width:100%;border-radius:8px;'/></div>");
        }

        // RSVP button
        sb.append("<table cellpadding='0' cellspacing='0' style='margin:0 auto 24px;'><tr>")
          .append("<td style='background-color:#673147;border-radius:8px;'>")
          .append("<a href='").append(regUrl).append("' ")
          .append("style='display:inline-block;padding:14px 36px;font-size:15px;font-weight:600;")
          .append("color:#ffffff;text-decoration:none;letter-spacing:0.3px;'>RSVP Now</a>")
          .append("</td></tr></table>");

        // Fallback link
        sb.append("<p style='margin:0 0 6px;font-size:12px;color:#aaa;'>")
          .append("If the button doesn't work, copy and paste this link into your browser:</p>")
          .append("<p style='margin:0;font-size:12px;word-break:break-all;'>")
          .append("<a href='").append(regUrl).append("' style='color:#3a7bd5;'>")
          .append(esc(regUrl)).append("</a></p>");

        sb.append("</td></tr></table></td></tr></table></body></html>");
        return sb.toString();
    }

    /**
     * Short invitation SMS — the church-name prefix and "Reply STOP to opt out."
     * suffix are appended automatically by {@link WhatsAppSenderService}.
     */
    private String buildInviteSmsBody(ChurchEvent event, LocalDate eventDate) {
        StringBuilder sb = new StringBuilder("You have been invited to the event \"")
                .append(safe(event.getEventName())).append("\".");
        String dateStr = formatDate(eventDate);
        if (!dateStr.isEmpty()) {
            sb.append("\n\nDate: ").append(dateStr);
            String time = eventTimeRange(event);
            if (!time.isEmpty()) sb.append(" & Time: ").append(time);
        }
        sb.append("\n\nPlease RSVP here:\n").append(registrationUrl(event));
        return sb.toString();
    }

    private String buildReminderSmsBody(ChurchEvent event, LocalDate eventDate) {
        StringBuilder sb = new StringBuilder("We are waiting for your response. Please RSVP here.");
        sb.append("\n\n").append(safe(event.getEventName()));
        sb.append("\nDate: ").append(formatDate(eventDate));
        String time = eventTimeRange(event);
        if (!time.isEmpty()) sb.append(" & Time: ").append(time);
        sb.append("\n\nRSVP: ").append(registrationUrl(event));
        return sb.toString();
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    /** Writes one audit row and tallies the run counters; never breaks the send loop. */
    private void log(RunResult r, ChurchEvent event, EventRegistrationReminderContact contact,
                     String messageType, String channel, String recipient, String recipientNorm,
                     String status, String reason, Integer daysBefore) {
        if (STATUS_SENT.equals(status)) {
            if (CHANNEL_EMAIL.equals(channel)) r.emailsSent++; else r.smsSent++;
        } else if (STATUS_FAILED.equals(status)) {
            r.failed++;
        } else {
            r.skipped++;
        }
        try {
            EventRegistrationReminderLog row = new EventRegistrationReminderLog();
            row.setEventId(event.getId());
            row.setContactId(contact.getId());
            row.setMessageType(messageType);
            row.setChannel(channel);
            row.setRecipient(recipient);
            row.setRecipientNorm(recipientNorm);
            row.setStatus(status);
            row.setReason(reason);
            row.setDaysBefore(daysBefore);
            row.setAppClientId(event.getAppClientId());
            logRepo.save(row);
            LOGGER.info("Event {} [event={}] {} {} → {}{}",
                    messageType, event.getId(), channel, recipient, status,
                    reason != null ? " (" + reason + ")" : "");
        } catch (Exception e) {
            LOGGER.error("Failed to write registration reminder log for event {} — {}",
                    event.getId(), e.getMessage());
        }
    }

    /**
     * Claims today's send slot for this event schedule. Returns {@code false}
     * when another thread/instance already claimed it (unique constraint).
     */
    private boolean markSent(String clientId, String referenceKey, LocalDate today) {
        try {
            ReminderSentLog log = new ReminderSentLog();
            log.setAppClientId(clientId);
            log.setReminderType(SENT_LOG_TYPE);
            log.setReferenceKey(referenceKey);
            log.setSentDate(today);
            sentLogRepo.saveAndFlush(log);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String detailRow(String label, String value) {
        return "<tr><td style='padding:4px 12px 4px 0;font-weight:600;white-space:nowrap;'>"
                + esc(label) + "</td><td>" + esc(value) + "</td></tr>";
    }

    /** "May 3, 2026" */
    private String formatDate(LocalDate date) {
        return date == null ? "" : date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US));
    }

    /** "17:30" → "5:30 PM" */
    private String formatTime(String t) {
        try {
            String[] parts = t.split(":");
            int h = Integer.parseInt(parts[0]);
            String m = parts.length > 1 ? parts[1] : "00";
            return (h % 12 == 0 ? 12 : h % 12) + ":" + m + " " + (h >= 12 ? "PM" : "AM");
        } catch (Exception e) {
            return t;
        }
    }

    /** "16127 S Bradley Dr, Olathe, KS 66062" from the event's address fields. */
    private String buildFullAddress(ChurchEvent event) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (notBlank(event.getAddress1())) parts.add(event.getAddress1().trim());
        if (notBlank(event.getAddress2())) parts.add(event.getAddress2().trim());
        if (notBlank(event.getCity()))     parts.add(event.getCity().trim());
        StringBuilder statePart = new StringBuilder();
        if (notBlank(event.getState())) {
            String stateVal = event.getState().trim();
            try {
                String abbr = com.churchgeniuspro.common.States.getCode(Integer.parseInt(stateVal));
                if (abbr != null) stateVal = abbr;
            } catch (NumberFormatException ignored) { /* already an abbreviation */ }
            statePart.append(stateVal);
        }
        if (notBlank(event.getPinCode())) {
            if (statePart.length() > 0) statePart.append(" ");
            statePart.append(event.getPinCode().trim());
        }
        if (statePart.length() > 0) parts.add(statePart.toString());
        return String.join(", ", parts);
    }

    /**
     * Converts stored (possibly HTML) description markup to plain text:
     * {@code <p>}/{@code <br>}/{@code <div>}/{@code <li>} become line breaks,
     * all other tags are dropped, and common entities are decoded. Result is
     * safe to re-escape and display without literal tags showing.
     */
    static String htmlToPlainText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html
                .replaceAll("(?i)<br\\s*/?\\s*>", "\n")
                .replaceAll("(?i)</\\s*(p|div|li|tr|h[1-6])\\s*>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        return s.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String safe(String s) { return s != null ? s : "Upcoming Event"; }

    private static String esc(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
