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
    private final com.churchgeniuspro.repository.SongBookAdRepository adRepo;
    private final SongExtractionService extraction;
    private final ObjectMapper mapper = new ObjectMapper();

    public SongBookService(SongRepository songRepo, SongSectionRepository sectionRepo,
                           FinalizedSectionRepository finalRepo,
                           SongBookPublishRepository publishRepo, SongAuditLogRepository auditRepo,
                           com.churchgeniuspro.repository.SongBookAssetRepository assetRepo,
                           com.churchgeniuspro.repository.SongBookAdRepository adRepo,
                           SongExtractionService extraction) {
        this.songRepo = songRepo;
        this.sectionRepo = sectionRepo;
        this.finalRepo = finalRepo;
        this.publishRepo = publishRepo;
        this.auditRepo = auditRepo;
        this.assetRepo = assetRepo;
        this.adRepo = adRepo;
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
        requireOwnSection(clientId, sectionId);
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
        requireOwnSection(clientId, sectionId);
        Song s = songRepo.save(newSong(clientId, sectionId, title, language, nextWorkingOrder(clientId, sectionId)));
        audit(clientId, actor, role, "ADD", s.getId(), s.getTitle(), null);
        return s;
    }

    /** A song may only be filed under one of this church's own sections. */
    private void requireOwnSection(String clientId, Long sectionId) {
        if (sectionId == null || sectionRepo.findByIdAndClientId(sectionId, clientId).isEmpty()) {
            throw new IllegalArgumentException("Section not found");
        }
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

    /**
     * Save the Finalized Song Book's title (the heading used when the book is
     * published / viewed) WITHOUT publishing. The live public copy keeps its
     * snapshot title until the next (re)publish, exactly as before.
     */
    @Transactional
    public SongBookPublish saveBookTitle(String clientId, String bookTitle, String actor, String role) {
        SongBookPublish p = publishRepo.findByClientId(clientId).orElseGet(() -> {
            SongBookPublish n = new SongBookPublish();
            n.setClientId(clientId);
            n.setToken(UUID.randomUUID().toString().replace("-", ""));
            return n;
        });
        p.setBookTitle(bookTitle);
        p.setUpdatedAt(Instant.now());
        p = publishRepo.save(p);
        audit(clientId, actor, role, "BOOK_TITLE", null, bookTitle, "finalized song book title updated");
        return p;
    }

    /* ════════════════════ advertisement pages ════════════════════ */

    private static final int MAX_AD_PDF_PAGES = 20;
    private static final java.util.Set<String> AD_FIXED_POSITIONS = java.util.Set.of("cover", "toc", "end");

    /** Ads for a book, ordered; no image bytes included. */
    public List<Map<String, Object>> listAds(String clientId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.churchgeniuspro.hibernate.SongBookAd a : adRepo.findByClientIdOrderBySortOrderAscIdAsc(clientId)) {
            out.add(adMap(a));
        }
        return out;
    }

    private Map<String, Object> adMap(com.churchgeniuspro.hibernate.SongBookAd a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("position", a.getPosition());
        m.put("sortOrder", a.getSortOrder());
        m.put("fileName", a.getFileName());
        m.put("contentType", a.getContentType());
        m.put("updatedAt", a.getUpdatedAt() != null ? a.getUpdatedAt().toString() : null);
        return m;
    }

    private String normalizeAdPosition(String position) {
        String p = position == null || position.isBlank() ? "end" : position.trim();
        if (AD_FIXED_POSITIONS.contains(p)) return p;
        if (p.startsWith("fsec:")) {
            try { Long.parseLong(p.substring(5)); return p; } catch (NumberFormatException ignored) { }
        }
        return "end";
    }

    /** Rasterizes one image (or one PDF page) to stored bytes; mirrors saveAsset rules. */
    private record AdImage(byte[] bytes, String contentType) { }

    private List<AdImage> toImages(byte[] bytes, String contentType, String fileName) throws Exception {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        String fn = fileName == null ? "" : fileName.toLowerCase();
        List<AdImage> out = new ArrayList<>();
        if (ct.contains("pdf") || fn.endsWith(".pdf")) {
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(doc);
                int pages = Math.min(doc.getNumberOfPages(), MAX_AD_PDF_PAGES);
                for (int i = 0; i < pages; i++) {
                    java.awt.image.BufferedImage img = r.renderImageWithDPI(i, 200f);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(img, "png", baos);
                    out.add(new AdImage(baos.toByteArray(), "image/png"));
                }
            }
        } else if (ct.startsWith("image/") || fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg")) {
            String stored = ct.startsWith("image/") ? contentType : (fn.endsWith(".png") ? "image/png" : "image/jpeg");
            out.add(new AdImage(bytes, stored));
        } else {
            throw new IllegalArgumentException("Unsupported file type. Please upload a PDF, JPG, or PNG.");
        }
        return out;
    }

    /**
     * Adds advertisement page(s) from an uploaded image or PDF (one page per
     * PDF page). Returns the number of pages added.
     */
    @Transactional
    public int addAds(String clientId, byte[] bytes, String contentType, String fileName,
                      String title, String position, String actor, String role) throws Exception {
        String pos = normalizeAdPosition(position);
        List<AdImage> images = toImages(bytes, contentType, fileName);
        int base = adRepo.findByClientIdOrderBySortOrderAscIdAsc(clientId).size();
        int n = 0;
        for (AdImage img : images) {
            com.churchgeniuspro.hibernate.SongBookAd a = new com.churchgeniuspro.hibernate.SongBookAd();
            a.setClientId(clientId);
            a.setTitle(images.size() > 1 && title != null && !title.isBlank()
                    ? title + " (page " + (n + 1) + ")" : title);
            a.setPosition(pos);
            a.setSortOrder(base + n);
            a.setContentType(img.contentType());
            a.setFileName(fileName);
            a.setData(img.bytes());
            a.setCreatedAt(Instant.now());
            a.setUpdatedAt(Instant.now());
            adRepo.save(a);
            n++;
        }
        audit(clientId, actor, role, "AD_UPLOAD", null, title, fileName + " · " + n + " page(s) · " + pos);
        return n;
    }

    public com.churchgeniuspro.hibernate.SongBookAd getAd(String clientId, Long id) {
        return adRepo.findByIdAndClientId(id, clientId).orElse(null);
    }

    /** Edit title and/or position of an ad page. */
    @Transactional
    public void updateAd(String clientId, Long id, String title, String position, String actor, String role) {
        com.churchgeniuspro.hibernate.SongBookAd a = adRepo.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Ad page not found."));
        if (title != null) a.setTitle(title.isBlank() ? null : title.trim());
        if (position != null && !position.isBlank()) a.setPosition(normalizeAdPosition(position));
        a.setUpdatedAt(Instant.now());
        adRepo.save(a);
        audit(clientId, actor, role, "AD_UPDATE", null, a.getTitle(), a.getPosition());
    }

    /** Replaces the image of an existing ad page (PDF → its first page). */
    @Transactional
    public void replaceAd(String clientId, Long id, byte[] bytes, String contentType,
                          String fileName, String actor, String role) throws Exception {
        com.churchgeniuspro.hibernate.SongBookAd a = adRepo.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Ad page not found."));
        List<AdImage> images = toImages(bytes, contentType, fileName);
        AdImage img = images.get(0);
        a.setContentType(img.contentType());
        a.setFileName(fileName);
        a.setData(img.bytes());
        a.setUpdatedAt(Instant.now());
        adRepo.save(a);
        audit(clientId, actor, role, "AD_REPLACE", null, a.getTitle(), fileName);
    }

    /** Applies a new global order (array of ad ids in the desired order). */
    @Transactional
    public void reorderAds(String clientId, List<Long> ids, String actor, String role) {
        if (ids == null || ids.isEmpty()) return;
        int i = 0;
        for (Long id : ids) {
            com.churchgeniuspro.hibernate.SongBookAd a = adRepo.findByIdAndClientId(id, clientId).orElse(null);
            if (a == null) continue;
            a.setSortOrder(i++);
            a.setUpdatedAt(Instant.now());
            adRepo.save(a);
        }
        audit(clientId, actor, role, "AD_REORDER", null, null, i + " ad page(s)");
    }

    @Transactional
    public void deleteAd(String clientId, Long id, String actor, String role) {
        adRepo.findByIdAndClientId(id, clientId).ifPresent(a -> {
            adRepo.delete(a);
            audit(clientId, actor, role, "AD_DELETE", null, a.getTitle(), a.getFileName());
        });
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
        Map<Long, Integer> fsecSnapIndex = new LinkedHashMap<>();   // fsec id → snapshot section index
        List<FinalizedSection> fsecs = finalizedSections(clientId);
        if (!fsecs.isEmpty()) {
            for (FinalizedSection fs : fsecs) {
                List<Map<String, Object>> songs = new ArrayList<>();
                for (Song s : finalizedSectionSongs(clientId, fs.getId())) songs.add(songSnap(s, null));
                if (songs.isEmpty()) continue;
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", fs.getName());
                sm.put("songs", songs);
                fsecSnapIndex.put(fs.getId(), sections.size());
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

        // Advertisement pages — position resolved to a viewer slot:
        // "cover" | "toc" | "sec:<index>" | "end" (stale section refs → end).
        List<Map<String, Object>> adsSnap = new ArrayList<>();
        for (com.churchgeniuspro.hibernate.SongBookAd ad : adRepo.findByClientIdOrderBySortOrderAscIdAsc(clientId)) {
            if (ad.getData() == null || ad.getData().length == 0) continue;
            String pos = ad.getPosition() == null ? "end" : ad.getPosition();
            String slot;
            if (pos.startsWith("fsec:")) {
                Integer idx = null;
                try { idx = fsecSnapIndex.get(Long.parseLong(pos.substring(5))); } catch (NumberFormatException ignored) { }
                slot = idx != null ? "sec:" + idx : "end";
            } else if (pos.equals("cover") || pos.equals("toc")) {
                slot = pos;
            } else {
                slot = "end";
            }
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", ad.getId());
            am.put("title", ad.getTitle() == null ? "" : ad.getTitle());
            am.put("slot", slot);
            adsSnap.add(am);
        }
        snap.put("ads", adsSnap);

        SongBookPublish p = publishRepo.findByClientId(clientId).orElseGet(() -> {
            SongBookPublish n = new SongBookPublish();
            n.setClientId(clientId);
            n.setToken(UUID.randomUUID().toString().replace("-", ""));
            return n;
        });
        if (p.getToken() == null || p.getToken().isBlank()) p.setToken(UUID.randomUUID().toString().replace("-", ""));
        p.setBookTitle((String) snap.get("bookTitle"));
        p.setSnapshot(mapper.writeValueAsString(snap));
        // Preserve the Now Singing / Next Song selections across a (re)publish, but
        // drop any whose song is no longer in the freshly-built snapshot (e.g. the
        // song was deleted or removed from the finalized list before republishing).
        java.util.Set<Long> liveIds = snapshotSongIds(p.getSnapshot());
        if (p.getCurrentSongId() != null && !liveIds.contains(p.getCurrentSongId())) {
            p.setCurrentSongId(null);
            p.setCurrentSongVersion(nextVersion(p));
        }
        if (p.getNextSongId() != null && !liveIds.contains(p.getNextSongId())) {
            p.setNextSongId(null);
        }
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
        // The song's stable id is embedded so the public viewer can anchor the
        // "Now Singing" / "Next Song" selections to a specific song and deep-link
        // to it, and so set-current/set-next can be validated against the snapshot.
        m.put("id", s.getId());
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

    /* ════════════════ "Now Singing" / "Next Song" live selections ════════════════ */

    /**
     * The songs in the published snapshot, in book order, each as
     * {@code {songId, title, section, sectionIndex, songIndex}}. Used by the admin
     * picker and to validate/resolve the Current / Next selections. Empty when the
     * book is not published or has no songs.
     */
    public List<Map<String, Object>> snapshotSongs(String snapshot) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (snapshot == null || snapshot.isBlank()) return out;
        try {
            Map<?, ?> root = mapper.readValue(snapshot, Map.class);
            Object secs = root.get("sections");
            if (secs instanceof List<?> sl) {
                int si = 0;
                for (Object secObj : sl) {
                    if (secObj instanceof Map<?, ?> sm) {
                        Object nameObj = sm.get("name");
                        String secName = nameObj == null ? "" : String.valueOf(nameObj);
                        Object songs = sm.get("songs");
                        if (songs instanceof List<?> gl) {
                            int gi = 0;
                            for (Object soObj : gl) {
                                if (soObj instanceof Map<?, ?> gm) {
                                    Long sid = asLong(gm.get("id"));
                                    Object titleObj = gm.get("title");
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("songId", sid);
                                    m.put("title", titleObj == null ? "" : String.valueOf(titleObj));
                                    m.put("section", secName);
                                    m.put("sectionIndex", si);
                                    m.put("songIndex", gi);
                                    out.add(m);
                                }
                                gi++;
                            }
                        }
                    }
                    si++;
                }
            }
        } catch (Exception ignore) { /* malformed snapshot → no songs */ }
        return out;
    }

    /** The set of song ids present in the published snapshot (empty when absent). */
    private java.util.Set<Long> snapshotSongIds(String snapshot) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Map<String, Object> s : snapshotSongs(snapshot)) {
            Long id = asLong(s.get("songId"));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }

    private long nextVersion(SongBookPublish p) {
        Long v = p.getCurrentSongVersion();
        return (v == null ? 0L : v) + 1;
    }

    private SongBookPublish requirePublished(String clientId) {
        SongBookPublish p = publishRepo.findByClientId(clientId).orElse(null);
        if (p == null || !p.isPublished() || p.getSnapshot() == null)
            throw new IllegalStateException("Publish the song book before choosing a song.");
        return p;
    }

    private void requireSongInSnapshot(SongBookPublish p, Long songId) {
        if (songId == null) throw new IllegalArgumentException("Song is required.");
        if (!snapshotSongIds(p.getSnapshot()).contains(songId))
            throw new IllegalArgumentException("That song is not in the published book. Re-publish and try again.");
    }

    /** Set (or replace) the Current Song. Only one song can be current at a time. */
    @Transactional
    public SongBookPublish setCurrentSong(String clientId, Long songId, String actor, String role) {
        SongBookPublish p = requirePublished(clientId);
        requireSongInSnapshot(p, songId);
        p.setCurrentSongId(songId);
        p.setCurrentSongVersion(nextVersion(p));
        p.setUpdatedAt(Instant.now());
        p = publishRepo.save(p);
        Song s = songRepo.findByIdAndClientId(songId, clientId).orElse(null);
        audit(clientId, actor, role, "NOW_SINGING_SET", songId, s != null ? s.getTitle() : null, null);
        return p;
    }

    /** Unset the Current Song (removes the "Now Singing" banner for viewers). */
    @Transactional
    public SongBookPublish clearCurrentSong(String clientId, String actor, String role) {
        SongBookPublish p = publishRepo.findByClientId(clientId).orElse(null);
        if (p == null) return null;
        if (p.getCurrentSongId() != null) {
            p.setCurrentSongId(null);
            p.setCurrentSongVersion(nextVersion(p));
            p.setUpdatedAt(Instant.now());
            p = publishRepo.save(p);
            audit(clientId, actor, role, "NOW_SINGING_CLEAR", null, null, null);
        }
        return p;
    }

    /** Set (or replace) the Next Song. Only one song can be next at a time. */
    @Transactional
    public SongBookPublish setNextSong(String clientId, Long songId, String actor, String role) {
        SongBookPublish p = requirePublished(clientId);
        requireSongInSnapshot(p, songId);
        p.setNextSongId(songId);
        p.setUpdatedAt(Instant.now());
        p = publishRepo.save(p);
        Song s = songRepo.findByIdAndClientId(songId, clientId).orElse(null);
        audit(clientId, actor, role, "NEXT_SONG_SET", songId, s != null ? s.getTitle() : null, null);
        return p;
    }

    /** Unset the Next Song. */
    @Transactional
    public SongBookPublish clearNextSong(String clientId, String actor, String role) {
        SongBookPublish p = publishRepo.findByClientId(clientId).orElse(null);
        if (p == null) return null;
        if (p.getNextSongId() != null) {
            p.setNextSongId(null);
            p.setUpdatedAt(Instant.now());
            p = publishRepo.save(p);
            audit(clientId, actor, role, "NEXT_SONG_CLEAR", null, null, null);
        }
        return p;
    }

    /**
     * Live "Now Singing" / "Next Song" state for a published book, with each
     * selection resolved to its title from the snapshot. A stored id that is no
     * longer in the snapshot resolves to {@code null} (treated as unset), so the
     * viewer never shows a stale heading. Shape:
     * {@code {currentVersion, current:{songId,title}|null, next:{songId,title}|null}}.
     */
    public Map<String, Object> liveState(SongBookPublish p) {
        Map<String, Object> out = new LinkedHashMap<>();
        long ver = p == null || p.getCurrentSongVersion() == null ? 0L : p.getCurrentSongVersion();
        out.put("currentVersion", ver);
        Map<Long, String> titles = new LinkedHashMap<>();
        if (p != null) for (Map<String, Object> s : snapshotSongs(p.getSnapshot())) {
            Long id = asLong(s.get("songId"));
            if (id != null) titles.put(id, String.valueOf(s.get("title")));
        }
        out.put("current", slot(p == null ? null : p.getCurrentSongId(), titles));
        out.put("next", slot(p == null ? null : p.getNextSongId(), titles));
        return out;
    }

    private Map<String, Object> slot(Long songId, Map<Long, String> titles) {
        if (songId == null || !titles.containsKey(songId)) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("songId", songId);
        m.put("title", titles.get(songId));
        return m;
    }

    /* ════════════════════ multi-book support ════════════════════ */

    /**
     * Deletes ALL Song Book data stored under the given (book-scoped) client id:
     * songs, sections, finalized sections, publish row and uploaded pages.
     * Used when a non-default Song Book is deleted. The caller guarantees the
     * scope is a book-suffixed id ({@code <clientId>#B<bookId>}) — never a plain
     * client id — so legacy/default-book data can never be purged by accident.
     */
    @Transactional
    public void purgeScope(String scopedClientId, String actor, String role) {
        // Songs before their sections/finalized sections (child-before-parent for the
        // W3 RESTRICT keys song.section_id / song.finalized_section_id); JPA's
        // flush-before-query between these derived deletes preserves that order.
        songRepo.deleteByClientId(scopedClientId);
        sectionRepo.deleteByClientId(scopedClientId);
        finalRepo.deleteByClientId(scopedClientId);
        publishRepo.deleteByClientId(scopedClientId);
        assetRepo.deleteByClientId(scopedClientId);
        adRepo.deleteByClientId(scopedClientId);
        // The book's append-only audit rows have no live FK (song_audit_log.song_id is a
        // historical reference, not a key — see W3), so removing them last is safe and keeps
        // a deleted book scope from leaving orphaned audit rows behind.
        auditRepo.deleteByClientId(scopedClientId);
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
