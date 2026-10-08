package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.SongBookPublish;
import com.churchgeniuspro.service.SongBookService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public, no-login Song Book endpoint. Whitelisted via the {@code /api/public}
 * prefix in {@code AuthFilter}. Returns the immutable published snapshot for a
 * secure token; unpublished / unknown tokens get 404.
 */
@RestController
public class SongBookPublicController {

    private final SongBookService service;

    public SongBookPublicController(SongBookService service) {
        this.service = service;
    }

    /**
     * Live "Now Singing" / "Next Song" state for a published book, resolved by its
     * public token. The viewer polls this to update the banner / next-song badge
     * without a page refresh. Tenant-safe: the token maps to exactly one book of
     * one church, so no cross-tenant state is ever exposed. Unknown / unpublished
     * tokens get a neutral empty state (not a 404) so the poller can stay quiet.
     */
    @GetMapping("/api/public/songbook/live")
    public ResponseEntity<?> live(@RequestParam("t") String token) {
        SongBookPublish p = service.publicByToken(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(service.liveState(p));   // p == null → {currentVersion:0, current:null, next:null}
    }

    @GetMapping("/api/public/songbook")
    public ResponseEntity<?> view(@RequestParam("t") String token) {
        SongBookPublish p = service.publicByToken(token);
        if (p == null || p.getSnapshot() == null) {
            return ResponseEntity.status(404).body(Map.of("error", "This song book is not available."));
        }
        // best-effort view audit
        try { service.audit(p.getClientId(), "public", "Public", "PUBLIC_VIEW", null, p.getBookTitle(), null); }
        catch (Exception ignore) {}
        // snapshot is already a JSON document — return it verbatim (UTF-8 for Indic scripts)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")).body(p.getSnapshot());
    }

    /** Public advertisement page image for a published book (ad id from the snapshot). */
    @GetMapping("/api/public/songbook/ad-image")
    public ResponseEntity<?> adImage(@RequestParam("t") String token, @RequestParam("id") Long id) {
        SongBookPublish p = service.publicByToken(token);
        if (p == null) return ResponseEntity.notFound().build();
        com.churchgeniuspro.hibernate.SongBookAd a = service.getAd(p.getClientId(), id);
        if (a == null || a.getData() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType() == null ? "image/png" : a.getContentType()))
                .body(a.getData());
    }

    /** Public uploaded cover / last page image for a published book (kind = cover | last). */
    @GetMapping("/api/public/songbook/cover-image")
    public ResponseEntity<?> coverImage(@RequestParam("t") String token, @RequestParam("kind") String kind) {
        SongBookPublish p = service.publicByToken(token);
        if (p == null) return ResponseEntity.notFound().build();
        com.churchgeniuspro.hibernate.SongBookAsset a = service.getAsset(p.getClientId(), kind);
        if (a == null || a.getData() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType() == null ? "image/png" : a.getContentType()))
                .body(a.getData());
    }
}
