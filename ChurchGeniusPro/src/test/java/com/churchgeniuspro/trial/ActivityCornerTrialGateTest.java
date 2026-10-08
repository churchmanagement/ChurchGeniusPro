package com.churchgeniuspro.trial;

import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Activity Corner (Guess It) as a subscription-gated feature.
 *
 * <p>Hiding the nav is cosmetic; what is pinned here is the server-side mapping
 * that makes the restriction real — a Trial user who types {@code /guessIt} or
 * calls the API directly is refused by {@code SubscriptionFeatureFilter}, not by
 * the absence of a sidebar button.
 *
 * <p>The other half of the guarantee is that this changes NOTHING for anyone
 * else: feature flags fail open, so only a plan carrying an explicit
 * {@code "activityCorner": false} loses it.
 */
@DisplayName("Activity Corner — Trial gate")
class ActivityCornerTrialGateTest {

    private static final String KEY = "activityCorner";

    @Nested
    @DisplayName("what the gate covers")
    class Covered {

        @Test
        @DisplayName("the staff page and the admin/member APIs are gated")
        void gatedPaths() {
            assertThat(SubscriptionFeatureCatalog.keyForPath("/guessIt")).isEqualTo(KEY);
            assertThat(SubscriptionFeatureCatalog.keyForPath("/guessIt.html")).isEqualTo(KEY);
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it")).isEqualTo(KEY);
            // Hosting a game is the capability that matters: without it a plan can
            // never produce a group code for anyone to join.
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it/admin/games")).isEqualTo(KEY);
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it/admin/groups")).isEqualTo(KEY);
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it/member/active")).isEqualTo(KEY);
        }

        @Test
        @DisplayName("the two public entry points stay ungated")
        void publicEntryPointsUngated() {
            // Both are reachable without a session and governed by their own tokens
            // — the big-screen display by an encrypted cid, participants by a
            // 6-character group code. Gating them would break the display for
            // paying churches without adding anything: the code they need can only
            // come from the admin API above, which IS gated.
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it/public")).isNull();
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it/group")).isNull();
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it/group/join")).isNull();
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/guess-it/group/state")).isNull();
        }

        @Test
        @DisplayName("the feature is listed so Subscription Plans can toggle it")
        void featureIsInTheCatalog() {
            List<SubscriptionFeatureCatalog.Feature> matching =
                    SubscriptionFeatureCatalog.FEATURES.stream()
                            .filter(f -> KEY.equals(f.key())).toList();

            assertThat(matching).hasSize(1);
            assertThat(matching.get(0).label()).isEqualTo("Activity Corner");
        }
    }

    @Nested
    @DisplayName("what the gate must not touch")
    class NotCovered {

        @Test
        @DisplayName("no unrelated path is swept in by the new prefixes")
        void neighboursUnaffected() {
            // /guessIt must not capture other pages, and the feature must not
            // shadow anything that was already gated by a different key.
            assertThat(SubscriptionFeatureCatalog.keyForPath("/home")).isNull();
            assertThat(SubscriptionFeatureCatalog.keyForPath("/memberHome")).isNull();
            assertThat(SubscriptionFeatureCatalog.keyForPath("/groups")).isEqualTo("groups");
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/groups")).isEqualTo("groups");
            assertThat(SubscriptionFeatureCatalog.keyForPath("/bankSync")).isEqualTo("bankSync");
        }

        @Test
        @DisplayName("every previously gated path still maps to the same feature")
        void noRemapping() {
            // A cheap guard against a new prefix silently winning a longest-prefix
            // contest it should not have entered.
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/expense/check-scan")).isEqualTo("scanCheck");
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/expense")).isEqualTo("accounting");
            assertThat(SubscriptionFeatureCatalog.keyForPath("/payroll")).isEqualTo("payroll");
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/plaid")).isEqualTo("bankSync");
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/plaid/webhook")).isNull();
        }
    }
}
