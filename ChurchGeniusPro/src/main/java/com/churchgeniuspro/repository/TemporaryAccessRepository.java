package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.TemporaryAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TemporaryAccessRepository extends JpaRepository<TemporaryAccess, Long> {

    Optional<TemporaryAccess> findByBarcodeValueAndDeleteFlagFalse(String barcodeValue);

    boolean existsByBarcodeValue(String barcodeValue);

    Optional<TemporaryAccess> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);

    List<TemporaryAccess> findByClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(String clientId);
}
