package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AiConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** {@code ai_consent} — every read is scoped to one church AND one login. */
@Repository
public interface AiConsentRepository extends JpaRepository<AiConsent, Long> {

    Optional<AiConsent> findByAppClientIdAndLoginIdAndNoticeType(String appClientId, Integer loginId, String noticeType);

    List<AiConsent> findByAppClientIdAndLoginId(String appClientId, Integer loginId);
}
