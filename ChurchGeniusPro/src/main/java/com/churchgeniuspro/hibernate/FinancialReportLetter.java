package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * One church's wording for the giving-statement letter.
 *
 * <p>The Financial Report is a letter wrapped around a contribution table: a
 * paragraph above it and a tax notice plus closing paragraph below. Those were
 * literals in two page scripts, so every church sent the same words. This row
 * holds one church's replacement for them.
 *
 * <p>At most one row per tenant. <b>No row is the normal state</b> — a church
 * that has never edited the letter has nothing here, and
 * {@code FinancialReportLetterService} serves the built-in default, which is the
 * wording the letter has always had. That is why no back-fill was needed and why
 * a report can never come out blank.
 *
 * <p>{@link #introHtml} and {@link #closingHtml} are rich text, already reduced to
 * a safe subset by {@code RichTextSanitizer} before they were stored — they are
 * written into a member's page as HTML, so nothing unsanitised may reach this
 * table. They may contain {@code {ChurchName}} and {@code {Year}}, substituted
 * when the letter is rendered.
 */
@Data
@Entity
@Table(name = "financial_report_letter")
public class FinancialReportLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "financial_report_letter_seq")
    @SequenceGenerator(
            name           = "financial_report_letter_seq",
            sequenceName   = "financial_report_letter_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** The owning tenant. Every read and write is keyed on this. */
    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** Rich text shown above the contribution table. */
    @Column(name = "intro_html", columnDefinition = "TEXT")
    private String introHtml;

    /** Rich text shown below the contribution table (tax notice + closing). */
    @Column(name = "closing_html", columnDefinition = "TEXT")
    private String closingHtml;

    /**
     * Signature block under the closing. Null or blank means the default — the
     * church's registered name and "Finance Department" — so existing churches
     * need nothing set. Plain text, escaped at render.
     */
    @Column(name = "signature_name", length = 200)
    private String signatureName;

    @Column(name = "signature_title", length = 200)
    private String signatureTitle;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "updated_date")
    private Date updatedDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        Date now = new Date();
        this.createdDate = now;
        this.updatedDate = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedDate = new Date();
    }
}
