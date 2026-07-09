package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A named worship team group (e.g. "Group English", "Group Mal").
 * One record per group per organization.
 */
@Data
@Entity
@Table(name = "worship_group")
public class WorshipGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "worship_group_seq")
    @SequenceGenerator(name = "worship_group_seq", sequenceName = "worship_group_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization identifier for multi-tenant isolation. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Display name of the group. */
    @Column(name = "group_name", nullable = false, length = 200)
    private String groupName;

    /** Soft-delete flag. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
