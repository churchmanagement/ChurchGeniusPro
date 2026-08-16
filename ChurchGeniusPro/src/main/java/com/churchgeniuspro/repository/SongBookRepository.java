package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongBook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SongBookRepository extends JpaRepository<SongBook, Long> {

    List<SongBook> findByClientIdOrderBySortOrderAscIdAsc(String clientId);

    Optional<SongBook> findByIdAndClientId(Long id, String clientId);

    Optional<SongBook> findFirstByClientIdAndDefaultBookTrue(String clientId);
}
