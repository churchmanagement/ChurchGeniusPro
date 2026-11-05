package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MemberEngagementSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MemberEngagementSnapshotRepository extends JpaRepository<MemberEngagementSnapshot, Long> {

    boolean existsByAppClientIdAndSnapshotDate(String appClientId, LocalDate snapshotDate);

    long countByAppClientIdAndSnapshotDate(String appClientId, LocalDate snapshotDate);

    List<MemberEngagementSnapshot> findByAppClientIdAndSnapshotDate(String appClientId, LocalDate snapshotDate);

    /** Latest snapshot day the church has. */
    @Query("SELECT MAX(s.snapshotDate) FROM MemberEngagementSnapshot s WHERE s.appClientId = :cid")
    LocalDate latestDate(@Param("cid") String cid);

    /** Latest snapshot day on or before a date (for a month-end report). */
    @Query("SELECT MAX(s.snapshotDate) FROM MemberEngagementSnapshot s WHERE s.appClientId = :cid AND s.snapshotDate <= :onOrBefore")
    LocalDate latestDateOnOrBefore(@Param("cid") String cid, @Param("onOrBefore") LocalDate onOrBefore);

    Optional<MemberEngagementSnapshot> findFirstByAppClientIdAndMemberIdOrderBySnapshotDateDesc(String appClientId, Integer memberId);

    /** signal → count for one church and day. */
    @Query("SELECT s.signal, COUNT(s) FROM MemberEngagementSnapshot s WHERE s.appClientId = :cid AND s.snapshotDate = :day GROUP BY s.signal")
    List<Object[]> countBySignal(@Param("cid") String cid, @Param("day") LocalDate day);

    @Modifying
    @Query("DELETE FROM MemberEngagementSnapshot s WHERE s.appClientId = :cid AND s.snapshotDate = :day")
    int deleteDay(@Param("cid") String cid, @Param("day") LocalDate day);

    @Modifying
    @Query(value = "DELETE FROM member_engagement_snapshot WHERE id IN (SELECT id FROM member_engagement_snapshot WHERE snapshot_date < :before LIMIT :batch)", nativeQuery = true)
    int purgeBefore(@Param("before") LocalDate before, @Param("batch") int batch);
}
