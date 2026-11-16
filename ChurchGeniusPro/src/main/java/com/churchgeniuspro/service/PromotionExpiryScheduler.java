package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ClientPromotion;
import com.churchgeniuspro.hibernate.ReminderSentLog;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.EmailMask;
import com.churchgeniuspro.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Promotions phase 4 — the daily pass (America/Chicago):
 * <ol>
 *   <li>Scheduled discounts whose start date arrived become Active;</li>
 *   <li>Active discounts whose last day has passed become Expired, with the automatic
 *       "Promotion expired – Regular subscription pricing restored" history entry;</li>
 *   <li>the client is told 30, 10 and 1 day(s) before its promotional pricing ends
 *       (30 only when the discount runs longer than one month), with a copy to the
 *       Support Email.</li>
 * </ol>
 * Each notice is claimed once in {@code reminder_sent_log} (type
 * {@code PROMOTION_EXPIRY}, key {@code <discount id>_<end date>_<days>}) before it is
 * sent, so a second run or a second server never sends it twice; the claim is released
 * when the email fails. One client failing never stops the others. Pricing does not
 * depend on this job: invoices follow the discount's dates.
 */
@Service
public class PromotionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PromotionExpiryScheduler.class);
    static final String TYPE = "PROMOTION_EXPIRY";
    static final int[] NOTICE_DAYS = { 30, 10, 1 };
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final ClientPromotionService promotions;
    private final ServiceClientRepository clients;
    private final PlatformSettingService settings;
    private final ReminderSentLogRepository sentLog;
    private final EmailService email;

    @Value("${promotion.notices.enabled:true}")
    private boolean noticesEnabled = true;

    public PromotionExpiryScheduler(ClientPromotionService promotions, ServiceClientRepository clients,
                                    PlatformSettingService settings, ReminderSentLogRepository sentLog, EmailService email) {
        this.promotions = promotions;
        this.clients = clients;
        this.settings = settings;
        this.sentLog = sentLog;
        this.email = email;
    }

    /** Test seam. */
    void setNoticesEnabled(boolean on) { this.noticesEnabled = on; }

    @Scheduled(cron = "${scheduler.job.promotion-expiry:0 10 7 * * *}", zone = "America/Chicago")
    public void run() {
        runFor(AppClock.today());
    }

    /** What one pass did. */
    record Result(int activated, int expired, int noticesSent) {}

    Result runFor(LocalDate today) {
        int activated = 0, expired = 0, sent = 0;
        try { activated = promotions.activateDue(today); }
        catch (Exception e) { log.warn("Promotions: activation pass failed — {}", e.getClass().getSimpleName()); }
        try { expired = promotions.expireDue(today); }
        catch (Exception e) { log.warn("Promotions: expiry pass failed — {}", e.getClass().getSimpleName()); }
        if (noticesEnabled) {
            for (int days : NOTICE_DAYS) {
                List<ClientPromotion> ending;
                try { ending = promotions.activeEndingOn(today.plusDays(days)); }
                catch (Exception e) { log.warn("Promotions: notice lookup failed — {}", e.getClass().getSimpleName()); continue; }
                for (ClientPromotion a : ending) {
                    try { if (notify(a, days, today)) sent++; }
                    catch (Exception e) {
                        log.warn("Promotions: notice for discount {} of {} failed — {}", a.getId(), a.getClientId(), e.getClass().getSimpleName());
                    }
                }
            }
        }
        if (activated + expired + sent > 0) {
            log.info("Promotions: {} activated, {} expired, {} notice(s) sent", activated, expired, sent);
        }
        return new Result(activated, expired, sent);
    }

    /** The 30-day notice is only for discounts that run longer than one month. */
    static boolean longerThanOneMonth(ClientPromotion a) {
        return a.getStartDate() != null && a.getEndDate() != null && !a.getEndDate().isBefore(a.getStartDate().plusMonths(1));
    }

    private boolean notify(ClientPromotion a, int days, LocalDate today) {
        if (days == 30 && !longerThanOneMonth(a)) return false;
        if (a.getStartDate() == null || a.getStartDate().isAfter(today)) return false;      // not started yet
        ServiceClient sc = clients.findByClientId(a.getClientId()).orElse(null);
        if (sc == null || Boolean.TRUE.equals(sc.getDeleteFlag()) || !"Active".equalsIgnoreCase(sc.getStatus())) return false;
        String to = sc.getEmail() == null ? "" : sc.getEmail().trim();
        if (to.isEmpty()) {
            log.info("Promotions: no billing email for {} — {}-day notice not sent", sc.getClientId(), days);
            return false;
        }
        ClientPromotionService.Quote q = promotions.quote(sc, a.getEndDate());
        if (!q.discounted() || !a.getId().equals(q.discount().getId())) return false;     // no promotional price in force

        ReminderSentLog claim = claim(sc.getClientId(), a.getId() + "_" + a.getEndDate() + "_" + days, today);
        if (claim == null) return false;                                                   // already sent
        try {
            String support = settings.supportEmail();
            List<String> bcc = support == null || support.isBlank() || support.equalsIgnoreCase(to) ? null : List.of(support.trim());
            email.sendComposed(List.of(to), bcc, subject(a, days), html(sc, a, q, days), null, "ChurchGeniusPro Billing");
            log.info("Promotions: {}-day notice sent to {} for {}", days, EmailMask.mask(to), sc.getClientId());
            return true;
        } catch (Exception e) {
            release(claim);
            log.error("Promotions: {}-day notice to {} failed — {}", days, EmailMask.mask(to), LogSafe.exception(e));
            return false;
        }
    }

    /**
     * Phase 5 — after a trial was converted with its discount applied: tells the church
     * its promotional price, when it ends and the regular price afterwards (copy to the
     * Support Email). Returns false, never throws, when there is nothing to send or the
     * email fails.
     */
    public boolean sendAppliedConfirmation(ServiceClient sc) {
        try {
            if (sc == null || sc.getClientId() == null || sc.getEmail() == null || sc.getEmail().isBlank()) return false;
            ClientPromotion a = promotions.liveDated(sc.getClientId());
            if (a == null) return false;
            ClientPromotionService.Quote q = promotions.quote(sc, a.getStartDate());
            if (!q.discounted()) return false;
            String to = sc.getEmail().trim();
            String support = settings.supportEmail();
            List<String> bcc = support == null || support.isBlank() || support.equalsIgnoreCase(to) ? null : List.of(support.trim());
            email.sendComposed(List.of(to), bcc, "Your ChurchGeniusPro promotional pricing", appliedHtml(sc, a, q), null, "ChurchGeniusPro Billing");
            log.info("Promotions: applied-discount confirmation sent to {} for {}", EmailMask.mask(to), sc.getClientId());
            return true;
        } catch (Exception e) {
            log.error("Promotions: applied-discount confirmation failed — {}", LogSafe.exception(e));
            return false;
        }
    }

    String appliedHtml(ServiceClient sc, ClientPromotion a, ClientPromotionService.Quote q) {
        String per = SubscriptionLifecycleService.YEARLY.equals(a.getBillingFrequency()) ? "year" : "month";
        String church = BillingService.esc(sc.getChurchName() != null ? sc.getChurchName() : "there");
        String end = a.getEndDate().format(LONG_DAY);
        String from = a.getEndDate().plusDays(1).format(LONG_DAY);
        return "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='padding:36px 16px;'><tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='max-width:540px;background:#fff;border-radius:14px;"
             + "overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);'>"
             + "<tr><td style='background:#673147;padding:24px 36px;text-align:center;'>"
             + "<p style='margin:0;font-size:19px;font-weight:700;color:#fff;'>ChurchGeniusPro</p>"
             + "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,.75);'>Your promotional pricing</p></td></tr>"
             + "<tr><td style='padding:30px 36px;'>"
             + "<p style='margin:0 0 14px;font-size:15px;color:#1a1a2e;font-weight:600;'>Hello " + church + ",</p>"
             + "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>Promotional pricing has been applied to your subscription.</p>"
             + "<table style='font-size:13.5px;color:#444;margin:0 0 16px;'>"
             + row("Promotion", BillingService.esc(ClientPromotionService.invoiceLabel(a)))
             + row("Promotional price", BillingService.money(q.price()) + " per " + per)
             + row("Promotional period", a.getStartDate().format(LONG_DAY) + " – " + end)
             + row("Regular price", BillingService.money(q.regularPrice()) + " per " + per + " (from " + from + ")")
             + "</table>"
             + "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>When the promotional period ends, your subscription "
             + "continues at the regular price. We will remind you before that happens.</p>"
             + "<p style='margin:0;font-size:12px;color:#888;line-height:1.6;'>Questions? Contact "
             + BillingService.esc(settings.supportEmail()) + ".</p>"
             + "</td></tr></table></td></tr></table></body></html>";
    }

    static String subject(ClientPromotion a, int days) {
        return "Your ChurchGeniusPro promotional pricing ends " + (days == 1 ? "tomorrow" : "in " + days + " days")
                + " (" + a.getEndDate().format(LONG_DAY) + ")";
    }

    String html(ServiceClient sc, ClientPromotion a, ClientPromotionService.Quote q, int days) {
        String per = SubscriptionLifecycleService.YEARLY.equals(a.getBillingFrequency()) ? "year" : "month";
        String church = BillingService.esc(sc.getChurchName() != null ? sc.getChurchName() : "there");
        String label = BillingService.esc(ClientPromotionService.invoiceLabel(a));
        String end = a.getEndDate().format(LONG_DAY);
        String from = a.getEndDate().plusDays(1).format(LONG_DAY);
        return "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='padding:36px 16px;'><tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='max-width:540px;background:#fff;border-radius:14px;"
             + "overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);'>"
             + "<tr><td style='background:#673147;padding:24px 36px;text-align:center;'>"
             + "<p style='margin:0;font-size:19px;font-weight:700;color:#fff;'>ChurchGeniusPro</p>"
             + "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,.75);'>Promotional pricing reminder</p></td></tr>"
             + "<tr><td style='padding:30px 36px;'>"
             + "<p style='margin:0 0 14px;font-size:15px;color:#1a1a2e;font-weight:600;'>Hello " + church + ",</p>"
             + "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>Your promotional pricing ends "
             + (days == 1 ? "<strong>tomorrow</strong>" : "in <strong>" + days + " days</strong>") + ", on <strong>" + end + "</strong>.</p>"
             + "<table style='font-size:13.5px;color:#444;margin:0 0 16px;'>"
             + row("Promotion", label)
             + row("Current price", BillingService.money(q.price()) + " per " + per + " (through " + end + ")")
             + row("Regular price", BillingService.money(q.regularPrice()) + " per " + per + " (from " + from + ")")
             + "</table>"
             + "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>You do not need to do anything. "
             + "Your subscription continues, and invoices for billing periods that start on or after " + from
             + " will be at the regular price.</p>"
             + "<p style='margin:0;font-size:12px;color:#888;line-height:1.6;'>Questions? Contact "
             + BillingService.esc(settings.supportEmail()) + ".</p>"
             + "</td></tr></table></td></tr></table></body></html>";
    }

    private static String row(String k, String v) {
        return "<tr><td style='padding:3px 14px 3px 0;font-weight:600;vertical-align:top;'>" + k + ":</td><td>" + v + "</td></tr>";
    }

    /** Claims a send slot; null when this notice was already sent. */
    private ReminderSentLog claim(String clientId, String key, LocalDate today) {
        try {
            if (sentLog.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(clientId, TYPE, key, today)) return null;
            ReminderSentLog row = new ReminderSentLog();
            row.setAppClientId(clientId);
            row.setReminderType(TYPE);
            row.setReferenceKey(key);
            row.setSentDate(today);
            return sentLog.saveAndFlush(row);
        } catch (Exception e) {
            return null;
        }
    }

    private void release(ReminderSentLog claim) {
        try { sentLog.delete(claim); } catch (Exception e) {
            log.warn("Promotions: could not release notice claim {} — {}", claim.getReferenceKey(), e.getClass().getSimpleName());
        }
    }
}
