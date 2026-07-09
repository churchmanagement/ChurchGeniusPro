package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PublicPageVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * Spring Data JPA repository for {@link PublicPageVisit} entities.
 */
@Repository
public interface PublicPageVisitRepository extends JpaRepository<PublicPageVisit, Long> {

    /** Per-page totals with the most recent visit instant: [page, count, maxVisitedAt]. */
    @Query("SELECT v.page, COUNT(v), MAX(v.visitedAt) FROM PublicPageVisit v GROUP BY v.page")
    List<Object[]> totalsByPage();

    /** Per-page counts since a given instant: [page, count]. */
    @Query("SELECT v.page, COUNT(v) FROM PublicPageVisit v WHERE v.visitedAt >= :since GROUP BY v.page")
    List<Object[]> countsByPageSince(@Param("since") Date since);
}
