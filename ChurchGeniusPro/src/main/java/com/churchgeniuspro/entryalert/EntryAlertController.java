package com.churchgeniuspro.entryalert;

import com.churchgeniuspro.ai.action.AiFinanceTools;
import com.churchgeniuspro.entryalert.EntryAlertService.Kind;
import com.churchgeniuspro.entryalert.EntryAlertService.Owner;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2026-11-09 — a login's own Income / Expense alert settings (spec §2). {@code GET|PUT /api/entry-alerts/{income|expense}}.
 *
 * <p>Only a login that may ADD that kind of entry (the Income / Expense page's own write rule — the same rule the
 * Genius Assistant uses) can read or change its alerts; each login sees and changes only its own row for the role it
 * is signed in with.
 */
@RestController
@RequestMapping("/api/entry-alerts")
public class EntryAlertController {

    private final EntryAlertService service;

    public EntryAlertController(EntryAlertService service) { this.service = service; }

    private static ResponseEntity<Map<String, Object>> err(int status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("error", message);
        return ResponseEntity.status(status).body(m);
    }

    /** Null = allowed; else the refusal. */
    private static ResponseEntity<Map<String, Object>> gate(HttpServletRequest req, Kind kind) {
        if (kind == null) return err(404, "NOT_FOUND", "Unknown entry type.");
        if (EntryAlertService.owner(req) == null) return err(401, "SIGN_IN", "Please sign in.");
        boolean may = kind == Kind.INCOME ? AiFinanceTools.mayCreateIncome(req) : AiFinanceTools.mayCreateExpense(req);
        if (!may) return err(403, "PERMISSION_DENIED", "Your login can't add " + (kind == Kind.INCOME ? "income" : "expenses") + ", so it has no alerts to set.");
        return null;
    }

    @GetMapping("/{kind}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String kind, HttpServletRequest req) {
        Kind k = Kind.of(kind);
        ResponseEntity<Map<String, Object>> deny = gate(req, k);
        if (deny != null) return deny;
        Owner o = EntryAlertService.owner(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", k.noun());
        m.put("role", o.role());
        m.put("settings", service.settings(o, k).toMap());
        m.put("maxDays", EntryAlertService.MAX_DAYS);
        return ResponseEntity.ok(m);
    }

    @PutMapping("/{kind}")
    public ResponseEntity<Map<String, Object>> put(@PathVariable String kind, @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest req) {
        Kind k = Kind.of(kind);
        ResponseEntity<Map<String, Object>> deny = gate(req, k);
        if (deny != null) return deny;
        Owner o = EntryAlertService.owner(req);
        Map<String, String> problems = service.save(o, k, body == null ? Map.of() : body);
        if (!problems.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", "INVALID_SETTINGS");
            m.put("error", problems.values().iterator().next());
            m.put("problems", problems);
            return ResponseEntity.badRequest().body(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("saved", true);
        m.put("kind", k.noun());
        m.put("role", o.role());
        m.put("settings", service.settings(o, k).toMap());
        return ResponseEntity.ok(m);
    }
}
