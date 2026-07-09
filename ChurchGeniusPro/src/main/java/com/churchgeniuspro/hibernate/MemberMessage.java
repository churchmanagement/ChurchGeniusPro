package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Peer-to-peer inbox message between two member accounts.
 *
 * <p>A reply is simply a MemberMessage whose {@code parentId} points to the
 * root message of the thread.  Threads are kept shallow (one level only)
 * to keep queries simple.
 */
@Entity
@Table(name = "member_message")
public class MemberMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
                    generator  = "member_message_seq")
    @SequenceGenerator(name            = "member_message_seq",
                       sequenceName    = "member_message_seq",
                       allocationSize  = 1)
    private Long id;

    /** FamilyMember.id of the sender. */
    @Column(name = "sender_member_id", nullable = false)
    private Integer senderMemberId;

    /** Display name captured at send time so it survives member record changes. */
    @Column(name = "sender_name", nullable = false, length = 120)
    private String senderName;

    /** FamilyMember.id of the recipient. */
    @Column(name = "recipient_member_id", nullable = false)
    private Integer recipientMemberId;

    /** Plain-text or simple HTML message body. */
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** When the message was created. */
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    /** Set when the recipient first opens the message; null = unread. */
    @Column(name = "read_at")
    private Instant readAt;

    /**
     * For replies: the {@code id} of the root (parent) message.
     * Null for top-level messages.
     */
    @Column(name = "parent_id")
    private Long parentId;

    /** Multi-tenant scoping. */
    @Column(name = "app_client_id", length = 64)
    private String appClientId;

    /** Soft-delete: sender deleted from their Sent view. */
    @Column(name = "deleted_by_sender", nullable = false)
    private boolean deletedBySender = false;

    /** Soft-delete: recipient deleted from their Inbox. */
    @Column(name = "deleted_by_recipient", nullable = false)
    private boolean deletedByRecipient = false;

    @PrePersist
    void onCreate() {
        if (sentAt == null) sentAt = Instant.now();
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long getId()                          { return id; }
    public void setId(Long id)                   { this.id = id; }

    public Integer getSenderMemberId()           { return senderMemberId; }
    public void setSenderMemberId(Integer v)     { this.senderMemberId = v; }

    public String getSenderName()                { return senderName; }
    public void setSenderName(String v)          { this.senderName = v; }

    public Integer getRecipientMemberId()        { return recipientMemberId; }
    public void setRecipientMemberId(Integer v)  { this.recipientMemberId = v; }

    public String getBody()                      { return body; }
    public void setBody(String v)                { this.body = v; }

    public Instant getSentAt()                   { return sentAt; }
    public void setSentAt(Instant v)             { this.sentAt = v; }

    public Instant getReadAt()                   { return readAt; }
    public void setReadAt(Instant v)             { this.readAt = v; }

    public Long getParentId()                    { return parentId; }
    public void setParentId(Long v)              { this.parentId = v; }

    public String getAppClientId()               { return appClientId; }
    public void setAppClientId(String v)         { this.appClientId = v; }

    public boolean isDeletedBySender()           { return deletedBySender; }
    public void setDeletedBySender(boolean v)    { this.deletedBySender = v; }

    public boolean isDeletedByRecipient()        { return deletedByRecipient; }
    public void setDeletedByRecipient(boolean v) { this.deletedByRecipient = v; }
}
