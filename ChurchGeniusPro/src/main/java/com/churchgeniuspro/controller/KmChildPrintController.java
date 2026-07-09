package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.KmChildSetup;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.repository.KmChildSetupRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.KmChildFormExtractor;
import com.churchgeniuspro.service.KmChildFormPdfService;
import com.churchgeniuspro.service.VisionUsageTracker;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Printable/fillable Child Registration Form PDF download, and the scanned-form
 * upload → extract → duplicate-check endpoint used by the Kids Ministry page.
 */
@Controller
public class KmChildPrintController {

    private final KmChildFormPdfService pdfService;
    private final KmChildFormExtractor  extractor;
    private final ServiceClientRepository clientRepo;
    private final ChurchLogoRepository    logoRepo;
    private final KmChildRepository       childRepo;
    private final KmChildSetupRepository  setupRepo;
    private final VisionUsageTracker      visionUsage;

    public KmChildPrintController(KmChildFormPdfService pdfService,
                                  KmChildFormExtractor extractor,
                                  ServiceClientRepository clientRepo,
                                  ChurchLogoRepository logoRepo,
                                  KmChildRepository childRepo,
                                  KmChildSetupRepository setupRepo,
                                  VisionUsageTracker visionUsage) {
        this.pdfService = pdfService;
        this.extractor  = extractor;
        this.clientRepo = clientRepo;
        this.logoRepo   = logoRepo;
        this.childRepo  = childRepo;
        this.setupRepo  = setupRepo;
        this.visionUsage = visionUsage;
    }

    /** Branded, printable + fillable Child Registration Form. */
    @GetMapping("/api/kids-ministry/child-form.pdf")
    public ResponseEntity<byte[]> childFormPdf(HttpServletRequest request) {
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        String churchName = clientRepo.findByClientId(clientId)
                .map(ServiceClient::getChurchName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        ChurchLogo logo = logoRepo.findByClientId(clientId).orElse(null);
        byte[] logoBytes = (logo != null && logo.getLogoData() != null && logo.getLogoData().length > 0) ? logo.getLogoData() : null;
        String logoType  = logo != null ? logo.getContentType() : null;

        KmChildSetup setup = setupRepo.findByClientId(clientId).orElse(null);
        byte[] pdf = pdfService.generate(churchName, logoBytes, logoType, setup);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"child-registration-form.pdf\"")
                .body(pdf);
    }

