package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A member assigned to a specific instrument within a WorshipAssignment.
 * Multiple members per instrument per assignment are allowed.
 */
@Data
@Entity
@Table(name = "worship_assignment_member",
       indexes = {
           @Index(name = "idx_worship_assignment_member_assignment_id",
                  columnList = "assignment_id"),
           @Index(name = "idx_worship_assignment_member_instrument_id",
                  columnList = "instrument_id")
       })
public class WorshipAssignmentMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "worship_assignment_member_seq")
    @SequenceGenerator(name = "worship_assignment_member_seq", sequenceName = "worship_assignment_member_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Parent assignment. */
    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    /** Instrument within the group for this assignment slot. */
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    /** The member name assigned to this slot. */
    @Column(name = "member_name", nullable = false, length = 200)
    private String memberName;

    /** Display order within this instrument slot. */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** Tenant column (H2): backfilled by W4 from the parent; set on create by the owning
     *  service/controller. Nullable for now — flipped to NOT NULL once every create-path
     *  is deployed (see db/window/W4_tenant_columns.sql). */
    @Column(name = "client_id")
    private String clientId;
}
