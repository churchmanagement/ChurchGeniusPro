package com.churchgeniuspro.controller;

import com.churchgeniuspro.common.ImportRunStatus;
import com.churchgeniuspro.hibernate.ImportAudit;
import com.churchgeniuspro.hibernate.ImportBatch;
import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.MappingRule;
import com.churchgeniuspro.hibernate.SourceColumnProfile;
import com.churchgeniuspro.hibernate.StagingExpense;
import com.churchgeniuspro.hibernate.StagingFamily;
import com.churchgeniuspro.hibernate.StagingIncome;
import com.churchgeniuspro.hibernate.StagingRaw;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SourceColumnProfileRepository;
import com.churchgeniuspro.repository.StagingExpenseRepository;
import com.churchgeniuspro.repository.StagingFamilyRepository;
import com.churchgeniuspro.repository.StagingIncomeRepository;
import com.churchgeniuspro.repository.StagingRawRepository;
import com.churchgeniuspro.service.EtlValidationService;
import com.churchgeniuspro.service.EtlValidationService.ValidationSummary;
import com.churchgeniuspro.service.ImportRunService;
import com.churchgeniuspro.service.ImportRunService.ImportRunException;
import com.churchgeniuspro.service.MappingService;
import com.churchgeniuspro.service.MappingService.MappingException;
import com.churchgeniuspro.service.SourceExtractService;
import com.churchgeniuspro.service.SourceExtractService.ExtractException;
import com.churchgeniuspro.service.SourceExtractService.ExtractResult;
import com.churchgeniuspro.service.SourceProfileService;
import com.churchgeniuspro.service.EtlAuditVerifier;
import com.churchgeniuspro.service.EtlConcurrencyGuard;
import com.churchgeniuspro.service.EtlConcurrencyGuard.BusyException;
import com.churchgeniuspro.service.EtlLoadService;
import com.churchgeniuspro.service.EtlLoadService.LoadException;
import com.churchgeniuspro.service.TransformStageService;
import com.churchgeniuspro.service.TransformStageService.StageException;
import com.churchgeniuspro.service.TransformStageService.StageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-Admin REST surface for the AI-assisted ETL pipeline (Phase 1: run
 * lifecycle only).
 *
 * <p><b>Authorization.</b> Every endpoint requires an authenticated Service Admin
 * — checked via the {@code serviceAdminId} session attribute set at
 * {@code /api/serviceadmin/login}. There is no per-church login here on purpose:
 * ETL is an operator tool. (The {@code AuthFilter} whitelists {@code /api/serviceadmin}
 * so these run outside the normal app-user gate.)
 *
 * <p><b>Tenant binding.</b> The caller supplies {@code clientId} explicitly and we
 * verify it is a real {@link com.churchgeniuspro.hibernate.ServiceClient} before
 * any run is created or read. The run then carries that tenant for its whole life;
 * reads/transitions are tenant-scoped in {@link ImportRunService}, so one tenant's
 * run can never be touched under another's id.
 *
 * <p>No live writes to family/income/expense exist in this phase.
 */
@RestController
@RequestMapping("/api/serviceadmin/etl")
public class ImportEtlController {

    private static final Logger log = LoggerFactory.getLogger(ImportEtlController.class);

    private final ImportRunService            runService;
    private final ServiceClientRepository     serviceClientRepository;
    private final SourceExtractService        extractService;
    private final SourceProfileService        profileService;
    private final StagingRawRepository        rawRepo;
    private final SourceColumnProfileRepository profileRepo;
    private final MappingService              mappingService;
    private final TransformStageService       stageService;
    private final EtlValidationService        validationService;
    private final StagingFamilyRepository     familyRepo;
    private final StagingIncomeRepository     incomeRepo;
    private final StagingExpenseRepository    expenseRepo;
    private final EtlLoadService              loadService;
    private final EtlConcurrencyGuard         guard;
    private final EtlAuditVerifier            verifier;

