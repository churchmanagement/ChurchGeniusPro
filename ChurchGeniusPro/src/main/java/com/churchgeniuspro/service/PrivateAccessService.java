package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.NetworkMatcher;
import com.churchgeniuspro.util.PrivatePageCatalog;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business logic for Private Page Access: per-tenant master setting, approved
 * networks, per-page rules, the access decision, audit logging, and reporting.
 */
@Service
public class PrivateAccessService {

    private final PrivateAccessSettingRepository settingRepo;
    private final PrivateNetworkRepository       networkRepo;
    private final PrivatePageRuleRepository       ruleRepo;
    private final PrivateAccessLogRepository       logRepo;

    public PrivateAccessService(PrivateAccessSettingRepository settingRepo,
                                PrivateNetworkRepository networkRepo,
                                PrivatePageRuleRepository ruleRepo,
                                PrivateAccessLogRepository logRepo) {
        this.settingRepo = settingRepo;
        this.networkRepo = networkRepo;
        this.ruleRepo    = ruleRepo;
        this.logRepo     = logRepo;
    }

    // ── Setting ────────────────────────────────────────────────────────────

    @Transactional
    public PrivateAccessSetting getSetting(String clientId) {
        return settingRepo.findByClientId(clientId).orElseGet(() -> {
            PrivateAccessSetting s = new PrivateAccessSetting();
            s.setClientId(clientId);
            s.setEnabled(false);
            s.setTrustProxy(true);
            return settingRepo.save(s);
        });
    }

    @Transactional
    public PrivateAccessSetting saveSetting(String clientId, boolean enabled, Boolean trustProxy) {
        PrivateAccessSetting s = getSetting(clientId);
        s.setEnabled(enabled);
        if (trustProxy != null) s.setTrustProxy(trustProxy);
        return settingRepo.save(s);
    }

    // ── Networks ───────────────────────────────────────────────────────────

    /**
     * Create ({@code id == null}) or update a network. Returns {@code null} when the id
     * is unknown or belongs to another tenant — never re-parents a foreign row.
     */
    @Transactional
    public PrivateNetwork saveNetwork(String clientId, Long id, String name, String ipRanges, boolean enabled) {
        PrivateNetwork n;
        if (id != null) {
            n = networkRepo.findById(id).filter(x -> clientId.equals(x.getClientId())).orElse(null);
            if (n == null) return null;
        } else {
            n = new PrivateNetwork();
        }
        n.setClientId(clientId);
        n.setName(name == null ? "Network" : name.trim());
        n.setIpRanges(ipRanges);
        n.setEnabled(enabled);
        return networkRepo.save(n);
    }

    @Transactional
    public void deleteNetwork(String clientId, Long id) {
        networkRepo.findById(id)
                .filter(n -> clientId.equals(n.getClientId()))
                .ifPresent(networkRepo::delete);
    }

    public List<PrivateNetwork> listNetworks(String clientId) {
        return networkRepo.findByClientIdOrderByNameAsc(clientId);
    }

    // ── Rules ──────────────────────────────────────────────────────────────

