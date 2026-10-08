package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmAuthorizedPickup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KmAuthorizedPickupRepository extends JpaRepository<KmAuthorizedPickup, Long> {

    List<KmAuthorizedPickup> findByClientIdAndChildIdAndDeleteFlagFalseOrderByPersonNameAsc(
            String clientId, Long childId);

    void deleteByChildId(Long childId);

    /** Tenant-scoped variant: never clears another church's pickup list. */
    void deleteByClientIdAndChildId(String clientId, Long childId);
}
