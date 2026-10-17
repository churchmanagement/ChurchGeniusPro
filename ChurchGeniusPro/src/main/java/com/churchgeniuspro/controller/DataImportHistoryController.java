package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ImportAudit;
import com.churchgeniuspro.hibernate.ImportBatch;
import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.repository.ImportAuditRepository;
import com.churchgeniuspro.repository.ImportBatchRepository;
import com.churchgeniuspro.repository.ImportRunRepository;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SourcePreset;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * ETL Migration Phase 5 (approved decision 5) — the church's own, read-only view of the
 * data ChurchGeniusPro support imported for it: when, from which product, how many records
 * of each kind, and whether the import was later undone. Shown on Church Settings.
 *
 * <p>Tenant: always the signed-in session's church — there is no clientId parameter, so a
 * church can never see another church's history. Roles: Admin or Church (the two roles that
 * reach Church Settings). Deliberately left out: who at ChurchGeniusPro ran it (always
 * "ChurchGeniusPro support"), run notes, file names, row-level data and runs that never
 * loaded anything.
 */
@RestController
public class DataImportHistoryController {

    private final ImportRunRepository runRepo;
    private final ImportBatchRepository batchRepo;
    private final ImportAuditRepository auditRepo;

    public DataImportHistoryController(ImportRunRepository runRepo, ImportBatchRepository batchRepo, ImportAuditRepository auditRepo) {
        this.runRepo = runRepo; this.batchRepo = batchRepo; this.auditRepo = auditRepo;
    }

    @GetMapping("/api/data-imports")
    public ResponseEntity<Map<String, Object>> history(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Login required"));
        if (RoleGuard.requireAdminOrChurch(request) != null)
            return ResponseEntity.status(403).body(Map.of("status", "error", "message", "Admin or Church role required"));

        List<Map<String, Object>> out = new ArrayList<>();
        for (ImportRun r : runRepo.findByClientIdOrderByCreatedDateDesc(clientId)) {
            if (!"LOADED".equals(r.getStatus()) && !"ROLLED_BACK".equals(r.getStatus())) continue;
            Map<String, Long> added = new LinkedHashMap<>(), updated = new LinkedHashMap<>();
            for (String t : com.churchgeniuspro.service.EtlLoadService.LOAD_ORDER) {
                for (ImportBatch b : batchRepo.findByRunIdOrderByIdAsc(r.getId())) {
                    if (!clientId.equals(b.getClientId()) || !t.equals(b.getTargetTable())) continue;
                    if (!Set.of("LOADED", "FAILED", "ROLLED_BACK").contains(b.getStatus())) continue;
                    String label = EtlTargetSchema.LABELS.getOrDefault(t, t);
                    added.merge(label, (long) (b.getInsertedCount() == null ? 0 : b.getInsertedCount()), Long::sum);
                    updated.merge(label, (long) (b.getUpdatedCount() == null ? 0 : b.getUpdatedCount()), Long::sum);
                }
            }
            String importedOn = null, undoneOn = null;
            for (ImportAudit a : auditRepo.findByRunIdAndClientIdOrderByCreatedDateAsc(r.getId(), clientId)) {
                if (importedOn == null && "LOAD".equals(a.getAction())) importedOn = String.valueOf(a.getCreatedDate());
                if ("ROLLBACK".equals(a.getAction())) undoneOn = String.valueOf(a.getCreatedDate());
            }
            if (importedOn == null) continue;   // nothing was ever loaded
            SourcePreset p = SourcePreset.of(r.getSourcePreset());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("importedOn", importedOn);
            m.put("importedBy", "ChurchGeniusPro support");
            m.put("source", p == SourcePreset.GENERIC ? "Spreadsheet (CSV / Excel)" : p.label());
            m.put("status", "ROLLED_BACK".equals(r.getStatus()) ? "Undone" : "Imported");
            m.put("undoneOn", "ROLLED_BACK".equals(r.getStatus()) ? undoneOn : null);
            m.put("added", added);
            m.put("updated", updated);
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "ok");
        res.put("imports", out);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(res);
    }
}
