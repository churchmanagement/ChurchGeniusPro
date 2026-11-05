package com.churchgeniuspro.workflow;

import com.churchgeniuspro.service.OpenAiVoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describe-to-build for automation rules (AI Phase 6): the model turns a sentence into rule JSON, and
 * {@link WorkflowSchema#validate} decides whether it is a rule at all. The model never runs anything
 * and never sees church data — only the description and the vocabulary; the result is a DRAFT shown
 * in the editable form, saved disabled only when the person presses Save.
 */
@Service
public class WorkflowRuleDrafter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuleDrafter.class);
    private static final ObjectMapper M = new ObjectMapper();
    public static final int MAX_DESCRIPTION = 1000;

    private final OpenAiVoiceService openAi;

    @Autowired
    public WorkflowRuleDrafter(@Autowired(required = false) OpenAiVoiceService openAi) { this.openAi = openAi; }

    public boolean isEnabled() { return openAi != null && openAi.isEnabled(); }

    /** Result: either a validated rule, or {@code refusal} (what was wrong) — never a throw. */
    public record Draft(WorkflowSchema.Rule rule, String refusal, Map<String, Object> raw) {}

    public Draft draft(String description) {
        String d = description == null ? "" : description.trim();
        if (d.isEmpty()) return new Draft(null, "Describe the automation first.", null);
        if (d.length() > MAX_DESCRIPTION) return new Draft(null, "The description is too long (1,000 characters).", null);
        if (!isEnabled()) return new Draft(null, "The AI drafting service is not configured on this server. Pick a template or build the rule by hand.", null);
        JsonNode n;
        try { n = openAi.chatJson(systemPrompt(), d, 0); }
        catch (RuntimeException e) { log.warn("[workflow] draft failed: {}", e.getClass().getSimpleName()); return new Draft(null, "The model did not answer. Please try again.", null); }
        if (n == null || !n.isObject()) return new Draft(null, "The model's answer could not be read.", null);
        if (n.hasNonNull("refusal") && !n.get("refusal").asText().isBlank())
            return new Draft(null, "The assistant could not express that as a rule: " + cut(n.get("refusal").asText(), 300), null);
        try {
            WorkflowSchema.Rule rule = WorkflowSchema.validate(n);
            return new Draft(rule, null, M.convertValue(n, LinkedHashMap.class));
        } catch (IllegalArgumentException e) {
            return new Draft(null, "The drafted rule was refused: " + e.getMessage(), null);
        }
    }

    public String systemPrompt() {
        String vocab;
        try { vocab = M.writeValueAsString(WorkflowSchema.describe()); } catch (Exception e) { vocab = "{}"; }
        return "You turn a church staff member's sentence into ONE automation rule for a church-management system. "
             + "Reply with JSON only: {\"name\": string (≤120), \"description\": string (≤500), \"trigger\": one of the trigger keys, "
             + "\"conditions\": [{\"field\", \"op\", \"value\"}], \"actions\": [{\"type\", \"params\": {...}}]} "
             + "or {\"refusal\": \"why\"} when the request cannot be expressed with this vocabulary (e.g. it asks to message members, visitors or donors, "
             + "to delete or change records, or to use data the triggers do not carry). Use ONLY these triggers, fields, operators, actions and parameters: "
             + vocab + ". Conditions are ANDed. In text parameters you may use {{field}} placeholders for the trigger's facts. "
             + "Recipients (\"to\" / \"assignTo\") are \"owner\" unless the person names a staff member — never a member or visitor. "
             + "Never mention anyone's age, birth year or years married. Keep names short and practical.";
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
}
