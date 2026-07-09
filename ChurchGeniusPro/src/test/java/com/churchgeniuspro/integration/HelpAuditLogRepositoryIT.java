package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.HelpAuditLog;
import com.churchgeniuspro.repository.HelpAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Data-layer integration test against a real PostgreSQL (Testcontainers) — the
 * same engine used in production — so JPA mappings and tenant-scoped queries are
 * exercised against the production dialect. Uses a full {@code @SpringBootTest}
 * (web environment NONE) and autowires the repository directly, avoiding the
 * relocated {@code @DataJpaTest} slice. Requires Docker; runs on `verify`.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HelpAuditLogRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired HelpAuditLogRepository repo;

    @Test
    void writesAndReadsBackTenantScoped() {
        HelpAuditLog a = new HelpAuditLog();
        a.setClientId("CLIENT-A");
        a.setActor("alice");
        a.setRole("Admin");
        a.setKind("QUERY");
        a.setQuery("How do I check in a child?");
        repo.save(a);

        HelpAuditLog b = new HelpAuditLog();
        b.setClientId("CLIENT-B");
        b.setKind("DENIED");
        repo.save(b);

        List<HelpAuditLog> forA = repo.findByClientIdOrderByCreatedAtDesc("CLIENT-A", PageRequest.of(0, 10));
        assertEquals(1, forA.size(), "tenant scoping must isolate CLIENT-A rows");
        assertEquals("QUERY", forA.get(0).getKind());
        assertNotNull(forA.get(0).getCreatedAt(), "@PrePersist must set createdAt");

        assertEquals(1, repo.findByClientIdAndKindOrderByCreatedAtDesc("CLIENT-B", "DENIED", PageRequest.of(0, 10)).size());
    }
}
