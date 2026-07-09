package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

@Data
@Entity
@Table(name = "signup")
public class SignUp {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "signup_seq")
    @SequenceGenerator(name = "signup_seq", sequenceName = "signup_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    /**
     * Plaintext copy of the originally generated password, stored ONLY for
     * demo/test tenants so the Service Admin "Demo Tenant Credentials" table
     * can display the real, usable login password after a page reload (the
     * {@link #password} column holds only the one-way BCrypt hash). Always
     * {@code null} for real user accounts — never populated by the normal
     * signup/registration flows.
     */
    @Column(name = "demo_password")
    private String demoPassword;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "deleted")
    private Boolean deleted;

    @Column(name = "church_id")
    private Integer churchId;

    @Column(name = "church")
    private Boolean church;

    @Column(name = "created", updatable = false)
    private Date created;

    @Column(name = "updated")
    private Date updated;

    @Column(name = "locked")
    private Boolean locked;

    @Column(name = "remember")
    private String remember;

    /**
     * Shared link-group UUID.  When this signup record belongs to a cross-type
     * account link (e.g. a member portal account linked with staff accounts), all
     * members of that link-group share the same non-null UUID here.
     * Set to {@code null} to remove from any link-group.
     */
    @Column(name = "link_group")
    private String linkGroup;
}
