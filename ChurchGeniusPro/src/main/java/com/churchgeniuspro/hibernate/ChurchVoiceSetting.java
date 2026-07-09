package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Per-church on/off configuration for the Voice features, managed by the
 * Service Administrator. Keyed by {@code client_id} (the church's
 * {@code service_client.client_id} / session {@code appClientId}).
 *
 * <p>All flags default to {@code true} so existing churches keep every voice
 * feature until an admin turns something off (backward compatible).
 *
 * <p>Dependency rules (enforced in the service, not here):
 * <ul>
 *   <li>If {@code voice} (parent) is off, every sub-feature is effectively off.</li>
 *   <li>If any of {@code voiceType}, {@code voiceCommand}, {@code converse} is off,
 *       then {@code voiceHelp} and {@code voiceDebug} are effectively off too.</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "church_voice_setting")
public class ChurchVoiceSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    /** Parent switch — when false, all voice sub-features are hidden/disabled. */
    @Column(name = "voice", nullable = false)
    private Boolean voice = true;

    @Column(name = "voice_type", nullable = false)
    private Boolean voiceType = true;

    @Column(name = "voice_command", nullable = false)
    private Boolean voiceCommand = true;

    @Column(name = "converse", nullable = false)
    private Boolean converse = true;

    @Column(name = "voice_help", nullable = false)
    private Boolean voiceHelp = true;

    @Column(name = "voice_debug", nullable = false)
    private Boolean voiceDebug = true;
}
