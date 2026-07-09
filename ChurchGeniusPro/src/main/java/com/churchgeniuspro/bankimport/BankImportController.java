package com.churchgeniuspro.bankimport;

import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.service.BankStatementExtractor;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bank / credit-card statement import (Accounting → Bank Import).
 *
 * <p>Uploads a CSV / OFX / QFX export — or a photo / PDF of a statement (OCR) —
 * parses the transactions, auto-categorizes them and auto-detects the
 * giving/expense fund, then returns them for review. Gated to accounting-capable
 * staff (SuperAdmin / Admin / Accountant).
 */
@Controller
public class BankImportController {

    private final BankStatementExtractor extractor;
    private final IncomeRepository incomeRepo;
    private final ExpenseRepository expenseRepo;
    private final com.churchgeniuspro.service.VisionCheckService vision;
    private final com.churchgeniuspro.service.VisionUploadService visionUpload;

    public BankImportController(BankStatementExtractor extractor,
                                IncomeRepository incomeRepo,
                                ExpenseRepository expenseRepo,
                                com.churchgeniuspro.service.VisionCheckService vision,
                                com.churchgeniuspro.service.VisionUploadService visionUpload) {
        this.extractor = extractor;
        this.incomeRepo = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.vision = vision;
        this.visionUpload = visionUpload;
    }

