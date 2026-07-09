package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * An instrument (or role) within a worship group (e.g. "Guitar", "Singer", "Drums").
 */
@Data
@Entity
@Table(name = "worship_instrument",
       indexes = {
           @Index(name = "idx_worship_instrument_group_id",
                  columnList = "group_id, delete_flag")
       })
public class WorshipInstrument {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "worship_instrument_seq")
    @SequenceGenerator(name = "worship_instrument_seq", sequenceName = "worship_instrument_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Parent group. */
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /** Instrument / role name. */
    @Column(name = "instrument_name", nullable = false, length = 200)
    private String instrumentName;

    /** Soft-delete flag. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
