package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.repository.GroupMemberRepository;
import com.churchgeniuspro.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for {@link Group} CRUD operations.
 */
@Service
public class GroupService {

    private final GroupRepository       repository;
    private final GroupMemberRepository memberRepository;

    public GroupService(GroupRepository repository, GroupMemberRepository memberRepository) {
        this.repository       = repository;
        this.memberRepository = memberRepository;
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
    public Group create(String groupName, String appClientId) {
        String name = groupName.trim();
        if (repository.existsByGroupNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(name, appClientId)) {
            throw new IllegalArgumentException(
                    "A group \"" + name + "\" already exists.");
        }
        Group g = new Group();
        g.setGroupName(name);
        g.setAppClientId(appClientId);
        return repository.save(g);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public Group update(Integer id, String groupName) {
        String name = groupName.trim();
        Group g = findOrThrow(id);
        if (repository.existsByGroupNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(
                name, g.getAppClientId(), id)) {
            throw new IllegalArgumentException(
                    "A group \"" + name + "\" already exists.");
        }
        g.setGroupName(name);
        return repository.save(g);
    }

    // ── Soft-Delete (cascades to members) ─────────────────────────────────

    @Transactional
    public void delete(Integer id) {
        Group g = findOrThrow(id);
        memberRepository.softDeleteByGroup(g);   // cascade all members first
        g.setDeleteFlag(true);
        repository.save(g);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Returns the Group entity (used by GroupMemberService to validate parent). */
    public Group findOrThrow(Integer id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Group not found: " + id));
    }

    private Map<String, Object> toMap(Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          g.getId());
        m.put("groupName",   g.getGroupName());
        m.put("memberCount", memberRepository.countByGroup_IdAndDeleteFlagFalse(g.getId()));
        return m;
    }
}
