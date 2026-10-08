package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.GroupMemberRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.util.MemberNameUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for {@link GroupMember} CRUD operations.
 */
@Service
public class GroupMemberService {

    private final GroupMemberRepository repo;
    private final GroupService          groupService;
    private final FamilyMemberRepository familyMemberRepo;

    public GroupMemberService(GroupMemberRepository repo, GroupService groupService,
                              FamilyMemberRepository familyMemberRepo) {
        this.repo             = repo;
        this.groupService     = groupService;
        this.familyMemberRepo = familyMemberRepo;
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMembers(Integer groupId, String appClientId) {
        groupService.findOrThrow(groupId, appClientId);   // validate group exists in this tenant
        return repo.findByGroupActiveByAppUser(groupId, appClientId)
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public GroupMember addMember(Integer groupId, String firstName, String lastName,
                                  String email, String appClientId) {
        Group group = groupService.findOrThrow(groupId, appClientId);
        // Email is optional — normalise when provided but do NOT dedup by email,
        // because multiple family members may share the same email address.
        String em = (email != null && !email.isBlank()) ? email.trim().toLowerCase() : "";
        GroupMember m = new GroupMember();
        m.setGroup(group);
        m.setFirstName(firstName.trim());
        m.setLastName(lastName.trim());
        m.setEmail(em);
        m.setAppClientId(appClientId);
        return repo.save(m);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public GroupMember updateMember(Integer id, String firstName, String lastName, String email,
                                    String appClientId) {
        GroupMember m = findOrThrow(id, appClientId);
        // Email is optional — normalise when provided but do NOT dedup by email,
        // because multiple family members may share the same email address.
        String em = (email != null && !email.isBlank()) ? email.trim().toLowerCase() : "";
        m.setFirstName(firstName.trim());
        m.setLastName(lastName.trim());
        m.setEmail(em);
        return repo.save(m);
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void removeMember(Integer id, String appClientId) {
        GroupMember m = findOrThrow(id, appClientId);
        m.setDeleteFlag(true);
        repo.save(m);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toMap(GroupMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",        m.getId());
        map.put("firstName", m.getFirstName());
        map.put("lastName",  m.getLastName());
        map.put("email",     m.getEmail());
        // Resolve the member's nickname (otherName) from the family directory by
        // email, so Groups can show "First Last (Nickname)" like everywhere else.
        String nickname = resolveNickname(m.getEmail(), m.getAppClientId());
        map.put("nickname",    nickname);
        map.put("displayName", MemberNameUtil.display(m.getFirstName(), m.getLastName(), nickname));
        map.put("groupId",   m.getGroup().getId());
        return map;
    }

    /** Best-effort nickname lookup for a group member by email within the org. */
    private String resolveNickname(String email, String appClientId) {
        if (email == null || email.isBlank() || appClientId == null) return null;
        try {
            return familyMemberRepo.findByPhoneOrEmailAndClient(email.trim(), appClientId)
                    .stream()
                    .map(FamilyMember::getOtherName)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Tenant-scoped; an id owned by another tenant reads as "not found". */
    public GroupMember findOrThrow(Integer id, String appClientId) {
        if (appClientId == null) throw new IllegalArgumentException("Group member not found: " + id);
        return repo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Group member not found: " + id));
    }
}
