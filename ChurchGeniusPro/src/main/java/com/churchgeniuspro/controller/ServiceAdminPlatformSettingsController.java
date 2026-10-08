package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.PlatformSettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service Admin → Platform Settings: the Support Email.
 *
 * <p>The Support Email is the one inbox for mail the platform sends to its own
 * operators — Trial Request notifications, Support Tickets and Demo Reminders
 * ({@link PlatformSettingService#supportEmail()}). Blank means the default,
 * {@value PlatformSettingService#DEFAULT_SUPPORT_EMAIL}. A save is live on the next
 * send; no restart.
 */
@RestController
public class ServiceAdminPlatformSettingsController {

    private final PlatformSettingService settings;

    public ServiceAdminPlatformSettingsController(PlatformSettingService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/serviceadmin/platform-settings")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("supportEmail", settings.supportEmail());
        out.put("defaultSupportEmail", PlatformSettingService.DEFAULT_SUPPORT_EMAIL);
        return ResponseEntity.ok(out);
    }

    @PutMapping("/api/serviceadmin/platform-settings")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        if (body == null || !body.containsKey("supportEmail")) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Nothing to save."));
        }
        String email = body.get("supportEmail") == null ? null : String.valueOf(body.get("supportEmail")).trim();
        String invalid = PlatformSettingService.validateEmail(email);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", invalid));
        }
        settings.set(PlatformSettingService.SUPPORT_EMAIL, email, actor(req));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("supportEmail", settings.supportEmail());
        out.put("message", "Support Email saved.");
        return ResponseEntity.ok(out);
    }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    static String actor(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object u = s == null ? null : s.getAttribute("serviceAdminUsername");
        return u == null ? "ServiceAdmin" : String.valueOf(u);
    }

    private static ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Not signed in as Service Admin"));
    }
}
