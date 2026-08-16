package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ReminderSentLog;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Subscription expiration notifications for every plan (Trial included).
 *
 * <p>Runs daily and emails each church's registered contact:
 * <ul>
 *   <li><b>10, 5, and 1 day(s)</b> before {@code service_client.end_date} — an
 *       upcoming-expiry warning with the exact date and renewal instructions.</li>
 *   <li><b>On the end date</b> — an "expired" notice.</li>
 * </ul>
 *
 * <p>Enforcement itself needs no action here: the login validation queries
 * ({@code LoginRepository.countValidChurchLogin / countValidNonChurchLogin})
 * already require {@code end_date > current_date AND status='Active'}, so from
 * the end date onward every user of the clientId is automatically blocked from
 * logging in until the Service Admin extends the subscription. All data is
 * retained; renewing or choosing another plan restores access with that plan's
 * features.
 *
 * <p>Deduplication uses {@code reminder_sent_log} (type
 * {@code SUBSCRIPTION_EXPIRY}, one row per client per warning-day per date),
 * so repeated runs never double-send. Emails go through
 * {@code sendGenericEmail} (platform-level) and do NOT count against the
 * client's own monthly email quota.
 */
@Service
public class SubscriptionExpiryNotifier {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryNotifier.class);
    private static final String SENT_LOG_TYPE = "SUBSCRIPTION_EXPIRY";
    private static final int[] WARNING_DAYS = {10, 5, 1};
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final ServiceClientRepository   clientRepo;
    private final ReminderSentLogRepository sentLogRepo;
    private final EmailService              emailService;

    public SubscriptionExpiryNotifier(ServiceClientRepository clientRepo,
                                      ReminderSentLogRepository sentLogRepo,
                                      EmailService emailService) {
        this.clientRepo  = clientRepo;
        this.sentLogRepo = sentLogRepo;
        this.emailService = emailService;
    }

    @Scheduled(cron = "${scheduler.job.subscription-expiry:0 0 7 * * *}", zone = "America/Chicago")
    public void run() {
        LocalDate today = LocalDate.now();
        int sent = 0;
        for (int days : WARNING_DAYS) {
            sent += notifyFor(today, today.plusDays(days), days);
        }
        sent += notifyFor(today, today, 0);   // expired today
        log.info("Subscription expiry notifier ran for {} — {} notification(s) sent", today, sent);
    }

    private int notifyFor(LocalDate today, LocalDate endDate, int daysLeft) {
        int sent = 0;
        List<ServiceClient> clients =
                clientRepo.findByEndDateAndStatusAndDeleteFlagFalse(endDate, "Active");
        for (ServiceClient sc : clients) {
            try {
                if (sc.getEmail() == null || sc.getEmail().isBlank()) continue;
                String refKey = sc.getClientId() + "_" + daysLeft;
                if (sentLogRepo.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
                        sc.getClientId(), SENT_LOG_TYPE, refKey, today)) continue;
                if (!markSent(sc.getClientId(), refKey, today)) continue;

                emailService.sendGenericEmail(sc.getEmail(), subject(sc, daysLeft), body(sc, daysLeft));
                sent++;
                log.info("Subscription expiry notice ({} day(s)) sent to {} for clientId={}",
                        daysLeft, sc.getEmail(), sc.getClientId());
            } catch (Exception e) {
                log.error("Subscription expiry notice failed for clientId={} — {}",
                        sc.getClientId(), e.getMessage());
            }
        }
        return sent;
    }

    private String subject(ServiceClient sc, int daysLeft) {
        String church = sc.getChurchName() != null ? sc.getChurchName() : "Your church";
        if (daysLeft == 0) return "⛔ " + church + " — Your ChurchGeniusPro subscription has expired";
        return "⏰ " + church + " — Your ChurchGeniusPro subscription expires in "
                + daysLeft + (daysLeft == 1 ? " day" : " days");
    }

    private String body(ServiceClient sc, int daysLeft) {
        String church  = esc(sc.getChurchName() != null ? sc.getChurchName() : "your church");
        String endDate = sc.getEndDate() != null ? sc.getEndDate().format(DATE_FMT) : "—";
        String plan    = sc.getSubscriptionType() != null ? esc(sc.getSubscriptionType()) : "—";

        String headline = daysLeft == 0
                ? "Your subscription expired on <strong>" + endDate + "</strong>."
                : "Your subscription will expire in <strong>" + daysLeft
                  + (daysLeft == 1 ? " day" : " days") + "</strong>, on <strong>" + endDate + "</strong>.";
        String consequence = daysLeft == 0
                ? "Access for all users of " + church + " is now suspended. Nothing has been deleted — "
                  + "all of your members, giving history, and records are safely retained and will be "
                  + "available again as soon as your subscription is renewed."
                : "When the subscription expires, all users of " + church + " will be unable to log in "
                  + "until it is renewed or extended. No data will be deleted.";

        return "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#f5f6fa;"
             + "font-family:-apple-system,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='padding:36px 16px;'><tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='max-width:520px;background:#fff;"
             + "border-radius:14px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);'>"
             + "<tr><td style='background:#673147;padding:24px 36px;text-align:center;'>"
             + "<p style='margin:0;font-size:19px;font-weight:700;color:#fff;'>ChurchGeniusPro</p>"
             + "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,.75);'>Subscription Notice</p></td></tr>"
             + "<tr><td style='padding:30px 36px;'>"
             + "<p style='margin:0 0 14px;font-size:15px;color:#1a1a2e;font-weight:600;'>Hello " + church + ",</p>"
             + "<p style='margin:0 0 14px;font-size:14px;color:#555;line-height:1.7;'>" + headline + "</p>"
             + "<table style='font-size:13px;color:#555;margin:0 0 16px;'>"
             + "<tr><td style='padding:3px 12px 3px 0;font-weight:600;'>Subscription:</td><td>" + plan + "</td></tr>"
             + "<tr><td style='padding:3px 12px 3px 0;font-weight:600;'>Expiration date:</td><td>" + endDate + "</td></tr>"
             + "</table>"
             + "<p style='margin:0 0 18px;font-size:13.5px;color:#555;line-height:1.7;'>" + consequence + "</p>"
             + "<p style='margin:0;font-size:13.5px;color:#555;line-height:1.7;'>To renew, extend, or choose a "
             + "different plan, please contact us at "
             + "<a href='mailto:info@churchgeniuspro.com' style='color:#673147;font-weight:600;'>info@churchgeniuspro.com</a>.</p>"
             + "</td></tr></table></td></tr></table></body></html>";
    }

    /** Claims the send slot; unique constraint makes duplicate sends impossible. */
    private boolean markSent(String clientId, String referenceKey, LocalDate today) {
        try {
            ReminderSentLog logRow = new ReminderSentLog();
            logRow.setAppClientId(clientId);
            logRow.setReminderType(SENT_LOG_TYPE);
            logRow.setReferenceKey(referenceKey);
            logRow.setSentDate(today);
            sentLogRepo.saveAndFlush(logRow);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
