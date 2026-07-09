package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Handles church-logo upload, retrieval, and deletion.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET  /logo}               → {@code logo.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code POST   /api/logo/upload}  → upload / replace logo (multipart)</li>
 *   <li>{@code GET    /api/logo/image}   → serve raw image bytes</li>
 *   <li>{@code GET    /api/logo/meta}    → exists flag + file name</li>
 *   <li>{@code DELETE /api/logo}         → remove logo</li>
 * </ul>
 */
@Controller
public class ChurchLogoController {

    private final ChurchLogoRepository repo;

    public ChurchLogoController(ChurchLogoRepository repo) {
        this.repo = repo;
    }

    // ── Page route ────────────────────────────────────────────────────────────

    @GetMapping("/logo")
    public String page(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        return deny != null ? deny : "forward:/logo.html";
    }

    /**
     * Church Settings — the Church-role view of the same configuration page
     * (church name, address, website & social links). Reached from the
     * "Church Settings" button on {@code /viewusers}. Church role only.
     */
    @GetMapping("/churchSettings")
    public String churchSettingsPage(HttpServletRequest request) {
        String deny = RoleGuard.requireChurch(request);
        return deny != null ? deny : "forward:/logo.html";
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Accepts a multipart image upload and upserts the logo for the current org.
     * Only image/* content types are accepted; max size is governed by Spring's
     * {@code spring.servlet.multipart.max-file-size} property (default 1 MB —
     * override in application.properties if needed).
     */
    @ResponseBody
    @PostMapping(value = "/api/logo/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only image files (PNG, JPG, GIF, WebP) are allowed."));
        }

        try {
            ChurchLogo logo = repo.findByClientId(clientId).orElse(new ChurchLogo());
            logo.setClientId(clientId);
            logo.setLogoData(file.getBytes());
            logo.setContentType(contentType);
            logo.setOriginalFileName(file.getOriginalFilename());
            repo.save(logo);
            return ResponseEntity.ok(Map.of(
                    "success",  true,
                    "fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "",
                    "size",     file.getSize()
            ));
        } catch (IOException ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to store logo: " + ex.getMessage()));
        }
    }

    // ── Serve image bytes ─────────────────────────────────────────────────────

    /**
     * Returns the raw image bytes with the correct Content-Type header.
     * Returns 404 when no logo has been uploaded for the organization.
     */
    @ResponseBody
    @GetMapping("/api/logo/image")
    public ResponseEntity<byte[]> getImage(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        Optional<ChurchLogo> opt = repo.findByClientId(clientId);
        if (opt.isEmpty() || opt.get().getLogoData() == null) {
            return ResponseEntity.notFound().build();
        }

        ChurchLogo logo = opt.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        logo.getContentType() != null ? logo.getContentType() : "image/png"))
                .body(logo.getLogoData());
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    /**
     * Returns a lightweight metadata object:
     * <pre>{ "exists": true/false, "fileName": "...", "contentType": "..." }</pre>
     * Used by the UI to decide whether to show a preview or the upload prompt.
     */
    @ResponseBody
    @GetMapping("/api/logo/meta")
    public ResponseEntity<Map<String, Object>> getMeta(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        Optional<ChurchLogo> opt = repo.findByClientId(clientId);
        if (opt.isEmpty() || opt.get().getLogoData() == null) {
            return ResponseEntity.ok(Map.of("exists", false));
        }

        ChurchLogo logo = opt.get();
        return ResponseEntity.ok(Map.of(
                "exists",      true,
                "fileName",    logo.getOriginalFileName() != null ? logo.getOriginalFileName() : "",
                "contentType", logo.getContentType()     != null ? logo.getContentType()     : ""
        ));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Removes the uploaded logo for the current organization. */
    @ResponseBody
    @DeleteMapping("/api/logo")
    public ResponseEntity<Map<String, Object>> deleteLogo(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        repo.findByClientId(clientId).ifPresent(repo::delete);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
