package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FinalizedSection;
import com.churchgeniuspro.hibernate.Song;
import com.churchgeniuspro.hibernate.SongAuditLog;
import com.churchgeniuspro.hibernate.SongBookPublish;
import com.churchgeniuspro.hibernate.SongSection;
import com.churchgeniuspro.repository.FinalizedSectionRepository;
import com.churchgeniuspro.repository.SongAuditLogRepository;
import com.churchgeniuspro.repository.SongBookPublishRepository;
import com.churchgeniuspro.repository.SongRepository;
import com.churchgeniuspro.repository.SongSectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core Song Book operations: multi-section (category) management, multilingual
 * song CRUD + reorder + move-between-sections, lyrics, the finalized (curated)
 * list, publish/unpublish (token + snapshot), and append-only audit logging.
 * Everything is tenant-scoped by {@code clientId}.
 */
@Service
public class SongBookService {

    private final SongRepository songRepo;
    private final SongSectionRepository sectionRepo;
    private final FinalizedSectionRepository finalRepo;
    private final SongBookPublishRepository publishRepo;
    private final SongAuditLogRepository auditRepo;
    private final com.churchgeniuspro.repository.SongBookAssetRepository assetRepo;
    private final SongExtractionService extraction;
    private final ObjectMapper mapper = new ObjectMapper();

    public SongBookService(SongRepository songRepo, SongSectionRepository sectionRepo,
                           FinalizedSectionRepository finalRepo,
                           SongBookPublishRepository publishRepo, SongAuditLogRepository auditRepo,
                           com.churchgeniuspro.repository.SongBookAssetRepository assetRepo,
                           SongExtractionService extraction) {
        this.songRepo = songRepo;
        this.sectionRepo = sectionRepo;
        this.finalRepo = finalRepo;
        this.publishRepo = publishRepo;
        this.auditRepo = auditRepo;
        this.assetRepo = assetRepo;
        this.extraction = extraction;
    }

    /* ════════════════════ sections ════════════════════ */

    /** All sections (custom order). Lazily creates a default and adopts orphan songs. */
    @Transactional
    public List<SongSection> sections(String clientId) {
        List<SongSection> list = sectionRepo.findByClientIdOrderBySortOrderAsc(clientId);
        List<Song> orphans = songRepo.findByClientIdAndSectionIdIsNull(clientId);
        if (list.isEmpty() && orphans.isEmpty()) return list;
        if (list.isEmpty()) {
            list = List.of(createSectionInternal(clientId, "Songs", 0));
        }
        if (!orphans.isEmpty()) {
            Long def = list.get(0).getId();
            int base = (int) songRepo.countByClientIdAndSectionIdAndFinalizedFalse(clientId, def);
            for (Song s : orphans) { s.setSectionId(def); s.setWorkingOrder(base++); if (s.getLanguage()==null) s.setLanguage("English"); songRepo.save(s); }
        }
        return sectionRepo.findByClientIdOrderBySortOrderAsc(clientId);
    }

    public List<Song> sectionSongs(String clientId, Long sectionId) {
        List<Song> all = songRepo.findByClientIdAndSectionId(clientId, sectionId);
        all.sort(Comparator.comparingInt(Song::getWorkingOrder));
        return all;
    }

    private SongSection createSectionInternal(String clientId, String name, int order) {
        SongSection s = new SongSection();
        s.setClientId(clientId);
        s.setName(name.trim());
        s.setSortOrder(order);
        s.setCreatedAt(Instant.now());
        return sectionRepo.save(s);
    }

    @Transactional
    public SongSection createSection(String clientId, String name, String actor, String role) {
        int order = sectionRepo.findByClientIdOrderBySortOrderAsc(clientId).stream()
                .mapToInt(SongSection::getSortOrder).max().orElse(-1) + 1;
        SongSection s = createSectionInternal(clientId, name, order);
        audit(clientId, actor, role, "SECTION_ADD", null, s.getName(), null);
        return s;
    }

