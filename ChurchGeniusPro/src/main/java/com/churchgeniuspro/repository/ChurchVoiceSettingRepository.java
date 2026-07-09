package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ChurchVoiceSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Per-church Voice feature settings. */
@Repository
public interface ChurchVoiceSettingRepository extends JpaRepository<ChurchVoiceSetting, Long> {
    Optional<ChurchVoiceSetting> findByClientId(String clientId);
}
