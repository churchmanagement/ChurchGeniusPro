package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A member assigned to a specific instrument within a worship group.
 * Carries a rotation order used for automatic assignment.
 */
@Data
@Entity
@Table(name = "worship_group_member",
       indexes = {
           @Index(name = "idx_worship_group_member_instrument_id",
                  columnList = "instrument_id, delete_flag")
       })
public class WorshipGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "worship_group_member_seq")
    @SequenceGenerator(name = "worship_group_member_seq", sequenceName = "worship_group_member_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Parent instrument. */
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    /** Free-text member name (not linked to family_member to allow flexibility). */
    @Column(name = "member_name", nullable = false, length = 200)
    private String memberName;

    /**
     * Rotation order for automatic assignment.
     * Members with lower order numbers are assigned first.
     * Null = not included in automatic rotation.
     */
    @Column(name = "rotation_order")
    private Integer rotationOrder;

    /** Soft-delete flag. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
