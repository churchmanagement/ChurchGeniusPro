package com.churchgeniuspro.donation;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.controller.DonationReviewController;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.DonationIncomePostingService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Donation Review — Pending / Completed. Completed donations stay in the list but leave
 * the page's Pending figures; Stripe status, the donation row and its Income posting are
 * never touched. Uses the church's real example: 7 donations, $428.00.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Donation Review — Pending / Completed review status")
class DonationReviewStatusTest {

    private static final String CLIENT_ID = "CHR-1";

    @Mock private DonationRepository           donationRepo;
    @Mock private StripeSettingsRepository     stripeRepo;
    @Mock private PublicScreenLinkRepository   linkRepo;
    @Mock private ServiceClientRepository      clientRepo;
    @Mock private ChurchLogoRepository         logoRepo;
    @Mock private EmailService                 emailService;
    @Mock private SubscriptionService          subscriptionService;
    @Mock private DonationIncomePostingService donationIncomePoster;

    private DonationController       listController;
    private DonationReviewController reviewController;

    @BeforeEach
    void setUp() {
        listController = new DonationController(donationRepo, stripeRepo, linkRepo,
                clientRepo, logoRepo, emailService, subscriptionService, donationIncomePoster,
                new com.churchgeniuspro.util.PublicSendLimiter());
        reviewController = new DonationReviewController(donationRepo);
    }

