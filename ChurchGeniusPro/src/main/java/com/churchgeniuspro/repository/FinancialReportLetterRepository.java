package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.FinancialReportLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Giving-statement letter text, one row per church.
 *
 * <p>There is deliberately no finder by id. The tenant always comes from the
 * session, so a row is only ever reached through its {@code appClientId} — one
 * church can neither read nor overwrite another's wording by guessing a number.
 */
public interface FinancialReportLetterRepository extends JpaRepository<FinancialReportLetter, Integer> {

    Optional<FinancialReportLetter> findFirstByAppClientIdAndDeleteFlagFalse(String appClientId);
}
