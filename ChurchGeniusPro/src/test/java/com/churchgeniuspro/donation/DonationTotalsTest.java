package com.churchgeniuspro.donation;

import com.churchgeniuspro.controller.DonationController;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Financial audit M11: {@code /api/donations} returned donation rows only — the
 * review page's headline total was {@code rows.reduce((s,d)=>s+parseFloat(d.amount))},
 * plain floating-point addition with no independent server figure to catch drift on
 * a large list. The endpoint now also returns {@code totals}: an exact BigDecimal
 * sum per currency, accumulated from the very same rows it sends in the very same
 * pass, so the two can never disagree.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Donations API — server-computed totals (M11)")
class DonationTotalsTest {

    private static final String CLIENT_ID = "CHR-1";

    @Mock private DonationRepository           donationRepo;
    @Mock private StripeSettingsRepository     stripeRepo;
    @Mock private PublicScreenLinkRepository   linkRepo;
    @Mock private ServiceClientRepository      clientRepo;
    @Mock private ChurchLogoRepository         logoRepo;
    @Mock private EmailService                 emailService;
    @Mock private SubscriptionService          subscriptionService;
    @Mock private DonationIncomePostingService donationIncomePoster;

    private DonationController controller;

    @BeforeEach
    void setUp() {
        controller = new DonationController(donationRepo, stripeRepo, linkRepo,
                clientRepo, logoRepo, emailService, subscriptionService, donationIncomePoster,
                new com.churchgeniuspro.util.PublicSendLimiter());
    }

