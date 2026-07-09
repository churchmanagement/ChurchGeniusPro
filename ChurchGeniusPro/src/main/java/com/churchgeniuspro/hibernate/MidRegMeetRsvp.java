package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * One RSVP submission for the Midwest Region Meet.
 * The {@code edit_token} field enables token-based edit links sent in confirmation emails/SMS.
 */
@Data
@Entity
@Table(name = "mid_reg_meet_rsvp")
public class MidRegMeetRsvp {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mid_reg_meet_rsvp_seq")
    @SequenceGenerator(name = "mid_reg_meet_rsvp_seq", sequenceName = "mid_reg_meet_rsvp_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Links back to the event org — same as the MidRegMeet client_id. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    // ── Personal details ──────────────────────────────────────────────────

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "church", length = 200)
    private String church;

    @Column(name = "email", nullable = false, length = 200)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "adults")
    private Integer adults;

    @Column(name = "children")
    private Integer children;

    // ── RSVP options (Yes / Maybe / No) ──────────────────────────────────

    /** Sunday RSVP: Yes / Maybe / No */
    @Column(name = "rsvp_sunday", length = 10)
    private String rsvpSunday;

    /** Saturday Mission Work RSVP: Yes / Maybe / No */
    @Column(name = "rsvp_saturday_mission", length = 10)
    private String rsvpSaturdayMission;

    /** Saturday Music Night RSVP: Yes / Maybe / No */
    @Column(name = "rsvp_saturday_music", length = 10)
    private String rsvpSaturdayMusic;

    /**
     * JSON array of music participation options selected:
     * e.g. ["sing","perform","needInstruments"]
     */
    @Column(name = "music_options", columnDefinition = "TEXT")
    private String musicOptions;

    // ── T-shirt selections ────────────────────────────────────────────────

    /**
     * JSON map of size → quantity:
     * e.g. {"XS":0,"S":1,"M":2,"L":0,"XL":1}
     */
    @Column(name = "tshirt_sizes", columnDefinition = "TEXT")
    private String tshirtSizes;

    // ── Note ─────────────────────────────────────────────────────────────

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    // ── Token-based edit link ─────────────────────────────────────────────

    /**
     * Unique token embedded in the confirmation email/SMS edit link.
     * Format: {@code MRM<uuid>}. Auto-generated on first insert.
     */
    @Column(name = "edit_token", unique = true, length = 100)
    private String editToken;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = new Date();
        this.updatedAt = new Date();
        if (this.editToken == null) {
            this.editToken = "MRM" + java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Date();
    }
}
