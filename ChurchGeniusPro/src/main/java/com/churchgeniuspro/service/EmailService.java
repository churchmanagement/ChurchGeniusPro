package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.EmailSettings;
import com.churchgeniuspro.hibernate.PromiseVerse;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.EmailSettingsRepository;
import com.churchgeniuspro.repository.PromiseVerseRepository;
import jakarta.mail.internet.MimeMessage;
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
    public void sendSignupInvitation(String toEmail, String firstName, String clientId) {
        String churchName = resolveChurchName(clientId);
        String signupLink = baseUrl + "/signup?clientId=" + clientId;
        doSend(toEmail, "Complete Your " + churchName + " Registration",
               buildHtml(firstName, signupLink, churchName), DEFAULT_FROM_NAME, null);
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
        System.out.println("[EmailService] Sending email to " + toEmail
                + " with subject '" + subject + "'");
        doSend(toEmail, subject, htmlBody, DEFAULT_FROM_NAME, null);
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
        System.out.println("[EmailService] Sending email to " + toEmail
                + " with subject '" + subject + "' (appClientId=" + appClientId + ")");
        EmailSettings settings = (appClientId != null && !appClientId.isBlank())
                ? emailSettingsRepository.findByClientId(appClientId).orElse(null)
                : null;
        String fromName = (settings != null
                && settings.getDisplayName() != null
                && !settings.getDisplayName().isBlank())
                ? settings.getDisplayName() : DEFAULT_FROM_NAME;
        doSend(toEmail, subject, htmlBody, fromName, null);
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
        // Skip if the recipient has unsubscribed
        if (unsubscribeService.isUnsubscribed(toEmail, clientId)) {
            System.out.println("[EmailService] Skipping email to " + toEmail
                    + " — unsubscribed (clientId=" + clientId + ")");
            return;
        }

        // Subscription plan: monthly email allowance
        if (clientId != null && !clientId.isBlank()
                && !subscriptionService.canSendEmail(clientId)) {
            System.out.println("[EmailService] Skipping email to " + toEmail
                    + " — monthly email limit reached for subscription plan (clientId=" + clientId + ")");
            return;
        }

        System.out.println("[EmailService] Sending org email to " + toEmail
                + " with subject '" + subject + "'");

        EmailSettings settings = (clientId != null && !clientId.isBlank())
                ? emailSettingsRepository.findByClientId(clientId).orElse(null)
                : null;

        String fromName = (settings != null
                && settings.getDisplayName() != null
                && !settings.getDisplayName().isBlank())
                ? settings.getDisplayName() : DEFAULT_FROM_NAME;

        String enrichedBody = appendOrgFooter(htmlBody, settings, clientId, toEmail);
        doSend(toEmail, subject, enrichedBody, fromName, icsData);
        if (clientId != null && !clientId.isBlank()) {
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

    private void doSend(String toEmail, String subject, String htmlBody,
                        String fromDisplayName, byte[] icsData) {
        try {
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
        } catch (Exception ex) {
            System.err.println("[EmailService] Mail send failed → " + toEmail
                    + ": " + ex.getMessage());
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
        if (toEmail != null && !toEmail.isBlank() && clientId != null && !clientId.isBlank()) {
            try {
                String encodedEmail    = java.net.URLEncoder.encode(toEmail,    java.nio.charset.StandardCharsets.UTF_8);
                String encodedClientId = java.net.URLEncoder.encode(clientId,  java.nio.charset.StandardCharsets.UTF_8);
                footer.append("<div style='max-width:520px;margin:6px auto 0;text-align:center;font-size:11px;color:#aaa;'>")
                      .append("<a href='").append(baseUrl)
                      .append("/unsubscribe?email=").append(encodedEmail)
                      .append("&amp;clientId=").append(encodedClientId).append("'")
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
