package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchDeletionRequest;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.ChurchDeletionRequestRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App Store readiness D7 (2026-11-06) — deleting a whole church account, started in the app.
 *
 * <ol>
 *   <li><b>Request</b> — the church login or a SuperAdmin re-enters their password and types the
 *       church's name. A link valid for {@value #TOKEN_HOURS} hours is e-mailed to the church's
 *       REGISTERED address (not to whoever is signed in).</li>
 *   <li><b>Verify</b> — opening the link and pressing Confirm marks the request VERIFIED. The
 *       church keeps full access, so it can export its data first.</li>
 *   <li><b>Delete</b> — a Service Admin reviews the dry-run counts and presses Delete now (within
 *       30 days of verification, the policy's response time). Every church-owned table is deleted by
 *       {@link TenantPurgePlanner#purgeChurch}, children before parents, each statement scoped to
 *       this church. Bank connections are revoked at Plaid first.</li>
 * </ol>
 *
 * <p>Kept: ChurchGenius LLC's own billing records for the church's subscription ({@link #KEEP}:
 * invoices, invoice lines, payment events, charges, plan changes and the {@code service_client}
 * account row, which is marked deleted) — tax records of the business. Database backups keep
 * older copies until they expire. Nothing here deletes automatically.
 */
@Service
public class ChurchDeletionService {

    private static final Logger log = LoggerFactory.getLogger(ChurchDeletionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int TOKEN_HOURS = 24;

    /** ChurchGenius LLC's own billing records for the church — kept as business tax records. */
    public static final Set<String> KEEP = Set.of(
            "billing_invoice", "billing_invoice_line", "billing_payment_event",
            "client_charge", "subscription_change", "service_client");

    public static final String REQUESTED = "REQUESTED", VERIFIED = "VERIFIED", DELETED = "DELETED",
            CANCELLED = "CANCELLED", EXPIRED = "EXPIRED";
    static final List<String> OPEN = List.of(REQUESTED, VERIFIED);

    private final ChurchDeletionRequestRepository requests;
    private final ChurchRegistrationRepository churches;
    private final LoginRepository logins;
    private final TenantPurgePlanner planner;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailService email;

    @Autowired(required = false)
    private com.churchgeniuspro.plaid.service.PlaidLinkService plaidLink;
    @Autowired(required = false)
    private com.churchgeniuspro.plaid.repository.PlaidItemRepository plaidItems;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl = "http://localhost:8080";

    public ChurchDeletionService(ChurchDeletionRequestRepository requests, ChurchRegistrationRepository churches,
                                 LoginRepository logins, TenantPurgePlanner planner, JdbcTemplate jdbc,
                                 TransactionTemplate tx, EmailService email) {
        this.requests = requests; this.churches = churches; this.logins = logins; this.planner = planner;
        this.jdbc = jdbc; this.tx = tx; this.email = email;
    }

    public record Outcome(boolean ok, int status, String code, String message, ChurchDeletionRequest request) {
        static Outcome fail(int status, String code, String message) { return new Outcome(false, status, code, message, null); }
        static Outcome ok(String message, ChurchDeletionRequest r) { return new Outcome(true, 200, null, message, r); }
    }

    // ── Church side ──────────────────────────────────────────────────────────

    /** The open request of the church (expired links are closed on the way). */
    public ChurchDeletionRequest current(String churchClientId) {
        ChurchDeletionRequest r = requests.findFirstByChurchClientIdAndStatusInOrderByIdDesc(churchClientId, OPEN).orElse(null);
        if (r != null && REQUESTED.equals(r.getStatus()) && r.getTokenExpiresAt() != null
                && r.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            r.setStatus(EXPIRED);
            r.setTokenHash(null);
            requests.save(r);
            return null;
        }
        return r;
    }

    public String churchName(String churchClientId) {
        return churches.findByClientIdAndDeleteFlagFalse(churchClientId).map(ChurchRegistration::getChurchName).orElse(null);
    }

    static String normName(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * @param loginId the session's own {@code signup.id} — the password is checked against it.
     */
    public Outcome request(String churchClientId, Integer loginId, String actor, String role,
                           String password, String typedName) {
        ChurchRegistration church = churches.findByClientIdAndDeleteFlagFalse(churchClientId).orElse(null);
        if (church == null) return Outcome.fail(404, "NOT_FOUND", "Church account not found.");
        SignUp login = loginId == null ? null : logins.findById(loginId)
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted())).orElse(null);
        if (login == null) return Outcome.fail(403, "SIGN_IN_AGAIN", "Please sign in again and retry.");
        if (password == null || password.isBlank() || !PasswordUtil.matches(password, login.getPassword())) {
            return Outcome.fail(403, "BAD_PASSWORD", "The password you entered is not correct.");
        }
        if (!normName(church.getChurchName()).equals(normName(typedName)) || normName(typedName).isEmpty()) {
            return Outcome.fail(400, "NAME_MISMATCH", "Type the church's name exactly as it is shown.");
        }
        ChurchDeletionRequest open = current(churchClientId);
        if (open != null) {
            return new Outcome(false, 409, "ALREADY_REQUESTED",
                    VERIFIED.equals(open.getStatus())
                            ? "Deletion of this church is already confirmed and waiting to be carried out."
                            : "A deletion request is already waiting for confirmation from the church's e-mail.", open);
        }
        String to = church.getEmail();
        if (to == null || to.isBlank()) {
            return Outcome.fail(409, "NO_EMAIL", "The church has no registered e-mail address. Contact support@churchgeniuspro.com.");
        }
        String token = newToken();
        ChurchDeletionRequest r = new ChurchDeletionRequest();
        r.setChurchClientId(churchClientId);
        r.setChurchName(church.getChurchName());
        r.setStatus(REQUESTED);
        r.setRequestedBy(actor);
        r.setRequestedRole(role);
        r.setNotifyEmail(to);
        r.setTokenHash(sha256(token));
        r.setTokenExpiresAt(LocalDateTime.now().plusHours(TOKEN_HOURS));
        r.setRequestedAt(LocalDateTime.now());
        requests.save(r);

        String link = baseUrl.replaceAll("/+$", "") + "/church-deletion/verify?token=" + token;
        String name = esc(church.getChurchName());
        send(to, "Confirm deletion of " + church.getChurchName() + " on ChurchGeniusPro",
                "<p>Someone signed in to <b>" + name + "</b> (" + esc(actor) + ") asked to delete the church's whole "
                        + "ChurchGeniusPro account.</p>"
                        + "<p>To confirm, open this link within " + TOKEN_HOURS + " hours and press <b>Confirm deletion</b>:<br>"
                        + "<a href=\"" + link + "\">" + link + "</a></p>"
                        + "<p>After you confirm, ChurchGeniusPro deletes every record of the church within 30 days: "
                        + "members, families, giving, events, messages, files and every login. You keep full access "
                        + "until then, so export anything you need first. ChurchGenius LLC keeps only its own invoices "
                        + "and payment records for your subscription.</p>"
                        + "<p>If you did not ask for this, ignore this e-mail — nothing happens without the confirmation — "
                        + "and change the password of the login named above.</p>", churchClientId);
        log.info("Church deletion requested: church={} request id={} by role {}", churchClientId, r.getId(), role);
        return Outcome.ok("We e-mailed a confirmation link to " + maskEmail(to) + ". The request goes ahead only after "
                + "that link is confirmed.", r);
    }

    public Outcome cancel(String churchClientId, String actor) {
        ChurchDeletionRequest r = current(churchClientId);
        if (r == null) return Outcome.fail(404, "NONE", "There is no open deletion request.");
        r.setStatus(CANCELLED);
        r.setTokenHash(null);
        r.setCancelledAt(LocalDateTime.now());
        r.setCancelledBy(actor);
        requests.save(r);
        send(r.getNotifyEmail(), "Church deletion request cancelled",
                "<p>The request to delete <b>" + esc(r.getChurchName()) + "</b> from ChurchGeniusPro was cancelled by "
                        + esc(actor) + ". Nothing was deleted.</p>", churchClientId);
        return Outcome.ok("The deletion request was cancelled. Nothing was deleted.", r);
    }

    /** The public link: one use, within its lifetime. */
    public Outcome verify(String token) {
        if (token == null || token.isBlank() || token.length() > 100) return Outcome.fail(400, "INVALID", "This link is not valid.");
        ChurchDeletionRequest r = requests.findByTokenHash(sha256(token)).orElse(null);
        if (r == null || !REQUESTED.equals(r.getStatus())) return Outcome.fail(404, "INVALID", "This link is not valid or was already used.");
        if (r.getTokenExpiresAt() == null || r.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            r.setStatus(EXPIRED);
            r.setTokenHash(null);
            requests.save(r);
            return Outcome.fail(410, "EXPIRED", "This link has expired. Sign in and request deletion again.");
        }
        r.setStatus(VERIFIED);
        r.setTokenHash(null);
        r.setVerifiedAt(LocalDateTime.now());
        requests.save(r);
        send(r.getNotifyEmail(), "Church deletion confirmed",
                "<p>Deletion of <b>" + esc(r.getChurchName()) + "</b> is confirmed. ChurchGeniusPro will delete the "
                        + "church's account and records within 30 days and e-mail you when it is done.</p>"
                        + "<p>You can still sign in and export your data until then. To stop the deletion, sign in and "
                        + "choose Cancel request on the Account &amp; Privacy page, or write to support@churchgeniuspro.com.</p>",
                r.getChurchClientId());
        log.info("Church deletion verified: church={} request id={}", r.getChurchClientId(), r.getId());
        return Outcome.ok("Deletion confirmed. The church's account will be deleted within 30 days; you will get an e-mail when it is done.", r);
    }

    // ── Service Admin side ───────────────────────────────────────────────────

    public List<ChurchDeletionRequest> list() {
        return requests.findTop200ByOrderByIdDesc();
    }

    public Map<String, Integer> dryRun(Long id) {
        ChurchDeletionRequest r = requests.findById(id).orElseThrow(() -> new IllegalArgumentException("Request not found."));
        return planner.countChurch(jdbc, r.getChurchClientId(), KEEP);
    }

    public Outcome adminCancel(Long id, String actor) {
        ChurchDeletionRequest r = requests.findById(id).orElse(null);
        if (r == null) return Outcome.fail(404, "NOT_FOUND", "Request not found.");
        if (!OPEN.contains(r.getStatus())) return Outcome.fail(409, "CLOSED", "This request is already " + r.getStatus().toLowerCase() + ".");
        return cancel(r.getChurchClientId(), actor);
    }

    /**
     * Deletes the church. Only a VERIFIED request, and only when the Service Admin typed the church's
     * client id. Bank connections are revoked at Plaid first (best effort), then ONE transaction removes
     * every church-owned row and marks the service_client account deleted.
     */
    public Outcome execute(Long id, String actor, String typedClientId) {
        ChurchDeletionRequest r = requests.findById(id).orElse(null);
        if (r == null) return Outcome.fail(404, "NOT_FOUND", "Request not found.");
        if (!VERIFIED.equals(r.getStatus())) {
            return Outcome.fail(409, "NOT_VERIFIED", "Only a request confirmed from the church's e-mail can be carried out.");
        }
        String cid = r.getChurchClientId();
        if (typedClientId == null || !cid.equals(typedClientId.trim())) {
            return Outcome.fail(400, "CONFIRM", "Type the church's client id to confirm.");
        }
        revokeBanks(cid, actor);
        Map<String, Integer> deleted = tx.execute(status -> {
            Map<String, Integer> d = planner.purgeChurch(jdbc, cid, KEEP);
            jdbc.update("UPDATE service_client SET delete_flag = true, status = 'DELETED' WHERE client_id = ?", cid);
            return d;
        });
        int total = deleted == null ? 0 : deleted.values().stream().mapToInt(Integer::intValue).sum();
        r.setStatus(DELETED);
        r.setCompletedAt(LocalDateTime.now());
        r.setCompletedBy(actor);
        r.setDeletedRows(total);
        requests.save(r);
        log.warn("AUDIT church-deletion | church='{}' clientId='{}' requestId={} performedBy='{}' at='{}' totalDeleted={} counts={}",
                r.getChurchName(), cid, r.getId(), actor, LocalDateTime.now(), total, deleted);
        send(r.getNotifyEmail(), "Your church's ChurchGeniusPro account has been deleted",
                "<p>The ChurchGeniusPro account of <b>" + esc(r.getChurchName()) + "</b> and its records have been deleted, "
                        + "as requested. Every login of the church has stopped working.</p>"
                        + "<p>ChurchGenius LLC keeps only its own invoices and payment records for your subscription, as "
                        + "tax records. Copies in database backups are removed as those backups expire. Stripe and Plaid "
                        + "keep their own records under their terms.</p>", null);
        return Outcome.ok("Deleted " + total + " records of " + r.getChurchName() + ".", r);
    }

    private void revokeBanks(String cid, String actor) {
        if (plaidLink == null || plaidItems == null) return;
        try {
            plaidItems.findByClientIdAndDeleteFlagFalse(cid).forEach(item -> {
                try { plaidLink.deleteItem(cid, item.getId(), actor); }
                catch (Exception e) { log.warn("Church deletion: bank revoke failed for one item of {}: {}", cid, e.getClass().getSimpleName()); }
            });
        } catch (Exception e) {
            log.warn("Church deletion: bank list failed for {}: {}", cid, e.getClass().getSimpleName());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void send(String to, String subject, String html, String clientId) {
        if (to == null || to.isBlank()) return;
        try { email.sendAccountEmail(to, subject, html, clientId); }
        catch (Exception e) { log.warn("Church deletion e-mail '{}' failed: {}", subject, e.getClass().getSimpleName()); }
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String maskEmail(String e) {
        if (e == null) return "";
        int at = e.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? e.substring(at) : "");
        return e.charAt(0) + "***" + e.substring(at);
    }
}
