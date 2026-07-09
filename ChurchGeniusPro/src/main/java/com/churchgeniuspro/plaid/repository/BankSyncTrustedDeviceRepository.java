package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.BankSyncTrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankSyncTrustedDeviceRepository extends JpaRepository<BankSyncTrustedDevice, Integer> {

    Optional<BankSyncTrustedDevice> findByAppUserIdAndTokenHash(Integer appUserId, String tokenHash);
}
