package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.util.Date;

/**
 * Represents a child source that belongs to a {@link MainSource}.
 * Mapped to the {@code sub_source} table.
 */
@Data
@Entity
@Table(name = "sub_source")
public class SubSource {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sub_source_seq")
    @SequenceGenerator(name = "sub_source_seq", sequenceName = "sub_source_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "main_source_id", nullable = false)
    private MainSource mainSource;

    /** Optional org identifier from the app_user who created this record. Null for church-level accounts. */
    @Column(name = "app_client_id")
    private String appClientId;

    /**
     * Whether income filed under this sub-source counts as a tax-deductible
     * charitable gift on the Year-End Tax Report and a member's own giving
     * statement. Defaults true so every sub-source that existed before this
     * flag was added keeps appearing on those statements exactly as it
     * always did; a bookkeeper turns specific non-gift categories (facility
     * rental, bookstore sales, event fees...) off from here. Financial
     * audit H9.
     *
     * <p>{@code @ColumnDefault} is load-bearing: ddl-auto cannot add a NOT
     * NULL column to a table that already has rows without one.
     */
    @Column(name = "tax_deductible", nullable = false)
    @ColumnDefault("true")
    private boolean taxDeductible = true;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
    }
}
