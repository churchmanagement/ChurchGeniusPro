package com.churchgeniuspro.controller;

import com.churchgeniuspro.common.ImportRunStatus;
import com.churchgeniuspro.hibernate.ImportAudit;
import com.churchgeniuspro.hibernate.ImportBatch;
import com.churchgeniuspro.hibernate.ImportFile;
import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.MappingRule;
import com.churchgeniuspro.hibernate.SourceColumnProfile;
import com.churchgeniuspro.hibernate.StagingExpense;
import com.churchgeniuspro.hibernate.StagingFamily;
import com.churchgeniuspro.hibernate.StagingIncome;
import com.churchgeniuspro.hibernate.StagingRaw;
import com.churchgeniuspro.repository.ImportFileRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SourceColumnProfileRepository;
import com.churchgeniuspro.repository.StagingExpenseRepository;
import com.churchgeniuspro.repository.StagingFamilyRepository;
import com.churchgeniuspro.repository.StagingIncomeRepository;
import com.churchgeniuspro.repository.StagingRawRepository;
import com.churchgeniuspro.service.EtlDictionaryService;
import com.churchgeniuspro.service.EtlDictionaryService.DictionaryException;
import com.churchgeniuspro.service.EtlValidationService;
import com.churchgeniuspro.service.EtlValidationService.ValidationSummary;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.SourcePreset;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // Migration Phase 1 (optional so existing wiring and tests keep working)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ImportFileRepository              fileRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtlDictionaryService              dictionaryService;
    // Migration Phase 4: staged rows of the record data types
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.StagingRecordRepository recordRepo;
    public void setRecordRepo(com.churchgeniuspro.repository.StagingRecordRepository r) { this.recordRepo = r; }

    private List<com.churchgeniuspro.hibernate.StagingRecord> records(Long runId, String table) {
        return recordRepo == null ? List.of() : recordRepo.findByRunIdAndTargetTableOrderByIdAsc(runId, table);
    }

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
        SourcePreset preset = SourcePreset.of(r.getSourcePreset());
        m.put("sourcePreset", preset.name());
        m.put("sourcePresetLabel", preset.label());
        m.put("sourcePresetVerified", preset.verified());
        m.put("dateOrder", r.getDateOrder() == null ? "AUTO" : r.getDateOrder());
        m.put("aiAssist", r.isAiAssist());
        return m;
    }

    private Map<String, Object> fileView(ImportFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("runId", f.getRunId());
        m.put("sourceTable", f.getSourceTable());
        m.put("fileName", f.getFileName());
        m.put("sizeBytes", f.getSizeBytes());
        m.put("sha256", f.getSha256());
        m.put("format", f.getFormat());
        m.put("rowCount", f.getRowCount());
        m.put("idColumn", f.getIdColumn());
        m.put("targetTable", f.getTargetTable());
        m.put("suggestedTable", f.getSuggestedTable());
        m.put("uploadedBy", f.getUploadedBy());
        m.put("forced", Boolean.TRUE.equals(f.getForced()));
        m.put("createdDate", String.valueOf(f.getCreatedDate()));
        return m;
    }

    // ───────────────────────── Migration Phase 1: presets ─────────────────────────

    /** Source application presets the Start step offers (verified flag = real export has passed end to end). */
    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> presets(HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SourcePreset p : SourcePreset.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.name());
            m.put("label", p.label());
            m.put("verified", p.verified());
            m.put("dateOrder", p.dateOrder());
            Map<String, Object> tables = new LinkedHashMap<>();
            for (String t : EtlTargetSchema.tables()) {
                Map<String, List<String>> a = p.aliasesFor(t);
                if (!a.isEmpty()) tables.put(t, a.keySet().stream().toList());
            }
            m.put("tables", tables);
            m.put("idColumns", Map.of("family", p.idColumns("family"), "income", p.idColumns("income")));
            m.put("exports", EXPORT_HINTS.getOrDefault(p.name(), List.of()));
            out.add(m);
        }
        Map<String, Object> res = ok("presets", out);
        res.put("targetTables", EtlTargetSchema.tables());
        res.put("tableLabels", EtlTargetSchema.LABELS);
        res.put("recordTables", EtlTargetSchema.RECORD_TABLES);
        res.put("dateOrders", List.of("AUTO", "MDY", "DMY", "YMD"));
        return ResponseEntity.ok(res);
    }

    /**
     * Phase 5: what to export from each product, shown on the wizard's Start cards. These are
     * suggestions only — no real export from any of these products has been run end to end
     * yet, which is why every preset still carries the "Preset (unverified)" badge.
     */
    static final Map<String, List<String>> EXPORT_HINTS = Map.of(
        "GENERIC", List.of("Any CSV or Excel file — one data type per file (people, giving, pledges, events, groups, kids …)"),
        "BREEZE", List.of("People (keep the Breeze ID and Family ID columns)", "Contributions", "Pledges", "Tags, imported as groups", "Events and attendance"),
        "CHMEETINGS", List.of("People / members (keep Person Id and Family ID)", "Contributions", "Pledges", "Groups and members", "Events, registrations and attendance"),
        "GRACELY", List.of("Members", "Donations"),
        "PLANNING_CENTER", List.of("People (People app)", "Donations (Giving app)", "Groups and memberships (Groups app)", "Check-ins (Check-Ins app)"),
        "CCB", List.of("Individuals (keep Individual ID and Family ID)", "Contributions / transactions", "Groups and participants", "Events and attendance"));

    // ───────────────────────── endpoints ─────────────────────────

    /** Create a run for an explicit tenant. Body: {clientId, sourceLabel?, sourcePreset?, dateOrder?, aiAssist?}. */
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
            ImportRun run = runService.createRun(clientId.trim(), createdBy, body.get("sourceLabel"),
                body.get("sourcePreset"), body.get("dateOrder"), "true".equalsIgnoreCase(body.get("aiAssist")));
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

        // Phase 5: each run carries its files and what it loaded (the wizard's Start step history).
        Map<String, String> names = new java.util.HashMap<>();
        List<Map<String, Object>> runs = runService.listRuns(clientId.trim())
            .stream().map(r -> historyView(r, names)).toList();
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
                                                       @RequestParam(required = false) String targetTable,
                                                       @RequestParam(required = false, defaultValue = "false") boolean force,
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
        // Phase 3: a run holds several tables, so a file may be added after another table was
        // approved or loaded; only a finished (rolled back / discarded / failed) run is closed.
        if (current == null || current.isTerminal())
            return ResponseEntity.status(409).body(err("Run is not in an extractable state: " + run.getStatus()));

        String name = file.getOriginalFilename() == null ? "source" : file.getOriginalFilename();
        String table = (sourceTable == null || sourceTable.isBlank())
            ? name.replaceAll("\\.[^.]*$", "") : sourceTable;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            return ResponseEntity.status(400).body(err("Could not read upload: " + e.getMessage()));
        }
        String fmt = SourceExtractService.detectFormat(format, name, bytes);
        String targetHint = (targetTable != null && EtlTargetSchema.isTable(targetTable)) ? targetTable : null;

        // Migration Phase 1: the same file for the same church is refused unless the
        // admin deliberately re-imports it (force=true). Compared across every
        // non-discarded run of the tenant, by content hash.
        String sha = SourceExtractService.sha256(bytes);
        if (fileRepo != null) {
            for (ImportFile prior : fileRepo.findByClientIdAndSha256(tenant, sha)) {
                if (prior.getRunId().equals(id) && table.equals(prior.getSourceTable())) continue;   // re-upload into the same slot
                ImportRun priorRun;
                try { priorRun = runService.getRun(prior.getRunId(), tenant); } catch (ImportRunException e) { continue; }
                if (ImportRunStatus.DISCARDED.name().equals(priorRun.getStatus())) continue;
                if (!force) {
                    Map<String, Object> dup = err("This file was already uploaded for this church (run #" + prior.getRunId()
                        + ", " + String.valueOf(prior.getFileName()) + ", " + String.valueOf(prior.getCreatedDate())
                        + "). Re-upload with force=true only if you mean to import it again.");
                    dup.put("duplicateOf", prior.getRunId());
                    dup.put("duplicateFile", fileView(prior));
                    return ResponseEntity.status(409).body(dup);
                }
                break;
            }
        }

        try {
            ExtractResult ex = extractService.extract(run, table, fmt, bytes, targetHint);

            List<SourceColumnProfile> profile = profileService.profile(id, tenant, ex.sourceTable());
            // Phase 5: what the headers look like (the wizard labels each dropped file with it).
            com.churchgeniuspro.util.EtlTypeDetector.Result detected =
                com.churchgeniuspro.util.EtlTypeDetector.detect(ex.columns() == null ? List.of() : ex.columns(), SourcePreset.of(run.getSourcePreset()));

            ImportFile rec = null;
            if (fileRepo != null) {
                for (ImportFile old : fileRepo.findByRunIdAndSourceTable(id, ex.sourceTable())) fileRepo.delete(old);
                rec = new ImportFile();
                rec.setRunId(id); rec.setClientId(tenant); rec.setSourceTable(ex.sourceTable());
                rec.setFileName(name.length() > 255 ? name.substring(0, 255) : name);
                rec.setSizeBytes((long) bytes.length); rec.setSha256(sha); rec.setFormat(fmt);
                rec.setRowCount(ex.rowCount()); rec.setIdColumn(ex.idColumn());
                rec.setUploadedBy("serviceadmin:" + admin); rec.setForced(force);
                rec.setTargetTable(targetHint); rec.setSuggestedTable(detected.table());
                rec = fileRepo.save(rec);
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sourceTable", ex.sourceTable());
            detail.put("rowCount", ex.rowCount());
            detail.put("columns", profile.size());
            detail.put("format", fmt);
            detail.put("fileName", name);
            detail.put("sizeBytes", bytes.length);
            detail.put("sha256", sha);
            detail.put("idColumn", String.valueOf(ex.idColumn()));
            detail.put("forced", force);
            runService.recordAudit(id, tenant, "serviceadmin:" + admin, "EXTRACT", detail);

            // Advance lifecycle only from DRAFT; re-extracts leave PROFILED as-is.
            if (current == ImportRunStatus.DRAFT) {
                runService.transition(id, tenant, "serviceadmin:" + admin, ImportRunStatus.PROFILED);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("sourceTable", ex.sourceTable());
            out.put("rowCount", ex.rowCount());
            out.put("format", fmt);
            out.put("idColumn", ex.idColumn());
            out.put("suggestedTarget", detected.table());
            out.put("suggestionConfidence", detected.confidence());
            out.put("detection", detected.ranking().stream().map(sc -> Map.of("table", sc.table(), "score", sc.score(),
                "label", EtlTargetSchema.LABELS.getOrDefault(sc.table(), sc.table()))).toList());
            out.put("file", rec == null ? null : fileView(rec));
            out.put("profile", profile.stream().map(this::profileView).toList());
            return ResponseEntity.ok(out);
        } catch (ExtractException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /** Files uploaded to a run (name, size, hash, rows, id column). Query: ?clientId=. */
    @GetMapping("/runs/{id}/files")
    public ResponseEntity<Map<String, Object>> files(@PathVariable Long id, @RequestParam String clientId,
                                                     HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try { runService.getRun(id, tenant); } catch (ImportRunException e) { return ResponseEntity.status(404).body(err(e.getMessage())); }
        List<Map<String, Object>> out = fileRepo == null ? List.of()
            : fileRepo.findByRunIdOrderByCreatedDateAsc(id).stream().map(this::fileView).toList();
        return ResponseEntity.ok(ok("files", out));
    }

    /**
     * Mapping review for one target table: unmapped required columns, source columns
     * no target uses, dictionary/virtual columns, preset. Query: ?clientId=&targetTable=&sourceTable=.
     */
    @GetMapping("/runs/{id}/mappings/review")
    public ResponseEntity<Map<String, Object>> mappingReview(@PathVariable Long id, @RequestParam String clientId,
                                                             @RequestParam String targetTable,
                                                             @RequestParam(required = false) String sourceTable,
                                                             HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            Map<String, Object> review = mappingService.review(id, tenant, targetTable, sourceTable);
            return ResponseEntity.ok(ok("review", review));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (MappingException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /** Value dictionaries for a target table (distinct source values, current translation, options). */
    @GetMapping("/runs/{id}/dictionaries")
    public ResponseEntity<Map<String, Object>> dictionaries(@PathVariable Long id, @RequestParam String clientId,
                                                            @RequestParam String targetTable,
                                                            HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        if (dictionaryService == null) return ResponseEntity.ok(ok("dictionaries", List.of()));
        final String tenant = clientId.trim();
        try {
            return ResponseEntity.ok(ok("dictionaries", dictionaryService.list(id, tenant, targetTable)));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (DictionaryException e) {
            return ResponseEntity.status(400).body(err(e.getMessage()));
        }
    }

    /** Save dictionary entries. Body: {clientId, dictionary, entries:[{source, target}]}. Re-stage to apply. */
    @PutMapping("/runs/{id}/dictionaries")
    public ResponseEntity<Map<String, Object>> saveDictionary(@PathVariable Long id,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : String.valueOf(body.get("clientId"));
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        if (dictionaryService == null) return ResponseEntity.status(400).body(err("Dictionaries unavailable"));
        final String tenant = clientId.trim();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> entries = (List<Map<String, String>>) body.get("entries");
            int n = dictionaryService.save(id, tenant, "serviceadmin:" + admin, String.valueOf(body.get("dictionary")), entries);
            return ResponseEntity.ok(ok("saved", n));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (DictionaryException | ClassCastException e) {
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
                Map<String, Object> res = ok("mappings", rules.stream().map(this::mappingView).toList());
                res.put("review", mappingService.review(id, tenant, body.get("targetTable"), body.get("sourceTable")));
                // Migration Phase 1: AUTO rules are active mappings, so a run whose preset/matcher
                // placed columns at high confidence is MAPPED without a manual confirm (an admin
                // may still confirm/reject individual rows).
                ImportRun run = runService.getRun(id, tenant);
                if (ImportRunStatus.fromString(run.getStatus()) == ImportRunStatus.PROFILED
                        && rules.stream().anyMatch(r -> "AUTO".equals(r.getStatus()))) {
                    runService.transition(id, tenant, "serviceadmin:" + admin, ImportRunStatus.MAPPED);
                }
                return ResponseEntity.ok(res);
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
        EtlTargetSchema.TargetColumn col = EtlTargetSchema.column(r.getTargetTable(), r.getTargetColumn());
        m.put("required", col != null && col.required());
        m.put("dictionary", col == null ? null : col.dictionary());
        m.put("virtual", col != null && col.virtual());
        m.put("targetType", col == null ? null : col.type());
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
        // Phase 3: another table of the same run may already be APPROVED or LOADED; staging a
        // further table then leaves the run's status where it is (the per-table state lives
        // in the run plan). The stage service refuses to touch a table whose own batch is live.
        boolean later = current == ImportRunStatus.APPROVED || current == ImportRunStatus.LOADED;
        if (current != ImportRunStatus.MAPPED && current != ImportRunStatus.STAGED
                && current != ImportRunStatus.VALIDATED && !later)
            return ResponseEntity.status(409).body(err("Run must be MAPPED before staging (is " + run.getStatus() + ")"));

        final ImportRunStatus cur = current;
        try {
            return guard.guard(id, () -> {
                // Move into STAGED (from MAPPED, or re-stage from VALIDATED).
                if (cur == ImportRunStatus.MAPPED || cur == ImportRunStatus.VALIDATED)
                    runService.transition(id, tenant, actor, ImportRunStatus.STAGED);

                StageResult stage = stageService.stage(id, tenant, actor, targetTable);
                ValidationSummary v = validationService.validate(id, tenant, actor, targetTable);

                if (!later) runService.transition(id, tenant, actor, ImportRunStatus.VALIDATED);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "ok");
                out.put("sourceTable", stage.sourceTable());
                out.put("staged", stage.total());
                out.put("transformErrors", stage.withErrors());
                out.put("validation", summaryView(v));
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
        // Phase 2 filters: duplicates (any match tier), new (no match), review (undecided), skipped (action SKIP)
        String f = filter == null ? "all" : filter.toLowerCase();
        boolean special = Set.of("duplicates", "new", "skipped").contains(f);
        String status = ("all".equals(f) || special) ? null : filter.toUpperCase();

        List<Map<String, Object>> rows;
        long total;
        switch (targetTable) {
            case "family" -> {
                List<StagingFamily> all = status == null
                    ? familyRepo.findByRunId(id) : familyRepo.findByRunIdAndRowStatus(id, status);
                all = all.stream().filter(r -> keep(f, r.getMatchTier(), r.getDedupeAction())).toList();
                total = all.size();
                rows = all.stream().skip(skip).limit(cap).map(this::familyView).toList();
            }
            case "income" -> {
                List<StagingIncome> all = status == null
                    ? incomeRepo.findByRunId(id) : incomeRepo.findByRunIdAndRowStatus(id, status);
                all = all.stream().filter(r -> keep(f, r.getMatchTier(), r.getDedupeAction())).toList();
                total = all.size();
                rows = all.stream().skip(skip).limit(cap).map(this::incomeView).toList();
            }
            case "expense" -> {
                List<StagingExpense> all = status == null
                    ? expenseRepo.findByRunId(id) : expenseRepo.findByRunIdAndRowStatus(id, status);
                all = all.stream().filter(r -> keep(f, r.getMatchTier(), r.getDedupeAction())).toList();
                total = all.size();
                rows = all.stream().skip(skip).limit(cap).map(this::expenseView).toList();
            }
            default -> {
                if (!EtlTargetSchema.isRecordTable(targetTable))
                    return ResponseEntity.status(400).body(err("Unknown target table: " + targetTable));
                List<com.churchgeniuspro.hibernate.StagingRecord> all = records(id, targetTable).stream()
                    .filter(r -> status == null || status.equals(r.getRowStatus()))
                    .filter(r -> keep(f, r.getMatchTier(), r.getDedupeAction())).toList();
                total = all.size();
                rows = all.stream().skip(skip).limit(cap).map(this::recordView).toList();
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

    private static boolean keep(String filter, String tier, String action) {
        boolean dup = tier != null && !"NONE".equals(tier);
        return switch (filter) {
            case "duplicates" -> dup;
            case "new" -> !dup;
            case "skipped" -> "SKIP".equals(action);
            default -> true;
        };
    }

    private Map<String, Object> summaryView(ValidationSummary v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", v.total()); m.put("valid", v.valid()); m.put("warn", v.warn()); m.put("error", v.error());
        m.put("review", v.review()); m.put("new", v.newRows()); m.put("exact", v.exact()); m.put("strong", v.strong());
        m.put("possible", v.possible()); m.put("inFile", v.inFile());
        return m;
    }

    /** Phase 2: live counts for the Review step (recomputed from the rows, so decisions are reflected). */
    @GetMapping("/runs/{id}/summary")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable Long id, @RequestParam String clientId,
                                                       @RequestParam String targetTable, HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try { runService.getRun(id, tenant); } catch (ImportRunException e) { return ResponseEntity.status(404).body(err(e.getMessage())); }
        List<String[]> rows = switch (targetTable) {
            case "family" -> familyRepo.findByRunId(id).stream().map(r -> new String[]{r.getRowStatus(), r.getMatchTier(), r.getDedupeAction()}).toList();
            case "income" -> incomeRepo.findByRunId(id).stream().map(r -> new String[]{r.getRowStatus(), r.getMatchTier(), r.getDedupeAction()}).toList();
            case "expense" -> expenseRepo.findByRunId(id).stream().map(r -> new String[]{r.getRowStatus(), r.getMatchTier(), r.getDedupeAction()}).toList();
            default -> EtlTargetSchema.isRecordTable(targetTable)
                ? records(id, targetTable).stream().map(r -> new String[]{r.getRowStatus(), r.getMatchTier(), r.getDedupeAction()}).toList()
                : null;
        };
        if (rows == null) return ResponseEntity.status(400).body(err("Unknown target table: " + targetTable));
        int total = rows.size(), valid = 0, warn = 0, error = 0, review = 0, nw = 0, exact = 0, strong = 0, possible = 0, inFile = 0,
            skip = 0, update = 0, merge = 0, insert = 0, loaded = 0;
        for (String[] r : rows) {
            switch (String.valueOf(r[0])) {
                case "ERROR" -> error++; case "REVIEW" -> review++; case "WARN" -> warn++; case "VALID" -> valid++;
                case "LOADED", "SKIPPED", "APPROVED", "FAILED", "ROLLED_BACK" -> loaded++; default -> { }
            }
            switch (String.valueOf(r[1])) {
                case "EXACT" -> exact++; case "STRONG" -> strong++; case "POSSIBLE" -> possible++; case "IN_FILE" -> inFile++; default -> nw++;
            }
            switch (String.valueOf(r[2])) {
                case "SKIP" -> skip++; case "UPDATE" -> update++; case "MERGE" -> merge++; case "INSERT" -> insert++; default -> { }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targetTable", targetTable); m.put("total", total); m.put("valid", valid); m.put("warn", warn); m.put("error", error);
        m.put("review", review); m.put("new", nw); m.put("exact", exact); m.put("strong", strong); m.put("possible", possible); m.put("inFile", inFile);
        m.put("willInsert", insert); m.put("willUpdate", update); m.put("willMerge", merge); m.put("willSkip", skip); m.put("processed", loaded);
        m.put("canApprove", review == 0 && (valid + warn) > 0);
        return ResponseEntity.ok(ok("summary", m));
    }

    /** Phase 2: validation report as CSV (source row, status, tier, decision, matched id, messages, key values). */
    @GetMapping("/runs/{id}/report-csv")
    public ResponseEntity<byte[]> reportCsv(@PathVariable Long id, @RequestParam String clientId,
                                            @RequestParam String targetTable, HttpServletRequest request) {
        if (adminId(request) == null) return ResponseEntity.status(401).build();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).build();
        final String tenant = clientId.trim();
        try { runService.getRun(id, tenant); } catch (ImportRunException e) { return ResponseEntity.status(404).build(); }
        List<Map<String, Object>> rows = switch (targetTable) {
            case "family" -> familyRepo.findByRunId(id).stream().map(this::familyView).toList();
            case "income" -> incomeRepo.findByRunId(id).stream().map(this::incomeView).toList();
            case "expense" -> expenseRepo.findByRunId(id).stream().map(this::expenseView).toList();
            default -> EtlTargetSchema.isRecordTable(targetTable) ? records(id, targetTable).stream().map(this::recordView).toList() : null;
        };
        if (rows == null) return ResponseEntity.status(400).build();
        StringBuilder sb = new StringBuilder("\uFEFF");
        List<String> valueKeys = rows.isEmpty() ? List.of() : new java.util.ArrayList<>(((Map<?, ?>) rows.get(0).get("values")).keySet().stream().map(String::valueOf).toList());
        sb.append("source_row,status,match_tier,match_detail,decision,matched_id,messages");
        for (String k : valueKeys) sb.append(',').append(csv(k));
        sb.append("\r\n");
        for (Map<String, Object> r : rows) {
            String msgs = "";
            try {
                com.fasterxml.jackson.databind.JsonNode arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(r.get("validationMsgs")));
                if (arr != null && arr.isArray()) { StringBuilder m = new StringBuilder(); for (var n : arr) { if (m.length() > 0) m.append(" | "); m.append(n.path("level").asText()).append(": ").append(n.path("message").asText()); } msgs = m.toString(); }
            } catch (Exception ignore) { }
            sb.append(csv(r.get("sourceRowId"))).append(',').append(csv(r.get("rowStatus"))).append(',').append(csv(r.get("matchTier")))
              .append(',').append(csv(r.get("matchDetail"))).append(',').append(csv(r.get("dedupeAction"))).append(',').append(csv(r.get("dedupeMatchId")))
              .append(',').append(csv(msgs));
            Map<?, ?> vals = (Map<?, ?>) r.get("values");
            for (String k : valueKeys) sb.append(',').append(csv(vals.get(k)));
            sb.append("\r\n");
        }
        String name = "etl-run-" + id + "-" + targetTable + "-validation.csv";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
            .header("Cache-Control", "no-store")
            .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String csv(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        if (s.matches("^[=+\\-@\\t\\r].*")) s = "'" + s;          // spreadsheet formula injection guard
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private Map<String, Object> familyView(StagingFamily r) {
        Map<String, Object> m = baseRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getValidationMsgs());
        m.put("dedupeMatchId", r.getDedupeMatchId());
        m.put("dedupeAction", r.getDedupeAction());
        m.put("matchTier", r.getMatchTier());
        m.put("matchDetail", r.getMatchDetail());
        m.put("householdProposal", r.getHouseholdProposal());
        m.put("householdDecision", r.getHouseholdDecision());
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
        m.put("dedupeMatchId", r.getDedupeMatchId());
        m.put("dedupeAction", r.getDedupeAction());
        m.put("matchTier", r.getMatchTier());
        m.put("matchDetail", r.getMatchDetail());
        m.put("importRef", r.getImportRef());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("amount", r.getAmount());
        v.put("incomeDate", String.valueOf(r.getIncomeDate()));
        v.put("sourceName", r.getSourceName());
        v.put("method", r.getMethod());
        v.put("familyLink", r.getFamilyLink());
        v.put("guestName", r.getGuestName());
        v.put("referenceNo", r.getReferenceNo());
        m.put("values", v);
        return m;
    }

    private Map<String, Object> expenseView(StagingExpense r) {
        Map<String, Object> m = baseRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getValidationMsgs());
        m.put("dedupeMatchId", r.getDedupeMatchId());
        m.put("dedupeAction", r.getDedupeAction());
        m.put("matchTier", r.getMatchTier());
        m.put("matchDetail", r.getMatchDetail());
        m.put("importRef", r.getImportRef());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("amount", r.getAmount());
        v.put("expenseDate", String.valueOf(r.getExpenseDate()));
        v.put("category", r.getCategory());
        v.put("payee", r.getPayee());
        v.put("familyLink", r.getFamilyLink());
        m.put("values", v);
        return m;
    }

    /**
     * Phase 4: a record row — values in schema order (every column present, so the CSV has
     * stable columns), plus what its references resolved to ("linkedTo").
     */
    private Map<String, Object> recordView(com.churchgeniuspro.hibernate.StagingRecord r) {
        Map<String, Object> m = baseRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getValidationMsgs());
        m.put("dedupeMatchId", r.getDedupeMatchId());
        m.put("dedupeAction", r.getDedupeAction());
        m.put("matchTier", r.getMatchTier());
        m.put("matchDetail", r.getMatchDetail());
        m.put("targetId", r.getTargetId());
        Map<String, Object> stored = readJson(r.getValuesJson());
        Map<String, Object> v = new LinkedHashMap<>();
        for (EtlTargetSchema.TargetColumn c : EtlTargetSchema.columns(r.getTargetTable()))
            if (!c.virtual()) v.put(c.name(), stored.get(c.name()));
        m.put("values", v);
        Map<String, Object> links = readJson(r.getLinksJson());
        Map<String, Object> linked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : links.entrySet()) {
            String tok = String.valueOf(e.getValue());
            if (!Set.of("parent", "person", "classroom", "fund").contains(e.getKey())) continue;
            linked.put(e.getKey(), tok.startsWith("new:") ? "new: " + tok.substring(4)
                : tok.startsWith("l") || tok.startsWith("m") ? "existing #" + tok.substring(1)
                : tok.startsWith("s") ? "row staged in this run" : tok);
        }
        m.put("linkedTo", linked);
        return m;
    }

    private static Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
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
        m.put("errorMessage", b.getErrorMessage());
        return m;
    }

    // ───────────────────────── Migration Phase 5: settings, date preview, summary, exports, overview ─────────────────────────

    /** Change the run's date order and/or label (before anything is approved or loaded). Body: {clientId, dateOrder?, sourceLabel?}. */
    @PutMapping("/runs/{id}/settings")
    public ResponseEntity<Map<String, Object>> settings(@PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        try {
            ImportRun run = runService.updateSettings(id, clientId.trim(), "serviceadmin:" + admin, body.get("dateOrder"), body.get("sourceLabel"));
            return ResponseEntity.ok(ok("run", runView(run)));
        } catch (ImportRunException e) {
            return ResponseEntity.status(e.getMessage() != null && e.getMessage().contains("not found") ? 404 : 409).body(err(e.getMessage()));
        }
    }

    /**
     * How each mapped date column reads under every date order — the wizard's date-format
     * picker. For every active mapping onto a DATE / DATETIME column, the profiled sample
     * values are parsed as AUTO, MDY, DMY and YMD. Query: ?clientId=&targetTable= (optional).
     */
    @GetMapping("/runs/{id}/date-preview")
    public ResponseEntity<Map<String, Object>> datePreview(@PathVariable Long id, @RequestParam String clientId,
                                                           @RequestParam(required = false) String targetTable, HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        ImportRun run;
        try { run = runService.getRun(id, tenant); } catch (ImportRunException e) { return ResponseEntity.status(404).body(err(e.getMessage())); }
        List<Map<String, Object>> cols = new java.util.ArrayList<>();
        boolean ambiguous = false;
        for (MappingRule r : mappingService.list(id, targetTable == null || targetTable.isBlank() ? null : targetTable)) {
            if (r.getSourceColumn() == null || !("AUTO".equals(r.getStatus()) || "CONFIRMED".equals(r.getStatus()))) continue;
            EtlTargetSchema.TargetColumn col = EtlTargetSchema.column(r.getTargetTable(), r.getTargetColumn());
            if (col == null || !("DATE".equals(col.type()) || "DATETIME".equals(col.type()))) continue;
            List<String> samples = new java.util.ArrayList<>();
            for (SourceColumnProfile pr : profileRepo.findByRunIdAndSourceTableOrderByOrdinalAsc(id, r.getSourceTable()))
                if (pr.getColumnName().equals(r.getSourceColumn())) samples.addAll(readList(pr.getSampleValues()));
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (String sample : samples.stream().limit(6).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("raw", sample);
                java.util.Set<String> distinct = new java.util.HashSet<>();
                for (String order : List.of("AUTO", "MDY", "DMY", "YMD")) {
                    String v;
                    try {
                        Object t = com.churchgeniuspro.util.Transforms.apply("DATETIME".equals(col.type()) ? "parse_datetime" : "parse_date", sample,
                            com.churchgeniuspro.util.Transforms.Context.of(order));
                        v = String.valueOf(t);
                    } catch (Exception e) { v = "✕ not a date"; }
                    row.put(order, v);
                    if (!"AUTO".equals(order) && !v.startsWith("✕")) distinct.add(v);
                }
                if (distinct.size() > 1) ambiguous = true;
                rows.add(row);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetTable", r.getTargetTable()); m.put("targetColumn", r.getTargetColumn());
            m.put("sourceTable", r.getSourceTable()); m.put("sourceColumn", r.getSourceColumn()); m.put("samples", rows);
            cols.add(m);
        }
        Map<String, Object> res = ok("columns", cols);
        res.put("dateOrder", run.getDateOrder() == null ? "AUTO" : run.getDateOrder());
        res.put("ambiguous", ambiguous);
        return ResponseEntity.ok(res);
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}); } catch (Exception e) { return List.of(); }
    }

    /**
     * The migration summary: the run, its files, and per data type what was staged, loaded,
     * skipped, failed and left behind (rows with errors or still undecided). The wizard's
     * Summary step and the CSV below are built from it.
     */
    Map<String, Object> migrationSummary(ImportRun run) {
        List<Map<String, Object>> plan = loadService.runPlan(run.getId(), run.getClientId());
        List<Map<String, Object>> tables = new java.util.ArrayList<>();
        long ins = 0, upd = 0, skip = 0, fail = 0, left = 0;
        for (Map<String, Object> t : plan) {
            if (((Number) t.get("staged")).intValue() == 0 && ((List<?>) t.get("batches")).isEmpty()) continue;
            @SuppressWarnings("unchecked") Map<String, Long> counts = (Map<String, Long>) t.get("counts");
            long i = 0, u = 0, sk = 0, f = 0;
            String loadedBatch = null;
            for (Object o : (List<?>) t.get("batches")) {
                Map<?, ?> b = (Map<?, ?>) o;
                String st = String.valueOf(b.get("status"));
                if ("LOADED".equals(st) || "FAILED".equals(st)) {
                    i += num(b.get("inserted")); u += num(b.get("updated")); sk += num(b.get("skipped")); f += num(b.get("failed"));
                    loadedBatch = String.valueOf(b.get("id"));
                }
            }
            long notLoaded = counts.getOrDefault("ERROR", 0L) + counts.getOrDefault("REVIEW", 0L) + counts.getOrDefault("VALID", 0L)
                + counts.getOrDefault("WARN", 0L) + counts.getOrDefault("PENDING", 0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetTable", t.get("targetTable")); m.put("label", EtlTargetSchema.LABELS.getOrDefault((String) t.get("targetTable"), (String) t.get("targetTable")));
            m.put("state", t.get("state")); m.put("staged", t.get("staged"));
            m.put("inserted", i); m.put("updated", u); m.put("skipped", sk); m.put("failed", f);
            m.put("errors", counts.getOrDefault("ERROR", 0L)); m.put("rolledBack", counts.getOrDefault("ROLLED_BACK", 0L));
            m.put("notLoaded", notLoaded); m.put("batch", loadedBatch);
            tables.add(m);
            ins += i; upd += u; skip += sk; fail += f; left += notLoaded;
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("inserted", ins); totals.put("updated", upd); totals.put("skipped", skip); totals.put("failed", fail); totals.put("notLoaded", left);
        String loadedAt = null, loadedBy = null, rolledBackAt = null;
        for (ImportAudit a : runService.getAudit(run.getId(), run.getClientId())) {
            if ("LOAD".equals(a.getAction())) { loadedAt = String.valueOf(a.getCreatedDate()); loadedBy = a.getActor(); }
            if ("ROLLBACK".equals(a.getAction()) || "ROLLBACK_RUN".equals(a.getAction())) rolledBackAt = String.valueOf(a.getCreatedDate());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", runView(run));
        out.put("church", churchName(run.getClientId()));
        out.put("files", fileRepo == null ? List.of() : fileRepo.findByRunIdOrderByCreatedDateAsc(run.getId()).stream().map(this::fileView).toList());
        out.put("tables", tables); out.put("totals", totals);
        out.put("lastLoadedAt", loadedAt); out.put("lastLoadedBy", loadedBy); out.put("lastRollbackAt", rolledBackAt);
        return out;
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }

    private String churchName(String clientId) {
        return serviceClientRepository.findByClientId(clientId)
            .map(c -> c.getChurchName() != null && !c.getChurchName().isBlank() ? c.getChurchName() : c.getName())
            .orElse(clientId);
    }

    /** Migration summary as JSON. Query: ?clientId=. */
    @GetMapping("/runs/{id}/migration-summary")
    public ResponseEntity<Map<String, Object>> migrationSummaryJson(@PathVariable Long id, @RequestParam String clientId, HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        try {
            ImportRun run = runService.getRun(id, clientId.trim());
            return ResponseEntity.ok(ok("summary", migrationSummary(run)));
        } catch (ImportRunException e) { return ResponseEntity.status(404).body(err(e.getMessage())); }
    }

    /** Migration summary as CSV (one line per data type, then totals). Query: ?clientId=. */
    @GetMapping("/runs/{id}/migration-summary-csv")
    public ResponseEntity<byte[]> migrationSummaryCsv(@PathVariable Long id, @RequestParam String clientId, HttpServletRequest request) {
        if (adminId(request) == null) return ResponseEntity.status(401).build();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).build();
        ImportRun run;
        try { run = runService.getRun(id, clientId.trim()); } catch (ImportRunException e) { return ResponseEntity.status(404).build(); }
        Map<String, Object> sum = migrationSummary(run);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("run,church,source,status,last_loaded_at,last_rollback_at\r\n");
        Map<?, ?> rv = (Map<?, ?>) sum.get("run");
        sb.append(csv(run.getId())).append(',').append(csv(sum.get("church"))).append(',').append(csv(rv.get("sourcePresetLabel")))
          .append(',').append(csv(run.getStatus())).append(',').append(csv(sum.get("lastLoadedAt"))).append(',').append(csv(sum.get("lastRollbackAt"))).append("\r\n\r\n");
        sb.append("data_type,state,staged,inserted,updated,skipped,failed,errors,not_loaded,rolled_back,batch\r\n");
        for (Object o : (List<?>) sum.get("tables")) {
            Map<?, ?> t = (Map<?, ?>) o;
            sb.append(csv(t.get("label"))).append(',').append(csv(t.get("state"))).append(',').append(csv(t.get("staged"))).append(',')
              .append(csv(t.get("inserted"))).append(',').append(csv(t.get("updated"))).append(',').append(csv(t.get("skipped"))).append(',')
              .append(csv(t.get("failed"))).append(',').append(csv(t.get("errors"))).append(',').append(csv(t.get("notLoaded"))).append(',')
              .append(csv(t.get("rolledBack"))).append(',').append(csv(t.get("batch"))).append("\r\n");
        }
        Map<?, ?> tot = (Map<?, ?>) sum.get("totals");
        sb.append("Total,,,").append(tot.get("inserted")).append(',').append(tot.get("updated")).append(',').append(tot.get("skipped")).append(',')
          .append(tot.get("failed")).append(",,").append(tot.get("notLoaded")).append(",,\r\n");
        return csvFile("etl-run-" + id + "-migration-summary.csv", sb);
    }

    /** Audit trail as CSV. Query: ?clientId=. */
    @GetMapping("/runs/{id}/audit-csv")
    public ResponseEntity<byte[]> auditCsv(@PathVariable Long id, @RequestParam String clientId, HttpServletRequest request) {
        if (adminId(request) == null) return ResponseEntity.status(401).build();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).build();
        List<ImportAudit> rows;
        try { rows = runService.getAudit(id, clientId.trim()); } catch (ImportRunException e) { return ResponseEntity.status(404).build(); }
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("time,actor,action,batch,detail\r\n");
        for (ImportAudit a : rows)
            sb.append(csv(a.getCreatedDate())).append(',').append(csv(a.getActor())).append(',').append(csv(a.getAction())).append(',')
              .append(csv(a.getBatchId())).append(',').append(csv(a.getDetail())).append("\r\n");
        return csvFile("etl-run-" + id + "-audit.csv", sb);
    }

    private static ResponseEntity<byte[]> csvFile(String name, StringBuilder sb) {
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
            .header("Cache-Control", "no-store")
            .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static final List<String> IN_PROGRESS = List.of("DRAFT", "PROFILED", "MAPPED", "STAGED", "VALIDATED", "APPROVED");
    static final List<String> COMPLETED = List.of("LOADED", "ROLLED_BACK");

    /**
     * Service Admin home card: runs in progress and the last five completed imports, across
     * every church (service admins operate all churches; this never reaches a church session).
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        Map<String, String> names = new java.util.HashMap<>();
        java.util.function.Function<ImportRun, Map<String, Object>> view = r -> historyView(r, names);
        Map<String, Object> res = ok("inProgress", runService.recentAcrossChurches(IN_PROGRESS).stream().limit(10).map(view).toList());
        res.put("completed", runService.recentAcrossChurches(COMPLETED).stream().limit(5).map(view).toList());
        return ResponseEntity.ok(res);
    }

    /** A run with its church, files and what it loaded — the overview card and the Start step's history. */
    Map<String, Object> historyView(ImportRun r, Map<String, String> names) {
            Map<String, Object> m = runView(r);
            m.put("church", names.computeIfAbsent(r.getClientId(), this::churchName));
            long ins = 0, upd = 0;
            List<String> types = new java.util.ArrayList<>();
            for (ImportBatch b : loadService.listBatches(r.getId())) {
                if ("LOADED".equals(b.getStatus()) || "FAILED".equals(b.getStatus()) || "ROLLED_BACK".equals(b.getStatus())) {
                    ins += b.getInsertedCount() == null ? 0 : b.getInsertedCount();
                    upd += b.getUpdatedCount() == null ? 0 : b.getUpdatedCount();
                    String l = EtlTargetSchema.LABELS.getOrDefault(b.getTargetTable(), b.getTargetTable());
                    if (!types.contains(l)) types.add(l);
                }
            }
            m.put("inserted", ins); m.put("updated", upd); m.put("dataTypes", types);
            m.put("files", fileRepo == null ? 0 : fileRepo.findByRunIdOrderByCreatedDateAsc(r.getId()).size());
            return m;
    }

    // ───────────────────────── Migration Phase 3: run plan, run load/rollback, households ─────────────────────────

    /** The run's per-table plan (load order, staging counts, batch state). Query: ?clientId=. */
    @GetMapping("/runs/{id}/plan")
    public ResponseEntity<Map<String, Object>> plan(@PathVariable Long id, @RequestParam String clientId, HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            ImportRun run = runService.getRun(id, tenant);
            List<Map<String, Object>> plan = loadService.runPlan(id, tenant);
            Map<String, Object> res = ok("plan", plan);
            res.put("run", runView(run));
            res.put("loadOrder", EtlLoadService.LOAD_ORDER);
            res.put("canLoadRun", plan.stream().anyMatch(t -> ((List<?>) t.get("batches")).stream()
                .anyMatch(b -> "APPROVED".equals(((Map<?, ?>) b).get("status")))));
            res.put("canRollbackRun", plan.stream().anyMatch(t -> ((List<?>) t.get("batches")).stream()
                .anyMatch(b -> "LOADED".equals(((Map<?, ?>) b).get("status")) || "FAILED".equals(((Map<?, ?>) b).get("status")))));
            return ResponseEntity.ok(res);
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        }
    }

    /** Load every APPROVED batch of the run in dependency order (people → income → expenses). Body: {clientId}. */
    @PostMapping("/runs/{id}/load")
    public ResponseEntity<Map<String, Object>> loadRun(@PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
            return guard.guard(id, () -> {
                List<ImportBatch> done = loadService.loadRun(id, tenant, "serviceadmin:" + admin);
                return ResponseEntity.ok(ok("batches", done.stream().map(this::batchView).toList()));
            });
        } catch (BusyException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (LoadException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        }
    }

    /** Reverse every LOADED/FAILED batch of the run in reverse order (expenses → income → people). Body: {clientId}. */
    @PostMapping("/runs/{id}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackRun(@PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try {
            runService.getRun(id, tenant);
            return guard.guard(id, () -> {
                List<ImportBatch> done = loadService.rollbackRun(id, tenant, "serviceadmin:" + admin);
                return ResponseEntity.ok(ok("batches", done.stream().map(this::batchView).toList()));
            });
        } catch (BusyException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        } catch (ImportRunException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (LoadException e) {
            return ResponseEntity.status(409).body(err(e.getMessage()));
        }
    }

    private static final Set<String> FROZEN_ROW = Set.of("APPROVED", "LOADED", "SKIPPED", "FAILED", "ROLLED_BACK");

    /** Proposed households (same last name + address) for the run's people rows. Query: ?clientId=. */
    @GetMapping("/runs/{id}/households")
    public ResponseEntity<Map<String, Object>> households(@PathVariable Long id, @RequestParam String clientId, HttpServletRequest request) {
        if (adminId(request) == null) return unauthorized();
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try { runService.getRun(id, tenant); } catch (ImportRunException e) { return ResponseEntity.status(404).body(err(e.getMessage())); }
        Map<String, List<StagingFamily>> groups = new LinkedHashMap<>();
        for (StagingFamily r : familyRepo.findByRunId(id))
            if (r.getHouseholdProposal() != null) groups.computeIfAbsent(r.getHouseholdProposal(), k -> new java.util.ArrayList<>()).add(r);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        int accepted = 0, rejected = 0, undecided = 0;
        for (Map.Entry<String, List<StagingFamily>> e : groups.entrySet()) {
            StagingFamily first = e.getValue().get(0);
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", e.getKey());
            g.put("decision", first.getHouseholdDecision());
            g.put("lastName", first.getLastName());
            g.put("address", String.join(", ", java.util.stream.Stream.of(first.getAddress1(), first.getCity(), first.getState(), first.getPinCode())
                .filter(x -> x != null && !x.isBlank()).toList()));
            g.put("locked", e.getValue().stream().anyMatch(r -> FROZEN_ROW.contains(r.getRowStatus())));
            g.put("members", e.getValue().stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId()); m.put("sourceRowId", r.getSourceRowId());
                m.put("name", ((r.getFirstName() == null ? "" : r.getFirstName()) + " " + (r.getLastName() == null ? "" : r.getLastName())).trim());
                m.put("role", r.getRole()); m.put("email", r.getEmail()); m.put("phone", r.getPhone());
                m.put("birthYear", r.getBirthdayYear()); m.put("rowStatus", r.getRowStatus()); m.put("matchTier", r.getMatchTier());
                m.put("dedupeAction", r.getDedupeAction());
                return m;
            }).toList());
            out.add(g);
            if ("ACCEPTED".equals(first.getHouseholdDecision())) accepted++; else if ("REJECTED".equals(first.getHouseholdDecision())) rejected++; else undecided++;
        }
        Map<String, Object> res = ok("households", out);
        res.put("accepted", accepted); res.put("rejected", rejected); res.put("undecided", undecided);
        return ResponseEntity.ok(res);
    }

    /** Decide a proposed household. Body: {clientId, key, decision: ACCEPT | REJECT | CLEAR}. */
    @PatchMapping("/runs/{id}/households")
    public ResponseEntity<Map<String, Object>> decideHousehold(@PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        Object admin = adminId(request);
        if (admin == null) return unauthorized();
        String clientId = body == null ? null : body.get("clientId");
        if (!isRealClient(clientId)) return ResponseEntity.status(400).body(err("Unknown or missing clientId"));
        final String tenant = clientId.trim();
        try { runService.getRun(id, tenant); } catch (ImportRunException e) { return ResponseEntity.status(404).body(err(e.getMessage())); }
        String key = body.get("key"), d = body.get("decision") == null ? "" : body.get("decision").trim().toUpperCase();
        String decision = switch (d) { case "ACCEPT", "ACCEPTED" -> "ACCEPTED"; case "REJECT", "REJECTED" -> "REJECTED"; case "CLEAR", "" -> null;
            default -> "?"; };
        if ("?".equals(decision)) return ResponseEntity.status(400).body(err("decision must be ACCEPT, REJECT or CLEAR"));
        List<StagingFamily> rows = familyRepo.findByRunId(id).stream()
            .filter(r -> key != null && key.equals(r.getHouseholdProposal()) && tenant.equals(r.getClientId())).toList();
        if (rows.isEmpty()) return ResponseEntity.status(404).body(err("No proposed household with that key"));
        if (rows.stream().anyMatch(r -> FROZEN_ROW.contains(r.getRowStatus())))
            return ResponseEntity.status(409).body(err("These rows are already approved or loaded — roll the batch back to change households"));
        for (StagingFamily r : rows) {
            r.setHouseholdDecision(decision);
            r.setValidationMsgs(rewriteHouseholdMsg(r.getValidationMsgs(), decision));
            familyRepo.save(r);
        }
        runService.recordAudit(id, tenant, "serviceadmin:" + admin, "HOUSEHOLD", Map.of("key", key, "decision", String.valueOf(decision), "rows", rows.size()));
        Map<String, Object> res = ok("key", key);
        res.put("decision", decision); res.put("rows", rows.size());
        return ResponseEntity.ok(res);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Keep the row's "_household" message in step with the decision. */
    private static String rewriteHouseholdMsg(String json, String decision) {
        if (json == null || json.isBlank()) return json;
        try {
            List<Map<String, String>> msgs = JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
            for (Map<String, String> m : msgs)
                if ("_household".equals(m.get("field")))
                    m.put("message", "Same last name and address as other rows in this file — proposed household"
                        + ("ACCEPTED".equals(decision) ? " (accepted)" : "REJECTED".equals(decision) ? " (rejected)" : " (undecided: stays separate)"));
            return JSON.writeValueAsString(msgs);
        } catch (Exception e) { return json; }
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
