package com.churchgeniuspro.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

/**
 * Sends bulk BCC emails (with optional attachment) for the Groups feature.
 */
@Service
public class GroupEmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:${spring.mail.username:noreply@churchgeniuspro.com}}")
    private String fromAddress;

    public GroupEmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * The one authority on whether a tenant may send at all (Trial subscription,
     * demo tenant). This class holds its own JavaMailSender rather than going
     * through EmailService, so it must ask for itself.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.MessagingPolicy messagingPolicy;

    /** Test seam — supply the policy without a Spring context. */
    public void setMessagingPolicy(com.churchgeniuspro.service.MessagingPolicy p) { this.messagingPolicy = p; }

    /** Phase B: the verified Trial/Demo test address a blocked tenant's message is redirected to. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private TrialTestEmailService trialTestEmails;
    public void setTrialTestEmails(TrialTestEmailService s) { this.trialTestEmails = s; }

    private final ThreadLocal<String> lastTestAddress = new ThreadLocal<>();
    /** Masked test address the last send on this thread was redirected to, or null. */
    public String lastTestAddress() { return lastTestAddress.get(); }

    /**
     * Sends an HTML email to the given BCC list, with an optional file attachment.
     *
     * @param subject    email subject
     * @param htmlBody   HTML body (from the rich-text editor)
     * @param bccEmails  list of recipient email addresses (placed in BCC)
     * @param attachment optional file attachment; may be null or empty
     * @throws Exception if the mail could not be sent
     */
    public void sendBccEmail(String subject,
                             String htmlBody,
                             List<String> bccEmails,
                             MultipartFile attachment) throws Exception {
        sendBccEmail(subject, htmlBody, bccEmails, attachment, null);
    }

    /**
     * Same as above for a known tenant, so a Trial or demo client's group email
     * is refused here too rather than only in the pages that call it.
     */
    public void sendBccEmail(String subject,
                             String htmlBody,
                             List<String> bccEmails,
                             MultipartFile attachment,
                             String clientId) throws Exception {

        lastTestAddress.remove();
        if (bccEmails == null || bccEmails.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient email is required.");
        }
        String testAddress = null;
        if (clientId != null && messagingPolicy != null) {
            String why = messagingPolicy.emailBlockReason(clientId);
            if (why != null) {
                // Phase B: one message is one action — it goes once to the verified
                // test address; without one the refusal stands.
                testAddress = trialTestEmails != null ? trialTestEmails.verifiedAddress(clientId) : null;
                if (testAddress == null) throw new IllegalStateException(why);
            }
        }

        MimeMessage message = mailSender.createMimeMessage();
        // true = multipart (required for attachments and inline HTML)
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromAddress);
        if (testAddress != null) {
            helper.setTo(testAddress);           // real recipients are never mailed
            helper.setText(TrialTestEmailService.withNotice(htmlBody, bccEmails.get(0), bccEmails.size()), true);
            lastTestAddress.set(com.churchgeniuspro.util.EmailMask.mask(testAddress));
        } else {
            // Set "To" to the sender address so the real recipients remain hidden in BCC
            helper.setTo(fromAddress);
            helper.setBcc(bccEmails.toArray(new String[0]));
            helper.setText(htmlBody, true);   // true = HTML
        }
        helper.setSubject(subject);

        if (attachment != null && !attachment.isEmpty()) {
            String filename = Objects.requireNonNullElse(
                    attachment.getOriginalFilename(), "attachment");
            helper.addAttachment(filename, attachment);
        }

        mailSender.send(message);
    }
}
