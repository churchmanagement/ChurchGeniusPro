package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FinalizedSection;
import com.churchgeniuspro.hibernate.Song;
import com.churchgeniuspro.hibernate.SongBookPublish;
import com.churchgeniuspro.hibernate.SongSection;
import com.churchgeniuspro.service.SongBookAccessService;
import com.churchgeniuspro.service.SongBookService;
import com.churchgeniuspro.service.SongExtractionService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Member/staff Song Book API. Reads require VIEW (or FULL); all mutations
 * require FULL access. Supports multiple sections/categories and multilingual
 * songs (per-song language), plus the curated finalized list and publishing.
 */
@RestController
@RequestMapping("/api/songbook")
public class SongBookController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SongBookController.class);

    private final SongBookService service;
    private final SongBookAccessService access;
    private final SongExtractionService extraction;
    private final com.churchgeniuspro.repository.SongBookRepository bookRepo;

    public SongBookController(SongBookService service, SongBookAccessService access,
                              SongExtractionService extraction,
                              com.churchgeniuspro.repository.SongBookRepository bookRepo) {
        this.service = service;
        this.access = access;
        this.extraction = extraction;
        this.bookRepo = bookRepo;
    }

    /* ─────────────────── access resolution ─────────────────── */

    /**
     * {@code clientId} is the DATA SCOPE the service operates on. For the
     * default Song Book it is the plain client id (so all pre-existing data
     * keeps working unchanged); for any other book it is the book-scoped id
     * {@code <clientId>#B<bookId>}. {@code realClientId} is the actual tenant
     * id, used for access checks and book management.
     */
    private record Ctx(String clientId, String realClientId, String level, String actor, String role) {}

    private Ctx ctx(HttpServletRequest req) {
        String clientId = SessionUtil.getAppClientId(req);
        String role = SessionUtil.getRole(req);
        String username = SessionUtil.getUsername(req);
        HttpSession s = req.getSession(false);
        Object mid = s == null ? null : s.getAttribute("memberId");
        String level, actor;
        if (mid != null && "Member".equalsIgnoreCase(String.valueOf(role))) {
            Long memberId = Long.valueOf(String.valueOf(mid));
            level = access.level(clientId, memberId);
            actor = "member:" + memberId;
        } else if (username != null || SessionUtil.isChurchAccount(req)) {
            level = "FULL";
            actor = username != null ? username : "church";
        } else {
            level = "NONE";
            actor = "anonymous";
        }
        // Resolve the active Song Book (optional ?bookId= on any endpoint;
        // absent → the default book, i.e. the original single-book behavior).
        String scope = clientId;
        if (clientId != null) {
            com.churchgeniuspro.hibernate.SongBook book = resolveBook(clientId, req.getParameter("bookId"));
            if (book != null && !book.isDefaultBook()) scope = scopeFor(clientId, book);
        }
        return new Ctx(scope, clientId, level, actor, role == null ? "" : role);
    }

    /* ─────────────────── multiple Song Books ─────────────────── */

    /** Default book name given to each church's original (legacy) Song Book. */
    private static final String DEFAULT_BOOK_NAME = "Musical Night";

    private static String scopeFor(String clientId, com.churchgeniuspro.hibernate.SongBook book) {
        return book.isDefaultBook() ? clientId : clientId + "#B" + book.getId();
    }

    /** Lists the church's books, lazily creating the default one on first use. */
    private synchronized List<com.churchgeniuspro.hibernate.SongBook> ensureBooks(String clientId) {
        List<com.churchgeniuspro.hibernate.SongBook> list = bookRepo.findByClientIdOrderBySortOrderAscIdAsc(clientId);
        if (list.isEmpty()) {
            com.churchgeniuspro.hibernate.SongBook def = new com.churchgeniuspro.hibernate.SongBook();
            def.setClientId(clientId);
            def.setName(DEFAULT_BOOK_NAME);
            def.setDefaultBook(true);
            def.setSortOrder(0);
            def.setCreatedAt(java.time.Instant.now());
            bookRepo.save(def);
            list = bookRepo.findByClientIdOrderBySortOrderAscIdAsc(clientId);
        }
        return list;
    }

    /** Resolve a bookId param to one of the church's books (default when absent/invalid). */
    private com.churchgeniuspro.hibernate.SongBook resolveBook(String clientId, String bookIdParam) {
        List<com.churchgeniuspro.hibernate.SongBook> books = ensureBooks(clientId);
        if (bookIdParam != null && !bookIdParam.isBlank()) {
            try {
                Long id = Long.valueOf(bookIdParam.trim());
                for (com.churchgeniuspro.hibernate.SongBook b : books) if (id.equals(b.getId())) return b;
            } catch (NumberFormatException ignore) {}
        }
        for (com.churchgeniuspro.hibernate.SongBook b : books) if (b.isDefaultBook()) return b;
        return books.isEmpty() ? null : books.get(0);
    }

    private static Map<String, Object> bookDto(com.churchgeniuspro.hibernate.SongBook b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("name", b.getName());
        m.put("defaultBook", b.isDefaultBook());
        m.put("sortOrder", b.getSortOrder());
        return m;
    }

    @GetMapping("/books")
    public ResponseEntity<?> books(HttpServletRequest req) {
        Ctx c = ctx(req);
        ResponseEntity<?> deny = needView(c); if (deny != null) return deny;
        return ResponseEntity.ok(ensureBooks(c.realClientId()).stream().map(SongBookController::bookDto).toList());
    }

    @PostMapping("/books")
    public ResponseEntity<?> addBook(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        String name = str(b.get("name"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Song Book name required"));
        List<com.churchgeniuspro.hibernate.SongBook> books = ensureBooks(c.realClientId());
        com.churchgeniuspro.hibernate.SongBook nb = new com.churchgeniuspro.hibernate.SongBook();
        nb.setClientId(c.realClientId());
        nb.setName(name);
        nb.setDefaultBook(false);
        nb.setSortOrder(books.stream().mapToInt(com.churchgeniuspro.hibernate.SongBook::getSortOrder).max().orElse(-1) + 1);
        nb.setCreatedAt(java.time.Instant.now());
        nb = bookRepo.save(nb);
        service.audit(c.realClientId(), c.actor(), c.role(), "BOOK_ADD", null, nb.getName(), null);
        return ResponseEntity.ok(bookDto(nb));
    }

    @PutMapping("/books/{id}")
    public ResponseEntity<?> renameBook(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        String name = str(b.get("name"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Song Book name required"));
        com.churchgeniuspro.hibernate.SongBook book = bookRepo.findByIdAndClientId(id, c.realClientId()).orElse(null);
        if (book == null) return ResponseEntity.status(404).body(Map.of("error", "Song Book not found"));
        book.setName(name);
        book = bookRepo.save(book);
        service.audit(c.realClientId(), c.actor(), c.role(), "BOOK_RENAME", null, book.getName(), null);
        return ResponseEntity.ok(bookDto(book));
    }

    @DeleteMapping("/books/{id}")
    public ResponseEntity<?> deleteBook(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        com.churchgeniuspro.hibernate.SongBook book = bookRepo.findByIdAndClientId(id, c.realClientId()).orElse(null);
        if (book == null) return ResponseEntity.status(404).body(Map.of("error", "Song Book not found"));
        if (book.isDefaultBook())
            return ResponseEntity.badRequest().body(Map.of("error", "The default Song Book cannot be deleted."));
        // Purge only the book-scoped rows — never the plain client id (legacy data).
        service.purgeScope(scopeFor(c.realClientId(), book), c.actor(), c.role());
        bookRepo.delete(book);
        service.audit(c.realClientId(), c.actor(), c.role(), "BOOK_DELETE", null, book.getName(), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private ResponseEntity<?> needView(Ctx c) {
        if (c.clientId() == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (!access.canView(c.level())) return ResponseEntity.status(403).body(Map.of("error", "No Song Book access"));
        return null;
    }
    private ResponseEntity<?> needEdit(Ctx c) {
        if (c.clientId() == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (!access.canEdit(c.level())) return ResponseEntity.status(403).body(Map.of("error", "Full Access required"));
        return null;
    }

    /* ─────────────────── reads ─────────────────── */

    @GetMapping("/access")
    public ResponseEntity<?> myAccess(HttpServletRequest req) {
        Ctx c = ctx(req);
        return ResponseEntity.ok(Map.of("level", c.level(),
                "canView", access.canView(c.level()), "canEdit", access.canEdit(c.level())));
    }

    @GetMapping("/songs")
    public ResponseEntity<?> songs(HttpServletRequest req) {
        Ctx c = ctx(req);
        ResponseEntity<?> deny = needView(c); if (deny != null) return deny;
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("level", c.level());
            out.put("canEdit", access.canEdit(c.level()));

            List<SongSection> secs = service.sections(c.clientId());
            Map<Long, String> secNames = new LinkedHashMap<>();
            List<Map<String, Object>> sections = new ArrayList<>();
            for (SongSection sec : secs) {
                secNames.put(sec.getId(), sec.getName());
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", sec.getId());
                sm.put("name", sec.getName());
                sm.put("sortOrder", sec.getSortOrder());
                List<Map<String, Object>> songs = new ArrayList<>();
                for (Song s : service.sectionSongs(c.clientId(), sec.getId())) songs.add(dto(s, sec.getName()));
                sm.put("songs", songs);
                sections.add(sm);
            }
            out.put("sections", sections);

            List<Map<String, Object>> finalizedSections = new ArrayList<>();
            for (FinalizedSection fs : service.finalizedSections(c.clientId())) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("id", fs.getId());
                fm.put("name", fs.getName());
                fm.put("sortOrder", fs.getSortOrder());
                List<Map<String, Object>> fsongs = new ArrayList<>();
                for (Song s : service.finalizedSectionSongs(c.clientId(), fs.getId()))
                    fsongs.add(dto(s, s.getSectionId() == null ? "" : secNames.getOrDefault(s.getSectionId(), "")));
                fm.put("songs", fsongs);
                finalizedSections.add(fm);
            }
            out.put("finalizedSections", finalizedSections);
            out.put("cover", service.coverJson(c.clientId()));
            out.put("customCover", service.hasAsset(c.clientId(), "cover"));
            out.put("customLast", service.hasAsset(c.clientId(), "last"));

            SongBookPublish p = service.publishState(c.clientId());
            Map<String, Object> pub = new LinkedHashMap<>();
            pub.put("published", p != null && p.isPublished());
            pub.put("bookTitle", p != null ? p.getBookTitle() : "");
            pub.put("token", p != null ? p.getToken() : null);
            pub.put("url", (p != null && p.isPublished() && p.getToken() != null) ? ("/songbook/view?t=" + p.getToken()) : null);
            out.put("publish", pub);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            LOG.error("Song Book /songs failed for clientId={}", c.clientId(), e);
            return ResponseEntity.status(500).body(Map.of("error",
                    "Song Book load failed: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
        }
    }

    /* ─────────────────── sections (FULL) ─────────────────── */

    @PostMapping("/sections")
    public ResponseEntity<?> addSection(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        String name = str(b.get("name"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Section name required"));
        return ResponseEntity.ok(secDto(service.createSection(c.clientId(), name, c.actor(), c.role())));
    }

    @PutMapping("/sections/{id}")
    public ResponseEntity<?> renameSection(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        String name = str(b.get("name"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Section name required"));
        return ResponseEntity.ok(secDto(service.renameSection(c.clientId(), id, name, c.actor(), c.role())));
    }

    @DeleteMapping("/sections/{id}")
    public ResponseEntity<?> deleteSection(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.deleteSection(c.clientId(), id, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/sections/reorder")
    public ResponseEntity<?> reorderSections(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.reorderSections(c.clientId(), ids(b.get("ids")), c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /* ─────────────────── song mutations (FULL) ─────────────────── */

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("sectionId") Long sectionId,
                                    @RequestParam(value = "language", required = false) String language,
                                    HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try {
            List<String> titles = extraction.extractTitles(file.getBytes(), file.getOriginalFilename());
            if (titles.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No song titles found in the file."));
            List<Song> added = service.addTitles(c.clientId(), sectionId, language, titles, c.actor(), c.role());
            return ResponseEntity.ok(Map.of("added", added.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read that file. Please upload a .txt, .doc, .docx or .pdf."));
        }
    }

    @PostMapping("/songs")
    public ResponseEntity<?> add(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        Long sectionId = lng(b.get("sectionId"));
        String title = str(b.get("title"));
        if (sectionId == null) return ResponseEntity.badRequest().body(Map.of("error", "Section required"));
        if (title.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Title required"));
        return ResponseEntity.ok(dto(service.addTitle(c.clientId(), sectionId, title, str(b.get("language")), c.actor(), c.role()), null));
    }

    @PutMapping("/songs/{id}")
    public ResponseEntity<?> edit(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        return ResponseEntity.ok(dto(service.editTitle(c.clientId(), id, str(b.get("title")), str(b.get("language")), c.actor(), c.role()), null));
    }

    @PostMapping("/songs/{id}/move")
    public ResponseEntity<?> move(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        Long target = lng(b.get("sectionId"));
        if (target == null) return ResponseEntity.badRequest().body(Map.of("error", "Target section required"));
        return ResponseEntity.ok(dto(service.moveToSection(c.clientId(), id, target, c.actor(), c.role()), null));
    }

    @DeleteMapping("/songs/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.delete(c.clientId(), id, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/reorder")
    public ResponseEntity<?> reorder(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        List<Long> ids = ids(b.get("ids"));
        String list = str(b.get("list"));
        if ("finalized".equalsIgnoreCase(list)) {
            Long fsId = lng(b.get("finalizedSectionId"));
            if (fsId == null) return ResponseEntity.badRequest().body(Map.of("error", "Finalized section required"));
            service.reorderFinalizedSection(c.clientId(), fsId, ids, c.actor(), c.role());
        } else {
            Long sectionId = lng(b.get("sectionId"));
            if (sectionId == null) return ResponseEntity.badRequest().body(Map.of("error", "Section required"));
            service.reorderSection(c.clientId(), sectionId, ids, c.actor(), c.role());
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /* ── finalized sections ── */

    @PostMapping("/finalized-sections")
    public ResponseEntity<?> addFinalSection(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        String name = str(b.get("name"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Section name required"));
        FinalizedSection fs = service.createFinalizedSection(c.clientId(), name, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("id", fs.getId(), "name", fs.getName()));
    }

    @PutMapping("/finalized-sections/{id}")
    public ResponseEntity<?> renameFinalSection(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        String name = str(b.get("name"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Section name required"));
        FinalizedSection fs = service.renameFinalizedSection(c.clientId(), id, name, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("id", fs.getId(), "name", fs.getName()));
    }

    @DeleteMapping("/finalized-sections/{id}")
    public ResponseEntity<?> deleteFinalSection(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.deleteFinalizedSection(c.clientId(), id, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/finalized-sections/reorder")
    public ResponseEntity<?> reorderFinalSections(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.reorderFinalizedSections(c.clientId(), ids(b.get("ids")), c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/songs/{id}/move-finalized")
    public ResponseEntity<?> moveFinalized(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        Long fsId = lng(b.get("finalizedSectionId"));
        if (fsId == null) return ResponseEntity.badRequest().body(Map.of("error", "Target finalized section required"));
        return ResponseEntity.ok(dto(service.moveFinalizedSong(c.clientId(), id, fsId, c.actor(), c.role()), null));
    }

    /* ── cover designer ── */

    @GetMapping("/cover")
    public ResponseEntity<?> getCover(HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> deny = needView(c); if (deny != null) return deny;
        String json = service.coverJson(c.clientId());
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(json == null || json.isBlank() ? "{}" : json);
    }

    @PutMapping("/cover")
    public ResponseEntity<?> saveCover(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try { service.saveCover(c.clientId(), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(b), c.actor(), c.role()); }
        catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", "Could not save cover")); }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /* ── uploaded custom cover / last page (kind = cover | last) ── */

    @PostMapping("/cover-asset")
    public ResponseEntity<?> uploadAsset(@RequestParam("file") MultipartFile file,
                                         @RequestParam("kind") String kind, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        if (!"cover".equals(kind) && !"last".equals(kind))
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid page kind"));
        try {
            service.saveAsset(c.clientId(), kind, file.getBytes(), file.getContentType(), file.getOriginalFilename(), c.actor(), c.role());
            return ResponseEntity.ok(Map.of("ok", true, "kind", kind));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Could not process that file. Please upload a clear PDF, JPG or PNG."));
        }
    }

    @GetMapping("/cover-asset")
    public ResponseEntity<?> getAsset(@RequestParam("kind") String kind, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> deny = needView(c); if (deny != null) return deny;
        com.churchgeniuspro.hibernate.SongBookAsset a = service.getAsset(c.clientId(), kind);
        if (a == null || a.getData() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        a.getContentType() == null ? "image/png" : a.getContentType()))
                .body(a.getData());
    }

    @DeleteMapping("/cover-asset")
    public ResponseEntity<?> deleteAsset(@RequestParam("kind") String kind, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.deleteAsset(c.clientId(), kind, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /* ── advertisement pages ── */

    @GetMapping("/ads")
    public ResponseEntity<?> listAds(HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> deny = needView(c); if (deny != null) return deny;
        return ResponseEntity.ok(service.listAds(c.clientId()));
    }

    @PostMapping("/ads")
    public ResponseEntity<?> uploadAd(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "title", required = false) String title,
                                      @RequestParam(value = "position", required = false) String position,
                                      HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try {
            int n = service.addAds(c.clientId(), file.getBytes(), file.getContentType(),
                    file.getOriginalFilename(), title, position, c.actor(), c.role());
            return ResponseEntity.ok(Map.of("ok", true, "pages", n));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Could not process that file. Please upload a clear PDF, JPG or PNG."));
        }
    }

    @GetMapping("/ads/{id}/image")
    public ResponseEntity<?> adImage(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> deny = needView(c); if (deny != null) return deny;
        com.churchgeniuspro.hibernate.SongBookAd a = service.getAd(c.clientId(), id);
        if (a == null || a.getData() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        a.getContentType() == null ? "image/png" : a.getContentType()))
                .body(a.getData());
    }

    @PutMapping("/ads/{id}")
    public ResponseEntity<?> updateAd(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try {
            service.updateAd(c.clientId(), id,
                    b.get("title") == null ? null : String.valueOf(b.get("title")),
                    b.get("position") == null ? null : String.valueOf(b.get("position")),
                    c.actor(), c.role());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/ads/{id}/replace")
    public ResponseEntity<?> replaceAd(@PathVariable Long id, @RequestParam("file") MultipartFile file, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try {
            service.replaceAd(c.clientId(), id, file.getBytes(), file.getContentType(),
                    file.getOriginalFilename(), c.actor(), c.role());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Could not process that file. Please upload a clear PDF, JPG or PNG."));
        }
    }

    @PostMapping("/ads/reorder")
    public ResponseEntity<?> reorderAds(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        Object raw = b.get("ids");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (raw instanceof java.util.List<?> list) {
            for (Object o : list) { try { ids.add(Long.parseLong(String.valueOf(o))); } catch (NumberFormatException ignored) { } }
        }
        service.reorderAds(c.clientId(), ids, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/ads/{id}")
    public ResponseEntity<?> deleteAd(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.deleteAd(c.clientId(), id, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/songs/{id}/finalize")
    public ResponseEntity<?> finalize(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        return ResponseEntity.ok(dto(service.finalize(c.clientId(), id, c.actor(), c.role()), null));
    }

    @PostMapping("/songs/{id}/unfinalize")
    public ResponseEntity<?> unfinalize(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        return ResponseEntity.ok(dto(service.unfinalize(c.clientId(), id, c.actor(), c.role()), null));
    }

    @PostMapping("/songs/{id}/lyrics/upload")
    public ResponseEntity<?> lyricsUpload(@PathVariable Long id, @RequestParam("file") MultipartFile file, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try {
            String html = extraction.extractLyricsHtml(file.getBytes(), file.getOriginalFilename());
            return ResponseEntity.ok(dto(service.setLyrics(c.clientId(), id, html, file.getOriginalFilename(), c.actor(), c.role()), null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read that file. Please upload a .txt, .doc, .docx or .pdf."));
        }
    }

    @PutMapping("/songs/{id}/lyrics")
    public ResponseEntity<?> lyricsEdit(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        return ResponseEntity.ok(dto(service.setLyrics(c.clientId(), id, str(b.get("html")), str(b.get("filename")), c.actor(), c.role()), null));
    }

    @DeleteMapping("/songs/{id}/lyrics")
    public ResponseEntity<?> lyricsDelete(@PathVariable Long id, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        return ResponseEntity.ok(dto(service.deleteLyrics(c.clientId(), id, c.actor(), c.role()), null));
    }

    @PostMapping("/publish")
    public ResponseEntity<?> publish(@RequestBody(required = false) Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        if (service.finalized(c.clientId()).isEmpty() && service.sections(c.clientId()).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Add songs before publishing."));
        try {
            SongBookPublish p = service.publish(c.clientId(), b == null ? null : str(b.get("bookTitle")), c.actor(), c.role());
            return ResponseEntity.ok(Map.of("published", true, "token", p.getToken(),
                    "url", "/songbook/view?t=" + p.getToken(), "bookTitle", p.getBookTitle()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Publish failed."));
        }
    }

    /** Rename the Finalized Song Book (its title / heading) without publishing. */
    @PutMapping("/book-title")
    public ResponseEntity<?> saveBookTitle(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        String title = str(b.get("bookTitle"));
        if (title.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Title required"));
        if (title.length() > 300) return ResponseEntity.badRequest().body(Map.of("error", "Title is too long (max 300 characters)."));
        SongBookPublish p = service.saveBookTitle(c.clientId(), title, c.actor(), c.role());
        return ResponseEntity.ok(Map.of("bookTitle", p.getBookTitle(), "published", p.isPublished()));
    }

    @PostMapping("/unpublish")
    public ResponseEntity<?> unpublish(HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.unpublish(c.clientId(), c.actor(), c.role());
        return ResponseEntity.ok(Map.of("published", false));
    }

    /* ─────────────────── "Now Singing" / "Next Song" (FULL) ─────────────────── */

    /**
     * The published book's songs (for the Set Current / Next picker) plus the
     * current selections. Scoped to the caller's active book + tenant via {@link #ctx}.
     */
    @GetMapping("/live")
    public ResponseEntity<?> live(HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> deny = needView(c); if (deny != null) return deny;
        SongBookPublish p = service.publishState(c.clientId());
        boolean published = p != null && p.isPublished() && p.getSnapshot() != null;
        List<Map<String, Object>> songs = published ? service.snapshotSongs(p.getSnapshot()) : new ArrayList<>();
        // A book published before this feature shipped has a snapshot whose songs carry no
        // id, so nothing can be selected against it. Say so explicitly rather than serving a
        // list that cannot be used — the page offers a one-click re-publish.
        boolean needsRepublish = published && !songs.isEmpty()
                && songs.stream().anyMatch(m -> m.get("songId") == null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("published", published);
        out.put("needsRepublish", needsRepublish);
        out.put("songs", songs);
        out.put("currentSongId", p != null ? p.getCurrentSongId() : null);
        out.put("nextSongId", p != null ? p.getNextSongId() : null);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/current-song")
    public ResponseEntity<?> setCurrentSong(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try {
            service.setCurrentSong(c.clientId(), lng(b.get("songId")), c.actor(), c.role());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/current-song")
    public ResponseEntity<?> clearCurrentSong(HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.clearCurrentSong(c.clientId(), c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/next-song")
    public ResponseEntity<?> setNextSong(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        try {
            service.setNextSong(c.clientId(), lng(b.get("songId")), c.actor(), c.role());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/next-song")
    public ResponseEntity<?> clearNextSong(HttpServletRequest req) {
        Ctx c = ctx(req); ResponseEntity<?> d = needEdit(c); if (d != null) return d;
        service.clearNextSong(c.clientId(), c.actor(), c.role());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /* ─────────────────── helpers ─────────────────── */

    private static Map<String, Object> dto(Song s, String sectionName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle());
        m.put("language", s.getLanguage() == null ? "" : s.getLanguage());
        m.put("sectionId", s.getSectionId());
        m.put("finalizedSectionId", s.getFinalizedSectionId());
        if (sectionName != null) m.put("sectionName", sectionName);
        m.put("finalized", s.isFinalized());
        m.put("workingOrder", s.getWorkingOrder());
        m.put("finalizedOrder", s.getFinalizedOrder());
        m.put("hasLyrics", s.getLyricsHtml() != null && !s.getLyricsHtml().isBlank());
        m.put("lyricsHtml", s.getLyricsHtml());
        m.put("lyricsFilename", s.getLyricsFilename());
        return m;
    }

    private static Map<String, Object> secDto(SongSection s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("sortOrder", s.getSortOrder());
        m.put("songs", new ArrayList<>());
        return m;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static Long lng(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(Object o) {
        List<Long> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) {
            try { out.add(Long.valueOf(String.valueOf(x))); } catch (Exception ignore) {}
        }
        return out;
    }
}
