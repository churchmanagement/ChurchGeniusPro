package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PublicSubmissionNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PublicSubmissionNotificationRepository extends JpaRepository<PublicSubmissionNotification, Long> {

    /** Recent notifications of one church, newest first — always tenant-scoped. */
    @Query("SELECT n FROM PublicSubmissionNotification n WHERE n.clientId = :clientId "
         + "AND n.createdAt >= :since ORDER BY n.createdAt DESC")
    List<PublicSubmissionNotification> findRecent(@Param("clientId") String clientId,
                                                  @Param("since") Instant since,
                                                  Pageable page);

    /** One notification, only if it belongs to the given church. */
    Optional<PublicSubmissionNotification> findByIdAndClientId(Long id, String clientId);
}
