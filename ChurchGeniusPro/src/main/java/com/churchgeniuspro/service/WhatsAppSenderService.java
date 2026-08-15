package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.EmailSettings;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.WhatsAppSettings;
import com.churchgeniuspro.repository.EmailSettingsRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.WhatsAppSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Sends WhatsApp and SMS messages via the Twilio REST API.
 *
 * <p>Uses the credentials stored in {@code whatsapp_settings} for the requesting
 * organization.  If no credentials are configured the send is silently skipped.
 *
 * <h3>Twilio message format</h3>
 * <ul>
 *   <li><b>WhatsApp</b>: {@code From=whatsapp:+15005550006}, {@code To=whatsapp:+1234567890}</li>
 *   <li><b>SMS</b>:      {@code From=+15005550006},          {@code To=+1234567890}</li>
 * </ul>
 *
 * <p>Phone numbers that are null, blank, or do not start with {@code +} are skipped
 * so that the scheduler never sends to malformed numbers.
 */
@Service
public class WhatsAppSenderService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSenderService.class);

    private static final String TWILIO_API =
            "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final WhatsAppSettingsRepository settingsRepo;
    private final FamilyMemberRepository     memberRepo;
    private final EmailSettingsRepository    emailSettingsRepo;
    private final SmsService                 smsService;
    private final SubscriptionService        subscriptionService;
    private final HttpClient                 http = HttpClient.newHttpClient();

    public WhatsAppSenderService(WhatsAppSettingsRepository settingsRepo,
                                 FamilyMemberRepository memberRepo,
                                 EmailSettingsRepository emailSettingsRepo,
                                 SmsService smsService,
                                 SubscriptionService subscriptionService) {
        this.settingsRepo      = settingsRepo;
        this.memberRepo        = memberRepo;
        this.emailSettingsRepo = emailSettingsRepo;
        this.smsService        = smsService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Single SMS choke-point that enforces the subscription plan's monthly SMS
     * allowance (base limit + extra SMS) and records usage per send.
     * Returns {@code true} when the message was handed to Twilio.
     */
    private boolean smsWithQuota(String toPhone, String body, String clientId) {
        if (clientId != null && !clientId.isBlank()
                && !subscriptionService.canSendSms(clientId)) {
            log.warn("SMS to {} skipped — monthly SMS limit reached for subscription plan (clientId={})",
                    toPhone, clientId);
            return false;
        }
        boolean sent = smsService.send(toPhone, body);
        if (sent && clientId != null && !clientId.isBlank()) {
            subscriptionService.recordSmsSent(clientId);
        }
        return sent;
    }

    // ── Public helpers ─────────────────────────────────────────────────────────

    /**
     * Sends ONE SMS to a single arbitrary phone number through the quota
     * choke-point (normalizes the number first). Used for public-form
     * confirmation messages. Returns true when handed to Twilio.
     */
    public boolean sendSingleSms(String phone, String body, String clientId) {
        String normalized = normalizePhone(phone);
        if (normalized == null) {
            log.debug("sendSingleSms skipped — unrecognized phone format: {}", phone);
            return false;
        }
        return smsWithQuota(normalized, body, clientId);
    }

    /**
     * Resolves phone numbers for the given recipient groups and sends a WhatsApp
     * message to each one that has a valid phone number.
     *
     * @param recipients  comma-separated tokens: "Members", "Guests", "Celebrant"
     * @param message     message body to send
     * @param clientId    organization client ID (used to look up Twilio credentials)
     */
    public void sendWhatsAppToRecipients(String recipients, String message, String clientId) {
        // WHATSAPP DISABLED — do not send WhatsApp messages until further notice
        // sendToRecipients(recipients, message, clientId, true);
        log.debug("WhatsApp (sendWhatsAppToRecipients) disabled — skipping for clientId={}", clientId);
    }

    /**
     * Resolves phone numbers for the given recipient groups and sends an SMS
     * to each one that has a valid phone number.
     */
    public void sendSmsToRecipients(String recipients, String message, String clientId) {
        sendToRecipients(recipients, message, clientId, false);
    }

    /**
     * Sends WhatsApp and/or SMS to recipient groups in a single pass, resolving
     * phone numbers only once.  This prevents a recipient from receiving both a
     * WhatsApp message and an SMS for the same reminder when both channels are
     * enabled — WhatsApp is preferred; a number only receives SMS if it did NOT
     * already receive a WhatsApp message.
     *
     * <p>Call this instead of calling {@link #sendWhatsAppToRecipients} and
     * {@link #sendSmsToRecipients} separately whenever both channels may be active.
     *
     * @param recipients   comma-separated tokens: "Members", "Guests", "Celebrant"
     * @param message      message body to send
     * @param clientId     organization client ID
     * @param doWhatsApp   send via WhatsApp
     * @param doSms        send via SMS
     */
    public void sendToRecipientsMultiChannel(String recipients, String message,
                                             String clientId,
                                             boolean doWhatsApp, boolean doSms) {
        // WHATSAPP DISABLED — treat doWhatsApp as false regardless of caller value
        // if (!doWhatsApp && !doSms) return;
        if (!doSms) return;

        List<String> phones = resolvePhones(recipients, clientId);
        if (phones.isEmpty()) return;

        // SMS only — use SmsService directly (quota-checked per send)
        if (smsService.isConfigured()) {
            String body = resolveMessageNoSettings(message, clientId);
            for (String phone : phones) {
                smsWithQuota(phone, body, clientId);
            }
        } else {
            log.debug("SMS skipped for clientId={} — SmsService not configured", clientId);
        }

        /* WHATSAPP DISABLED — original multi-channel logic commented out
        Optional<WhatsAppSettings> settingsOpt = settingsRepo.findByClientId(clientId);
        boolean hasOrgSettings = settingsOpt.isPresent() && credentialsValid(settingsOpt.get());

        if (hasOrgSettings) {
            WhatsAppSettings settings = settingsOpt.get();
            String effectiveMessage = resolveMessage(message, settings, clientId);
            for (String phone : phones) {
                if (doWhatsApp) {
                    doSend(phone, effectiveMessage, settings, true);
                    // WhatsApp sent — skip SMS to this number to avoid duplicate
                } else if (doSms) {
                    doSend(phone, effectiveMessage, settings, false);
                }
            }
            // If both channels requested and org settings exist: WhatsApp only (no duplicate SMS)
        } else {
            // No org-level credentials — fall back to global SmsService for SMS only
            if (doSms && smsService.isConfigured()) {
                String body = resolveMessageNoSettings(message, clientId);
                for (String phone : phones) {
                    smsService.send(phone, body);
                }
            } else {
                log.debug("WhatsApp/SMS skipped for clientId={} — no credentials configured", clientId);
            }
        }
        */
    }

    /**
     * Sends WhatsApp and/or SMS to a single pre-resolved phone number in one call,
     * ensuring the number is not messaged twice when both channels are active.
     * WhatsApp is preferred; SMS is sent only if WhatsApp was not also sent.
     *
     * @param toPhone    phone number (will be normalised to E.164)
     * @param message    message body
     * @param clientId   organization client ID
     * @param doWhatsApp send via WhatsApp
     * @param doSms      send via SMS
     */
    public void sendToPhoneMultiChannel(String toPhone, String message,
                                        String clientId,
                                        boolean doWhatsApp, boolean doSms) {
        // WHATSAPP DISABLED — treat doWhatsApp as false regardless of caller value
        // if (!doWhatsApp && !doSms) return;
        if (!doSms) return;
        String normalized = normalizePhone(toPhone);
        if (normalized == null) return;

        // SMS only (quota-checked)
        if (smsService.isConfigured()) {
            smsWithQuota(normalized, resolveMessageNoSettings(message, clientId), clientId);
        } else {
            log.debug("SMS to {} skipped — SmsService not configured", normalized);
        }

        /* WHATSAPP DISABLED — original multi-channel logic commented out
        Optional<WhatsAppSettings> settingsOpt = settingsRepo.findByClientId(clientId);
        boolean hasOrgSettings = settingsOpt.isPresent() && credentialsValid(settingsOpt.get());

        if (hasOrgSettings) {
            WhatsAppSettings settings = settingsOpt.get();
            String effectiveMessage = resolveMessage(message, settings, clientId);
            if (doWhatsApp) {
                doSend(normalized, effectiveMessage, settings, true);
                // WhatsApp sent — do not also send SMS to avoid duplicate
            } else if (doSms) {
                doSend(normalized, effectiveMessage, settings, false);
            }
        } else {
            if (doSms && smsService.isConfigured()) {
                smsService.send(normalized, resolveMessageNoSettings(message, clientId));
            } else {
                log.debug("WhatsApp/SMS to {} skipped — no credentials configured", normalized);
            }
        }
        */
    }

    /**
     * Sends a WhatsApp message to a single, pre-resolved phone number.
     * Used by the birthday/anniversary scheduler where the celebrant is
     * a specific person rather than a recipient group.
     */
    public void sendWhatsAppToPhone(String toPhone, String message, String clientId) {
        // WHATSAPP DISABLED — do not send WhatsApp messages until further notice
        // sendToPhone(toPhone, message, clientId, true);
        log.debug("WhatsApp (sendWhatsAppToPhone) disabled — skipping for clientId={}", clientId);
    }

    /**
     * Sends an SMS to a single, pre-resolved phone number.
     */
    public void sendSmsToPhone(String toPhone, String message, String clientId) {
        sendToPhone(toPhone, message, clientId, false);
    }

    /**
     * Returns the display name for the church identified by {@code clientId},
     * falling back to "Church" when not found.  Used by callers that need to
     * prefix SMS messages with the church name.
     */
    public String resolveChurchNameForSms(String clientId) {
        if (clientId == null || clientId.isBlank()) return "Church";
        return emailSettingsRepo.findByClientId(clientId)
                .map(EmailSettings::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Church");
    }

    // ── Private implementation ─────────────────────────────────────────────────

    private void sendToRecipients(String recipients, String message,
                                  String clientId, boolean whatsApp) {
        Optional<WhatsAppSettings> settingsOpt = settingsRepo.findByClientId(clientId);

        // For SMS: fall back to the global SmsService (application.properties credentials)
        // when no org-level WhatsApp/SMS settings are configured.
        if (settingsOpt.isEmpty() || !credentialsValid(settingsOpt.get())) {
            if (!whatsApp && smsService.isConfigured()) {
                List<String> phones = resolvePhones(recipients, clientId);
                String body = resolveMessageNoSettings(message, clientId);
                for (String phone : phones) {
                    smsWithQuota(phone, body, clientId);
                }
            } else {
                log.debug("WhatsApp/SMS skipped for clientId={} — no credentials configured", clientId);
            }
            return;
        }

        WhatsAppSettings settings = settingsOpt.get();
        String effectiveMessage = resolveMessage(message, settings, clientId);
        List<String> phones     = resolvePhones(recipients, clientId);

        for (String phone : phones) {
            doSend(phone, effectiveMessage, settings, whatsApp, clientId);
        }
    }

    private void sendToPhone(String toPhone, String message,
                             String clientId, boolean whatsApp) {
        String normalized = normalizePhone(toPhone);
        if (normalized == null) return;

        Optional<WhatsAppSettings> settingsOpt = settingsRepo.findByClientId(clientId);

        // For SMS: fall back to global SmsService when no org-level settings exist.
        if (settingsOpt.isEmpty() || !credentialsValid(settingsOpt.get())) {
            if (!whatsApp && smsService.isConfigured()) {
                String body = resolveMessageNoSettings(message, clientId);
                smsWithQuota(normalized, body, clientId);
            } else {
                log.debug("WhatsApp/SMS to {} skipped — no credentials configured", normalized);
            }
            return;
        }

        WhatsAppSettings settings = settingsOpt.get();
        doSend(normalized, resolveMessage(message, settings, clientId), settings, whatsApp, clientId);
    }

    /**
     * Makes the actual Twilio API HTTP call.
     *
     * <p>For SMS ({@code whatsApp=false}) the global {@link SmsService} is always used
     * so that the correct SMS sender number (+18449252978) is applied instead of the
     * WhatsApp-only sender phone stored in {@code WhatsAppSettings}.
     *
     * @param toPhone   recipient phone in E.164 format (e.g. {@code +15005550006})
     * @param message   message body
     * @param settings  Twilio credentials (used for WhatsApp only)
     * @param whatsApp  {@code true} = WhatsApp prefix, {@code false} = plain SMS via SmsService
     */
    private void doSend(String toPhone, String message,
                        WhatsAppSettings settings, boolean whatsApp, String clientId) {
        // toPhone must already be normalized at this point, but guard just in case
        if (toPhone == null || toPhone.isBlank() || !toPhone.startsWith("+")) return;

        // SMS always goes through SmsService to use the correct +18449252978 sender number.
        if (!whatsApp) {
            if (smsService.isConfigured()) {
                smsWithQuota(toPhone, message, clientId);
            } else {
                log.warn("SMS to {} skipped — SmsService not configured", toPhone);
            }
            return;
        }

        // WHATSAPP DISABLED — skip the Twilio WhatsApp API call entirely
        log.debug("WhatsApp (doSend) disabled — skipping send to {}", toPhone);

        /* WHATSAPP DISABLED — original Twilio WhatsApp call commented out
        try {
            String accountSid   = settings.getAccountSid();
            String authToken    = settings.getAuthToken();
            String senderPhone  = settings.getSenderPhone();

            String from = "whatsapp:" + senderPhone;
            String to   = "whatsapp:" + toPhone;

            String body = "From=" + enc(from) +
                          "&To="   + enc(to)   +
                          "&Body=" + enc(message);

            String credentials = Base64.getEncoder().encodeToString(
                    (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(TWILIO_API, accountSid)))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("WhatsApp sent to {} (clientId={})", toPhone, settings.getClientId());
            } else {
                log.warn("WhatsApp to {} failed — status={} body={}",
                         toPhone, resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Error sending WhatsApp to {}: {}", toPhone, e.getMessage(), e);
        }
        */
    }

    /**
     * Sends a Twilio Content Template WhatsApp message to all members of the
     * given recipient groups.  Used for pre-approved WhatsApp Business templates
     * (e.g. Meeting Reminder with SID {@code HXxxxxxxxx}).
     *
     * <p>The {@code variables} map keys must match the template's variable names
     * (e.g. {@code "1" -> "churchname"} for {{1}} or named variables).
     * For Twilio Content Templates with named variables the map should be
     * {@code { "churchname": "…", "name": "…", … }}.
     *
     * @param recipients   comma-separated tokens: "Members", "Guests"
     * @param contentSid   Twilio Content Template SID (e.g. {@code HX58b1dcca...})
     * @param variables    template variable values keyed by variable name
     * @param clientId     organization client ID
     */
    public void sendTemplateToRecipients(String recipients, String contentSid,
                                         java.util.Map<String, String> variables,
                                         String clientId) {
        // WHATSAPP DISABLED — do not send WhatsApp template messages until further notice
        log.debug("WhatsApp template (sendTemplateToRecipients) disabled — skipping contentSid={} clientId={}", contentSid, clientId);

        /* WHATSAPP DISABLED — original implementation commented out
        if (contentSid == null || contentSid.isBlank()) return;

        Optional<WhatsAppSettings> settingsOpt = settingsRepo.findByClientId(clientId);
        if (settingsOpt.isEmpty() || !credentialsValid(settingsOpt.get())) {
            log.debug("Template send skipped for clientId={} — no WhatsApp credentials", clientId);
            return;
        }

        WhatsAppSettings settings = settingsOpt.get();
        List<String> phones = resolvePhones(recipients, clientId);
        if (phones.isEmpty()) return;

        for (String phone : phones) {
            doSendTemplate(phone, contentSid, variables, settings);
        }
        */
    }

    /**
     * Sends a Twilio Content Template WhatsApp message to a single pre-resolved phone number.
     *
     * @param toPhone      recipient phone in E.164 format
     * @param contentSid   Twilio Content Template SID
     * @param variables    template variable values keyed by variable name
     * @param clientId     organization client ID
     */
    public void sendTemplateToPhone(String toPhone, String contentSid,
                                    java.util.Map<String, String> variables,
                                    String clientId) {
        // WHATSAPP DISABLED — do not send WhatsApp template messages until further notice
        log.debug("WhatsApp template (sendTemplateToPhone) disabled — skipping contentSid={} clientId={}", contentSid, clientId);

        /* WHATSAPP DISABLED — original implementation commented out
        if (contentSid == null || contentSid.isBlank()) return;
        String normalized = normalizePhone(toPhone);
        if (normalized == null) return;

        Optional<WhatsAppSettings> settingsOpt = settingsRepo.findByClientId(clientId);
        if (settingsOpt.isEmpty() || !credentialsValid(settingsOpt.get())) {
            log.debug("Template send to {} skipped — no WhatsApp credentials", normalized);
            return;
        }

        doSendTemplate(normalized, contentSid, variables, settingsOpt.get());
        */
    }

    /**
     * Makes the Twilio API call to send a Content Template message via WhatsApp.
     *
     * <p>The variables map is serialized as a JSON object and passed as
     * {@code ContentVariables} in the POST body alongside {@code ContentSid}.
     * Example body:
     * <pre>
     *   From=whatsapp:+14155238886
     *   &amp;To=whatsapp:+15017122661
     *   &amp;ContentSid=HX58b1dcca49836ba44966a47975a747ae
     *   &amp;ContentVariables={"churchname":"Sharon Fellowship","name":"Anson","dateandtime":"..."}
     * </pre>
     */
    private void doSendTemplate(String toPhone, String contentSid,
                                 java.util.Map<String, String> variables,
                                 WhatsAppSettings settings) {
        // WHATSAPP DISABLED — skip the Twilio Content Template API call entirely
        log.debug("WhatsApp template (doSendTemplate) disabled — skipping send to {}", toPhone);

        /* WHATSAPP DISABLED — original Twilio Content Template call commented out
        if (toPhone == null || toPhone.isBlank() || !toPhone.startsWith("+")) return;

        try {
            String accountSid  = settings.getAccountSid();
            String authToken   = settings.getAuthToken();
            String senderPhone = settings.getSenderPhone();

            String from = "whatsapp:" + senderPhone;
            String to   = "whatsapp:" + toPhone;

            // Build ContentVariables JSON manually
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, String> e : variables.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(jsonEscape(e.getKey())).append("\":")
                    .append("\"").append(jsonEscape(e.getValue())).append("\"");
                first = false;
            }
            json.append("}");

            String body = "From="             + enc(from)
                        + "&To="              + enc(to)
                        + "&ContentSid="      + enc(contentSid)
                        + "&ContentVariables=" + enc(json.toString());

            String credentials = Base64.getEncoder().encodeToString(
                    (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(TWILIO_API, accountSid)))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("WhatsApp template {} sent to {} (clientId={})",
                         contentSid, toPhone, settings.getClientId());
            } else {
                log.warn("WhatsApp template {} to {} failed — status={} body={}",
                         contentSid, toPhone, resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Error sending WhatsApp template {} to {}: {}", contentSid, toPhone, e.getMessage(), e);
        }
        */
    }

    /** Escapes a string for safe embedding inside a JSON string value. */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Resolution helpers ─────────────────────────────────────────

    /**
     * Resolves phone numbers for "Members", "Guests" tokens from the DB.
     * Any other token (e.g. "Celebrant") is ignored here — the caller handles
     * celebrant look-ups directly.
     */
    private List<String> resolvePhones(String recipients, String clientId) {
        if (recipients == null || recipients.isBlank()) return List.of();

        java.util.Set<String> phones = new java.util.LinkedHashSet<>();
        for (String token : recipients.split(",")) {
            String t = token.trim();
            List<FamilyMember> members = switch (t) {
                case "Members"  -> memberRepo.findByMemberTypeWithPhoneByAppUser("Member",  clientId);
                case "Guests"   -> memberRepo.findByMemberTypeWithPhoneByAppUser("Guest",   clientId);
                case "Visitors" -> memberRepo.findByMemberTypeWithPhoneByAppUser("Visitor", clientId);
                // "Group:<id>" tokens carry only email addresses (group members have
                // no phone number on file) and "Celebrant" is handled by the caller.
                default         -> List.of();
            };
            members.stream()
                   .map(FamilyMember::getPhone)
                   .map(this::normalizePhone)
                   .filter(p -> p != null)
                   .forEach(phones::add);
        }
        return List.copyOf(phones);
    }

    /**
     * Resolves the display name prefix from {@code email_settings} and prepends it
     * to {@code message}.  Used when no {@code WhatsAppSettings} are available
     * (i.e. SMS is being sent via the global {@link SmsService} fallback).
     */
    private String resolveMessageNoSettings(String message, String clientId) {
        String body = (message != null && !message.isBlank()) ? message
                    : "You have a reminder from your church.";
        String displayName = emailSettingsRepo.findByClientId(clientId)
                .map(EmailSettings::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        return (displayName != null) ? displayName + ": " + body + " Reply STOP to opt out." : body + " Reply STOP to opt out.";
    }

    /**
     * Returns the final message body, falling back to the configured template when
     * the caller supplied nothing, and prepending the organization's display name
     * (from {@code email_settings.display_name}) as a subject prefix so recipients
     * know which church the message is from.
     *
     * <p>Format: {@code "Display Name: <message body>"}
     */
    private String resolveMessage(String message, WhatsAppSettings settings, String clientId) {
        String body = (message != null && !message.isBlank()) ? message : null;
        if (body == null) {
            String tmpl = settings.getMessageTemplate();
            body = (tmpl != null && !tmpl.isBlank()) ? tmpl : "You have a reminder from your church.";
        }

        // Prepend display_name from email_settings as subject prefix
        String displayName = emailSettingsRepo.findByClientId(clientId)
                .map(EmailSettings::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);

        return (displayName != null) ? displayName + ": " + body : body;
    }

    /**
     * Normalises a stored phone number to E.164 format.
     *
     * <ul>
     *   <li>Already E.164 (starts with {@code +}) → returned as-is.</li>
     *   <li>Exactly 10 digits (US number without country code) → {@code +1} prepended.</li>
     *   <li>Anything else (null, blank, wrong length) → {@code null} (skip).</li>
     * </ul>
     */
    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String trimmed = phone.trim();
        if (trimmed.startsWith("+")) return trimmed;                 // already E.164
        if (trimmed.matches("\\d{10}")) return "+1" + trimmed;       // US 10-digit
        return null;                                                 // unrecognised format
    }

    private boolean credentialsValid(WhatsAppSettings s) {
        return s.getAccountSid()   != null && !s.getAccountSid().isBlank()
            && s.getAuthToken()    != null && !s.getAuthToken().isBlank()
            && s.getSenderPhone()  != null && !s.getSenderPhone().isBlank();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
