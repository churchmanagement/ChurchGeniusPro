package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.IncomeCheckImage;
import com.churchgeniuspro.repository.IncomeCheckImageRepository;
import com.churchgeniuspro.service.CheckExtractor;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.service.VisionCheckService;
import com.churchgeniuspro.service.VisionUploadService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Scan Check" support for the Income page: upload a check (image/PDF) → OCR/extract
 * → match contributor → return fields for review; plus storing the check image with
 * the saved income record.
 */
@Controller
public class CheckScanController {

    private final CheckExtractor extractor;
    private final IncomeService incomeService;
    private final IncomeCheckImageRepository imageRepo;
    private final VisionCheckService vision;
    private final VisionUploadService visionUpload;

    public CheckScanController(CheckExtractor extractor,
                               IncomeService incomeService,
                               IncomeCheckImageRepository imageRepo,
                               VisionCheckService vision,
                               VisionUploadService visionUpload) {
        this.extractor = extractor;
        this.incomeService = incomeService;
        this.imageRepo = imageRepo;
        this.vision = vision;
        this.visionUpload = visionUpload;
    }

    /** Upload a check image/PDF → extract fields (PDF parsed here; images flagged for browser OCR). */
    @ResponseBody
    @PostMapping(value = "/api/income/check-scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> scan(@RequestParam("file") MultipartFile file,
                                                     HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "accounting.income.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));

        byte[] bytes;
        CheckExtractor.Result result;
        try {
            bytes = file.getBytes();
            result = extractor.extract(bytes, file.getOriginalFilename(), file.getContentType());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not read the file: " + e.getMessage()));
        }

        // For photos, prefer the vision model (reads handwriting) when configured.
        // Falls back to the browser-OCR path (image-needs-ocr) when disabled, over
        // quota, or on error. The per-church Vision quota + size/page/resize limits
        // are applied here; they never affect the browser-OCR fallback.
        boolean visionPaused = false;
        if ("image-needs-ocr".equals(result.method) && vision.isEnabled()) {
            if (!visionUpload.available(clientId)) {
                visionPaused = true;   // quota used up or Vision disabled for this church
            } else {
                try {
                    VisionUploadService.Prepared prep =
                            visionUpload.prepare(clientId, bytes, file.getContentType(), file.getOriginalFilename());
                    Map<String, String> vf = vision.extractCheck(prep.bytes, prep.contentType);
                    visionUpload.recordUse(clientId);
                    if (vf != null && !vf.isEmpty()) result = visionToResult(vf);
                } catch (VisionUploadService.VisionRejectedException re) {
                    return ResponseEntity.badRequest().body(Map.of("error", re.getMessage()));
                } catch (Exception ignore) { /* keep the fallback result */ }
            }
        }
        Map<String, Object> resp = buildResponse(result, clientId);
        if (visionPaused) {
            resp.put("visionDisabled", true);
            resp.put("visionMessage", "AI scanning is paused — your OpenAI Vision quota is used up or has been "
                    + "disabled by your administrator. The image will be read in your browser instead.");
        }
        return ResponseEntity.ok(resp);
    }

