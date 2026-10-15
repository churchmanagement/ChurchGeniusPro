package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MemberBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MemberBlockRepository extends JpaRepository<MemberBlock, Long> {

    List<MemberBlock> findByAppClientIdAndBlockerMemberIdOrderByCreatedAtDesc(String appClientId, Integer blockerMemberId);

    Optional<MemberBlock> findByAppClientIdAndBlockerMemberIdAndBlockedMemberId(String appClientId, Integer blockerMemberId, Integer blockedMemberId);

    boolean existsByAppClientIdAndBlockerMemberIdAndBlockedMemberId(String appClientId, Integer blockerMemberId, Integer blockedMemberId);

    /** Ids of the members {@code blockerMemberId} has blocked (for inbox filtering). */
    @Query("SELECT b.blockedMemberId FROM MemberBlock b WHERE b.appClientId = :appClientId AND b.blockerMemberId = :blockerMemberId")
    List<Integer> blockedIds(@Param("appClientId") String appClientId, @Param("blockerMemberId") Integer blockerMemberId);

    /** Account deletion: every block the member placed or is the subject of. */
    @Transactional
    @Modifying
    @Query("DELETE FROM MemberBlock b WHERE b.appClientId = :appClientId AND (b.blockerMemberId = :memberId OR b.blockedMemberId = :memberId)")
    int deleteAllInvolving(@Param("appClientId") String appClientId, @Param("memberId") Integer memberId);
}