    public ImportEtlController(ImportRunService runService,
                               ServiceClientRepository serviceClientRepository,
                               SourceExtractService extractService,
                               SourceProfileService profileService,
                               StagingRawRepository rawRepo,
                               SourceColumnProfileRepository profileRepo,
                               MappingService mappingService,
                               TransformStageService stageService,
                               EtlValidationService validationService,
                               StagingFamilyRepository familyRepo,
                               StagingIncomeRepository incomeRepo,
                               StagingExpenseRepository expenseRepo,
                               EtlLoadService loadService,
                               EtlConcurrencyGuard guard,
                               EtlAuditVerifier verifier) {
        this.guard                   = guard;
        this.verifier                = verifier;
        this.runService              = runService;
        this.serviceClientRepository = serviceClientRepository;
        this.extractService          = extractService;
        this.profileService          = profileService;
        this.rawRepo                 = rawRepo;
        this.profileRepo             = profileRepo;
        this.mappingService          = mappingService;
        this.stageService            = stageService;
        this.validationService       = validationService;
        this.familyRepo              = familyRepo;
        this.incomeRepo              = incomeRepo;
        this.expenseRepo             = expenseRepo;
        this.loadService             = loadService;
    }

    // ───────────────────────── gate helpers ─────────────────────────

    /** @return the service-admin id, or null if not authenticated. */
    private Object adminId(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        return s == null ? null : s.getAttribute("serviceAdminId");
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(err("Service Admin login required"));
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> ok(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put(key, value);
        return m;
    }

    /** Validates the tenant exists; null/blank/unknown → not a real client. */
    private boolean isRealClient(String clientId) {
        return clientId != null && !clientId.isBlank()
            && serviceClientRepository.findByClientId(clientId.trim()).isPresent();
    }

    private Map<String, Object> runView(ImportRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("clientId", r.getClientId());
        m.put("createdBy", r.getCreatedBy());
        m.put("sourceLabel", r.getSourceLabel());
        m.put("status", r.getStatus());
        m.put("createdDate", String.valueOf(r.getCreatedDate()));
        m.put("updatedDate", String.valueOf(r.getUpdatedDate()));
        return m;
    }

    // ───────────────────────── endpoints ─────────────────────────

    /** Create a run for an explicit tenant. Body: {clientId, sourceLabel?}. */
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> createRun(@RequestBody Map<String, String> body,
                                                         HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();

        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));

        String createdBy = "serviceadmin:" + admin;
        try {
            ImportRun run = runService.createRun(clientId.trim(), createdBy, body.get("sourceLabel"));
            return ResponseEntity.ok(ok("run", runView(run)));
        } catch (ImportRunException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /** List runs for a tenant. Query: ?clientId=. */
    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> listRuns(@RequestParam String clientId,
                                                        HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));

