package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchVoiceSetting;
import com.churchgeniuspro.repository.ChurchVoiceSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Business logic for per-church Voice feature settings.
 *
 * <p>Stores the raw admin choices and computes the <em>effective</em> flags after
 * applying the dependency rules:
 * <ul>
 *   <li>Parent {@code voice} off → every sub-feature off.</li>
 *   <li>Any of Voice&nbsp;Type / Voice&nbsp;Command / Converse off → Voice&nbsp;Help and
 *       Voice&nbsp;Debug off.</li>
 * </ul>
 * Unconfigured churches default to all features ON (backward compatible).
 */
@Service
public class ChurchVoiceSettingService {

    private final ChurchVoiceSettingRepository repo;

    public ChurchVoiceSettingService(ChurchVoiceSettingRepository repo) {
        this.repo = repo;
    }

    private static boolean b(Boolean v) { return v == null || v; } // null treated as true (default ON)

    /** The stored setting for a church, or a fresh all-true default (not persisted). */
    public ChurchVoiceSetting getOrDefault(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            ChurchVoiceSetting d = new ChurchVoiceSetting();
            d.setClientId(clientId);
            return d; // entity field defaults are all true
        }
        return repo.findByClientId(clientId).orElseGet(() -> {
            ChurchVoiceSetting d = new ChurchVoiceSetting();
            d.setClientId(clientId);
            return d;
        });
    }

    /** The raw, admin-chosen flags (what the checkboxes should show). */
    public Map<String, Object> rawMap(String clientId) {
        ChurchVoiceSetting s = getOrDefault(clientId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voice",        b(s.getVoice()));
        m.put("voiceType",    b(s.getVoiceType()));
        m.put("voiceCommand", b(s.getVoiceCommand()));
        m.put("converse",     b(s.getConverse()));
        m.put("voiceHelp",    b(s.getVoiceHelp()));
        m.put("voiceDebug",   b(s.getVoiceDebug()));
        return m;
    }

    /** The effective flags after applying the dependency rules. */
    public Map<String, Object> effectiveMap(String clientId) {
        ChurchVoiceSetting s = getOrDefault(clientId);
        boolean voice = b(s.getVoice());
        boolean type     = voice && b(s.getVoiceType());
        boolean command  = voice && b(s.getVoiceCommand());
        boolean converse = voice && b(s.getConverse());
        // Help & Debug require the parent AND all three primary modes enabled.
        boolean primaryAllOn = type && command && converse;
        boolean help  = voice && primaryAllOn && b(s.getVoiceHelp());
        boolean debug = voice && primaryAllOn && b(s.getVoiceDebug());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voice",        voice);
        m.put("voiceType",    type);
        m.put("voiceCommand", command);
        m.put("converse",     converse);
        m.put("voiceHelp",    help);
        m.put("voiceDebug",   debug);
        return m;
    }

    private boolean eff(String clientId, String key) {
        return Boolean.TRUE.equals(effectiveMap(clientId).get(key));
    }

    // Effective per-feature checks used by the backend gates.
    public boolean typeEnabled(String clientId)     { return eff(clientId, "voiceType"); }
    public boolean commandEnabled(String clientId)  { return eff(clientId, "voiceCommand"); }
    public boolean converseEnabled(String clientId) { return eff(clientId, "converse"); }
    /** True when any audio-using voice feature (mic command or converse) is on. */
    public boolean audioEnabled(String clientId)    { return commandEnabled(clientId) || converseEnabled(clientId); }

    /** Upsert the admin-chosen flags, normalizing per the dependency rules before saving. */
    @Transactional
    public Map<String, Object> save(String clientId, Map<String, Object> body) {
        if (clientId == null || clientId.isBlank())
            throw new IllegalArgumentException("Missing church identifier.");
        ChurchVoiceSetting s = repo.findByClientId(clientId).orElseGet(() -> {
            ChurchVoiceSetting n = new ChurchVoiceSetting();
            n.setClientId(clientId);
            return n;
        });

        boolean voice    = asBool(body.get("voice"), true);
        boolean type     = asBool(body.get("voiceType"), true);
        boolean command  = asBool(body.get("voiceCommand"), true);
        boolean converse = asBool(body.get("converse"), true);
        boolean help     = asBool(body.get("voiceHelp"), true);
        boolean debug    = asBool(body.get("voiceDebug"), true);

        // Normalize so what we store already reflects the dependency rules.
        if (!voice) { type = command = converse = help = debug = false; }
        if (!(type && command && converse)) { help = false; debug = false; }

        s.setVoice(voice);
        s.setVoiceType(type);
        s.setVoiceCommand(command);
        s.setConverse(converse);
        s.setVoiceHelp(help);
        s.setVoiceDebug(debug);
        repo.save(s);
        return rawMap(clientId);
    }

    private static boolean asBool(Object o, boolean dflt) {
        if (o == null) return dflt;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
