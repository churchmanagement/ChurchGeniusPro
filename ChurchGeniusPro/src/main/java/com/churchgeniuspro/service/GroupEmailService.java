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

        if (bccEmails == null || bccEmails.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient email is required.");
        }

        MimeMessage message = mailSender.createMimeMessage();
        // true = multipart (required for attachments and inline HTML)
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromAddress);
        // Set "To" to the sender address so the real recipients remain hidden in BCC
        helper.setTo(fromAddress);
        helper.setBcc(bccEmails.toArray(new String[0]));
        helper.setSubject(subject);
        helper.setText(htmlBody, true);   // true = HTML

        if (attachment != null && !attachment.isEmpty()) {
            String filename = Objects.requireNonNullElse(
                    attachment.getOriginalFilename(), "attachment");
            helper.addAttachment(filename, attachment);
        }

        mailSender.send(message);
    }
}
