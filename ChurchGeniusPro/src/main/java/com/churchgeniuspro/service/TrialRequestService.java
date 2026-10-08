package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.hibernate.TrialRequest;
import com.churchgeniuspro.repository.TrialRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The public Trial Request flow (Service Admin → Trial Requests).
 *
 * <pre>
 *   token-gated request page → submit → code emailed → requester verifies
 *     → Support Email notified, request listed for the Service Admin
 *     → Approve → an ordinary Trial Registration Link is issued and emailed
 *     → the existing /trialRegistration flow creates the trial account
 * </pre>
 *
 * <p><b>No second account-creation path.</b> Approval calls
 * {@link TrialRegistrationLinkService#generate} exactly as the Service Admin's
 * "Generate Link" button does, with no trial length of its own, so the link captures
 * the configured Trial duration ({@link TrialPolicy}) when it is issued and the
 * account is provisioned by the same code as every other trial.
 *
 * <p><b>Nothing is "official" until the email is verified.</b> A submission only
 * creates a {@code PENDING_VERIFICATION} row and emails a code to the address
 * entered; the Support Email hears about it, and an admin can approve it, only once
 * that code comes back — so a request cannot be lodged in someone else's name.
 *
 * <p><b>The page's link</b> is one opaque 256-bit token kept in
 * {@code platform_setting} ({@link PlatformSettingService#TRIAL_REQUEST_TOKEN}); the
 * Service Admin can regenerate it (old link dies at once) or revoke it.
 */
@Service
public class TrialRequestService {

    private static final Logger log = LoggerFactory.getLogger(TrialRequestService.class);

    /** VerificationStore type for these codes. */
    static final String OTP_TYPE = "TRIAL_REQUEST";
    /** Minimum gap between two codes for one request. */
    public static final int RESEND_COOLDOWN_SECONDS = 60;
    /** Codes a single request may be sent (bounds guessing across resends). */
    public static final int MAX_CODES = 5;

    private static final String REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
    private static final DateTimeFormatter DAY   = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final TrialRequestRepository repo;
    private final VerificationStore codes;
    private final EmailService email;
    private final PlatformSettingService settings;
    private final TrialPolicy trialPolicy;
    private final TrialRegistrationLinkService links;

    @Value("${app.base-url:}")
    private String baseUrl;

    public TrialRequestService(TrialRequestRepository repo, VerificationStore codes, EmailService email,
                               PlatformSettingService settings, TrialPolicy trialPolicy,
                               TrialRegistrationLinkService links) {
        this.repo = repo;
        this.codes = codes;
        this.email = email;
        this.settings = settings;
        this.trialPolicy = trialPolicy;
        this.links = links;
    }

    /** Test seam. */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    // ── Phase C: the tenant a request produced, and its account status ─────────
    // Optional so the existing constructions keep working; without them the account
    // actions still change the request but cannot reach the tenant (reported as such).

    private com.churchgeniuspro.repository.TrialRegistrationLinkRepository linkRepo;
    private com.churchgeniuspro.repository.ServiceClientRepository clientRepo;
    private AccountStatusService accountStatus;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLinkRepo(com.churchgeniuspro.repository.TrialRegistrationLinkRepository r) { this.linkRepo = r; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientRepo(com.churchgeniuspro.repository.ServiceClientRepository r) { this.clientRepo = r; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAccountStatus(AccountStatusService s) { this.accountStatus = s; }

    /** The tenant registered through this request's link, or null while nobody has registered. */
    public String tenantOf(TrialRequest r) {
        if (r == null || r.getTrialLinkId() == null || linkRepo == null) return null;
        return linkRepo.findById(r.getTrialLinkId())
                .map(com.churchgeniuspro.hibernate.TrialRegistrationLink::getUsedClientId)
                .filter(c -> c != null && !c.isBlank()).orElse(null);
    }

    /** The request a tenant was registered through, or empty for a tenant with none (e.g. a manual demo). */
    public java.util.Optional<TrialRequest> requestForTenant(String clientId) {
        if (clientId == null || clientId.isBlank() || linkRepo == null) return java.util.Optional.empty();
        return linkRepo.findFirstByUsedClientId(clientId)
                .flatMap(l -> repo.findFirstByTrialLinkId(l.getId()));
    }

    /**
     * Mirrors a request status onto its tenant's {@code service_client.status} so
     * every existing gate — sign-in (status must be Active) and the per-request
     * {@link AccountStatusService} check — refuses the tenant at once. Returns the
     * tenant id, or null when the request has no tenant yet.
     */
    private String setTenantStatus(TrialRequest r, String tenantStatus) {
        String clientId = tenantOf(r);
        if (clientId == null || clientRepo == null) return null;
        clientRepo.findByClientId(clientId).ifPresent(sc -> {
            sc.setStatus(tenantStatus);
            clientRepo.save(sc);
        });
        if (accountStatus != null) accountStatus.invalidateTenant(clientId);   // takes effect on the next request
        log.info("Trial request {}: tenant {} set to {}", r.getReference(), clientId, tenantStatus);
        return clientId;
    }

    public record AccountAction(TrialRequest request, String tenantClientId, String message) {}

    /** Switches the account off: request DISABLED, tenant status Disabled; every login refused. */
    public AccountAction disable(Long id, String actor) {
        TrialRequest r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Trial request not found."));
        String from = r.getStatus();
        if (TrialRequest.DISABLED.equals(from)) throw new IllegalArgumentException("This request is already disabled.");
        if (TrialRequest.DELETED.equals(from))  throw new IllegalArgumentException("This request has been deleted. Change it back to Pending first.");
        if (repo.transition(id, from, TrialRequest.DISABLED, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("This request was just changed by someone else. Refresh the list.");
        }
        r = repo.findById(id).orElse(r);
        String tenant = setTenantStatus(r, TrialRequest.TENANT_DISABLED);
        log.info("Trial request {} disabled by {}", r.getReference(), actor);
        return new AccountAction(r, tenant, tenant != null
                ? "Disabled. All logins for " + tenant + " are now refused."
                : "Disabled. (No account has been registered from this request yet.)");
    }

    /** Removes the request from the list (soft) and refuses its tenant. Reversible via Back to Pending. */
    public AccountAction delete(Long id, String actor) {
        TrialRequest r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Trial request not found."));
        String from = r.getStatus();
        if (TrialRequest.DELETED.equals(from)) throw new IllegalArgumentException("This request is already deleted.");
        if (repo.transition(id, from, TrialRequest.DELETED, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("This request was just changed by someone else. Refresh the list.");
        }
        r = repo.findById(id).orElse(r);
        codes.remove(r.getReference(), OTP_TYPE);
        String tenant = setTenantStatus(r, TrialRequest.TENANT_DELETED);
        log.info("Trial request {} deleted by {}", r.getReference(), actor);
        return new AccountAction(r, tenant, tenant != null
                ? "Deleted. All logins for " + tenant + " are now refused. The account's data is kept; "
                  + "use Demo/Trial Accounts to purge it."
                : "Deleted.");
    }

    /**
     * Returns the request to Pending Approval (VERIFIED). Its tenant, if any, is set to
     * Pending and stays refused until the request is approved again.
     */
    public AccountAction backToPending(Long id, String actor) {
        TrialRequest r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Trial request not found."));
        String from = r.getStatus();
        if (TrialRequest.VERIFIED.equals(from)) throw new IllegalArgumentException("This request is already pending approval.");
        if (TrialRequest.PENDING_VERIFICATION.equals(from) || TrialRequest.VERIFICATION_EXPIRED.equals(from)) {
            throw new IllegalArgumentException("This request's email has not been verified, so it cannot be set to pending approval.");
        }
        if (repo.transition(id, from, TrialRequest.VERIFIED, null, null) != 1) {
            throw new IllegalArgumentException("This request was just changed by someone else. Refresh the list.");
        }
        r = repo.findById(id).orElse(r);
        r.setStatus(TrialRequest.VERIFIED);
        r.setRejectReason(null);
        repo.save(r);
        String tenant = setTenantStatus(r, TrialRequest.TENANT_PENDING);
        log.info("Trial request {} set back to pending by {} (from {})", r.getReference(), actor, from);
        return new AccountAction(r, tenant, tenant != null
                ? "Back to Pending. All logins for " + tenant + " are refused until the request is approved again."
                : "Back to Pending.");
    }

    // ── The page's link ───────────────────────────────────────────────────

    /** True when {@code token} is the current request-page token (constant-time). */
    public boolean isLinkToken(String token) {
        String current = settings.get(PlatformSettingService.TRIAL_REQUEST_TOKEN).orElse(null);
        if (current == null || current.isBlank() || token == null || token.isBlank()) return false;
        return MessageDigest.isEqual(current.getBytes(StandardCharsets.UTF_8), token.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** The shareable URL of the request page, or null when no link is active. */
    public String linkUrl() {
        String t = settings.get(PlatformSettingService.TRIAL_REQUEST_TOKEN).orElse(null);
        return (t == null || t.isBlank()) ? null : base() + "/trialRequest.html?k=" + t;
    }

    /** Issues a new token; the previous link stops working immediately. */
    public String regenerateLink(String actor) {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        settings.set(PlatformSettingService.TRIAL_REQUEST_TOKEN, token, actor);
        log.info("Trial Request link (re)generated by {}", actor);
        return linkUrl();
    }

    /** Disables the request page until a new link is generated. */
    public void revokeLink(String actor) {
        settings.set(PlatformSettingService.TRIAL_REQUEST_TOKEN, null, actor);
        log.info("Trial Request link revoked by {}", actor);
    }

    // ── Submit ────────────────────────────────────────────────────────────

    public record Form(String firstName, String lastName, String churchName, String email,
                       String phone, String designation, String note) {}

    /** Field rules; null when the form is acceptable. Messages are for the requester. */
    public static String validate(Form f) {
        if (f == null) return "Please complete the form.";
        if (blank(f.firstName()))  return "First Name is required.";
        if (blank(f.lastName()))   return "Last Name is required.";
        if (blank(f.churchName())) return "Church Name is required.";
        if (blank(f.email()))      return "Email is required.";
        if (!f.email().trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || f.email().trim().length() > 320)
            return "Please enter a valid email address.";
        if (f.firstName().trim().length() > 100 || f.lastName().trim().length() > 100)
            return "Names must be 100 characters or fewer.";
        if (f.churchName().trim().length() > 200) return "Church Name must be 200 characters or fewer.";
        if (!blank(f.phone()) && f.phone().trim().length() > 40) return "Phone must be 40 characters or fewer.";
        if (!blank(f.designation()) && f.designation().trim().length() > 100) return "Designation must be 100 characters or fewer.";
        if (!blank(f.note()) && f.note().trim().length() > 1000) return "Note must be 1000 characters or fewer.";
        return null;
    }

    /**
     * Records the request as PENDING_VERIFICATION and emails the code. Throws
     * IllegalArgumentException for form problems and IllegalStateException when the
     * code could not be emailed (the requester is told; nothing is sent onward).
     */
    /** Shown when the code is entered (or resent) after the verification window closed. */
    public static final String EXPIRED_MESSAGE =
            "This request was not verified within " + TrialRequest.VERIFY_WINDOW_HOURS
          + " hours and has expired. Please submit the form again.";

    /** Statuses in which a request is still open and blocks a new one for the same email. */
    static final List<String> OPEN = List.of(TrialRequest.PENDING_VERIFICATION, TrialRequest.VERIFIED);

    /** Shown when the email already has a request awaiting the Service Admin's decision. */
    public static final String STILL_PENDING_MESSAGE =
            "Your previous trial request is still being reviewed. "
          + "You cannot submit another request until it has been completed.";
    /** Shown when the email already has a request awaiting its emailed code. */
    public static final String AWAITING_CODE_MESSAGE =
            "A trial request for this email address is waiting for email verification. "
          + "Enter the code we sent, or send a new code.";

    /**
     * A second request for an email that already has an open one. {@code reference}
     * is set only while that request is awaiting its code, so the page can resume
     * code entry (e.g. after a refresh) instead of creating another request.
     */
    public static class DuplicateRequestException extends RuntimeException {
        private final String reference;
        public DuplicateRequestException(String message, String reference) {
            super(message);
            this.reference = reference;
        }
        public String reference() { return reference; }
    }

    /** Applies the 24-hour verification window to every unverified request. */
    void expireStale() {
        try {
            int n = repo.expireUnverified(LocalDateTime.now().minusHours(TrialRequest.VERIFY_WINDOW_HOURS));
            if (n > 0) log.info("Trial requests: {} unverified request(s) passed the {}-hour window -> VERIFICATION_EXPIRED",
                    n, TrialRequest.VERIFY_WINDOW_HOURS);
        } catch (Exception e) {
            log.warn("Trial requests: could not expire stale unverified requests — {}", e.toString());
        }
    }

    private static boolean pastWindow(TrialRequest r) {
        return r.getCreatedAt() != null
            && r.getCreatedAt().isBefore(LocalDateTime.now().minusHours(TrialRequest.VERIFY_WINDOW_HOURS));
    }

    private DuplicateRequestException duplicateOf(TrialRequest open) {
        return TrialRequest.VERIFIED.equals(open.getStatus())
                ? new DuplicateRequestException(STILL_PENDING_MESSAGE, null)
                : new DuplicateRequestException(AWAITING_CODE_MESSAGE, open.getReference());
    }

    /** Refuses a request when the email already has an open one (server-side; also enforced by a unique index). */
    private void refuseIfOpen(String email) {
        List<TrialRequest> open = repo.findByEmailAndStatusInOrderByCreatedAtDesc(email, OPEN);
        if (!open.isEmpty()) throw duplicateOf(open.get(0));
    }

    public TrialRequest submit(Form f, String ip) {
        String invalid = validate(f);
        if (invalid != null) throw new IllegalArgumentException(invalid);
        String normalisedEmail = f.email().trim().toLowerCase();
        expireStale();
        refuseIfOpen(normalisedEmail);
        TrialRequest r = new TrialRequest();
        r.setReference(newReference());
        r.setFirstName(f.firstName().trim());
        r.setLastName(f.lastName().trim());
        r.setChurchName(f.churchName().trim());
        r.setEmail(f.email().trim().toLowerCase());
        r.setPhone(trimOrNull(f.phone()));
        r.setDesignation(trimOrNull(f.designation()));
        r.setNote(trimOrNull(f.note()));
        r.setRequestIp(ip);
        r.setStatus(TrialRequest.PENDING_VERIFICATION);
        r.setCodesSent(0);
        r.setSupportEmailSent(false);
        try {
            repo.save(r);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // A simultaneous submit (double click, second tab) won the unique index
            // on the open email; answer exactly as the check above would have.
            refuseIfOpen(normalisedEmail);
            throw new DuplicateRequestException(STILL_PENDING_MESSAGE, null);
        }
        try {
            sendCode(r);
        } catch (IllegalStateException mailFailed) {
            // Nothing reached the requester, so this request must not block the address.
            r.setStatus(TrialRequest.VERIFICATION_EXPIRED);
            repo.save(r);
            throw mailFailed;
        }
        return r;
    }

    /** Sends a fresh code (cooldown and cap enforced). */
    public void resend(String reference) {
        TrialRequest r = repo.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("We could not find that request. Please submit the form again."));
        if (TrialRequest.VERIFICATION_EXPIRED.equals(r.getStatus())
                || (TrialRequest.PENDING_VERIFICATION.equals(r.getStatus()) && pastWindow(r))) {
            throw new IllegalArgumentException(EXPIRED_MESSAGE);
        }
        if (!TrialRequest.PENDING_VERIFICATION.equals(r.getStatus())) {
            throw new IllegalArgumentException("This request has already been verified.");
        }
        int sent = r.getCodesSent() == null ? 0 : r.getCodesSent();
        if (sent >= MAX_CODES) {
            throw new IllegalArgumentException("Too many codes have been sent for this request. Please submit the form again.");
        }
        if (r.getCodeSentAt() != null && r.getCodeSentAt().plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Please wait a minute before requesting another code.");
        }
        sendCode(r);
    }

    private void sendCode(TrialRequest r) {
        String code = codes.generateAndStore(r.getReference(), OTP_TYPE, r.getEmail());
        r.setCodeSentAt(LocalDateTime.now());
        r.setCodesSent((r.getCodesSent() == null ? 0 : r.getCodesSent()) + 1);
        repo.save(r);
        try {
            email.sendAccountEmailOrThrow(r.getEmail(), "Your ChurchGeniusPro verification code: " + code,
                    verificationEmailHtml(r, code), null);
        } catch (Exception e) {
            log.warn("Trial request {}: verification email to {} failed — {}", r.getReference(), r.getEmail(), e.toString());
            throw new IllegalStateException("We could not send the verification email to " + r.getEmail()
                    + ". Please check the address and try again.");
        }
    }

    // ── Verify ────────────────────────────────────────────────────────────

    public record Verified(TrialRequest request, boolean supportEmailSent) {}

    /**
     * Confirms the emailed code. On success the request becomes VERIFIED — now an
     * official Trial Request — and the Support Email is notified. Re-verifying an
     * already verified request is a harmless success.
     */
    public Verified verify(String reference, String code) {
        TrialRequest r = repo.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new IllegalArgumentException("We could not find that request. Please submit the form again."));
        if (TrialRequest.VERIFICATION_EXPIRED.equals(r.getStatus())
                || (TrialRequest.PENDING_VERIFICATION.equals(r.getStatus()) && pastWindow(r))) {
            if (TrialRequest.PENDING_VERIFICATION.equals(r.getStatus())) {
                r.setStatus(TrialRequest.VERIFICATION_EXPIRED);
                repo.save(r);
            }
            throw new IllegalArgumentException(EXPIRED_MESSAGE);
        }
        if (!TrialRequest.PENDING_VERIFICATION.equals(r.getStatus())) {
            return new Verified(r, Boolean.TRUE.equals(r.getSupportEmailSent()));
        }
        String c = code == null ? "" : code.replaceAll("\\s", "");
        if (!codes.validate(r.getReference(), OTP_TYPE, c)) {
            throw new IllegalArgumentException("That code is not correct or has expired. "
                    + "Check the latest email from us, or request a new code.");
        }
        codes.remove(r.getReference(), OTP_TYPE);
        r.setStatus(TrialRequest.VERIFIED);
        r.setVerifiedAt(LocalDateTime.now());
        repo.save(r);

        boolean sent = false;
        String to = settings.supportEmail();
        try {
            email.sendComposed(List.of(to), null, "New Trial Request " + r.getReference() + " — " + r.getChurchName(),
                    supportEmailHtml(r), null, "ChurchGeniusPro Trial Requests");
            sent = true;
        } catch (Exception e) {
            // The request is verified and listed for the Service Admin either way;
            // a mail outage must not undo the requester's verification.
            log.error("Trial request {}: Support Email notification to {} failed — {}", r.getReference(), to, e.toString());
        }
        r.setSupportEmailSent(sent);
        repo.save(r);
        log.info("Trial request {} verified ({} / {}); support notified={}", r.getReference(), r.getChurchName(), r.getEmail(), sent);
        return new Verified(r, sent);
    }

    // ── Service Admin decisions ───────────────────────────────────────────

    public record Approval(TrialRequest request, String registrationUrl, int trialDays,
                           boolean emailSent, String emailError) {}

    /**
     * Approves a VERIFIED request: issues an ordinary Trial Registration Link for the
     * verified address and emails it. The status is claimed atomically first, so a
     * double click or two admins cannot issue two links.
     */
    public Approval approve(Long id, String actor) {
        TrialRequest r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Trial request not found."));
        if (!TrialRequest.VERIFIED.equals(r.getStatus())) {
            throw new IllegalArgumentException(TrialRequest.PENDING_VERIFICATION.equals(r.getStatus())
                    ? "This request has not been verified yet — the requester must confirm their email first."
                    : "This request has already been " + r.getStatus().toLowerCase() + ".");
        }
        if (repo.transition(id, TrialRequest.VERIFIED, TrialRequest.APPROVED, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("This request was just decided by someone else. Refresh the list.");
        }
        // Phase C: a request whose link was already used has a tenant. Re-approving it
        // restores that account; no second link, no second registration.
        if (tenantOf(r) != null) {
            r = repo.findById(id).orElse(r);
            r.setStatus(TrialRequest.APPROVED);
            repo.save(r);
            String tenant = setTenantStatus(r, "Active");
            log.info("Trial request {} re-approved by {} — tenant {} active again", r.getReference(), actor, tenant);
            return new Approval(r, null, trialDays(), true, null);
        }
        TrialRegistrationLink link;
        try {
            // Exactly what "Generate Link" does: default validity, and NO trial length
            // of its own — the link captures the configured Trial duration now.
            link = links.generate(r.fullName(), r.getEmail(),
                    truncate("Trial Request " + r.getReference() + " · " + r.getChurchName(), 500),
                    null, null, actor);
        } catch (RuntimeException e) {
            repo.transition(id, TrialRequest.APPROVED, TrialRequest.VERIFIED, null, null);   // give it back
            throw e;
        }
        r = repo.findById(id).orElse(r);
        r.setStatus(TrialRequest.APPROVED);
        r.setTrialLinkId(link.getId());
        String url = links.urlFor(link.getToken());
        int days = TrialRegistrationLinkService.trialDaysFor(link);

        boolean sent = false;
        String err = null;
        try {
            email.sendAccountEmailOrThrow(r.getEmail(), "Your ChurchGeniusPro free trial is approved",
                    approvalEmailHtml(r, url, days, link.getExpiresAt()), null);
            sent = true;
        } catch (Exception e) {
            err = "The trial was approved and the registration link created, but the email to "
                + r.getEmail() + " could not be sent. Copy the link and send it to them directly.";
            log.error("Trial request {}: approval email to {} failed — {}", r.getReference(), r.getEmail(), e.toString());
        }
        r.setApprovalEmailSent(sent);
        repo.save(r);
        log.info("Trial request {} approved by {} — link #{} ({} days), email sent={}", r.getReference(), actor, link.getId(), days, sent);
        return new Approval(r, url, days, sent, err);
    }

    /** Rejects a request that is awaiting verification or approval. No email is sent. */
    public TrialRequest reject(Long id, String actor, String reason) {
        TrialRequest r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Trial request not found."));
        String from = r.getStatus();
        if (!TrialRequest.VERIFIED.equals(from) && !TrialRequest.PENDING_VERIFICATION.equals(from)) {
            throw new IllegalArgumentException("This request has already been " + from.toLowerCase() + ".");
        }
        if (repo.transition(id, from, TrialRequest.REJECTED, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("This request was just decided by someone else. Refresh the list.");
        }
        r = repo.findById(id).orElse(r);
        r.setStatus(TrialRequest.REJECTED);
        r.setRejectReason(truncate(trimOrNull(reason), 500));
        repo.save(r);
        codes.remove(r.getReference(), OTP_TYPE);
        log.info("Trial request {} rejected by {}", r.getReference(), actor);
        return r;
    }

    public List<Map<String, Object>> listForAdmin() {
        expireStale();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TrialRequest r : repo.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("reference", r.getReference());
            m.put("firstName", r.getFirstName());
            m.put("lastName", r.getLastName());
            m.put("churchName", r.getChurchName());
            m.put("email", r.getEmail());
            m.put("phone", r.getPhone());
            m.put("designation", r.getDesignation());
            m.put("note", r.getNote());
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            m.put("verifiedAt", r.getVerifiedAt() != null ? r.getVerifiedAt().toString() : null);
            m.put("supportEmailSent", r.getSupportEmailSent());
            m.put("decidedAt", r.getDecidedAt() != null ? r.getDecidedAt().toString() : null);
            m.put("decidedBy", r.getDecidedBy());
            m.put("rejectReason", r.getRejectReason());
            m.put("trialLinkId", r.getTrialLinkId());
            m.put("approvalEmailSent", r.getApprovalEmailSent());
            String tenant = tenantOf(r);
            m.put("tenantClientId", tenant);
            m.put("tenantStatus", tenant != null && clientRepo != null
                    ? clientRepo.findByClientId(tenant).map(com.churchgeniuspro.hibernate.ServiceClient::getStatus).orElse(null) : null);
            out.add(m);
        }
        return out;
    }

    // ── Emails ────────────────────────────────────────────────────────────

    String verificationEmailHtml(TrialRequest r, String code) {
        return "<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:15px;color:#2b2b2b;line-height:1.6;\">"
             + "<p>Hello " + esc(r.getFirstName()) + ",</p>"
             + "<p>Thank you for requesting a free trial of ChurchGeniusPro for <strong>" + esc(r.getChurchName()) + "</strong>. "
             + "Please confirm your email address by entering this code on the request page:</p>"
             + "<p style=\"font-size:28px;font-weight:700;letter-spacing:6px;color:#673147;margin:18px 0;\">" + esc(code) + "</p>"
             + "<p>The code is valid for 10 minutes. Your request reference is <strong>" + esc(r.getReference()) + "</strong>.</p>"
             + "<p style=\"color:#777;font-size:13px;\">If you did not request a ChurchGeniusPro trial, you can ignore this email — "
             + "nothing will happen unless the code is entered.</p></div>";
    }

    String supportEmailHtml(TrialRequest r) {
        String[][] rows = {
            {"Reference", r.getReference()},
            {"First Name", r.getFirstName()},
            {"Last Name", r.getLastName()},
            {"Church Name", r.getChurchName()},
            {"Email", r.getEmail()},
            {"Phone", or(r.getPhone())},
            {"Designation", or(r.getDesignation())},
            {"Note", or(r.getNote())},
            {"Request Date/Time", r.getCreatedAt() != null ? r.getCreatedAt().format(STAMP) : "—"},
            {"Verification Status", "Email verified" + (r.getVerifiedAt() != null ? " — " + r.getVerifiedAt().format(STAMP) : "")},
        };
        StringBuilder sb = new StringBuilder("<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#2b2b2b;\">"
                + "<p>A new, email-verified Trial Request has been submitted. Review it under "
                + "<strong>Service Admin → Trial Requests</strong> to approve or reject it.</p>"
                + "<table cellpadding=\"7\" style=\"border-collapse:collapse;\">");
        for (String[] row : rows) {
            sb.append("<tr><td style=\"border:1px solid #e3e3e8;background:#f7f7fa;font-weight:600;white-space:nowrap;vertical-align:top;\">")
              .append(esc(row[0])).append("</td><td style=\"border:1px solid #e3e3e8;white-space:pre-wrap;\">")
              .append(esc(row[1])).append("</td></tr>");
        }
        return sb.append("</table></div>").toString();
    }

    String approvalEmailHtml(TrialRequest r, String url, int days, LocalDateTime linkExpires) {
        return "<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:15px;color:#2b2b2b;line-height:1.6;\">"
             + "<p>Hello " + esc(r.getFirstName()) + ",</p>"
             + "<p>Good news — your request for a ChurchGeniusPro free trial for <strong>" + esc(r.getChurchName())
             + "</strong> has been approved.</p>"
             + "<p>Your <strong>" + days + "-day free trial</strong> includes every Pro feature, including AI. "
             + "No credit card and no payment are required, and you can cancel anytime.</p>"
             + "<p style=\"margin:22px 0;\"><a href=\"" + esc(url) + "\" style=\"background:#673147;color:#fff;"
             + "padding:12px 26px;border-radius:8px;text-decoration:none;font-weight:600;\">Create my trial account</a></p>"
             + "<p style=\"font-size:13px;color:#555;\">Or copy this link into your browser:<br/>" + esc(url) + "</p>"
             + (linkExpires != null ? "<p style=\"font-size:13px;color:#555;\">This link can be used once and expires on "
             + linkExpires.format(DAY) + ".</p>" : "")
             + "<p>We look forward to serving your church.<br/>— The ChurchGeniusPro team</p></div>";
    }

    // ── helpers ───────────────────────────────────────────────────────────

    String newReference() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("TRQ-");
            for (int i = 0; i < 8; i++) sb.append(REF_ALPHABET.charAt(RNG.nextInt(REF_ALPHABET.length())));
            if (!repo.existsByReference(sb.toString())) return sb.toString();
        }
        throw new IllegalStateException("Could not allocate a request reference.");
    }

    /** The configured Trial duration, for the request page. */
    public int trialDays() { return trialPolicy.trialDays(); }

    private String base() { return baseUrl == null ? "" : baseUrl.replaceAll("/+$", ""); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String trimOrNull(String s) { return blank(s) ? null : s.trim(); }
    private static String or(String s) { return blank(s) ? "—" : s; }
    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
