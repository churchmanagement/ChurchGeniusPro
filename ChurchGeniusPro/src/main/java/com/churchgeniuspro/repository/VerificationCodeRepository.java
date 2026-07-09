package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Integer> {

    Optional<VerificationCode> findByClientIdAndType(String clientId, String type);

    @Modifying
    @Transactional
    @Query("DELETE FROM VerificationCode v WHERE v.clientId = :clientId AND v.type = :type")
    void deleteByClientIdAndType(@Param("clientId") String clientId, @Param("type") String type);

    @Modifying
    @Transactional
    @Query("DELETE FROM VerificationCode v WHERE v.expiresAt < :now")
    void deleteExpiredBefore(@Param("now") LocalDateTime now);
}
