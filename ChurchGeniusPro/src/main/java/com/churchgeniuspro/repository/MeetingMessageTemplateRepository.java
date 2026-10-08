package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MeetingMessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for tenant-scoped {@link MeetingMessageTemplate}s. */
@Repository
public interface MeetingMessageTemplateRepository extends JpaRepository<MeetingMessageTemplate, Integer> {

    List<MeetingMessageTemplate> findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(String appClientId);

    Optional<MeetingMessageTemplate> findByIdAndDeleteFlagFalse(Integer id);

    /** Tenant-scoped single lookup (security audit, week 1). */
    Optional<MeetingMessageTemplate> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);

    // Explicit JPQL avoids any "is"-prefixed boolean ambiguity in derived queries.
    @Query("SELECT t FROM MeetingMessageTemplate t WHERE t.appClientId = :appClientId AND t.isDefault = true AND t.deleteFlag = false")
    List<MeetingMessageTemplate> findDefaults(@Param("appClientId") String appClientId);

    boolean existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(String name, String appClientId);

    boolean existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(String name, String appClientId, Integer id);
}
