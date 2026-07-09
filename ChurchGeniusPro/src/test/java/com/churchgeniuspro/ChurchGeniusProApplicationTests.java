package com.churchgeniuspro;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the full application context loads. It boots against a real
 * PostgreSQL (Testcontainers) instead of the developer's localhost database, so
 * it never depends on an external DB being up. When Docker is unavailable the
 * test is skipped (so {@code mvn test} stays green on machines without Docker);
 * CI runs it with Docker present.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChurchGeniusProApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine");

	@Test
	void contextLoads() {
	}

}
