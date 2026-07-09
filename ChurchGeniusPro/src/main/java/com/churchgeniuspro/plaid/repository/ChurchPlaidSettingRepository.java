package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.ChurchPlaidSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChurchPlaidSettingRepository extends JpaRepository<ChurchPlaidSetting, Integer> {

    Optional<ChurchPlaidSetting> findByClientId(String clientId);
}
