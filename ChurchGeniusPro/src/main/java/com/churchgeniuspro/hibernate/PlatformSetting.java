package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One platform-wide (not per-church) setting the Service Admin can edit, stored as a
 * key/value row. Introduced for the Support Email and the public Trial Request link;
 * see {@code PlatformSettingService} for the keys and their defaults. Hibernate
 * {@code ddl-auto=update} creates the table from this entity.
 */
@Data
@Entity
@Table(name = "platform_setting")
public class PlatformSetting {

    @Id
    @Column(name = "setting_key", nullable = false, length = 80)
    private String key;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
