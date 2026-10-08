package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.DemoReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface DemoReminderLogRepository extends JpaRepository<DemoReminderLog, Long> {

    /**
     * Explicit JPQL, not a derived name, on purpose: Spring Data parses
     * "...AndDaysBefore..." as the property {@code days} plus its {@code Before}
     * keyword (the less-than operator for dates), and refuses to start. Any
     * derived query naming this field would hit the same wall.
     */
    @Query("""
           SELECT COUNT(l) > 0 FROM DemoReminderLog l
            WHERE l.roleAccessId = :roleAccessId
              AND l.daysBefore   = :daysBefore
              AND l.forEndDate   = :forEndDate
           """)
    boolean alreadySent(@Param("roleAccessId") Long roleAccessId,
                        @Param("daysBefore")   Integer daysBefore,
                        @Param("forEndDate")   LocalDate forEndDate);
}
