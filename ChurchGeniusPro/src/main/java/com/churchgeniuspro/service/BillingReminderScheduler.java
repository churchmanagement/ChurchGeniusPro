package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.ReminderSentLog;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.util.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Billing reminders (daily, 07:30 Chicago) for paid-plan clients whose next billing
 * date (end date) is 10, 3 or 0 days away. Two independent switches, both OFF by
 * default (Service Admin → Billing → Reminder settings):
 *
 * <ul>
 *   <li><b>Notify the Support Email</b> — one digest listing the clients due and what
 *       happened to each.</li>
 *   <li><b>Email the client an invoice</b> — if the client has no renewal invoice for
 *       that date one is created and sent; if one was sent and is unpaid it is sent
 *       again (with a new link). A DRAFT is never sent automatically — not even one
 *       this job created on an earlier day whose email failed; it waits for review and
 *       is listed in the Support Email digest. A paid invoice is left alone, and a
 *       voided renewal invoice is not re-created.</li>
 * </ul>
 *
 * <p>With both switches off this job does nothing. The existing subscription expiry
 * notices ({@link SubscriptionExpiryNotifier}) are separate and unchanged.
 *
 * <p>Renewal invoices are found by billing date (the client's end date = the invoice's
 * {@code period_start}), never by due date, so editing a due date cannot make this job
 * create a second invoice for the same billing date.
 *
 * <p>Each send is claimed once in {@code reminder_sent_log} (types
 * {@code BILLING_CLIENT} / {@code BILLING_SUPPORT}, key {@code <due date>_<days>}),
 * so a second run or a second server never sends it twice; the claim is released if
 * the email fails.
 */
