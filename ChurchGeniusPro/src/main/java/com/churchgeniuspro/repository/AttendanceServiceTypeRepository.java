package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AttendanceServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceServiceTypeRepository extends JpaRepository<AttendanceServiceType, Long> {

    List<AttendanceServiceType> findByClientIdAndDeleteFlagFalseOrderBySortOrderAscNameAsc(String clientId);

    long countByClientIdAndDeleteFlagFalse(String clientId);

    Optional<AttendanceServiceType> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);
}
