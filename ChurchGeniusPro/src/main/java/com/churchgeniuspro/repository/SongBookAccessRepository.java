package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SongBookAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Per-member Song Book access grants (tenant-scoped). */
@Repository
public interface SongBookAccessRepository extends JpaRepository<SongBookAccess, Long> {

    Optional<SongBookAccess> findByClientIdAndMemberId(String clientId, Long memberId);

    List<SongBookAccess> findByClientId(String clientId);
}
