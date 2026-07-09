package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Join row for the many-to-many between {@link PrayerRequest} and
 * {@link PrayerVolunteer}.
 *
 * <p>The legacy {@code prayer_request.assigned_volunteer_id} single-FK is
 * still kept for backward compatibility — it tracks the most recent
 * assignment. This table is the authoritative list of every volunteer on
 * a request, supporting one-or-many assignment, removal, and reassignment.
 *
 * <p>Soft delete is not needed: the natural unit of "unassign" is to delete
 * the join row, so the history of who-was-assigned-when is implicit in the
 * notes thread rather than the join table.
 */
@Data
@Entity
@Table(name = "prayer_request_volunteer",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_prv_request_volunteer",
                             columnNames = { "prayer_request_id", "prayer_volunteer_id" })
       })
public class PrayerRequestVolunteer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prayer_request_volunteer_seq")
    @SequenceGenerator(name = "prayer_request_volunteer_seq",
                       sequenceName = "prayer_request_volunteer_id_seq",
                       allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "prayer_request_id", nullable = false)
    private Long prayerRequestId;

    @Column(name = "prayer_volunteer_id", nullable = false)
    private Integer prayerVolunteerId;

    @Column(name = "created_date")
    private Date createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = new Date();
    }
}
