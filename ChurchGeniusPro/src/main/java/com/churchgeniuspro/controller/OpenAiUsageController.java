package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Page-facing OpenAI usage status. The income / expense / home pages poll this
 * to decide whether to enable the voice button and the OpenAI-Vision upload
 * controls. Scoped to the logged-in church's {@code appClientId}.
 */
@RestController
public class OpenAiUsageController {

    private final OpenAiUsageService usageService;

    public OpenAiUsageController(OpenAiUsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping("/api/openai-usage/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            // No church context — report everything off so buttons stay disabled.
            Map<String, Object> off = new LinkedHashMap<>();
            off.put("voiceAvailable",  false);
            off.put("visionAvailable", false);
            return ResponseEntity.ok(off);
        }
        return ResponseEntity.ok(usageService.statusMap(clientId));
    }
}
