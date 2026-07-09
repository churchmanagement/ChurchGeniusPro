package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.service.WhatsAppSenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ServiceClientService {

    private static final Logger log = LoggerFactory.getLogger(ServiceClientService.class);

    private final ServiceClientRepository repository;
    private final EmailService             emailService;
    private final WhatsAppSenderService    whatsAppSender;
    private final LoginRepository          loginRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    public ServiceClientService(ServiceClientRepository repository,
                                EmailService             emailService,
                                WhatsAppSenderService    whatsAppSender,
                                LoginRepository          loginRepository) {
        this.repository      = repository;
        this.emailService    = emailService;
        this.whatsAppSender  = whatsAppSender;
        this.loginRepository = loginRepository;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<ServiceClient> getAll() {
        return repository.findAllByDeleteFlagFalseOrderByIdDesc();
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public ServiceClient save(ServiceClientBO bo) {
        ServiceClient entity = new ServiceClient();
        mapBoToEntity(bo, entity);
        ServiceClient saved = repository.save(entity);
        // Generate a unique token as the Client ID, prefixed with "CHR"
        saved.setClientId("CHR" + UUID.randomUUID().toString());
        return repository.save(saved);
    }

    public ServiceClient update(Integer id, ServiceClientBO bo) {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));
        mapBoToEntity(bo, entity);
        return repository.save(entity);
    }

    public void softDelete(Integer id) {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));
        entity.setDeleteFlag(true);
        repository.save(entity);
    }

    /**
     * Marks the client as approved, sets status to Active, and sends a
     * welcome email containing a church-registration link with an
     * encrypted Client ID.
     */
    public void approve(Integer id) throws Exception {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));
        entity.setApproved(true);
        entity.setStatus("Active");
        repository.save(entity);

        String encryptedId = EncryptionUtil.encrypt(entity.getClientId());
        String link = baseUrl + "/churchregistration.html?clientId=" + encryptedId;

        // ── Email ──────────────────────────────────────────────────────────
        String html = buildApprovalEmail(entity.getName(), link);
        emailService.sendGenericEmail(
                entity.getEmail(),
                "Your Registration Has Been Approved – Church Genius Pro",
                html);

        // ── WhatsApp ───────────────────────────────────────────────────────
        if (entity.getPhone() != null && !entity.getPhone().isBlank()) {
            String whatsAppMsg = buildApprovalWhatsAppMessage(entity.getName(), link);
            whatsAppSender.sendWhatsAppToPhone(entity.getPhone(), whatsAppMsg, entity.getClientId());
        }
    }

    /**
     * Re-approves an already-approved client.
     *
     * <ol>
     *   <li>Invalidates any existing church signup tied to this clientId
     *       (sets deleted=true, active=false) so the old registration link
     *       can no longer be used to log in.</li>
     *   <li>Generates a fresh encrypted registration link and sends it
     *       via email (and WhatsApp if a phone number is on file).</li>
     * </ol>
     */
    public void reapprove(Integer id) throws Exception {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));

        // ── Invalidate existing church signup for this clientId ────────────
        loginRepository.findByClientId(entity.getClientId()).ifPresent(existing -> {
            if (Boolean.TRUE.equals(existing.getChurch())) {
                existing.setDeleted(true);
                existing.setActive(false);
                loginRepository.save(existing);
                log.info("Invalidated existing church signup id={} for clientId={}",
                        existing.getId(), entity.getClientId());
            }
        });

        // ── Ensure client remains approved and active ──────────────────────
        entity.setApproved(true);
        entity.setStatus("Active");
        repository.save(entity);

        // ── Generate fresh link and notify ────────────────────────────────
        String encryptedId = EncryptionUtil.encrypt(entity.getClientId());
        String link = baseUrl + "/churchregistration.html?clientId=" + encryptedId;

        String html = buildReapprovalEmail(entity.getName(), link);
        emailService.sendGenericEmail(
                entity.getEmail(),
                "Your New Church Registration Link – Church Genius Pro",
                html);

        if (entity.getPhone() != null && !entity.getPhone().isBlank()) {
            String whatsAppMsg = buildApprovalWhatsAppMessage(entity.getName(), link);
            whatsAppSender.sendWhatsAppToPhone(entity.getPhone(), whatsAppMsg, entity.getClientId());
        }
    }

    /**
     * Decrypts {@code encryptedClientId} and returns the matching active
     * ServiceClient, or {@code null} if the ID is invalid / inactive.
     */
    public ServiceClient validateClientId(String encryptedClientId) throws Exception {
        String decrypted = EncryptionUtil.decrypt(encryptedClientId);
        return repository.findByClientIdAndStatusAndDeleteFlagFalse(decrypted, "Active")
                .orElse(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void mapBoToEntity(ServiceClientBO bo, ServiceClient entity) {
        entity.setName(bo.getName());
        entity.setChurchName(bo.getChurchName());
        entity.setEmail(bo.getEmail());
        entity.setPhone(bo.getPhone());
        entity.setAddressLine1(bo.getAddressLine1());
        entity.setAddressLine2(bo.getAddressLine2());
        entity.setCity(bo.getCity());
        entity.setState(bo.getState());
        entity.setCountry(bo.getCountry() != null && !bo.getCountry().isBlank()
                ? bo.getCountry() : "USA");
        entity.setPinCode(bo.getPinCode());
        entity.setWebsiteUrl(bo.getWebsiteUrl());
        entity.setFacebookUrl(bo.getFacebookUrl());
        entity.setInstagramUrl(bo.getInstagramUrl());
        entity.setYoutubeUrl(bo.getYoutubeUrl());
        entity.setActivePeriod(bo.getActivePeriod());

        String unit = (bo.getActivePeriodUnit() != null && !bo.getActivePeriodUnit().isBlank())
                ? bo.getActivePeriodUnit() : "MONTHS";
        entity.setActivePeriodUnit(unit);

        LocalDate startDate = (bo.getStartDate() != null && !bo.getStartDate().isBlank())
                ? LocalDate.parse(bo.getStartDate())
                : LocalDate.now();
        entity.setStartDate(startDate);

        if (bo.getActivePeriod() != null && bo.getActivePeriod() > 0) {
            LocalDate endDate = unit.equalsIgnoreCase("YEARS")
                    ? startDate.plusYears(bo.getActivePeriod())
                    : startDate.plusMonths(bo.getActivePeriod());
            entity.setEndDate(endDate);
        }

        entity.setPaymentStatus(bo.getPaymentStatus() != null ? bo.getPaymentStatus() : "PENDING");
        entity.setSubscriptionType(bo.getSubscriptionType() != null ? bo.getSubscriptionType() : "FREE");
        entity.setNote(bo.getNote());
        entity.setStatus(bo.getStatus() != null && !bo.getStatus().isBlank()
                ? bo.getStatus() : "Active");
    }

    private String buildApprovalEmail(String name, String link) {
        String safeName = escapeHtml(name);
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>Registration Approved</title></head>"
             + "<body style='margin:0;padding:0;background-color:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background-color:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:520px;background:#ffffff;border-radius:16px;"
             +          "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             + "<tr><td style='background-color:#3a5a9b;padding:32px 40px;text-align:center;'>"
             + "<h1 style='color:#ffffff;font-size:22px;margin:0;'>Church Genius Pro</h1></td></tr>"
             + "<tr><td style='padding:32px 40px;'>"
             + "<p style='font-size:16px;color:#333;'>Dear " + safeName + ",</p>"
             + "<p style='font-size:15px;color:#333;'>Your church registration has been approved!</p>"
             + "<p style='font-size:15px;color:#333;'>Please use the link below to complete your setup:</p>"
             + "<p style='text-align:center;margin:24px 0;'>"
             + "<a href='" + link + "' style='background-color:#3a5a9b;color:#fff;padding:12px 28px;"
             +   "border-radius:8px;text-decoration:none;font-size:15px;'>Complete Registration</a></p>"
             + "<p style='font-size:13px;color:#999;'>Or copy this link: " + link + "</p>"
             + "</td></tr>"
             + "<tr><td style='background-color:#f5f6fa;padding:16px 40px;text-align:center;'>"
             + "<p style='font-size:12px;color:#999;margin:0;'>&copy; Church Genius Pro</p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }

    private String buildReapprovalEmail(String name, String link) {
        String safeName = escapeHtml(name);
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><title>New Registration Link</title></head>"
             + "<body style='font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;"
             +   "background:#f5f6fa;margin:0;padding:40px 20px;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'><tr><td align='center'>"
             + "<table style='max-width:520px;background:#fff;border-radius:16px;"
             +   "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             + "<tr><td style='background:#3a5a9b;padding:32px 40px;text-align:center;'>"
             + "<h1 style='color:#fff;font-size:22px;margin:0;'>Church Genius Pro</h1></td></tr>"
             + "<tr><td style='padding:32px 40px;'>"
             + "<p style='font-size:16px;color:#333;'>Dear " + safeName + ",</p>"
             + "<p style='font-size:15px;color:#333;'>A new registration link has been generated for your church.</p>"
             + "<p style='text-align:center;margin:24px 0;'>"
             + "<a href='" + link + "' style='background:#3a5a9b;color:#fff;padding:12px 28px;"
             +   "border-radius:8px;text-decoration:none;font-size:15px;'>Register Now</a></p>"
             + "<p style='font-size:13px;color:#999;'>Or copy: " + link + "</p>"
             + "</td></tr></table></td></tr></table></body></html>";
    }

    private String buildApprovalWhatsAppMessage(String name, String link) {
        return "Hello " + name + ",\n\nYour Church Genius Pro registration has been approved!\n"
             + "Please use the following link to complete your setup:\n" + link
             + "\n\nThank you,\nChurch Genius Pro";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
