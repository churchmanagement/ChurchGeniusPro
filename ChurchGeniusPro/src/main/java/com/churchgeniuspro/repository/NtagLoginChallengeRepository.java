package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.NtagLoginChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NtagLoginChallengeRepository extends JpaRepository<NtagLoginChallenge, Long> {
    Optional<NtagLoginChallenge> findByToken(String token);
}
