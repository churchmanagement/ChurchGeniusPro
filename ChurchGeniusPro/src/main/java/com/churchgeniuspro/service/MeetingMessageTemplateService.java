package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.MeetingMessageTemplate;
import com.churchgeniuspro.repository.MeetingMessageTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD + default handling for {@link MeetingMessageTemplate}s.
 *
 * <p>A built-in default template (id {@code 0}) is always available to every client.
 * If a client has no custom templates, or none flagged default, the built-in default
 * is the effective default. Clients may also explicitly select the built-in default.
 */
@Service
public class MeetingMessageTemplateService {

    /** Synthetic id for the built-in, non-editable default template. */
    public static final int BUILT_IN_ID = 0;

    public static final String DEFAULT_NAME        = "Default Template";
    public static final String DEFAULT_DATE_FORMAT = "MMMM dd, yyyy";   // June 03, 2026
    public static final String DEFAULT_TIME_FORMAT = "startEnd";

    /** The pre-configured default template body, available to all users. */
    public static final String DEFAULT_BODY =
            "Hello,\n\n" +
            "You are invited to the following meeting:\n\n" +
            "Category: {Category}\n\n" +
            "Date: {Date}\n" +
            "Time: {Time}\n\n" +
            "Location:\n" +
            "{LocationName}\n" +
            "{LocationAddress}\n\n" +
            "Notes:\n" +
            "{Notes}\n\n" +
            "{Image}\n\n" +
            "Thank you.";

    private final MeetingMessageTemplateRepository repo;

    public MeetingMessageTemplateService(MeetingMessageTemplateRepository repo) {
        this.repo = repo;
    }

    /**
     * All templates for a client: the built-in default first, then the client's
     * custom templates. Each entry carries an {@code isDefault} flag marking the
     * single effective default (a custom default if one exists, else the built-in).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAll(String appClientId) {
        List<MeetingMessageTemplate> custom = repo.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(appClientId);
        boolean customDefaultExists = custom.stream().anyMatch(MeetingMessageTemplate::isDefault);

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(builtInMap(!customDefaultExists));
        for (MeetingMessageTemplate t : custom) out.add(toMap(t));
        return out;
    }

    @Transactional
    public MeetingMessageTemplate create(String name, String body, String dateFormat,
                                         String timeFormat, boolean makeDefault, String appClientId) {
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) throw new IllegalArgumentException("Template name is required.");
        if (repo.existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(nm, appClientId)) {
            throw new IllegalArgumentException("A template named \"" + nm + "\" already exists.");
        }
        MeetingMessageTemplate t = new MeetingMessageTemplate();
        t.setName(nm);
        t.setBody(body == null ? "" : body);
        t.setDateFormat(blankToDefault(dateFormat, DEFAULT_DATE_FORMAT));
        t.setTimeFormat(blankToDefault(timeFormat, DEFAULT_TIME_FORMAT));
        t.setAppClientId(appClientId);
        t.setDefault(false);
        MeetingMessageTemplate saved = repo.save(t);
        if (makeDefault) setDefault(saved.getId(), appClientId);
        return saved;
    }

    @Transactional
    public MeetingMessageTemplate update(Integer id, String name, String body, String dateFormat,
                                         String timeFormat, String appClientId) {
        MeetingMessageTemplate t = findOrThrow(id, appClientId);
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) throw new IllegalArgumentException("Template name is required.");
        if (repo.existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(nm, appClientId, id)) {
            throw new IllegalArgumentException("A template named \"" + nm + "\" already exists.");
        }
        t.setName(nm);
        t.setBody(body == null ? "" : body);
        t.setDateFormat(blankToDefault(dateFormat, DEFAULT_DATE_FORMAT));
        t.setTimeFormat(blankToDefault(timeFormat, DEFAULT_TIME_FORMAT));
        return repo.save(t);
    }

    @Transactional
    public void delete(Integer id, String appClientId) {
        MeetingMessageTemplate t = findOrThrow(id, appClientId);
        t.setDeleteFlag(true);
        repo.save(t);
    }

    /**
     * Marks a template as the client's default. Passing {@link #BUILT_IN_ID} clears
     * every custom default, so the built-in default becomes effective again.
     */
    @Transactional
    public void setDefault(Integer id, String appClientId) {
        // Clear any existing custom defaults first.
        for (MeetingMessageTemplate t : repo.findDefaults(appClientId)) {
            t.setDefault(false);
            repo.save(t);
        }
        if (id == null || id == BUILT_IN_ID) return;   // built-in default selected
        MeetingMessageTemplate t = findOrThrow(id, appClientId);
        t.setDefault(true);
        repo.save(t);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Tenant-scoped, non-deleted lookup; unknown and foreign ids fail identically. */
    private MeetingMessageTemplate findOrThrow(Integer id, String appClientId) {
        return repo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
    }

    private Map<String, Object> builtInMap(boolean isDefault) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", BUILT_IN_ID);
        m.put("name", DEFAULT_NAME);
        m.put("body", DEFAULT_BODY);
        m.put("dateFormat", DEFAULT_DATE_FORMAT);
        m.put("timeFormat", DEFAULT_TIME_FORMAT);
        m.put("isDefault", isDefault);
        m.put("builtIn", true);
        return m;
    }

    private Map<String, Object> toMap(MeetingMessageTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("body", t.getBody());
        m.put("dateFormat", t.getDateFormat());
        m.put("timeFormat", t.getTimeFormat());
        m.put("isDefault", t.isDefault());
        m.put("builtIn", false);
        return m;
    }

    private static String blankToDefault(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }
}
