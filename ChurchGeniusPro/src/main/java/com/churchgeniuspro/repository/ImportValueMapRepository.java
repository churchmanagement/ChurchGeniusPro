package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ImportValueMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImportValueMapRepository extends JpaRepository<ImportValueMap, Long> {
    List<ImportValueMap> findByRunIdOrderByDictionaryAscSourceValueAsc(Long runId);
    List<ImportValueMap> findByRunIdAndDictionary(Long runId, String dictionary);
    Optional<ImportValueMap> findByRunIdAndDictionaryAndSourceValueIgnoreCase(Long runId, String dictionary, String sourceValue);
}
