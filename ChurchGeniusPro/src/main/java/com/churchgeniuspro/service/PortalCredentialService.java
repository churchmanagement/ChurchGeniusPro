package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three doors into a trial or demo account, described in one place.
 *
 * <p>A trial tenant is one church with three sign-ins: the staff portal its
 * administrator uses, a Member Portal that shows what a congregant sees, and a
 * Kids Portal that shows what a child sees. The credentials for all three were
 * only ever visible on the Service Admin screen, so the person actually
 * evaluating the product had no way to reach two thirds of it.
 *
 * <p>This reads the same rows the Service Admin table does — {@code signup}
 * joined to its tenant through {@link DemoAccessService#allDemoLogins()} — so
 * there is one source of truth for what a demo login is, and nothing here has to
 * be kept in step with provisioning.
 *
 * <p><b>Only ever for a demo or trial tenant.</b> The generated passwords live in
 * {@code signup.demo_password} and exist only for tenants this deployment
 * provisioned; a real church has no such column value and is refused outright by
 * {@link #portalsFor}.
 */
@Service
public class PortalCredentialService {

    private static final Logger log = LoggerFactory.getLogger(PortalCredentialService.class);

    /** Order the portals are presented in, and what each one is for. */
    private static final String[][] PORTALS = {
        { "staff",  "SuperAdmin",                     "Staff Portal",
          "Used by church staff and administrators to manage the church account, including people, giving, events, reminders, and settings." },
        { "member", TestDataService.ROLE_MEMBER_PORTAL, "Member Portal",
          "What a church member sees when they sign in: their family and profile, events, giving history and the ministries they belong to." },
        { "child",  TestDataService.ROLE_CHILD_PORTAL,  "Kids Portal",
          "What a child sees: a simplified screen with their Kids Ministry classes, Sunday School lessons and exam results." }
    };

    private final DemoAccessService demoAccess;

    public PortalCredentialService(DemoAccessService demoAccess) {
        this.demoAccess = demoAccess;
    }

    /**
     * The Staff, Member and Kids portal logins for one demo/trial tenant.
     *
     * <p>Each entry carries {@code key}, {@code label}, {@code purpose},
     * {@code username}, {@code password} and {@code memberName}. A portal a tenant
     * does not have is left out rather than shown empty — tenants created before
     * trials included portals have only the staff row.
     *
     * @return an empty list for anything that is not a demo or trial tenant
     */
    public List<Map<String, Object>> portalsFor(String clientId) {
        if (!EvaluationTenant.isManaged(clientId)) return List.of();

        List<Map<String, Object>> rows;
        try {
            rows = demoAccess.allDemoLogins();
        } catch (Exception e) {
            log.warn("Portal credentials: could not read logins for {} — {}", clientId, e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] portal : PORTALS) {
            Map<String, Object> match = rows.stream()
                    .filter(r -> clientId.equals(r.get("tenant")))
                    .filter(r -> portal[1].equalsIgnoreCase(
                            TestDataService.canonicalDemoRole(String.valueOf(r.get("role_label")))))
                    .findFirst().orElse(null);
            if (match == null) continue;

            String password = String.valueOf(match.getOrDefault("demo_password", ""));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key",        portal[0]);
            m.put("label",      portal[2]);
            m.put("purpose",    portal[3]);
            m.put("username",   String.valueOf(match.getOrDefault("username", "")));
            // A tenant provisioned before demo_password existed has no recoverable
            // password. Say so plainly rather than showing a blank box the person
            // would copy and wonder about.
            m.put("password",   password.isBlank() ? null : password);
            m.put("memberName", String.valueOf(match.getOrDefault("member_name", "")).trim());
            out.add(m);
        }
        return out;
    }
}