    /** Bank Import page. */
    @GetMapping("/bank-import")
    public String page(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "accounting.bankimport");
        if (deny != null) return deny;
        return "forward:/bank-import.html";
    }

    /** Parse + categorize an uploaded statement. Returns rows + a summary. */
    @ResponseBody
    @PostMapping(value = "/api/bank-import/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> parse(@RequestParam("file") MultipartFile file,
                                                      HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in to import."));
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));

        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        if (!(lower.endsWith(".csv") || lower.endsWith(".ofx") || lower.endsWith(".qfx") || lower.endsWith(".txt"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unsupported file type. Upload a .csv, .ofx, or .qfx bank/credit-card export."));
        }

        try {
            List<BankTxn> txns = BankStatementParser.parse(name, file.getBytes());
            for (BankTxn t : txns) TransactionCategorizer.categorize(t);
            // Newest first (date is normalized ISO so string sort == date sort).
            txns.sort((a, b) -> safe(b.date).compareTo(safe(a.date)));

            List<Map<String, Object>> rows = new ArrayList<>();
            int credits = 0, debits = 0;
            BigDecimal totalIn = BigDecimal.ZERO, totalOut = BigDecimal.ZERO;
            for (BankTxn t : txns) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", t.date);
                m.put("description", t.description);
                m.put("amount", t.amount);
                m.put("category", t.category);
                m.put("fund", t.fund);
                m.put("confidence", t.confidence);
                rows.add(m);
                if (t.amount != null && t.amount.signum() > 0) { credits++; totalIn = totalIn.add(t.amount); }
                else if (t.amount != null) { debits++; totalOut = totalOut.add(t.amount.abs()); }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("count", rows.size());
            summary.put("credits", credits);
            summary.put("debits", debits);
            summary.put("totalIn", totalIn);
            summary.put("totalOut", totalOut);
            summary.put("fileName", name);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("data", rows);
            resp.put("summary", summary);
            if (rows.isEmpty()) {
                resp.put("message", "No transactions could be read from this file. "
                        + "Make sure it is a CSV with Date/Description/Amount columns, or an OFX/QFX export.");
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not read the file: " + e.getMessage()));
        }
    }

    /**
     * Photo / PDF statement upload → OCR/extract transactions. PDFs are parsed
     * server-side; images are flagged so the browser OCR's them and calls
     * {@code /scan-parse}. Returns the SAME {data, summary} shape as {@code /parse}.
     */
    @ResponseBody
    @PostMapping(value = "/api/bank-import/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> scan(@RequestParam("file") MultipartFile file,
                                                     HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in to import."));
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));
        byte[] bytes;
        BankStatementExtractor.Result r;
        try {
            bytes = file.getBytes();
            r = extractor.extract(bytes, file.getOriginalFilename(), file.getContentType());
        }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", "Could not read the file: " + e.getMessage())); }

        // Photos → vision model when configured (reads handwriting / screenshots);
        // otherwise the browser OCR path handles it (image-needs-ocr).
        // OpenAI paths are gated by the church's Vision quota. Over quota / disabled
        // → fall back to the deterministic parse or browser OCR.
        if ("image-needs-ocr".equals(r.method) && vision.isEnabled() && visionUpload.available(clientId)) {
            try {
                com.churchgeniuspro.service.VisionUploadService.Prepared prep =
                        visionUpload.prepare(clientId, bytes, file.getContentType(), file.getOriginalFilename());
                List<Map<String, Object>> txns = vision.extractStatement(prep.bytes, prep.contentType);
                visionUpload.recordUse(clientId);
                if (txns != null && !txns.isEmpty()) r = extractor.fromVisionTransactions(txns);
            } catch (com.churchgeniuspro.service.VisionUploadService.VisionRejectedException re) {
                return ResponseEntity.badRequest().body(Map.of("error", re.getMessage()));
            } catch (Exception ignore) { /* keep fallback */ }
        }
        // Text-layer PDF whose layout the line parser couldn't read → ask the AI to
        // structure the extracted text (cheaper than vision; no image conversion).
        else if (!r.readable && r.rawText != null && !r.rawText.isBlank()
                 && vision.isEnabled() && visionUpload.available(clientId)) {
            try {
                List<Map<String, Object>> txns = vision.extractStatementFromText(r.rawText);
                visionUpload.recordUse(clientId);
                if (txns != null && !txns.isEmpty()) r = extractor.fromVisionTransactions(txns);
            } catch (Exception ignore) { /* keep fallback */ }
        }
        return ResponseEntity.ok(scanResponse(r, file.getOriginalFilename()));
    }

    /** Parse statement text recognized in the browser (Tesseract.js) for image uploads. */
    @ResponseBody
    @PostMapping(value = "/api/bank-import/scan-parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> scanParse(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in to import."));
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        int ocrConf = 0;
        try { if (body.get("ocrConfidence") != null) ocrConf = (int) Math.round(Double.parseDouble(String.valueOf(body.get("ocrConfidence")))); }
        catch (Exception ignore) { }
        String fileName = body.get("fileName") == null ? null : String.valueOf(body.get("fileName"));
        BankStatementExtractor.Result r = extractor.extractFromText(text, ocrConf);
        return ResponseEntity.ok(scanResponse(r, fileName));
    }

    /**
     * Duplicate detection: does an active income/expense already exist for this org
     * with the same date + amount (+ matching ref/check number if provided)?
     * Body: {type, date (yyyy-MM-dd), amount, refNo, checkNo}.
     */
    @ResponseBody
    @PostMapping(value = "/api/bank-import/check-duplicate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> checkDuplicate(@RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        LocalDate date;
        BigDecimal amount;
        try {
            date = LocalDate.parse(String.valueOf(body.get("date")));
            amount = new BigDecimal(String.valueOf(body.get("amount"))).abs();
        } catch (Exception e) { return ResponseEntity.ok(out); }

        String type = body.get("type") == null ? "" : String.valueOf(body.get("type"));
        String refNo = body.get("refNo") == null ? null : String.valueOf(body.get("refNo")).trim();
        String checkNo = body.get("checkNo") == null ? null : String.valueOf(body.get("checkNo")).trim();
        String wantRef = (refNo != null && !refNo.isEmpty()) ? refNo : checkNo;

        // Check the requested type first, then the other.
        boolean incomeFirst = !"expense".equalsIgnoreCase(type);
        Map<String, Object> hit = incomeFirst
                ? firstNonNull(matchIncome(clientId, date, amount, wantRef), matchExpense(clientId, date, amount, wantRef))
                : firstNonNull(matchExpense(clientId, date, amount, wantRef), matchIncome(clientId, date, amount, wantRef));
        if (hit != null) { hit.put("found", true); return ResponseEntity.ok(hit); }
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> matchIncome(String clientId, LocalDate date, BigDecimal amount, String wantRef) {
        List<Income> list = incomeRepo.findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(clientId, date, amount);
        Income best = pick(list, wantRef, Income::getRefNo);
        if (best == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "income");
        m.put("id", best.getId());
        m.put("date", date.toString());
        m.put("amount", amount);
        m.put("refNo", best.getRefNo());
        return m;
    }

    private Map<String, Object> matchExpense(String clientId, LocalDate date, BigDecimal amount, String wantRef) {
        List<Expense> list = expenseRepo.findByAppClientIdAndExpenseDateAndAmountAndDeleteFlagFalse(clientId, date, amount);
        Expense best = pick(list, wantRef, Expense::getRefNo);
        if (best == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "expense");
        m.put("id", best.getId());
        m.put("date", date.toString());
        m.put("amount", amount);
        m.put("refNo", best.getRefNo());
        return m;
    }

    private <T> T pick(List<T> list, String wantRef, java.util.function.Function<T, String> refOf) {
        if (list == null || list.isEmpty()) return null;
        if (wantRef != null && !wantRef.isEmpty()) {
            for (T t : list) {
                String r = refOf.apply(t);
                if (r != null && r.equalsIgnoreCase(wantRef)) return t;   // strong match on ref/check
            }
        }
        return list.get(0);   // date + amount match (possible duplicate)
    }

    @SafeVarargs
    private static <X> X firstNonNull(X... xs) {
        for (X x : xs) if (x != null) return x;
        return null;
    }

    private Map<String, Object> scanResponse(BankStatementExtractor.Result r, String fileName) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("method", r.method);
        resp.put("readable", r.readable);
        if (r.note != null && !r.note.isEmpty()) resp.put("note", r.note);
        resp.put("data", r.rows);
        if (fileName != null && r.summary != null) r.summary.put("fileName", fileName);
        resp.put("summary", r.summary);
        if (r.rows.isEmpty() && !"image-needs-ocr".equals(r.method)) {
            resp.put("message", "No transactions could be read. Please review or enter them manually.");
        }
        return resp;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
