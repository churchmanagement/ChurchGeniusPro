package com.churchgeniuspro.service;

import com.churchgeniuspro.common.ImportRunStatus;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * ETL Phase 6 — Load + Rollback. The ONLY service that writes to the live
 * {@code family}/{@code income}/{@code expense} tables, and it does so under
 * strict guarantees (see the ETL design doc, §7–9 and §12):
 *
 * <ul>
 *   <li><b>Tenant stamp</b> — every inserted live row gets {@code appClientId} =
 *       the run's bound {@code client_id}; updates only touch rows already owned
 *       by that tenant. Source data never chooses the tenant.</li>
 *   <li><b>INSERT-or-SKIP</b> — a row flagged as a duplicate defaults to SKIP;
 *       an UPDATE happens only when the operator explicitly set that action, and a
 *       before-image is saved so it can be reversed.</li>
 *   <li><b>Batch provenance</b> — every staged row keeps its live {@code target_id};
 *       a whole batch is reversible as a unit (inserts → soft-delete, updates →
 *       restore the before-image).</li>
 *   <li><b>Partial-failure isolation</b> — a row that fails to load is marked
 *       FAILED and the batch continues.</li>
 * </ul>
 */
@Service
public class EtlLoadService {

    private static final Logger log = LoggerFactory.getLogger(EtlLoadService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Shared field names between StagingFamily and FamilyMember (copied verbatim). */
    private static final List<String> FAMILY_FIELDS = List.of(
        "firstName", "lastName", "otherName", "email", "phone", "gender", "memberType", "role",
        "address1", "address2", "city", "state", "country", "pinCode",
        "birthdayMonth", "birthdayDay", "birthdayYear", "phonePrivate", "emailPrivate", "addressPrivate");

    private final ImportBatchRepository    batchRepo;
    private final StagingFamilyRepository  sFamilyRepo;
    private final StagingIncomeRepository  sIncomeRepo;
    private final StagingExpenseRepository sExpenseRepo;
    private final FamilyRepository         familyRepo;
    private final FamilyMemberRepository   memberRepo;
    private final IncomeRepository         incomeRepo;
    private final ExpenseRepository        expenseRepo;
    private final EtlReferenceResolver     refResolver;
    private final ImportRunService         runService;
    private final TransactionTemplate      tx;

    /**
     * Plan people limit. Optional so hand-built tests are unchanged; without it no
     * limit is applied, as before.
     */
    private SubscriptionService subscriptionService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubscriptionService(SubscriptionService s) { this.subscriptionService = s; }

    /** Migration Phase 2: tells whether the file behind a target carried real source ids (optional, field-injected). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TransformStageService stageService;
    public void setStageService(TransformStageService s) { this.stageService = s; }

    /** Migration Phase 3: persistent source-id links + reference-row bookkeeping (optional; absent = no links). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtlLinkService linkService;
    public void setLinkService(EtlLinkService s) { this.linkService = s; }

    /** Phase 4: the record data types (pledges, events, groups, kids …) — optional so older hand-built tests are unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtlRecordService recordService;
    public void setRecordService(EtlRecordService s) { this.recordService = s; }

    /**
     * The order tables load in — people and the reference-like records first (gifts,
     * pledges, registrations, memberships and check-ins link to them), dependants after.
     * Rollback runs it backwards. (Phase 3: family → income → expense; Phase 4 adds the rest.)
     */
    public static final List<String> LOAD_ORDER = List.of(
        "family", "pledge_campaign", "event", "group", "km_child",
        "income", "expense", "pledge", "event_registration", "attendance", "group_member", "km_checkin");

    /** Phase 4: which tables' rows a table's rows link to (a batch waits for these; they cannot be undone first). */
    public static final Map<String, List<String>> DEPENDS = Map.of(
        "income", List.of("family"),
        "pledge", List.of("pledge_campaign", "family"),
        "event_registration", List.of("event", "family"),
        "attendance", List.of("family"),
        "group_member", List.of("group", "family"),
        "km_child", List.of("family"),
        "km_checkin", List.of("km_child"));

    private static boolean record(String table) { return com.churchgeniuspro.util.EtlTargetSchema.isRecordTable(table); }

    private EtlRecordService records() {
        if (recordService == null) throw new LoadException("Record data types are not available");
        return recordService;
    }

    /** Word used in ordering messages ("the giving batch", "these people"). */
    private static String word(String table) {
        return switch (table) {
            case "family" -> "people"; case "income" -> "giving";
            default -> com.churchgeniuspro.util.EtlTargetSchema.LABELS.getOrDefault(table, table).toLowerCase(Locale.ROOT);
        };
    }

    /** Rows committed per transaction during a load (bounds tx size on big imports). */
    @Value("${etl.load.chunk-size:500}")
    private int chunkSize;

    public EtlLoadService(ImportBatchRepository batchRepo,
                          StagingFamilyRepository sFamilyRepo,
                          StagingIncomeRepository sIncomeRepo,
                          StagingExpenseRepository sExpenseRepo,
                          FamilyRepository familyRepo,
                          FamilyMemberRepository memberRepo,
                          IncomeRepository incomeRepo,
                          ExpenseRepository expenseRepo,
                          EtlReferenceResolver refResolver,
                          ImportRunService runService,
                          PlatformTransactionManager txManager) {
        this.batchRepo    = batchRepo;
        this.sFamilyRepo  = sFamilyRepo;
        this.sIncomeRepo  = sIncomeRepo;
        this.sExpenseRepo = sExpenseRepo;
        this.familyRepo   = familyRepo;
        this.memberRepo   = memberRepo;
        this.incomeRepo   = incomeRepo;
        this.expenseRepo  = expenseRepo;
        this.refResolver  = refResolver;
        this.runService   = runService;
        this.tx           = new TransactionTemplate(txManager);
    }

    private int chunkSize() { return chunkSize > 0 ? chunkSize : 500; }

    public static class LoadException extends RuntimeException {
        public LoadException(String m) { super(m); }
    }

    // ───────────────────────── per-row review ─────────────────────────

    /**
     * Operator action on a single staged row before load: change the dedupe action
     * (INSERT/UPDATE/SKIP) or reject the row. Tenant-checked via {@code clientId}.
     */
    @Transactional
    public void updateRow(String clientId, String targetTable, Long rowId,
                          String dedupeAction, String rowStatus) {
        String action = dedupeAction == null ? null : normAction(dedupeAction);
        switch (targetTable) {
            case "family" -> {
                StagingFamily r = sFamilyRepo.findById(rowId)
                    .filter(x -> x.getClientId().equals(clientId))
                    .orElseThrow(() -> new LoadException("Row not found for tenant"));
                decide(r.getRowStatus(), r.getMatchTier(), r.getDedupeMatchId(), action, true);
                if (action != null) { r.setDedupeAction(action); if ("REVIEW".equals(r.getRowStatus())) r.setRowStatus("WARN"); }
                if (rowStatus != null) r.setRowStatus(rowStatus.trim().toUpperCase());
                sFamilyRepo.save(r);
            }
            case "income" -> {
                StagingIncome r = sIncomeRepo.findById(rowId)
                    .filter(x -> x.getClientId().equals(clientId))
                    .orElseThrow(() -> new LoadException("Row not found for tenant"));
                decide(r.getRowStatus(), r.getMatchTier(), r.getDedupeMatchId(), action, false);
                if (action != null) { r.setDedupeAction(action); if ("REVIEW".equals(r.getRowStatus())) r.setRowStatus("WARN"); }
                if (rowStatus != null) r.setRowStatus(rowStatus.trim().toUpperCase());
                sIncomeRepo.save(r);
            }
            case "expense" -> {
                StagingExpense r = sExpenseRepo.findById(rowId)
                    .filter(x -> x.getClientId().equals(clientId))
                    .orElseThrow(() -> new LoadException("Row not found for tenant"));
                decide(r.getRowStatus(), r.getMatchTier(), r.getDedupeMatchId(), action, false);
                if (action != null) { r.setDedupeAction(action); if ("REVIEW".equals(r.getRowStatus())) r.setRowStatus("WARN"); }
                if (rowStatus != null) r.setRowStatus(rowStatus.trim().toUpperCase());
                sExpenseRepo.save(r);
            }
            default -> {
                if (!record(targetTable)) throw new LoadException("Unknown target table: " + targetTable);
                StagingRecord r = records().row(rowId, clientId, targetTable);
                if (r == null) throw new LoadException("Row not found for tenant");
                decide(r.getRowStatus(), r.getMatchTier(), r.getDedupeMatchId(), action, !EtlRecordService.financial(targetTable));
                if (action != null) { r.setDedupeAction(action); if ("REVIEW".equals(r.getRowStatus())) r.setRowStatus("WARN"); }
                if (rowStatus != null) r.setRowStatus(rowStatus.trim().toUpperCase());
                records().save(r);
            }
        }
    }

    /**
     * Migration Phase 2 — which decisions a row may take. MERGE (fill empty fields)
     * is for people only; UPDATE and MERGE need a matched live row; a financial row
     * may be UPDATEd only from an EXACT or STRONG match, never from a POSSIBLE one.
     */
    private void decide(String rowStatus, String tier, Long matchId, String action, boolean people) {
        if (action == null) return;
        if ("LOADED".equals(rowStatus) || "SKIPPED".equals(rowStatus) || "ROLLED_BACK".equals(rowStatus))
            throw new LoadException("Row is already " + rowStatus);
        if ("MERGE".equals(action)) {
            if (!people) throw new LoadException("Financial rows cannot be merged — choose Skip, Update or Import as new");
            if (matchId == null) throw new LoadException("Merge needs a matched existing record");
        }
        if ("UPDATE".equals(action)) {
            if (matchId == null) throw new LoadException("Update needs a matched existing record");
            if (!people && !(EtlMatchService.EXACT.equals(tier) || EtlMatchService.STRONG.equals(tier)))
                throw new LoadException("A financial row can only update an exact or strong match (this one is " + tier + ")");
        }
    }

    private String normAction(String a) {
        String s = a.trim().toUpperCase();
        if (!Set.of("INSERT", "UPDATE", "SKIP", "MERGE").contains(s))
            throw new LoadException("Invalid dedupe action: " + a);
        return s;
    }

    // ───────────────────────── approve batch ─────────────────────────

    /** Create an APPROVED batch over the run's loadable (VALID/WARN) rows for a table. */
    @Transactional
    public ImportBatch createBatch(Long runId, String clientId, String actor, String targetTable) {
        ImportRun run = runService.getRun(runId, clientId);
        ImportRunStatus rs = ImportRunStatus.fromString(run.getStatus());
        if (rs == null || !(rs == ImportRunStatus.VALIDATED || rs == ImportRunStatus.APPROVED
                || rs == ImportRunStatus.LOADED))
            throw new LoadException("Run must be VALIDATED before approving a batch (is " + run.getStatus() + ")");

        long undecided = countReview(runId, targetTable);
        if (undecided > 0)
            throw new LoadException(undecided + " row(s) are possible duplicates awaiting a decision (skip, update or import as new) — resolve them before approving");

        int count = tagLoadable(runId, targetTable);
        if (count == 0)
            throw new LoadException("No loadable (VALID/WARN) rows for '" + targetTable + "'");

        ImportBatch batch = new ImportBatch();
        batch.setRunId(runId);
        batch.setClientId(clientId);
        batch.setTargetTable(targetTable);
        batch.setStatus("APPROVED");
        batch.setApprovedBy(actor);
        batch.setApprovedDate(java.time.LocalDateTime.now());
        batch = batchRepo.save(batch);

        // Stamp the batch id onto the rows it covers.
        assignBatch(runId, targetTable, batch.getId());

        if (rs == ImportRunStatus.VALIDATED)
            runService.transition(runId, clientId, actor, ImportRunStatus.APPROVED);
        runService.recordAudit(runId, clientId, actor, "APPROVE", Map.of(
            "batchId", batch.getId(), "targetTable", targetTable, "rows", count));
        log.info("ETL run {} approved batch {} ({} {} rows)", runId, batch.getId(), count, targetTable);
        return batch;
    }

    /** Migration Phase 2: rows still in REVIEW (possible duplicate, no decision yet). */
    public long countReview(Long runId, String table) {
        return switch (table) {
            case "family"  -> sFamilyRepo.findByRunIdAndRowStatus(runId, "REVIEW").size();
            case "income"  -> sIncomeRepo.findByRunIdAndRowStatus(runId, "REVIEW").size();
            case "expense" -> sExpenseRepo.findByRunIdAndRowStatus(runId, "REVIEW").size();
            default -> record(table) ? records().countReview(runId, table) : 0;
        };
    }

    /** Mark loadable rows APPROVED (skip ERROR/REVIEW/already-loaded). Returns count. */
    private int tagLoadable(Long runId, String table) {
        int n = 0;
        switch (table) {
            case "family" -> { for (StagingFamily r : sFamilyRepo.findByRunId(runId))
                if (loadable(r.getRowStatus())) { r.setRowStatus("APPROVED"); sFamilyRepo.save(r); n++; } }
            case "income" -> { for (StagingIncome r : sIncomeRepo.findByRunId(runId))
                if (loadable(r.getRowStatus())) { r.setRowStatus("APPROVED"); sIncomeRepo.save(r); n++; } }
            case "expense" -> { for (StagingExpense r : sExpenseRepo.findByRunId(runId))
                if (loadable(r.getRowStatus())) { r.setRowStatus("APPROVED"); sExpenseRepo.save(r); n++; } }
            default -> {
                if (!record(table)) throw new LoadException("Unknown target table: " + table);
                n = records().tagLoadable(runId, table);
            }
        }
        return n;
    }

    private boolean loadable(String status) {
        return "VALID".equals(status) || "WARN".equals(status) || "APPROVED".equals(status);
    }

    private void assignBatch(Long runId, String table, Long batchId) {
        switch (table) {
            case "family" -> { for (StagingFamily r : sFamilyRepo.findByRunIdAndRowStatus(runId, "APPROVED"))
                { r.setBatchId(batchId); sFamilyRepo.save(r); } }
            case "income" -> { for (StagingIncome r : sIncomeRepo.findByRunIdAndRowStatus(runId, "APPROVED"))
                { r.setBatchId(batchId); sIncomeRepo.save(r); } }
            case "expense" -> { for (StagingExpense r : sExpenseRepo.findByRunIdAndRowStatus(runId, "APPROVED"))
                { r.setBatchId(batchId); sExpenseRepo.save(r); } }
            default -> { if (record(table)) records().assignBatch(runId, table, batchId); }
        }
    }

    // ───────────────────────── load (chunked commits) ─────────────────────────

    /**
     * Load an APPROVED batch into the live tables, committing in chunks of
     * {@link #chunkSize()} rows. Bounding the transaction size keeps large imports
     * from holding one giant transaction; if a later chunk fails, earlier chunks
     * are already durably committed (and still batch-reversible), and a re-run
     * simply continues the remaining still-APPROVED rows.
     */
    public ImportBatch loadBatch(Long batchId, String clientId, String actor) {
        ImportBatch batch = batchRepo.findByIdAndClientId(batchId, clientId)
            .orElseThrow(() -> new LoadException("Batch not found for tenant"));
        // Phase 3: a FAILED batch may be retried — it continues with the rows still APPROVED.
        if (!"APPROVED".equals(batch.getStatus()) && !"FAILED".equals(batch.getStatus()))
            throw new LoadException("Batch is not APPROVED (is " + batch.getStatus() + ")");
        requireDependenciesLoaded(batch);

        final Long bid = batch.getId();
        final String system = sourceSystem(batch.getRunId(), clientId);
        final Ctx ctx = new Ctx(batch.getRunId(), bid, clientId, actor, system);
        // Totals accumulate across chunks so a part-way failure still reports what DID commit.
        // A retry starts from the counts already on the batch.
        int[] c = {nz(batch.getInsertedCount()), nz(batch.getUpdatedCount()), nz(batch.getSkippedCount()), nz(batch.getFailedCount())};
        try {
            switch (batch.getTargetTable()) {
                case "family"  -> loadFamilyChunked(ctx, c);
                case "income"  -> loadIncomeChunked(ctx, c);
                case "expense" -> loadExpenseChunked(ctx, c);
                default -> {
                    if (!record(batch.getTargetTable())) throw new LoadException("Unknown target table: " + batch.getTargetTable());
                    loadRecordsChunked(batch.getTargetTable(), ctx, c);
                }
            }
        } catch (RuntimeException ex) {
            // Phase 3: the chunks that committed are durable and stay reversible; the batch
            // says so (FAILED + reason) instead of silently staying APPROVED. The admin can
            // retry (remaining rows) or roll the batch back.
            final int[] cc = c;
            final String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            tx.execute(st -> {
                ImportBatch b = batchRepo.findByIdAndClientId(bid, clientId).orElseThrow(() -> new LoadException("Batch vanished"));
                b.setInsertedCount(cc[0]); b.setUpdatedCount(cc[1]); b.setSkippedCount(cc[2]); b.setFailedCount(cc[3]);
                b.setStatus("FAILED"); b.setErrorMessage(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
                return batchRepo.save(b);
            });
            runService.recordAudit(batch.getRunId(), clientId, actor, "LOAD_FAILED", Map.of(
                "batchId", batchId, "targetTable", batch.getTargetTable(), "reason", reason,
                "inserted", c[0], "updated", c[1], "skipped", c[2], "failed", c[3]));
            log.warn("ETL batch {} load stopped: {} ({} inserted, {} updated so far)", batchId, reason, c[0], c[1]);
            throw ex instanceof LoadException le ? le : new LoadException("Load stopped: " + reason + " — rows already loaded are kept and can be rolled back; retry continues the rest");
        }

        final int[] cc = c;
        ImportBatch updated = tx.execute(st -> {
            ImportBatch b = batchRepo.findByIdAndClientId(bid, clientId).orElseThrow(() -> new LoadException("Batch vanished"));
            b.setInsertedCount(cc[0]); b.setUpdatedCount(cc[1]);
            b.setSkippedCount(cc[2]);  b.setFailedCount(cc[3]);
            b.setStatus("LOADED"); b.setErrorMessage(null);
            return batchRepo.save(b);
        });

        ImportRun run = runService.getRun(batch.getRunId(), clientId);
        if (ImportRunStatus.fromString(run.getStatus()) == ImportRunStatus.APPROVED)
            runService.transition(batch.getRunId(), clientId, actor, ImportRunStatus.LOADED);
        runService.recordAudit(batch.getRunId(), clientId, actor, "LOAD", Map.of(
            "batchId", batchId, "targetTable", batch.getTargetTable(),
            "inserted", c[0], "updated", c[1], "skipped", c[2], "failed", c[3], "chunkSize", chunkSize()));
        log.info("ETL batch {} loaded in chunks of {}: {} inserted, {} updated, {} skipped, {} failed",
            batchId, chunkSize(), c[0], c[1], c[2], c[3]);
        return updated;
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    /** Per-load context shared by the chunk workers. */
    private record Ctx(Long runId, Long batchId, String tenant, String actor, String system) {}

    private String sourceSystem(Long runId, String tenant) {
        try { return com.churchgeniuspro.util.SourcePreset.of(runService.getRun(runId, tenant).getSourcePreset()).name(); }
        catch (Exception e) { return "GENERIC"; }
    }

    /**
     * Phase 3 dependency rule: gifts link to people, so an income batch may not load
     * while the same run still has an unloaded people batch — the links would miss.
     * (Expenses depend on nobody.)
     */
    private void requireDependenciesLoaded(ImportBatch batch) {
        String table = batch.getTargetTable();
        for (String parent : DEPENDS.getOrDefault(table, List.of()))
            for (ImportBatch b : batchRepo.findByRunIdAndTargetTable(batch.getRunId(), parent))
                if ("APPROVED".equals(b.getStatus()) || "FAILED".equals(b.getStatus()))
                    throw new LoadException("Load the " + word(parent) + " batch #" + b.getId() + " first — the "
                        + ("income".equals(table) ? "gifts" : "rows") + " in this batch link to it");
        // Phase 4: a record row may point at a parent / person staged in this run that has no batch yet.
        if (record(table)) {
            String waiting = records().waitingFor(batch.getId(), table, batch.getClientId());
            if (waiting != null) throw new LoadException(waiting);
        }
    }

    /**
     * Phase 3 — load every APPROVED batch of a run in dependency order (people, then
     * income, then expenses). Stops at the first batch that fails; what loaded stays
     * loaded and reversible, and the failure is reported on that batch.
     */
    public List<ImportBatch> loadRun(Long runId, String clientId, String actor) {
        runService.getRun(runId, clientId);   // tenant ownership
        List<ImportBatch> out = new ArrayList<>();
        for (String table : LOAD_ORDER)
            for (ImportBatch b : batchRepo.findByRunIdAndTargetTable(runId, table))
                if ("APPROVED".equals(b.getStatus())) out.add(loadBatch(b.getId(), clientId, actor));
        if (out.isEmpty()) throw new LoadException("No approved batches to load in this run");
        runService.recordAudit(runId, clientId, actor, "LOAD_RUN", Map.of("batches", out.stream().map(ImportBatch::getId).toList()));
        return out;
    }

    private void add(int[] total, int[] c) { for (int i = 0; i < 4; i++) total[i] += c[i]; }

    /**
     * Phase 3: the household a people row loads into — the admin-ACCEPTED proposal when
     * there is one, else the file's own household key, else the row by itself.
     */
    static String householdKey(StagingFamily r) {
        if ("ACCEPTED".equals(r.getHouseholdDecision()) && r.getHouseholdProposal() != null && !r.getHouseholdProposal().isBlank())
            return "hh:" + r.getHouseholdProposal();
        return r.getFamilyKey() == null ? ("row:" + r.getId()) : r.getFamilyKey();
    }

    // —— family (households kept whole within a chunk) ——
    private void loadFamilyChunked(Ctx ctx, int[] total) {
        List<StagingFamily> rows = sFamilyRepo.findByBatchId(ctx.batchId()).stream()
            .filter(r -> "APPROVED".equals(r.getRowStatus())).toList();
        requireRoomForNewPeople(rows, ctx.tenant());
        LinkedHashMap<String, List<Long>> groups = new LinkedHashMap<>();
        for (StagingFamily r : rows)
            groups.computeIfAbsent(householdKey(r), k -> new ArrayList<>()).add(r.getId());
        List<List<Long>> chunks = new ArrayList<>();
        List<Long> cur = new ArrayList<>();
        for (List<Long> grp : groups.values()) {
            if (!cur.isEmpty() && cur.size() + grp.size() > chunkSize()) { chunks.add(cur); cur = new ArrayList<>(); }
            cur.addAll(grp);
        }
        if (!cur.isEmpty()) chunks.add(cur);

        for (List<Long> ids : chunks)
            add(total, tx.execute(st -> loadFamilyChunk(ids, ctx)));
    }

    private int[] loadFamilyChunk(List<Long> ids, Ctx ctx) {
        final String tenant = ctx.tenant();
        int inserted = 0, updated = 0, skipped = 0, failed = 0;
        Map<String, Family> familyByKey = new HashMap<>();
        List<StagingFamily> staged = sFamilyRepo.findAllById(ids);
        String system = ctx.system(); boolean hasIds = false;
        if (!staged.isEmpty())
            hasIds = stageService != null && stageService.hasSourceIdsForTarget(ctx.runId(), "family");
        for (StagingFamily r : staged) {
            if (!"APPROVED".equals(r.getRowStatus())) continue;
            String action = effectiveAction(r.getDedupeAction(), r.getDedupeMatchId());
            try {
                if ("SKIP".equals(action)) { r.setRowStatus("SKIPPED"); skipped++; }
                else if ("UPDATE".equals(action) || "MERGE".equals(action)) {
                    FamilyMember m = memberRepo.findById(r.getDedupeMatchId().intValue())
                        .filter(x -> x.getFamily() != null && tenant.equals(x.getFamily().getAppClientId()))
                        .orElse(null);
                    if (m == null) { r.setRowStatus("FAILED"); failed++; }
                    else {
                        r.setBeforeImage(snapshot(m));
                        if ("MERGE".equals(action)) mergeFamilyFields(r, m); else copyFamilyFields(r, m);
                        // Phase 2: a matched record learns its source identity (so the next import is EXACT).
                        if (hasIds && m.getSourceId() == null && r.getSourceRowId() != null) { m.setSourceSystem(system); m.setSourceId(r.getSourceRowId()); }
                        memberRepo.save(m);
                        r.setTargetId(m.getId().longValue()); r.setRowStatus("LOADED"); updated++;
                        if (hasIds) link(ctx, ImportLink.PERSON, r.getSourceRowId(), m.getId().longValue());
                    }
                } else { // INSERT
                    String key = householdKey(r);
                    final String sys = system; final boolean withIds = hasIds && !key.startsWith("hh:") && !key.startsWith("row:");
                    // A household LINK is only meaningful when the file had a household id of its own —
                    // not when the key is just the person's own id (the person link already covers that).
                    final boolean realHousehold = withIds && (r.getSourceRowId() == null || !r.getSourceRowId().trim().equals(key.trim()));
                    Family fam = familyByKey.computeIfAbsent(key, k -> {
                        Family f = new Family();
                        f.setAppClientId(tenant); f.setInactive(false);
                        f.setDeleteFlag(false); f.setCreatedDate(new Date());
                        if (withIds) { f.setSourceSystem(sys); f.setSourceId(r.getFamilyKey()); }
                        Family saved = familyRepo.save(f);
                        if (realHousehold) link(ctx, ImportLink.HOUSEHOLD, r.getFamilyKey(), saved.getId().longValue());
                        return saved;
                    });
                    FamilyMember m = new FamilyMember();
                    m.setFamily(fam); copyFamilyFields(r, m);
                    m.setAppClientId(tenant); m.setDeleteFlag(false);
                    m.setCreatedDate(new Date());
                    if (hasIds && r.getSourceRowId() != null) { m.setSourceSystem(system); m.setSourceId(r.getSourceRowId()); }
                    m = memberRepo.save(m);
                    r.setTargetId(m.getId().longValue()); r.setParentTargetId(fam.getId().longValue());
                    r.setRowStatus("LOADED"); inserted++;
                    if (hasIds) link(ctx, ImportLink.PERSON, r.getSourceRowId(), m.getId().longValue());
                }
            } catch (Exception e) {
                r.setRowStatus("FAILED"); failed++;
                log.warn("ETL family row {} load failed: {}", r.getId(), e.getMessage());
            }
            sFamilyRepo.save(r);
        }
        return new int[]{inserted, updated, skipped, failed};
    }

    /** Phase 3: learn a source-id link (no-op without the link service or a key). */
    private void link(Ctx ctx, String entity, String sourceKey, Long targetId) {
        if (linkService == null || sourceKey == null || sourceKey.isBlank() || targetId == null) return;
        try { linkService.record(ctx.tenant(), ctx.system(), entity, sourceKey, targetId, ctx.runId(), ctx.batchId()); }
        catch (Exception e) { log.warn("ETL link {}:{} not recorded: {}", entity, sourceKey, e.getMessage()); }
    }

    // —— Phase 4: record data types ——
    private void loadRecordsChunked(String table, Ctx ctx, int[] total) {
        List<Long> ids = records().batchRows(ctx.batchId()).stream()
            .filter(r -> "APPROVED".equals(r.getRowStatus())).map(StagingRecord::getId).toList();
        boolean hasIds = stageService != null && stageService.hasSourceIdsForTarget(ctx.runId(), table);
        EtlRecordService.LoadCache cache = records().cache(ctx.tenant());
        for (int i = 0; i < ids.size(); i += chunkSize()) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize(), ids.size()));
            add(total, tx.execute(st -> records().loadChunk(table, chunk, ctx.tenant(), ctx.actor(), ctx.runId(), ctx.batchId(), ctx.system(), hasIds, cache)));
        }
    }

