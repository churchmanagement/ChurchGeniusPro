package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * A role assigned to a specific event volunteer.
 * One volunteer can have multiple rows (multiple roles).
 */
@Data
@Entity
@Table(name = "event_volunteer_role")
public class EventVolunteerRole {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "event_volunteer_role_seq")
    @SequenceGenerator(name = "event_volunteer_role_seq", sequenceName = "event_volunteer_role_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** Links to EventVolunteer.id */
    @Column(name = "event_volunteer_id", nullable = false)
    private Long eventVolunteerId;

    /** Role name — free text or chosen from the org's VolunteerRole list */
    @Column(name = "role_name", nullable = false, length = 200)
    private String roleName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
