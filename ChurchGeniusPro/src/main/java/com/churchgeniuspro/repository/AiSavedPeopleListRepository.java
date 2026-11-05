package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AiSavedPeopleList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** {@code ai_saved_people_list} — every read is tenant-scoped. */
@Repository
public interface AiSavedPeopleListRepository extends JpaRepository<AiSavedPeopleList, Long> {
    List<AiSavedPeopleList> findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(String appClientId);
    Optional<AiSavedPeopleList> findByIdAndAppClientIdAndDeleteFlagFalse(Long id, String appClientId);
    long countByAppClientIdAndDeleteFlagFalse(String appClientId);
    boolean existsByAppClientIdAndDeleteFlagFalseAndNameIgnoreCase(String appClientId, String name);
}