    static MockHttpServletRequest staff(String role, String privilegesJson) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "bookkeeper@example.org");
        session.setAttribute("role", role);
        session.setAttribute("appClientId", CLIENT_ID);
        if (privilegesJson != null) session.setAttribute("privileges", privilegesJson);
        req.setSession(session);
        return req;
    }

    static Donation donation(long id, String first, String last, String amount) {
        Donation d = new Donation();
        d.setId(id);
        d.setClientId(CLIENT_ID);
        d.setFirstName(first);
        d.setLastName(last);
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("usd");
        d.setStatus("succeeded");
        return d;
    }

    /** The church's current list, in the order the page shows it. */
    static List<Donation> sevenDonations() {
        return new ArrayList<>(List.of(
                donation(1, "Binny", "Varghese", "100.00"),
                donation(2, "Binny", "Varghese", "250.00"),
                donation(3, "Priya", "Paul",      "25.00"),
                donation(4, "Lynn",  "Rajan",     "50.00"),
                donation(5, "Anson", "Mathew",     "1.00"),
                donation(6, "ANSON", "MATHEW",     "1.00"),
                donation(7, "Anson", "Mathew",     "1.00")));
    }

    static void complete(Donation d) {
        d.setReviewStatus(Donation.REVIEW_COMPLETED);
        d.setReviewCompletedAt(LocalDateTime.of(2026, 10, 6, 11, 30));
        d.setReviewCompletedBy("bookkeeper@example.org");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(ResponseEntity<?> res, String key) {
        return (List<Map<String, Object>>) ((Map<String, Object>) res.getBody()).get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    /** Pending After Stripe Fees, computed exactly as the page does (integer cents, per donation). */
    static long pendingAfterFeesCents(List<Donation> rows) {
        long total = 0, fees = 0;
        for (Donation d : rows) {
            if (d.isReviewCompleted()) continue;
            long cents = d.getAmount().movePointRight(2).longValueExact();
            total += cents;
            fees  += Math.round(cents * 2.9 / 100) + 30;   // page defaults: 2.9% + $0.30
        }
        return total - fees;
    }

    // ── /api/donations totals ──────────────────────────────────────────────

    @Nested
    @DisplayName("the 7-donation example")
    class SevenDonationExample {

        @Test
        @DisplayName("before: $428.00 / 7 pending / $413.48 after fees, nothing completed")
        void original() {
            List<Donation> rows = sevenDonations();
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(rows);

            ResponseEntity<?> res = listController.getDonations(staff("Accountant", null));

            Map<String, Object> t = list(res, "totals").get(0);
            assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("428.00");
            assertThat(t.get("count")).isEqualTo(7);
            assertThat(list(res, "completedTotals")).isEmpty();
            assertThat(list(res, "rows")).allSatisfy(r -> assertThat(r).containsEntry("reviewStatus", "PENDING"));
            assertThat(pendingAfterFeesCents(rows)).isEqualTo(41348);
        }

        @Test
        @DisplayName("after completing #5–#7: $425.00 / 4 pending / $411.47 after fees / Completed: 3 · $3.00")
        void afterCompletingThreeOneDollarGifts() {
            List<Donation> rows = sevenDonations();
            complete(rows.get(4)); complete(rows.get(5)); complete(rows.get(6));
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(rows);

            ResponseEntity<?> res = listController.getDonations(staff("Accountant", null));

            Map<String, Object> pending = list(res, "totals").get(0);
            assertThat((BigDecimal) pending.get("total")).isEqualByComparingTo("425.00");
            assertThat(pending.get("count")).isEqualTo(4);
            Map<String, Object> done = list(res, "completedTotals").get(0);
            assertThat((BigDecimal) done.get("total")).isEqualByComparingTo("3.00");
            assertThat(done.get("count")).isEqualTo(3);
            // Fees are taken only on the 4 pending gifts — not "$413.48 − $3.00".
            assertThat(pendingAfterFeesCents(rows)).isEqualTo(41147);
        }

        @Test
        @DisplayName("completed donations stay in the list, with Stripe's status unchanged")
        void completedStayListed() {
            List<Donation> rows = sevenDonations();
            complete(rows.get(4)); complete(rows.get(5)); complete(rows.get(6));
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(rows);

            List<Map<String, Object>> out = list(listController.getDonations(staff("Accountant", null)), "rows");

            assertThat(out).hasSize(7);
            assertThat(out).allSatisfy(r -> assertThat(r).containsEntry("status", "succeeded"));
            assertThat(out.subList(0, 4)).allSatisfy(r -> assertThat(r).containsEntry("reviewStatus", "PENDING"));
            assertThat(out.subList(4, 7)).allSatisfy(r -> {
                assertThat(r).containsEntry("reviewStatus", "COMPLETED");
                assertThat(r).containsEntry("reviewCompletedAt", "2026-10-06T11:30");
            });
        }

        @Test
        @DisplayName("everything completed → no pending totals entry, all of it on the Completed line")
        void allCompleted() {
            List<Donation> rows = sevenDonations();
            rows.forEach(DonationReviewStatusTest::complete);
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(rows);

            ResponseEntity<?> res = listController.getDonations(staff("Accountant", null));

            assertThat(list(res, "totals")).isEmpty();
            assertThat((BigDecimal) list(res, "completedTotals").get(0).get("total")).isEqualByComparingTo("428.00");
            assertThat(list(res, "completedTotals").get(0).get("count")).isEqualTo(7);
        }
    }

    // ── POST /api/donations/review-status ──────────────────────────────────

    @Nested
    @DisplayName("marking Completed / Pending")
    class SetStatus {

        private Map<String, Object> req(String status, Object... ids) {
            Map<String, Object> m = new HashMap<>();
            m.put("status", status);
            m.put("ids", List.of(ids));
            return m;
        }

        @Test
        @DisplayName("marks the selected donations Completed, scoped to the session's church, stamped with who and when")
        void marksCompleted() {
            when(donationRepo.countByClientIdAndIdIn(eq(CLIENT_ID), anyCollection())).thenReturn(3L);
            when(donationRepo.markReviewCompleted(eq(CLIENT_ID), anyCollection(), any(), anyString())).thenReturn(3);

            ResponseEntity<?> res = reviewController.setReviewStatus(req("COMPLETED", 5, 6, 7), staff("Accountant", null));

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("updated", 3).containsEntry("requested", 3);
            verify(donationRepo).markReviewCompleted(eq(CLIENT_ID), eq(Set.of(5L, 6L, 7L)), any(LocalDateTime.class),
                    eq("bookkeeper@example.org"));
            verify(donationRepo, never()).markReviewPending(anyString(), anyCollection());
        }

        @Test
        @DisplayName("undo: marks Completed donations back to Pending")
        void undoToPending() {
            when(donationRepo.countByClientIdAndIdIn(eq(CLIENT_ID), anyCollection())).thenReturn(1L);
            when(donationRepo.markReviewPending(eq(CLIENT_ID), anyCollection())).thenReturn(1);

            ResponseEntity<?> res = reviewController.setReviewStatus(req("pending", "6"), staff("Admin", null));

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("status", "PENDING").containsEntry("updated", 1);
            verify(donationRepo).markReviewPending(CLIENT_ID, Set.of(6L));
        }

        @Test
        @DisplayName("an id from another church refuses the whole request and changes nothing")
        void otherChurchRefused() {
            when(donationRepo.countByClientIdAndIdIn(eq(CLIENT_ID), anyCollection())).thenReturn(2L);

            ResponseEntity<?> res = reviewController.setReviewStatus(req("COMPLETED", 5, 6, 999), staff("Accountant", null));

            assertThat(res.getStatusCode().value()).isEqualTo(404);
            verify(donationRepo, never()).markReviewCompleted(anyString(), anyCollection(), any(), any());
            verify(donationRepo, never()).markReviewPending(anyString(), anyCollection());
        }

        @Test
        @DisplayName("roles without donation access are refused before any database call")
        void wrongRoleRefused() {
            for (String role : List.of("User", "Member", "Limited")) {
                ResponseEntity<?> res = reviewController.setReviewStatus(req("COMPLETED", 5), staff(role, null));
                assertThat(res.getStatusCode().value()).as(role).isEqualTo(403);
            }
            assertThat(reviewController.setReviewStatus(req("COMPLETED", 5), new MockHttpServletRequest())
                    .getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(donationRepo);
        }

        @Test
        @DisplayName("an Accountant whose Donation permission is switched off is refused")
        void permissionOffRefused() {
            ResponseEntity<?> res = reviewController.setReviewStatus(req("COMPLETED", 5),
                    staff("Accountant", "{\"accounting.donation\":false}"));

            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(donationRepo);
        }

        @Test
        @DisplayName("bad input is rejected with 400 and nothing changes")
        void badInput() {
            MockHttpServletRequest ok = staff("Accountant", null);
            assertThat(reviewController.setReviewStatus(null, ok).getStatusCode().value()).isEqualTo(400);
            assertThat(reviewController.setReviewStatus(req("DELETED", 5), ok).getStatusCode().value()).isEqualTo(400);
            assertThat(reviewController.setReviewStatus(req("COMPLETED"), ok).getStatusCode().value()).isEqualTo(400);
            assertThat(reviewController.setReviewStatus(req("COMPLETED", -1), ok).getStatusCode().value()).isEqualTo(400);
            assertThat(reviewController.setReviewStatus(req("COMPLETED", "5; drop table"), ok).getStatusCode().value()).isEqualTo(400);
            assertThat(reviewController.setReviewStatus(req("COMPLETED", 1.5), ok).getStatusCode().value()).isEqualTo(400);
            List<Object> many = new ArrayList<>();
            for (int i = 1; i <= 1001; i++) many.add(i);
            Map<String, Object> big = new HashMap<>(Map.of("status", "COMPLETED", "ids", many));
            assertThat(reviewController.setReviewStatus(big, ok).getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(donationRepo);
        }

        @Test
        @DisplayName("review never touches Income, Stripe or email")
        void touchesNothingElse() {
            when(donationRepo.countByClientIdAndIdIn(eq(CLIENT_ID), anyCollection())).thenReturn(1L);
            reviewController.setReviewStatus(req("COMPLETED", 5), staff("Accountant", null));
            verifyNoInteractions(donationIncomePoster, stripeRepo, emailService);
        }
    }

    // ── the page ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the Donation Review page")
    class Page {

        private String page() throws IOException {
            return Files.readString(Paths.get("src/main/resources/static/donationReview.html"));
        }

        @Test
        @DisplayName("uses the approved headings and the Completed line")
        void headings() throws IOException {
            assertThat(page()).contains(">Pending Total<", ">Pending Donations<", ">Pending After Stripe Fees<",
                    "id=\"statCompleted\"", "'Completed: ' + completed.length");
            assertThat(page()).doesNotContain("stat-label\">Total Received<", "stat-label\">Remaining<", "stat-label\">Donations<");
        }

        @Test
        @DisplayName("totals and Stripe fees are taken over pending donations only")
        void pendingOnly() throws IOException {
            String s = page();
            assertThat(s).contains("const pending   = sameCur.filter(function(d) { return !isCompleted(d); });");
            assertThat(s).contains("const feeCents  = pending.reduce(");
            assertThat(s).contains("totalCents = pending.reduce(");
        }

        @Test
        @DisplayName("keeps Stripe's Status column and adds a separate Review column")
        void separateReviewColumn() throws IOException {
            String s = page();
            assertThat(s).contains("title=\"Stripe payment status\">Status</th>", ">Review</th>");
            assertThat(s).contains("statusClass(d.status)", "rv-badge rv-done", "rv-badge rv-pending", "row-done");
        }

        @Test
        @DisplayName("has select, Mark Completed and Mark Pending — and no delete or archive")
        void actions() throws IOException {
            String s = page();
            assertThat(s).contains("id=\"selAll\"", "class=\"row-sel\"", "setReviewStatus('COMPLETED')",
                    "setReviewStatus('PENDING')", "'/api/donations/review-status'");
            assertThat(s.toLowerCase()).doesNotContain("method: 'delete'", "archive");
        }
    }
}
