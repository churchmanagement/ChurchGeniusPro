package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ExpenseCheckImage;
import com.churchgeniuspro.repository.ExpenseCheckImageRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.service.CheckExtractor;
import com.churchgeniuspro.service.VisionCheckService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Scan Check" support for the Expense page — mirrors {@link CheckScanController}
 * (income) but for expense records: no contributor matching, and images are stored
 * against an expense id. PDFs are parsed server-side; photos use the vision model
 * when configured, otherwise the browser OCR path.
 */
@Controller
public class ExpenseCheckController {

    private final CheckExtractor extractor;
    private final VisionCheckService vision;
    private final ExpenseCheckImageRepository imageRepo;
    private final ExpenseRepository expenseRepo;
    private final com.churchgeniuspro.service.VisionUploadService visionUpload;

    public ExpenseCheckController(CheckExtractor extractor,
                                  VisionCheckService vision,
                                  ExpenseCheckImageRepository imageRepo,
                                  ExpenseRepository expenseRepo,
                                  com.churchgeniuspro.service.VisionUploadService visionUpload) {
        this.extractor = extractor;
        this.vision = vision;
        this.imageRepo = imageRepo;
        this.expenseRepo = expenseRepo;
        this.visionUpload = visionUpload;
    }

    /** Upload a check image/PDF → extract fields. */
    @ResponseBody
    @PostMapping(value = "/api/expense/check-scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> scan(@RequestParam("file") MultipartFile file,
                                                     HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense.edit");
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
        // OpenAI Vision path — gated by the church's quota + size/page/resize limits.
        // When over quota or disabled, fall back to browser OCR (image-needs-ocr).
        boolean visionPaused = false;
        if ("image-needs-ocr".equals(result.method) && vision.isEnabled()) {
            if (!visionUpload.available(clientId)) {
                visionPaused = true;
            } else {
                try {
                    com.churchgeniuspro.service.VisionUploadService.Prepared prep =
                            visionUpload.prepare(clientId, bytes, file.getContentType(), file.getOriginalFilename());
                    Map<String, String> vf = vision.extractCheck(prep.bytes, prep.contentType);
                    visionUpload.recordUse(clientId);
                    if (vf != null && !vf.isEmpty()) result = visionToResult(vf);
                } catch (com.churchgeniuspro.service.VisionUploadService.VisionRejectedException re) {
                    return ResponseEntity.badRequest().body(Map.of("error", re.getMessage()));
                } catch (Exception ignore) { /* keep fallback */ }
            }
        }
        Map<String, Object> resp = buildResponse(result);
        if (visionPaused) {
            resp.put("visionDisabled", true);
            resp.put("visionMessage", "AI scanning is paused — your OpenAI Vision quota is used up or has been "
                    + "disabled by your administrator. The image will be read in your browser instead.");
        }
        return ResponseEntity.ok(resp);
    }

    /** Parse text recognized in the browser (Tesseract.js) for image uploads. */
    @ResponseBody
    @PostMapping(value = "/api/expense/check-parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> parse(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));

        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        int ocrConf = 0;
        try { if (body.get("ocrConfidence") != null) ocrConf = (int) Math.round(Double.parseDouble(String.valueOf(body.get("ocrConfidence")))); }
        catch (Exception ignore) { }
        return ResponseEntity.ok(buildResponse(extractor.extractFromText(text, ocrConf)));
    }

    /** Store the uploaded check image against a saved expense record. */
    @ResponseBody
    @PostMapping(value = "/api/expense/{expenseId}/check-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> storeImage(@PathVariable Integer expenseId,
                                                           @RequestParam("file") MultipartFile file,
                                                           HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file."));
        // The image is attached to an expense row: only accept an id that belongs to this church.
        if (expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(expenseId, clientId).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Expense record not found: " + expenseId));
        }
        try {
            ExpenseCheckImage img = new ExpenseCheckImage();
            img.setExpenseId(expenseId);
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

    /** Retrieve the most recent stored check image for an expense record. */
    @GetMapping("/api/expense/{expenseId}/check-image")
    public ResponseEntity<byte[]> getImage(@PathVariable Integer expenseId, HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense");
        if (deny != null) return ResponseEntity.status(403).build();
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        List<ExpenseCheckImage> imgs = imageRepo.findByExpenseIdAndClientIdOrderByIdDesc(expenseId, clientId);
        if (imgs.isEmpty()) return ResponseEntity.notFound().build();
        ExpenseCheckImage img = imgs.get(0);
        MediaType ct = MediaType.APPLICATION_OCTET_STREAM;
        try { if (img.getContentType() != null) ct = MediaType.parseMediaType(img.getContentType()); } catch (Exception ignore) { }
        return ResponseEntity.ok()
                .contentType(ct)
                .header("Content-Disposition", "inline; filename=\"check-" + expenseId + "\"")
                .body(img.getData());
    }

    // ── helpers ──

    private Map<String, Object> buildResponse(CheckExtractor.Result result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("method", result.method);
        resp.put("readable", result.readable);
        resp.put("amountMismatch", result.amountMismatch);
        if (result.note != null && !result.note.isEmpty()) resp.put("note", result.note);
        resp.put("fields", result.fields);
        resp.put("confidence", result.confidence);
        return resp;
    }

    private CheckExtractor.Result visionToResult(Map<String, String> vf) {
        CheckExtractor.Result r = new CheckExtractor.Result();
        r.method = "vision";
        String payor = vf.get("payorName");
        if (payor != null) { r.fields.put("payorName", payor); r.confidence.put("payorName", 96); }
        String amt = cleanAmount(vf.get("amount"));
        if (amt != null) { r.fields.put("amount", amt); r.confidence.put("amount", 97); }
        String[] d = isoDate(vf.get("date"));
        if (d != null) { r.fields.put("date", d[0]); r.fields.put("dateDisplay", d[1]); r.confidence.put("date", 95); }
        String chk = vf.get("checkNo");
        if (chk != null) { chk = chk.replaceAll("[^0-9]", ""); if (!chk.isEmpty()) { r.fields.put("checkNo", chk); r.confidence.put("checkNo", 96); } }
        String memo = vf.get("memo");
        if (memo != null) { r.fields.put("memo", memo); r.confidence.put("memo", 92); }
        String bank = vf.get("bank");
        if (bank != null) { r.fields.put("bank", bank); r.confidence.put("bank", 95); }
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
}
