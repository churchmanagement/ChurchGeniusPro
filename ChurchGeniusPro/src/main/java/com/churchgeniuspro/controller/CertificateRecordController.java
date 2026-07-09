package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.IssuedCertificate;
import com.churchgeniuspro.repository.IssuedCertificateRepository;
import com.churchgeniuspro.util.SessionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence API for issued certificates (all certificate types).
 *
 * <ul>
 *   <li>{@code POST /api/certificates/issue}  — save a generated certificate.</li>
 *   <li>{@code GET  /api/certificates/issued} — list this church's records
 *       (optionally filtered by {@code ?type=}).</li>
 * </ul>
 *
 * Records are tenant-scoped by the session {@code appClientId}; the full field
 * set is preserved as JSON so a certificate can be re-rendered exactly.
 */
@RestController
public class CertificateRecordController {

    private final IssuedCertificateRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public CertificateRecordController(IssuedCertificateRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/api/certificates/issue")
    public ResponseEntity<?> issue(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        IssuedCertificate rec = new IssuedCertificate();
        rec.setClientId(clientId);
        rec.setCertType(str(body.get("certType"), "other"));
        rec.setTitle(str(body.get("title"), ""));
        rec.setRecipientName(str(body.get("recipientName"), ""));
        rec.setSecondaryName(strOrNull(body.get("secondaryName")));
        rec.setChurchName(str(body.get("churchName"), ""));
        rec.setIssuedDate(strOrNull(body.get("issuedDate")));
        rec.setCreatedBy(SessionUtil.getUsername(request));
        rec.setCreatedAt(Instant.now());

        // Record number: use the one supplied by the user, else auto-generate.
        String num = strOrNull(body.get("certNumber"));
        if (num == null || num.isBlank()) num = generateNumber(rec.getCertType());
        rec.setCertNumber(num);

        // Preserve the full editable field set as JSON.
        Object details = body.get("details");
        try {
            rec.setDetails(mapper.writeValueAsString(details != null ? details : body));
        } catch (Exception e) {
            rec.setDetails("{}");
        }

        IssuedCertificate saved = repo.save(rec);
        Map<String, Object> out = new HashMap<>();
        out.put("id", saved.getId());
        out.put("certNumber", saved.getCertNumber());
        out.put("createdAt", saved.getCreatedAt().toString());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/certificates/issued")
    public ResponseEntity<?> issued(@RequestParam(value = "type", required = false) String type,
                                    HttpServletRequest request) {
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        List<IssuedCertificate> rows = (type != null && !type.isBlank())
                ? repo.findByClientIdAndCertTypeOrderByCreatedAtDesc(clientId, type)
                : repo.findByClientIdOrderByCreatedAtDesc(clientId);
        return ResponseEntity.ok(rows);
    }

    /* ── helpers ───────────────────────────────────────────────────────────── */

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** Short, readable, reasonably unique record number, e.g. APPR-20260617-K3F9. */
    private String generateNumber(String certType) {
        String abbr = switch (certType == null ? "" : certType) {
            case "baptism" -> "BAPT";
            case "appreciation" -> "APPR";
            case "dedication" -> "DEDI";
            case "completion" -> "COMP";
            case "sunday-school" -> "SSCH";
            case "membership" -> "MEMB";
            case "marriage" -> "MARR";
            default -> "CERT";
        };
        String rand = Long.toString(System.nanoTime() % 1_679_616L, 36).toUpperCase();
        while (rand.length() < 4) rand = "0" + rand;
        return abbr + "-" + DAY.format(Instant.now()) + "-" + rand;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
