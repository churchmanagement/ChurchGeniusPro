package com.churchgeniuspro.trial;

import com.churchgeniuspro.service.EvaluationTenant;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4: one rule for "this tenant is evaluating the product".
 *
 * <p>Four gates decided this independently and disagreed — the feature overlay
 * (prefix OR Trial plan, fail open), the public-page rule (the same, fail closed),
 * Plaid (Trial plan only) and the demo access windows (prefix only). The gaps
 * between them were the bugs: a demo tenant on the Pro plan reached production
 * Plaid, and a self-service trial that upgraded kept its {@code TRIAL-} prefix and
 * so stayed restricted for life.
 *
 * <p>This pins the table every gate now shares.
 */
@DisplayName("Evaluation tenant rule (M4)")
class EvaluationTenantRuleTest {

    private static final String DEMO  = TestDataService.DEMO_CLIENT_PREFIX  + "1757300000123";
    private static final String TRIAL = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";
    private static final String CHR   = "CHR-real-church";

    private static Boolean bool(String s) {
        return "null".equals(s) ? null : Boolean.valueOf(s);
    }

    @Nested
    @DisplayName("the table")
    class Table {

        @ParameterizedTest(name = "{0} + trialState={1} → {2}")
        @CsvSource({
            // DEMO- is never a real customer, whatever its plan row says.
            "DEMO,  true,  true",
            "DEMO,  false, true",
            "DEMO,  null,  true",
            // TRIAL- is a real prospect: only a subscription we can positively read
            // as "not Trial" releases it. Unknown keeps the restrictions.
            "TRIAL, true,  true",
            "TRIAL, false, false",
            "TRIAL, null,  true",
            // Everything else is exactly what its subscription says — including
            // "we could not tell", which each caller handles for itself.
            "CHR,   true,  true",
            "CHR,   false, false",
            "CHR,   null,  null",
        })
        void rule(String who, String trialState, String expected) {
            String clientId = switch (who) { case "DEMO" -> DEMO; case "TRIAL" -> TRIAL; default -> CHR; };
            assertThat(EvaluationTenant.state(clientId, bool(trialState))).isEqualTo(bool(expected));
        }

        @Test
        @DisplayName("a blank tenant is not an evaluation account")
        void blankIsNotEvaluation() {
            assertThat(EvaluationTenant.state(null, null)).isFalse();
            assertThat(EvaluationTenant.state("", Boolean.TRUE)).isFalse();
        }
    }

    @Nested
    @DisplayName("the two ways of reading it")
    class Readings {

        @Test
        @DisplayName("fail-open callers treat 'unknown' as not evaluating")
        void failOpen() {
            assertThat(EvaluationTenant.isEvaluation(CHR, null)).isFalse();
            assertThat(EvaluationTenant.isEvaluation(CHR, Boolean.TRUE)).isTrue();
            // ...but a TRIAL- tenant is never released by uncertainty.
            assertThat(EvaluationTenant.isEvaluation(TRIAL, null)).isTrue();
        }

        @Test
        @DisplayName("fail-closed callers treat 'unknown' as evaluating")
        void failClosed() {
            assertThat(EvaluationTenant.isEvaluationOrUnknown(CHR, null)).isTrue();
            assertThat(EvaluationTenant.isEvaluationOrUnknown(CHR, Boolean.FALSE)).isFalse();
        }

        @Test
        @DisplayName("'managed' is about where a tenant came from, not what it may do today")
        void managedIsSeparate() {
            // An upgraded trial is still ours to administer, but is no longer evaluating.
            assertThat(EvaluationTenant.isManaged(TRIAL)).isTrue();
            assertThat(EvaluationTenant.isEvaluation(TRIAL, Boolean.FALSE)).isFalse();
            assertThat(EvaluationTenant.isManaged(CHR)).isFalse();
        }
    }
}
