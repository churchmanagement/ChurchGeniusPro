package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PromiseVerse;
import com.churchgeniuspro.repository.PromiseVerseRepository;
import com.churchgeniuspro.service.DailyVerseSetupService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
public class PromiseVerseController {

    private final PromiseVerseRepository repo;

    /**
     * The one-click year loader. Field-injected and null-checked so this
     * controller's existing construction sites (and their tests) are unchanged;
     * without it the per-verse screen carries on exactly as before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DailyVerseSetupService setupService;

    /** Test seam — supply the loader without a Spring context. */
    public void setSetupService(DailyVerseSetupService s) { this.setupService = s; }

    public PromiseVerseController(PromiseVerseRepository repo) {
        this.repo = repo;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/promiseVerse")
    public String page(HttpServletRequest request) {
        String redirect = RoleGuard.requireAdmin(request);
        return redirect != null ? redirect : "forward:/promiseVerse.html";
    }

    /** Daily Verse Setup — load a whole year of verses at once. Same gate as above. */
    @GetMapping("/dailyVerseSetup")
    public String setupPage(HttpServletRequest request) {
        String redirect = RoleGuard.requireAdmin(request);
        return redirect != null ? redirect : "forward:/dailyVerseSetup.html";
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

        // 366, not 365: a leap year has a 29 February, and the year loader fills it.
        // Capping at 365 here would have made that day the one day of the year a
        // church could not edit by hand.
        Integer day = toInt(body.get("dayNumber"));
        if (day == null || day < 1 || day > 366)
            return err("Day number must be between 1 and 366.");

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

        // 366, not 365: a leap year has a 29 February, and the year loader fills it.
        // Capping at 365 here would have made that day the one day of the year a
        // church could not edit by hand.
        Integer day = toInt(body.get("dayNumber"));
        if (day == null || day < 1 || day > 366)
            return err("Day number must be between 1 and 366.");

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

    // ── Year setup ────────────────────────────────────────────────────────────

    /**
     * How much of a year this church has covered, plus a preview of the stock list.
     *
     * <p>The tenant comes from the session; a {@code clientId} in the request is
     * never read, so this can only ever describe the caller's own church.
     */
    @ResponseBody
    @GetMapping("/api/promise-verses/coverage")
    public ResponseEntity<?> coverage(@RequestParam(required = false) Integer year,
                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (setupService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Verse setup is unavailable."));
        }
        int y = year != null ? year : java.time.LocalDate.now().getYear();
        Map<String, Object> out = new LinkedHashMap<>(setupService.coverage(clientId, y));
        out.put("years", DailyVerseSetupService.selectableYears());
        return ResponseEntity.ok(out);
    }

    /**
     * Loads a whole year of verses into this church's list.
     *
     * <p>Body: {@code year}, and {@code mode} = {@code fill} (default — only the
     * empty days) or {@code replace} (overwrite every day). Admin only, and written
     * against the session's tenant.
     */
    @ResponseBody
    @PostMapping("/api/promise-verses/populate")
    public ResponseEntity<?> populate(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (setupService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Verse setup is unavailable."));
        }

        Map<String, Object> in = body == null ? Map.of() : body;
        Integer year = toInt(in.get("year"));
        if (year == null) year = java.time.LocalDate.now().getYear();
        DailyVerseSetupService.Mode mode = DailyVerseSetupService.Mode.of(str(in.get("mode")));

        try {
            return ResponseEntity.ok(setupService.populate(clientId, year, mode).asMap());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
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
