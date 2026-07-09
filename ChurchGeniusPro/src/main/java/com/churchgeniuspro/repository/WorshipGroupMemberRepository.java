package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorshipGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface WorshipGroupMemberRepository extends JpaRepository<WorshipGroupMember, Long> {
    List<WorshipGroupMember> findByInstrumentIdAndDeleteFlagFalseOrderByRotationOrderAsc(Long instrumentId);
    List<WorshipGroupMember> findByInstrumentIdAndDeleteFlagFalse(Long instrumentId);
    void deleteByInstrumentId(Long instrumentId);

    /**
     * Bulk-fetch all active members for a set of instrument IDs in one query.
     * Eliminates N+1 when building group trees for multiple instruments.
     */
    @Query("SELECT m FROM WorshipGroupMember m WHERE m.instrumentId IN :instrumentIds AND m.deleteFlag = false ORDER BY m.rotationOrder ASC NULLS LAST, m.id ASC")
    List<WorshipGroupMember> findByInstrumentIdInAndDeleteFlagFalse(
            @Param("instrumentIds") Collection<Long> instrumentIds);
}
