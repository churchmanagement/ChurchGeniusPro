package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.MemberType;
import com.churchgeniuspro.repository.MemberTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for {@link MemberType} CRUD operations.
 */
@Service
public class MemberTypeService {

    private final MemberTypeRepository repository;

    public MemberTypeService(MemberTypeRepository repository) {
        this.repository = repository;
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAll(String appClientId) {
        return repository.findActiveByAppUser(appClientId)
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public MemberType create(String typeName, String appClientId) {
        String name = typeName.trim();
        if (repository.existsByTypeNameAndClientId(name, appClientId)) {
            throw new IllegalArgumentException(
                    "A member type \"" + name + "\" already exists.");
        }
        MemberType mt = new MemberType();
        mt.setTypeName(name);
        mt.setAppClientId(appClientId);
        return repository.save(mt);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public MemberType update(Integer id, String typeName, String appClientId) {
        String name = typeName.trim();
        MemberType mt = findOrThrow(id, appClientId);
        // Use the existing record's appClientId so the check stays within the same org
        if (repository.existsByTypeNameAndClientIdAndIdNot(name, mt.getAppClientId(), id)) {
            throw new IllegalArgumentException(
                    "A member type \"" + name + "\" already exists.");
        }
        mt.setTypeName(name);
        return repository.save(mt);
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id, String appClientId) {
        MemberType mt = findOrThrow(id, appClientId);
        mt.setDeleteFlag(true);
        repository.save(mt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toMap(MemberType mt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       mt.getId());
        m.put("typeName", mt.getTypeName());
        return m;
    }

    /** Tenant-scoped; an id owned by another tenant reads as "not found". */
    private MemberType findOrThrow(Integer id, String appClientId) {
        if (appClientId == null) throw new IllegalArgumentException("Member type not found: " + id);
        return repository.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Member type not found: " + id));
    }
}
