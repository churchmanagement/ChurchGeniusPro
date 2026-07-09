package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MemberMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for peer-to-peer member messages.
 */
@Repository
public interface MemberMessageRepository extends JpaRepository<MemberMessage, Long> {

    /**
     * Inbox: top-level (non-reply) messages sent TO this member, not deleted by them,
     * newest first.
     */
    @Query("SELECT m FROM MemberMessage m " +
           "WHERE m.recipientMemberId = :memberId " +
           "  AND m.parentId IS NULL " +
           "  AND m.deletedByRecipient = false " +
           "ORDER BY m.sentAt DESC")
    List<MemberMessage> findInbox(@Param("memberId") Integer memberId);

    /**
     * Sent: top-level messages sent BY this member, not deleted by them, newest first.
     */
    @Query("SELECT m FROM MemberMessage m " +
           "WHERE m.senderMemberId = :memberId " +
           "  AND m.parentId IS NULL " +
           "  AND m.deletedBySender = false " +
           "ORDER BY m.sentAt DESC")
    List<MemberMessage> findSent(@Param("memberId") Integer memberId);

    /**
     * Thread: the root message plus all its replies, ordered oldest-first.
     * Returns messages visible to the given member (either sender or recipient).
     */
    @Query("SELECT m FROM MemberMessage m " +
           "WHERE (m.id = :rootId OR m.parentId = :rootId) " +
           "  AND (m.senderMemberId = :memberId OR m.recipientMemberId = :memberId) " +
           "ORDER BY m.sentAt ASC")
    List<MemberMessage> findThread(@Param("rootId") Long rootId,
                                   @Param("memberId") Integer memberId);

    /** Count of unread inbox messages for a member (top-level only, matching what the inbox shows). */
    @Query("SELECT COUNT(m) FROM MemberMessage m " +
           "WHERE m.recipientMemberId = :memberId " +
           "  AND m.parentId IS NULL " +
           "  AND m.readAt IS NULL " +
           "  AND m.deletedByRecipient = false")
    long countUnread(@Param("memberId") Integer memberId);
}
