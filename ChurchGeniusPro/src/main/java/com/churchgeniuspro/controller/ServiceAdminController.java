package com.churchgeniuspro.controller;

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
import com.churchgeniuspro.util.EncryptionUtil;
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

    /** ETL data-import wizard (Phase 5 UI over the /api/serviceadmin/etl endpoints). */
    @GetMapping("/etlImport")
    public String etlImportPage() {
        return "forward:/etl-import.html";
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/serviceadmin/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        String username = body.get("username");
        String password = body.get("password");

        log.info("ServiceAdmin login attempt for username='{}'", username);

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            response.put("status",  "error");
            response.put("message", "Username and password are required.");
            return ResponseEntity.status(400).body(response);
        }

        Optional<ServiceAdmin> admin =
                serviceAdminRepository.findByUsernameAndDeletedFalse(username.trim());

        if (admin.isEmpty()) {
            log.warn("ServiceAdmin login failed — no active account found for username='{}'", username);
            response.put("status",  "error");
            response.put("message", "Invalid username or password.");
            return ResponseEntity.status(401).body(response);
        }

        if (!PasswordUtil.matches(password, admin.get().getPassword())) {
            log.warn("ServiceAdmin login failed — wrong password for username='{}'", username);
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

        // Set server-side session
        HttpSession session = request.getSession(true);
        session.setAttribute("serviceAdminId",       admin.get().getId());
        session.setAttribute("serviceAdminUsername", admin.get().getUsername());
        session.setAttribute("role",                 "ServiceAdmin");

        log.info("ServiceAdmin login successful for username='{}'", username);
        response.put("status",  "success");
        response.put("message", "Login successful");
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
            // Decrypt directly here (no @Transactional service call) so a DB
            // connection-pool delay cannot cause this public endpoint to hang.
            String decrypted = EncryptionUtil.decrypt(encryptedClientId);
            ServiceClient client = serviceClientRepository
                    .findByClientIdAndStatusAndDeleteFlagFalse(decrypted, "Active")
                    .orElse(null);
            if (client == null) {
                resp.put("status",  "invalid");
                resp.put("message", "This link is invalid, has expired, or the account is inactive.");
                return ResponseEntity.status(403).body(resp);
            }
            resp.put("status",   "valid");
            resp.put("clientId", client.getClientId());
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
    public ResponseEntity<Map<String, Object>> createClient(@RequestBody ServiceClientBO bo) {
        Map<String, Object> response = new HashMap<>();
        try {
            ServiceClient saved = serviceClientService.save(bo);
            response.put("status",  "success");
            response.put("message", "Client registered successfully.");
            response.put("data",    saved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Update Client ─────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/serviceadmin/clients/{id}")
    public ResponseEntity<Map<String, Object>> updateClient(@PathVariable Integer id,
                                                             @RequestBody ServiceClientBO bo) {
        Map<String, Object> response = new HashMap<>();
        try {
            ServiceClient updated = serviceClientService.update(id, bo);
            response.put("status",  "success");
            response.put("message", "Client updated successfully.");
            response.put("data",    updated);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Soft Delete ───────────────────────────────────────────────────────────

    @ResponseBody
    @PatchMapping("/api/serviceadmin/clients/{id}/delete")
    public ResponseEntity<Map<String, Object>> deleteClient(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();
        try {
            serviceClientService.softDelete(id);
            response.put("status",  "success");
            response.put("message", "Client deleted.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", e.getMessage());
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
            response.put("message", e.getMessage());
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
            response.put("message", e.getMessage());
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
            response.put("message", e.getMessage());
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
            response.put("message", e.getMessage());
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
            BackupConfig saved    = backupService.saveConfig(intervalMonths, tableScope, notifyEmails);
            resp.put("status",         "success");
            resp.put("message",        "Backup configuration saved.");
            resp.put("nextRunDate",    saved.getNextRunDate() != null ? saved.getNextRunDate().toString() : null);
        } catch (Exception e) {
            resp.put("status",  "error");
            resp.put("message", e.getMessage());
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
            resp.put("message", e.getMessage());
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
            resp.put("message", e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
        return ResponseEntity.ok(resp);
    }
}
