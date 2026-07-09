package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Address;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.model.ChurchRegistrationBO;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ChurchRegistrationService {

    private final ChurchRegistrationRepository churchRegistrationRepository;
    private final EmailService emailService;

    public ChurchRegistrationService(ChurchRegistrationRepository churchRegistrationRepository,
                                     EmailService emailService) {
        this.churchRegistrationRepository = churchRegistrationRepository;
        this.emailService                 = emailService;
    }

    /**
     * Persists the church-registration form data to the database.
     * No email is sent here; the calling controller is responsible for
     * sending the OTP and the post-registration confirmation email.
     */
    public ChurchRegistration save(ChurchRegistrationBO bo) {
        ChurchRegistration entity = new ChurchRegistration();

        // Use the clientId from the form (always set from the encrypted URL parameter).
        // Fall back to a random UUID only if somehow missing.
        String clientId = (bo.getClientId() != null && !bo.getClientId().isBlank())
                ? bo.getClientId()
                : UUID.randomUUID().toString();
        entity.setClientId(clientId);

        entity.setFirstName(bo.getFirstName());
        entity.setLastName(bo.getLastName());
        entity.setChurchName(bo.getChurchName());
        entity.setEmail(bo.getEmail());
        entity.setPhone(bo.getPhone());
        entity.setEin(bo.getEin());
        entity.setWebsiteUrl(bo.getWebsiteUrl());
        entity.setFacebookUrl(bo.getFacebookUrl());
        entity.setInstagramUrl(bo.getInstagramUrl());
        entity.setYoutubeUrl(bo.getYoutubeUrl());
        entity.setDeclarationCheck(bo.getDeclarationCheck());

        // ── Build and attach Address ──────────────────────────────────────
        Address address = new Address();
        address.setAddress1(bo.getAddress1());
        address.setAddress2(bo.getAddress2());
        address.setCity(bo.getCity());
        address.setState(bo.getState());
        address.setPinCode(bo.getPinCode());
        address.setType(Address.AddressType.CHURCH);
        entity.setAddress(address);

        return churchRegistrationRepository.save(entity);
    }

    // ── Email builders ────────────────────────────────────────────────────────

    /**
     * Sends a confirmation email after the church has been fully registered
     * (form saved + signup record created). Instructs the recipient that they
     * can now log in with the credentials they created.
     */
    public void sendConfirmationEmail(String email, String firstName, String churchName) throws Exception {
        String safeName = (churchName != null && !churchName.isBlank()) ? churchName : "Church Genius Pro";
        String html = buildConfirmationEmail(firstName, safeName);
        emailService.sendGenericEmail(
                email,
                "Your Church Has Been Successfully Registered – " + safeName,
                html);
    }

    private String buildConfirmationEmail(String firstName, String churchName) {
        String safeName   = escapeHtml(firstName);
        String safeChurch = escapeHtml(churchName);
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>Registration Complete</title></head>"
             + "<body style='margin:0;padding:0;background-color:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background-color:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:520px;background:#ffffff;border-radius:16px;"
             +          "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             // Header
             + "<tr><td style='background-color:#673147;padding:32px 40px;text-align:center;'>"
             +   "<p style='margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.5px;'>" + safeChurch + "</p>"
             +   "<p style='margin:8px 0 0;font-size:13px;color:rgba(255,255,255,0.75);'>Registration Complete</p>"
             + "</td></tr>"
             // Body
             + "<tr><td style='padding:36px 40px;'>"
             +   "<p style='margin:0 0 16px;font-size:16px;color:#1a1a2e;font-weight:600;'>Welcome, " + safeName + "!</p>"
             +   "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>"
             +     "Your church has been successfully registered with <strong>" + safeChurch + "</strong>.</p>"
             +   "<p style='margin:0 0 28px;font-size:14px;color:#555;line-height:1.7;'>"
             +     "You can now log in to the application using the username and password "
             +     "you created during registration.</p>"
             +   "<div style='background:#f5eaef;border-radius:8px;padding:16px 20px;margin-bottom:24px;"
             +              "border-left:4px solid #673147;'>"
             +     "<p style='margin:0;font-size:13px;color:#673147;font-weight:600;'>"
             +       "&#x2705; Your account is active and ready to use.</p>"
             +   "</div>"
             + "</td></tr>"
             // Footer
             + "<tr><td style='background-color:#f8f9ff;padding:20px 40px;"
             +             "border-top:1px solid #e8eaf6;text-align:center;'>"
             +   "<p style='margin:0;font-size:12px;color:#aaa;line-height:1.6;'>"
             +     "This email was sent by <strong>" + safeChurch + "</strong>.<br/>"
             +     "If you did not register, please contact support.</p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
