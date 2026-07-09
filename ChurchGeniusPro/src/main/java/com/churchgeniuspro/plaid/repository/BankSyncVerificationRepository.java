package com.churchgeniuspro.plaid.repository;

import com.churchgeniuspro.plaid.entity.BankSyncVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankSyncVerificationRepository extends JpaRepository<BankSyncVerification, Integer> {

    /** Latest not-yet-used code for a user (most recent first). */
    Optional<BankSyncVerification> findFirstByAppUserIdAndUsedFalseOrderByIdDesc(Integer appUserId);

    List<BankSyncVerification> findByAppUserIdAndUsedFalse(Integer appUserId);

    /** Codes for a user filtered by used flag (used=false → active/unconsumed). */
    List<BankSyncVerification> findByAppUserIdAndUsed(Integer appUserId, boolean used);
}
