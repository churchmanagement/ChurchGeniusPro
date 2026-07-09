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
        MainSource ms = findMainOrThrow(id);
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
    public void deleteMainSource(Integer id) {
        MainSource ms = findMainOrThrow(id);
        subRepo.softDeleteByMainSource(ms);   // cascade sub-sources first
        ms.setDeleteFlag(true);
        mainRepo.save(ms);
    }

    // ── Sub Source – List ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSubSources(Integer mainSourceId, String appClientId) {
        findMainOrThrow(mainSourceId);        // validates parent exists
        return subRepo.findByMainSourceActiveByAppUser(mainSourceId, appClientId)
                .stream()
                .map(this::subToMap)
                .collect(Collectors.toList());
    }

    // ── Sub Source – Create ───────────────────────────────────────────────

    @Transactional
    public SubSource createSubSource(Integer mainSourceId, String sourceName, String appClientId) {
        String name = sourceName.trim();
        MainSource ms = findMainOrThrow(mainSourceId);
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
    public SubSource updateSubSource(Integer id, String sourceName) {
        String name = sourceName.trim();
        SubSource ss = findSubOrThrow(id);
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
    public void deleteSubSource(Integer id) {
        SubSource ss = findSubOrThrow(id);
        ss.setDeleteFlag(true);
        subRepo.save(ss);
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
        m.put("id",           ss.getId());
        m.put("sourceName",   ss.getSourceName());
        m.put("mainSourceId", ss.getMainSource().getId());
        return m;
    }

    private MainSource findMainOrThrow(Integer id) {
        return mainRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Main source not found: " + id));
    }

    private SubSource findSubOrThrow(Integer id) {
        return subRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sub-source not found: " + id));
    }
}
