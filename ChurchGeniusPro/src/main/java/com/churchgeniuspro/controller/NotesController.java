package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Note;
import com.churchgeniuspro.repository.NoteRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Handles the Notes page and note management REST API.
 *
 * <h3>Page route</h3>
 * <ul>
 *   <li>{@code GET /notes} → {@code notes.html} (authenticated)</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/notes}           → list all notes for org</li>
 *   <li>{@code POST   /api/notes}           → create a note</li>
 *   <li>{@code PUT    /api/notes/{id}}      → update (creator only)</li>
 *   <li>{@code DELETE /api/notes/{id}}      → soft-delete (creator only)</li>
 *   <li>{@code PUT    /api/notes/{id}/star} → toggle star (creator only)</li>
 * </ul>
 */
@Controller
public class NotesController {

    private final NoteRepository noteRepo;

    public NotesController(NoteRepository noteRepo) {
        this.noteRepo = noteRepo;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/notes")
    public String notesPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        return "forward:/notes.html";
    }

    // ── List ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/notes")
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        Integer currentUserId = getCurrentUserId(request);
        List<Note> notes = noteRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Note n : notes) {
            result.add(toMap(n, currentUserId));
        }
        return ResponseEntity.ok(result);
    }

    // ── Create ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/notes")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        String title = (String) body.get("title");
        String noteBody = (String) body.get("body");
        if (title == null || title.isBlank()) return bad("Title is required.");

        HttpSession session = request.getSession(false);
        String creatorName = "";
        Integer creatorId  = null;
        if (session != null) {
            String first = (String) session.getAttribute("firstName");
            String last  = (String) session.getAttribute("lastName");
            creatorName = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
            creatorId = getSessionUserId(session);
        }

        Note note = new Note();
        note.setTitle(title.trim());
        note.setBody(noteBody);
        note.setCreatedById(creatorId);
        note.setCreatedByName(creatorName);
        note.setAppClientId(appClientId);
        Note saved = noteRepo.save(note);
        return ResponseEntity.ok(toMap(saved, creatorId));
    }

    // ── Update (creator only) ─────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/notes/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Integer currentUserId = getCurrentUserId(request);
        Optional<Note> opt = noteRepo.findByIdAndDeleteFlagFalse(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Note note = opt.get();
        if (!isOwner(note, currentUserId)) return forbidden();

        String title    = (String) body.get("title");
        String noteBody = (String) body.get("body");
        if (title != null && !title.isBlank()) note.setTitle(title.trim());
        if (noteBody != null) note.setBody(noteBody);
        Note saved = noteRepo.save(note);
        return ResponseEntity.ok(toMap(saved, currentUserId));
    }

    // ── Toggle star ───────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/notes/{id}/star")
    public ResponseEntity<Map<String, Object>> toggleStar(@PathVariable Integer id,
                                                           HttpServletRequest request) {
        Integer currentUserId = getCurrentUserId(request);
        Optional<Note> opt = noteRepo.findByIdAndDeleteFlagFalse(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Note note = opt.get();
        // Any authenticated user can star/unstar any note — no owner check here

        note.setStarred(!note.isStarred());
        Note saved = noteRepo.save(note);
        return ResponseEntity.ok(toMap(saved, currentUserId));
    }

    // ── Delete (creator only) ─────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/notes/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                       HttpServletRequest request) {
        Integer currentUserId = getCurrentUserId(request);
        Optional<Note> opt = noteRepo.findByIdAndDeleteFlagFalse(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Note note = opt.get();
        if (!isOwner(note, currentUserId)) return forbidden();

        note.setDeleteFlag(true);
        noteRepo.save(note);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        return ResponseEntity.ok(ok);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toMap(Note n, Integer currentUserId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            n.getId());
        m.put("title",         n.getTitle());
        m.put("body",          n.getBody());
        m.put("createdById",   n.getCreatedById());
        m.put("createdByName", n.getCreatedByName());
        m.put("starred",       n.isStarred());
        m.put("createdDate",   n.getCreatedDate() != null ? n.getCreatedDate().toString() : null);
        m.put("updatedDate",   n.getUpdatedDate() != null ? n.getUpdatedDate().toString() : null);
        m.put("isOwner",       isOwner(n, currentUserId));
        return m;
    }

    private boolean isOwner(Note note, Integer currentUserId) {
        if (currentUserId == null) return false;
        return currentUserId.equals(note.getCreatedById());
    }

    private Integer getCurrentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        return getSessionUserId(session);
    }

    private Integer getSessionUserId(HttpSession session) {
        // appUserId is the AppUser.id (integer PK), set at login
        Object uid = session.getAttribute("appUserId");
        if (uid instanceof Integer i) return i;
        if (uid instanceof String s) {
            try { return Integer.parseInt(s); } catch (Exception ignored) {}
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return ResponseEntity.badRequest().body(m);
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "You can only edit or delete your own notes.");
        return ResponseEntity.status(403).body(m);
    }
}
