package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorshipInstrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface WorshipInstrumentRepository extends JpaRepository<WorshipInstrument, Long> {
    List<WorshipInstrument> findByGroupIdAndDeleteFlagFalse(Long groupId);
    void deleteByGroupId(Long groupId);

    /**
     * Bulk-fetch all active instruments for a set of group IDs in one query.
     * Eliminates N+1 when building group trees for multiple groups.
     */
    @Query("SELECT i FROM WorshipInstrument i WHERE i.groupId IN :groupIds AND i.deleteFlag = false ORDER BY i.id ASC")
    List<WorshipInstrument> findByGroupIdInAndDeleteFlagFalse(
            @Param("groupIds") Collection<Long> groupIds);

    /**
     * Bulk-fetch instruments by a set of IDs in one query.
     * Eliminates repeated findById calls when resolving instrument names in assignments.
     */
    @Query("SELECT i FROM WorshipInstrument i WHERE i.id IN :ids")
    List<WorshipInstrument> findByIdIn(@Param("ids") Collection<Long> ids);
}
