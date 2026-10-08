package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.DemoClientSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoClientSettingsRepository extends JpaRepository<DemoClientSettings, String> {
}
