package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Map;

/**
 * The email a new trial administrator gets listing all three portal sign-ins.
 *
 * <p>Provisioning already emails an invitation link so the administrator can set
 * their own password for the staff portal. That invitation says nothing about the
 * Member Portal or the Kids Portal, whose generated credentials were visible only
 * on the Service Admin screen — so the person evaluating the product had no way
 * to open two of the three doors into their own account.
 *
 * <p>Sent as ACCOUNT mail, deliberately. {@code MessagingPolicy} blocks ordinary
 * email on a Trial subscription, and it should: a trial must not mail a
 * congregation. This is not congregation mail — it is the message that makes the
 * account usable at all, in the same class as the invitation link and a password
 * reset, and {@code sendAccountEmail} is the existing exemption for exactly that.
 */
@Service
public class PortalWelcomeEmail {

    private static final Logger log = LoggerFactory.getLogger(PortalWelcomeEmail.class);

    /**
     * Shown on the staff card only.
     *
     * <p>Provisioning sends an invitation link first, so by the time this message
     * arrives the administrator may already have set a password of their own. Two
     * emails offering two different staff sign-ins reads as a contradiction unless
     * the second one says which is which: the credentials below still work, and are
     * the fallback if the invitation was missed or has lapsed.
     */
    public static final String STAFF_NOTE =
            "You may have already received a separate email with instructions to create "
            + "your own credentials for the Staff Portal.";

    private final EmailService emailService;
    private final PortalCredentialService portals;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public PortalWelcomeEmail(EmailService emailService, PortalCredentialService portals) {
        this.emailService = emailService;
        this.portals = portals;
    }

    /** Test seam — set the site address without a Spring context. */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /**
     * Sends the portal list for a newly created trial tenant.
     *
     * <p>Never throws: the tenant exists either way, and a failed send is a
     * resendable state, not a reason to lose a registration. Mirrors how the
     * invitation email is handled one line above the call site.
     *
     * @return true when the message was handed to the mail server
     */
    public boolean send(String clientId, String churchName, String toEmail, String firstName,
                        String trialEndsOn) {
        if (toEmail == null || toEmail.isBlank()) return false;
        List<Map<String, Object>> rows = portals.portalsFor(clientId);
        if (rows.isEmpty()) return false;
        try {
            emailService.sendAccountEmail(
                    toEmail,
                    "Your " + churchName + " trial — all three portal sign-ins",
                    buildHtml(churchName, firstName, rows, trialEndsOn),
                    clientId);
            log.info("Trial portal credentials emailed for {} ({} portals)", clientId, rows.size());
            return true;
        } catch (Exception e) {
            log.warn("Trial portal credentials email failed for {} — {}", clientId, e.getMessage());
            return false;
        }
    }

    /** The message body. Table-based and inline-styled, as mail clients require. */
    String buildHtml(String churchName, String firstName,
                     List<Map<String, Object>> rows, String trialEndsOn) {
        StringBuilder b = new StringBuilder();
        b.append("<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;")
         .append("color:#1B1620;font-size:15px;line-height:1.6;max-width:640px;\">");

        b.append("<h2 style=\"color:#673147;font-size:20px;margin:0 0 14px;\">Welcome to ")
         .append(esc(churchName)).append("</h2>");

        b.append("<p style=\"margin:0 0 14px;\">Hello ").append(esc(firstName)).append(",</p>");

        b.append("<p style=\"margin:0 0 18px;\">Your trial account is ready. It comes with three ")
         .append("separate sign-ins so you can see the product from every side — the staff view ")
         .append("you administer it from, what a church member sees, and what a child sees. ")
         .append("All three use the same sample data and the same account.</p>");

        for (Map<String, Object> r : rows) {
            Object password = r.get("password");
            b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" ")
             .append("style=\"width:100%;border:1px solid #D9CFD5;border-left:3px solid #673147;")
             .append("border-radius:4px;margin:0 0 14px;\"><tr><td style=\"padding:14px 16px;\">")
             .append("<div style=\"font-weight:600;font-size:16px;color:#673147;\">")
             .append(esc(String.valueOf(r.get("label")))).append("</div>")
             .append("<div style=\"color:#55495A;font-size:13px;margin:2px 0 10px;\">")
             .append(esc(String.valueOf(r.get("purpose")))).append("</div>");

            if ("staff".equals(String.valueOf(r.get("key")))) {
                b.append("<div style=\"background:#F6F1F4;border-radius:3px;padding:8px 10px;")
                 .append("margin:0 0 10px;color:#55495A;font-size:13px;\">")
                 .append(esc(STAFF_NOTE))
                 // Only promise a working fallback when there actually is one: a
                 // tenant provisioned before demo_password existed has no default.
                 .append(password == null ? "" : " The default sign-in below works as well.")
                 .append("</div>");
            }

            b.append("<div style=\"font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:14px;\">")
             .append("Username: <strong>").append(esc(String.valueOf(r.get("username")))).append("</strong><br>")
             .append("Password: <strong>")
             .append(password == null
                     ? "(set during sign-up)"
                     : esc(String.valueOf(password)))
             .append("</strong></div>")
             .append("</td></tr></table>");
        }

        b.append("<p style=\"margin:18px 0 0;\">Sign in at <a href=\"").append(esc(baseUrl))
         .append("/login\" style=\"color:#673147;\">").append(esc(hostOf(baseUrl)))
         .append("</a>.</p>");

        if (trialEndsOn != null && !trialEndsOn.isBlank()) {
            b.append("<p style=\"margin:10px 0 0;color:#55495A;\">Your trial runs until <strong>")
             .append(esc(trialEndsOn)).append("</strong>. All three portals end on that date together.</p>");
        }

        b.append("<p style=\"margin:18px 0 0;color:#55495A;font-size:13px;\">")
         .append("Email and SMS sending stay switched off during a trial, so nobody in your ")
         .append("congregation is contacted while you are exploring. You can see these sign-ins ")
         .append("again at any time on your home page, under “Your trial portals”.</p>");

        b.append("<p style=\"margin:18px 0 0;color:#55495A;font-size:13px;\">")
         .append("Questions? Reply to this email or write to support@churchgeniuspro.com.</p>");

        b.append("</div>");
        return b.toString();
    }

    private static String esc(String s) { return HtmlUtils.htmlEscape(s == null ? "" : s); }

    private static String hostOf(String url) {
        if (url == null) return "";
        return url.replaceFirst("^https?://", "").replaceFirst("/$", "");
    }
}