    // —— income ——
    private void loadIncomeChunked(Ctx ctx, int[] total) {
        List<Long> ids = sIncomeRepo.findByBatchId(ctx.batchId()).stream()
            .filter(r -> "APPROVED".equals(r.getRowStatus())).map(StagingIncome::getId).toList();
        EtlReferenceResolver.Session ref = refResolver.session(ctx.tenant());
        Map<String, Integer> memberIndex = buildMemberIndex(ctx.runId(), ctx.tenant());
        boolean hasIds = stageService != null && stageService.hasSourceIdsForTarget(ctx.runId(), "income");
        for (int i = 0; i < ids.size(); i += chunkSize()) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize(), ids.size()));
            add(total, tx.execute(st -> {
                int[] c = loadIncomeChunk(chunk, ctx, ref, memberIndex, hasIds);
                if (linkService != null) linkService.recordReferenceRows(ctx.tenant(), ctx.system(), ctx.runId(), ctx.batchId(), ref);
                return c;
            }));
        }
    }

    private int[] loadIncomeChunk(List<Long> ids, Ctx ctx, EtlReferenceResolver.Session ref,
                                  Map<String, Integer> memberIndex, boolean hasIds) {
        final String tenant = ctx.tenant(), actor = ctx.actor();
        int inserted = 0, updated = 0, skipped = 0, failed = 0;
        for (StagingIncome r : sIncomeRepo.findAllById(ids)) {
            if (!"APPROVED".equals(r.getRowStatus())) continue;
            String action = effectiveAction(r.getDedupeAction(), r.getDedupeMatchId());
            try {
                if ("SKIP".equals(action)) { r.setRowStatus("SKIPPED"); skipped++; }
                else if ("UPDATE".equals(action)) {
                    // Financial audit M9: only SKIP was ever honoured here — choosing
                    // UPDATE for a duplicate fell through to this same INSERT branch and
                    // created a second row, with the "updated" counter permanently stuck
                    // at 0. Tenant-filtered lookup by dedupeMatchId, mirroring the family
                    // table's own (already-correct) UPDATE branch below.
                    Income e = incomeRepo.findById(r.getDedupeMatchId().intValue())
                        .filter(x -> tenant.equals(x.getAppClientId()))
                        .orElse(null);
                    if (e == null) { r.setRowStatus("FAILED"); failed++; }
                    else {
                        r.setBeforeImage(snapshotIncome(e));
                        copyIncomeFields(r, e, ref, memberIndex);
                        e.setUpdatedBy(actor); e.setUpdatedDate(new Date());
                        incomeRepo.save(e);
                        r.setTargetId(e.getId().longValue()); r.setRowStatus("LOADED"); updated++;
                        if (hasIds) link(ctx, ImportLink.INCOME, r.getSourceRowId(), e.getId().longValue());
                    }
                } else { // INSERT
                    Income e = new Income();
                    copyIncomeFields(r, e, ref, memberIndex);
                    e.setImportRef(uniqueRef(r.getImportRef(), r.getId(), tenant, true));
                    e.setAppClientId(tenant); e.setDeleteFlag(false); e.setQuickAdd(false);
                    e.setCreatedDate(new Date()); e.setCreatedBy(actor);
                    e = incomeRepo.save(e);
                    r.setTargetId(e.getId().longValue()); r.setRowStatus("LOADED"); inserted++;
                    if (hasIds) link(ctx, ImportLink.INCOME, r.getSourceRowId(), e.getId().longValue());
                }
            } catch (Exception ex) {
                r.setRowStatus("FAILED"); failed++;
                log.warn("ETL income row {} load failed: {}", r.getId(), ex.getMessage());
            }
            sIncomeRepo.save(r);
        }
        return new int[]{inserted, updated, skipped, failed};
    }

    /**
     * Applies the staging row's content onto a live Income — shared by INSERT
     * (a fresh row, so every field starts blank) and UPDATE (an existing row,
     * financial audit M9). refNo/note only overwrite when the staging row
     * actually supplied one, so a sparse import row can never blank out data
     * the existing row already had; amount/date/fund are always applied,
     * since a loadable (VALID/WARN) row is expected to carry them.
     */
    private void copyIncomeFields(StagingIncome r, Income e, EtlReferenceResolver.Session ref,
                                  Map<String, Integer> memberIndex) {
        e.setAmount(r.getAmount());
        e.setIncomeDate(r.getIncomeDate());
        e.setSubSource(ref.subSource(r.getSourceName(), r.getSubSourceName()));
        if (r.getReferenceNo() != null) e.setRefNo(r.getReferenceNo());
        if (r.getNotes() != null) e.setNote(r.getNotes());
        // Migration Phase 1: the mapped payment method used to be validated and then dropped.
        if (r.getMethod() != null) e.setTransactionType(ref.transactionType(r.getMethod()));
        Integer mid = resolveMember(r.getFamilyLink(), memberIndex);
        if (mid != null) {
            e.setMember(memberRepo.findById(mid).orElse(null));
            r.setResolvedMemberId(mid.longValue());
        }
        // Approved decision 4: an unresolved donor is kept as the guest name the live
        // schema already supports — never attached to an uncertain member.
        if (mid == null && r.getGuestName() != null && !r.getGuestName().isBlank()) e.setGuestName(r.getGuestName().trim());
    }

    /**
     * Financial audit M9: before-image for an Income UPDATE, so rollback can
     * restore it (mirrors {@link #snapshot(FamilyMember)} for family). FK
     * fields are captured by id, never the entity itself — an entity
     * reference would either drag in a lazy-loaded object graph outside a
     * live session or (worse) get silently dropped by Jackson — and the
     * amount is captured as a decimal STRING, never a raw JSON number, so a
     * round-trip through Jackson can never quietly lose precision on money,
     * the same discipline this codebase applies to money everywhere else.
     */
    private String snapshotIncome(Income e) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("amount", e.getAmount() == null ? null : e.getAmount().toPlainString());
        snap.put("incomeDate", e.getIncomeDate() == null ? null : e.getIncomeDate().toString());
        snap.put("subSourceId", e.getSubSource() == null ? null : e.getSubSource().getId());
        snap.put("memberId", e.getMember() == null ? null : e.getMember().getId());
        snap.put("refNo", e.getRefNo());
        snap.put("note", e.getNote());
        snap.put("transactionTypeId", e.getTransactionType() == null ? null : e.getTransactionType().getId());
        snap.put("guestName", e.getGuestName());
        try { return MAPPER.writeValueAsString(snap); } catch (Exception ex) { return null; }
    }

    private void restoreIncome(Income e, String beforeImage, String tenant) {
        try {
            Map<String, Object> snap = MAPPER.readValue(beforeImage, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object amt = snap.get("amount");
            e.setAmount(amt == null ? null : new BigDecimal((String) amt));
            Object dt = snap.get("incomeDate");
            e.setIncomeDate(dt == null ? null : LocalDate.parse((String) dt));
            Integer subId = asInt(snap.get("subSourceId"));
            e.setSubSource(subId == null ? null : refResolver.subSourceById(subId, tenant).orElse(null));
            Integer memId = asInt(snap.get("memberId"));
            e.setMember(memId == null ? null : memberRepo.findById(memId)
                .filter(m -> m.getFamily() != null && tenant.equals(m.getFamily().getAppClientId()))
                .orElse(null));
            e.setRefNo((String) snap.get("refNo"));
            e.setNote((String) snap.get("note"));
            if (snap.containsKey("transactionTypeId")) {
                Integer ttId = asInt(snap.get("transactionTypeId"));
                e.setTransactionType(ttId == null ? null : refResolver.transactionTypeById(ttId, tenant).orElse(null));
            }
            if (snap.containsKey("guestName")) e.setGuestName((String) snap.get("guestName"));
        } catch (Exception ex) {
            log.warn("ETL restore failed for income {}: {}", e.getId(), ex.getMessage());
        }
    }

    // —— expense ——
    private void loadExpenseChunked(Ctx ctx, int[] total) {
        List<Long> ids = sExpenseRepo.findByBatchId(ctx.batchId()).stream()
            .filter(r -> "APPROVED".equals(r.getRowStatus())).map(StagingExpense::getId).toList();
        EtlReferenceResolver.Session ref = refResolver.session(ctx.tenant());
        boolean hasIds = stageService != null && stageService.hasSourceIdsForTarget(ctx.runId(), "expense");
        for (int i = 0; i < ids.size(); i += chunkSize()) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize(), ids.size()));
            add(total, tx.execute(st -> {
                int[] c = loadExpenseChunk(chunk, ctx, ref, hasIds);
                if (linkService != null) linkService.recordReferenceRows(ctx.tenant(), ctx.system(), ctx.runId(), ctx.batchId(), ref);
                return c;
            }));
        }
    }

    private int[] loadExpenseChunk(List<Long> ids, Ctx ctx, EtlReferenceResolver.Session ref, boolean hasIds) {
        final String tenant = ctx.tenant(), actor = ctx.actor();
        int inserted = 0, updated = 0, skipped = 0, failed = 0;
        for (StagingExpense r : sExpenseRepo.findAllById(ids)) {
            if (!"APPROVED".equals(r.getRowStatus())) continue;
            String action = effectiveAction(r.getDedupeAction(), r.getDedupeMatchId());
            try {
                if ("SKIP".equals(action)) { r.setRowStatus("SKIPPED"); skipped++; }
                else if ("UPDATE".equals(action)) {
                    // Financial audit M9 — see the identical note in loadIncomeChunk.
                    Expense e = expenseRepo.findById(r.getDedupeMatchId().intValue())
                        .filter(x -> tenant.equals(x.getAppClientId()))
                        .orElse(null);
                    if (e == null) { r.setRowStatus("FAILED"); failed++; }
                    else {
                        r.setBeforeImage(snapshotExpense(e));
                        copyExpenseFields(r, e, ref);
                        e.setUpdatedBy(actor); e.setUpdatedDate(new Date());
                        expenseRepo.save(e);
                        r.setTargetId(e.getId().longValue()); r.setRowStatus("LOADED"); updated++;
                        if (hasIds) link(ctx, ImportLink.EXPENSE, r.getSourceRowId(), e.getId().longValue());
                    }
                } else { // INSERT
                    Expense e = new Expense();
                    copyExpenseFields(r, e, ref);
                    e.setImportRef(uniqueRef(r.getImportRef(), r.getId(), tenant, false));
                    e.setAppClientId(tenant); e.setDeleteFlag(false); e.setQuickAdd(false);
                    e.setCreatedDate(new Date()); e.setCreatedBy(actor);
                    e = expenseRepo.save(e);
                    r.setTargetId(e.getId().longValue()); r.setRowStatus("LOADED"); inserted++;
                    if (hasIds) link(ctx, ImportLink.EXPENSE, r.getSourceRowId(), e.getId().longValue());
                }
            } catch (Exception ex) {
                r.setRowStatus("FAILED"); failed++;
                log.warn("ETL expense row {} load failed: {}", r.getId(), ex.getMessage());
            }
            sExpenseRepo.save(r);
        }
        return new int[]{inserted, updated, skipped, failed};
    }

    /** Expense twin of {@link #copyIncomeFields} — same sparse-overwrite rule for refNo/note. */
    private void copyExpenseFields(StagingExpense r, Expense e, EtlReferenceResolver.Session ref) {
        e.setAmount(r.getAmount());
        e.setExpenseDate(r.getExpenseDate());
        e.setPurpose(ref.purpose(r.getPurposeName() != null ? r.getPurposeName() : r.getCategory()));
        e.setMainSource(ref.mainSource(r.getCategory() != null ? r.getCategory() : r.getPurposeName()));
        if (r.getReferenceNo() != null) e.setRefNo(r.getReferenceNo());
        // Migration Phase 1: payee was mapped, validated and dropped. The live Expense has no
        // payee column, so it is kept at the head of the note ("Payee: Acme Supply").
        String note = r.getNotes();
        if (r.getPayee() != null && !r.getPayee().isBlank()) {
            String payeeLine = "Payee: " + r.getPayee().trim();
            note = (note == null || note.isBlank()) ? payeeLine : payeeLine + "\n" + note;
        }
        if (note != null) e.setNote(note);
        if (r.getMethod() != null) e.setTransactionType(ref.transactionType(r.getMethod()));
    }

    /** Expense twin of {@link #snapshotIncome} (financial audit M9). */
    private String snapshotExpense(Expense e) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("amount", e.getAmount() == null ? null : e.getAmount().toPlainString());
        snap.put("expenseDate", e.getExpenseDate() == null ? null : e.getExpenseDate().toString());
        snap.put("purposeId", e.getPurpose() == null ? null : e.getPurpose().getId());
        snap.put("mainSourceId", e.getMainSource() == null ? null : e.getMainSource().getId());
        snap.put("refNo", e.getRefNo());
        snap.put("note", e.getNote());
        snap.put("transactionTypeId", e.getTransactionType() == null ? null : e.getTransactionType().getId());
        try { return MAPPER.writeValueAsString(snap); } catch (Exception ex) { return null; }
    }

    private void restoreExpense(Expense e, String beforeImage, String tenant) {
        try {
            Map<String, Object> snap = MAPPER.readValue(beforeImage, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object amt = snap.get("amount");
            e.setAmount(amt == null ? null : new BigDecimal((String) amt));
            Object dt = snap.get("expenseDate");
            e.setExpenseDate(dt == null ? null : LocalDate.parse((String) dt));
            Integer purposeId = asInt(snap.get("purposeId"));
            e.setPurpose(purposeId == null ? null : refResolver.purposeById(purposeId, tenant).orElse(null));
            Integer mainId = asInt(snap.get("mainSourceId"));
            e.setMainSource(mainId == null ? null : refResolver.mainSourceById(mainId, tenant).orElse(null));
            e.setRefNo((String) snap.get("refNo"));
            e.setNote((String) snap.get("note"));
            if (snap.containsKey("transactionTypeId")) {
                Integer ttId = asInt(snap.get("transactionTypeId"));
                e.setTransactionType(ttId == null ? null : refResolver.transactionTypeById(ttId, tenant).orElse(null));
            }
        } catch (Exception ex) {
            log.warn("ETL restore failed for expense {}: {}", e.getId(), ex.getMessage());
        }
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        return Integer.valueOf(o.toString());
    }

    // ───────────────────────── rollback ─────────────────────────

    @Transactional
    public ImportBatch rollbackBatch(Long batchId, String clientId, String actor) {
        ImportBatch batch = batchRepo.findByIdAndClientId(batchId, clientId)
            .orElseThrow(() -> new LoadException("Batch not found for tenant"));
        // Phase 3: a FAILED batch (load stopped part-way) is reversible too — its committed chunks are real rows.
        if (!"LOADED".equals(batch.getStatus()) && !"FAILED".equals(batch.getStatus()))
            throw new LoadException("Only a LOADED or FAILED batch can be rolled back (is " + batch.getStatus() + ")");
        // Phase 3/4 ordering: a table may not be reversed while the run's rows that link to it are still live.
        for (Map.Entry<String, List<String>> d : DEPENDS.entrySet()) {
            if (!d.getValue().contains(batch.getTargetTable())) continue;
            for (ImportBatch b : batchRepo.findByRunIdAndTargetTable(batch.getRunId(), d.getKey()))
                if ("LOADED".equals(b.getStatus()) || "FAILED".equals(b.getStatus()))
                    throw new LoadException("Roll back the " + word(d.getKey()) + " batch #" + b.getId() + " first — its rows link to these " + word(batch.getTargetTable()));
        }

        int reversed = switch (batch.getTargetTable()) {
            case "family"  -> rollbackFamily(batch, clientId);
            case "income"  -> rollbackMoney(batch, clientId, "income");
            case "expense" -> rollbackMoney(batch, clientId, "expense");
            default -> {
                if (!record(batch.getTargetTable())) throw new LoadException("Unknown target table: " + batch.getTargetTable());
                yield records().rollback(batch, clientId);
            }
        };
        // Phase 3: forget what this batch taught the resolver; remove the funds/purposes/methods
        // it created that nothing uses any more (kept + reported when something does).
        int[] links = linkService == null ? new int[]{0, 0, 0} : linkService.rollbackBatch(clientId, batch.getId());
        batch.setStatus("ROLLED_BACK");
        batch = batchRepo.save(batch);

        // Phase 3 truthfulness: the RUN is rolled back only when no batch of it is still live.
        boolean othersLive = batchRepo.findByRunIdOrderByIdAsc(batch.getRunId()).stream()
            .anyMatch(b -> !b.getId().equals(batchId) && ("LOADED".equals(b.getStatus()) || "FAILED".equals(b.getStatus())));
        ImportRun run = runService.getRun(batch.getRunId(), clientId);
        if (!othersLive && ImportRunStatus.fromString(run.getStatus()) == ImportRunStatus.LOADED)
            runService.transition(batch.getRunId(), clientId, actor, ImportRunStatus.ROLLED_BACK);
        runService.recordAudit(batch.getRunId(), clientId, actor, "ROLLBACK", Map.of(
            "batchId", batchId, "targetTable", batch.getTargetTable(), "reversed", reversed,
            "linksDeleted", links[0], "referenceRowsRemoved", links[1], "referenceRowsKept", links[2],
            "runStillLoaded", othersLive));
        log.info("ETL batch {} rolled back: {} live rows reversed, {} links deleted, {} reference rows removed ({} kept)",
            batchId, reversed, links[0], links[1], links[2]);
        return batch;
    }

    /**
     * Phase 3 — reverse a whole run: every LOADED/FAILED batch in reverse dependency
     * order (expenses, gifts, then people). The run becomes ROLLED_BACK only once the
     * last live batch is reversed, so the status is never ahead of the data.
     */
    @Transactional
    public List<ImportBatch> rollbackRun(Long runId, String clientId, String actor) {
        runService.getRun(runId, clientId);
        List<ImportBatch> out = new ArrayList<>();
        List<String> reverse = new ArrayList<>(LOAD_ORDER); Collections.reverse(reverse);
        for (String table : reverse)
            for (ImportBatch b : batchRepo.findByRunIdAndTargetTable(runId, table))
                if ("LOADED".equals(b.getStatus()) || "FAILED".equals(b.getStatus())) out.add(rollbackBatch(b.getId(), clientId, actor));
        if (out.isEmpty()) throw new LoadException("Nothing to roll back — no loaded batches in this run");
        runService.recordAudit(runId, clientId, actor, "ROLLBACK_RUN", Map.of("batches", out.stream().map(ImportBatch::getId).toList()));
        return out;
    }

    /**
     * Phase 3 — the run's plan: one entry per target table in load order with its
     * staging counts, batch state and what can happen next. The UI's Step 4 is built
     * from this; nothing here writes.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> runPlan(Long runId, String clientId) {
        runService.getRun(runId, clientId);
        List<ImportBatch> batches = batchRepo.findByRunIdOrderByIdAsc(runId);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<String>> recordStatuses = new HashMap<>();
        if (recordService != null)
            for (StagingRecord r : recordService.runRows(runId)) recordStatuses.computeIfAbsent(r.getTargetTable(), k -> new ArrayList<>()).add(r.getRowStatus());
        for (String table : LOAD_ORDER) {
            List<String> statuses = switch (table) {
                case "family"  -> sFamilyRepo.findByRunId(runId).stream().map(StagingFamily::getRowStatus).toList();
                case "income"  -> sIncomeRepo.findByRunId(runId).stream().map(StagingIncome::getRowStatus).toList();
                case "expense" -> sExpenseRepo.findByRunId(runId).stream().map(StagingExpense::getRowStatus).toList();
                default        -> recordStatuses.getOrDefault(table, List.of());
            };
            // Phase 4: the core three always show; a record type shows once the run uses it.
            if (record(table) && statuses.isEmpty() && batches.stream().noneMatch(b -> table.equals(b.getTargetTable()))) continue;
            Map<String, Long> counts = new TreeMap<>();
            for (String s : statuses) counts.merge(String.valueOf(s), 1L, Long::sum);
            long review = counts.getOrDefault("REVIEW", 0L);
            long loadable = counts.getOrDefault("VALID", 0L) + counts.getOrDefault("WARN", 0L);
            List<Map<String, Object>> bs = new ArrayList<>();
            String batchState = null;
            for (ImportBatch b : batches) {
                if (!table.equals(b.getTargetTable())) continue;
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("id", b.getId()); bm.put("status", b.getStatus()); bm.put("inserted", b.getInsertedCount());
                bm.put("updated", b.getUpdatedCount()); bm.put("skipped", b.getSkippedCount()); bm.put("failed", b.getFailedCount());
                bm.put("errorMessage", b.getErrorMessage());
                bs.add(bm);
                if ("LOADED".equals(b.getStatus()) || "FAILED".equals(b.getStatus()) || "APPROVED".equals(b.getStatus())) batchState = b.getStatus();
                else if (batchState == null) batchState = b.getStatus();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetTable", table);
            m.put("order", out.size() + 1);   // position among the tables this run shows (load order)
            List<String> deps = DEPENDS.getOrDefault(table, List.of());
            m.put("dependsOn", deps.isEmpty() ? null : String.join(",", deps));
            m.put("label", com.churchgeniuspro.util.EtlTargetSchema.LABELS.getOrDefault(table, table));
            m.put("staged", statuses.size());
            m.put("counts", counts);
            m.put("review", review);
            m.put("loadable", loadable);
            m.put("canApprove", statuses.size() > 0 && review == 0 && loadable > 0);
            m.put("batches", bs);
            m.put("state", statuses.isEmpty() ? "NOT_STAGED" : (batchState != null ? batchState : (review > 0 ? "REVIEW" : "VALIDATED")));
            out.add(m);
        }
        return out;
    }

    private int rollbackFamily(ImportBatch batch, String tenant) {
        int n = 0;
        Set<Long> familyIds = new HashSet<>();
        for (StagingFamily r : sFamilyRepo.findByBatchId(batch.getId())) {
            if (!"LOADED".equals(r.getRowStatus()) || r.getTargetId() == null) continue;
            FamilyMember m = memberRepo.findById(r.getTargetId().intValue())
                .filter(x -> x.getFamily() != null && tenant.equals(x.getFamily().getAppClientId()))
                .orElse(null);
            if (m == null) { r.setRowStatus("ROLLED_BACK"); sFamilyRepo.save(r); continue; }
            if (r.getBeforeImage() != null) {     // was an UPDATE — restore
                restore(m, r.getBeforeImage());
                memberRepo.save(m);
            } else {                               // was an INSERT — soft-delete
                m.setDeleteFlag(true);
                memberRepo.save(m);
                if (r.getParentTargetId() != null) familyIds.add(r.getParentTargetId());
            }
            r.setRowStatus("ROLLED_BACK");
            sFamilyRepo.save(r);
            n++;
        }
        // Soft-delete the families we created (inserts only).
        for (Long fid : familyIds) {
            familyRepo.findById(fid.intValue())
                .filter(f -> tenant.equals(f.getAppClientId()))
                .ifPresent(f -> { f.setDeleteFlag(true); familyRepo.save(f); });
        }
        return n;
    }

    private int rollbackMoney(ImportBatch batch, String tenant, String table) {
        int n = 0;
        if ("income".equals(table)) {
            for (StagingIncome r : sIncomeRepo.findByBatchId(batch.getId())) {
                if (!"LOADED".equals(r.getRowStatus()) || r.getTargetId() == null) continue;
                incomeRepo.findById(r.getTargetId().intValue())
                    .filter(x -> tenant.equals(x.getAppClientId()))
                    .ifPresent(x -> {
                        // Financial audit M9: a LOADED row that was an UPDATE (carries a
                        // before-image) must be restored to what it was, never
                        // soft-deleted — the live row pre-dates this import and has
                        // nothing to do with it except having been overwritten.
                        if (r.getBeforeImage() != null) { restoreIncome(x, r.getBeforeImage(), tenant); incomeRepo.save(x); }
                        else { x.setDeleteFlag(true); incomeRepo.save(x); }
                    });
                r.setRowStatus("ROLLED_BACK"); sIncomeRepo.save(r); n++;
            }
        } else {
            for (StagingExpense r : sExpenseRepo.findByBatchId(batch.getId())) {
                if (!"LOADED".equals(r.getRowStatus()) || r.getTargetId() == null) continue;
                expenseRepo.findById(r.getTargetId().intValue())
                    .filter(x -> tenant.equals(x.getAppClientId()))
                    .ifPresent(x -> {
                        if (r.getBeforeImage() != null) { restoreExpense(x, r.getBeforeImage(), tenant); expenseRepo.save(x); }
                        else { x.setDeleteFlag(true); expenseRepo.save(x); }
                    });
                r.setRowStatus("ROLLED_BACK"); sExpenseRepo.save(r); n++;
            }
        }
        return n;
    }

    // ───────────────────────── helpers ─────────────────────────

    /** Resolve the action actually taken: an unflagged row inserts; a dup defaults SKIP. */
    /**
     * The plan's people limit applies to an import exactly as to the Family form:
     * only NEW people count (updates and skips do not), and if the batch would take
     * the church past its limit nothing is loaded — no partial import. Existing
     * people are never touched by this check. Fails open on a lookup error, as the
     * Family form does.
     */
    private void requireRoomForNewPeople(List<StagingFamily> rows, String tenant) {
        if (subscriptionService == null) return;
        int adding = (int) rows.stream()
            .filter(r -> "INSERT".equals(effectiveAction(r.getDedupeAction(), r.getDedupeMatchId())))
            .count();
        if (adding == 0) return;
        String limitMsg;
        try {
            Long current = memberRepo.countActiveMembers(tenant);
            limitMsg = subscriptionService.checkPeopleLimit(tenant, current == null ? 0 : current, adding);
        } catch (Exception e) {
            log.warn("ETL people-limit check failed for {} — allowing. {}", tenant, e.getMessage());
            return;
        }
        if (limitMsg != null) {
            throw new LoadException(limitMsg + " This import would add " + adding
                + " new " + (adding == 1 ? "person" : "people") + ", so nothing was loaded.");
        }
    }

    /**
     * Phase 2: the import reference for a new ledger row. When the admin chose
     * "import as new" for a row whose reference is already live (an exact or in-file
     * duplicate), the reference is suffixed with the staging row id so the H8 partial
     * unique index accepts it and the two rows stay distinguishable.
     */
    private String uniqueRef(String ref, Long stagingId, String tenant, boolean income) {
        if (ref == null || ref.isBlank()) return null;
        boolean taken = income
            ? incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(tenant, ref).isPresent()
            : expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(tenant, ref).isPresent();
        if (!taken) return ref;
        String out = ref + ":" + stagingId;
        return out.length() > 160 ? out.substring(0, 160) : out;
    }

    private String effectiveAction(String dedupeAction, Long matchId) {
        if ("SKIP".equals(dedupeAction)) return "SKIP";
        if ("UPDATE".equals(dedupeAction) && matchId != null) return "UPDATE";
        if ("MERGE".equals(dedupeAction) && matchId != null) return "MERGE";
        if ("INSERT".equals(dedupeAction)) return "INSERT";
        // No explicit action: a detected duplicate is SKIP by default; otherwise INSERT.
        return matchId != null ? "SKIP" : "INSERT";
    }

    private void copyFamilyFields(StagingFamily src, FamilyMember dst) {
        BeanWrapper s = new BeanWrapperImpl(src);
        BeanWrapper d = new BeanWrapperImpl(dst);
        for (String f : FAMILY_FIELDS) {
            Object v = s.getPropertyValue(f);
            if (v != null) d.setPropertyValue(f, v);
        }
    }

    /** Phase 2 MERGE: fill only the live fields that are empty; never overwrite a value the church already has. */
    private void mergeFamilyFields(StagingFamily src, FamilyMember dst) {
        BeanWrapper s = new BeanWrapperImpl(src);
        BeanWrapper d = new BeanWrapperImpl(dst);
        for (String f : FAMILY_FIELDS) {
            Object v = s.getPropertyValue(f);
            Object cur = d.getPropertyValue(f);
            boolean empty = cur == null || (cur instanceof String cs && cs.isBlank());
            if (v != null && empty) d.setPropertyValue(f, v);
        }
    }

    private String snapshot(FamilyMember m) {
        BeanWrapper w = new BeanWrapperImpl(m);
        Map<String, Object> snap = new LinkedHashMap<>();
        for (String f : FAMILY_FIELDS) snap.put(f, w.getPropertyValue(f));
        try { return MAPPER.writeValueAsString(snap); } catch (Exception e) { return null; }
    }

    private void restore(FamilyMember m, String beforeImage) {
        try {
            Map<String, Object> snap = MAPPER.readValue(beforeImage, new TypeReference<LinkedHashMap<String, Object>>() {});
            BeanWrapper w = new BeanWrapperImpl(m);
            for (String f : FAMILY_FIELDS) w.setPropertyValue(f, snap.get(f));
        } catch (Exception e) {
            log.warn("ETL restore failed for member {}: {}", m.getId(), e.getMessage());
        }
    }

    /** Map of resolvable family keys → live member id (staged inserts + existing members). */
    private Map<String, Integer> buildMemberIndex(Long runId, String tenant) {
        Map<String, Integer> idx = new HashMap<>();
        for (StagingFamily f : sFamilyRepo.findByRunId(runId)) {
            // Phase 3: a people row skipped as a duplicate still tells us who that person IS.
            Long live = f.getTargetId() != null ? f.getTargetId()
                      : ("SKIPPED".equals(f.getRowStatus()) ? f.getDedupeMatchId() : null);
            if (live == null) continue;
            // Phase 3: the person's own id first (a gift's "Person Id" is a person, not a household),
            // then the household key as before.
            if (f.getSourceRowId() != null && !f.getSourceRowId().isBlank()) idx.putIfAbsent(f.getSourceRowId().trim(), live.intValue());
            if (f.getFamilyKey() != null) idx.putIfAbsent(f.getFamilyKey().trim(), live.intValue());
        }
        String system = null;
        try { system = com.churchgeniuspro.util.SourcePreset.of(runService.getRun(runId, tenant).getSourcePreset()).name(); } catch (Exception ignore) { }
        Set<Integer> active = new HashSet<>();
        for (FamilyMember m : memberRepo.findActiveByTenantForEtl(tenant)) {
            if (m.getId() != null) { idx.putIfAbsent(String.valueOf(m.getId()), m.getId()); active.add(m.getId()); }
            if (m.getMemberRef() != null && !m.getMemberRef().isBlank())
                idx.putIfAbsent(m.getMemberRef().trim(), m.getId());
            // Phase 2: a gift's "Person Id" resolves to the member migrated from the same product.
            if (system != null && system.equals(m.getSourceSystem()) && m.getSourceId() != null && !m.getSourceId().isBlank())
                idx.putIfAbsent(m.getSourceId().trim(), m.getId());
        }
        // Phase 3: links learned by earlier runs (only to members still active).
        if (linkService != null && system != null)
            for (Map.Entry<String, Long> e : linkService.index(tenant, system, ImportLink.PERSON).entrySet())
                if (active.contains(e.getValue().intValue())) idx.putIfAbsent(e.getKey(), e.getValue().intValue());
        return idx;
    }

    private Integer resolveMember(String link, Map<String, Integer> index) {
        if (link == null || link.isBlank()) return null;
        return index.get(link.trim());
    }

    // ───────────────────────── reporting ─────────────────────────

    @Transactional(readOnly = true)
    public ImportBatch getBatch(Long batchId, String clientId) {
        return batchRepo.findByIdAndClientId(batchId, clientId)
            .orElseThrow(() -> new LoadException("Batch not found for tenant"));
    }

    @Transactional(readOnly = true)
    public List<ImportBatch> listBatches(Long runId) {
        return batchRepo.findByRunIdOrderByIdAsc(runId);
    }

    /** Per-row outcome for a batch (source row, action, live id, status). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> batchReport(Long batchId, String clientId) {
        ImportBatch batch = getBatch(batchId, clientId);
        List<Map<String, Object>> out = new ArrayList<>();
        switch (batch.getTargetTable()) {
            case "family" -> { for (StagingFamily r : sFamilyRepo.findByBatchId(batchId))
                out.add(reportRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getTargetId(),
                    r.getDedupeMatchId(), r.getValidationMsgs())); }
            case "income" -> { for (StagingIncome r : sIncomeRepo.findByBatchId(batchId))
                out.add(reportRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getTargetId(),
                    r.getDedupeMatchId(), r.getValidationMsgs())); }
            case "expense" -> { for (StagingExpense r : sExpenseRepo.findByBatchId(batchId))
                out.add(reportRow(r.getId(), r.getSourceRowId(), r.getRowStatus(), r.getTargetId(),
                    r.getDedupeMatchId(), r.getValidationMsgs())); }
            default -> { }
        }
        return out;
    }

    private Map<String, Object> reportRow(Long id, String sourceRowId, String status, Long targetId,
                                          Long dedupeMatchId, String msgs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stagingId", id);
        m.put("sourceRowId", sourceRowId);
        m.put("rowStatus", status);
        m.put("targetId", targetId);
        m.put("dedupeMatchId", dedupeMatchId);
        m.put("validationMsgs", msgs);
        return m;
    }
}
