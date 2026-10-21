package com.churchgeniuspro;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the full application context loads. It boots against a real
 * PostgreSQL (Testcontainers) instead of the developer's localhost database, so
 * it never depends on an external DB being up. When Docker is unavailable the
 * test is skipped (so {@code mvn test} stays green on machines without Docker);
 * CI runs it with Docker present.
 *
 * <p>{@code @DirtiesContext}: the Postgres container is stopped as soon as this class
 * finishes, but Spring's test-context cache would otherwise keep the booted context —
 * schedulers, Hikari pool and all — alive for the rest of the surefire run, each
 * scheduled job then blocking 30 s on a connection to a database that no longer
 * exists and slowing every later test class. Closing the context here stops that.
 */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
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
