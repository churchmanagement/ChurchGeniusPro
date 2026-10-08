package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.EventEmailTemplate;
import com.churchgeniuspro.repository.EventEmailTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD + default handling for {@link EventEmailTemplate}s (RSVP confirmation emails).
 *
 * <p>A built-in default template (id {@code 0}) is always available to every client
 * and is used automatically when the client has no custom default. Clients may set a
 * custom template as default or explicitly select the built-in default.
 */
@Service
public class EventEmailTemplateService {

    public static final int BUILT_IN_ID = 0;
    public static final String DEFAULT_NAME = "Default RSVP Confirmation";

    /** Pre-configured default template body, available to all users. */
    public static final String DEFAULT_BODY =
            "Hi {Registrant Name},\n\n" +
            "Thank you for your RSVP! We're excited to see you at {Event Name}.\n\n" +
            "Date: {Date}\n" +
            "Time: {Time}\n" +
            "Location: {Location}\n\n" +
            "Contact: {Contact}\n\n" +
            "{Google Calendar Link}\n" +
            "{Directions Link}\n\n" +
            "We look forward to seeing you there!";

    private final EventEmailTemplateRepository repo;

    public EventEmailTemplateService(EventEmailTemplateRepository repo) {
        this.repo = repo;
    }

    /** Built-in default first, then the client's custom templates; one is flagged the effective default. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAll(String appClientId) {
        List<EventEmailTemplate> custom = repo.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(appClientId);
        boolean customDefaultExists = custom.stream().anyMatch(EventEmailTemplate::isDefault);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(builtInMap(!customDefaultExists));
        for (EventEmailTemplate t : custom) out.add(toMap(t));
        return out;
    }

    /** The body that should be used when sending: the custom default, else the built-in default. */
    @Transactional(readOnly = true)
    public String getEffectiveDefaultBody(String appClientId) {
        List<EventEmailTemplate> defaults = repo.findDefaults(appClientId);
        if (!defaults.isEmpty() && defaults.get(0).getBody() != null && !defaults.get(0).getBody().isBlank()) {
            return defaults.get(0).getBody();
        }
        return DEFAULT_BODY;
    }

    @Transactional
    public EventEmailTemplate create(String name, String body, boolean makeDefault, String appClientId) {
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) throw new IllegalArgumentException("Template name is required.");
        if (repo.existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(nm, appClientId)) {
            throw new IllegalArgumentException("A template named \"" + nm + "\" already exists.");
        }
        EventEmailTemplate t = new EventEmailTemplate();
        t.setName(nm);
        t.setBody(body == null ? "" : body);
        t.setAppClientId(appClientId);
        t.setDefault(false);
        EventEmailTemplate saved = repo.save(t);
        if (makeDefault) setDefault(saved.getId(), appClientId);
        return saved;
    }

    @Transactional
    public EventEmailTemplate update(Integer id, String name, String body, String appClientId) {
        EventEmailTemplate t = findOrThrow(id, appClientId);
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) throw new IllegalArgumentException("Template name is required.");
        if (repo.existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(nm, appClientId, id)) {
            throw new IllegalArgumentException("A template named \"" + nm + "\" already exists.");
        }
        t.setName(nm);
        t.setBody(body == null ? "" : body);
        return repo.save(t);
    }

    @Transactional
    public void delete(Integer id, String appClientId) {
        EventEmailTemplate t = findOrThrow(id, appClientId);
        t.setDeleteFlag(true);
        repo.save(t);
    }

    @Transactional
    public void setDefault(Integer id, String appClientId) {
        for (EventEmailTemplate t : repo.findDefaults(appClientId)) {
            t.setDefault(false);
            repo.save(t);
        }
        if (id == null || id == BUILT_IN_ID) return;   // built-in default selected
        EventEmailTemplate t = findOrThrow(id, appClientId);
        t.setDefault(true);
        repo.save(t);
    }

    /** Tenant-scoped, non-deleted lookup; unknown and foreign ids fail identically. */
    private EventEmailTemplate findOrThrow(Integer id, String appClientId) {
        return repo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
    }

    private Map<String, Object> builtInMap(boolean isDefault) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", BUILT_IN_ID);
        m.put("name", DEFAULT_NAME);
        m.put("body", DEFAULT_BODY);
        m.put("isDefault", isDefault);
        m.put("builtIn", true);
        return m;
    }

    private Map<String, Object> toMap(EventEmailTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("body", t.getBody());
        m.put("isDefault", t.isDefault());
        m.put("builtIn", false);
        return m;
    }
}
