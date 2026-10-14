package com.churchgeniuspro.donation;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.service.DonationPostingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Approval decision 4 — review status and accounting status are separate.
 * Mark Completed / Return to Pending change the three review fields and nothing
 * else; a donation returned to Pending keeps its posting links, so it cannot be
 * posted again (DonationPostingService short-circuits on posted_income_id, and
 * the income row's import_ref stays taken).
 */
@DisplayName("Donation Review — status changes never touch posting or ledger fields")
class DonationReviewStatusKeepsLedgerTest {

    static String querySql(String methodName) throws IOException {
        String src = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/repository/DonationRepository.java"));
        // The @Query(...) immediately preceding the method declaration.
        int at = src.indexOf(" " + methodName + "(");
        assertThat(at).as("method " + methodName).isGreaterThan(0);
        int q = src.lastIndexOf("@Query(", at);
        return src.substring(q, at);
    }

    @Test
    @DisplayName("markReviewCompleted and markReviewPending update review_* only")
    void statusQueriesSetOnlyReviewFields() throws IOException {
        for (String m : new String[] { "markReviewCompleted", "markReviewPending" }) {
            String sql = querySql(m);
            Matcher set = Pattern.compile("set\\s(.*?)\\bwhere\\b", Pattern.DOTALL).matcher(sql);
            assertThat(set.find()).as(m + " has a set clause").isTrue();
            String assigned = set.group(1);
            assertThat(assigned).contains("d.reviewStatus");
            for (String forbidden : new String[] { "posted", "stripeFee", "stripeNet", "matchedMember", "contributorCategory", "amount", "income", "expense" }) {
                assertThat(assigned).as(m + " must not assign " + forbidden).doesNotContainIgnoringCase(forbidden);
            }
        }
    }

    @Test
    @DisplayName("DonationRepository exposes no method that deletes or edits income/expense")
    void repositoryHasNoLedgerWrites() {
        for (Method m : DonationRepository.class.getDeclaredMethods()) {
            assertThat(m.getName()).doesNotContainIgnoringCase("income").doesNotContainIgnoringCase("expense");
        }
    }

    @Test
    @DisplayName("a posted donation returned to Pending still reads as posted — the links survive the status change")
    void returnedToPendingStaysPosted() {
        Donation d = new Donation();
        d.setId(1L); d.setClientId("CHR-1"); d.setAmount(new BigDecimal("100.00"));
        d.setPostedIncomeId(501); d.setPostedExpenseId(901);
        d.setReviewStatus(Donation.REVIEW_COMPLETED);
        // what markReviewPending does, field by field
        d.setReviewStatus(null); d.setReviewCompletedAt(null); d.setReviewCompletedBy(null);
        assertThat(d.isReviewCompleted()).isFalse();
        assertThat(d.isPosted()).isTrue();
        assertThat(d.getPostedIncomeId()).isEqualTo(501);
        assertThat(d.getPostedExpenseId()).isEqualTo(901);
    }

    @Test
    @DisplayName("the fee expense import_ref is derived from the PaymentIntent id, so it is as unique as the income's")
    void feeImportRefDerivation() {
        Donation d = new Donation(); d.setStripePaymentIntentId("pi_3AbC");
        assertThat(DonationPostingService.feeImportRef(d)).isEqualTo("pi_3AbC:stripe-fee");
        Income i = new Income(); i.setImportRef("pi_3AbC");
        assertThat(DonationPostingService.feeImportRef(d)).isNotEqualTo(i.getImportRef());
        // No PaymentIntent (seeded demo/trial rows in production): a per-donation key, never "null".
        Donation seeded = new Donation(); seeded.setId(31L);
        assertThat(DonationPostingService.ledgerRef(seeded)).isEqualTo("donation:31");
        assertThat(DonationPostingService.feeImportRef(seeded)).isEqualTo("donation:31:stripe-fee");
    }
}
