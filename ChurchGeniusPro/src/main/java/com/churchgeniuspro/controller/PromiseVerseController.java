package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PromiseVerse;
import com.churchgeniuspro.repository.PromiseVerseRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
public class PromiseVerseController {

    private final PromiseVerseRepository repo;

    public PromiseVerseController(PromiseVerseRepository repo) {
        this.repo = repo;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/promiseVerse")
    public String page(HttpServletRequest request) {
        String redirect = RoleGuard.requireAdmin(request);
        return redirect != null ? redirect : "forward:/promiseVerse.html";
    }

    // ── REST ──────────────────────────────────────────────────────────────────

    /** List all verses for the current organization. */
    @ResponseBody
    @GetMapping("/api/promise-verses")
    public ResponseEntity<?> list(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(repo.findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(clientId));
    }

    /** Save a new verse. */
    @ResponseBody
    @PostMapping("/api/promise-verses")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        Integer day = toInt(body.get("dayNumber"));
        if (day == null || day < 1 || day > 365)
            return err("Day number must be between 1 and 365.");

        if (repo.existsByClientIdAndDayNumberAndDeleteFlagFalse(clientId, day))
            return err("A verse for day " + day + " already exists.");

        PromiseVerse v = new PromiseVerse();
        v.setClientId(clientId);
        v.setDayNumber(day);
        v.setReference(str(body.get("reference")));
        v.setVerseText(str(body.get("verseText")));
        return ResponseEntity.ok(repo.save(v));
    }

    /** Update an existing verse. */
    @ResponseBody
    @PutMapping("/api/promise-verses/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        PromiseVerse v = repo.findById(id).orElse(null);
        if (v == null || !clientId.equals(v.getClientId()))
            return ResponseEntity.notFound().build();

        Integer day = toInt(body.get("dayNumber"));
        if (day == null || day < 1 || day > 365)
            return err("Day number must be between 1 and 365.");

        // Duplicate check excluding self
        Optional<PromiseVerse> existing = repo.findByClientIdAndDayNumber(clientId, day);
        if (existing.isPresent() && !existing.get().getId().equals(id))
            return err("A verse for day " + day + " already exists.");

        v.setDayNumber(day);
        v.setReference(str(body.get("reference")));
        v.setVerseText(str(body.get("verseText")));
        return ResponseEntity.ok(repo.save(v));
    }

    /** Soft-delete a verse. */
    @ResponseBody
    @DeleteMapping("/api/promise-verses/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        PromiseVerse v = repo.findById(id).orElse(null);
        if (v == null || !clientId.equals(v.getClientId()))
            return ResponseEntity.notFound().build();

        v.setDeleteFlag(true);
        repo.save(v);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    /** Return a random verse for the current org (used in email footers). */
    @ResponseBody
    @GetMapping("/api/promise-verses/random")
    public ResponseEntity<?> random(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return repo.findRandomByClientId(clientId)
                   .map(ResponseEntity::ok)
                   .orElse(ResponseEntity.noContent().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }

    private ResponseEntity<Map<String, String>> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