    @Transactional
    public SongSection renameSection(String clientId, Long id, String name, String actor, String role) {
        SongSection s = sectionRepo.findByIdAndClientId(id, clientId).orElseThrow();
        s.setName(name.trim());
        s = sectionRepo.save(s);
        audit(clientId, actor, role, "SECTION_RENAME", null, s.getName(), null);
        return s;
    }

    @Transactional
    public void deleteSection(String clientId, Long id, String actor, String role) {
        SongSection s = sectionRepo.findByIdAndClientId(id, clientId).orElseThrow();
        List<Song> songs = songRepo.findByClientIdAndSectionId(clientId, id);
        songRepo.deleteAll(songs);
        sectionRepo.delete(s);
        audit(clientId, actor, role, "SECTION_DELETE", null, s.getName(), songs.size() + " songs removed");
    }

    @Transactional
    public void reorderSections(String clientId, List<Long> orderedIds, String actor, String role) {
        int order = 0;
        for (Long id : orderedIds) {
            SongSection s = sectionRepo.findByIdAndClientId(id, clientId).orElse(null);
            if (s == null) continue;
            s.setSortOrder(order++);
            sectionRepo.save(s);
        }
        audit(clientId, actor, role, "SECTION_REORDER", null, null, "sections reordered");
    }

    /* ════════════════════ reads ════════════════════ */

    public List<Song> finalized(String clientId) { return songRepo.findByClientIdAndFinalizedTrueOrderByFinalizedOrderAsc(clientId); }

    /* ════════════════════ song mutations ════════════════════ */

    @Transactional
    public List<Song> addTitles(String clientId, Long sectionId, String language, List<String> titles, String actor, String role) {
        int order = nextWorkingOrder(clientId, sectionId);
        List<Song> added = new ArrayList<>();
        for (String t : titles) {
            if (t == null || t.trim().isEmpty()) continue;
            Song s = newSong(clientId, sectionId, t, language, order++);
            added.add(songRepo.save(s));
        }
        audit(clientId, actor, role, "UPLOAD", null, null, added.size() + " song(s) added");
        return added;
    }

    @Transactional
    public Song addTitle(String clientId, Long sectionId, String title, String language, String actor, String role) {
        Song s = songRepo.save(newSong(clientId, sectionId, title, language, nextWorkingOrder(clientId, sectionId)));
        audit(clientId, actor, role, "ADD", s.getId(), s.getTitle(), null);
        return s;
    }

    private Song newSong(String clientId, Long sectionId, String title, String language, int order) {
        Song s = new Song();
        s.setClientId(clientId);
        s.setSectionId(sectionId);
        s.setLanguage(language == null || language.isBlank() ? "English" : language.trim());
        s.setTitle(title.trim());
        s.setWorkingOrder(order);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return s;
    }

    @Transactional
    public Song editTitle(String clientId, Long id, String title, String language, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        if (title != null && !title.isBlank()) s.setTitle(title.trim());
        if (language != null && !language.isBlank()) s.setLanguage(language.trim());
        s.setUpdatedAt(Instant.now());
        s = songRepo.save(s);
        audit(clientId, actor, role, "EDIT", id, s.getTitle(), null);
        return s;
    }

    @Transactional
    public Song moveToSection(String clientId, Long id, Long targetSectionId, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        s.setSectionId(targetSectionId);
        s.setWorkingOrder(nextWorkingOrder(clientId, targetSectionId));
        s.setUpdatedAt(Instant.now());
        s = songRepo.save(s);
        SongSection sec = sectionRepo.findByIdAndClientId(targetSectionId, clientId).orElse(null);
        audit(clientId, actor, role, "MOVE", id, s.getTitle(), "→ " + (sec != null ? sec.getName() : "section"));
        return s;
    }

    @Transactional
    public void delete(String clientId, Long id, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        String title = s.getTitle();
        songRepo.delete(s);
        audit(clientId, actor, role, "DELETE", id, title, null);
    }

