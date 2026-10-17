package com.churchgeniuspro.service;

import com.churchgeniuspro.common.ImportRunStatus;
import com.churchgeniuspro.hibernate.ImportAudit;
import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.repository.ImportAuditRepository;
import com.churchgeniuspro.repository.ImportRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run lifecycle for the AI-assisted ETL pipeline (Phase 1).
 *
 * <p>Responsibilities in this phase: create a run bound to exactly one tenant,
 * enforce the {@link ImportRunStatus} transition rules, discard, read, and list —
 * with an append-only audit entry for every mutation. <b>No live writes</b> to
 * family/income/expense happen here; staging, validation, and loading are later
 * phases.
 *
 * <p>Tenant safety: every method that touches a specific run takes the caller's
 * {@code clientId} and looks the run up with {@link ImportRunRepository#findByIdAndClientId}
 * so a run from one tenant can never be read or mutated under another.
 */
@Service
public class ImportRunService {

    private static final Logger log = LoggerFactory.getLogger(ImportRunService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImportRunRepository   runRepo;
    private final ImportAuditRepository auditRepo;

    public ImportRunService(ImportRunRepository runRepo, ImportAuditRepository auditRepo) {
        this.runRepo   = runRepo;
        this.auditRepo = auditRepo;
    }

    /** Thrown for an illegal lifecycle transition or a missing/cross-tenant run. */
    public static class ImportRunException extends RuntimeException {
        public ImportRunException(String message) { super(message); }
    }

    // ───────────────────────── create ─────────────────────────

    /**
     * Create a new run bound to {@code clientId}. The tenant is fixed here and
     * never changes; it is NOT derived from any imported data.
     */
    @Transactional
    public ImportRun createRun(String clientId, String createdBy, String sourceLabel) {
        return createRun(clientId, createdBy, sourceLabel, null, null, false);
    }

    /**
     * Migration Phase 1: the run also records the source application preset, the
     * date order its files use and whether AI mapping assistance is allowed.
     */
    @Transactional
    public ImportRun createRun(String clientId, String createdBy, String sourceLabel,
                               String sourcePreset, String dateOrder, boolean aiAssist) {
        if (clientId == null || clientId.isBlank())
            throw new ImportRunException("clientId is required to create an import run");
        if (createdBy == null || createdBy.isBlank())
            throw new ImportRunException("createdBy is required");

        ImportRun run = new ImportRun();
        run.setClientId(clientId.trim());
        run.setCreatedBy(createdBy.trim());
        run.setSourceLabel(sourceLabel == null ? null : sourceLabel.trim());
        run.setStatus(ImportRunStatus.DRAFT.name());
        com.churchgeniuspro.util.SourcePreset preset = com.churchgeniuspro.util.SourcePreset.of(sourcePreset);
        run.setSourcePreset(preset.name());
        String order = com.churchgeniuspro.util.Transforms.Context.of(
            dateOrder == null || dateOrder.isBlank() ? preset.dateOrder() : dateOrder).dateOrder();
        run.setDateOrder(order);
        run.setAiAssist(aiAssist);
        run = runRepo.save(run);

        audit(run, createdBy, "CREATE", Map.of(
            "status", run.getStatus(),
            "sourceLabel", String.valueOf(run.getSourceLabel()),
            "sourcePreset", preset.name(),
            "dateOrder", order,
            "aiAssist", aiAssist));
        log.info("ETL run {} created for tenant {} by {}", run.getId(), clientId, createdBy);
        return run;
    }

    // ───────────────────────── read ─────────────────────────

    /** Tenant-scoped fetch; throws if absent or owned by another tenant. */
    @Transactional(readOnly = true)
    public ImportRun getRun(Long id, String clientId) {
        return runRepo.findByIdAndClientId(id, clientId)
            .orElseThrow(() -> new ImportRunException("Import run " + id + " not found for this tenant"));
    }

    @Transactional(readOnly = true)
    public List<ImportRun> listRuns(String clientId) {
        return runRepo.findByClientIdOrderByCreatedDateDesc(clientId);
    }

    /** Migration Phase 5: recent runs in these statuses across all churches (Service Admin overview only). */
    @Transactional(readOnly = true)
    public List<ImportRun> recentAcrossChurches(java.util.Collection<String> statuses) {
        return runRepo.findTop50ByStatusInOrderByUpdatedDateDesc(statuses);
    }

    /**
     * Migration Phase 5: change a run's date order and/or label from the wizard. Allowed until
     * anything of the run has been approved or loaded (the date order decides how every
     * staged date was read, so it cannot move under an approved batch). Audited.
     */
    @Transactional
    public ImportRun updateSettings(Long id, String clientId, String actor, String dateOrder, String sourceLabel) {
        ImportRun run = getRun(id, clientId);
        ImportRunStatus st = ImportRunStatus.fromString(run.getStatus());
        if (st == null || st.isTerminal() || st == ImportRunStatus.APPROVED || st == ImportRunStatus.LOADED)
            throw new ImportRunException("Settings can only change before anything is approved or loaded (run is " + run.getStatus() + ")");
        Map<String, Object> detail = new LinkedHashMap<>();
        if (dateOrder != null) {
            String d = dateOrder.trim().toUpperCase();
            if (!java.util.Set.of("AUTO", "MDY", "DMY", "YMD").contains(d)) throw new ImportRunException("Date order must be AUTO, MDY, DMY or YMD");
            detail.put("dateOrder", d + " (was " + (run.getDateOrder() == null ? "AUTO" : run.getDateOrder()) + ")");
            run.setDateOrder(d);
        }
        if (sourceLabel != null) {
            String l = sourceLabel.trim();
            if (l.length() > 256) l = l.substring(0, 256);
            detail.put("sourceLabel", l);
            run.setSourceLabel(l.isEmpty() ? null : l);
        }
        run = runRepo.save(run);
        audit(run, actor, "SETTINGS", detail);
        return run;
    }

    @Transactional(readOnly = true)
    public List<ImportAudit> getAudit(Long runId, String clientId) {
        // Ensure the run belongs to the tenant before exposing its audit trail.
        getRun(runId, clientId);
        return auditRepo.findByRunIdAndClientIdOrderByCreatedDateAsc(runId, clientId);
    }

    // ───────────────────────── transition ─────────────────────────

    /**
     * Move a run to {@code target}, enforcing {@link ImportRunStatus} rules.
     * Rejects unknown states, illegal jumps, and transitions on terminal runs.
     */
    @Transactional
    public ImportRun transition(Long id, String clientId, String actor, ImportRunStatus target) {
        ImportRun run = getRun(id, clientId);
        ImportRunStatus current = ImportRunStatus.fromString(run.getStatus());
        if (current == null)
            throw new ImportRunException("Run " + id + " has an unrecognized status: " + run.getStatus());
        if (target == null)
            throw new ImportRunException("Target status is required");
        if (!current.canTransitionTo(target))
            throw new ImportRunException("Illegal transition " + current + " → " + target
                + " for run " + id);

        run.setStatus(target.name());
        run = runRepo.save(run);
        audit(run, actor, "STATUS", Map.of("from", current.name(), "to", target.name()));
        log.info("ETL run {} transitioned {} -> {} by {}", id, current, target, actor);
        return run;
    }

    /** Convenience: abandon a run before load (any non-terminal state → DISCARDED). */
    @Transactional
    public ImportRun discard(Long id, String clientId, String actor, String reason) {
        ImportRun run = getRun(id, clientId);
        ImportRunStatus current = ImportRunStatus.fromString(run.getStatus());
        if (current == null || !current.canTransitionTo(ImportRunStatus.DISCARDED))
            throw new ImportRunException("Run " + id + " cannot be discarded from status " + run.getStatus());

        run.setStatus(ImportRunStatus.DISCARDED.name());
        run = runRepo.save(run);
        audit(run, actor, "DISCARD", Map.of(
            "from", current.name(),
            "reason", reason == null ? "" : reason));
        log.info("ETL run {} discarded by {} ({})", id, actor, reason);
        return run;
    }

    /**
     * Record an audit entry from a later-phase service (extract, profile, …),
     * tenant-checked. Does not change run status.
     */
    @Transactional
    public void recordAudit(Long runId, String clientId, String actor, String action, Map<String, Object> detail) {
        ImportRun run = getRun(runId, clientId);
        audit(run, actor, action, detail);
    }

    // ───────────────────────── audit helper ─────────────────────────

    private void audit(ImportRun run, String actor, String action, Map<String, Object> detail) {
        ImportAudit a = new ImportAudit();
        a.setRunId(run.getId());
        a.setClientId(run.getClientId());
        a.setActor(actor == null ? "system" : actor);
        a.setAction(action);
        a.setDetail(toJson(detail));
        // Migration Phase 5: batch events (APPROVE, LOAD, LOAD_FAILED, ROLLBACK) carry their batch
        // in the detail; record it in the column too, so a batch's history is one indexed lookup.
        Object b = detail == null ? null : detail.get("batchId");
        if (b instanceof Number n) a.setBatchId(n.longValue());
        auditRepo.save(a);
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return MAPPER.writeValueAsString(detail == null ? new LinkedHashMap<>() : detail);
        } catch (Exception e) {
            return "{}";
        }
    }
}
