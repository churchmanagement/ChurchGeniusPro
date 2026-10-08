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
        switch (targetTable) {
            case "family" -> {
                StagingFamily r = sFamilyRepo.findById(rowId)
                    .filter(x -> x.getClientId().equals(clientId))
                    .orElseThrow(() -> new LoadException("Row not found for tenant"));
                if (dedupeAction != null) r.setDedupeAction(normAction(dedupeAction));
                if (rowStatus != null) r.setRowStatus(rowStatus.trim().toUpperCase());
                sFamilyRepo.save(r);
            }
            case "income" -> {
                StagingIncome r = sIncomeRepo.findById(rowId)
                    .filter(x -> x.getClientId().equals(clientId))
                    .orElseThrow(() -> new LoadException("Row not found for tenant"));
                if (dedupeAction != null) r.setDedupeAction(normAction(dedupeAction));
                if (rowStatus != null) r.setRowStatus(rowStatus.trim().toUpperCase());
                sIncomeRepo.save(r);
            }
            case "expense" -> {
                StagingExpense r = sExpenseRepo.findById(rowId)
                    .filter(x -> x.getClientId().equals(clientId))
                    .orElseThrow(() -> new LoadException("Row not found for tenant"));
                if (dedupeAction != null) r.setDedupeAction(normAction(dedupeAction));
                if (rowStatus != null) r.setRowStatus(rowStatus.trim().toUpperCase());
                sExpenseRepo.save(r);
            }
            default -> throw new LoadException("Unknown target table: " + targetTable);
        }
    }

    private String normAction(String a) {
        String s = a.trim().toUpperCase();
        if (!Set.of("INSERT", "UPDATE", "SKIP").contains(s))
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

    /** Mark loadable rows APPROVED (skip ERROR/REJECTED/already-loaded). Returns count. */
    private int tagLoadable(Long runId, String table) {
        int n = 0;
        switch (table) {
            case "family" -> { for (StagingFamily r : sFamilyRepo.findByRunId(runId))
                if (loadable(r.getRowStatus())) { r.setRowStatus("APPROVED"); sFamilyRepo.save(r); n++; } }
            case "income" -> { for (StagingIncome r : sIncomeRepo.findByRunId(runId))
                if (loadable(r.getRowStatus())) { r.setRowStatus("APPROVED"); sIncomeRepo.save(r); n++; } }
            case "expense" -> { for (StagingExpense r : sExpenseRepo.findByRunId(runId))
                if (loadable(r.getRowStatus())) { r.setRowStatus("APPROVED"); sExpenseRepo.save(r); n++; } }
            default -> throw new LoadException("Unknown target table: " + table);
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
            default -> { }
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
        if (!"APPROVED".equals(batch.getStatus()))
            throw new LoadException("Batch is not APPROVED (is " + batch.getStatus() + ")");

        final Long bid = batch.getId();
        int[] c = switch (batch.getTargetTable()) {
            case "family"  -> loadFamilyChunked(bid, clientId, actor);
            case "income"  -> loadIncomeChunked(batch.getRunId(), bid, clientId, actor);
            case "expense" -> loadExpenseChunked(bid, clientId, actor);
            default -> throw new LoadException("Unknown target table: " + batch.getTargetTable());
        };

        final int[] cc = c;
        ImportBatch updated = tx.execute(st -> {
            ImportBatch b = batchRepo.findById(bid).orElseThrow(() -> new LoadException("Batch vanished"));
            b.setInsertedCount(cc[0]); b.setUpdatedCount(cc[1]);
            b.setSkippedCount(cc[2]);  b.setFailedCount(cc[3]);
            b.setStatus("LOADED");
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

    private void add(int[] total, int[] c) { for (int i = 0; i < 4; i++) total[i] += c[i]; }

    // —— family (households kept whole within a chunk) ——
    private int[] loadFamilyChunked(Long batchId, String tenant, String actor) {
        List<StagingFamily> rows = sFamilyRepo.findByBatchId(batchId).stream()
            .filter(r -> "APPROVED".equals(r.getRowStatus())).toList();
        requireRoomForNewPeople(rows, tenant);
        LinkedHashMap<String, List<Long>> groups = new LinkedHashMap<>();
        for (StagingFamily r : rows) {
            String key = r.getFamilyKey() == null ? ("row:" + r.getId()) : r.getFamilyKey();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getId());
        }
        List<List<Long>> chunks = new ArrayList<>();
        List<Long> cur = new ArrayList<>();
        for (List<Long> grp : groups.values()) {
            if (!cur.isEmpty() && cur.size() + grp.size() > chunkSize()) { chunks.add(cur); cur = new ArrayList<>(); }
            cur.addAll(grp);
        }
        if (!cur.isEmpty()) chunks.add(cur);

        int[] total = {0, 0, 0, 0};
        for (List<Long> ids : chunks)
            add(total, tx.execute(st -> loadFamilyChunk(ids, tenant, actor)));
        return total;
    }

    private int[] loadFamilyChunk(List<Long> ids, String tenant, String actor) {
        int inserted = 0, updated = 0, skipped = 0, failed = 0;
        Map<String, Family> familyByKey = new HashMap<>();
        for (StagingFamily r : sFamilyRepo.findAllById(ids)) {
            if (!"APPROVED".equals(r.getRowStatus())) continue;
            String action = effectiveAction(r.getDedupeAction(), r.getDedupeMatchId());
            try {
                if ("SKIP".equals(action)) { r.setRowStatus("SKIPPED"); skipped++; }
                else if ("UPDATE".equals(action)) {
                    FamilyMember m = memberRepo.findById(r.getDedupeMatchId().intValue())
                        .filter(x -> x.getFamily() != null && tenant.equals(x.getFamily().getAppClientId()))
                        .orElse(null);
                    if (m == null) { r.setRowStatus("FAILED"); failed++; }
                    else {
                        r.setBeforeImage(snapshot(m)); copyFamilyFields(r, m);
                        memberRepo.save(m);
                        r.setTargetId(m.getId().longValue()); r.setRowStatus("LOADED"); updated++;
                    }
                } else { // INSERT
                    String key = r.getFamilyKey() == null ? ("row:" + r.getId()) : r.getFamilyKey();
                    Family fam = familyByKey.computeIfAbsent(key, k -> {
                        Family f = new Family();
                        f.setAppClientId(tenant); f.setInactive(false);
                        f.setDeleteFlag(false); f.setCreatedDate(new Date());
                        return familyRepo.save(f);
                    });
                    FamilyMember m = new FamilyMember();
                    m.setFamily(fam); copyFamilyFields(r, m);
                    m.setAppClientId(tenant); m.setDeleteFlag(false);
                    m.setCreatedDate(new Date());
                    m = memberRepo.save(m);
                    r.setTargetId(m.getId().longValue()); r.setParentTargetId(fam.getId().longValue());
                    r.setRowStatus("LOADED"); inserted++;
                }
            } catch (Exception e) {
                r.setRowStatus("FAILED"); failed++;
                log.warn("ETL family row {} load failed: {}", r.getId(), e.getMessage());
            }
            sFamilyRepo.save(r);
        }
        return new int[]{inserted, updated, skipped, failed};
    }

    // —— income ——
    private int[] loadIncomeChunked(Long runId, Long batchId, String tenant, String actor) {
        List<Long> ids = sIncomeRepo.findByBatchId(batchId).stream()
            .filter(r -> "APPROVED".equals(r.getRowStatus())).map(StagingIncome::getId).toList();
        EtlReferenceResolver.Session ref = refResolver.session(tenant);
        Map<String, Integer> memberIndex = buildMemberIndex(runId, tenant);
        int[] total = {0, 0, 0, 0};
        for (int i = 0; i < ids.size(); i += chunkSize()) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize(), ids.size()));
            add(total, tx.execute(st -> loadIncomeChunk(chunk, tenant, actor, ref, memberIndex)));
        }
        return total;
    }

    private int[] loadIncomeChunk(List<Long> ids, String tenant, String actor,
                                  EtlReferenceResolver.Session ref, Map<String, Integer> memberIndex) {
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
                    }
                } else { // INSERT
                    Income e = new Income();
                    copyIncomeFields(r, e, ref, memberIndex);
                    e.setAppClientId(tenant); e.setDeleteFlag(false); e.setQuickAdd(false);
                    e.setCreatedDate(new Date()); e.setCreatedBy(actor);
                    e = incomeRepo.save(e);
                    r.setTargetId(e.getId().longValue()); r.setRowStatus("LOADED"); inserted++;
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
        Integer mid = resolveMember(r.getFamilyLink(), memberIndex);
        if (mid != null) {
            e.setMember(memberRepo.findById(mid).orElse(null));
            r.setResolvedMemberId(mid.longValue());
        }
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
        } catch (Exception ex) {
            log.warn("ETL restore failed for income {}: {}", e.getId(), ex.getMessage());
        }
    }

    // —— expense ——
    private int[] loadExpenseChunked(Long batchId, String tenant, String actor) {
        List<Long> ids = sExpenseRepo.findByBatchId(batchId).stream()
            .filter(r -> "APPROVED".equals(r.getRowStatus())).map(StagingExpense::getId).toList();
        EtlReferenceResolver.Session ref = refResolver.session(tenant);
        int[] total = {0, 0, 0, 0};
        for (int i = 0; i < ids.size(); i += chunkSize()) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize(), ids.size()));
            add(total, tx.execute(st -> loadExpenseChunk(chunk, tenant, actor, ref)));
        }
        return total;
    }

    private int[] loadExpenseChunk(List<Long> ids, String tenant, String actor, EtlReferenceResolver.Session ref) {
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
                    }
                } else { // INSERT
                    Expense e = new Expense();
                    copyExpenseFields(r, e, ref);
                    e.setAppClientId(tenant); e.setDeleteFlag(false); e.setQuickAdd(false);
                    e.setCreatedDate(new Date()); e.setCreatedBy(actor);
                    e = expenseRepo.save(e);
                    r.setTargetId(e.getId().longValue()); r.setRowStatus("LOADED"); inserted++;
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
        if (r.getNotes() != null) e.setNote(r.getNotes());
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
        if (!"LOADED".equals(batch.getStatus()))
            throw new LoadException("Only a LOADED batch can be rolled back (is " + batch.getStatus() + ")");

        int reversed = switch (batch.getTargetTable()) {
            case "family"  -> rollbackFamily(batch, clientId);
            case "income"  -> rollbackMoney(batch, clientId, "income");
            case "expense" -> rollbackMoney(batch, clientId, "expense");
            default -> throw new LoadException("Unknown target table: " + batch.getTargetTable());
        };
        batch.setStatus("ROLLED_BACK");
        batch = batchRepo.save(batch);

        ImportRun run = runService.getRun(batch.getRunId(), clientId);
        if (ImportRunStatus.fromString(run.getStatus()) == ImportRunStatus.LOADED)
            runService.transition(batch.getRunId(), clientId, actor, ImportRunStatus.ROLLED_BACK);
        runService.recordAudit(batch.getRunId(), clientId, actor, "ROLLBACK", Map.of(
            "batchId", batchId, "targetTable", batch.getTargetTable(), "reversed", reversed));
        log.info("ETL batch {} rolled back: {} live rows reversed", batchId, reversed);
        return batch;
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

    private String effectiveAction(String dedupeAction, Long matchId) {
        if ("SKIP".equals(dedupeAction)) return "SKIP";
        if ("UPDATE".equals(dedupeAction) && matchId != null) return "UPDATE";
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
            if (f.getTargetId() != null && f.getFamilyKey() != null)
                idx.putIfAbsent(f.getFamilyKey().trim(), f.getTargetId().intValue());
        }
        for (FamilyMember m : memberRepo.findActiveByTenantForEtl(tenant)) {
            if (m.getId() != null) idx.putIfAbsent(String.valueOf(m.getId()), m.getId());
            if (m.getMemberRef() != null && !m.getMemberRef().isBlank())
                idx.putIfAbsent(m.getMemberRef().trim(), m.getId());
        }
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
