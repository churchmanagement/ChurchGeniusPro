package com.churchgeniuspro.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single Help Center article. Carries the permission/role gating used to
 * decide whether a given user may see it, plus version history for change
 * tracking. Instances are immutable and built by {@link HelpContentCatalog}.
 */
public class HelpArticle {

    public final String id;
    public final String category;
    public final String title;
    public final String permKey;     // null = visible to any authenticated user
    public final boolean adminOnly;  // true = administrators only (admin/church)
    public final String summary;     // brief explanation
    public final String detail;      // in-depth explanation (what / why / how)
    public final List<String> steps;
    public final List<String> troubleshooting;
    public final List<String> changelog;   // version history, newest first
    public final String version;
    public final List<String> keywords;

    public HelpArticle(String id, String category, String title, String permKey, boolean adminOnly,
                       String summary, String detail, List<String> steps, List<String> troubleshooting,
                       String version, List<String> changelog, List<String> keywords) {
        this.id = id;
        this.category = category;
        this.title = title;
        this.permKey = permKey;
        this.adminOnly = adminOnly;
        this.summary = summary;
        this.detail = detail;
        this.steps = steps == null ? List.of() : steps;
        this.troubleshooting = troubleshooting == null ? List.of() : troubleshooting;
        this.version = version;
        this.changelog = changelog == null ? List.of() : changelog;
        this.keywords = keywords == null ? List.of() : keywords;
    }

    /**
     * Roles that can actually use the feature (SuperAdmin, Admin, Accountant, User, Limited,
     * Member, Church). Empty = every role. The permission key alone cannot express this: a
     * Member Portal session carries no staff permission map, so without a role list every
     * staff article was listed for members too.
     */
    private java.util.Set<String> roles = java.util.Set.of();

    /** Restricts the article to these roles (comma-separated). Returns this for chaining. */
    public HelpArticle roles(String csv) {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        if (csv != null) for (String r : csv.split(",")) if (!r.isBlank()) s.add(r.trim());
        this.roles = java.util.Collections.unmodifiableSet(s);
        return this;
    }

    public java.util.Set<String> roles() { return roles; }

    /** Compact entry for the article list / search results. */
    public Map<String, Object> toListItem() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("category", category);
        m.put("summary", summary);
        m.put("version", version);
        m.put("adminOnly", adminOnly);
        return m;
    }

    /** Full article payload. */
    public Map<String, Object> toFull() {
        Map<String, Object> m = toListItem();
        m.put("detail", detail);
        m.put("steps", steps);
        m.put("troubleshooting", troubleshooting);
        m.put("changelog", changelog);
        return m;
    }

    /** Plain-text rendering used to ground the AI assistant. */
    public String toKnowledge() {
        StringBuilder sb = new StringBuilder();
        sb.append("ARTICLE id=").append(id).append(" | ").append(title)
          .append(" (").append(category).append(", v").append(version).append(")\n");
        sb.append("Summary: ").append(summary).append('\n');
        if (detail != null && !detail.isBlank()) sb.append("Detail: ").append(detail).append('\n');
        if (!steps.isEmpty()) sb.append("Steps: ").append(String.join(" | ", steps)).append('\n');
        if (!troubleshooting.isEmpty()) sb.append("Troubleshooting: ").append(String.join(" | ", troubleshooting)).append('\n');
        return sb.toString();
    }

    // ── small builder helpers ──
    static List<String> list(String... v) { return new ArrayList<>(Arrays.asList(v)); }
}
