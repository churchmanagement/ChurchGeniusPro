package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PledgeMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PledgeMemberRepository extends JpaRepository<PledgeMember, Integer> {

    @Query("SELECT p FROM PledgeMember p " +
           "WHERE p.clientId = :clientId AND p.campaignId = :campaignId AND p.deleteFlag = false " +
           "ORDER BY p.createdDate DESC")
    List<PledgeMember> findByCampaign(@Param("clientId") String clientId,
                                      @Param("campaignId") Integer campaignId);

    Optional<PledgeMember> findByIdAndClientId(Integer id, String clientId);

    /**
     * Look up active pledges by member id (across every campaign) — used by
     * the Member Portal Contributions tab and the Income auto-allocate hook.
     */
    @Query("SELECT p FROM PledgeMember p " +
           "WHERE p.clientId = :clientId AND p.familyMemberId = :memberId AND p.deleteFlag = false")
    List<PledgeMember> findByMember(@Param("clientId") String clientId,
                                    @Param("memberId") Integer memberId);

    /**
     * Allocate target: a single PledgeMember row keyed by (campaign, member),
     * used by the Income auto-allocate hook to credit the right pledge.
     */
    @Query("SELECT p FROM PledgeMember p " +
           "WHERE p.clientId = :clientId AND p.campaignId = :campaignId " +
           "AND p.familyMemberId = :memberId AND p.deleteFlag = false")
    Optional<PledgeMember> findByCampaignAndMember(
            @Param("clientId") String clientId,
            @Param("campaignId") Integer campaignId,
            @Param("memberId") Integer memberId);
}
