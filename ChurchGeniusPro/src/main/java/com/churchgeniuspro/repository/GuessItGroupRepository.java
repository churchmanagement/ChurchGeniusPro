package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.GuessItGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuessItGroupRepository extends JpaRepository<GuessItGroup, Long> {

    /**
     * Resolve a join code typed by a participant.
     * Only groups that are still active can be joined, so an ended group's code
     * stops working the moment it is closed.
     */
    Optional<GuessItGroup> findFirstByCodeAndStatusAndDeleteFlagFalse(String code, String status);

    /** Any live group holding this code — used to guarantee a fresh code is free. */
    boolean existsByCodeAndStatusAndDeleteFlagFalse(String code, String status);

    /** Admin listing for an org, newest first. */
    List<GuessItGroup> findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(String clientId);

    /** Tenant-safe fetch: a group only resolves for the org that owns it. */
    Optional<GuessItGroup> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);

    /** Any code at all, live or ended — used when reporting final results. */
    Optional<GuessItGroup> findFirstByCodeAndDeleteFlagFalseOrderByCreatedAtDesc(String code);
}
