package com.churchgeniuspro.trialemail;

import com.churchgeniuspro.util.EmailActionScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmailActionScope — one test email per action")
class EmailActionScopeTest {

    @Test @DisplayName("first claim per tenant wins; the rest are simulated; different tenants and sub-keys are separate")
    void claims() {
        try (EmailActionScope s = EmailActionScope.begin("meeting-notify:7")) {
            assertThat(EmailActionScope.current()).isSameAs(s);
            assertThat(s.claim("T1")).isTrue();
            assertThat(s.claim("T1")).isFalse();
            assertThat(s.claim("T2")).isTrue();
            s.sub("event:9");
            assertThat(s.claim("T1")).isTrue();
            assertThat(s.claim("T1")).isFalse();
            s.sub(null);
            assertThat(s.claim("T1")).isFalse();
        }
        assertThat(EmailActionScope.current()).isNull();
    }

    @Test @DisplayName("scopes nest and restore the outer one on close")
    void nesting() {
        try (EmailActionScope outer = EmailActionScope.begin("outer")) {
            try (EmailActionScope inner = EmailActionScope.begin("inner")) {
                assertThat(EmailActionScope.current()).isSameAs(inner);
                assertThat(inner.claim("T")).isTrue();
            }
            assertThat(EmailActionScope.current()).isSameAs(outer);
            assertThat(outer.claim("T")).as("a different action").isTrue();
        }
        assertThat(EmailActionScope.current()).isNull();
    }

    @Test @DisplayName("scopes are per thread")
    void perThread() throws Exception {
        try (EmailActionScope s = EmailActionScope.begin("a")) {
            Thread t = new Thread(() -> assertThat(EmailActionScope.current()).isNull());
            t.start(); t.join();
        }
    }
}
