package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service layer for {@link MainSource} and {@link SubSource} CRUD operations.
 */
@Service
public class SourceService {

    private final MainSourceRepository mainRepo;
    private final SubSourceRepository  subRepo;

    public SourceService(MainSourceRepository mainRepo, SubSourceRepository subRepo) {
        this.mainRepo = mainRepo;
        this.subRepo  = subRepo;
    }

    // ── Main Source – List ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllMainSources(String appClientId) {
        return mainRepo.findActiveByAppUser(appClientId)
                .stream()
                .map(this::mainToMap)
                .collect(Collectors.toList());
    }

    // ── Main Source – Create ──────────────────────────────────────────────

    @Transactional
    public MainSource createMainSource(String sourceName, String appClientId) {
        String name = sourceName.trim();
        // Duplicate check is scoped to this church only (different churches may share names)
        if (mainRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(name, appClientId)) {
            throw new IllegalArgumentException("A source \"" + name + "\" already exists.");
        }
        MainSource ms = new MainSource();
        ms.setSourceName(name);
        ms.setAppClientId(appClientId);
        return mainRepo.save(ms);
    }

    // ── Main Source – Update ──────────────────────────────────────────────

    @Transactional
    public MainSource updateMainSource(Integer id, String sourceName, String appClientId) {
        String name = sourceName.trim();
        MainSource ms = findMainOrThrow(id, appClientId);
        // Duplicate check scoped to this church only
        if (mainRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientIdAndIdNot(
                name, appClientId, id)) {
            throw new IllegalArgumentException("A source \"" + name + "\" already exists.");
        }
        ms.setSourceName(name);
        return mainRepo.save(ms);
    }

    // ── Main Source – Soft-Delete (cascades to sub-sources) ───────────────

    @Transactional
    public void deleteMainSource(Integer id, String appClientId) {
        MainSource ms = findMainOrThrow(id, appClientId);
        subRepo.softDeleteByMainSource(ms);   // cascade sub-sources first
        ms.setDeleteFlag(true);
        mainRepo.save(ms);
    }

    // ── Sub Source – List ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSubSources(Integer mainSourceId, String appClientId) {
        findMainOrThrow(mainSourceId, appClientId);   // validates parent exists in this church
        return subRepo.findByMainSourceActiveByAppUser(mainSourceId, appClientId)
                .stream()
                .map(this::subToMap)
                .collect(Collectors.toList());
    }

    // ── Sub Source – Create ───────────────────────────────────────────────

    @Transactional
    public SubSource createSubSource(Integer mainSourceId, String sourceName, String appClientId) {
        String name = sourceName.trim();
        MainSource ms = findMainOrThrow(mainSourceId, appClientId);
        if (subRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_Id(name, mainSourceId)) {
            throw new IllegalArgumentException("A sub-source \"" + name + "\" already exists.");
        }
        SubSource ss = new SubSource();
        ss.setSourceName(name);
        ss.setMainSource(ms);
        ss.setAppClientId(appClientId);
        return subRepo.save(ss);
    }

    // ── Sub Source – Update ───────────────────────────────────────────────

    @Transactional
    public SubSource updateSubSource(Integer id, String sourceName, String appClientId) {
        String name = sourceName.trim();
        SubSource ss = findSubOrThrow(id, appClientId);
        Integer mainSourceId = ss.getMainSource().getId();
        if (subRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_IdAndIdNot(
                name, mainSourceId, id)) {
            throw new IllegalArgumentException("A sub-source \"" + name + "\" already exists.");
        }
        ss.setSourceName(name);
        return subRepo.save(ss);
    }

    // ── Sub Source – Soft-Delete ──────────────────────────────────────────

    @Transactional
    public void deleteSubSource(Integer id, String appClientId) {
        SubSource ss = findSubOrThrow(id, appClientId);
        ss.setDeleteFlag(true);
        subRepo.save(ss);
    }

    // ── Sub Source – Tax-deductible flag ───────────────────────────────────

    /**
     * Marks whether income filed under this sub-source counts as a
     * tax-deductible gift on the Year-End Tax Report and a member's own
     * giving statement. Financial audit H9.
     */
    @Transactional
    public SubSource setTaxDeductible(Integer id, boolean taxDeductible, String appClientId) {
        SubSource ss = findSubOrThrow(id, appClientId);
        ss.setTaxDeductible(taxDeductible);
        return subRepo.save(ss);
    }

    // ── Online-giving default category (find-or-create) ────────────────────

    private static final String ONLINE_GIVING_MAIN = "Online Giving";
    private static final String ONLINE_GIVING_SUB  = "Online Donations";

    /**
     * This church's "Online Giving" → "Online Donations" category, creating
     * it (once, per church) the first time it's needed. Lets an online
     * donation (Stripe giving, MidRegMeet giving) be filed into Income
     * without asking a bookkeeper to pre-create a category first — same
     * idea as the app auto-creating a member's first family record.
     * Financial audit H9.
     *
     * <p>There is no unique database constraint on a source's name (see
     * {@link #createMainSource}) — churches may legitimately want two
     * differently-cased or differently-spaced categories — so a genuine
     * concurrent first call from two requests could each try to create the
     * row. That's why both lookups below fall back to a re-query rather
     * than propagating the race as a failure: the loser of the race simply
     * uses the winner's row instead of its own.
     */
    @Transactional
    public SubSource findOrCreateOnlineDonationsSubSource(String appClientId) {
        MainSource main = findOrCreateMainByName(ONLINE_GIVING_MAIN, appClientId);
        return findOrCreateSubByName(main, ONLINE_GIVING_SUB, appClientId);
    }

    private MainSource findOrCreateMainByName(String name, String appClientId) {
        Optional<MainSource> existing =
                mainRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId);
        if (existing.isPresent()) return existing.get();
        try {
            return createMainSource(name, appClientId);
        } catch (RuntimeException raced) {
            return mainRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId)
                    .orElseThrow(() -> raced);
        }
    }

    private SubSource findOrCreateSubByName(MainSource main, String name, String appClientId) {
        Optional<SubSource> existing =
                subRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId);
        if (existing.isPresent()) return existing.get();
        try {
            return createSubSource(main.getId(), name, appClientId);
        } catch (RuntimeException raced) {
            return subRepo.findFirstBySourceNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId)
                    .orElseThrow(() -> raced);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> mainToMap(MainSource ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         ms.getId());
        m.put("sourceName", ms.getSourceName());
        return m;
    }

    private Map<String, Object> subToMap(SubSource ss) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             ss.getId());
        m.put("sourceName",     ss.getSourceName());
        m.put("mainSourceId",   ss.getMainSource().getId());
        m.put("taxDeductible",  ss.isTaxDeductible());
        return m;
    }

    // Tenant-scoped: another church's id is reported exactly like an unknown id.

    private MainSource findMainOrThrow(Integer id, String appClientId) {
        return mainRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Main source not found: " + id));
    }

    private SubSource findSubOrThrow(Integer id, String appClientId) {
        return subRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Sub-source not found: " + id));
    }
}
