package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PledgeCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PledgeCampaignRepository extends JpaRepository<PledgeCampaign, Integer> {

    @Query("SELECT c FROM PledgeCampaign c " +
           "WHERE c.clientId = :clientId AND c.deleteFlag = false " +
           "ORDER BY CASE WHEN c.status = 'Active' THEN 0 ELSE 1 END, c.createdDate DESC")
    List<PledgeCampaign> findByClientId(@Param("clientId") String clientId);

    Optional<PledgeCampaign> findByIdAndClientId(Integer id, String clientId);

    /**
     * Active campaigns that pledge against the supplied fund. Used by the
     * Income auto-allocate hook to find which campaign should consume a
     * newly recorded contribution.
     */
    @Query("SELECT c FROM PledgeCampaign c " +
           "WHERE c.clientId = :clientId AND c.deleteFlag = false " +
           "AND c.status = 'Active' AND c.subSourceId = :subSourceId")
    List<PledgeCampaign> findActiveByClientIdAndSubSource(
            @Param("clientId") String clientId,
            @Param("subSourceId") Integer subSourceId);
}
