package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.SongBookAccess;
import com.churchgeniuspro.repository.SongBookAccessRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves and manages per-member Song Book access levels (NONE / VIEW / FULL).
 * Members with no row default to NONE (no access until granted).
 */
@Service
public class SongBookAccessService {

    private final SongBookAccessRepository repo;

    public SongBookAccessService(SongBookAccessRepository repo) {
        this.repo = repo;
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
