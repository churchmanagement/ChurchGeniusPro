package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for {@link TransactionType} CRUD operations.
 */
@Service
public class TransactionTypeService {

    private final TransactionTypeRepository repo;

    public TransactionTypeService(TransactionTypeRepository repo) {
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
    public TransactionType create(String typeName, String appClientId) {
        String name = typeName.trim();
        if (repo.existsByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId)) {
            throw new IllegalArgumentException("A transaction type \"" + name + "\" already exists.");
        }
        TransactionType tt = new TransactionType();
        tt.setTypeName(name);
        tt.setAppClientId(appClientId);
        return repo.save(tt);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public TransactionType update(Integer id, String typeName, String appClientId) {
        String name = typeName.trim();
        TransactionType tt = findOrThrow(id, appClientId);
        // Scope duplicate-check to the same client as the record being updated
        if (repo.existsByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(name, tt.getAppClientId(), id)) {
            throw new IllegalArgumentException("A transaction type \"" + name + "\" already exists.");
        }
        tt.setTypeName(name);
        return repo.save(tt);
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id, String appClientId) {
        TransactionType tt = findOrThrow(id, appClientId);
        tt.setDeleteFlag(true);
        repo.save(tt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toMap(TransactionType tt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       tt.getId());
        m.put("typeName", tt.getTypeName());
        return m;
    }

    /** Tenant-scoped: another church's id is reported exactly like an unknown id. */
    private TransactionType findOrThrow(Integer id, String appClientId) {
        return repo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction type not found: " + id));
    }
}
