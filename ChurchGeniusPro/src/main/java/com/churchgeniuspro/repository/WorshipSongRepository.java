package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorshipSong;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WorshipSongRepository extends JpaRepository<WorshipSong, Long> {

    /** All songs (and headings) for a specific group+date, ordered by sort_order. */
    List<WorshipSong> findByClientIdAndGroupIdAndServiceDateAndDeleteFlagFalseOrderBySortOrderAsc(
            String clientId, Long groupId, LocalDate serviceDate);

    /** All songs for a client+group across a date range. */
    List<WorshipSong> findByClientIdAndGroupIdAndServiceDateBetweenAndDeleteFlagFalseOrderByServiceDateAscSortOrderAsc(
            String clientId, Long groupId, LocalDate from, LocalDate to);

    /** Bulk-delete all songs for a group+date (used during full replace). */
    @Modifying
    @Transactional
    @Query("DELETE FROM WorshipSong s WHERE s.clientId = :clientId AND s.groupId = :groupId AND s.serviceDate = :serviceDate")
    void deleteByClientIdAndGroupIdAndServiceDate(
            @Param("clientId") String clientId,
            @Param("groupId") Long groupId,
            @Param("serviceDate") LocalDate serviceDate);

    /** Group-level library songs (serviceDate IS NULL). */
    List<WorshipSong> findByClientIdAndGroupIdAndServiceDateIsNullAndDeleteFlagFalseOrderBySortOrderAsc(
            String clientId, Long groupId);

    /** Bulk-delete library songs for a group. */
    @Modifying
    @Transactional
    @Query("DELETE FROM WorshipSong s WHERE s.clientId = :clientId AND s.groupId = :groupId AND s.serviceDate IS NULL")
    void deleteLibraryByClientIdAndGroupId(
            @Param("clientId") String clientId,
            @Param("groupId") Long groupId);
}
