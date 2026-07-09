package com.churchgeniuspro.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-application smoke integration test on a real PostgreSQL container:
 * verifies the context boots end-to-end, the actuator health endpoint is UP
 * (health/performance monitoring), and that protected APIs reject anonymous
 * callers (the auth filter is active). Uses the JDK HttpClient so it does not
 * depend on Spring Boot test-slice modules. Requires Docker; runs on `verify`.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> getString(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void contextLoadsAndHealthIsUp() throws Exception {
        HttpResponse<String> health = getString("/actuator/health");
        assertEquals(200, health.statusCode());
        assertTrue(health.body() != null && health.body().contains("UP"), "health endpoint should report UP");
    }

    @Test
    void protectedApi_rejectsAnonymous() throws Exception {
        HttpResponse<String> r = getString("/api/session");
        assertEquals(401, r.statusCode(), "/api/session must require an authenticated session");
    }
}