    /**
     * Reads an uploaded child-registration form (digital PDF) and returns the extracted
     * fields plus any existing-child match (by parent email, then parent phone) for
     * de-duplication.
     */
    @ResponseBody
    @PostMapping(value = "/api/kids-ministry/child-scan-extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> scanExtract(@RequestParam("file") MultipartFile file,
                                                           HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));

        KmChildFormExtractor.Result result;
        try {
            String username = SessionUtil.getUsername(request);
            result = extractor.extract(file.getBytes(), file.getOriginalFilename(), file.getContentType(), clientId, username);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not read the file: " + e.getMessage()));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("method", result.method);
        if (result.note != null && !result.note.isEmpty()) resp.put("note", result.note);
        resp.put("fields", result.fields);
        resp.put("children", result.children);     // one entry per completed Child N section
        resp.put("childCount", result.children.size());
        resp.put("shared", result.shared);         // parent/guardian + emergency for all children
        resp.put("pickups", result.pickups);       // authorized pickups (vision OCR)

        // Per-child duplicate matches (index-aligned with `children`), using the
        // tenant's configured matching criteria.
        KmChildSetup setup = setupRepo.findByClientId(clientId).orElse(null);
        List<KmChild> all = childRepo.findByClientIdAndDeleteFlagFalseAndInactiveFalseOrderByLastNameAscFirstNameAsc(clientId);
        List<Object> childMatches = new ArrayList<>();
        for (Map<String, String> child : result.children) {
            childMatches.add(findChildMatches(child, result.shared, setup, all));
        }
        resp.put("childMatches", childMatches);

        // ── duplicate check by parent email, then parent phone (same client) ──
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("found", false);
        KmChild match = null;
        String email = result.fields.get("parentEmail");
        String phone = result.fields.get("parentPhone");
        if (email != null && !email.isBlank()) {
            match = first(childRepo.findByClientIdAndParentEmailIgnoreCase(clientId, email.trim()));
        }
        if (match == null && phone != null && !phone.isBlank()) {
            String digits = phone.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) match = first(childRepo.findByClientIdAndParentPhone(clientId, digits));
        }
        if (match != null) {
            dup.put("found", true);
            dup.put("childId", match.getId());
            dup.put("child", childMap(match));
        }
        resp.put("duplicate", dup);
        return ResponseEntity.ok(resp);
    }

    /**
     * Admin-only AI Scan (Vision/OCR) usage report for this church: summary totals
     * (scans, success/fail/rejected, tokens, estimated cost, children extracted) plus
     * the most recent calls. Drives the "AI Scan Usage" panel on the Kids Ministry page.
     */
    @ResponseBody
    @GetMapping("/api/kids-ministry/vision-usage")
    public ResponseEntity<Map<String, Object>> visionUsage(@RequestParam(value = "limit", defaultValue = "50") int limit,
                                                           HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        return ResponseEntity.ok(visionUsage.report(clientId, limit));
    }

    private static KmChild first(List<KmChild> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /**
     * Potential existing-child matches for an extracted child, using the tenant's
     * configured criteria (child name + DOB, parent phone, parent email). Returns
     * one entry per candidate with a human-readable match reason.
     */
    private List<Map<String, Object>> findChildMatches(Map<String, String> child,
                                                       Map<String, String> shared,
                                                       KmChildSetup setup,
                                                       List<KmChild> all) {
        boolean byName  = setup == null || setup.isDedupeName();
        boolean byPhone = setup == null || setup.isDedupePhone();
        boolean byEmail = setup == null || setup.isDedupeEmail();
        String fn  = lc(child.get("firstName"));
        String ln  = lc(child.get("lastName"));
        String dob = child.get("dob");
        String pPhone = shared != null ? digitsOnly(shared.get("parentPhone")) : "";
        String pEmail = shared != null ? lc(shared.get("parentEmail")) : null;

        List<Map<String, Object>> out = new ArrayList<>();
        for (KmChild ec : all) {
            List<String> reasons = new ArrayList<>();
            if (byName && fn != null && ln != null
                    && fn.equals(lc(ec.getFirstName())) && ln.equals(lc(ec.getLastName()))) {
                String ecDob = ec.getDob() != null ? ec.getDob().toString() : null;
                if (dob != null && ecDob != null) {
                    if (dob.equals(ecDob)) reasons.add("name + DOB");   // same DOB → same child
                    // different DOB → likely a sibling with the same name; not a match
                } else {
                    reasons.add("name");
                }
            }
            if (byPhone && !pPhone.isEmpty() && pPhone.equals(digitsOnly(ec.getParentPhone())))
                reasons.add("parent phone");
            if (byEmail && pEmail != null && !pEmail.isEmpty()
                    && pEmail.equals(lc(ec.getParentEmail())))
                reasons.add("parent email");

            if (!reasons.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("childId", ec.getId());
                m.put("name", (nz(ec.getFirstName()) + " " + nz(ec.getLastName())).trim());
                m.put("dob", ec.getDob() != null ? ec.getDob().toString() : null);
                m.put("parentName", ec.getParentName());
                m.put("parentPhone", ec.getParentPhone());
                m.put("matchReason", String.join(", ", reasons));
                out.add(m);
            }
        }
        return out;
    }

    private static String lc(String s) { return s == null ? null : s.trim().toLowerCase(); }
    private static String digitsOnly(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }
    private static String nz(String s) { return s == null ? "" : s; }

    private static Map<String, Object> childMap(KmChild c) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("firstName", c.getFirstName());
        x.put("lastName",  c.getLastName());
        x.put("dob",       c.getDob() != null ? c.getDob().toString() : null);
        x.put("gender",    c.getGender());
        x.put("grade",     c.getGrade());
        x.put("parentName",  c.getParentName());
        x.put("parentPhone", c.getParentPhone());
        x.put("parentEmail", c.getParentEmail());
        x.put("emergencyContactName",  c.getEmergencyContactName());
        x.put("emergencyContactPhone", c.getEmergencyContactPhone());
        x.put("allergies",    c.getAllergies());
        x.put("medicalNotes", c.getMedicalNotes());
        return x;
    }
}