    @Transactional
    public void reorderSection(String clientId, Long sectionId, List<Long> orderedIds, String actor, String role) {
        int order = 0;
        for (Long id : orderedIds) {
            Song s = songRepo.findByIdAndClientId(id, clientId).orElse(null);
            if (s == null) continue;
            s.setWorkingOrder(order++);
            s.setUpdatedAt(Instant.now());
            songRepo.save(s);
        }
        audit(clientId, actor, role, "REORDER", null, null, "section reordered");
    }

    /* ════════════════════ finalized sections ════════════════════ */

    /** All finalized sections (custom order). Lazily adopts orphan finalized songs. */
    @Transactional
    public List<FinalizedSection> finalizedSections(String clientId) {
        List<Song> orphans = songRepo.findByClientIdAndFinalizedTrueAndFinalizedSectionIdIsNull(clientId);
        for (Song s : orphans) {
            FinalizedSection fs = sectionNameFor(clientId, s);
            s.setFinalizedSectionId(fs.getId());
            s.setFinalizedOrder((int) songRepo.countByClientIdAndFinalizedSectionId(clientId, fs.getId()));
            songRepo.save(s);
        }
        return finalRepo.findByClientIdOrderBySortOrderAsc(clientId);
    }

    public List<Song> finalizedSectionSongs(String clientId, Long fsId) {
        return songRepo.findByClientIdAndFinalizedSectionIdOrderByFinalizedOrderAsc(clientId, fsId);
    }

    /** Find-or-create a finalized section named after the song's working section. */
    private FinalizedSection sectionNameFor(String clientId, Song s) {
        String name = "Songs";
        if (s.getSectionId() != null) {
            SongSection ws = sectionRepo.findByIdAndClientId(s.getSectionId(), clientId).orElse(null);
            if (ws != null && ws.getName() != null && !ws.getName().isBlank()) name = ws.getName();
        }
        return findOrCreateFinalSection(clientId, name);
    }

    private FinalizedSection findOrCreateFinalSection(String clientId, String name) {
        return finalRepo.findFirstByClientIdAndNameIgnoreCase(clientId, name.trim()).orElseGet(() -> {
            FinalizedSection fs = new FinalizedSection();
            fs.setClientId(clientId);
            fs.setName(name.trim());
            fs.setSortOrder(finalRepo.findByClientIdOrderBySortOrderAsc(clientId).stream()
                    .mapToInt(FinalizedSection::getSortOrder).max().orElse(-1) + 1);
            fs.setCreatedAt(Instant.now());
            return finalRepo.save(fs);
        });
    }

    @Transactional
    public FinalizedSection createFinalizedSection(String clientId, String name, String actor, String role) {
        FinalizedSection fs = findOrCreateFinalSection(clientId, name);
        audit(clientId, actor, role, "FSECTION_ADD", null, fs.getName(), null);
        return fs;
    }

    @Transactional
    public FinalizedSection renameFinalizedSection(String clientId, Long id, String name, String actor, String role) {
        FinalizedSection fs = finalRepo.findByIdAndClientId(id, clientId).orElseThrow();
        fs.setName(name.trim());
        fs = finalRepo.save(fs);
        audit(clientId, actor, role, "FSECTION_RENAME", null, fs.getName(), null);
        return fs;
    }

    /** Delete a finalized section — its songs return to the working library. */
    @Transactional
    public void deleteFinalizedSection(String clientId, Long id, String actor, String role) {
        FinalizedSection fs = finalRepo.findByIdAndClientId(id, clientId).orElseThrow();
        for (Song s : songRepo.findByClientIdAndFinalizedSectionId(clientId, id)) {
            s.setFinalized(false);
            s.setFinalizedSectionId(null);
            s.setWorkingOrder(nextWorkingOrder(clientId, s.getSectionId()));
            s.setUpdatedAt(Instant.now());
            songRepo.save(s);
        }
        finalRepo.delete(fs);
        audit(clientId, actor, role, "FSECTION_DELETE", null, fs.getName(), null);
    }

