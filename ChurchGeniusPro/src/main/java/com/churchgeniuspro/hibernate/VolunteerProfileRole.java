package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Links a volunteer profile to one of the church's named roles.
 * A single volunteer may hold multiple roles (Singer + Media Team, etc.),
 * and multiple volunteers may share the same role.
 *
 * The unique constraint on (app_client_id, volunteer_profile_id, role_id)
 * prevents duplicate assignments.
 */
@Data
@Entity
@Table(
    name = "volunteer_profile_role",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_vol_profile_role",
        columnNames = {"app_client_id", "volunteer_profile_id", "role_id"}
    )
)
public class VolunteerProfileRole {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "volunteer_profile_role_seq")
    @SequenceGenerator(name = "volunteer_profile_role_seq",
                       sequenceName = "volunteer_profile_role_id_seq",
                       allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** FK → volunteer_profile.id */
    @Column(name = "volunteer_profile_id", nullable = false)
    private Long volunteerProfileId;

    /** FK → volunteer_role.id */
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
