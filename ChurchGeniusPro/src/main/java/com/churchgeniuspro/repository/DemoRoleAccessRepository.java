package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DemoRoleAccessRepository extends JpaRepository<DemoRoleAccess, Long> {

    Optional<DemoRoleAccess> findBySignupId(Integer signupId);

    Optional<DemoRoleAccess> findByUsername(String username);

    List<DemoRoleAccess> findByClientId(String clientId);

    /**
     * Candidates for reminder evaluation: still usable, with a date to count down
     * to. Written as JPQL rather than a derived name — long derived names in this
     * area collide with Spring Data's own keywords (see the reminder-log repo).
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT a FROM DemoRoleAccess a
            WHERE a.blocked = false
              AND a.endDate IS NOT NULL
              AND a.endDate >= :from
           """)
    List<DemoRoleAccess> findCountdownCandidates(
            @org.springframework.data.repository.query.Param("from") LocalDate from);
}
