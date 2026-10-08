package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    /**
     * Most recently issued token for a username (at most one survives, since
     * {@link #deleteByUsername} clears the rest before each insert).
     *
     * <p>Used to enforce the reset-request cooldown, which stops an attacker who knows
     * a username from flooding that person's inbox with reset emails. It cannot lock
     * anyone out: the worst outcome is that a second email inside the cooldown is not
     * sent, and the first one is still valid.
     */
    Optional<PasswordResetToken> findFirstByUsernameOrderByIdDesc(String username);

    /**
     * Remove all tokens for the given username before issuing a new one.
     * Uses an explicit JPQL DELETE so Spring does not load entities first —
     * requires @Modifying + @Transactional on the repository method itself.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.username = :username")
    void deleteByUsername(@Param("username") String username);
}
