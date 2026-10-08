package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.EmailSettings;
import com.churchgeniuspro.hibernate.PromiseVerse;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.EmailSettingsRepository;
import com.churchgeniuspro.repository.PromiseVerseRepository;
import com.churchgeniuspro.util.EmailMask;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends transactional emails via the configured Titan SMTP server.
 *
 * <p>Two public entry points:
 * <ul>
 *   <li>{@link #sendGenericEmail} — system-level emails (OTP, password reset, …)
 *       with no org-specific branding.</li>
 *   <li>{@link #sendOrgEmail} — org-branded emails; applies the display name,
 *       logo, daily verse, footer comments, and signature from EmailSettings.</li>
 * </ul>
 *
 * <p>All SMTP credentials and the {@code app.base-url} are injected from
 * {@code application.properties} so no values are hard-coded in this class.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private static final String DEFAULT_FROM_NAME = "Church Genius Pro";

    private final JavaMailSender                 mailSender;
    private final EmailSettingsRepository        emailSettingsRepository;
    private final ChurchLogoRepository           churchLogoRepository;
    private final PromiseVerseRepository         promiseVerseRepository;
    private final UnsubscribeService             unsubscribeService;
    private final ChurchRegistrationRepository   churchRegistrationRepository;
    private final SubscriptionService            subscriptionService;

    /** Public base URL of the application — used to build links in emails. */
    @Value("${app.base-url}")
    private String baseUrl;

    public EmailService(JavaMailSender mailSender,
                        EmailSettingsRepository emailSettingsRepository,
                        ChurchLogoRepository churchLogoRepository,
                        PromiseVerseRepository promiseVerseRepository,
                        UnsubscribeService unsubscribeService,
                        ChurchRegistrationRepository churchRegistrationRepository,
                        SubscriptionService subscriptionService) {
        this.mailSender                    = mailSender;
        this.emailSettingsRepository       = emailSettingsRepository;
        this.churchLogoRepository          = churchLogoRepository;
        this.promiseVerseRepository        = promiseVerseRepository;
        this.unsubscribeService            = unsubscribeService;
        this.churchRegistrationRepository  = churchRegistrationRepository;
        this.subscriptionService           = subscriptionService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends a signup invitation email to the church registrant.
     *
     * <p>The email contains a personalised greeting and a button linking to
     * {@code /signup?clientId=<clientId>} so the recipient can create their
     * login credentials without having to enter the token manually.
     *
     * <p>Any mail-sending failure is caught and logged rather than propagated,
     * so a transient SMTP error never rolls back the registration record.
     *
     * @param toEmail   recipient e-mail address (from the registration form)
     * @param firstName registrant's first name — used in the greeting line
     * @param clientId  UUID token generated during registration
     */
    /**
     * Demo-tenant send guard. Injected by field rather than constructor so this
     * service's many construction sites stay untouched; null-checked at use.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DemoAccessService demoAccess;

    /**
     * The one authority on whether a tenant may send at all (Trial subscription,
     * demo tenant). Field-injected for the same reason as {@code demoAccess}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MessagingPolicy messagingPolicy;

    /** Test seam — supply the policy without a Spring context. */
    public void setMessagingPolicy(MessagingPolicy p) { this.messagingPolicy = p; }

    /**
     * Why congregation mail for {@code clientId} would be dropped, or {@code null}
     * when it would be delivered. The same answer {@link #doSend} acts on, exposed so
     * a bulk sender can report "blocked" instead of counting silently dropped
     * messages as sent. Account mail is never affected by this.
     */
    public String emailBlockReason(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        if (messagingPolicy != null) return messagingPolicy.emailBlockReason(clientId);
        return (demoAccess != null && !demoAccess.sendingAllowed(clientId, false))
                ? MessagingPolicy.DEMO_EMAIL_MSG : null;
    }

    /**
     * Phase B: the verified Trial/Demo test address a blocked tenant's congregation
     * mail is redirected to. Optional (unit tests) and lazy (it sends its code
     * email through this service).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private TrialTestEmailService trialTestEmails;

    /** Test seam. */
    public void setTrialTestEmails(TrialTestEmailService s) { this.trialTestEmails = s; }

    /** How congregation mail for a tenant will be handled. */
    public enum DeliveryMode {
        /** Delivered to the real recipients. */
        NORMAL,
        /** Dropped: Trial/Demo with no verified test address. */
        BLOCKED,
        /** Trial/Demo with a verified test address: one test email per action, real recipients never mailed. */
        TEST
    }

    /**
     * @param reason    why mail is not delivered normally (BLOCKED and TEST), else null
     * @param testEmail the verified test address, masked (TEST only)
     */
    public record Delivery(DeliveryMode mode, String reason, String testEmail) {
        public boolean blocked() { return mode == DeliveryMode.BLOCKED; }
        public boolean test()    { return mode == DeliveryMode.TEST; }
    }

    /**
     * What a bulk sender should expect for {@code clientId}: NORMAL for a paying
     * church (and tenant-less mail), BLOCKED or TEST for a Trial/Demo tenant. A
     * caller uses this to report accurately — "blocked", or "1 test email sent, n
     * simulated" — rather than counting dropped mail as sent.
     */
    public Delivery delivery(String clientId) {
        String why = emailBlockReason(clientId);
        if (why == null) return new Delivery(DeliveryMode.NORMAL, null, null);
        String test = testAddress(clientId);
        if (test == null) return new Delivery(DeliveryMode.BLOCKED, why, null);
        return new Delivery(DeliveryMode.TEST, why, com.churchgeniuspro.util.EmailMask.mask(test));
    }

    private String testAddress(String clientId) {
        return trialTestEmails != null ? trialTestEmails.verifiedAddress(clientId) : null;
    }

    /**
     * Phase B redirect for one blocked message. Returns true when a test email was
     * delivered, false when it was suppressed (the action's test email already went)
     * or the tenant has no verified test address (nothing sent — the Phase A block).
     * The real recipient is never mailed on this path.
     */
    private boolean redirectToTestAddress(String clientId, String toEmail, String subject, String htmlBody,
                                          String fromDisplayName, byte[] icsData) {
        String test = testAddress(clientId);
        if (test == null) return false;
        com.churchgeniuspro.util.EmailActionScope scope = com.churchgeniuspro.util.EmailActionScope.current();
        if (scope != null && !scope.claim(clientId)) {
            scope.recordSimulated();
            return false;
        }
        try {
            deliver(test, subject, TrialTestEmailService.withNotice(htmlBody, toEmail, 1), fromDisplayName, icsData);
            if (scope != null) scope.recordTestEmailSent();
            log.info("[EmailService] Trial/Demo test email for client {} sent to the verified test address (original recipient {})",
                    clientId, EmailMask.mask(toEmail));
            return true;
        } catch (Exception ex) {
            log.warn("[EmailService] test email send failed for client {}", clientId, ex);
            return false;
        }
    }

    /** True when {@code clientId} is blocked; logs the reason. */
    private boolean blocked(String clientId, String toEmail) {
        if (messagingPolicy == null) {
            return demoAccess != null && clientId != null && !demoAccess.sendingAllowed(clientId, false);
        }
        String why = messagingPolicy.emailBlockReason(clientId);
        if (why == null) return false;
        log.info("[EmailService] blocked email to {} for client {} — {}", EmailMask.mask(toEmail), clientId, why);
        return true;
    }

    public void sendSignupInvitation(String toEmail, String firstName, String clientId) {
        String churchName = resolveChurchName(clientId);
        String signupLink = baseUrl + "/signup?clientId=" + clientId;
        // Registration mail is exempt: a Trial client still has to be able to
        // register, and the recipient is the person signing up, not the congregation.
        doSend(toEmail, "Complete Your " + churchName + " Registration",
               buildHtml(firstName, signupLink, churchName), DEFAULT_FROM_NAME, null, clientId, true);
    }

    /**
     * Sends a system-level HTML email with no organization-specific branding.
     * Used for OTP codes, password-reset links, and other pre-login messages.
     *
     * <p>Failures are caught and logged rather than propagated.
     *
     * @param toEmail  recipient address
     * @param subject  email subject line
     * @param htmlBody pre-built HTML body
     */
    public void sendGenericEmail(String toEmail, String subject, String htmlBody) {
        log.info("[EmailService] sending email to {} subject '{}'", EmailMask.mask(toEmail), subject);
        doSend(toEmail, subject, htmlBody, DEFAULT_FROM_NAME, null, null);
    }

    /**
     * Sends a generic HTML email using the organization's configured Display Name
     * as the sender name instead of the hardcoded default.
     *
     * <p>Looks up {@link EmailSettings#getDisplayName()} for the given
     * {@code appClientId}; falls back to {@link #DEFAULT_FROM_NAME} when no
     * record exists or the display name is blank.
     *
     * <p>Failures are caught and logged rather than propagated.
     *
     * @param toEmail     recipient address
     * @param subject     email subject line
     * @param htmlBody    pre-built HTML body
     * @param appClientId organization identifier used to look up the display name
     */
    public void sendGenericEmail(String toEmail, String subject, String htmlBody, String appClientId) {
        log.info("[EmailService] sending email to {} subject '{}' (appClientId={})", EmailMask.mask(toEmail), subject, appClientId);
        EmailSettings settings = (appClientId != null && !appClientId.isBlank())
                ? emailSettingsRepository.findByClientId(appClientId).orElse(null)
                : null;
        String fromName = (settings != null
                && settings.getDisplayName() != null
                && !settings.getDisplayName().isBlank())
                ? settings.getDisplayName() : DEFAULT_FROM_NAME;
        doSend(toEmail, subject, htmlBody, fromName, null, appClientId);
    }

    /**
     * Returns the display name for the church identified by {@code clientId}.
     * Falls back to {@link #DEFAULT_FROM_NAME} when the record is not found.
     * Callers can use this to build church-branded subjects and body copy before
     * calling {@link #sendGenericEmail}.
     */
    public String getChurchName(String clientId) {
        return resolveChurchName(clientId);
    }

    /**
     * Sends an organization-branded email for the given {@code clientId}.
     *
     * <p>Before sending the email is enriched with:
     * <ul>
     *   <li>Sender display name from {@link EmailSettings#getDisplayName()} (if set).</li>
     *   <li>Organization logo embedded as a Base64 data-URL in the footer
     *       (when {@link EmailSettings#isIncludeLogo()} is {@code true}).</li>
     *   <li>A randomly selected promise verse in the footer
     *       (when {@link EmailSettings#isIncludeDailyVerse()} is {@code true}).</li>
     *   <li>Footer comments from {@link EmailSettings#getFooterComments()} (if not blank).</li>
     *   <li>Closing signature from {@link EmailSettings#getSignature()} (if not blank).</li>
     * </ul>
     *
     * <p>Falls back gracefully when no EmailSettings record exists for the org.
     *
     * @param toEmail  recipient address
     * @param subject  email subject line
     * @param htmlBody pre-built HTML body (footer will be inserted before {@code </body>})
     * @param clientId organization identifier used to look up branding settings
     */
    public void sendOrgEmail(String toEmail, String subject, String htmlBody, String clientId) {
        sendOrgEmail(toEmail, subject, htmlBody, clientId, null);
    }

    /**
     * Same as {@link #sendOrgEmail(String, String, String, String)} but also attaches
     * an ICS calendar file so the recipient can add the event to their calendar.
     *
     * @param icsData raw bytes of the {@code .ics} file; ignored when {@code null}
     */
    public void sendOrgEmail(String toEmail, String subject, String htmlBody,
                             String clientId, byte[] icsData) {
        sendOrgEmail(toEmail, subject, htmlBody, clientId, icsData, false);
    }

    /**
     * <b>Account mail</b> — registration, verification and account recovery. Always
     * delivered, whatever the tenant's plan says.
     *
     * <p>The Trial block exists to stop a church that is still evaluating the
     * product from messaging its <em>congregation</em>. It was never meant to stop
     * anyone from signing up, confirming an address, or getting back into a
     * locked-out account: a code or reset link that never arrives just dead-ends
     * the flow, with nothing on screen to explain why. The line drawn here is
     * therefore <em>who the message is addressed to</em> — the person performing
     * the action is exempt; the congregation is not.
     *
     * <p>Covers: the registration invitation, member sign-up verification codes and
     * the account-created welcome, bank-connect verification codes, password-reset
     * links, and username recovery.
     *
     * <p>Call this rather than {@code sendGenericEmail} for such mail even when
     * there is no tenant to pass. Both are delivered today, but only this one says
     * so on purpose: {@code sendGenericEmail} is exempt merely because its callers
     * happen to have no clientId, and it also carries ordinary congregation mail.
     * Adding a tenant to a recovery call would silently start blocking it.
     *
     * <p>This overload is organization-branded — display name, logo, footer — but it
     * bypasses everything that could legitimately drop ordinary congregation mail:
     * the Trial/demo block, the recipient's unsubscribe preference, and the monthly
     * allowance. It is also left out of the usage count, and carries no unsubscribe
     * link, since there is nothing here to unsubscribe from.
     */
    public void sendAccountEmail(String toEmail, String subject, String htmlBody, String clientId) {
        sendOrgEmail(toEmail, subject, htmlBody, clientId, null, true);
    }

    /**
     * Account mail with no tenant and no organization branding — password resets,
     * username recovery, and other pre-login messages, where the sender is the
     * platform rather than a particular church.
     */
    public void sendAccountEmail(String toEmail, String subject, String htmlBody) {
        doSend(toEmail, subject, htmlBody, DEFAULT_FROM_NAME, null, null, true);
    }

    /**
     * Account mail sent on behalf of an admin who is watching for the result.
     *
     * <p>Identical to {@link #sendAccountEmail(String, String, String, String)} —
     * organization branding, and exempt from the plan block, the unsubscribe list
     * and the monthly allowance — except that a delivery failure is THROWN rather
     * than logged. Use it where a person pressed a button and is being told
     * whether it worked, on the same reasoning as {@link #sendComposed}: an admin
     * pressing Invite is owed a real error, not a line in the server log.
     *
     * <p>{@link #sendAccountEmail} remains right for mail sent as a side effect of
     * some other action, where a failed send must not fail the action itself.
     */
    public void sendAccountEmailOrThrow(String toEmail, String subject,
                                        String htmlBody, String clientId) throws Exception {
        EmailSettings settings = (clientId != null && !clientId.isBlank())
                ? emailSettingsRepository.findByClientId(clientId).orElse(null)
                : null;

        String fromName = (settings != null
                && settings.getDisplayName() != null
                && !settings.getDisplayName().isBlank())
                ? settings.getDisplayName() : DEFAULT_FROM_NAME;

        // No unsubscribe link: account mail carries nothing to unsubscribe from.
        String enrichedBody = appendOrgFooter(htmlBody, settings, clientId, toEmail, false);
        deliver(toEmail, subject, enrichedBody, fromName, null);
    }

    /**
     * Congregation mail sent on behalf of a staff member who is watching for the
     * result — the Attendance "Email / SMS Volunteers" action.
     *
     * <p>Applies exactly the guards {@link #sendOrgEmail} applies (the recipient's
     * unsubscribe preference, the plan's monthly allowance, the Trial/demo block),
     * with the same organization branding and unsubscribe footer, and it is metered
     * the same way. The one difference is the verdict: where {@code sendOrgEmail}
     * logs a refusal or an SMTP failure and returns, this method THROWS an
     * {@link IllegalStateException} whose message says why, so the person who
     * pressed Send sees "unsubscribed" or "monthly allowance used up" next to that
     * recipient instead of a success message for mail that never left. Existing
     * callers of {@code sendOrgEmail} are unchanged.
     *
     * @throws IllegalStateException when the message is refused by a guard
     * @throws Exception when the mail server rejects the message
     */
    public void sendOrgEmailOrThrow(String toEmail, String subject, String htmlBody, String clientId) throws Exception {
        if (unsubscribeService.isUnsubscribed(toEmail, clientId)) {
            throw new IllegalStateException("Recipient has unsubscribed");
        }
        if (clientId != null && !clientId.isBlank() && !subscriptionService.canSendEmail(clientId)) {
            throw new IllegalStateException("Monthly email allowance for this subscription plan is used up");
        }
        String why = messagingPolicy != null ? messagingPolicy.emailBlockReason(clientId)
                : (demoAccess != null && clientId != null && !demoAccess.sendingAllowed(clientId, false)
                        ? "Email sending is switched off for this demo account" : null);
        EmailSettings settings = (clientId != null && !clientId.isBlank())
                ? emailSettingsRepository.findByClientId(clientId).orElse(null)
                : null;
        String fromName = (settings != null
                && settings.getDisplayName() != null
                && !settings.getDisplayName().isBlank())
                ? settings.getDisplayName() : DEFAULT_FROM_NAME;
        String enrichedBody = appendOrgFooter(htmlBody, settings, clientId, toEmail, true);
        if (why != null) {
            // Phase B: with a verified test address the message is redirected (one per
            // action) rather than refused; without one the refusal stands.
            if (testAddress(clientId) == null) throw new IllegalStateException(why);
            if (redirectToTestAddress(clientId, toEmail, subject, enrichedBody, fromName, null)) {
                subscriptionService.recordEmailSent(clientId);
            }
            return;
        }
        deliver(toEmail, subject, enrichedBody, fromName, null);
        if (clientId != null && !clientId.isBlank()) {
            subscriptionService.recordEmailSent(clientId);
        }
    }

    /**
     * @param accountMail {@code true} for registration, verification and account-recovery
     *        mail. Such a message is addressed to the person performing the action and has
     *        to arrive for the flow to complete, so it bypasses everything that could
     *        legitimately drop ordinary congregation mail: the tenant's plan block, the
     *        recipient's unsubscribe preference, and the monthly allowance. It is also
     *        left out of the usage count and carries no unsubscribe link — see the
     *        individual comments below.
     */
    private void sendOrgEmail(String toEmail, String subject, String htmlBody,
                              String clientId, byte[] icsData, boolean accountMail) {
        // Unsubscribing is a choice about a church's messages, not about being able to
        // reset your own password. Someone who opted out must still get their code.
        if (!accountMail && unsubscribeService.isUnsubscribed(toEmail, clientId)) {
            log.info("[EmailService] skipping email to {} — unsubscribed (clientId={})", EmailMask.mask(toEmail), clientId);
            return;
        }

        // Subscription plan: monthly email allowance. Account mail is exempt — running
        // out of allowance must not lock a congregation out of their own accounts.
        if (!accountMail && clientId != null && !clientId.isBlank()
                && !subscriptionService.canSendEmail(clientId)) {
            log.info("[EmailService] skipping email to {} — monthly email limit reached for subscription plan (clientId={})", EmailMask.mask(toEmail), clientId);
            return;
        }

        log.info("[EmailService] sending org email to {} subject '{}'", EmailMask.mask(toEmail), subject);

        EmailSettings settings = (clientId != null && !clientId.isBlank())
                ? emailSettingsRepository.findByClientId(clientId).orElse(null)
                : null;

        String fromName = (settings != null
                && settings.getDisplayName() != null
                && !settings.getDisplayName().isBlank())
                ? settings.getDisplayName() : DEFAULT_FROM_NAME;

        // No unsubscribe link on account mail: there is nothing to unsubscribe from,
        // and a recipient who followed it would silently opt out of their church's
        // messages as a side effect of resetting a password.
        String enrichedBody = appendOrgFooter(htmlBody, settings, clientId, toEmail, !accountMail);
        boolean delivered = doSend(toEmail, subject, enrichedBody, fromName, icsData, clientId, accountMail);
        // Account mail is not metered either. Counting it would let password resets
        // eat the allowance the church actually bought for reaching its congregation.
        // Nor is a message the tenant block dropped: only mail actually handed to the
        // mail sender consumes the allowance — so a Trial/demo message redirected to a
        // verified test address later counts as exactly the one email it is.
        if (delivered && !accountMail && clientId != null && !clientId.isBlank()) {
            subscriptionService.recordEmailSent(clientId);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Core mail-send logic shared by all public methods.
     * Failures are caught and logged; never re-thrown so a transient SMTP
     * error cannot roll back any enclosing database transaction.
     */
    /**
     * Regex that matches {@code src='data:<mime>;base64,<data>'} inside HTML.
     * Captures group 1 = MIME type, group 2 = base64 payload.
     */
    private static final Pattern DATA_IMG_PATTERN =
            Pattern.compile("src='data:([^;']+);base64,([A-Za-z0-9+/=\\r\\n]+)'");

    /** Holds a decoded inline image to be attached after {@code setText} is called. */
    private static final class InlineRef {
        final String cid, mime;
        final byte[] data;
        InlineRef(String cid, byte[] data, String mime) {
            this.cid = cid; this.data = data; this.mime = mime;
        }
    }

    /**
     * Scans {@code html} for inline base64 data-URL images and replaces each one
     * with a {@code cid:} reference.  The decoded image bytes are added to
     * {@code inlines} so the caller can attach them AFTER calling
     * {@link MimeMessageHelper#setText} — the correct MIME ordering.
     */
    private String extractBase64Images(String html, java.util.List<InlineRef> inlines) {
        Matcher m  = DATA_IMG_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            String mime = m.group(1).trim();
            String b64  = m.group(2).replaceAll("\\s", "");
            String cid  = "emailImg" + idx++;
            try {
                byte[] data = Base64.getDecoder().decode(b64);
                inlines.add(new InlineRef(cid, data, mime));
                m.appendReplacement(sb, Matcher.quoteReplacement("src='cid:" + cid + "'"));
            } catch (Exception e) {
                // Malformed base64 — leave this image unreplaced
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Sends one composed message to many recipients, for the Service Admin
     * composer. Unlike {@link #doSend}, failures are THROWN: an admin pressing
     * Send is owed a real error, not a line in the server log.
     *
     * <p>BCC privacy: recipients are handed to {@code setBcc}, so the addresses
     * travel as SMTP envelope recipients only and never appear in the message
     * headers. No BCC recipient can see the To list's siblings or any other BCC
     * address, because a single MimeMessage carries no BCC header at all.
     */
    public void sendComposed(java.util.List<String> to,
                             java.util.List<String> bcc,
                             String subject,
                             String htmlBody,
                             String fromOverride,
                             String fromDisplayName) throws Exception {
        sendComposed(to, bcc, subject, htmlBody, fromOverride, fromDisplayName, null);
    }

    /**
     * Same as above for a known tenant. This method builds its own MimeMessage
     * rather than going through {@code doSend}, so it carries its own block check;
     * a Trial or demo client must not reach real inboxes through it either.
     */
    public void sendComposed(java.util.List<String> to,
                             java.util.List<String> bcc,
                             String subject,
                             String htmlBody,
                             String fromOverride,
                             String fromDisplayName,
                             String clientId) throws Exception {
        if ((to == null || to.isEmpty()) && (bcc == null || bcc.isEmpty())) {
            throw new IllegalArgumentException("At least one To or BCC recipient is required");
        }
        if (clientId != null && messagingPolicy != null) {
            String why = messagingPolicy.emailBlockReason(clientId);
            if (why != null) {
                // Phase B: one composed message is one action — it goes, once, to the
                // verified test address with the notice; the real To/BCC lists are dropped.
                String test = testAddress(clientId);
                if (test == null) throw new IllegalStateException(why);
                int n = (to == null ? 0 : to.size()) + (bcc == null ? 0 : bcc.size());
                String first = to != null && !to.isEmpty() ? to.get(0) : bcc.get(0);
                to = java.util.List.of(test);
                bcc = java.util.List.of();
                htmlBody = TrialTestEmailService.withNotice(htmlBody, first, n);
                com.churchgeniuspro.util.EmailActionScope scope = com.churchgeniuspro.util.EmailActionScope.current();
                if (scope != null) { scope.claim(clientId); scope.recordTestEmailSent(); }
            }
        }
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        String from = (fromOverride != null && !fromOverride.isBlank())
                ? fromOverride.trim() : "info@churchgeniuspro.com";
        helper.setFrom(from, fromDisplayName == null || fromDisplayName.isBlank()
                ? "Church Genius Pro" : fromDisplayName);
        if (to  != null && !to.isEmpty())  helper.setTo(to.toArray(new String[0]));
        if (bcc != null && !bcc.isEmpty()) helper.setBcc(bcc.toArray(new String[0]));
        helper.setSubject(subject == null ? "" : subject);

        java.util.List<InlineRef> inlines = new java.util.ArrayList<>();
        String processedBody = extractBase64Images(htmlBody == null ? "" : htmlBody, inlines);
        helper.setText(processedBody, true);          // body first, then inlines (MIME order)
        for (InlineRef ir : inlines) {
            helper.addInline(ir.cid, new ByteArrayResource(ir.data), ir.mime);
        }
        mailSender.send(message);
    }

    /**
     * The single point at which this service hands a message to the mail sender.
     *
     * <p>The tenant block is enforced HERE rather than in the public methods, so a
     * caller cannot route around it by choosing a different overload. {@code clientId}
     * may be null for genuinely tenant-less mail (pre-login OTP and password resets);
     * every caller that knows its tenant is expected to pass it.
     */
    private boolean doSend(String toEmail, String subject, String htmlBody,
                           String fromDisplayName, byte[] icsData, String clientId) {
        return doSend(toEmail, subject, htmlBody, fromDisplayName, icsData, clientId, false);
    }

    /**
     * @param accountMail {@code true} only for registration, verification and
     *        account-recovery mail, which must reach the person completing the flow
     *        whatever the tenant's plan says. Passed explicitly rather than by
     *        leaving {@code clientId} null, so an exemption is visible at the call
     *        site and cannot be mistaken for a caller that simply forgot to pass
     *        its tenant.
     */
    private boolean doSend(String toEmail, String subject, String htmlBody,
                           String fromDisplayName, byte[] icsData, String clientId,
                           boolean accountMail) {
        if (!accountMail && blocked(clientId, toEmail)) {
            // Phase B: a Trial/Demo tenant with a verified test address gets its one
            // test email per action here instead of a dropped message.
            return redirectToTestAddress(clientId, toEmail, subject, htmlBody, fromDisplayName, icsData);
        }
        try {
            deliver(toEmail, subject, htmlBody, fromDisplayName, icsData);
            return true;
        } catch (Exception ex) {
            log.warn("[EmailService] mail send failed \u2192 {}", EmailMask.mask(toEmail), ex);
            return false;
        }
    }

    /**
     * Builds and hands one message to the mail sender, propagating any failure.
     *
     * <p>The single place a MimeMessage is assembled for single-recipient mail.
     * {@link #doSend} wraps this and swallows the failure; {@link
     * #sendAccountEmailOrThrow} wraps it and does not. Splitting the two apart here
     * means the choice is about who is waiting for an answer, not about two
     * divergent copies of the MIME assembly.
     */
    private void deliver(String toEmail, String subject, String htmlBody,
                         String fromDisplayName, byte[] icsData) throws Exception {
        {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom("info@churchgeniuspro.com", fromDisplayName);
            helper.setTo(toEmail);
            helper.setSubject(subject);

            // Step 1: replace base64 data-URLs with cid: references, collect images.
            // Images must NOT be added to the helper yet — addInline() must come
            // after setText() or the body part ends up empty in the MIME structure.
            java.util.List<InlineRef> inlines = new java.util.ArrayList<>();
            String processedBody = extractBase64Images(htmlBody, inlines);

            // Step 2: set the HTML body first.
            helper.setText(processedBody, true);

            // Step 3: now attach inline images (correct MIME ordering).
            for (InlineRef ir : inlines) {
                helper.addInline(ir.cid, new ByteArrayResource(ir.data), ir.mime);
            }

            if (icsData != null && icsData.length > 0) {
                // Use application/octet-stream to prevent Gmail from injecting
                // its own "Yes / No / Maybe" RSVP widget and timezone overlay.
                helper.addAttachment("event.ics",
                        new ByteArrayResource(icsData), "application/octet-stream");
            }
            mailSender.send(message);
        }
    }

    /**
     * Appends an organization-branded footer block to the HTML email body.
     *
     * <p>The footer is inserted immediately before the closing {@code </body>} tag.
     * If that tag is absent the block is appended at the end of the string.
     *
     * <p>No-ops (returns the original body unchanged) when {@code settings} is
     * {@code null} or when none of the four footer features are enabled/non-blank.
     */
    private String appendOrgFooter(String htmlBody, EmailSettings settings,
                                    String clientId, String toEmail) {
        return appendOrgFooter(htmlBody, settings, clientId, toEmail, true);
    }

    /**
     * @param includeUnsubscribeLink false for account mail, which the recipient
     *        cannot meaningfully unsubscribe from.
     */
    private String appendOrgFooter(String htmlBody, EmailSettings settings,
                                    String clientId, String toEmail,
                                    boolean includeUnsubscribeLink) {
        StringBuilder footer = new StringBuilder();

        // ── Org-specific branding (logo, verse, comments, signature) ────────
        boolean hasOrgContent = false;
        if (settings != null) {
            boolean includeLogo       = settings.isIncludeLogo();
            boolean includeDailyVerse = settings.isIncludeDailyVerse();
            String  footerComments    = settings.getFooterComments();
            String  signature         = settings.getSignature();
            boolean hasComments  = footerComments != null && !footerComments.isBlank();
            boolean hasSignature = signature      != null && !signature.isBlank();
            hasOrgContent = includeLogo || includeDailyVerse || hasComments || hasSignature;

            if (hasOrgContent) {
                footer.append("<div style='margin-top:24px;padding:16px 32px 20px;"
                            + "background-color:#f8f9ff;border-top:1px solid #e8eaf6;text-align:center;'>");

                // ── Logo ────────────────────────────────────────────────────
                if (includeLogo && clientId != null) {
                    ChurchLogo logo = churchLogoRepository.findByClientId(clientId).orElse(null);
                    if (logo != null && logo.getLogoData() != null) {
                        String mime = logo.getContentType() != null ? logo.getContentType() : "image/png";
                        String b64  = Base64.getEncoder().encodeToString(logo.getLogoData());
                        footer.append("<div style='margin-bottom:12px;'>")
                              .append("<img src='data:").append(mime).append(";base64,").append(b64).append("'")
                              .append(" alt='Logo' style='max-height:60px;max-width:200px;"
                                    + "object-fit:contain;display:inline-block;' />")
                              .append("</div>");
                    }
                }

                // ── Daily verse ──────────────────────────────────────────────
                if (includeDailyVerse && clientId != null) {
                    PromiseVerse verse = promiseVerseRepository.findRandomByClientId(clientId).orElse(null);
                    if (verse != null) {
                        footer.append("<div style='margin-bottom:12px;font-size:13px;"
                                    + "color:#5c6bc0;line-height:1.6;'>")
                              .append("<em>&ldquo;").append(escapeHtml(verse.getVerseText())).append("&rdquo;</em>")
                              .append("<br/><strong>&mdash;&nbsp;")
                              .append(escapeHtml(verse.getReference())).append("</strong>")
                              .append("</div>");
                    }
                }

                // ── Footer comments ──────────────────────────────────────────
                if (hasComments) {
                    footer.append("<div style='margin-bottom:8px;font-size:12px;color:#777;line-height:1.6;'>")
                          .append(escapeHtml(footerComments))
                          .append("</div>");
                }

                // ── Signature ────────────────────────────────────────────────
                if (hasSignature) {
                    footer.append("<div style='font-size:12px;color:#555;font-weight:600;'>")
                          .append(escapeHtml(signature))
                          .append("</div>");
                }

                footer.append("</div>");
            }
        }

        // ── Unsubscribe link ─────────────────────────────────────────────────
        if (includeUnsubscribeLink
                && toEmail != null && !toEmail.isBlank() && clientId != null && !clientId.isBlank()) {
            try {
                String encodedEmail    = java.net.URLEncoder.encode(toEmail,    java.nio.charset.StandardCharsets.UTF_8);
                String encodedClientId = java.net.URLEncoder.encode(clientId,  java.nio.charset.StandardCharsets.UTF_8);
                footer.append("<div style='max-width:520px;margin:6px auto 0;text-align:center;font-size:11px;color:#aaa;'>")
                      .append("<a href='").append(baseUrl)
                      .append("/unsubscribe?email=").append(encodedEmail)
                      .append("&amp;clientId=").append(encodedClientId)
                      // Signed so the recipient can only unsubscribe THIS address from THIS
                      // church; the plaintext id alone used to let anyone unsubscribe anyone.
                      .append("&amp;sig=").append(com.churchgeniuspro.util.EncryptionUtil.sign(toEmail.trim().toLowerCase() + "|" + clientId))
                      .append("'")
                      .append(" style='color:#aaa;text-decoration:underline;'>Unsubscribe from these emails</a>")
                      .append("</div>");
            } catch (Exception ignored) { /* URL encoding should never fail for UTF-8 */ }
        }

        // ── Disclaimer — always last, after all other content ────────────────
        String churchNameForDisclaimer = resolveChurchName(clientId);
        footer.append("<div style='max-width:520px;margin:6px auto 24px;text-align:center;"
                    + "font-size:11px;color:#aaa;line-height:1.6;'>")
              .append("Sent by ").append(escapeHtml(churchNameForDisclaimer))
              .append(" &mdash; if you believe this was sent in error, "
                    + "please contact your church administrator.")
              .append("</div>");

        // Insert before </body> when present; otherwise append
        int bodyClose = htmlBody.lastIndexOf("</body>");
        if (bodyClose >= 0) {
            return htmlBody.substring(0, bodyClose) + footer + htmlBody.substring(bodyClose);
        }
        return htmlBody + footer;
    }

    /**
     * Builds the HTML body for the signup invitation email.
     *
     * @param firstName   registrant's first name
     * @param signupLink  full signup URL including the {@code clientId} parameter
     * @param churchName  name of the church, used in header and body copy
     * @return HTML string ready to be set as the message body
     */
    private String buildHtml(String firstName, String signupLink, String churchName) {
        String safeName = escapeHtml(churchName);
        return "<!DOCTYPE html>"
             + "<html lang='en'>"
             + "<head>"
             + "  <meta charset='UTF-8' />"
             + "  <meta name='viewport' content='width=device-width, initial-scale=1.0' />"
             + "  <title>Complete Your Registration</title>"
             + "</head>"
             + "<body style='margin:0;padding:0;background-color:#f5f6fa;"
             +             "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"

             + "  <table width='100%' cellpadding='0' cellspacing='0' style='background-color:#f5f6fa;padding:40px 20px;'>"
             + "    <tr><td align='center'>"

             + "      <table width='100%' cellpadding='0' cellspacing='0'"
             + "             style='max-width:520px;background:#ffffff;border-radius:16px;"
             + "                    box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"

             // ── Header band ────────────────────────────────────────────────
             + "        <tr>"
             + "          <td style='background-color:#673147;padding:32px 40px;text-align:center;'>"
             + "            <p style='margin:0;font-size:22px;font-weight:700;color:#ffffff;"
             + "                      letter-spacing:0.5px;'>" + safeName + "</p>"
             + "            <p style='margin:8px 0 0;font-size:13px;color:rgba(255,255,255,0.75);'>"
             + "              Welcome to the family</p>"
             + "          </td>"
             + "        </tr>"

             // ── Body ───────────────────────────────────────────────────────
             + "        <tr>"
             + "          <td style='padding:36px 40px;'>"

             + "            <p style='margin:0 0 16px;font-size:16px;color:#1a1a2e;font-weight:600;'>"
             + "              Hi " + escapeHtml(firstName) + ",</p>"

             + "            <p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>"
             + "              Thank you for registering your church with <strong>" + safeName + "</strong>. "
             + "              Your church profile has been created successfully.</p>"

             + "            <p style='margin:0 0 28px;font-size:14px;color:#555;line-height:1.7;'>"
             + "              To complete your account setup and create your login credentials, "
             + "              please click the button below. This link is unique to your registration.</p>"

             // ── CTA button ─────────────────────────────────────────────────
             + "            <table cellpadding='0' cellspacing='0' style='margin:0 auto 28px;'>"
             + "              <tr>"
             + "                <td style='background-color:#673147;border-radius:8px;'>"
             + "                  <a href='" + signupLink + "'"
             + "                     style='display:inline-block;padding:14px 36px;"
             + "                            font-size:15px;font-weight:600;color:#ffffff;"
             + "                            text-decoration:none;letter-spacing:0.3px;'>"
             + "                    Create My Account</a>"
             + "                </td>"
             + "              </tr>"
             + "            </table>"

             // ── Fallback link ──────────────────────────────────────────────
             + "            <p style='margin:0 0 8px;font-size:12px;color:#aaa;'>"
             + "              If the button doesn't work, copy and paste this link into your browser:</p>"
             + "            <p style='margin:0;font-size:12px;word-break:break-all;'>"
             + "              <a href='" + signupLink + "' style='color:#3a7bd5;'>"
             + signupLink + "</a></p>"

             + "          </td>"
             + "        </tr>"

             // ── Footer ─────────────────────────────────────────────────────
             + "        <tr>"
             + "          <td style='background-color:#f8f9ff;padding:20px 40px;"
             + "                     border-top:1px solid #e8eaf6;text-align:center;'>"
             + "            <p style='margin:0;font-size:12px;color:#aaa;line-height:1.6;'>"
             + "              This email was sent by <strong>" + safeName + "</strong>.<br/>"
             + "              If you did not register, please ignore this email.</p>"
             + "          </td>"
             + "        </tr>"

             + "      </table>"
             + "    </td></tr>"
             + "  </table>"

             + "</body></html>";
    }

    /**
     * Looks up the church name for the given {@code clientId}.
     * Falls back to {@link #DEFAULT_FROM_NAME} when the record is not found
     * or {@code clientId} is blank.
     */
    private String resolveChurchName(String clientId) {
        if (clientId == null || clientId.isBlank()) return DEFAULT_FROM_NAME;
        return churchRegistrationRepository
                .findByClientIdAndDeleteFlagFalse(clientId)
                .map(ChurchRegistration::getChurchName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(DEFAULT_FROM_NAME);
    }

    /**
     * Minimal HTML escaping to prevent injection of special characters
     * into the email body (e.g. a first name containing {@code <} or {@code &}).
     */
    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
