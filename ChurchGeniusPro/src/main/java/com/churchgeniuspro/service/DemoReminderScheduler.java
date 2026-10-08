package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.DemoClientSettings;
import com.churchgeniuspro.hibernate.DemoReminderLog;
import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoClientSettingsRepository;
import com.churchgeniuspro.repository.DemoReminderLogRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Daily expiry reminders for demo/test roles.
 *
 * <p>Reminder dates are never stored. Each run recomputes them from every role's
 * CURRENT end date, which is what makes the requirement "if the expiration date
 * changes, the reminders must recalculate" true by construction rather than by
 * remembering to update a second table.
 *
 * <p>Reminders go to the Service Admin, deliberately: demo tenants have real
 * delivery blocked by default, so mailing the demo user would either send
 * nothing or — worse — reach a real address the demo data invented. The message
 * states each client's live SMS/email delivery setting, so the admin can see at
 * a glance whether that tenant can be messaged at all.
 */
@Service
public class DemoReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemoReminderScheduler.class);

    private final DemoRoleAccessRepository     accessRepo;
    private final DemoClientSettingsRepository settingsRepo;
    private final DemoReminderLogRepository    logRepo;
    private final EmailService                 emailService;

    /**
     * Where the digest goes: the configured Support Email (Service Admin → Platform
     * Settings) when available, else this property, else the default inbox.
     */
    @Value("${demo.reminder.recipient:support@churchgeniuspro.com}")
    private String recipient;
    private PlatformSettingService platformSettings;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformSettings(PlatformSettingService s) { this.platformSettings = s; }
    private String recipient() { return platformSettings != null ? platformSettings.supportEmail() : recipient; }

    /**
     * The church registered with the demo/trial tenant: its registered address gets
     * the expiry notice too. Optional so existing constructions keep working.
     */
    private com.churchgeniuspro.repository.ServiceClientRepository clientRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientRepo(com.churchgeniuspro.repository.ServiceClientRepository r) { this.clientRepo = r; }

    public DemoReminderScheduler(DemoRoleAccessRepository accessRepo,
                                 DemoClientSettingsRepository settingsRepo,
                                 DemoReminderLogRepository logRepo,
                                 EmailService emailService) {
        this.accessRepo   = accessRepo;
        this.settingsRepo = settingsRepo;
        this.logRepo      = logRepo;
        this.emailService = emailService;
    }

    /** 08:00 daily, in the same zone the other schedulers evaluate in. */
    @Scheduled(cron = "${scheduler.job.demo-expiry-reminders:0 0 8 * * *}", zone = "America/Chicago")
    public void run() {
        try {
            int sent = sweep(LocalDate.now());
            if (sent > 0) log.info("DemoReminderScheduler: {} reminder(s) sent", sent);
        } catch (Exception e) {
            log.error("DemoReminderScheduler: sweep failed — {}", e.getMessage(), e);
        }
    }

    /**
     * Evaluates every countdown candidate for {@code today}.
     *
     * <p>Package-visible and date-parameterised so the logic can be exercised
     * without waiting for 08:00 to come round.
     *
     * @return how many reminders were sent
     */
    public int sweep(LocalDate today) {
        List<DemoRoleAccess> candidates = accessRepo.findCountdownCandidates(today);
        Map<String, DemoClientSettings> settingsCache = new HashMap<>();
        int sent = 0;

        for (DemoRoleAccess role : candidates) {
            LocalDate end = role.getEndDate();
            if (end == null) continue;
            long daysLeft = ChronoUnit.DAYS.between(today, end);

            DemoClientSettings cfg = settingsCache.computeIfAbsent(role.getClientId(),
                    id -> settingsRepo.findById(id).orElse(null));
            String csv = (cfg != null && cfg.getReminderDays() != null)
                    ? cfg.getReminderDays() : "10,5,1";

            for (int daysBefore : parseDays(csv)) {
                if (daysLeft != daysBefore) continue;
                // Scoped to this end date: move the expiry and the reminder re-arms.
                if (logRepo.alreadySent(role.getId(), daysBefore, end)) continue;
                if (send(role, cfg, daysBefore, end)) sent++;
            }
        }
        return sent;
    }

    /**
     * The tenant's own copies of the expiry notice, both in addition to the Service
     * Admin's: (1) the address registered with the subscription/trial registration,
     * as account mail (a notice to the account owner — never blocked, never metered);
     * (2) the verified Trial/Demo test address through the test-email mechanism, one
     * test email for this action. Returns a suffix for the delivery record.
     */
    private String notifyTenant(String clientId, String subject, String body) {
        if (clientId == null || clientRepo == null) return "";
        StringBuilder out = new StringBuilder();
        String registered = null;
        try {
            registered = clientRepo.findByClientId(clientId)
                    .map(com.churchgeniuspro.hibernate.ServiceClient::getEmail)
                    .filter(e -> e != null && !e.isBlank()).orElse(null);
            if (registered != null) {
                emailService.sendAccountEmail(registered, subject, body, clientId);
                out.append("+REGISTERED");
            }
        } catch (Exception e) {
            log.warn("DemoReminderScheduler: registered-address notice failed for {} — {}", clientId, e.getMessage());
        }
        try {
            EmailService.Delivery d = emailService.delivery(clientId);
            if (d != null && d.test()) {
                try (com.churchgeniuspro.util.EmailActionScope scope = com.churchgeniuspro.util.EmailActionScope.begin(
                        "demo-expiry:" + clientId + ":" + subject)) {
                    // Nominal recipient is the registered address; the tenant block redirects it to the verified test address.
                    emailService.sendOrgEmail(registered != null ? registered : "owner@" + clientId.toLowerCase(), subject, body, clientId);
                    if (scope.testEmailsSent() > 0) out.append("+TEST_EMAIL");
                }
            }
        } catch (Exception e) {
            log.warn("DemoReminderScheduler: test-address notice failed for {} — {}", clientId, e.getMessage());
        }
        return out.toString();
    }

    static List<Integer> parseDays(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split("[,;\\s]+")) {
            try {
                int d = Integer.parseInt(p.trim());
                if (d > 0 && d <= 365) out.add(d);
            } catch (NumberFormatException ignored) { /* skip junk */ }
        }
        return out;
    }

    private boolean send(DemoRoleAccess role, DemoClientSettings cfg, int daysBefore, LocalDate end) {
        boolean smsOn   = cfg != null && Boolean.TRUE.equals(cfg.getAllowSms());
        boolean emailOn = cfg != null && Boolean.TRUE.equals(cfg.getAllowEmail());

        String subject = "Demo trial ends in " + daysBefore + " day" + (daysBefore == 1 ? "" : "s")
                       + " — " + role.getClientId()
                       + (role.getUsername() != null ? " (" + role.getUsername() + ")" : "");

        String body = """
            <div style="font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#333;">
              <p>Your trial period will end in <b>%d day%s</b>. If you would like to continue
                 using the application or have any questions, please contact
                 <a href="mailto:support@churchgeniuspro.com">support@churchgeniuspro.com</a>
                 or visit <a href="https://www.churchgeniuspro.com">www.churchgeniuspro.com</a>.</p>
              <table style="border-collapse:collapse;font-size:13px;margin-top:14px;">
                <tr><td style="padding:4px 12px 4px 0;color:#777;">Client ID</td><td><b>%s</b></td></tr>
                <tr><td style="padding:4px 12px 4px 0;color:#777;">Role</td><td>%s</td></tr>
                <tr><td style="padding:4px 12px 4px 0;color:#777;">Member</td><td>%s</td></tr>
                <tr><td style="padding:4px 12px 4px 0;color:#777;">Username</td><td>%s</td></tr>
                <tr><td style="padding:4px 12px 4px 0;color:#777;">End date</td><td><b>%s</b></td></tr>
              </table>
              <p style="margin-top:14px;padding:10px 12px;background:%s;border-radius:6px;">
                Delivery for this demo client — SMS: <b>%s</b> &nbsp;·&nbsp; Email: <b>%s</b>.
                %s
              </p>
            </div>
            """.formatted(
                daysBefore, daysBefore == 1 ? "" : "s",
                nz(role.getClientId()), nz(role.getRoleLabel()), nz(role.getMemberName()),
                nz(role.getUsername()), end,
                (smsOn || emailOn) ? "#e8f5e9" : "#fff8e1",
                smsOn ? "Enabled" : "Blocked", emailOn ? "Enabled" : "Blocked",
                (smsOn || emailOn) ? "" : "This demo client cannot message its congregation; this "
                                        + "notice goes to the Service Admin, the address registered with "
                                        + "the trial, and the tenant's verified test address if one is set.");

        String delivery;
        try {
            // The 3-argument overload carries no client id and is therefore not
            // subject to the demo send block — correct here, because the recipient
            // is the Service Admin rather than the demo tenant.
            emailService.sendGenericEmail(recipient(), subject, body);
            delivery = "SERVICE_ADMIN";
        } catch (Exception e) {
            log.warn("DemoReminderScheduler: could not email {} — {}", recipient(), e.getMessage());
            delivery = "FAILED";
        }
        delivery = delivery + notifyTenant(role.getClientId(), subject, body);

        DemoReminderLog entry = new DemoReminderLog();
        entry.setClientId(role.getClientId());
        entry.setRoleAccessId(role.getId());
        entry.setDaysBefore(daysBefore);
        entry.setForEndDate(end);
        entry.setSentAt(LocalDateTime.now());
        entry.setDelivery(delivery);
        logRepo.save(entry);
        return delivery.startsWith("SERVICE_ADMIN");
    }

    private static String nz(String s) { return s == null ? "—" : s; }
}
