package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.repository.PurposeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for {@link Purpose} CRUD operations.
 */
@Service
public class PurposeService {

    private final PurposeRepository repo;

    public PurposeService(PurposeRepository repo) {
        this.repo = repo;
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAll(String appClientId) {
        return repo.findActiveByAppUser(appClientId)
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public Purpose create(String purposeName, String appClientId) {
        String name = purposeName.trim();
        // Duplicate check scoped to this church only
        if (repo.existsByPurposeNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(name, appClientId)) {
            throw new IllegalArgumentException("A purpose \"" + name + "\" already exists.");
        }
        Purpose p = new Purpose();
        p.setPurposeName(name);
        p.setAppClientId(appClientId);
        return repo.save(p);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public Purpose update(Integer id, String purposeName, String appClientId) {
        String name = purposeName.trim();
        Purpose p = findOrThrow(id, appClientId);
        // Duplicate check scoped to this church only
        if (repo.existsByPurposeNameIgnoreCaseAndDeleteFlagFalseAndAppClientIdAndIdNot(
                name, appClientId, id)) {
            throw new IllegalArgumentException("A purpose \"" + name + "\" already exists.");
        }
        p.setPurposeName(name);
        return repo.save(p);
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id, String appClientId) {
        Purpose p = findOrThrow(id, appClientId);
        p.setDeleteFlag(true);
        repo.save(p);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toMap(Purpose p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          p.getId());
        m.put("purposeName", p.getPurposeName());
        return m;
    }

    /** Tenant-scoped: another church's id is reported exactly like an unknown id. */
    private Purpose findOrThrow(Integer id, String appClientId) {
        return repo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Purpose not found: " + id));
    }
}
