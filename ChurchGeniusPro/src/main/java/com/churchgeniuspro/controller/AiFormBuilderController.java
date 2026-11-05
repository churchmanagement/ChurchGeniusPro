package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.write.AiFormDraftService;
import com.churchgeniuspro.util.AppClock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Phase 3 — {@code POST /api/ai/forms/draft {description}}: describe-to-build for the Event / registration form.
 * Returns validated form JSON ({@code event}, {@code questions}, {@code unsupported}) that the page puts into the
 * form; the staff member still clicks Save. Same gates as {@link AiWritingController}; plan feature {@code aiWrite}
 * on /api/ai/forms; audited (channel FORMS). {@code POST /api/ai/forms/validate} checks a form JSON against the
 * same closed schema without calling a model (an unknown field type → 400).
 */
@RestController
public class AiFormBuilderController {

    private final AiFormDraftService forms;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public AiFormBuilderController(AiFormDraftService forms) { this.forms = forms; }

    @PostMapping("/api/ai/forms/draft")
    public ResponseEntity<Map<String, Object>> draft(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = AiWritingController.gate(req, "The AI form builder");
        if (g != null) return g;
        String description = body == null || body.get("description") == null ? "" : String.valueOf(body.get("description"));
        AiAuditService.intent(req, "forms.draft");
        Map<String, Object> d;
        try {
            if (!forms.isEnabled()) return ResponseEntity.status(503).body(Map.of("error", "The AI form builder is not configured on this server."));
            d = forms.draft(description, AppClock.today());
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        if (d == null) return ResponseEntity.status(502).body(Map.of("error", "The assistant couldn't draft that form just now. Please try again or fill the form by hand."));
        Map<String, Object> out = new LinkedHashMap<>(d);
        out.put("draft", true);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/ai/forms/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = AiWritingController.gate(req, "The AI form builder");
        if (g != null) return g;
        AiAuditService.intent(req, "forms.validate");
        try {
            Map<String, Object> ok = AiFormDraftService.validate(mapper.valueToTree(body == null ? Map.of() : body));
            Map<String, Object> out = new LinkedHashMap<>(ok); out.put("valid", true);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", e.getMessage()));
        }
    }
}
