package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.FinancialReportLetter;
import com.churchgeniuspro.repository.FinancialReportLetterRepository;
import com.churchgeniuspro.service.FinancialReportLetterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The letter-text table against real PostgreSQL — the engine production uses.
 *
 * <p>Two things only a database can answer. That {@code ddl-auto=update} actually
 * creates {@code financial_report_letter} with TEXT columns wide enough for a few
 * paragraphs of rich text, so the first church to save a letter is not the one
 * that discovers a truncation. And that the letter survives the round trip
 * byte-for-byte: it is HTML, and a column or dialect that mangled a quote would
 * break the printed design silently.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FinancialReportLetterRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired FinancialReportLetterRepository repo;
    @Autowired FinancialReportLetterService    service;

    @Test
    void storesOneLettersWordingPerChurchAndReadsItBackIntact() {
        service.save("CHR-it-a", FinancialReportLetterService.DEFAULT_INTRO_HTML,
                     FinancialReportLetterService.DEFAULT_CLOSING_HTML, "alice");

        Optional<FinancialReportLetter> row = repo.findFirstByAppClientIdAndDeleteFlagFalse("CHR-it-a");
        assertTrue(row.isPresent());
        assertEquals(FinancialReportLetterService.DEFAULT_CLOSING_HTML, row.get().getClosingHtml());
        assertNotNull(row.get().getCreatedDate());
        assertEquals("alice", row.get().getUpdatedBy());

        // A second church is unaffected, and gets the built-in wording.
        assertTrue(repo.findFirstByAppClientIdAndDeleteFlagFalse("CHR-it-b").isEmpty());
        assertTrue(service.rendered("CHR-it-b", "Second Church", 2026)
                          .get("introHtml").contains("Second Church"));

        // Editing replaces the church's own row rather than adding another.
        service.save("CHR-it-a", "<p>Ours</p>", "<p>Ours too</p>", "alice");
        assertEquals(1, repo.findAll().stream()
                            .filter(r -> "CHR-it-a".equals(r.getAppClientId()))
                            .count());

        // Restoring the default retires it, so the built-in wording returns.
        assertTrue(service.resetToDefault("CHR-it-a"));
        assertTrue(repo.findFirstByAppClientIdAndDeleteFlagFalse("CHR-it-a").isEmpty());
        assertTrue(service.rendered("CHR-it-a", "First Church", 2026)
                          .get("introHtml").contains("Thank you for your faithful giving"));
    }
}
