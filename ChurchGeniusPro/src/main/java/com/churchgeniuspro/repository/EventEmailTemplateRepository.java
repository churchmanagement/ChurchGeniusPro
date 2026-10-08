package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EventEmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for tenant-scoped {@link EventEmailTemplate}s. */
@Repository
public interface EventEmailTemplateRepository extends JpaRepository<EventEmailTemplate, Integer> {

    List<EventEmailTemplate> findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(String appClientId);

    Optional<EventEmailTemplate> findByIdAndDeleteFlagFalse(Integer id);

    /** Tenant-scoped single lookup (security audit, week 1). */
    Optional<EventEmailTemplate> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);

    // Explicit JPQL avoids any "is"-prefixed boolean ambiguity in derived queries.
    @Query("SELECT t FROM EventEmailTemplate t WHERE t.appClientId = :appClientId AND t.isDefault = true AND t.deleteFlag = false")
    List<EventEmailTemplate> findDefaults(@Param("appClientId") String appClientId);

    boolean existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(String name, String appClientId);

    boolean existsByNameIgnoreCaseAndAppClientIdAndDeleteFlagFalseAndIdNot(String name, String appClientId, Integer id);
}
