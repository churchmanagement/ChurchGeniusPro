package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PushNotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface PushNotificationLogRepository extends JpaRepository<PushNotificationLog, Long> {

    /**
     * Count unread (readAt = null) notifications for a user.
     * Used to populate the badge count and topbar bell indicator.
     */
    @Query("SELECT COUNT(p) FROM PushNotificationLog p WHERE p.userKey = :userKey AND p.readAt IS NULL")
    long countUnread(@Param("userKey") String userKey);

    /**
     * Fetch the most recent N notifications for a user (read + unread),
     * ordered newest first. Used for the topbar notification dropdown.
     */
    @Query("SELECT p FROM PushNotificationLog p WHERE p.userKey = :userKey ORDER BY p.sentAt DESC")
    List<PushNotificationLog> findRecentByUserKey(@Param("userKey") String userKey,
                                                   org.springframework.data.domain.Pageable pageable);

    /**
     * Fetch notifications for a user sent on or after {@code since} (start of today),
     * ordered newest first. Used to show only today's and future notifications.
     */
    @Query("SELECT p FROM PushNotificationLog p WHERE p.userKey = :userKey AND p.sentAt >= :since ORDER BY p.sentAt DESC")
    List<PushNotificationLog> findTodayAndFutureByUserKey(@Param("userKey") String userKey,
                                                           @Param("since") Instant since,
                                                           org.springframework.data.domain.Pageable pageable);

    /**
     * Mark all unread notifications as read for a given user.
     * Called when the user opens the notification dropdown or taps a notification.
     */
    @Transactional
    @Modifying
    @Query("UPDATE PushNotificationLog p SET p.readAt = :now WHERE p.userKey = :userKey AND p.readAt IS NULL")
    int markAllRead(@Param("userKey") String userKey, @Param("now") Instant now);

    /**
     * Mark a single notification as read by its ID.
     */
    @Transactional
    @Modifying
    @Query("UPDATE PushNotificationLog p SET p.readAt = :now WHERE p.id = :id AND p.readAt IS NULL")
    int markReadById(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Check whether a duplicate push was already sent to this user with the same tag
     * within the last N seconds (deduplication guard for high-frequency schedulers).
     */
    @Query("SELECT COUNT(p) FROM PushNotificationLog p WHERE p.userKey = :userKey AND p.tag = :tag AND p.sentAt > :since")
    long countRecentByTag(@Param("userKey") String userKey,
                          @Param("tag") String tag,
                          @Param("since") Instant since);
}
