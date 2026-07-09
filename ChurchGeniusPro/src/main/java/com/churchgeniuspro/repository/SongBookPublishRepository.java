package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongBookPublish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Song Book publication state (one row per church). */
@Repository
public interface SongBookPublishRepository extends JpaRepository<SongBookPublish, Long> {

    Optional<SongBookPublish> findByClientId(String clientId);

    Optional<SongBookPublish> findByTokenAndPublishedTrue(String token);
}
