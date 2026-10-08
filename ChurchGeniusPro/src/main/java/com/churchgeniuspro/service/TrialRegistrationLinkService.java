package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues and validates the invitation links that gate the Trial Registration form.
 *
 * <p>One place decides whether a token opens the form, and both the page filter
 * and the registration POST ask it — so the form cannot be reached by URL, and
 * the API behind it cannot be reached by skipping the form.
 */
@Service
public class TrialRegistrationLinkService {

    private static final Logger log = LoggerFactory.getLogger(TrialRegistrationLinkService.class);

    /** How long a link works unless the admin says otherwise. */
    public static final int DEFAULT_VALID_DAYS = 7;
    private static final int MAX_VALID_DAYS = 90;

    /** Trial lengths the Service Admin screen offers when issuing a link. */
    public static final java.util.List<Integer> TRIAL_DAY_OPTIONS = java.util.List.of(30, 60, 90, 120, 180);
    /** Upper bound for a trial length arriving from any caller. */
    public static final int MAX_TRIAL_DAYS = 365;

    /**
     * The trial length a link grants: the value captured on it when it was issued.
     * Every link issued since trial length became configurable carries one (see
     * {@link #generate}); a changed plan setting never alters an existing link.
     * Null-safe for rows issued before the column existed, which keep granting the
     * 30 days they always did.
     */
    public static int trialDaysFor(TrialRegistrationLink link) {
        Integer d = link == null ? null : link.getTrialDays();
        return (d == null || d < 1 || d > MAX_TRIAL_DAYS) ? TrialPolicy.FALLBACK_DAYS : d;
    }

    /** Why a token was refused. The visitor is told something different for each. */
    public enum Outcome { VALID, UNKNOWN, EXPIRED, USED, REVOKED, MISSING }

    public record Validation(Outcome outcome, TrialRegistrationLink link) {
        public boolean valid() { return outcome == Outcome.VALID; }

        /** Wording shown to whoever opened the link. */
        public String message() {
            return switch (outcome) {
                case VALID   -> null;
                case EXPIRED -> "This Trial Registration link has expired. "
                              + "Please contact the administrator for a new registration link.";
                case USED    -> "This Trial Registration link has already been used to create an account. "
                              + "Please contact the administrator if you need another one.";
                case REVOKED -> "This Trial Registration link is no longer valid. "
                              + "Please contact the administrator for a new registration link.";
                // UNKNOWN and MISSING are answered identically on purpose: telling a
                // prober which of the two happened turns the page into an oracle for
                // guessing valid tokens.
                default      -> "This Trial Registration link is not valid. "
                              + "Please contact the administrator for a registration link.";
            };
        }
    }