    @Transactional
    public void reorderFinalizedSections(String clientId, List<Long> orderedIds, String actor, String role) {
        int order = 0;
        for (Long id : orderedIds) {
            FinalizedSection fs = finalRepo.findByIdAndClientId(id, clientId).orElse(null);
            if (fs == null) continue;
            fs.setSortOrder(order++);
            finalRepo.save(fs);
        }
        audit(clientId, actor, role, "FSECTION_REORDER", null, null, "finalized sections reordered");
    }

    @Transactional
    public Song finalize(String clientId, Long id, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        if (!s.isFinalized()) {
            FinalizedSection fs = sectionNameFor(clientId, s);
            s.setFinalized(true);
            s.setFinalizedSectionId(fs.getId());
            s.setFinalizedOrder((int) songRepo.countByClientIdAndFinalizedSectionId(clientId, fs.getId()));
            s.setUpdatedAt(Instant.now());
            s = songRepo.save(s);
            audit(clientId, actor, role, "FINALIZE", id, s.getTitle(), "→ " + fs.getName());
        }
        return s;
    }

    @Transactional
    public Song unfinalize(String clientId, Long id, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        if (s.isFinalized()) {
            s.setFinalized(false);
            s.setFinalizedSectionId(null);
            s.setWorkingOrder(nextWorkingOrder(clientId, s.getSectionId()));
            s.setUpdatedAt(Instant.now());
            s = songRepo.save(s);
            audit(clientId, actor, role, "UNFINALIZE", id, s.getTitle(), null);
        }
        return s;
    }

    @Transactional
    public Song moveFinalizedSong(String clientId, Long id, Long fsId, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        FinalizedSection fs = finalRepo.findByIdAndClientId(fsId, clientId).orElseThrow();
        s.setFinalized(true);
        s.setFinalizedSectionId(fs.getId());
        s.setFinalizedOrder((int) songRepo.countByClientIdAndFinalizedSectionId(clientId, fs.getId()));
        s.setUpdatedAt(Instant.now());
        s = songRepo.save(s);
        audit(clientId, actor, role, "MOVE", id, s.getTitle(), "→ " + fs.getName() + " (finalized)");
        return s;
    }

    @Transactional
    public void reorderFinalizedSection(String clientId, Long fsId, List<Long> orderedIds, String actor, String role) {
        int order = 0;
        for (Long id : orderedIds) {
            Song s = songRepo.findByIdAndClientId(id, clientId).orElse(null);
            if (s == null || !s.isFinalized()) continue;
            s.setFinalizedOrder(order++);
            s.setUpdatedAt(Instant.now());
            songRepo.save(s);
        }
        audit(clientId, actor, role, "REORDER", null, null, "finalized section reordered");
    }

    /* ════════════════════ cover designer ════════════════════ */

    public String coverJson(String clientId) {
        return publishRepo.findByClientId(clientId).map(SongBookPublish::getCoverJson).orElse(null);
    }

    /* ════════════════════ uploaded cover / last page ════════════════════ */

    public boolean hasAsset(String clientId, String kind) {
        return assetRepo.existsByClientIdAndKind(clientId, kind);
    }

    public com.churchgeniuspro.hibernate.SongBookAsset getAsset(String clientId, String kind) {
        return assetRepo.findByClientIdAndKind(clientId, kind).orElse(null);
    }

