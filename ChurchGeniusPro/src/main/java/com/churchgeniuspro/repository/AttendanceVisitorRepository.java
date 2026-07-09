package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AttendanceVisitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceVisitorRepository extends JpaRepository<AttendanceVisitor, Long> {

    List<AttendanceVisitor> findByClientIdAndDeleteFlagFalseOrderByNameAsc(String clientId);

    Optional<AttendanceVisitor> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);

    Optional<AttendanceVisitor> findFirstByClientIdAndPhoneAndDeleteFlagFalse(String clientId, String phone);

    Optional<AttendanceVisitor> findFirstByClientIdAndEmailAndDeleteFlagFalse(String clientId, String email);
}