    private final TrialRegistrationLinkRepository repo;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.base-url}")
    private String baseUrl;


    /**
     * Configured trial length (Service Admin → Subscription Plans → Trial plan).
     * Setter-injected so the existing constructor — and every test built on it —
     * keeps working; without a policy the historical {@value TrialPolicy#FALLBACK_DAYS}
     * days apply.
     */
    private TrialPolicy trialPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTrialPolicy(TrialPolicy p) { this.trialPolicy = p; }
    private int defaultTrialDays() { return trialPolicy != null ? trialPolicy.trialDays() : TrialPolicy.FALLBACK_DAYS; }

    public TrialRegistrationLinkService(TrialRegistrationLinkRepository repo) {
        this.repo = repo;
    }

    /* ── issue ──────────────────────────────────────────────────────────── */

    /**
     * Creates a link for one prospect, revoking any still-open link they already
     * hold so only the newest URL works.
     *
     * @param validDays null or out of range falls back to {@link #DEFAULT_VALID_DAYS}
     */
    @Transactional
    public TrialRegistrationLink generate(String prospectName, String prospectEmail,
                                          String note, Integer validDays, String actor) {
        return generate(prospectName, prospectEmail, note, validDays, null, actor);
    }

    /**
     * As {@link #generate(String, String, String, Integer, String)}, also choosing how
     * long the resulting trial will run.
     *
     * @param trialDays an explicit per-link override; null or out of range
     *                  (1–{@value #MAX_TRIAL_DAYS}) captures the configured default
     *                  from {@link TrialPolicy} at issue time
     */
    @Transactional
    public TrialRegistrationLink generate(String prospectName, String prospectEmail,
                                          String note, Integer validDays, Integer trialDays,
                                          String actor) {
        int days = (validDays == null || validDays < 1 || validDays > MAX_VALID_DAYS)
                ? DEFAULT_VALID_DAYS : validDays;

        // Replacing a link must retire the old one immediately. Without this the
        // previous URL keeps working for the rest of its week, which is exactly
        // what issuing a replacement is meant to stop.
        int revoked = 0;
        if (prospectEmail != null && !prospectEmail.isBlank()) {
            for (TrialRegistrationLink prior :
                    repo.findByProspectEmailIgnoreCaseAndUsedAtIsNullAndRevokedFalse(prospectEmail.trim())) {
                prior.setRevoked(true);
                prior.setRevokedAt(LocalDateTime.now());
                repo.save(prior);
                revoked++;
            }
        }

        TrialRegistrationLink link = new TrialRegistrationLink();
        link.setToken(newToken());
        link.setProspectName(trimToNull(prospectName));
        link.setProspectEmail(trimToNull(prospectEmail));
        link.setNote(trimToNull(note));
        link.setCreatedBy(actor);
        link.setCreatedAt(LocalDateTime.now());
        link.setExpiresAt(LocalDateTime.now().plusDays(days));
        link.setTrialDays((trialDays == null || trialDays < 1 || trialDays > MAX_TRIAL_DAYS)
                ? defaultTrialDays() : trialDays);
        link.setRevoked(false);
        TrialRegistrationLink saved = repo.save(link);

        log.info("Trial registration link issued by {} for '{}' — expires {} ({} prior link(s) revoked)",
                 actor, saved.getProspectEmail(), saved.getExpiresAt(), revoked);
        return saved;
    }

    /** 32 random bytes, URL-safe base64, unpadded. Not sequential, not guessable. */
    private String newToken() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /* ── validate ───────────────────────────────────────────────────────── */

    /** Whether this token may open the form right now, and why not if it may not. */
    public Validation validate(String token) {
        if (token == null || token.isBlank()) return new Validation(Outcome.MISSING, null);
        Optional<TrialRegistrationLink> found = repo.findByToken(token.trim());
        if (found.isEmpty()) return new Validation(Outcome.UNKNOWN, null);

        TrialRegistrationLink link = found.get();
        return switch (link.getStatus()) {
            case "USED"    -> new Validation(Outcome.USED, link);
            // A soft-deleted link is out of use. Answered as REVOKED rather than
            // with a category of its own: the visitor holding it needs to know it
            // no longer works and to ask for another, which is exactly what the
            // revoked wording says. What the Service Admin did with their own list
            // is not the prospect's business.
            case "DELETED" -> new Validation(Outcome.REVOKED, link);
            case "REVOKED" -> new Validation(Outcome.REVOKED, link);
            case "EXPIRED" -> new Validation(Outcome.EXPIRED, link);
            default        -> new Validation(Outcome.VALID, link);
        };
    }

    /* ── claim / complete / release ─────────────────────────────────────── */

    /** Written into {@code used_client_id} while a claim is in flight. */
    static final String PENDING_PREFIX = "PENDING:";

    /**
     * A link taken for one registration attempt.
     *
     * <p>{@code marker} is the claim's own handle: {@link #complete} and
     * {@link #release} act only on a row that still carries it, so two attempts
     * can never complete or undo each other's claim.
     */
    public record Claim(Validation validation, String marker) {
        public boolean valid() { return validation.valid() && marker != null; }
        public TrialRegistrationLink link() { return validation.link(); }
    }

    /**
     * Takes the link for one registration attempt, atomically.
     *
     * <p>The old sequence — provision the tenant, then mark the link used — let
     * every concurrent POST carrying the same token pass validation and each
     * create a tenant, because nothing between "is it usable?" and "mark it used"
     * was exclusive. The claim is now a single conditional UPDATE
     * ({@link TrialRegistrationLinkRepository#claim}) whose WHERE clause is the
     * usability rule itself, so exactly one caller wins and the rest are told the
     * link is already used. It runs BEFORE provisioning; a failed provisioning
     * hands the link back with {@link #release}, so a prospect whose registration
     * hit an error is not left with a spent link.
     *
     * @return a valid claim, or one whose validation says why the token was refused
     */
    @Transactional
    public Claim claim(String token) {
        Validation v = validate(token);
        if (!v.valid()) return new Claim(v, null);

        String marker = PENDING_PREFIX + java.util.UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        int rows = repo.claim(token.trim(), now, marker);
        if (rows != 1) {
            // Lost the race, or expired/revoked in the instant since validate().
            // Re-read so the visitor gets the precise reason; a row that is
            // somehow still "usable" here is answered as USED, never as valid.
            Validation again = validate(token);
            Outcome why = again.valid() ? Outcome.USED : again.outcome();
            log.warn("Trial registration link claim refused — prospect='{}' outcome={}",
                     v.link().getProspectEmail(), why);
            return new Claim(new Validation(why, again.link()), null);
        }
        TrialRegistrationLink link = v.link();
        link.setUsedAt(now);            // keep the in-memory copy in step with the row
        link.setUsedClientId(marker);
        return new Claim(v, marker);
    }

    /** Records the tenant a claim produced. */
    @Transactional
    public void complete(Claim claim, String clientId) {
        if (claim == null || !claim.valid()) return;
        String token = claim.link().getToken();
        int rows = repo.complete(token, claim.marker(), clientId);
        if (rows == 1) {
            claim.link().setUsedClientId(clientId);
            log.info("Trial registration link consumed — prospect='{}' clientId={}",
                     claim.link().getProspectEmail(), clientId);
        } else {
            // The row no longer carries our marker — it was released or re-claimed
            // underneath us. The tenant exists regardless; say so loudly.
            log.error("Trial registration link for prospect='{}' could not be completed with clientId={} "
                    + "(marker no longer present)", claim.link().getProspectEmail(), clientId);
        }
    }

    /** Hands a claimed link back after a failed registration, so it can be tried again. */
    @Transactional
    public void release(Claim claim) {
        if (claim == null || !claim.valid()) return;
        String token = claim.link().getToken();
        int rows = repo.release(token, claim.marker());
        if (rows == 1) {
            claim.link().setUsedAt(null);
            claim.link().setUsedClientId(null);
            log.info("Trial registration link released after a failed registration — prospect='{}'",
                     claim.link().getProspectEmail());
        }
    }

    /**
     * Claims and completes in one step — for callers that already hold the tenant.
     *
     * <p>Kept for the admin tooling and tests; the registration endpoint uses
     * {@link #claim} / {@link #complete} / {@link #release} so the claim happens
     * before, not after, the tenant is provisioned.
     *
     * @return the validation as it stood at consumption time
     */
    @Transactional
    public Validation consume(String token, String clientId) {
        Claim c = claim(token);
        if (!c.valid()) return c.validation();
        complete(c, clientId);
        return c.validation();
    }

    /* ── admin ──────────────────────────────────────────────────────────── */

    @Transactional
    public TrialRegistrationLink revoke(Integer id, String actor) {
        TrialRegistrationLink link = repo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No such registration link: " + id));
        link.setRevoked(true);
        link.setRevokedAt(LocalDateTime.now());
        log.info("Trial registration link {} revoked by {}", id, actor);
        return repo.save(link);
    }

    /** Every link, newest first, as the Service Admin table renders them. */
    public List<Map<String, Object>> listForAdmin() {
        return listForAdmin(false);
    }

    /**
     * The admin list, either the live rows or the soft-deleted ones.
     *
     * @param deleted false for the normal list, true for what "Show deleted" reveals
     */
    public List<Map<String, Object>> listForAdmin(boolean deleted) {
        List<TrialRegistrationLink> rows = deleted
                ? repo.findByDeletedAtIsNotNullOrderByIdDesc()
                : repo.findByDeletedAtIsNullOrderByIdDesc();
        return rows.stream().map(this::toRow).toList();
    }

    public Map<String, Object> toRow(TrialRegistrationLink link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            link.getId());
        m.put("prospectName",  link.getProspectName());
        m.put("prospectEmail", link.getProspectEmail());
        m.put("note",          link.getNote());
        m.put("deleted",       link.isDeleted());
        m.put("deletedAt",     link.getDeletedAt() == null ? null : link.getDeletedAt().toString());
        m.put("deletedBy",     link.getDeletedBy());
        m.put("status",        link.getStatus());
        m.put("createdBy",     link.getCreatedBy());
        m.put("createdAt",     str(link.getCreatedAt()));
        m.put("expiresAt",     str(link.getExpiresAt()));
        m.put("usedAt",        str(link.getUsedAt()));
        m.put("usedClientId",  link.getUsedClientId());
        m.put("trialDays",     trialDaysFor(link));
        // Only a link that still works is worth handing back — showing the URL of a
        // spent or revoked one just invites someone to send it.
        m.put("url",           link.isUsable() ? urlFor(link.getToken()) : null);
        return m;
    }

    public String urlFor(String token) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return base + "/trialRegistration.html?id=" + token;
    }

    private static String str(LocalDateTime t) { return t == null ? null : t.toString(); }

    private static String trimToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
