package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SongBookAccess;
import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.OffsetWindow;
import com.churchgeniuspro.repository.SongBookAccessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves and manages per-member Song Book access levels (NONE / VIEW / FULL).
 * Members with no row default to NONE (no access until granted).
 */
@Service
public class SongBookAccessService {

    /** Largest page the Song Book Access list will serve in one request. */
    public static final int MAX_PAGE_SIZE = 100;

    private final SongBookAccessRepository repo;
    private final FamilyMemberRepository memberRepo;

    public SongBookAccessService(SongBookAccessRepository repo, FamilyMemberRepository memberRepo) {
        this.repo = repo;
        this.memberRepo = memberRepo;
    }

    /**
     * One page of active members with their Song Book access level, for the admin
     * Song Book Access screen. Fetches {@code limit + 1} members (so {@code hasMore}
     * needs no count query) and only the access grants for those members — instead of
     * every member and every grant in the church.
     *
     * @param search free text matched against name, nickname and email (case-insensitive)
     */
    @Transactional(readOnly = true)
    public PageSlice<Map<String, Object>> memberPage(String clientId, String search, int offset, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        int from = Math.max(0, offset);
        List<FamilyMember> members = memberRepo.findSongBookAccessPage(
                clientId, likePattern(search), new OffsetWindow(from, size + 1));

        List<Long> ids = new ArrayList<>();
        for (FamilyMember m : members) if (m.getId() != null) ids.add(m.getId().longValue());
        Map<Long, String> levels = new HashMap<>();
        if (!ids.isEmpty()) {
            for (SongBookAccess a : repo.findByClientIdAndMemberIdIn(clientId, ids)) {
                levels.put(a.getMemberId(), a.getAccessLevel());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (FamilyMember m : members) {
            if (m.getId() == null) continue;
            long id = m.getId().longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", id);
            row.put("firstName", m.getFirstName());
            row.put("lastName", m.getLastName());
            row.put("email", m.getEmail());
            row.put("role", m.getRole());
            String lvl = levels.get(id);
            row.put("level", lvl == null || lvl.isBlank() ? "NONE" : lvl);
            rows.add(row);
        }
        return PageSlice.of(rows, size);
    }

    /** Number of active members matching {@code search} (shown as "Showing x of N"). */
    @Transactional(readOnly = true)
    public long memberCount(String clientId, String search) {
        return memberRepo.countSongBookAccessMembers(clientId, likePattern(search));
    }

    /** Lower-cased "%text%" LIKE pattern with %, _ and ! escaped ('!' is the escape char); '' = no filter. */
    static String likePattern(String search) {
        String q = search == null ? "" : search.trim().toLowerCase();
        if (q.isEmpty()) return "";
        if (q.length() > 100) q = q.substring(0, 100);
        q = q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + q + "%";
    }

    /** "FULL" | "VIEW" | "NONE" for a member (NONE when no grant exists). */
    public String level(String clientId, Long memberId) {
        if (clientId == null || memberId == null) return "NONE";
        return repo.findByClientIdAndMemberId(clientId, memberId)
                .map(SongBookAccess::getAccessLevel)
                .filter(l -> l != null && !l.isBlank())
                .orElse("NONE");
    }

    public boolean canView(String level) { return "VIEW".equals(level) || "FULL".equals(level); }
    public boolean canEdit(String level) { return "FULL".equals(level); }

    /** Map of memberId → level for the whole church (only granted rows). */
    public Map<Long, String> levelsForChurch(String clientId) {
        Map<Long, String> out = new HashMap<>();
        for (SongBookAccess a : repo.findByClientId(clientId)) out.put(a.getMemberId(), a.getAccessLevel());
        return out;
    }

    public List<SongBookAccess> rows(String clientId) { return repo.findByClientId(clientId); }

    /** Grant / change a member's access. level must be NONE | VIEW | FULL. */
    public SongBookAccess setLevel(String clientId, Long memberId, String level, String actor) {
        String lvl = normalize(level);
        SongBookAccess a = repo.findByClientIdAndMemberId(clientId, memberId).orElseGet(() -> {
            SongBookAccess n = new SongBookAccess();
            n.setClientId(clientId);
            n.setMemberId(memberId);
            return n;
        });
        a.setAccessLevel(lvl);
        a.setUpdatedBy(actor);
        a.setUpdatedAt(Instant.now());
        return repo.save(a);
    }

    private String normalize(String level) {
        if (level == null) return "NONE";
        String l = level.trim().toUpperCase();
        return (l.equals("VIEW") || l.equals("FULL")) ? l : "NONE";
    }
}