    /** Diagnostic: is the OpenAI vision OCR active? (No secret is returned.) */
    @ResponseBody
    @GetMapping("/api/income/vision-status")
    public ResponseEntity<Map<String, Object>> visionStatus(HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "accounting.income");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        return ResponseEntity.ok(vision.status());
    }

    /** Build a CheckExtractor.Result from vision-extracted fields. */
    private CheckExtractor.Result visionToResult(Map<String, String> vf) {
        CheckExtractor.Result r = new CheckExtractor.Result();
        r.method = "vision";
        StringBuilder raw = new StringBuilder();
        String payor = vf.get("payorName");
        if (payor != null) { r.fields.put("payorName", payor); r.confidence.put("payorName", 96); raw.append(payor).append(' '); }
        String amt = cleanAmount(vf.get("amount"));
        if (amt != null) { r.fields.put("amount", amt); r.confidence.put("amount", 97); }
        String[] d = isoDate(vf.get("date"));
        if (d != null) { r.fields.put("date", d[0]); r.fields.put("dateDisplay", d[1]); r.confidence.put("date", 95); }
        String chk = vf.get("checkNo");
        if (chk != null) { chk = chk.replaceAll("[^0-9]", ""); if (!chk.isEmpty()) { r.fields.put("checkNo", chk); r.confidence.put("checkNo", 96); } }
        String memo = vf.get("memo");
        if (memo != null) { r.fields.put("memo", memo); r.confidence.put("memo", 92); raw.append(memo).append(' '); }
        String bank = vf.get("bank");
        if (bank != null) { r.fields.put("bank", bank); r.confidence.put("bank", 95); }
        r.rawText = raw.toString();
        r.readable = !r.fields.isEmpty();
        return r;
    }

    private static String cleanAmount(String s) {
        if (s == null) return null;
        String v = s.replaceAll("[^0-9.]", "");
        if (v.isEmpty()) return null;
        try { double d = Double.parseDouble(v); if (d <= 0 || d > 10_000_000) return null; return String.format("%.2f", d); }
        catch (NumberFormatException e) { return null; }
    }

    /** Parse MM/DD/YYYY (or M/D/YY, or yyyy-MM-dd) → [iso yyyy-MM-dd, display MM/DD/YYYY]. */
    private static String[] isoDate(String s) {
        if (s == null) return null;
        s = s.trim();
        java.util.regex.Matcher iso = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (iso.find()) return fmt(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})").matcher(s);
        if (m.find()) {
            int mo = Integer.parseInt(m.group(1)), da = Integer.parseInt(m.group(2));
            String y = m.group(3); int yr = Integer.parseInt(y);
            if (y.length() == 2) yr = (yr > 70 ? 1900 : 2000) + yr;
            return fmt(yr, mo, da);
        }
        return null;
    }

    private static String[] fmt(int yr, int mo, int da) {
        if (mo < 1 || mo > 12 || da < 1 || da > 31) return null;
        return new String[]{ String.format("%04d-%02d-%02d", yr, mo, da), String.format("%02d/%02d/%04d", mo, da, yr) };
    }

    /** Parse text recognized in the browser (Tesseract.js) when the upload was an image. */
    @ResponseBody
    @PostMapping(value = "/api/income/check-parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> parse(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "accounting.income.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));

        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        int ocrConf = 0;
        try { if (body.get("ocrConfidence") != null) ocrConf = (int) Math.round(Double.parseDouble(String.valueOf(body.get("ocrConfidence")))); }
        catch (Exception ignore) { }

        CheckExtractor.Result result = extractor.extractFromText(text, ocrConf);
        return ResponseEntity.ok(buildResponse(result, clientId));
    }

    /** Store the uploaded check image against a saved income record. */
    @ResponseBody
    @PostMapping(value = "/api/income/{incomeId}/check-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> storeImage(@PathVariable Integer incomeId,
                                                           @RequestParam("file") MultipartFile file,
                                                           HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "accounting.income.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file."));

        try {
            IncomeCheckImage img = new IncomeCheckImage();
            img.setIncomeId(incomeId);
            img.setClientId(clientId);
            img.setFilename(file.getOriginalFilename());
            img.setContentType(file.getContentType());
            img.setData(file.getBytes());
            img.setCreatedDate(LocalDateTime.now());
            imageRepo.save(img);
            return ResponseEntity.ok(Map.of("success", true, "id", img.getId()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not store the image: " + e.getMessage()));
        }
    }

    /** Retrieve the most recent stored check image for an income record. */
    @GetMapping("/api/income/{incomeId}/check-image")
    public ResponseEntity<byte[]> getImage(@PathVariable Integer incomeId, HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "accounting.income");
        if (deny != null) return ResponseEntity.status(403).build();
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        List<IncomeCheckImage> imgs = imageRepo.findByIncomeIdAndClientIdOrderByIdDesc(incomeId, clientId);
        if (imgs.isEmpty()) return ResponseEntity.notFound().build();
        IncomeCheckImage img = imgs.get(0);
        MediaType ct = MediaType.APPLICATION_OCTET_STREAM;
        try { if (img.getContentType() != null) ct = MediaType.parseMediaType(img.getContentType()); } catch (Exception ignore) { }
        return ResponseEntity.ok()
                .contentType(ct)
                .header("Content-Disposition", "inline; filename=\"check-" + incomeId + "\"")
                .body(img.getData());
    }

    // ── helpers ──

    private Map<String, Object> buildResponse(CheckExtractor.Result result, String clientId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("method", result.method);
        resp.put("readable", result.readable);
        resp.put("amountMismatch", result.amountMismatch);
        if (result.note != null && !result.note.isEmpty()) resp.put("note", result.note);
        resp.put("fields", result.fields);
        resp.put("confidence", result.confidence);
        resp.put("contributor", matchContributor(result.fields.get("payorName"), result.rawText, clientId));
        return resp;
    }

    /**
     * Fuzzy, case-insensitive contributor matching. Matches the extracted payor name
     * AND scans the full OCR text for any existing contributor's name — so a printed
     * name that survived OCR (e.g. "Anson Mathew") is found even when the single
     * "payor" line picked up noise.
     */
    private Map<String, Object> matchContributor(String payorName, String rawText, String clientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("autoSelectId", null);
        out.put("matches", new ArrayList<>());

        List<Map<String, Object>> contributors;
        try { contributors = incomeService.getContributors(clientId); }
        catch (Exception e) { return out; }

        String normText = " " + (rawText == null ? "" : rawText).toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> c : contributors) {
            Object full = c.get("fullName");
            if (full == null) continue;
            int score = 0;
            if (payorName != null && !payorName.isBlank()) score = CheckExtractor.nameScore(payorName, String.valueOf(full));
            int contained = textContainmentScore(String.valueOf(full), normText);
            if (contained > score) score = contained;
            if (score >= 60) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.get("id"));
                m.put("fullName", full);
                m.put("score", score);
                scored.add(m);
            }
        }
        scored.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("score")).reversed());
        if (scored.size() > 6) scored = new ArrayList<>(scored.subList(0, 6));
        out.put("matches", scored);

        // Auto-select when there is a single strong match, or a clear top match.
        if (scored.size() == 1 && (Integer) scored.get(0).get("score") >= 85) {
            out.put("autoSelectId", scored.get(0).get("id"));
        } else if (scored.size() >= 2) {
            int top = (Integer) scored.get(0).get("score");
            int second = (Integer) scored.get(1).get("score");
            if (top >= 92 && top - second >= 15) out.put("autoSelectId", scored.get(0).get("id"));
        }
        return out;
    }

    /**
     * Score how strongly a contributor's name appears as whole words within the OCR
     * text. Requires the surname (and, when present, the first name) to be found, so a
     * common first name alone won't trigger a false match. Returns 0..100.
     */
    private static int textContainmentScore(String fullName, String normText) {
        if (fullName == null || normText == null || normText.isBlank()) return 0;
        String norm = fullName.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (norm.isEmpty()) return 0;
        String[] toks = norm.split(" ");
        int total = 0, present = 0;
        for (String t : toks) {
            if (t.length() < 3) continue;          // skip initials / short tokens
            total++;
            if (normText.contains(" " + t + " ")) present++;
        }
        if (total == 0 || present == 0) return 0;
        // A single-token name needs that token present; multi-token names need >= 2 present.
        if (total >= 2 && present < 2) return 0;
        return Math.min(99, (int) Math.round(95.0 * present / total));
    }
}
