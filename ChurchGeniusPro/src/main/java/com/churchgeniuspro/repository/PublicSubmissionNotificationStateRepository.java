package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PublicSubmissionNotificationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublicSubmissionNotificationStateRepository
        extends JpaRepository<PublicSubmissionNotificationState, Long> {

    List<PublicSubmissionNotificationState> findByUserKeyAndNotificationIdIn(String userKey, Collection<Long> ids);

    Optional<PublicSubmissionNotificationState> findByNotificationIdAndUserKey(Long notificationId, String userKey);
}
