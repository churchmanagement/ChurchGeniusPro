package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Per-church (clientId) OpenAI usage limits and counters.
 *
 * <p>Tracks two independent quotas, both metered at the church/client level and
 * shared across every page that uses OpenAI:
 * <ul>
 *   <li><b>Voice commands</b> — {@code voice_used_seconds} accrues across the
 *       income / expense / home voice buttons; once it reaches
 *       {@code voice_limit_minutes} every voice button is disabled until a
 *       Service Admin resets it.</li>
 *   <li><b>Vision uploads</b> — {@code vision_used_uploads} accrues across the
 *       check / receipt / membership scanners; once it reaches
 *       {@code vision_limit_uploads} every OpenAI-Vision upload is disabled
 *       until reset. Regular (non-Vision) uploads are unaffected.</li>
 * </ul>
 *
 * <p>The remaining columns are cost-protection controls for the Vision pipeline
 * (max file size, max pages, resize target, JPEG quality), configurable per
 * church from the Service Admin page.
 */
@Data
@Entity
@Table(name = "openai_usage",
       uniqueConstraints = @UniqueConstraint(name = "uq_openai_usage_client", columnNames = {"client_id"}))
public class OpenAiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "openai_usage_seq")
    @SequenceGenerator(name = "openai_usage_seq", sequenceName = "openai_usage_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Church/client identifier — matches {@code service_client.client_id}. */
    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    // ── Voice command quota ──────────────────────────────────────────────────

    @Column(name = "voice_enabled", nullable = false)
    private Boolean voiceEnabled = true;

    /** Allowed voice minutes for this church (default 30). */
    @Column(name = "voice_limit_minutes", nullable = false)
    private Integer voiceLimitMinutes = 30;

    /** Voice seconds consumed so far (stored in seconds for precision). */
    @Column(name = "voice_used_seconds", nullable = false)
    private Long voiceUsedSeconds = 0L;

    // ── Vision upload quota ──────────────────────────────────────────────────

    @Column(name = "vision_enabled", nullable = false)
    private Boolean visionEnabled = true;

    /** Allowed OpenAI-Vision uploads for this church (default 1000). */
    @Column(name = "vision_limit_uploads", nullable = false)
    private Integer visionLimitUploads = 1000;

    /** Vision uploads consumed so far. */
    @Column(name = "vision_used_uploads", nullable = false)
    private Long visionUsedUploads = 0L;

    // ── Vision image cost-protection controls ────────────────────────────────

    @Column(name = "max_file_size_mb", nullable = false)
    private Integer maxFileSizeMb = 5;

    @Column(name = "max_pages_per_upload", nullable = false)
    private Integer maxPagesPerUpload = 2;

    @Column(name = "max_image_resolution_px", nullable = false)
    private Integer maxImageResolutionPx = 2000;

    @Column(name = "auto_resize_images", nullable = false)
    private Boolean autoResizeImages = true;

    @Column(name = "jpeg_quality", nullable = false)
    private Integer jpegQuality = 85;

    @PrePersist
    void onCreate() {
        if (voiceEnabled == null)         voiceEnabled = true;
        if (voiceLimitMinutes == null)    voiceLimitMinutes = 30;
        if (voiceUsedSeconds == null)     voiceUsedSeconds = 0L;
        if (visionEnabled == null)        visionEnabled = true;
        if (visionLimitUploads == null)   visionLimitUploads = 1000;
        if (visionUsedUploads == null)    visionUsedUploads = 0L;
        if (maxFileSizeMb == null)        maxFileSizeMb = 5;
        if (maxPagesPerUpload == null)    maxPagesPerUpload = 2;
        if (maxImageResolutionPx == null) maxImageResolutionPx = 2000;
        if (autoResizeImages == null)     autoResizeImages = true;
        if (jpegQuality == null)          jpegQuality = 85;
    }
}
