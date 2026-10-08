package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.UploadedFile;
import com.churchgeniuspro.repository.UploadedFileRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Handles the Files Upload page and file management REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /filesUpload} → {@code filesUpload.html} (authenticated)</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/files}       → list files for org</li>
 *   <li>{@code POST   /api/files}       → upload a file (base64 body)</li>
 *   <li>{@code GET    /api/files/{id}}  → get single file with data</li>
 *   <li>{@code DELETE /api/files/{id}}  → soft-delete a file</li>
 * </ul>
 */
@Controller
public class FilesController {

    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2 MB

    private final UploadedFileRepository fileRepo;

    public FilesController(UploadedFileRepository fileRepo) {
        this.fileRepo = fileRepo;
    }

    // ── Page route ────────────────────────────────────────────────────────
    // HIDDEN — Files & Notes feature temporarily disabled for all roles.
    // Uncomment the @GetMapping to re-enable. Do not remove this code.
    //
    // @GetMapping("/filesUpload")
    // public String filesPage(HttpServletRequest request) {
    //     String deny = RoleGuard.requireAuth(request);
    //     if (deny != null) return deny;
    //     deny = RoleGuard.requirePermission(request, "admin.files");
    //     if (deny != null) return deny;
    //     return "forward:/filesUpload.html";
    // }

    // ── List ──────────────────────────────────────────────────────────────

   // @ResponseBody
    @GetMapping("/api/files")
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
        if (filesGuard(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        List<UploadedFile> files = fileRepo.findByAppClientIdAndDeleteFlagFalseOrderByUploadDateDesc(appClientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (UploadedFile f : files) {
            result.add(toMetaMap(f));
        }
        return ResponseEntity.ok(result);
    }

    // ── Upload ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/files")
    public ResponseEntity<Map<String, Object>> upload(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        if (filesGuard(request) != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        String fileName = (String) body.get("fileName");
        String fileType = (String) body.get("fileType");
        String fileData = (String) body.get("fileData");
        Object sizeObj  = body.get("fileSize");

        if (fileName == null || fileName.isBlank()) return bad("File name is required.");
        if (fileData == null || fileData.isBlank()) return bad("File data is required.");

        long fileSize = sizeObj instanceof Number n ? n.longValue() : 0L;
        if (fileSize > MAX_FILE_SIZE) {
            return bad("File size exceeds the 2 MB limit.");
        }

        // Validate allowed file types
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".doc") && !lower.endsWith(".docx")
                && !lower.endsWith(".xls") && !lower.endsWith(".xlsx")
                && !lower.endsWith(".ppt") && !lower.endsWith(".pptx")
                && !lower.endsWith(".txt") && !lower.endsWith(".csv")) {
            return bad("File type not allowed. Allowed: PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX, TXT, CSV.");
        }

        HttpSession session = request.getSession(false);
        String uploaderName = "";
        Integer uploaderId  = null;
        if (session != null) {
            String first = (String) session.getAttribute("firstName");
            String last  = (String) session.getAttribute("lastName");
            uploaderName = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
            Object cid = session.getAttribute("clientId");
            if (cid instanceof Integer i) uploaderId = i;
            else if (cid instanceof String s) { try { uploaderId = Integer.parseInt(s); } catch (Exception ignored) {} }
        }

        UploadedFile file = new UploadedFile();
        file.setFileName(fileName.trim());
        file.setFileType(fileType);
        file.setFileSize(fileSize);
        file.setFileData(fileData);
        file.setAppClientId(appClientId);
        file.setUploadedById(uploaderId);
        file.setUploadedByName(uploaderName);
        UploadedFile saved = fileRepo.save(file);

        return ResponseEntity.ok(toMetaMap(saved));
    }

    // ── Get single (with data for view/download) ──────────────────────────

    @ResponseBody
    @GetMapping("/api/files/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Integer id,
                                                       HttpServletRequest request) {
        if (filesGuard(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        Optional<UploadedFile> opt = fileRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> m = toMetaMap(opt.get());
        m.put("fileData", opt.get().getFileData());
        return ResponseEntity.ok(m);
    }

    // ── Delete ────────────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/files/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        if (filesGuard(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        Optional<UploadedFile> opt = fileRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        UploadedFile f = opt.get();
        f.setDeleteFlag(true);
        fileRepo.save(f);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        return ResponseEntity.ok(ok);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toMetaMap(UploadedFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             f.getId());
        m.put("fileName",       f.getFileName());
        m.put("fileType",       f.getFileType());
        m.put("fileSize",       f.getFileSize());
        m.put("uploadedByName", f.getUploadedByName());
        m.put("uploadDate",     f.getUploadDate() != null ? f.getUploadDate().toString() : null);
        return m;
    }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return ResponseEntity.badRequest().body(m);
    }

    /** Same gate as the (currently disabled) {@code /filesUpload} page route. */
    private static String filesGuard(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        return RoleGuard.requirePermission(request, "admin.files");
    }
}