        List<Map<String, Object>> runs = runService.listRuns(clientId.trim())
            .stream().map(this::runView).toList();
        return ResponseEntity.ok(ok("runs", runs));
    }

    /** Fetch one run (tenant-scoped). Query: ?clientId=. */
    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> getRun(@PathVariable Long id,
                                                     @RequestParam String clientId,
                                                     HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        try {
            return ResponseEntity.ok(ok("run", runView(runService.getRun(id, clientId.trim()))));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
    }

    /** Transition a run. Body: {clientId, status}. */
    @PostMapping("/runs/{id}/transition")
    public ResponseEntity<Map<String, Object>> transition(@PathVariable Long id,
                                                          @RequestBody Map<String, String> body,
                                                          HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();

        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));

        ImportRunStatus target = ImportRunStatus.fromString(body.get("status"));
        if (target == null)
            return ResponseEntity.status(400).body(err("Unknown target status: " + body.get("status")));

        try {
            ImportRun run = runService.transition(id, clientId.trim(), "serviceadmin:" + admin, target);
            return ResponseEntity.ok(ok("run", runView(run)));
        } catch (ImportRunException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        }
    }

    /** Discard a run. Body: {clientId, reason?}. */
    @PostMapping("/runs/{id}/discard")
    public ResponseEntity<Map<String, Object>> discard(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, String> body,
                                                      HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();

        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));

        try {
            ImportRun run = runService.discard(id, clientId.trim(), "serviceadmin:" + admin,
                body == null ? null : body.get("reason"));
            return ResponseEntity.ok(ok("run", runView(run)));
        } catch (ImportRunException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        }
    }

    // ───────────────────────── Phase 2: extract + profile ─────────────────────────

    /**
     * Extract an uploaded CSV/JSON source into {@code staging_raw} and profile it.
     * Multipart: {@code file} (required), {@code clientId} (required),
     * {@code sourceTable} (optional; defaults to the file name),
     * {@code format} (optional: "csv" | "json"; otherwise sniffed).
     * Advances the run DRAFT → PROFILED on first successful extract.
     */
    @PostMapping("/runs/{id}/extract")
    public ResponseEntity<Map<String, Object>> extract(@PathVariable Long id,
                                                       @RequestParam String clientId,
                                                       @RequestParam(required = false) String sourceTable,
                                                       @RequestParam(required = false) String format,
                                                       @RequestParam("file") MultipartFile file,
                                                       HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        if (file == null || file.isEmpty())
            return ResponseEntity.status(400).body(err("A non-empty file is required"));

        final String tenant = clientId.trim();
        final ImportRun run;
        try {
            run = runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
        ImportRunStatus current = ImportRunStatus.fromString(run.getStatus());
        if (current == null || current.isTerminal() || current == ImportRunStatus.LOADED)
            return ResponseEntity.status(409).body(err("Run is not in an extractable state: " + run.getStatus()));

        String name = file.getOriginalFilename() == null ? "source" : file.getOriginalFilename();
        String table = (sourceTable == null || sourceTable.isBlank())
            ? name.replaceAll("\\.[^.]*$", "") : sourceTable;

        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(err("Could not read upload: " + e.getMessage()));
        }

        boolean asJson = "json".equalsIgnoreCase(format)
            || (format == null && (name.toLowerCase().endsWith(".json") || looksLikeJson(text)));

        try {
            ExtractResult ex = asJson
                ? extractService.extractJson(run, table, text)
                : extractService.extractCsv(run, table, text);

            List<SourceColumnProfile> profile = profileService.profile(id, tenant, ex.sourceTable());

            runService.recordAudit(id, tenant, "serviceadmin:" + admin, "EXTRACT", Map.of(
                "sourceTable", ex.sourceTable(),
                "rowCount", ex.rowCount(),
                "columns", profile.size(),
                "format", asJson ? "json" : "csv"));

            // Advance lifecycle only from DRAFT; re-extracts leave PROFILED as-is.
            if (current == ImportRunStatus.DRAFT) {
                runService.transition(id, tenant, "serviceadmin:" + admin, ImportRunStatus.PROFILED);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("sourceTable", ex.sourceTable());
            out.put("rowCount", ex.rowCount());
            out.put("format", asJson ? "json" : "csv");
            out.put("profile", profile.stream().map(this::profileView).toList());
            return ResponseEntity.ok(out);
        } catch (ExtractException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /** Profile for a run. Query: ?clientId= and optional &sourceTable= (else all tables). */
    @GetMapping("/runs/{id}/profile")
    public ResponseEntity<Map<String, Object>> profile(@PathVariable Long id,
                                                       @RequestParam String clientId,
                                                       @RequestParam(required = false) String sourceTable,
                                                       HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);   // tenant ownership check
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }

        List<SourceColumnProfile> cols = (sourceTable == null || sourceTable.isBlank())
            ? profileRepo.findByRunIdOrderBySourceTableAscOrdinalAsc(id)
            : profileRepo.findByRunIdAndSourceTableOrderByOrdinalAsc(id, sourceTable);
        // group by source table for the UI
        Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();
        for (SourceColumnProfile p : cols) {
            byTable.computeIfAbsent(p.getSourceTable(), k -> new java.util.ArrayList<>())
                   .add(profileView(p));
        }
        return ResponseEntity.ok(ok("profile", byTable));
    }

    /** A few raw captured rows for traceability. Query: ?clientId=&sourceTable=&limit=. */
    @GetMapping("/runs/{id}/raw-sample")
    public ResponseEntity<Map<String, Object>> rawSample(@PathVariable Long id,
                                                        @RequestParam String clientId,
                                                        @RequestParam String sourceTable,
                                                        @RequestParam(defaultValue = "20") int limit,
                                                        HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
        int cap = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> sample = rawRepo.findByRunIdAndSourceTable(id, sourceTable)
            .stream().limit(cap).map(this::rawView).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("sourceTable", sourceTable);
        out.put("total", rawRepo.countByRunIdAndSourceTable(id, sourceTable));
        out.put("rows", sample);
        return ResponseEntity.ok(out);
    }

    private static boolean looksLikeJson(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("[") || t.startsWith("{");
    }

    private Map<String, Object> profileView(SourceColumnProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", p.getColumnName());
        m.put("ordinal", p.getOrdinal());
        m.put("type", p.getInferredType());
        m.put("total", p.getTotalCount());
        m.put("nonNull", p.getNonNullCount());
        m.put("nullCount", p.getNullCount());
        m.put("distinct", p.getDistinctCount());
        m.put("nullRate", p.getNullRate());
        m.put("minLength", p.getMinLength());
        m.put("maxLength", p.getMaxLength());
        m.put("samples", p.getSampleValues());
        return m;
    }

    private Map<String, Object> rawView(StagingRaw r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("sourceRowId", r.getSourceRowId());
        m.put("payload", r.getPayload());
        return m;
    }

    // ───────────────────────── Phase 3: mapping ─────────────────────────

    /**
     * Build mapping suggestions for a target table from a profiled source table
     * (deterministic + AI fallback). Body: {clientId, targetTable, sourceTable}.
     * Persists one {@code mapping_rule} per target column; preserves prior operator
     * decisions.
     */
    @PostMapping("/runs/{id}/mappings/suggest")
    public ResponseEntity<Map<String, Object>> suggestMappings(@PathVariable Long id,
                                                              @RequestBody Map<String, String> body,
                                                              HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
        try {
            return guard.guard(id, () -> {
                List<MappingRule> rules = mappingService.suggest(id, tenant, "serviceadmin:" + admin,
                    body.get("targetTable"), body.get("sourceTable"));
                return ResponseEntity.ok(ok("mappings", rules.stream().map(this::mappingView).toList()));
            });
        } catch (BusyException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (MappingException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /** List mapping rules. Query: ?clientId= and optional &targetTable=. */
    @GetMapping("/runs/{id}/mappings")
    public ResponseEntity<Map<String, Object>> listMappings(@PathVariable Long id,
                                                           @RequestParam String clientId,
                                                           @RequestParam(required = false) String targetTable,
                                                           HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
        List<Map<String, Object>> rules = mappingService.list(id, targetTable)
            .stream().map(this::mappingView).toList();
        return ResponseEntity.ok(ok("mappings", rules));
    }

    /**
     * Edit / confirm / reject one mapping rule. Body:
     * {clientId, targetTable, targetColumn, sourceTable?, sourceColumn?, transform?, decision}.
     * Advances PROFILED → MAPPED on first confirm.
     */
    @PutMapping("/runs/{id}/mappings")
    public ResponseEntity<Map<String, Object>> updateMapping(@PathVariable Long id,
                                                           @RequestBody Map<String, String> body,
                                                           HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        final ImportRun run;
        try {
            run = runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
        try {
            MappingRule rule = mappingService.updateRule(id, tenant, "serviceadmin:" + admin,
                body.get("targetTable"), body.get("targetColumn"),
                body.get("sourceTable"), body.get("sourceColumn"),
                body.get("transform"), body.get("decision"));

            // First confirmed mapping moves the run forward.
            if ("CONFIRMED".equalsIgnoreCase(body.get("decision"))
                    && ImportRunStatus.fromString(run.getStatus()) == ImportRunStatus.PROFILED) {
                runService.transition(id, tenant, "serviceadmin:" + admin, ImportRunStatus.MAPPED);
            }
            return ResponseEntity.ok(ok("mapping", mappingView(rule)));
        } catch (MappingException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    private Map<String, Object> mappingView(MappingRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("targetTable", r.getTargetTable());
        m.put("targetColumn", r.getTargetColumn());
        m.put("sourceTable", r.getSourceTable());
        m.put("sourceColumn", r.getSourceColumn());
        m.put("transform", r.getTransform());
        m.put("confidence", r.getConfidence());
        m.put("status", r.getStatus());
        m.put("rationale", r.getRationale());
        m.put("decidedBy", r.getDecidedBy());
        return m;
    }

    // ───────────────────────── Phase 4: transform + validate ─────────────────────────

    /**
     * Transform the active mappings into the {@code staging_*} table and validate
     * every row. Body: {clientId, targetTable}. Lifecycle: MAPPED/VALIDATED →
     * STAGED → VALIDATED. Returns stage + validation summaries. No live writes.
     */
    @PostMapping("/runs/{id}/stage")
    public ResponseEntity<Map<String, Object>> stage(@PathVariable Long id,
                                                    @RequestBody Map<String, String> body,
                                                    HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        final String actor = "serviceadmin:" + admin;
        final String targetTable = body.get("targetTable");

        final ImportRun run;
        try {
            run = runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
        ImportRunStatus current = ImportRunStatus.fromString(run.getStatus());
        if (current != ImportRunStatus.MAPPED && current != ImportRunStatus.STAGED
                && current != ImportRunStatus.VALIDATED)
            return ResponseEntity.status(409).body(err("Run must be MAPPED before staging (is " + run.getStatus() + ")"));

        final ImportRunStatus cur = current;
        try {
            return guard.guard(id, () -> {
                // Move into STAGED (from MAPPED, or re-stage from VALIDATED).
                if (cur == ImportRunStatus.MAPPED || cur == ImportRunStatus.VALIDATED)
                    runService.transition(id, tenant, actor, ImportRunStatus.STAGED);

                StageResult stage = stageService.stage(id, tenant, actor, targetTable);
                ValidationSummary v = validationService.validate(id, tenant, actor, targetTable);

                runService.transition(id, tenant, actor, ImportRunStatus.VALIDATED);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "ok");
                out.put("sourceTable", stage.sourceTable());
                out.put("staged", stage.total());
                out.put("transformErrors", stage.withErrors());
                out.put("validation", Map.of(
                    "total", v.total(), "valid", v.valid(), "warn", v.warn(), "error", v.error()));
                return ResponseEntity.ok(out);
            });
        } catch (BusyException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (StageException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /**
     * Paged preview of staged rows. Query: ?clientId=&targetTable=
     * &filter=all|valid|warn|error &limit=&offset=.
     */
    @GetMapping("/runs/{id}/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable Long id,
                                                      @RequestParam String clientId,
                                                      @RequestParam String targetTable,
                                                      @RequestParam(defaultValue = "all") String filter,
                                                      @RequestParam(defaultValue = "50") int limit,
                                                      @RequestParam(defaultValue = "0") int offset,
                                                      HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }

        int cap = Math.max(1, Math.min(limit, 200));
        int skip = Math.max(0, offset);
        String status = "all".equalsIgnoreCase(filter) ? null : filter.toUpperCase();

        List<Map<String, Object>> rows;
        long total;
        switch (targetTable) {
            case "family" -> {
                List<StagingFamily> all = status == null
                    ? familyRepo.findByRunId(id) : familyRepo.findByRunIdAndRowStatus(id, status);
                total = all.size();
                rows = all.stream().skip(skip).limit(cap).map(this::familyView).toList();
            }
            case "income" -> {
                List<StagingIncome> all = status == null
                    ? incomeRepo.findByRunId(id) : incomeRepo.findByRunIdAndRowStatus(id, status);
                total = all.size();
                rows = all.stream().skip(skip).limit(cap).map(this::incomeView).toList();
            }
            case "expense" -> {
                List<StagingExpense> all = status == null
                    ? expenseRepo.findByRunId(id) : expenseRepo.findByRunIdAndRowStatus(id, status);
                total = all.size();
                rows = all.stream().skip(skip).limit(cap).map(this::expenseView).toList();
            }
            default -> {
                return ResponseEntity.status(400).body(err("Unknown target table: " + targetTable));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("targetTable", targetTable);
        out.put("filter", filter);
        out.put("total", total);
        out.put("offset", skip);
        out.put("rows", rows);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> familyView(StagingFamily r) {
        Map<String, Object> m = baseRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getValidationMsgs());
        m.put("dedupeMatchId", r.getDedupeMatchId());
        m.put("dedupeAction", r.getDedupeAction());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("firstName", r.getFirstName());
        v.put("lastName", r.getLastName());
        v.put("email", r.getEmail());
        v.put("phone", r.getPhone());
        v.put("city", r.getCity());
        v.put("familyKey", r.getFamilyKey());
        m.put("values", v);
        return m;
    }

    private Map<String, Object> incomeView(StagingIncome r) {
        Map<String, Object> m = baseRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getValidationMsgs());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("amount", r.getAmount());
        v.put("incomeDate", String.valueOf(r.getIncomeDate()));
        v.put("sourceName", r.getSourceName());
        v.put("method", r.getMethod());
        v.put("familyLink", r.getFamilyLink());
        m.put("values", v);
        return m;
    }

    private Map<String, Object> expenseView(StagingExpense r) {
        Map<String, Object> m = baseRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getValidationMsgs());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("amount", r.getAmount());
        v.put("expenseDate", String.valueOf(r.getExpenseDate()));
        v.put("category", r.getCategory());
        v.put("payee", r.getPayee());
        v.put("familyLink", r.getFamilyLink());
        m.put("values", v);
        return m;
    }

    private Map<String, Object> baseRow(Long rid, String sourceRowId, String rowStatus, String msgs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rid);
        m.put("sourceRowId", sourceRowId);
        m.put("rowStatus", rowStatus);
        m.put("validationMsgs", msgs);
        return m;
    }

    // ───────────────────────── Phase 6: load + rollback ─────────────────────────

    /** Operator action on one staged row. Body: {clientId, targetTable, dedupeAction?, rowStatus?}. */
    @PatchMapping("/runs/{id}/rows/{rowId}")
    public ResponseEntity<Map<String, Object>> patchRow(@PathVariable Long id,
                                                      @PathVariable Long rowId,
                                                      @RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);   // tenant ownership of the run
            loadService.updateRow(tenant, body.get("targetTable"), rowId,
                body.get("dedupeAction"), body.get("rowStatus"));
            return ResponseEntity.ok(ok("updated", rowId));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (LoadException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /** Approve a batch over the run's loadable rows. Body: {clientId, targetTable}. */
    @PostMapping("/runs/{id}/batches")
    public ResponseEntity<Map<String, Object>> createBatch(@PathVariable Long id,
                                                         @RequestBody Map<String, String> body,
                                                         HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
            return guard.guard(id, () -> {
                ImportBatch b = loadService.createBatch(id, tenant, "serviceadmin:" + admin, body.get("targetTable"));
                return ResponseEntity.ok(ok("batch", batchView(b)));
            });
        } catch (BusyException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (LoadException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        }
    }

    /** List batches for a run. Query: ?clientId=. */
    @GetMapping("/runs/{id}/batches")
    public ResponseEntity<Map<String, Object>> listBatches(@PathVariable Long id,
                                                         @RequestParam String clientId,
                                                         HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
        return ResponseEntity.ok(ok("batches",
            loadService.listBatches(id).stream().map(this::batchView).toList()));
    }

    /** Load an APPROVED batch into the live tables. Body: {clientId}. */
    @PostMapping("/batches/{batchId}/load")
    public ResponseEntity<Map<String, Object>> loadBatch(@PathVariable Long batchId,
                                                       @RequestBody Map<String, String> body,
                                                       HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            Long runId = loadService.getBatch(batchId, tenant).getRunId();
            return guard.guard(runId, () -> {
                ImportBatch b = loadService.loadBatch(batchId, tenant, "serviceadmin:" + admin);
                return ResponseEntity.ok(ok("batch", batchView(b)));
            });
        } catch (BusyException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (LoadException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        }
    }

    /** Reverse a LOADED batch as a unit. Body: {clientId}. */
    @PostMapping("/batches/{batchId}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackBatch(@PathVariable Long batchId,
                                                           @RequestBody Map<String, String> body,
                                                           HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            Long runId = loadService.getBatch(batchId, tenant).getRunId();
            return guard.guard(runId, () -> {
                ImportBatch b = loadService.rollbackBatch(batchId, tenant, "serviceadmin:" + admin);
                return ResponseEntity.ok(ok("batch", batchView(b)));
            });
        } catch (BusyException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (LoadException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        }
    }

    /** Per-row outcome report for a batch. Query: ?clientId=. */
    @GetMapping("/batches/{batchId}/report")
    public ResponseEntity<Map<String, Object>> batchReport(@PathVariable Long batchId,
                                                         @RequestParam String clientId,
                                                         HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        try {
            return ResponseEntity.ok(ok("report", loadService.batchReport(batchId, clientId.trim())));
        } catch (LoadException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
    }

    private Map<String, Object> batchView(ImportBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("runId", b.getRunId());
        m.put("targetTable", b.getTargetTable());
        m.put("status", b.getStatus());
        m.put("approvedBy", b.getApprovedBy());
        m.put("inserted", b.getInsertedCount());
        m.put("updated", b.getUpdatedCount());
        m.put("skipped", b.getSkippedCount());
        m.put("failed", b.getFailedCount());
        return m;
    }

    /** End-to-end consistency verification of a run (staging ↔ batches ↔ audit). Query: ?clientId=. */
    @GetMapping("/runs/{id}/verify")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable Long id,
                                                     @RequestParam String clientId,
                                                     HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);   // tenant ownership
            return ResponseEntity.ok(verifier.verify(id, tenant));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
    }

    /** Audit trail for a run (tenant-scoped). Query: ?clientId=. */
    @GetMapping("/runs/{id}/audit")
    public ResponseEntity<Map<String, Object>> audit(@PathVariable Long id,
                                                    @RequestParam String clientId,
                                                    HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId))
            return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        try {
            List<Map<String, Object>> entries = runService.getAudit(id, clientId.trim())
                .stream().map(this::auditView).toList();
            return ResponseEntity.ok(ok("audit", entries));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
    }

    private Map<String, Object> auditView(ImportAudit a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("runId", a.getRunId());
        m.put("batchId", a.getBatchId());
        m.put("actor", a.getActor());
        m.put("action", a.getAction());
        m.put("detail", a.getDetail());
        m.put("createdDate", String.valueOf(a.getCreatedDate()));
        return m;
    }
}
