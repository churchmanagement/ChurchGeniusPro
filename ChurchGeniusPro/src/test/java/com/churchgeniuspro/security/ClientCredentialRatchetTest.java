package com.churchgeniuspro.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mobile Phase 1, M1 + M3 on the client. Reads the shipped static files so the build
 * fails if a reversible credential ever returns to the login page, or if a logout
 * path stops telling the server which push subscription to deactivate.
 */
class ClientCredentialRatchetTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String read(String name) throws Exception {
        return Files.readString(STATIC.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("login.html stores no password and no WebAuthn gate — only the username is kept")
    void loginPageHoldsNoCredential() throws Exception {
        String html = read("login.html");
        String withoutCleanup = html.replace("localStorage.removeItem('rememberedPassword')", "");
        assertThat(withoutCleanup).doesNotContain("rememberedPassword");
        assertThat(html).doesNotContain("btoa(password").doesNotContain("atob(savedPass");
        assertThat(html).doesNotContain("navigator.credentials");
        assertThat(html).doesNotContain("registerBiometric").doesNotContain("initBiometricButton");
        assertThat(html).contains("localStorage.setItem('rememberedUsername', username)");
        assertThat(html).contains("fetch('/api/auth/check-remember')");
        // The native shell's hook stays, inert on the web.
        assertThat(html).contains("id=\"biometricBtn\"").contains("if (!window.Capacitor) return;");
    }

    @Test
    @DisplayName("every logout path can hand the push endpoint to the server")
    void logoutPathsCarryPushEndpoint() throws Exception {
        // Phase 6 widened the body ({ pushEndpoint?, deviceToken? }); the endpoint is still carried on every path.
        assertThat(read("session.js")).contains("function CGP_pushEndpoint(")
                .contains("if (r[0]) body.pushEndpoint = r[0];");
        assertThat(read("memberHome.html")).contains("function cgpPushEndpoint(")
                .contains("var body = endpoint ? { pushEndpoint: endpoint } : {};");
        assertThat(read("home.html")).contains("var body = endpoint ? { pushEndpoint: endpoint } : {};")
                .contains("if (dt) body.deviceToken = dt;");
    }

    @Test
    @DisplayName("an existing browser subscription is re-posted so it is keyed to the signed-in user")
    void existingSubscriptionIsRekeyed() throws Exception {
        String session = read("session.js");
        assertThat(session).doesNotContain("if (existing) return; /* already subscribed */");
        assertThat(session).contains("cgp_push_synced");
        String member = read("memberHome.html");
        assertThat(member).doesNotContain("if (existing) return; // already subscribed");
        assertThat(member).contains("cgp_push_synced");
    }
}
