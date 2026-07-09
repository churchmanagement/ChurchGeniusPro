package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Hibernate entity for the {@code address} table.
 *
 * <p>{@code addressId} acts as a foreign key to either the
 * {@code InitialSetUp} table (Personal address) or the
 * {@code Church} table (Church / Office address), discriminated
 * by the {@code type} field.
 *
 * <p>One-to-Many relationship:
 * <ul>
 *   <li>One InitialSetUp  → Many Address rows (type = PERSONAL)</li>
 *   <li>One Church        → Many Address rows (type = OFFICE | CHURCH)</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "address")
public class Address {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "address_seq")
    @SequenceGenerator(name = "address_seq", sequenceName = "address_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Address Fields ────────────────────────────────────────────────────

    @Column(name = "name")
    private String name;

    @Column(name = "address1")
    private String address1;

    @Column(name = "address2")
    private String address2;

    @Column(name = "city")
    private String city;

    /** References a State lookup / code table. */
    @Column(name = "state")
    private Integer state;

    /** References a Country lookup / code table. */
    @Column(name = "country")
    private Integer country;

    @Column(name = "pin_code")
    private String pinCode;

    @Column(name = "delete_flag")
    private Boolean deleteFlag;

    // ── Relationship ──────────────────────────────────────────────────────

    /**
     * Foreign key to {@code InitialSetUp.id} when {@code type = PERSONAL},
     * or to {@code Church.id} when {@code type = OFFICE} or {@code CHURCH}.
     * Represents the "many" side of a One-to-Many association.
     */
    @Column(name = "address_id")
    private Integer addressId;

    /**
     * Discriminates which parent table {@code addressId} references:
     * <ul>
     *   <li>{@link AddressType#PERSONAL} → mapped from {@code InitialSetUp}</li>
     *   <li>{@link AddressType#OFFICE}   → mapped from {@code Church}</li>
     *   <li>{@link AddressType#CHURCH}   → mapped from {@code Church}</li>
     * </ul>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20)
    private AddressType type;

    // ── Address Type Enum ─────────────────────────────────────────────────

    public enum AddressType {
        PERSONAL,
        OFFICE,
        CHURCH
    }
}
