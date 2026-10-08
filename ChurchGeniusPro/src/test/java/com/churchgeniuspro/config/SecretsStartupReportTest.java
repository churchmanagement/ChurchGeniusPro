package com.churchgeniuspro.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.churchgeniuspro.util.EncryptionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production-readiness audit 2026-10-07, Phase 4.3. {@code CGP_CID_KEY} is read from the
 * OS environment by {@link EncryptionUtil}, which this JVM cannot change, so the
 * fail-fast branch is exercised only when the variable is absent in the test
 * environment and the "set" branch only when it is present; every other assertion
 * runs regardless.
 */
@DisplayName("SecretsStartupReport — present/missing report, values never logged")
class SecretsStartupReportTest {

    static final String SECRET_VALUE = "sk_test_do_not_print_4f9a1c7e";

    ListAppender<ILoggingEvent> appender;
    Logger logger;

    @BeforeEach void capture() {
        logger = (Logger) LoggerFactory.getLogger(SecretsStartupReport.class);
        appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
    }
    @AfterEach void release() { logger.detachAppender(appender); }

    static boolean cidKeyFromEnvironment() { return EncryptionUtil.isKeyFromEnvironment(); }

    private List<String> lines() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList(); }

    private MockEnvironment env(String profile) {
        MockEnvironment e = new MockEnvironment();
        if (profile != null) e.setActiveProfiles(profile);
        return e;
    }

    @Test @DisplayName("every variable is reported exactly once, by group, and values never appear")
    void reportsPresenceNotValues() {
        MockEnvironment e = env("local")
                .withProperty("platform.stripe.secret-key", SECRET_VALUE)
                .withProperty("twilio.auth-token", SECRET_VALUE)
                .withProperty("public.forms.recaptcha.secret", SECRET_VALUE)
                .withProperty("plaid.token-enc-key-previous", SECRET_VALUE);
        new SecretsStartupReport(e).report();

        List<String> out = lines();
        assertThat(out).noneMatch(l -> l.contains(SECRET_VALUE));
        assertThat(out).anyMatch(l -> l.equals("[Secrets] set      PLATFORM_STRIPE_SECRET_KEY"));
        assertThat(out).anyMatch(l -> l.equals("[Secrets] set      TWILIO_AUTH_TOKEN"));
        assertThat(out).anyMatch(l -> l.startsWith("[Secrets] MISSING  PLATFORM_STRIPE_WEBHOOK_SECRET — "));
        assertThat(out).anyMatch(l -> l.startsWith("[Secrets] MISSING  PAYROLL_SSN_ENC_KEY — "));
        assertThat(out).anyMatch(l -> l.startsWith("[Secrets] set      RECAPTCHA_SECRET (optional"));
        assertThat(out).anyMatch(l -> l.startsWith("[Secrets] not set  GEOIP_DB_PATH (optional"));
        assertThat(out).anyMatch(l -> l.startsWith("[Secrets] set      PLAID_TOKEN_ENC_KEY_PREVIOUS (rotation-only"));
        for (SecretsStartupReport.Var v : SecretsStartupReport.EXPECTED) {
            long expected = v.name().endsWith("_PROD") ? 0 : 1;   // DB_*_PROD only reported under prod
            assertThat(out.stream().filter(l -> l.matches(".*  " + v.name() + "(?![A-Z_]).*")).count()).as(v.name()).isEqualTo(expected);
        }
        // Every line must survive the logging layout's masker unchanged — the old
        // "NAME: missing" form was rendered as "NAME: ***REDACTED***" in the real log.
        for (String l : out) {
            assertThat(com.churchgeniuspro.logging.SensitiveDataMasker.mask(l)).as("masker must not alter: " + l).isEqualTo(l);
        }
    }

    @Test @DisplayName("missing expected variables are WARN, optional and rotation-only are INFO — never an exception")
    void levelsByGroup() {
        new SecretsStartupReport(env("local")).report();
        assertThat(appender.list.stream().filter(ev -> ev.getFormattedMessage().contains("  PAYROLL_SSN_ENC_KEY ")).map(ILoggingEvent::getLevel)).containsExactly(Level.WARN);
        assertThat(appender.list.stream().filter(ev -> ev.getFormattedMessage().contains("RECAPTCHA_SITE_KEY")).map(ILoggingEvent::getLevel)).containsExactly(Level.INFO);
        assertThat(appender.list.stream().filter(ev -> ev.getFormattedMessage().contains("PLAID_TOKEN_ENC_KEY_PREVIOUS")).map(ILoggingEvent::getLevel)).containsExactly(Level.INFO);
    }

    @Test @DisplayName("PAYROLL_SSN_ENC_KEY missing in prod is a WARN, not a startup failure (rotation phase pending)")
    @DisabledIf("cidKeyFromEnvironment")
    void payrollKeyDoesNotFailProdWhenCidKeyAlsoMissing() {
        // With CGP_CID_KEY absent, prod startup fails for THAT reason only; the message names no other variable.
        assertThatThrownBy(() -> new SecretsStartupReport(env("prod")).report())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CGP_CID_KEY")
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain("PAYROLL").doesNotContain(SECRET_VALUE));
    }

    @Test @DisplayName("CGP_CID_KEY missing: prod refuses to start, other profiles log ERROR and continue")
    @DisabledIf("cidKeyFromEnvironment")
    void cidKeyMissing() {
        assertThatThrownBy(() -> new SecretsStartupReport(env("prod")).report()).isInstanceOf(IllegalStateException.class);
        appender.list.clear();
        new SecretsStartupReport(env("local")).report();
        assertThat(appender.list.stream().filter(ev -> ev.getFormattedMessage().startsWith("[Secrets] MISSING  CGP_CID_KEY")).map(ILoggingEvent::getLevel)).containsExactly(Level.ERROR);
        appender.list.clear();
        new SecretsStartupReport(env(null)).report();   // no profile at all → not prod → continues
        assertThat(lines()).anyMatch(l -> l.startsWith("[Secrets] MISSING  CGP_CID_KEY"));
    }

    @Test @DisplayName("DB_*_PROD lines appear only under the prod profile")
    @EnabledIf("cidKeyFromEnvironment")
    void dbVarsOnlyInProd() {
        new SecretsStartupReport(env("prod").withProperty("spring.datasource.url", "jdbc:x")).report();
        assertThat(lines()).anyMatch(l -> l.equals("[Secrets] set      DB_URL_PROD")).anyMatch(l -> l.startsWith("[Secrets] MISSING  DB_USER_PROD"));
    }

    @Test @DisplayName("CGP_CID_KEY set: prod starts and reports it as set")
    @EnabledIf("cidKeyFromEnvironment")
    void cidKeySet() {
        new SecretsStartupReport(env("prod")).report();
        assertThat(lines()).contains("[Secrets] set      CGP_CID_KEY (required)");
    }
}
