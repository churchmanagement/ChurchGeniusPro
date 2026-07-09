package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrivateAccessSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PrivateAccessSettingRepository extends JpaRepository<PrivateAccessSetting, Long> {
    Optional<PrivateAccessSetting> findByClientId(String clientId);
}
