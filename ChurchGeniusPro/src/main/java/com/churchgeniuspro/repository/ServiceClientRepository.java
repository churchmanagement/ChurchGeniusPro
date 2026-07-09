package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ServiceClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ServiceClientRepository extends JpaRepository<ServiceClient, Integer> {

    List<ServiceClient> findAllByDeleteFlagFalseOrderByIdDesc();

    Optional<ServiceClient> findByClientIdAndStatusAndDeleteFlagFalse(String clientId, String status);

    Optional<ServiceClient> findByClientId(String clientId);

    /**
     * Returns true if the service_client row for the given clientId is active:
     * status = 'Active', delete_flag = false, and end_date >= today.
     */
    @Query("SELECT COUNT(sc) > 0 FROM ServiceClient sc " +
           "WHERE sc.clientId = :clientId " +
           "AND sc.status = 'Active' " +
           "AND sc.deleteFlag = false " +
           "AND sc.endDate >= :today")
    boolean isActiveSubscription(@Param("clientId") String clientId,
                                 @Param("today") LocalDate today);
}
