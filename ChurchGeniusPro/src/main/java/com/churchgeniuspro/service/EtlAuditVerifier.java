package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * ETL Phase 7 — end-to-end audit/consistency verifier. Given a run, it reconciles
 * the staged rows against the batches, the live {@code target_id}s, and the audit
 * trail, and reports any anomaly. This is the "did the pipeline do what the audit
 * says it did?" check an operator runs after a load (or before trusting a result).
 *
 * <p>Read-only. Each check yields PASS / WARN / FAIL with a human detail; the
 * overall {@code ok} flag is false if any check FAILs.
 */
@Service
public class EtlAuditVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StagingFamilyRepository  familyRepo;
    private final StagingIncomeRepository  incomeRepo;
    private final StagingExpenseRepository expenseRepo;
    private final ImportBatchRepository    batchRepo;
    private final ImportAuditRepository    auditRepo;

    public EtlAuditVerifier(StagingFamilyRepository familyRepo,
                            StagingIncomeRepository incomeRepo,
                            StagingExpenseRepository expenseRepo,
                            ImportBatchRepository batchRepo,
                            ImportAuditRepository auditRepo) {
        this.familyRepo  = familyRepo;
        this.incomeRepo  = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.batchRepo   = batchRepo;
        this.auditRepo   = auditRepo;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> verify(Long runId, String clientId) {
        List<Map<String, Object>> checks = new ArrayList<>();

        // Gather staged rows per table (rowStatus, targetId, batchId).
        List<Row> rows = new ArrayList<>();
        for (StagingFamily r : familyRepo.findByRunId(runId))
            rows.add(new Row("family", r.getRowStatus(), r.getTargetId(), r.getBatchId(), r.getClientId()));
        for (StagingIncome r : incomeRepo.findByRunId(runId))
            rows.add(new Row("income", r.getRowStatus(), r.getTargetId(), r.getBatchId(), r.getClientId()));
        for (StagingExpense r : expenseRepo.findByRunId(runId))
            rows.add(new Row("expense", r.getRowStatus(), r.getTargetId(), r.getBatchId(), r.getClientId()));

        List<ImportBatch> batches = batchRepo.findByRunIdOrderByIdAsc(runId);
        List<ImportAudit> audit = auditRepo.findByRunIdOrderByCreatedDateAsc(runId);

        // ── Check 1: tenant stamp consistency on staged rows ──
        long foreign = rows.stream().filter(x -> x.clientId == null || !x.clientId.equals(clientId)).count();
        checks.add(check("tenant_stamp",
            foreign == 0 ? "PASS" : "FAIL",
            foreign == 0 ? "All staged rows carry the run's client_id"
                         : foreign + " staged row(s) carry a different client_id"));

        // ── Check 2: every LOADED row has a live target_id ──
        long loadedNoTarget = rows.stream()
            .filter(x -> "LOADED".equals(x.rowStatus) && x.targetId == null).count();
        checks.add(check("loaded_have_target",
            loadedNoTarget == 0 ? "PASS" : "FAIL",
            loadedNoTarget == 0 ? "Every LOADED row has a live target id"
                                : loadedNoTarget + " LOADED row(s) have no target id"));

        // ── Check 3: every LOADED/ROLLED_BACK batch has a matching audit event ──
        Set<Long> loadAudited = batchIdsForAction(audit, "LOAD");
        Set<Long> rollbackAudited = batchIdsForAction(audit, "ROLLBACK");
        List<String> missingAudit = new ArrayList<>();
        for (ImportBatch b : batches) {
            if ("LOADED".equals(b.getStatus()) && !loadAudited.contains(b.getId()))
                missingAudit.add("batch " + b.getId() + " (LOAD)");
            if ("ROLLED_BACK".equals(b.getStatus()) && !rollbackAudited.contains(b.getId()))
                missingAudit.add("batch " + b.getId() + " (ROLLBACK)");
        }
        checks.add(check("batch_audit_present",
            missingAudit.isEmpty() ? "PASS" : "FAIL",
            missingAudit.isEmpty() ? "Every loaded/rolled-back batch has an audit event"
                                   : "Missing audit for: " + String.join(", ", missingAudit)));

        // ── Check 4: per-batch count reconciliation ──
        List<String> mismatches = new ArrayList<>();
        for (ImportBatch b : batches) {
            if (!"LOADED".equals(b.getStatus())) continue;
            long loadedRows = rows.stream()
                .filter(x -> b.getId().equals(x.batchId) && "LOADED".equals(x.rowStatus)).count();
            int claimed = nz(b.getInsertedCount()) + nz(b.getUpdatedCount());
            if (loadedRows != claimed)
                mismatches.add("batch " + b.getId() + ": counters say " + claimed
                    + " loaded, staging shows " + loadedRows);
        }
        checks.add(check("count_reconciliation",
            mismatches.isEmpty() ? "PASS" : "FAIL",
            mismatches.isEmpty() ? "Batch counters match staged LOADED rows"
                                 : String.join("; ", mismatches)));

        // ── Check 5: rolled-back batches leave no live LOADED rows ──
        List<String> notReversed = new ArrayList<>();
        for (ImportBatch b : batches) {
            if (!"ROLLED_BACK".equals(b.getStatus())) continue;
            long stillLoaded = rows.stream()
                .filter(x -> b.getId().equals(x.batchId) && "LOADED".equals(x.rowStatus)).count();
            if (stillLoaded > 0) notReversed.add("batch " + b.getId() + ": " + stillLoaded + " rows still LOADED");
        }
        checks.add(check("rollback_complete",
            notReversed.isEmpty() ? "PASS" : "FAIL",
            notReversed.isEmpty() ? "Rolled-back batches have no lingering LOADED rows"
                                  : String.join("; ", notReversed)));

        // ── Check 6 (informational): unbatched loadable rows remain ──
        long unbatchedLoadable = rows.stream()
            .filter(x -> ("VALID".equals(x.rowStatus) || "WARN".equals(x.rowStatus))).count();
        checks.add(check("pending_loadable",
            unbatchedLoadable == 0 ? "PASS" : "WARN",
            unbatchedLoadable == 0 ? "No loadable rows left unapproved"
                                   : unbatchedLoadable + " VALID/WARN row(s) not yet approved into a batch"));

        boolean ok = checks.stream().noneMatch(c -> "FAIL".equals(c.get("status")));

        Map<String, Object> rowStatusCounts = new TreeMap<>();
        for (Row r : rows) rowStatusCounts.merge(r.table + ":" + r.rowStatus, 1L, (a, c) -> (Long) a + (Long) c);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("runId", runId);
        out.put("verified", ok);
        out.put("stagedRows", rows.size());
        out.put("batches", batches.size());
        out.put("auditEvents", audit.size());
        out.put("rowStatusCounts", rowStatusCounts);
        out.put("checks", checks);
        return out;
    }

    private Set<Long> batchIdsForAction(List<ImportAudit> audit, String action) {
        Set<Long> ids = new HashSet<>();
        for (ImportAudit a : audit) {
            if (!action.equals(a.getAction()) || a.getDetail() == null) continue;
            try {
                JsonNode n = MAPPER.readTree(a.getDetail()).get("batchId");
                if (n != null && n.canConvertToLong()) ids.add(n.asLong());
            } catch (Exception ignore) { /* skip unparseable */ }
        }
        return ids;
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    private Map<String, Object> check(String name, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    private static final class Row {
        final String table, rowStatus, clientId;
        final Long targetId, batchId;
        Row(String table, String rowStatus, Long targetId, Long batchId, String clientId) {
            this.table = table; this.rowStatus = rowStatus;
            this.targetId = targetId; this.batchId = batchId; this.clientId = clientId;
        }
    }
}
