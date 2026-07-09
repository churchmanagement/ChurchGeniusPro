package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.OpenAiUsage;
import com.churchgeniuspro.repository.OpenAiUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-church OpenAI usage accounting: voice minutes and Vision uploads.
 *
 * <p>Both quotas are metered at the church/client level and shared across every
 * page. The page-facing status map drives button enable/disable; the consume
 * methods are called by the voice / vision endpoints after a successful OpenAI
 * call; reset and config are driven from the Service Admin page.
 */
@Service
public class OpenAiUsageService {

    private final OpenAiUsageRepository repo;

    public OpenAiUsageService(OpenAiUsageRepository repo) {
        this.repo = repo;
    }

    /** Returns the settings row for a church, creating a default one if absent. */
    @Transactional
    public OpenAiUsage getOrCreate(String clientId) {
        return repo.findByClientId(clientId).orElseGet(() -> {
            OpenAiUsage u = new OpenAiUsage();
            u.setClientId(clientId);
            return repo.save(u);
        });
    }

    public Optional<OpenAiUsage> find(String clientId) {
        return repo.findByClientId(clientId);
    }

    // ── Voice ────────────────────────────────────────────────────────────────

    private long voiceLimitSeconds(OpenAiUsage u) {
        return (long) Math.max(0, u.getVoiceLimitMinutes()) * 60L;
    }

    /** {@code true} when voice is enabled and the church is under its limit. */
    public boolean voiceAvailable(String clientId) {
        if (clientId == null || clientId.isBlank()) return false;
        OpenAiUsage u = getOrCreate(clientId);
        return Boolean.TRUE.equals(u.getVoiceEnabled())
                && u.getVoiceUsedSeconds() < voiceLimitSeconds(u);
    }

    /** Adds consumed voice seconds (called after an OpenAI transcription). */
    @Transactional
    public OpenAiUsage addVoiceSeconds(String clientId, long seconds) {
        OpenAiUsage u = getOrCreate(clientId);
        if (seconds > 0) {
            u.setVoiceUsedSeconds(u.getVoiceUsedSeconds() + seconds);
            repo.save(u);
        }
        return u;
    }

    // ── Vision ───────────────────────────────────────────────────────────────

    /** {@code true} when Vision is enabled and the church is under its limit. */
    public boolean visionAvailable(String clientId) {
        if (clientId == null || clientId.isBlank()) return false;
        OpenAiUsage u = getOrCreate(clientId);
        return Boolean.TRUE.equals(u.getVisionEnabled())
                && u.getVisionUsedUploads() < u.getVisionLimitUploads();
    }

    /** Increments the Vision upload counter (called after an OpenAI Vision call). */
    @Transactional
    public OpenAiUsage incrementVisionUploads(String clientId, int n) {
        OpenAiUsage u = getOrCreate(clientId);
        if (n > 0) {
            u.setVisionUsedUploads(u.getVisionUsedUploads() + n);
            repo.save(u);
        }
        return u;
    }

    // ── Reset (Service Admin) ─────────────────────────────────────────────────

    @Transactional
    public OpenAiUsage resetVoice(String clientId) {
        OpenAiUsage u = getOrCreate(clientId);
        u.setVoiceUsedSeconds(0L);
        return repo.save(u);
    }

    @Transactional
    public OpenAiUsage resetVision(String clientId) {
        OpenAiUsage u = getOrCreate(clientId);
        u.setVisionUsedUploads(0L);
        return repo.save(u);
    }

    // ── Config update (Service Admin) ─────────────────────────────────────────

    @Transactional
    public OpenAiUsage updateConfig(String clientId, Map<String, Object> body) {
        OpenAiUsage u = getOrCreate(clientId);
        if (body.containsKey("voiceEnabled"))         u.setVoiceEnabled(asBool(body.get("voiceEnabled"), u.getVoiceEnabled()));
        if (body.containsKey("voiceLimitMinutes"))    u.setVoiceLimitMinutes(asInt(body.get("voiceLimitMinutes"), u.getVoiceLimitMinutes()));
        if (body.containsKey("visionEnabled"))        u.setVisionEnabled(asBool(body.get("visionEnabled"), u.getVisionEnabled()));
        if (body.containsKey("visionLimitUploads"))   u.setVisionLimitUploads(asInt(body.get("visionLimitUploads"), u.getVisionLimitUploads()));
        if (body.containsKey("maxFileSizeMb"))        u.setMaxFileSizeMb(asInt(body.get("maxFileSizeMb"), u.getMaxFileSizeMb()));
        if (body.containsKey("maxPagesPerUpload"))    u.setMaxPagesPerUpload(asInt(body.get("maxPagesPerUpload"), u.getMaxPagesPerUpload()));
        if (body.containsKey("maxImageResolutionPx")) u.setMaxImageResolutionPx(asInt(body.get("maxImageResolutionPx"), u.getMaxImageResolutionPx()));
        if (body.containsKey("autoResizeImages"))     u.setAutoResizeImages(asBool(body.get("autoResizeImages"), u.getAutoResizeImages()));
        if (body.containsKey("jpegQuality"))          u.setJpegQuality(asInt(body.get("jpegQuality"), u.getJpegQuality()));
        return repo.save(u);
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    /** Lightweight status for the pages (button enable/disable + remaining). */
    public Map<String, Object> statusMap(String clientId) {
        OpenAiUsage u = getOrCreate(clientId);
        long limitSec = voiceLimitSeconds(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voiceEnabled",          Boolean.TRUE.equals(u.getVoiceEnabled()));
        m.put("voiceLimitMinutes",     u.getVoiceLimitMinutes());
        m.put("voiceUsedSeconds",      u.getVoiceUsedSeconds());
        m.put("voiceRemainingSeconds", Math.max(0, limitSec - u.getVoiceUsedSeconds()));
        m.put("voiceAvailable",        Boolean.TRUE.equals(u.getVoiceEnabled()) && u.getVoiceUsedSeconds() < limitSec);
        m.put("visionEnabled",         Boolean.TRUE.equals(u.getVisionEnabled()));
        m.put("visionLimitUploads",    u.getVisionLimitUploads());
        m.put("visionUsedUploads",     u.getVisionUsedUploads());
        m.put("visionRemainingUploads",Math.max(0, (long) u.getVisionLimitUploads() - u.getVisionUsedUploads()));
        m.put("visionAvailable",       Boolean.TRUE.equals(u.getVisionEnabled()) && u.getVisionUsedUploads() < u.getVisionLimitUploads());
        return m;
    }

    /** Full settings for the Service Admin editor (config + usage). */
    public Map<String, Object> adminMap(String clientId) {
        OpenAiUsage u = getOrCreate(clientId);
        Map<String, Object> m = new LinkedHashMap<>(statusMap(clientId));
        m.put("clientId",              u.getClientId());
        m.put("maxFileSizeMb",         u.getMaxFileSizeMb());
        m.put("maxPagesPerUpload",     u.getMaxPagesPerUpload());
        m.put("maxImageResolutionPx",  u.getMaxImageResolutionPx());
        m.put("autoResizeImages",      Boolean.TRUE.equals(u.getAutoResizeImages()));
        m.put("jpegQuality",           u.getJpegQuality());
        return m;
    }

    private static boolean asBool(Object v, Boolean dflt) {
        if (v == null) return dflt != null && dflt;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static int asInt(Object v, Integer dflt) {
        if (v == null) return dflt == null ? 0 : dflt;
        try { return (int) Math.round(Double.parseDouble(v.toString())); }
        catch (Exception e) { return dflt == null ? 0 : dflt; }
    }
}
