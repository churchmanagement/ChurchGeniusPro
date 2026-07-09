package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PromiseVerse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromiseVerseRepository extends JpaRepository<PromiseVerse, Long> {

    /** All non-deleted verses for an organization, ordered by day number. */
    List<PromiseVerse> findByClientIdAndDeleteFlagFalseOrderByDayNumberAsc(String clientId);

    /** Check whether a day number is already taken for this org (excluding a given id on update). */
    boolean existsByClientIdAndDayNumberAndDeleteFlagFalse(String clientId, Integer dayNumber);

    @Query("SELECT v FROM PromiseVerse v WHERE v.clientId = :clientId AND v.dayNumber = :day AND v.deleteFlag = false")
    Optional<PromiseVerse> findByClientIdAndDayNumber(@Param("clientId") String clientId,
                                                       @Param("day") Integer day);

    /** Pick a random verse for the given organization. */
    @Query(value = "SELECT * FROM promise_verse WHERE client_id = :clientId AND delete_flag = false ORDER BY RANDOM() LIMIT 1",
           nativeQuery = true)
    Optional<PromiseVerse> findRandomByClientId(@Param("clientId") String clientId);
}
