package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.HelpAuditLog;
import com.churchgeniuspro.repository.HelpAuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Permission-aware Help Center assistant. Filters the {@link HelpContentCatalog}
 * to the articles the current user is allowed to see, answers questions from that
 * scoped knowledge base (via OpenAI when configured, with a keyword fallback when
 * not), and writes an audit row for every interaction.
 *
 * <p>Security: AI answers and article lists never reference modules the user is
 * not permitted to access. Restricted requests return a polite denial message.
 */
@Service
public class HelpAssistantService {

    private static final Logger LOG = LoggerFactory.getLogger(HelpAssistantService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String DENY_MESSAGE =
        "You do not currently have permission to access this module. Please contact your administrator for additional access.";

    private final HelpContentCatalog catalog;
    private final OpenAiVoiceService openAi;
    private final HelpAuditLogRepository auditRepo;

    public HelpAssistantService(HelpContentCatalog catalog, OpenAiVoiceService openAi,
                                HelpAuditLogRepository auditRepo) {
        this.catalog = catalog;
        this.openAi = openAi;
        this.auditRepo = auditRepo;
    }

    // ── Visibility ──────────────────────────────────────────────────────────

    private boolean isAdmin(String role, boolean church) {
        return church || "SuperAdmin".equals(role) || "Admin".equals(role);
    }

    /** Opt-in denial, mirroring RoleGuard.requirePermission. */
    boolean allowed(HelpArticle a, String privilegesJson, String role, boolean church) {
        if (a.adminOnly && !isAdmin(role, church)) return false;
        if (a.permKey == null || a.permKey.isBlank()) return true;
        if (church) return true;                                   // church bypass
        if (privilegesJson == null || privilegesJson.isBlank()) return true; // full access
        try {
            JsonNode map = MAPPER.readTree(privilegesJson);
            JsonNode v = map.get(a.permKey);
            return v == null || !v.isBoolean() || v.asBoolean();    // missing/non-false = allowed
        } catch (Exception e) {
            return true;
        }
    }

    private List<HelpArticle> visible(String privs, String role, boolean church) {
        List<HelpArticle> out = new ArrayList<>();
        for (HelpArticle a : catalog.all()) if (allowed(a, privs, role, church)) out.add(a);
        return out;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> articles(String privs, String role, boolean church) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HelpArticle a : visible(privs, role, church)) out.add(a.toListItem());
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("category")) + String.valueOf(m.get("title"))));
        return out;
    }

    public Map<String, Object> article(String privs, String role, boolean church, String id,
                                       String clientId, String actor) {
        HelpArticle a = catalog.byId(id);
        if (a == null) return null;
        if (!allowed(a, privs, role, church)) {
            audit(clientId, actor, role, "DENIED", null, id, a.permKey, DENY_MESSAGE);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("denied", true);
            m.put("message", DENY_MESSAGE);
            return m;
        }
        audit(clientId, actor, role, "VIEW", null, id, a.permKey, a.title);
        return a.toFull();
    }

    /**
     * Conversational answer grounded in the user's permitted articles.
     * @param voice true when the request originated from speech (audited as VOICE).
     */
    public Map<String, Object> assist(String privs, String role, boolean church, String question,
                                      String history, boolean voice, String clientId, String actor) {
        List<HelpArticle> vis = visible(privs, role, church);

        Map<String, Object> result = (openAi.isEnabled())
                ? answerWithAi(question, history, vis, catalog.all())
                : null;
        if (result == null) result = answerWithKeywords(question, vis, catalog.all());

        audit(clientId, actor, role, voice ? "VOICE" : "QUERY", question, null, null,
              String.valueOf(result.getOrDefault("answer", "")));
        return result;
    }

    // ── AI answer ─────────────────────────────────────────────────────────────

    /**
     * Grounds the model in the WHOLE catalogue, each article tagged accessible=true/false
     * for this user, so a feature the user cannot open is still explained (and never
     * called non-existent); links are only ever returned for accessible articles.
     */
    private Map<String, Object> answerWithAi(String question, String history, List<HelpArticle> vis, List<HelpArticle> all) {
        Set<String> visIds = new HashSet<>();
        for (HelpArticle a : vis) visIds.add(a.id);
        StringBuilder kb = new StringBuilder();
        for (HelpArticle a : all) kb.append("[accessible=").append(visIds.contains(a.id)).append("] ").append(a.toKnowledge()).append('\n');

        String system =
            "You are the Help Assistant for ChurchGenius Pro, a church-management web app. "
          + "Answer ONLY using the KNOWLEDGE articles provided below; they describe EVERY feature of the product. "
          + "Each article is tagged accessible=true (this user can use it) or accessible=false (it exists, but this "
          + "user's role, permissions or subscription plan do not include it). For an accessible=false feature, still "
          + "explain what it does and where it lives, then add one sentence that it is not enabled for their account "
          + "and an administrator can enable it (or the plan can be changed); never say such a feature does not exist, "
          + "and never give step-by-step instructions for using it. Only if a feature appears in NO article, say you have "
          + "no information about it. "
          + "Adapt to the request: 'briefly' = 1-2 sentences; 'in detail' = a fuller explanation; "
          + "'step by step' = numbered steps; 'what is it / why / how' = explain plainly; if the user describes a "
          + "problem, give troubleshooting steps. Be friendly and concise. "
          + "Reply ONLY as a JSON object: {answer (string), steps (array of strings, may be empty), "
          + "troubleshooting (array of strings, may be empty), articleIds (array of article ids you used, from the "
          + "knowledge only), category (short label), followUpQuestion (string or null), "
          + "restricted (true only if the feature asked about is tagged accessible=false)}.\n\n"
          + "KNOWLEDGE:\n" + kb;

        String user = "Question: " + question
                    + (history == null || history.isBlank() ? "" : "\nConversation so far: " + history);

        JsonNode j = openAi.chatJson(system, user);
        if (j == null) return null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("answer", j.path("answer").asText(""));
        m.put("steps", textList(j.get("steps")));
        m.put("troubleshooting", textList(j.get("troubleshooting")));
        m.put("category", j.path("category").asText(""));
        String fu = j.path("followUpQuestion").asText("");
        m.put("followUpQuestion", fu.isBlank() || "null".equalsIgnoreCase(fu) ? null : fu);

        m.put("restricted", j.path("restricted").asBoolean(false));
        // Only surface article links the user is actually allowed to see.
        List<Map<String, Object>> arts = new ArrayList<>();
        if (j.get("articleIds") != null && j.get("articleIds").isArray()) {
            for (JsonNode n : j.get("articleIds")) {
                String id = n.asText("");
                if (visIds.contains(id)) {
                    HelpArticle a = catalog.byId(id);
                    if (a != null) arts.add(a.toListItem());
                }
            }
        }
        m.put("articles", arts);
        m.put("source", "AI Help Assistant");
        if (m.get("answer") == null || String.valueOf(m.get("answer")).isBlank()) return null;
        return m;
    }

    // ── Keyword fallback (works without OpenAI) ──────────────────────────────

    private Map<String, Object> answerWithKeywords(String question, List<HelpArticle> vis, List<HelpArticle> all) {
        String q = question == null ? "" : question.toLowerCase();
        HelpArticle best = null;
        int bestScore = 0;
        for (HelpArticle a : all) {
            int score = 0;
            for (String kw : a.keywords) if (!kw.isBlank() && q.contains(kw.toLowerCase())) score += 2;
            for (String w : a.title.toLowerCase().split("\\s+")) if (w.length() > 3 && q.contains(w)) score += 1;
            if (score > bestScore) { bestScore = score; best = a; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (best == null) {
            m.put("answer", "I couldn't find help on that. Try rephrasing, or browse the articles below.");
            m.put("steps", List.of());
            m.put("troubleshooting", List.of());
            m.put("articles", List.of());
        } else if (!vis.contains(best)) {
            // The feature exists but this login cannot use it: explain it, offer no steps or link.
            m.put("answer", best.summary + (best.detail == null || best.detail.isBlank() ? "" : " " + best.detail)
                + " This feature is not enabled for your account (role, permissions or plan) — an administrator can enable it.");
            m.put("steps", List.of());
            m.put("troubleshooting", List.of());
            m.put("articles", List.of());
            m.put("category", best.category);
            m.put("restricted", true);
        } else {
            m.put("answer", best.summary + (best.detail == null || best.detail.isBlank() ? "" : " " + best.detail));
            m.put("steps", best.steps);
            m.put("troubleshooting", best.troubleshooting);
            m.put("articles", List.of(best.toListItem()));
            m.put("category", best.category);
        }
        m.put("followUpQuestion", null);
        m.put("source", "Help Center");
        return m;
    }

    private List<String> textList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) for (JsonNode x : n) { String s = x.asText(""); if (!s.isBlank()) out.add(s); }
        return out;
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    private void audit(String clientId, String actor, String role, String kind, String query,
                       String articleId, String permKey, String answer) {
        try {
            HelpAuditLog row = new HelpAuditLog();
            row.setClientId(clientId);
            row.setActor(actor);
            row.setRole(role);
            row.setKind(kind);
            row.setQuery(trim(query, 4000));
            row.setArticleId(articleId);
            row.setPermKey(permKey);
            row.setAnswerSummary(trim(answer, 4000));
            auditRepo.save(row);
        } catch (Exception e) {
            LOG.warn("[HelpAudit] failed to write {} row: {}", kind, e.toString());
        }
    }

    public List<Map<String, Object>> recentAudit(String clientId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HelpAuditLog r : auditRepo.findByClientIdOrderByCreatedAtDesc(clientId,
                org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 500)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("actor", r.getActor());
            m.put("role", r.getRole());
            m.put("kind", r.getKind());
            m.put("query", r.getQuery());
            m.put("articleId", r.getArticleId());
            m.put("permKey", r.getPermKey());
            m.put("time", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
