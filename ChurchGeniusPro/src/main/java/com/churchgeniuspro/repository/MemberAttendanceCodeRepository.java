package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MemberAttendanceCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberAttendanceCodeRepository extends JpaRepository<MemberAttendanceCode, Long> {

    Optional<MemberAttendanceCode> findByClientIdAndFamilyMemberId(String clientId, Integer familyMemberId);

    Optional<MemberAttendanceCode> findByCode(String code);
}
