package com.churchgeniuspro.payroll.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Base64;

import static com.churchgeniuspro.payroll.service.SsnCrypto.State.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security audit 2026-10-07, Phase 5: previous-key decryption, "derived" sentinel, explicit state. */
@DisplayName("SsnCrypto — key rotation")
class SsnCryptoRotationTest {

    static final String KEY_A = b64(32, 1);
    static final String KEY_B = b64(32, 2);
    static final String LAST4 = "7391";

    static String b64(int len, int seed) { byte[] b = new byte[len]; for (int i = 0; i < len; i++) b[i] = (byte) (seed * 37 + i); return Base64.getEncoder().encodeToString(b); }

    ListAppender<ILoggingEvent> appender; Logger logger;
    @BeforeEach void capture() { logger = (Logger) LoggerFactory.getLogger(SsnCrypto.class); appender = new ListAppender<>(); appender.start(); logger.addAppender(appender); }
    @AfterEach void release() { logger.detachAppender(appender); }

    @Test @DisplayName("no previous key: behaves exactly as before (encrypt/decrypt/plaintext passthrough)")
    void noPreviousKey() {
        SsnCrypto c = new SsnCrypto(KEY_A, "");
        assertThat(c.rotationInProgress()).isFalse();
        assertThat(c.isConfigured()).isTrue();
        String enc = c.encrypt(LAST4);
        assertThat(enc).isNotEqualTo(LAST4);
        assertThat(c.decrypt(enc)).isEqualTo(LAST4);
        assertThat(c.decrypt("1234")).isEqualTo("1234");
        assertThat(c.decrypt(null)).isNull();
        assertThat(c.decrypt("")).isEmpty();
        assertThat(new SsnCrypto(KEY_A).decrypt(enc)).isEqualTo(LAST4);   // single-arg constructor
    }

    @Test @DisplayName("current key first, previous key second")
    void previousKeyFallback() {
        String underA = new SsnCrypto(KEY_A, "").encrypt(LAST4);
        SsnCrypto rotating = new SsnCrypto(KEY_B, KEY_A);
        assertThat(rotating.rotationInProgress()).isTrue();
        assertThat(rotating.decrypt(underA)).isEqualTo(LAST4);
        String underB = rotating.encrypt(LAST4);
        assertThat(rotating.decrypt(underB)).isEqualTo(LAST4);
        assertThat(new SsnCrypto(KEY_B, "").decrypt(underA)).as("without the previous key the old value is unreadable").isNull();
    }

    @Test @DisplayName("classify() reports BLANK / LEGACY_PLAINTEXT / CURRENT_KEY / PREVIOUS_KEY / UNREADABLE explicitly")
    void classifyMatrix() {
        String underA = new SsnCrypto(KEY_A, "").encrypt(LAST4);
        SsnCrypto rotating = new SsnCrypto(KEY_B, KEY_A);
        String underB = rotating.encrypt(LAST4);
        assertThat(rotating.classify(null)).isEqualTo(BLANK);
        assertThat(rotating.classify("  ")).isEqualTo(BLANK);
        assertThat(rotating.classify("4321")).isEqualTo(LEGACY_PLAINTEXT);
        assertThat(rotating.classify(underB)).isEqualTo(CURRENT_KEY);
        assertThat(rotating.classify(underA)).isEqualTo(PREVIOUS_KEY);
        assertThat(rotating.classify("not-base64!")).isEqualTo(UNREADABLE);
        assertThat(rotating.classify(underB.substring(0, underB.length() - 6) + "AAAAAA")).as("tampered tag").isEqualTo(UNREADABLE);
        assertThat(new SsnCrypto(KEY_B, "").classify(underA)).as("no previous key → old ciphertext is UNREADABLE, not PREVIOUS_KEY").isEqualTo(UNREADABLE);
        assertThat(rotating.needsRotation("4321")).isTrue();
        assertThat(rotating.needsRotation(underA)).isTrue();
        assertThat(rotating.needsRotation(underB)).isFalse();
        assertThat(rotating.needsRotation("garbage")).isFalse();
        assertThat(rotating.needsRotation(null)).isFalse();
    }

    @Test @DisplayName("\"derived\" sentinel opens values written when no key was configured")
    void derivedSentinel() {
        String underDerived = new SsnCrypto("", "").encrypt(LAST4);
        SsnCrypto rotating = new SsnCrypto(KEY_A, "derived");
        assertThat(rotating.classify(underDerived)).isEqualTo(PREVIOUS_KEY);
        assertThat(rotating.decrypt(underDerived)).isEqualTo(LAST4);
        assertThat(new SsnCrypto(KEY_A, " Derived ").classify(underDerived)).as("case/whitespace tolerant").isEqualTo(PREVIOUS_KEY);
        String rotated = rotating.reEncrypt(underDerived);
        assertThat(rotating.classify(rotated)).isEqualTo(CURRENT_KEY);
        assertThat(new SsnCrypto(KEY_A, "").decrypt(rotated)).isEqualTo(LAST4);
    }

    @Test @DisplayName("reEncrypt moves plaintext and previous-key values onto the current key; refuses unreadable")
    void reEncrypt() {
        SsnCrypto rotating = new SsnCrypto(KEY_B, KEY_A);
        String fromPlain = rotating.reEncrypt("0007");
        assertThat(rotating.classify(fromPlain)).isEqualTo(CURRENT_KEY);
        assertThat(rotating.decrypt(fromPlain)).isEqualTo("0007");
        assertThatThrownBy(() -> rotating.reEncrypt("garbage")).isInstanceOf(IllegalStateException.class)
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain("garbage"));
    }

    @Test @DisplayName("startup validation: previous without current, bad length, bad base64 — messages name the variable only")
    void startupValidation() {
        assertThatThrownBy(() -> new SsnCrypto("", "derived")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYROLL_SSN_ENC_KEY_PREVIOUS").hasMessageContaining("PAYROLL_SSN_ENC_KEY is not");
        assertThatThrownBy(() -> new SsnCrypto("", KEY_A)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SsnCrypto(KEY_A, b64(20, 3))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYROLL_SSN_ENC_KEY_PREVIOUS").hasMessageContaining("20")
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain(b64(20, 3)));
        assertThatThrownBy(() -> new SsnCrypto(b64(20, 3), "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYROLL_SSN_ENC_KEY must decode")
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain("PREVIOUS"));
        assertThatThrownBy(() -> new SsnCrypto(KEY_A, "***not base64***")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYROLL_SSN_ENC_KEY_PREVIOUS is not valid base64")
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain("***"));
        assertThat(new SsnCrypto(KEY_A, b64(16, 4)).rotationInProgress()).as("16-byte previous key accepted").isTrue();
    }

    @Test @DisplayName("no log line ever carries a last-4 value or a key")
    void nothingSensitiveLogged() {
        String underDerived = new SsnCrypto("", "").encrypt(LAST4);           // logs the derived-key WARN
        SsnCrypto rotating = new SsnCrypto(KEY_A, "derived");                 // logs the rotation WARN
        rotating.decrypt(underDerived); rotating.classify("garbage"); rotating.decrypt("garbage");
        new SsnCrypto(KEY_A, "").decrypt(underDerived);                        // undecryptable WARN
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent ev : appender.list) {
            String m = ev.getFormattedMessage();
            assertThat(m).doesNotContain(LAST4).doesNotContain(KEY_A).doesNotContain(underDerived);
        }
    }
}
