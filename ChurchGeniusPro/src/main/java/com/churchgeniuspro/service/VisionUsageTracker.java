package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.VisionUsageLog;
import com.churchgeniuspro.repository.VisionUsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records and reports per-call OpenAI Vision/OCR usage.
 *
 * <p>Every Vision call (success, empty, error, rejected by quota/limits, or
 * disabled) is written to {@link VisionUsageLog} with the user, timestamp,
 * pages, token counts, an estimated USD cost and the outcome. On a successful
 * call it also bumps the per-church aggregate counter via {@link OpenAiUsageService}.
 *
 * <p>Cost is estimated from token counts using configurable per-1K rates
 * (defaults match GPT-4o list pricing):
 * <pre>
 *   openai.vision.cost.input-per-1k=0.0025
 *   openai.vision.cost.output-per-1k=0.01
 * </pre>
 */
@Service
public class VisionUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(VisionUsageTracker.class);

    private final VisionUsageLogRepository repo;
    private final OpenAiUsageService usage;

    @Value("${openai.vision.cost.input-per-1k:0.0025}")  private double inputCostPer1k;
    @Value("${openai.vision.cost.output-per-1k:0.01}")   private double outputCostPer1k;

    public VisionUsageTracker(VisionUsageLogRepository repo, OpenAiUsageService usage) {
        this.repo = repo;
        this.usage = usage;
    }

    /** Estimated USD cost for a token split. */
    public double estimateCost(int promptTokens, int completionTokens) {
        return (promptTokens / 1000.0) * inputCostPer1k
             + (completionTokens / 1000.0) * outputCostPer1k;
    }

    /**
     * Log one completed Vision extraction. Increments the church's aggregate Vision
     * counter only when the call actually reached OpenAI successfully.
     */
    @Transactional
    public VisionUsageLog record(String clientId, String username, String feature, int pages,
                                 VisionCheckService.VisionExtraction ext, int childrenFound) {
        VisionUsageLog row = new VisionUsageLog();
        row.setClientId(clientId);
        row.setUsername(username);
        row.setFeature(feature);
        row.setPages(Math.max(1, pages));
        row.setChildrenFound(Math.max(0, childrenFound));

        if (ext == null) {
            row.setStatus("ERROR");
            row.setErrorMessage("no extraction result");
        } else {
            row.setModel(ext.model);
            row.setPromptTokens(ext.promptTokens);
            row.setCompletionTokens(ext.completionTokens);
            row.setTotalTokens(ext.totalTokens > 0 ? ext.totalTokens : ext.promptTokens + ext.completionTokens);
            row.setEstimatedCost(round4(estimateCost(ext.promptTokens, ext.completionTokens)));
            if (!ext.enabled)      { row.setStatus("DISABLED"); row.setErrorMessage(trunc(ext.error)); }
            else if (ext.success)  { row.setStatus("SUCCESS"); }
            else                   { row.setStatus("ERROR"); row.setErrorMessage(trunc(ext.error)); }
        }

        VisionUsageLog saved = repo.save(row);
        if ("SUCCESS".equals(saved.getStatus())) {
            try { usage.incrementVisionUploads(clientId, saved.getPages()); }
            catch (Exception e) { log.warn("[Vision] could not bump aggregate counter: {}", e.toString()); }
        }
        return saved;
    }

    /** Log a call that never reached OpenAI because a quota/size/page limit blocked it. */
    @Transactional
    public VisionUsageLog recordRejected(String clientId, String username, String feature, String reason) {
        VisionUsageLog row = new VisionUsageLog();
        row.setClientId(clientId);
        row.setUsername(username);
        row.setFeature(feature);
        row.setStatus("REJECTED");
        row.setErrorMessage(trunc(reason));
        return repo.save(row);
    }

    /** Reporting payload for the admin "AI Scan Usage" view: summary totals + recent rows. */
    public Map<String, Object> report(String clientId, int limit) {
        int cap = Math.max(1, Math.min(limit, 500));
        List<VisionUsageLog> recent = repo.findByClientIdOrderByCreatedAtDesc(clientId, PageRequest.of(0, cap));

        long scans = recent.size(), ok = 0, failed = 0, rejected = 0;
        long pTok = 0, cTok = 0, tTok = 0, kids = 0;
        double cost = 0;
        for (VisionUsageLog r : recent) {
            switch (r.getStatus() == null ? "" : r.getStatus()) {
                case "SUCCESS"  -> ok++;
                case "REJECTED" -> rejected++;
                default         -> failed++;
            }
            pTok += r.getPromptTokens();
            cTok += r.getCompletionTokens();
            tTok += r.getTotalTokens();
            kids += r.getChildrenFound();
            cost += r.getEstimatedCost();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalScans", scans);
        summary.put("successful", ok);
        summary.put("failed", failed);
        summary.put("rejected", rejected);
        summary.put("promptTokens", pTok);
        summary.put("completionTokens", cTok);
        summary.put("totalTokens", tTok);
        summary.put("childrenExtracted", kids);
        summary.put("estimatedCost", round4(cost));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (VisionUsageLog r : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            m.put("username", r.getUsername());
            m.put("feature", r.getFeature());
            m.put("status", r.getStatus());
            m.put("pages", r.getPages());
            m.put("childrenFound", r.getChildrenFound());
            m.put("promptTokens", r.getPromptTokens());
            m.put("completionTokens", r.getCompletionTokens());
            m.put("totalTokens", r.getTotalTokens());
            m.put("estimatedCost", r.getEstimatedCost());
            m.put("model", r.getModel());
            m.put("error", r.getErrorMessage());
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("recent", rows);
        return out;
    }

    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
    private static String trunc(String s) { return s == null ? null : (s.length() > 480 ? s.substring(0, 480) : s); }
}
