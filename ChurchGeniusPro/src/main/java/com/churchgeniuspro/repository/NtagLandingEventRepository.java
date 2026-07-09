package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.NtagLandingEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NtagLandingEventRepository extends JpaRepository<NtagLandingEvent, Long> {

    long countByClientIdAndType(String clientId, String type);

    /** Click totals per button: rows of [buttonKey, buttonLabel, count]. */
    @Query("SELECT e.buttonKey, e.buttonLabel, COUNT(e) FROM NtagLandingEvent e " +
           "WHERE e.clientId = :clientId AND e.type = 'click' " +
           "GROUP BY e.buttonKey, e.buttonLabel ORDER BY COUNT(e) DESC")
    List<Object[]> clickTotals(@Param("clientId") String clientId);

    List<NtagLandingEvent> findByClientIdOrderByCreatedAtDesc(String clientId, Pageable pageable);
}