    @Transactional
    public PrivatePageRule saveRule(String clientId, String pageKey, boolean enabled, List<Long> networkIds) {
        if (PrivatePageCatalog.byKey(pageKey) == null)
            throw new IllegalArgumentException("Unknown page: " + pageKey);
        PrivatePageRule r = ruleRepo.findByClientIdAndPageKey(clientId, pageKey).orElseGet(PrivatePageRule::new);
        r.setClientId(clientId);
        r.setPageKey(pageKey);
        r.setEnabled(enabled);
        // Only this tenant's networks may be referenced by a rule.
        if (networkIds != null && !networkIds.isEmpty()) {
            Set<Long> owned = networkRepo.findByClientIdOrderByNameAsc(clientId).stream()
                    .map(PrivateNetwork::getId).collect(Collectors.toSet());
            networkIds = networkIds.stream().filter(owned::contains).collect(Collectors.toList());
        }
        r.setNetworkIds(networkIds == null || networkIds.isEmpty() ? null
                : networkIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return ruleRepo.save(r);
    }

    // ── Decision ─────────────────────────────────────────────────────────────

    public static class Decision {
        public boolean allowed;
        public String status;   // ALLOWED | BLOCKED | OVERRIDE | OFF
        public String reason;
        public String pageKey;
    }

    /**
     * Evaluate whether {@code ip} may access the page group {@code pageKey} for this
     * tenant. {@code isAdmin} grants an override. Returns OFF when the feature or the
     * page rule is disabled (the filter then lets the request through without logging).
     */
    @Transactional(readOnly = true)
    public Decision evaluate(String clientId, String pageKey, String ip, boolean isAdmin) {
        Decision d = new Decision();
        d.pageKey = pageKey;

        PrivateAccessSetting setting = settingRepo.findByClientId(clientId).orElse(null);
        if (setting == null || !setting.isEnabled()) { d.status = "OFF"; d.allowed = true; return d; }

        // Gated only when BOTH the master setting is on (checked above) AND this page's own
        // rule is enabled — same model as Kids Ministry and Event Check-In.
        PrivatePageRule rule = ruleRepo.findByClientIdAndPageKey(clientId, pageKey).orElse(null);
        if (rule == null || !rule.isEnabled()) { d.status = "OFF"; d.allowed = true; return d; }

        // Which networks apply to this rule?
        List<PrivateNetwork> enabledNetworks = networkRepo.findByClientIdAndEnabledTrue(clientId);
        Set<Long> allowIds = parseIds(rule.getNetworkIds());
        List<PrivateNetwork> applicable = allowIds.isEmpty() ? enabledNetworks
                : enabledNetworks.stream().filter(n -> allowIds.contains(n.getId())).collect(Collectors.toList());

        if (isAdmin) {
            d.allowed = true; d.status = "OVERRIDE"; d.reason = "admin override";
            return d;
        }

        for (PrivateNetwork n : applicable) {
            if (NetworkMatcher.matchesAny(ip, NetworkMatcher.parseRanges(n.getIpRanges()))) {
                d.allowed = true; d.status = "ALLOWED"; d.reason = "matched: " + n.getName();
                return d;
            }
        }
        d.allowed = false; d.status = "BLOCKED";
        d.reason = applicable.isEmpty() ? "no approved network configured" : "ip not on an approved network";
        return d;
    }

    /** True when the page group is currently Private (feature on + rule enabled) for this tenant. */
    public boolean isPrivate(String clientId, String pageKey) {
        PrivateAccessSetting s = settingRepo.findByClientId(clientId).orElse(null);
        if (s == null || !s.isEnabled()) return false;
        return ruleRepo.findByClientIdAndPageKey(clientId, pageKey).map(PrivatePageRule::isEnabled).orElse(false);
    }

    // ── Audit log ─────────────────────────────────────────────────────────────

    @Transactional
    public void log(String clientId, String username, Decision d, String ip, String path) {
        PrivateAccessLog row = new PrivateAccessLog();
        row.setClientId(clientId);
        row.setUsername(username == null ? "(anonymous)" : username);
        row.setPageKey(d.pageKey);
        row.setRequestedPath(path != null && path.length() > 300 ? path.substring(0, 300) : path);
        row.setIpAddress(ip);
        row.setStatus(d.status);
        row.setReason(d.reason);
        row.setCreatedAt(LocalDateTime.now());
        logRepo.save(row);
    }

    public Map<String, Object> report(String clientId, int limit) {
        int cap = Math.max(1, Math.min(limit, 500));
        List<PrivateAccessLog> recent = logRepo.findByClientIdOrderByCreatedAtDesc(clientId, PageRequest.of(0, cap));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PrivateAccessLog r : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            m.put("username", r.getUsername());
            m.put("ip", r.getIpAddress());
            m.put("page", r.getPageKey());
            m.put("path", r.getRequestedPath());
            m.put("status", r.getStatus());
            m.put("reason", r.getReason());
            rows.add(m);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("blocked", logRepo.countByClientIdAndStatus(clientId, "BLOCKED"));
        summary.put("allowed", logRepo.countByClientIdAndStatus(clientId, "ALLOWED"));
        summary.put("override", logRepo.countByClientIdAndStatus(clientId, "OVERRIDE"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("recent", rows);
        return out;
    }

    // ── Config serialization (for the admin page) ───────────────────────────

    /** Tenant config: only the per-tenant (non-global) pages. */
    public Map<String, Object> configMap(String clientId) {
        PrivateAccessSetting s = getSetting(clientId);
        List<PrivateNetwork> nets = listNetworks(clientId);
        Map<String, PrivatePageRule> rulesByKey = ruleRepo.findByClientId(clientId).stream()
                .collect(Collectors.toMap(PrivatePageRule::getPageKey, r -> r, (a, b) -> a));

        List<Map<String, Object>> netList = new ArrayList<>();
        for (PrivateNetwork n : nets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("name", n.getName());
            m.put("ipRanges", n.getIpRanges());
            m.put("enabled", n.isEnabled());
            netList.add(m);
        }

        List<Map<String, Object>> pageList = new ArrayList<>();
        for (PrivatePageCatalog.Page p : PrivatePageCatalog.PAGES) {
            PrivatePageRule r = rulesByKey.get(p.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("label", p.label());
            m.put("preAuth", p.preAuth());
            m.put("enabled", r != null && r.isEnabled());
            m.put("networkIds", r == null ? List.of()
                    : new ArrayList<>(parseIds(r.getNetworkIds())));
            pageList.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.isEnabled());
        out.put("trustProxy", s.isTrustProxy());
        out.put("networks", netList);
        out.put("pages", pageList);
        return out;
    }


    private static Set<Long> parseIds(String csv) {
        Set<Long> ids = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return ids;
        for (String t : csv.split(",")) {
            try { ids.add(Long.parseLong(t.trim())); } catch (NumberFormatException ignored) {}
        }
        return ids;
    }
}
