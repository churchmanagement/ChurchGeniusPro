package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.McpTenantSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** The church's MCP opt-in row (AI Phase 5). */
public interface McpTenantSettingRepository extends JpaRepository<McpTenantSetting, Long> {
    Optional<McpTenantSetting> findByAppClientId(String appClientId);
}
