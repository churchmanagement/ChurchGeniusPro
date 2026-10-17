package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.ImportLink;
import com.churchgeniuspro.hibernate.KmClassroom;
import com.churchgeniuspro.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * ETL Migration Phase 3 — the persistent resolver over {@code import_link}.
 *
 * <ul>
 *   <li>{@link #record} learns "source product id → our id" when a row is inserted,
 *       updated or merged (and which run/batch learned it).</li>
 *   <li>{@link #index} hands a loader/validator every known link of one entity for a
 *       tenant + source system in one query, so a giving file's "Person 1042" resolves
 *       to the member a previous run imported without scanning members.</li>
 *   <li>{@link #recordReferenceRows} notes the funds / purposes / payment methods a load
 *       had to create; {@link #rollbackBatch} deletes the batch's links and soft-deletes
 *       those reference rows again when nothing live uses them any more — which is what
 *       lets a rollback claim, truthfully, that it left nothing behind.</li>
 * </ul>
 *
 * <p>All lookups and writes are tenant-scoped. Every repository is optional so the
 * hand-built unit tests of older phases keep working (a missing repository simply
 * means "no links").
 */
@Service
public class EtlLinkService {

    private static final Logger log = LoggerFactory.getLogger(EtlLinkService.class);

    private final ImportLinkRepository      links;
    private final SubSourceRepository       subSourceRepo;
    private final MainSourceRepository      mainSourceRepo;
    private final PurposeRepository         purposeRepo;
    private final TransactionTypeRepository txTypeRepo;
    private final IncomeRepository          incomeRepo;
    private final ExpenseRepository         expenseRepo;

    @org.springframework.beans.factory.annotation.Autowired
    public EtlLinkService(ImportLinkRepository links, SubSourceRepository subSourceRepo, MainSourceRepository mainSourceRepo,
                          PurposeRepository purposeRepo, TransactionTypeRepository txTypeRepo,
                          IncomeRepository incomeRepo, ExpenseRepository expenseRepo) {
        this.links = links; this.subSourceRepo = subSourceRepo; this.mainSourceRepo = mainSourceRepo;
        this.purposeRepo = purposeRepo; this.txTypeRepo = txTypeRepo; this.incomeRepo = incomeRepo; this.expenseRepo = expenseRepo;
    }

    /** Phase 4: live lookups for groups / classrooms / campaigns (optional; absent = those references are kept). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtlLiveLookup lookup;
    public void setLookup(EtlLiveLookup l) { this.lookup = l; }

    /** Links only (no reference-row cleanup) — for tests and callers without the finance repositories. */
    public EtlLinkService(ImportLinkRepository links) {
        this(links, null, null, null, null, null, null);
    }

    private static String key(String k) { return k == null ? null : k.trim(); }

    /** The live id a source key resolved to in an earlier import, if any. */
    @Transactional(readOnly = true)
    public Optional<Long> resolve(String tenant, String system, String entity, String sourceKey) {
        String k = key(sourceKey);
        if (links == null || k == null || k.isEmpty()) return Optional.empty();
        return links.findByClientIdAndSourceSystemAndEntityAndSourceKey(tenant, system, entity, k).map(ImportLink::getTargetId);
    }

    /** Every link of one entity for a tenant + source system: source key → live id. */
    @Transactional(readOnly = true)
    public Map<String, Long> index(String tenant, String system, String entity) {
        Map<String, Long> out = new HashMap<>();
        if (links == null || system == null) return out;
        for (ImportLink l : links.findByClientIdAndSourceSystemAndEntity(tenant, system, entity)) out.put(l.getSourceKey(), l.getTargetId());
        return out;
    }

    /**
     * Learn (or re-learn) a link. The admin's decision on the row that produced it is
     * authoritative, so a later import pointing the same key at another live row wins.
     */
    @Transactional
    public void record(String tenant, String system, String entity, String sourceKey, Long targetId, Long runId, Long batchId) {
        String k = key(sourceKey);
        if (links == null || k == null || k.isEmpty() || targetId == null || system == null) return;
        if (k.length() > 160) k = k.substring(0, 160);
        final String fk = k;
        ImportLink l = links.findByClientIdAndSourceSystemAndEntityAndSourceKey(tenant, system, entity, fk).orElseGet(() -> {
            ImportLink n = new ImportLink();
            n.setClientId(tenant); n.setSourceSystem(system); n.setEntity(entity); n.setSourceKey(fk);
            return n;
        });
        if (l.getId() != null && targetId.equals(l.getTargetId())) return;
        l.setTargetId(targetId); l.setRunId(runId); l.setBatchId(batchId);
        links.save(l);
    }

    /** Record the reference rows a resolver session created during a batch, then forget them in the session. */
    @Transactional
    public void recordReferenceRows(String tenant, String system, Long runId, Long batchId, EtlReferenceResolver.Session session) {
        if (session == null) return;
        for (Object[] c : session.created()) {
            Object id = c[2];
            record(tenant, system, (String) c[0], (String) c[1], id == null ? null : ((Number) id).longValue(), runId, batchId);
        }
        session.clearCreated();
    }

    /** Links created/learned by a batch (for the rollback report). */
    @Transactional(readOnly = true)
    public List<ImportLink> linksOfBatch(Long batchId) {
        return links == null ? List.of() : links.findByBatchId(batchId);
    }

    /**
     * Undo what a batch taught the resolver: delete its links and soft-delete the
     * reference rows it created that no live income/expense (or live fund) still uses.
     * Reference rows that ARE still used — the church has posted to that fund since —
     * are kept and reported, never deleted from under live data.
     *
     * @return {@code {linksDeleted, referenceRowsRemoved, referenceRowsKept}}
     */
    @Transactional
    public int[] rollbackBatch(String tenant, Long batchId) {
        if (links == null) return new int[]{0, 0, 0};
        List<ImportLink> all = links.findByBatchId(batchId).stream().filter(l -> tenant.equals(l.getClientId())).toList();
        int removed = 0, kept = 0;
        // sub_source before main_source: a main source is only free once its funds are gone.
        for (String entity : List.of(ImportLink.REF_SUB_SOURCE, ImportLink.REF_PURPOSE, ImportLink.REF_TRANSACTION_TYPE, ImportLink.REF_MAIN_SOURCE,
                                     ImportLink.REF_GROUP, ImportLink.REF_KM_CLASSROOM)) {
            for (ImportLink l : all) {
                if (!entity.equals(l.getEntity())) continue;
                if (removeIfUnused(tenant, entity, l.getTargetId().intValue())) removed++; else kept++;
            }
        }
        for (ImportLink l : all) links.delete(l);
        log.info("ETL batch {} rollback: {} links deleted, {} reference rows removed, {} kept (still in use)", batchId, all.size(), removed, kept);
        return new int[]{all.size(), removed, kept};
    }

    private boolean removeIfUnused(String tenant, String entity, Integer id) {
        try {
            switch (entity) {
                case ImportLink.REF_SUB_SOURCE -> {
                    if (subSourceRepo == null || incomeRepo == null) return false;
                    var s = subSourceRepo.findById(id).filter(x -> tenant.equals(x.getAppClientId()) && !x.isDeleteFlag()).orElse(null);
                    if (s == null) return false;
                    if (incomeRepo.countBySubSource_IdAndDeleteFlagFalse(id) > 0) return false;
                    if (lookup != null && lookup.dependents("sub_source", id.longValue(), tenant) > 0) return false;   // Phase 4: a live campaign uses it
                    s.setDeleteFlag(true); subSourceRepo.save(s); return true;
                }
                case ImportLink.REF_MAIN_SOURCE -> {
                    if (mainSourceRepo == null || expenseRepo == null || subSourceRepo == null) return false;
                    var m = mainSourceRepo.findById(id).filter(x -> tenant.equals(x.getAppClientId()) && !x.isDeleteFlag()).orElse(null);
                    if (m == null) return false;
                    if (expenseRepo.countByMainSource_IdAndDeleteFlagFalse(id) > 0 || subSourceRepo.countByMainSource_IdAndDeleteFlagFalse(id) > 0) return false;
                    m.setDeleteFlag(true); mainSourceRepo.save(m); return true;
                }
                case ImportLink.REF_PURPOSE -> {
                    if (purposeRepo == null || expenseRepo == null) return false;
                    var p = purposeRepo.findById(id).filter(x -> tenant.equals(x.getAppClientId()) && !x.isDeleteFlag()).orElse(null);
                    if (p == null) return false;
                    if (expenseRepo.countByPurpose_IdAndDeleteFlagFalse(id) > 0) return false;
                    p.setDeleteFlag(true); purposeRepo.save(p); return true;
                }
                case ImportLink.REF_TRANSACTION_TYPE -> {
                    if (txTypeRepo == null || incomeRepo == null || expenseRepo == null) return false;
                    var t = txTypeRepo.findById(id).filter(x -> tenant.equals(x.getAppClientId()) && !x.isDeleteFlag()).orElse(null);
                    if (t == null) return false;
                    if (incomeRepo.countByTransactionType_IdAndDeleteFlagFalse(id) > 0 || expenseRepo.countByTransactionType_IdAndDeleteFlagFalse(id) > 0) return false;
                    t.setDeleteFlag(true); txTypeRepo.save(t); return true;
                }
                case ImportLink.REF_GROUP -> {
                    if (lookup == null) return false;
                    Group g = lookup.<Group>one("group", id.longValue(), tenant).orElse(null);
                    if (g == null || g.isDeleteFlag() || lookup.dependents("group", id.longValue(), tenant) > 0) return false;
                    g.setDeleteFlag(true); lookup.save(g); return true;
                }
                case ImportLink.REF_KM_CLASSROOM -> {
                    if (lookup == null) return false;
                    KmClassroom k = lookup.<KmClassroom>one("km_classroom", id.longValue(), tenant).orElse(null);
                    if (k == null || k.isDeleteFlag() || lookup.dependents("km_classroom", id.longValue(), tenant) > 0) return false;
                    k.setDeleteFlag(true); lookup.save(k); return true;
                }
                default -> { return false; }
            }
        } catch (Exception e) {
            log.warn("ETL reference-row cleanup skipped for {} #{}: {}", entity, id, e.getMessage());
            return false;
        }
    }
}