    /** Accept PDF (first page rasterized to PNG), JPG or PNG; store as an image. */
    @Transactional
    public void saveAsset(String clientId, String kind, byte[] bytes, String contentType,
                          String fileName, String actor, String role) throws Exception {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        String fn = fileName == null ? "" : fileName.toLowerCase();
        byte[] out;
        String storedCt;
        if (ct.contains("pdf") || fn.endsWith(".pdf")) {
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(doc);
                java.awt.image.BufferedImage img = r.renderImageWithDPI(0, 200f);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "png", baos);
                out = baos.toByteArray();
                storedCt = "image/png";
            }
        } else if (ct.startsWith("image/") || fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg")) {
            out = bytes;
            storedCt = (ct.startsWith("image/")) ? contentType : (fn.endsWith(".png") ? "image/png" : "image/jpeg");
        } else {
            throw new IllegalArgumentException("Unsupported file type. Please upload a PDF, JPG, or PNG.");
        }
        com.churchgeniuspro.hibernate.SongBookAsset a = assetRepo.findByClientIdAndKind(clientId, kind)
                .orElseGet(() -> {
                    com.churchgeniuspro.hibernate.SongBookAsset n = new com.churchgeniuspro.hibernate.SongBookAsset();
                    n.setClientId(clientId);
                    n.setKind(kind);
                    return n;
                });
        a.setContentType(storedCt);
        a.setFileName(fileName);
        a.setData(out);
        a.setUpdatedAt(Instant.now());
        assetRepo.save(a);
        audit(clientId, actor, role, "last".equals(kind) ? "LASTPAGE_UPLOAD" : "COVER_UPLOAD", null, null, fileName);
    }

    @Transactional
    public void deleteAsset(String clientId, String kind, String actor, String role) {
        assetRepo.findByClientIdAndKind(clientId, kind).ifPresent(a -> {
            assetRepo.delete(a);
            audit(clientId, actor, role, "last".equals(kind) ? "LASTPAGE_DELETE" : "COVER_DELETE", null, null, null);
        });
    }

    @Transactional
    public void saveCover(String clientId, String coverJson, String actor, String role) {
        SongBookPublish p = publishRepo.findByClientId(clientId).orElseGet(() -> {
            SongBookPublish n = new SongBookPublish();
            n.setClientId(clientId);
            n.setToken(UUID.randomUUID().toString().replace("-", ""));
            return n;
        });
        p.setCoverJson(coverJson);
        p.setUpdatedAt(Instant.now());
        publishRepo.save(p);
        audit(clientId, actor, role, "COVER_SAVE", null, null, "cover page updated");
    }

    /* ════════════════════ lyrics ════════════════════ */

    @Transactional
    public Song setLyrics(String clientId, Long id, String html, String filename, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        s.setLyricsHtml(extraction.sanitizeHtml(html));
        s.setLyricsFilename(filename);
        s.setUpdatedAt(Instant.now());
        s = songRepo.save(s);
        audit(clientId, actor, role, "LYRICS_SET", id, s.getTitle(), filename);
        return s;
    }

    @Transactional
    public Song deleteLyrics(String clientId, Long id, String actor, String role) {
        Song s = songRepo.findByIdAndClientId(id, clientId).orElseThrow();
        s.setLyricsHtml(null);
        s.setLyricsFilename(null);
        s.setUpdatedAt(Instant.now());
        s = songRepo.save(s);
        audit(clientId, actor, role, "LYRICS_DELETE", id, s.getTitle(), null);
        return s;
    }

    /* ════════════════════ publish ════════════════════ */

    public SongBookPublish publishState(String clientId) {
        return publishRepo.findByClientId(clientId).orElse(null);
    }

    @Transactional
    public SongBookPublish publish(String clientId, String bookTitle, String actor, String role) throws Exception {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("bookTitle", bookTitle == null || bookTitle.isBlank() ? "Song Book" : bookTitle);
        snap.put("publishedAt", Instant.now().toString());

        // Cover designer settings + uploaded custom-page flags.
        Map<String, Object> cover = new LinkedHashMap<>();
        String existingCover = coverJson(clientId);
        if (existingCover != null && !existingCover.isBlank()) {
            try { cover.putAll(mapper.readValue(existingCover, Map.class)); } catch (Exception ignore) {}
        }
        cover.put("customCover", hasAsset(clientId, "cover"));
        cover.put("customLast", hasAsset(clientId, "last"));
        snap.put("cover", cover);

        // The published book = finalized sections (custom order) with songs in
        // finalized order. Falls back to the working sections if nothing finalized.
        List<Map<String, Object>> sections = new ArrayList<>();
        List<FinalizedSection> fsecs = finalizedSections(clientId);
        if (!fsecs.isEmpty()) {
            for (FinalizedSection fs : fsecs) {
                List<Map<String, Object>> songs = new ArrayList<>();
                for (Song s : finalizedSectionSongs(clientId, fs.getId())) songs.add(songSnap(s, null));
                if (songs.isEmpty()) continue;
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", fs.getName());
                sm.put("songs", songs);
                sections.add(sm);
            }
        } else {
            for (SongSection sec : sections(clientId)) {
                List<Map<String, Object>> songs = new ArrayList<>();
                for (Song s : sectionSongs(clientId, sec.getId())) songs.add(songSnap(s, null));
                if (songs.isEmpty()) continue;
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", sec.getName());
                sm.put("songs", songs);
                sections.add(sm);
            }
        }
        snap.put("sections", sections);

        SongBookPublish p = publishRepo.findByClientId(clientId).orElseGet(() -> {
            SongBookPublish n = new SongBookPublish();
            n.setClientId(clientId);
            n.setToken(UUID.randomUUID().toString().replace("-", ""));
            return n;
        });
        if (p.getToken() == null || p.getToken().isBlank()) p.setToken(UUID.randomUUID().toString().replace("-", ""));
        p.setBookTitle((String) snap.get("bookTitle"));
        p.setSnapshot(mapper.writeValueAsString(snap));
        p.setPublished(true);
        p.setPublishedAt(Instant.now());
        p.setPublishedBy(actor);
        p.setUpdatedAt(Instant.now());
        p = publishRepo.save(p);
        int songCount = sections.stream().mapToInt(m -> ((List<?>) m.get("songs")).size()).sum();
        audit(clientId, actor, role, "PUBLISH", null, p.getBookTitle(), songCount + " songs · " + sections.size() + " sections");
        return p;
    }

    private Map<String, Object> songSnap(Song s, String sectionName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", s.getTitle());
        m.put("language", s.getLanguage() == null ? "" : s.getLanguage());
        m.put("lyricsHtml", s.getLyricsHtml() == null ? "" : s.getLyricsHtml());
        if (sectionName != null) m.put("section", sectionName);
        return m;
    }

    @Transactional
    public void unpublish(String clientId, String actor, String role) {
        SongBookPublish p = publishRepo.findByClientId(clientId).orElse(null);
        if (p != null && p.isPublished()) {
            p.setPublished(false);
            p.setUpdatedAt(Instant.now());
            publishRepo.save(p);
            audit(clientId, actor, role, "UNPUBLISH", null, p.getBookTitle(), null);
        }
    }

    public SongBookPublish publicByToken(String token) {
        return publishRepo.findByTokenAndPublishedTrue(token).orElse(null);
    }

    /* ════════════════════ audit ════════════════════ */

    public void audit(String clientId, String actor, String role, String action,
                      Long songId, String songTitle, String detail) {
        SongAuditLog log = new SongAuditLog();
        log.setClientId(clientId);
        log.setActor(actor);
        log.setRole(role);
        log.setAction(action);
        log.setSongId(songId);
        log.setSongTitle(songTitle);
        log.setDetail(detail);
        log.setCreatedAt(Instant.now());
        auditRepo.save(log);
    }

    public List<SongAuditLog> recentAudit(String clientId, int limit) {
        return auditRepo.findByClientIdOrderByCreatedAtDesc(clientId, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    /* ════════════════════ helpers ════════════════════ */

    public Song find(String clientId, Long id) { return songRepo.findByIdAndClientId(id, clientId).orElse(null); }
    public SongSection findSection(String clientId, Long id) { return sectionRepo.findByIdAndClientId(id, clientId).orElse(null); }

    private int nextWorkingOrder(String clientId, Long sectionId) {
        if (sectionId == null) return 0;
        return songRepo.findByClientIdAndSectionId(clientId, sectionId).stream()
                .mapToInt(Song::getWorkingOrder).max().orElse(-1) + 1;
    }
}