    private static MockHttpServletRequest staffRequest(String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "bookkeeper@example.org");
        session.setAttribute("role", role);
        session.setAttribute("appClientId", CLIENT_ID);
        req.setSession(session);
        return req;
    }

    private static Donation donation(long id, String amount, String currency) {
        Donation d = new Donation();
        d.setId(id);
        d.setClientId(CLIENT_ID);
        d.setAmount(new BigDecimal(amount));
        d.setCurrency(currency);
        d.setStatus("succeeded");
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(ResponseEntity<?> res) {
        return (List<Map<String, Object>>) body(res).get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> totalsOf(ResponseEntity<?> res) {
        return (List<Map<String, Object>>) body(res).get("totals");
    }

    private static Map<String, Object> totalFor(ResponseEntity<?> res, String currency) {
        return totalsOf(res).stream()
                .filter(t -> currency.equals(t.get("currency")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no totals entry for " + currency));
    }

    @Nested
    @DisplayName("response shape")
    class ResponseShape {

        @Test
        @DisplayName("still returns every row field the review page relies on")
        void rowsCarryAllFields() {
            Donation d = donation(1, "25.00", "usd");
            d.setFirstName("Jane");
            d.setLastName("Doe");
            d.setEmail("jane@example.org");
            d.setPhone("5551234567");
            d.setNote("For the roof fund");
            d.setPaymentMethod("Visa •••• 4242");
            d.setStripePaymentIntentId("pi_123");
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(List.of(d));

            Map<String, Object> row = rowsOf(controller.getDonations(staffRequest("Accountant"))).get(0);

            assertThat(row).containsEntry("id", 1L);
            assertThat(row).containsEntry("firstName", "Jane");
            assertThat(row).containsEntry("lastName", "Doe");
            assertThat(row).containsEntry("email", "jane@example.org");
            assertThat(row).containsEntry("phone", "5551234567");
            assertThat(row).containsEntry("note", "For the roof fund");
            assertThat(row).containsEntry("paymentMethod", "Visa •••• 4242");
            assertThat(row).containsEntry("amount", new BigDecimal("25.00"));
            assertThat(row).containsEntry("currency", "usd");
            assertThat(row).containsEntry("status", "succeeded");
            assertThat(row).containsEntry("stripePaymentIntentId", "pi_123");
        }

        @Test
        @DisplayName("an empty org has empty rows and empty totals — no spurious zero entry")
        void emptyOrgHasNoTotalsEntries() {
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(List.of());

            ResponseEntity<?> res = controller.getDonations(staffRequest("Accountant"));

            assertThat(rowsOf(res)).isEmpty();
            assertThat(totalsOf(res)).isEmpty();
        }

        @Test
        @DisplayName("access control is unchanged — wrong role is still refused")
        void wrongRoleStillRefused() {
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(List.of());

            ResponseEntity<?> res = controller.getDonations(staffRequest("User"));

            assertThat(res.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("access control is unchanged — no session is still refused")
        void noSessionStillRefused() {
            ResponseEntity<?> res = controller.getDonations(new MockHttpServletRequest());

            assertThat(res.getStatusCode().value()).isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("totals are exact, never float-summed")
    class ExactTotals {

        @Test
        @DisplayName("500 rows of a fractional-cent-prone amount still sum to the exact expected total")
        void manySmallAmountsSumExactly() {
            // 0.10 has no exact binary floating-point representation, so naively
            // accumulating it hundreds of times in a double (or a JS number, as the
            // client used to) visibly drifts from the true sum. BigDecimal addition
            // of scale-2 values is exact at any list size — this is the whole point
            // of doing the summation here instead of trusting the client's redo.
            List<Donation> rows = new ArrayList<>();
            for (int i = 0; i < 500; i++) rows.add(donation(i, "0.10", "usd"));
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(rows);

            ResponseEntity<?> res = controller.getDonations(staffRequest("Accountant"));

            Map<String, Object> usd = totalFor(res, "usd");
            assertThat((BigDecimal) usd.get("total")).isEqualByComparingTo("50.00");
            assertThat(usd.get("count")).isEqualTo(500);
        }

        @Test
        @DisplayName("totals are grouped per currency — one currency's rows never inflate another's total")
        void perCurrencyGrouping() {
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(List.of(
                    donation(1, "25.00", "usd"),
                    donation(2, "10.00", "usd"),
                    donation(3, "100.00", "eur"))); // historical/out-of-band row — the exact M2 scenario

            ResponseEntity<?> res = controller.getDonations(staffRequest("Accountant"));

            assertThat(totalsOf(res)).hasSize(2);
            Map<String, Object> usd = totalFor(res, "usd");
            assertThat((BigDecimal) usd.get("total")).isEqualByComparingTo("35.00");
            assertThat(usd.get("count")).isEqualTo(2);
            Map<String, Object> eur = totalFor(res, "eur");
            assertThat((BigDecimal) eur.get("total")).isEqualByComparingTo("100.00");
            assertThat(eur.get("count")).isEqualTo(1);
        }

        @Test
        @DisplayName("a null currency is normalized to \"USD\" — the same rule (and the same case) the review page applies")
        void nullCurrencyNormalizedToUsd() {
            // The client's own grouping key is `rows[0].currency || 'USD'` — a currency
            // that IS present passes through verbatim, uncased; only a missing/blank one
            // becomes the literal string "USD". The server has to normalize null the
            // same way, to the same case, or the two total lookups would never match for
            // the very case they exist to handle (a mix of legacy null-currency rows and
            // real ones) — so this deliberately does NOT test case-folding "usd" rows in
            // together; that was never how the client behaves either.
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(List.of(
                    donation(1, "25.00", null),
                    donation(2, "15.00", "USD")));

            ResponseEntity<?> res = controller.getDonations(staffRequest("Accountant"));

            assertThat(totalsOf(res)).hasSize(1);
            Map<String, Object> usd = totalFor(res, "USD");
            assertThat((BigDecimal) usd.get("total")).isEqualByComparingTo("40.00");
            assertThat(usd.get("count")).isEqualTo(2);
        }

        @Test
        @DisplayName("currency casing is preserved, not folded — a lowercase \"usd\" row stays distinct from an uppercase \"USD\" row")
        void currencyCaseIsPreservedNotFolded() {
            // This looks like it should probably merge, but it must NOT: the client's own
            // "which currency am I displaying" key (rows[0].currency || 'USD') is a plain,
            // uncased string compare. If the server folded case here, its totals map would
            // use a key the client-side lookup could never match for a real lowercase
            // "usd" row (donation.html always sends "usd" lowercase — see M2), silently
            // breaking the M11 reconciliation for the common case instead of the rare one.
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(List.of(
                    donation(1, "25.00", "usd"),
                    donation(2, "15.00", "USD")));

            ResponseEntity<?> res = controller.getDonations(staffRequest("Accountant"));

            assertThat(totalsOf(res)).hasSize(2);
            assertThat((BigDecimal) totalFor(res, "usd").get("total")).isEqualByComparingTo("25.00");
            assertThat((BigDecimal) totalFor(res, "USD").get("total")).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("totals are computed only from the tenant-scoped repository call — no second, unscoped lookup")
        void totalsHaveNoSecondDataSource() {
            when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT_ID)).thenReturn(List.of(
                    donation(1, "25.00", "usd")));

            ResponseEntity<?> res = controller.getDonations(staffRequest("Accountant"));

            assertThat((BigDecimal) totalFor(res, "usd").get("total")).isEqualByComparingTo("25.00");
            verify(donationRepo).findByClientIdOrderByDonatedAtDesc(CLIENT_ID);
            verifyNoMoreInteractions(donationRepo);
        }
    }
}
