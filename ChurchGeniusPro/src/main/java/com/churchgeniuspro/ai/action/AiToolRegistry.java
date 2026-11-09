package com.churchgeniuspro.ai.action;

import org.springframework.stereotype.Component;

import java.util.*;

/** AI Phase 4 — the closed list of registered tools (every {@link AiTool} bean), by name. */
@Component
public class AiToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public AiToolRegistry(List<AiTool> beans) {
        for (AiTool t : beans) tools.put(t.name(), t);
    }

    public AiTool get(String name) { return name == null ? null : tools.get(name); }

    public Collection<AiTool> all() { return Collections.unmodifiableCollection(tools.values()); }

    public Set<String> names() { return Collections.unmodifiableSet(tools.keySet()); }

    /** The tool list for the LLM fallback prompt and the Help Center (name, description, parameters). */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiTool t : tools.values()) {
            if (t.guidedOnly()) continue;                 // only the guided conversation proposes these
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name()); m.put("label", t.label()); m.put("description", t.description()); m.put("parameters", t.parameters());
            out.add(m);
        }
        return out;
    }
}
