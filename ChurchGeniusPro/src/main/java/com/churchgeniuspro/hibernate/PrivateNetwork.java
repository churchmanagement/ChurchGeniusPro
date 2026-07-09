package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * One approved church network (e.g. "Church Staff Wi-Fi").
 *
 * <p>Because a web server cannot see a client's Wi-Fi SSID, each network is
 * identified by the IP address(es) / CIDR range(s) it presents to the server —
 * a network's public egress IP or its internal subnet. {@code ipRanges} holds a
 * newline- or comma-separated list of IPv4 addresses (e.g. {@code 203.0.113.7})
 * and CIDR ranges (e.g. {@code 10.0.0.0/24}).
 */
@Data
@Entity
@Table(name = "private_network")
public class PrivateNetwork {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Friendly label, e.g. "Church Private Wi-Fi", "Children's Ministry Wi-Fi". */
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Newline/comma-separated IPv4 addresses and CIDR ranges that this network uses. */
    @Column(name = "ip_ranges", columnDefinition = "TEXT")
    private String ipRanges;

    @Column(name = "enabled", nullable = false, columnDefinition = "boolean not null default true")
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PrePersist
    void onCreate() { this.createdAt = new Date(); this.updatedAt = new Date(); }
    @PreUpdate
    void onUpdate() { this.updatedAt = new Date(); }
}
