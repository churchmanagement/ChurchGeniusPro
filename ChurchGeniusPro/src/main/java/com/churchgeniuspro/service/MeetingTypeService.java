package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.repository.MeetingTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for {@link MeetingType} CRUD operations.
 */
@Service
public class MeetingTypeService {

    private final MeetingTypeRepository repository;

    public MeetingTypeService(MeetingTypeRepository repository) {
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
    public MeetingType create(String typeName, String appClientId) {
        String name = typeName.trim();
        if (repository.existsByTypeNameAndClientId(name, appClientId)) {
            throw new IllegalArgumentException(
                    "A meeting type \"" + name + "\" already exists.");
        }
        MeetingType mt = new MeetingType();
        mt.setTypeName(name);
        mt.setAppClientId(appClientId);
        return repository.save(mt);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public MeetingType update(Integer id, String typeName) {
        String name = typeName.trim();
        MeetingType mt = findOrThrow(id);
        // Use the existing record's appClientId so the check stays within the same org
        if (repository.existsByTypeNameAndClientIdAndIdNot(name, mt.getAppClientId(), id)) {
            throw new IllegalArgumentException(
                    "A meeting type \"" + name + "\" already exists.");
        }
        mt.setTypeName(name);
        return repository.save(mt);
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id) {
        MeetingType mt = findOrThrow(id);
        mt.setDeleteFlag(true);
        repository.save(mt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toMap(MeetingType mt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       mt.getId());
        m.put("typeName", mt.getTypeName());
        return m;
    }

    private MeetingType findOrThrow(Integer id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Meeting type not found: " + id));
    }
}
