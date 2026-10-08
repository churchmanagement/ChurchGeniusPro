package com.churchgeniuspro.integration;

import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 1 (Critical) — end-to-end proof over <em>real HTTP</em> and the <em>real
 * dispatcher</em> that the encoded-traversal authentication bypass is closed for the
 * concrete Group-member endpoints, across every verb.
 *
 * <p>A real embedded Tomcat serves the whole application on a random port; requests are
 * sent with the JDK {@link HttpClient} (which transmits the raw path, so the encoded
 * traversal reaches the container exactly as written). Because the request is
 * unauthenticated, {@link com.churchgeniuspro.webfilter.AuthFilter} — now deciding on
 * the canonical path — refuses it with 401 <em>before</em> it can reach the controller.
 *
 * <p>Note this endpoint is defended in depth: {@code GroupMemberController} also runs
 * {@code GroupController.groupsPageGuard} (which denies a session-less caller) and every
 * data path is tenant-scoped, so an anonymous caller could never read another church's
 * data even without this filter. The value asserted here is the authentication
 * <em>gate</em>: a request that the pre-fix filter whitelisted as public (its raw form
 * starts with {@code /api/public/}) now correctly resolves to the protected endpoint and
 * is refused at the filter. Server-side tenant isolation between two tenants is proven
 * by {@link GroupMemberTenantIsolationIT}; the deterministic, container-independent
 * filter proof is {@code AuthChainCanonicalPathTest}.
 *
 * <p>Requires Docker; auto-skipped when absent, runs on {@code verify} in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Finding 1 — encoded-traversal bypass closed end-to-end (real HTTP)")
class GroupMemberBypassIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /** Sends {@code method rawPath} with no session and returns the HTTP status. */
    private int status(String method, String rawPath) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath));
        switch (method) {
            case "GET"    -> b.GET();
            case "DELETE" -> b.DELETE();
            case "POST"   -> b.POST(HttpRequest.BodyPublishers.noBody());
            case "PUT"    -> b.PUT(HttpRequest.BodyPublishers.noBody());
            default       -> throw new IllegalArgumentException(method);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // The real controller mappings: GET/POST on /api/groups/{id}/members, PUT/DELETE on /api/group-members/{id}.
    private static final String GROUP_MEMBERS = "/api/groups/5/members";
    private static final String GROUP_MEMBER  = "/api/group-members/9";

    @Test
    @DisplayName("plain unauthenticated access to the protected endpoints is refused (401) for every verb")
    void plainUnauthenticatedIsRefused() throws Exception {
        assertThat(status("GET",    GROUP_MEMBERS)).isEqualTo(401);
        assertThat(status("POST",   GROUP_MEMBERS)).isEqualTo(401);
        assertThat(status("PUT",    GROUP_MEMBER)).isEqualTo(401);
        assertThat(status("DELETE", GROUP_MEMBER)).isEqualTo(401);
    }

    @Test
    @DisplayName("encoded-traversal off the public prefix is refused (401) for every verb — the bypass is closed")
    void encodedTraversalIsRefused() throws Exception {
        // These raw paths all start with /api/public/ (pre-fix whitelist match) but the
        // dispatcher resolves them to the protected endpoint. Encodings Tomcat forwards
        // to the filter chain (encoded/plain dot-segments): expect the AuthFilter 401.
        String[] toMembers = {
            "/api/public/x/%2e%2e/%2e%2e/groups/5/members",
            "/api/public/x/%2E%2E/%2E%2E/groups/5/members",
            "/api/public/x/../../groups/5/members",
            "/api/public/x/../%2e%2e/groups/5/members",
            "/api/public/a/b/c/%2e%2e/%2e%2e/%2e%2e/%2e%2e/groups/5/members",
        };
        for (String p : toMembers) {
            assertThat(status("GET",  p)).as("GET %s", p).isEqualTo(401);
            assertThat(status("POST", p)).as("POST %s", p).isEqualTo(401);
        }
        String[] toMember = {
            "/api/public/x/%2e%2e/%2e%2e/group-members/9",
            "/api/public/x/../../group-members/9",
        };
        for (String p : toMember) {
            assertThat(status("PUT",    p)).as("PUT %s", p).isEqualTo(401);
            assertThat(status("DELETE", p)).as("DELETE %s", p).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("an encoded-slash traversal is also refused (never reaches the endpoint)")
    void encodedSlashTraversalIsRefused() throws Exception {
        // %2F may be rejected by the container itself (400) rather than reaching the
        // filter (401). Either way the request is denied and no endpoint is served.
        int s = status("GET", "/api/public/x%2F%2e%2e%2F%2e%2e%2Fgroups/5/members");
        assertThat(s).as("encoded-slash traversal must be denied").isGreaterThanOrEqualTo(400).isNotEqualTo(404);
    }

    @Test
    @DisplayName("the server is up and not over-blocking (health is 200)")
    void healthStillUp() throws Exception {
        assertThat(status("GET", "/actuator/health")).isEqualTo(200);
    }
}
