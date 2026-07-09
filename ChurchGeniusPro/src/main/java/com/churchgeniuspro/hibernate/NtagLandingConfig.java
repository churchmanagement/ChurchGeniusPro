package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Per-church configuration for the public NTAG landing page (a branded
 * "link-in-bio" page opened when a visitor taps an NTAG tag). One row per client.
 *
 * <p>The button list is stored as JSON in {@code buttonsJson} — an array of
 * {@code {key,label,icon,url,enabled,order}} objects the admin can customize.
 */
@Data
@Entity
@Table(name = "ntag_landing_config")
public class NtagLandingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;

    /** Brand/accent color (hex). Defaults to the app brand. */
    @Column(name = "theme_color", length = 20)
    private String themeColor = "#673147";

    /** Optional banner image (base64 data URL). When absent the logo + color header is shown. */
    @Column(name = "banner_image", columnDefinition = "TEXT")
    private String bannerImage;

    @Column(name = "show_logo", nullable = false, columnDefinition = "boolean not null default true")
    private boolean showLogo = true;

    @Column(name = "enabled", nullable = false, columnDefinition = "boolean not null default true")
    private boolean enabled = true;

    /** JSON array of buttons: [{key,label,icon,url,enabled,order}]. */
    @Column(name = "buttons_json", columnDefinition = "TEXT")
    private String buttonsJson;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { this.updatedAt = LocalDateTime.now(); }
}