@Service
public class BillingReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingReminderScheduler.class);
    static final int[] DAYS = {10, 3, 0};
    static final String CLIENT_TYPE  = "BILLING_CLIENT";
    static final String SUPPORT_TYPE = "BILLING_SUPPORT";
    static final String ACTOR = "system (billing reminders)";
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final BillingService billing;
    private final PlatformSettingService settings;
    private final ReminderSentLogRepository sentLog;
    private final EmailService email;

    public BillingReminderScheduler(BillingService billing, PlatformSettingService settings,
                                    ReminderSentLogRepository sentLog, EmailService email) {
        this.billing = billing;
        this.settings = settings;
        this.sentLog = sentLog;
        this.email = email;
    }

    /** One client due, and what the client channel did (or would do) about it. */
    record Row(ServiceClient client, LocalDate due, int days, String outcome) {}

    @Scheduled(cron = "${scheduler.job.billing-reminders:0 30 7 * * *}", zone = "America/Chicago")
    public void run() {
        runFor(AppClock.today());
    }

    /** The day's run; returns the rows considered (empty when both switches are off). */
    List<Row> runFor(LocalDate today) {
        boolean toSupport = settings.isEnabled(PlatformSettingService.BILLING_REMINDER_SUPPORT_ENABLED);
        boolean toClient  = settings.isEnabled(PlatformSettingService.BILLING_REMINDER_CLIENT_ENABLED);
        if (!toSupport && !toClient) {
            log.debug("Billing reminders: both switches off — nothing to do.");
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (int days : DAYS) {
            LocalDate due = today.plusDays(days);
            for (ServiceClient sc : billing.billableDueBetween(due, due)) {
                String outcome;
                try {
                    outcome = toClient ? clientReminder(sc, due, days, today) : state(sc, due);
                } catch (Exception e) {
                    log.error("Billing reminder for {} failed — {}", sc.getClientId(), e.toString());
                    outcome = "Failed: " + e.getMessage();
                }
                rows.add(new Row(sc, due, days, outcome));
            }
        }
        if (toSupport && !rows.isEmpty()) supportDigest(rows, today);
        log.info("Billing reminders ran for {} — {} client(s) due (support={}, client={})", today, rows.size(), toSupport, toClient);
        return rows;
    }

    /**
     * Client channel: for the renewal invoice of billing date {@code due} — none yet →
     * create it and send it at once; SENT and unpaid → re-send it (new link). A DRAFT is
     * never sent, whoever created it — including a draft this job created on an earlier
     * day whose email failed: it waits for a Service Admin to review and send it.
     */
    private String clientReminder(ServiceClient sc, LocalDate due, int days, LocalDate today) {
        Optional<BillingInvoice> inv = billing.renewalFor(sc.getClientId(), due);
        if (inv.isPresent()) {
            BillingInvoice i = inv.get();
            if (BillingInvoice.PAID.equals(i.getStatus())) return "Paid (" + i.getInvoiceNumber() + ")";
            if (BillingInvoice.DRAFT.equals(i.getStatus())) return draftWaiting(i);
        } else {
            if (billing.anyRenewalFor(sc.getClientId(), due)) return "Renewal invoice was voided — not re-created";
            if (billing.renewalAmount(sc).signum() <= 0) return "Price is $0.00 — no invoice sent";
        }
        ReminderSentLog claim = claim(sc.getClientId(), CLIENT_TYPE, due + "_" + days, today);
        if (claim == null) return "Already sent today";
        String note = days == 0 ? "Payment is due today."
                : "This is a reminder: payment is due in " + days + (days == 1 ? " day" : " days") + ", on " + due.format(LONG_DAY) + ".";
        try {
            if (inv.isPresent()) {   // SENT, unpaid
                billing.resend(inv.get().getId(), ACTOR, note);
                return "Reminder sent with invoice " + inv.get().getInvoiceNumber();
            }
            BillingService.Draft d = billing.createRenewalDraft(sc.getClientId(), ACTOR);
            BillingInvoice i = d.invoice();
            if (!d.created()) {   // someone created it a moment ago — never send theirs
                release(claim);
                return BillingInvoice.DRAFT.equals(i.getStatus()) ? draftWaiting(i)
                        : "Invoice " + i.getInvoiceNumber() + " (" + i.getStatus().toLowerCase() + ") was created elsewhere — not sent";
            }
            try {
                billing.send(i.getId(), null, ACTOR, days == 10 ? null : note);
            } catch (RuntimeException sendFailed) {
                release(claim);
                return "Invoice " + i.getInvoiceNumber() + " was created but its email failed (" + sendFailed.getMessage()
                        + "). It is a draft now and will not be sent automatically — please review and send it";
            }
            return "Invoice " + i.getInvoiceNumber() + " sent";
        } catch (RuntimeException e) {
            release(claim);
            throw e;
        }
    }

    private static String draftWaiting(BillingInvoice i) {
        return "Draft " + i.getInvoiceNumber() + " is waiting for review — not sent automatically"
                + (ACTOR.equals(i.getCreatedBy()) ? " (its automatic email failed earlier)" : "");
    }

    /** What the Support Email is told when the client channel is off. */
    private String state(ServiceClient sc, LocalDate due) {
        return billing.renewalFor(sc.getClientId(), due)
                .map(i -> "Invoice " + i.getInvoiceNumber() + " — " + i.getStatus().toLowerCase())
                .orElse("No invoice yet");
    }

    private void supportDigest(List<Row> rows, LocalDate today) {
        List<Row> mine = new ArrayList<>();
        List<ReminderSentLog> claims = new ArrayList<>();
        for (Row r : rows) {
            ReminderSentLog c = claim(r.client().getClientId(), SUPPORT_TYPE, r.due() + "_" + r.days(), today);
            if (c != null) { mine.add(r); claims.add(c); }
        }
        if (mine.isEmpty()) return;
        String to = settings.supportEmail();
        try {
            email.sendComposed(List.of(to), null,
                    "Billing: " + mine.size() + " client" + (mine.size() == 1 ? "" : "s") + " due — " + today.format(LONG_DAY),
                    digestHtml(mine), null, "ChurchGeniusPro Billing");
        } catch (Exception e) {
            claims.forEach(this::release);
            log.error("Billing reminders: Support Email digest to {} failed — {}", to, e.toString());
        }
    }

    String digestHtml(List<Row> rows) {
        StringBuilder sb = new StringBuilder("<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#2b2b2b;\">"
                + "<p>These clients reach their next billing date soon. Review them under "
                + "<strong>Service Admin → Billing</strong>.</p><table cellpadding=\"7\" style=\"border-collapse:collapse;font-size:13px;\">"
                + "<tr style=\"background:#f7f7fa;\"><th align=\"left\">Church</th><th align=\"left\">Client ID</th>"
                + "<th align=\"left\">Billing date</th><th align=\"right\">Amount</th><th align=\"left\">Status</th></tr>");
        for (Row r : rows) {
            ServiceClient sc = r.client();
            sb.append("<tr><td style=\"border:1px solid #e3e3e8;\">").append(BillingService.esc(sc.getChurchName()))
              .append("</td><td style=\"border:1px solid #e3e3e8;\">").append(BillingService.esc(sc.getClientId()))
              .append("</td><td style=\"border:1px solid #e3e3e8;white-space:nowrap;\">").append(r.due().format(LONG_DAY))
              .append(r.days() == 0 ? " (today)" : " (in " + r.days() + " days)")
              .append("</td><td style=\"border:1px solid #e3e3e8;text-align:right;\">")
              .append(BillingService.money(billing.renewalAmount(sc))).append(" ")
              .append(BillingService.frequencyOf(sc).equals(SubscriptionLifecycleService.YEARLY) ? "/yr" : "/mo")
              .append("</td><td style=\"border:1px solid #e3e3e8;\">").append(BillingService.esc(r.outcome())).append("</td></tr>");
        }
        return sb.append("</table></div>").toString();
    }

    /** Claims a send slot; null when it was already claimed (sent) today. */
    private ReminderSentLog claim(String clientId, String type, String key, LocalDate today) {
        try {
            if (sentLog.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(clientId, type, key, today)) return null;
            ReminderSentLog row = new ReminderSentLog();
            row.setAppClientId(clientId);
            row.setReminderType(type);
            row.setReferenceKey(key);
            row.setSentDate(today);
            return sentLog.saveAndFlush(row);
        } catch (Exception e) {
            return null;
        }
    }

    private void release(ReminderSentLog claim) {
        try { sentLog.delete(claim); } catch (Exception e) {
            log.warn("Billing reminders: could not release claim {} — {}", claim.getReferenceKey(), e.getMessage());
        }
    }
}
