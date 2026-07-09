package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A pledge campaign — e.g. "Building Fund 2026" — that members can
 * commit pledges against. The optional {@link #subSourceId} ties the
 * campaign to a Fund (income.sub_source) so that incoming donations
 * keyed to that fund can be auto-applied to a member's pledge balance.
 */
@Data
@Entity
@Table(name = "pledge_campaign")
public class PledgeCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "pledge_campaign_seq")
    @SequenceGenerator(name = "pledge_campaign_seq", sequenceName = "pledge_campaign_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** Org owner — matches Income.appClientId. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Fund / SubSource this campaign drives donations into. Nullable so
     *  pledges can exist without a backing income fund. */
    @Column(name = "sub_source_id")
    private Integer subSourceId;

    /** Free-text "Other Gifts / Services" — e.g. "Volunteer Service". */
    @Column(name = "other_gifts", length = 500)
    private String otherGifts;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Optional target — when null, campaign has no fundraising goal. */
    @Column(name = "target_amount", precision = 15, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** Active / Closed / Completed. Stored as text so admins can extend. */
    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
        if (status == null || status.isBlank()) status = "Active";
    }
}
