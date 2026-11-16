package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.ErrorResponses;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.hibernate.BackupConfig;
import com.churchgeniuspro.hibernate.BackupLog;
import com.churchgeniuspro.hibernate.ServiceAdmin;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.ServiceAdminRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.BackupService;
import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.ServiceClientService;
import com.churchgeniuspro.util.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles Service Admin login, page routing, and client-registration CRUD.
 *
 * <p>Page routes (GET) forward to static HTML files.
 * REST endpoints (under {@code /api/serviceadmin/}) are annotated
 * with {@code @ResponseBody} so they serialize to JSON.
 */
@Controller
public class ServiceAdminController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminController.class);

    private final ServiceAdminRepository  serviceAdminRepository;
    private final ServiceClientService    serviceClientService;
    private final ServiceClientRepository serviceClientRepository;
    private final BackupService           backupService;
    private final OpenAiUsageService       openAiUsageService;
    private final ChurchVoiceSettingService voiceSettingService;

    public ServiceAdminController(ServiceAdminRepository  serviceAdminRepository,
                                  ServiceClientService    serviceClientService,
                                  ServiceClientRepository serviceClientRepository,
                                  BackupService           backupService,
                                  OpenAiUsageService      openAiUsageService,
                                  ChurchVoiceSettingService voiceSettingService) {
        this.serviceAdminRepository  = serviceAdminRepository;
        this.serviceClientService    = serviceClientService;
        this.serviceClientRepository = serviceClientRepository;
        this.backupService           = backupService;
        this.openAiUsageService      = openAiUsageService;
        this.voiceSettingService     = voiceSettingService;
    }

    // ── Page Routes ───────────────────────────────────────────────────────────

    @GetMapping("/serviceadminlogin")
    public String serviceAdminLoginPage() {
        return "forward:/serviceAdminLogin.html";
    }

    @GetMapping("/serviceadminhome")
    public String serviceAdminHomePage() {
        return "forward:/serviceadminhome.html";
    }

    /**
     * ETL data-import wizard. Migration Phase 5: the page is no longer a public static file —
     * it lives outside {@code static/} and is served only to a session carrying
     * {@code serviceAdminId} (the same check {@code ServiceAdminAuthFilter} applies to the
     * APIs). Anyone else is sent to the service-admin login.
     */
    @GetMapping("/etlImport")
    public ResponseEntity<byte[]> etlImportPage(HttpServletRequest request) throws java.io.IOException {
        jakarta.servlet.http.HttpSession s = request.getSession(false);
        if (s == null || s.getAttribute("serviceAdminId") == null) {
            log.warn("[ServiceAdmin] /etlImport refused — no service-admin session");
            return ResponseEntity.status(302).header("Location", "/serviceadminlogin").header("Cache-Control", "no-store").build();
        }
        byte[] html = new org.springframework.core.io.ClassPathResource("admin-pages/etl-import.html").getInputStream().readAllBytes();
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType("text/html;charset=UTF-8"))
            .header("Cache-Control", "no-store")
            .body(html);
    }

    /** The page's old static address now goes through the gate above. */
    @GetMapping("/etl-import.html")
    public ResponseEntity<Void> etlImportOldUrl() {
        return ResponseEntity.status(302).header("Location", "/etlImport").build();
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        String username = body.get("username");
        String password = body.get("password");

        log.info("ServiceAdmin login attempt for username='{}'", LoginProtectionService.mask(username));

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            response.put("status",  "error");
            response.put("message", "Username and password are required.");
            return ResponseEntity.status(400).body(response);
        }

        Optional<ServiceAdmin> admin =
                serviceAdminRepository.findByUsernameAndDeletedFalse(username.trim());

        if (admin.isEmpty()) {
            log.warn("ServiceAdmin login failed — no active account found for username='{}'", LoginProtectionService.mask(username));
            response.put("status",  "error");
            response.put("message", "Invalid username or password.");
            return ResponseEntity.status(401).body(response);
        }

        if (!PasswordUtil.matches(password, admin.get().getPassword())) {
            log.warn("ServiceAdmin login failed — wrong password for username='{}'", LoginProtectionService.mask(username));
            response.put("status",  "error");
            response.put("message", "Invalid username or password.");
            return ResponseEntity.status(401).body(response);
        }
        // Transparent migration: re-hash plaintext password on first successful login
        if (!PasswordUtil.isBCrypt(admin.get().getPassword())) {
            try {
                admin.get().setPassword(PasswordUtil.encode(password));
                serviceAdminRepository.save(admin.get());
                log.info("Migrated ServiceAdmin plaintext password for username='{}'", username);
            } catch (Exception ex) {
                log.warn("Could not migrate ServiceAdmin password for username='{}': {}", username, ex.getMessage());
            }
        }

        // Set server-side session. Rotate it first (as /login does): a service-admin
        // identity must never be layered onto a tenant session that already exists,
        // and a pre-authentication session id must not survive authentication.
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute("serviceAdminId",       admin.get().getId());
        session.setAttribute("serviceAdminUsername", admin.get().getUsername());
        session.setAttribute("role",                 "ServiceAdmin");
        session.setAttribute(com.churchgeniuspro.util.CredentialSessions.SERVICE_ADMIN_ATTR,
                com.churchgeniuspro.util.CredentialSessions.version(admin.get().getCredentialVersion()));

        log.info("ServiceAdmin login successful for username='{}'", username);
        response.put("status",  "success");
        response.put("message", "Login successful");
        return ResponseEntity.ok(response);
    }

    // ── Change Password ───────────────────────────────────────────────────────

    /**
     * Changes the Service Admin password. Self-authenticating (available from
     * the login page): the CURRENT password must be supplied and verified
     * before the change is applied, and the new password must satisfy the
     * application-wide {@link com.churchgeniuspro.util.PasswordPolicy}.
     *
     * <p>Body (JSON): {@code { username, currentPassword, newPassword }}
     */
    @ResponseBody
    @PostMapping("/api/serviceadmin/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        String username        = body.get("username");
        String currentPassword = body.get("currentPassword");
        String newPassword     = body.get("newPassword");

        if (username == null || username.isBlank()
                || currentPassword == null || currentPassword.isBlank()
                || newPassword == null || newPassword.isBlank()) {
            response.put("status",  "error");
            response.put("message", "Username, current password, and new password are required.");
            return ResponseEntity.status(400).body(response);
        }

        Optional<ServiceAdmin> admin =
                serviceAdminRepository.findByUsernameAndDeletedFalse(username.trim());
        if (admin.isEmpty() || !PasswordUtil.matches(currentPassword, admin.get().getPassword())) {
            log.warn("ServiceAdmin change-password failed — bad credentials for username='{}'", username);
            response.put("status",  "error");
            response.put("message", "Invalid username or current password.");
            return ResponseEntity.status(401).body(response);
        }

        // Application-wide password policy (length + upper/lower/digit/special)
        String policyError = com.churchgeniuspro.util.PasswordPolicy.validate(newPassword);
        if (policyError != null) {
            response.put("status",  "error");
            response.put("message", policyError);
            return ResponseEntity.status(400).body(response);
        }
        if (newPassword.equals(currentPassword)) {
            response.put("status",  "error");
            response.put("message", "New password must be different from the current password.");
            return ResponseEntity.status(400).body(response);
        }

        admin.get().setPassword(PasswordUtil.encode(newPassword));
        admin.get().markCredentialChanged();     // every open service-admin session for this login now ends
        serviceAdminRepository.save(admin.get());
        log.info("ServiceAdmin password changed for username='{}'", username);

        response.put("status",  "success");
        response.put("message", "Password changed successfully. Please log in with your new password.");
        return ResponseEntity.ok(response);
    }

    // ── Validate Encrypted Client ID (public — used by churchregistration.html) ─
    // NOTE: Mapped to /public/ (NOT /api/) so Spring Session JDBC never intercepts
    // this request for a session lookup.  Spring Session's SessionRepositoryFilter
    // runs on every request including unauthenticated /api/* ones and does a
    // PostgreSQL SELECT on SPRING_SESSION — when the pool is busy that SELECT
    // blocks indefinitely, causing the "Verifying your link…" hang.

    @ResponseBody
    @GetMapping("/public/churchregistration/validate")
    public ResponseEntity<Map<String, Object>> validateClientId(
            @RequestParam("clientId") String encryptedClientId) {
        Map<String, Object> resp = new HashMap<>();
        try {
            // The parameter is the registration token (random, minted at approval).
            ServiceClient client = encryptedClientId == null ? null : serviceClientRepository
                    .findByRegistrationTokenAndStatusAndDeleteFlagFalse(encryptedClientId.trim(), "Active")
                    .orElse(null);
            if (client == null) {
                resp.put("status",  "invalid");
                resp.put("message", "This link is invalid, has expired, or the account is inactive.");
                return ResponseEntity.status(403).body(resp);
            }
            resp.put("status",   "valid");
            // The page carries this value into status/prefill/initiate/verify. It is the
            // registration token, not the tenant id — the server resolves it each time.
            resp.put("clientId", client.getRegistrationToken());
            resp.put("name",       client.getName());
            resp.put("churchName", client.getChurchName());
            resp.put("email",      client.getEmail());
            resp.put("phone",      client.getPhone());
            resp.put("addressLine1", client.getAddressLine1());
            resp.put("addressLine2", client.getAddressLine2());
            resp.put("city",       client.getCity());
            resp.put("state",      client.getState());
            resp.put("country",    client.getCountry());
            resp.put("pinCode",    client.getPinCode());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("validateClientId failed: {}", e.getMessage());
            resp.put("status",  "invalid");
            resp.put("message", "This link is invalid or has expired.");
            return ResponseEntity.status(403).body(resp);
        }
    }

    /**
     * Customer discounts (promotions phase 2). Field-injected and null-checked so this
     * controller's construction sites are unchanged; without it every client action
     * behaves exactly as before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.ClientPromotionService clientPromotions;

    /** Test seam. */
    public void setClientPromotions(com.churchgeniuspro.service.ClientPromotionService s) { this.clientPromotions = s; }

    /** Promotions phase 5: the "promotional pricing applied" email after Convert. Optional. */
    private com.churchgeniuspro.service.PromotionExpiryScheduler promotionMails;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromotionMails(com.churchgeniuspro.service.PromotionExpiryScheduler s) { this.promotionMails = s; }

    // ── Client List ───────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/serviceadmin/clients")
    public ResponseEntity<Map<String, Object>> listClients() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("data",   serviceClientService.getAll());
        return ResponseEntity.ok(response);
    }

    // ── Register Client ───────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/clients")
    public ResponseEntity<Map<String, Object>> createClient(@RequestBody ServiceClientBO bo,
                                                            jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> response = new HashMap<>();
        try {
            ServiceClient saved = serviceClientService.save(bo, ServiceAdminPlatformSettingsController.actor(req));
            response.put("status",  "success");
            response.put("message", "Client registered successfully.");
            response.put("data",    saved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Update Client ─────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/serviceadmin/clients/{id}")
    public ResponseEntity<Map<String, Object>> updateClient(@PathVariable Integer id,
                                                             @RequestBody ServiceClientBO bo,
                                                             jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> response = new HashMap<>();
        try {
            // A plan / billing-frequency change on a client with a discount needs the Service
            // Admin's decision first (keep it when still eligible, or cancel it). Nothing is saved until then.
            if (clientPromotions != null) clientPromotions.checkEdit(id, bo);
            ServiceClient updated = serviceClientService.update(id, bo, ServiceAdminPlatformSettingsController.actor(req));
            if (clientPromotions != null) {
                clientPromotions.afterEdit(id, bo, updated, ServiceAdminPlatformSettingsController.actor(req));
            }
            response.put("status",  "success");
            response.put("message", "Client updated successfully.");
            response.put("data",    updated);
            return ResponseEntity.ok(response);
        } catch (com.churchgeniuspro.service.ClientPromotionService.DecisionRequired e) {
            // Not an error: the page asks Keep / Cancel and resends with promotionDecision.
            response.put("status",   "confirm");
            response.put("code",     "PROMOTION_DECISION_REQUIRED");
            response.put("eligible", e.eligible());
            response.put("message",  e.getMessage());
            return ResponseEntity.status(409).body(response);
        } catch (ServiceClientService.StartDateConfirmationRequired e) {
            // Not an error: the page shows this and resends with confirmStartDateChange=true.
            response.put("status",  "confirm");
            response.put("code",    "START_DATE_CONFIRMATION_REQUIRED");
            response.put("message", e.getMessage());
            return ResponseEntity.status(409).body(response);
        } catch (IllegalArgumentException e) {
            response.put("status",  "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Extend trial ──────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/clients/{id}/extend-trial")
    public ResponseEntity<Map<String, Object>> extendTrial(@PathVariable Integer id,
                                                           @RequestBody Map<String, Object> body,
                                                           jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object v = body == null ? null : body.get("endDate");
            java.time.LocalDate end;
            try {
                end = (v == null || String.valueOf(v).isBlank()) ? null : java.time.LocalDate.parse(String.valueOf(v).trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("Enter the new end date as YYYY-MM-DD.");
            }
            ServiceClient saved = serviceClientService.extendTrial(id, end, ServiceAdminPlatformSettingsController.actor(req));
            response.put("status",  "success");
            response.put("message", "Trial extended. The new end date is " + saved.getEndDate()
                                  + "; access closes at the start of that day.");
            response.put("data",    saved);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("status",  "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Convert a Trial-plan client to a paid plan ───────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/clients/{id}/convert")
    public ResponseEntity<Map<String, Object>> convertClient(@PathVariable Integer id,
                                                             @RequestBody Map<String, Object> body,
                                                             jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object rid = body.get("requestId");
            ServiceClientService.ConvertCommand cmd = new ServiceClientService.ConvertCommand(
                    str(body.get("planCode")), str(body.get("billingFrequency")), str(body.get("customPrice")),
                    str(body.get("startDate")), str(body.get("endDate")), str(body.get("paymentStatus")),
                    Boolean.TRUE.equals(body.get("sendConfirmation")) || "true".equals(String.valueOf(body.get("sendConfirmation"))),
                    rid == null || String.valueOf(rid).isBlank() ? null : Long.valueOf(String.valueOf(rid)));
            // A trial with a waiting discount: the Service Admin must have chosen Apply or
            // Do not apply. Validated here, before anything is converted; written after.
            com.churchgeniuspro.service.ClientPromotionService.ConversionPlan promo = clientPromotions == null ? null
                    : clientPromotions.prepareConversion(id, conversionChoice(body.get("promotion")),
                            cmd.planCode(), cmd.billingFrequency(), cmd.customPrice(), cmd.startDate());
            ServiceClientService.ConvertResult r = serviceClientService.convert(id, cmd,
                    ServiceAdminPlatformSettingsController.actor(req));
            String promoNote = "";
            if (promo != null) {
                try {
                    clientPromotions.completeConversion(promo, r.client(), ServiceAdminPlatformSettingsController.actor(req));
                    promoNote = promo.apply()
                            ? " The discount was applied from " + promo.start() + " through " + promo.end() + "."
                            : " The discount was not applied.";
                    if (promo.apply() && cmd.sendConfirmation() && promotionMails != null
                            && promotionMails.sendAppliedConfirmation(r.client())) {
                        promoNote += " The church was emailed its promotional price and end date.";
                    }
                } catch (Exception e) {
                    log.error("Conversion of client {} succeeded but its discount could not be recorded", id, e);
                    promoNote = " The discount could not be recorded — open Discount for this client to review it.";
                }
            }
            response.put("status",  "success");
            response.put("message", "Converted to the " + r.planName() + " plan. The church keeps its account, logins and data."
                    + promoNote
                    + (r.emailSent() ? " A confirmation email was sent to " + r.client().getEmail() + "." : "")
                    + (r.emailError() != null ? " " + r.emailError() : ""));
            response.put("emailSent", r.emailSent());
            response.put("data",    r.client());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("status",  "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(response);
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /** The Convert dialog's choice for a trial's waiting discount ({@code promotion} in the request), or null. */
    private static com.churchgeniuspro.service.ClientPromotionService.ConversionChoice conversionChoice(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        com.churchgeniuspro.service.ClientPromotionService.Terms adjust = null;
        if (m.get("adjust") instanceof Map<?, ?> a) {
            Integer amount = null;
            String n = str(a.get("durationAmount"));
            if (n != null) {
                try { amount = Integer.valueOf(n.contains(".") ? n.substring(0, n.indexOf('.')) : n); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Enter the duration as a number."); }
            }
            adjust = new com.churchgeniuspro.service.ClientPromotionService.Terms(str(a.get("discountType")),
                    str(a.get("discountValue")), amount, str(a.get("durationUnit")), str(a.get("endDate")));
        }
        return new com.churchgeniuspro.service.ClientPromotionService.ConversionChoice(
                str(m.get("decision")), str(m.get("reason")), adjust, str(m.get("notes")));
    }

    // ── Soft Delete ───────────────────────────────────────────────────────────

    @ResponseBody
    @PatchMapping("/api/serviceadmin/clients/{id}/delete")
    public ResponseEntity<Map<String, Object>> deleteClient(@PathVariable Integer id,
                                                            jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> response = new HashMap<>();
        try {
            serviceClientService.softDelete(id);
            // A deleted client's live discount is cancelled (history kept). Never fails the delete.
            if (clientPromotions != null) {
                clientPromotions.cancelForDeletedClient(id, ServiceAdminPlatformSettingsController.actor(req));
            }
            response.put("status",  "success");
            response.put("message", "Client deleted.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── OpenAI usage limits (voice + vision) per church ─────────────────────────

    /** Resolves the {@code clientId} for a {@code service_client.id}, or null. */
    private String clientIdForServiceClient(Integer id) {
        return serviceClientRepository.findById(id)
                .map(ServiceClient::getClientId).orElse(null);
    }

    /** Current OpenAI limits + usage for one church. */
    @ResponseBody
    @GetMapping("/api/serviceadmin/clients/{id}/openai")
    public ResponseEntity<Map<String, Object>> getOpenAiUsage(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();
        String clientId = clientIdForServiceClient(id);
        if (clientId == null) {
            response.put("status", "error");
            response.put("message", "Client not found.");
            return ResponseEntity.status(404).body(response);
        }
        response.put("status", "success");
        response.put("data",   openAiUsageService.adminMap(clientId));
        return ResponseEntity.ok(response);
    }

    /** Update limits / enable flags / image-control settings for one church. */
    @ResponseBody
    @PutMapping("/api/serviceadmin/clients/{id}/openai")
    public ResponseEntity<Map<String, Object>> updateOpenAiUsage(@PathVariable Integer id,
                                                                 @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        String clientId = clientIdForServiceClient(id);
        if (clientId == null) {
            response.put("status", "error");
            response.put("message", "Client not found.");
            return ResponseEntity.status(404).body(response);
        }
        try {
            openAiUsageService.updateConfig(clientId, body);
            response.put("status",  "success");
            response.put("message", "OpenAI settings updated.");
            response.put("data",    openAiUsageService.adminMap(clientId));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Per-church Voice feature on/off controls ────────────────────────────────

    /** Current Voice feature flags (raw + effective) for one church. */
    @ResponseBody
    @GetMapping("/api/serviceadmin/clients/{id}/voice")
    public ResponseEntity<Map<String, Object>> getVoiceSettings(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();
        String clientId = clientIdForServiceClient(id);
        if (clientId == null) {
            response.put("status", "error");
            response.put("message", "Client not found.");
            return ResponseEntity.status(404).body(response);
        }
        Map<String, Object> data = new HashMap<>(voiceSettingService.rawMap(clientId));
        data.put("effective", voiceSettingService.effectiveMap(clientId));
        response.put("status", "success");
        response.put("data",   data);
        return ResponseEntity.ok(response);
    }

    /** Save Voice feature flags for one church (dependency rules applied server-side). */
    @ResponseBody
    @PutMapping("/api/serviceadmin/clients/{id}/voice")
    public ResponseEntity<Map<String, Object>> updateVoiceSettings(@PathVariable Integer id,
                                                                   @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        String clientId = clientIdForServiceClient(id);
        if (clientId == null) {
            response.put("status", "error");
            response.put("message", "Client not found.");
            return ResponseEntity.status(404).body(response);
        }
        try {
            Map<String, Object> raw = new HashMap<>(voiceSettingService.save(clientId, body));
            raw.put("effective", voiceSettingService.effectiveMap(clientId));
            response.put("status",  "success");
            response.put("message", "Voice settings updated.");
            response.put("data",    raw);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(response);
        }
    }

    /** Reset the voice-minutes counter for one church. */
    @ResponseBody
    @PostMapping("/api/serviceadmin/clients/{id}/openai/reset-voice")
    public ResponseEntity<Map<String, Object>> resetVoice(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();
        String clientId = clientIdForServiceClient(id);
        if (clientId == null) {
            response.put("status", "error");
            response.put("message", "Client not found.");
            return ResponseEntity.status(404).body(response);
        }
        openAiUsageService.resetVoice(clientId);
        response.put("status",  "success");
        response.put("message", "Voice usage reset.");
        response.put("data",    openAiUsageService.adminMap(clientId));
        return ResponseEntity.ok(response);
    }

    /** Reset the Vision-uploads counter for one church. */
    @ResponseBody
    @PostMapping("/api/serviceadmin/clients/{id}/openai/reset-vision")
    public ResponseEntity<Map<String, Object>> resetVision(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();
        String clientId = clientIdForServiceClient(id);
        if (clientId == null) {
            response.put("status", "error");
            response.put("message", "Client not found.");
            return ResponseEntity.status(404).body(response);
        }
        openAiUsageService.resetVision(clientId);
        response.put("status",  "success");
        response.put("message", "Vision usage reset.");
        response.put("data",    openAiUsageService.adminMap(clientId));
        return ResponseEntity.ok(response);
    }

    // ── Reapprove Client ──────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/clients/{id}/reapprove")
    public ResponseEntity<Map<String, Object>> reapproveClient(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();
        try {
            serviceClientService.reapprove(id);
            response.put("status",  "success");
            response.put("message", "New registration link sent. Previous link deactivated.");
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
        }
        return ResponseEntity.ok(response);
    }

    // ── Approve Client ────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/clients/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveClient(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();
        try {
            serviceClientService.approve(id);
            response.put("status",  "success");
            response.put("message", "Approved.");
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", ErrorResponses.message(log, "The request could not be completed", e));
        }
        return ResponseEntity.ok(response);
    }

    // ── Backup Config — GET ───────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/serviceadmin/backup/config")
    public ResponseEntity<Map<String, Object>> getBackupConfig() {
        BackupConfig cfg = backupService.getConfig();
        Map<String, Object> resp = new HashMap<>();
        resp.put("status",         "success");
        resp.put("intervalMonths", cfg.getIntervalMonths());
        resp.put("tableScope",     cfg.getTableScope());
        resp.put("retentionMonths", cfg.getRetentionMonths());
        resp.put("notifyEmails",   cfg.getNotifyEmails() != null ? cfg.getNotifyEmails() : "");
        resp.put("nextRunDate",    cfg.getNextRunDate() != null ? cfg.getNextRunDate().toString() : null);
        resp.put("lastRunDate",    cfg.getLastRunDate() != null ? cfg.getLastRunDate().toString() : null);
        return ResponseEntity.ok(resp);
    }

    // ── Backup Config — SAVE ──────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/backup/config")
    public ResponseEntity<Map<String, Object>> saveBackupConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new HashMap<>();
        try {
            int    intervalMonths = Integer.parseInt(String.valueOf(body.getOrDefault("intervalMonths", 0)));
            String tableScope     = String.valueOf(body.getOrDefault("tableScope", "ALL"));
            String notifyEmails   = String.valueOf(body.getOrDefault("notifyEmails", ""));
            // Absent -> 12 (one year). Never defaults to 0: a payload missing this field must
            // not be read as "keep backups for ever" or as "delete everything".
            int retentionMonths   = Integer.parseInt(String.valueOf(body.getOrDefault("retentionMonths", 12)));
            BackupConfig saved    = backupService.saveConfig(intervalMonths, tableScope, notifyEmails, retentionMonths);
            resp.put("status",          "success");
            resp.put("message",         "Backup configuration saved.");
            resp.put("nextRunDate",     saved.getNextRunDate() != null ? saved.getNextRunDate().toString() : null);
            resp.put("retentionMonths", saved.getRetentionMonths());
        } catch (Exception e) {
            resp.put("status",  "error");
            resp.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    // ── Backup — Run Now ──────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/backup/run")
    public ResponseEntity<Map<String, Object>> runBackupNow() {
        Map<String, Object> resp = new HashMap<>();
        try {
            BackupConfig cfg = backupService.getConfig();
            BackupLog    log = backupService.runBackup(cfg);
            resp.put("status",  "success");
            resp.put("result",  log.getStatus());
            resp.put("message", log.getMessage());
        } catch (Exception e) {
            resp.put("status",  "error");
            resp.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    // ── Backup Logs ───────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/serviceadmin/backup/logs")
    public ResponseEntity<Map<String, Object>> getBackupLogs() {
        List<BackupLog> logs = backupService.getRecentLogs();
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "success");
        resp.put("data",   logs);
        return ResponseEntity.ok(resp);
    }

    // ── Available Snapshots ───────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/serviceadmin/backup/snapshots")
    public ResponseEntity<Map<String, Object>> getSnapshots() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "success");
        resp.put("data",   backupService.getAvailableSnapshots());
        return ResponseEntity.ok(resp);
    }

    // ── Restore Snapshot ──────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/backup/restore")
    public ResponseEntity<Map<String, Object>> restoreSnapshot(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new HashMap<>();
        try {
            String dateSuffix = body.get("dateSuffix");
            Map<String, Object> result = backupService.restoreSnapshot(dateSuffix);
            resp.put("status",  "success");
            resp.putAll(result);
        } catch (IllegalArgumentException e) {
            resp.put("status",  "error");
            resp.put("message", e.getMessage());
            return ResponseEntity.status(400).body(resp);
        } catch (Exception e) {
            resp.put("status",  "error");
            resp.put("message", ErrorResponses.message(log, "The request could not be completed", e));
            return ResponseEntity.status(500).body(resp);
        }
        return ResponseEntity.ok(resp);
    }
}
