package com.churchgeniuspro.donation;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Donation Review (V14): the list marks donations that the FORMER automatic hook
 * already put in Income (production donation 42 is one) so the page shows
 * "In Income #n (auto)" rather than a Save button. Church-scoped, read-only,
 * exposes only the income id and amount.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Donation list — rows already in Income from the old workflow")
class DonationListHistoricalPostingTest {

    static final String CLIENT = "CGP-00001";

    @Mock DonationRepository donationRepo;
    @Mock StripeSettingsRepository stripeRepo;
    @Mock PublicScreenLinkRepository linkRepo;
    @Mock ServiceClientRepository clientRepo;
    @Mock ChurchLogoRepository logoRepo;
    @Mock EmailService emailService;
    @Mock SubscriptionService subscriptionService;
    @Mock IncomeRepository incomeRepo;

    DonationController controller;

    @BeforeEach
    void setUp() {
        controller = new DonationController(donationRepo, stripeRepo, linkRepo, clientRepo, logoRepo,
                emailService, subscriptionService, new com.churchgeniuspro.util.PublicSendLimiter());
        controller.setIncomeRepo(incomeRepo);
    }

    static MockHttpServletRequest staff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "bookkeeper@example.org"); s.setAttribute("role", "Accountant"); s.setAttribute("appClientId", CLIENT);
        req.setSession(s);
        return req;
    }

    static Donation donation(long id, String amount, String pi) {
        Donation d = new Donation();
        d.setId(id); d.setClientId(CLIENT); d.setAmount(new BigDecimal(amount)); d.setCurrency("USD"); d.setStatus("succeeded");
        d.setStripePaymentIntentId(pi);
        return d;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ResponseEntity<?> r) { return (List<Map<String, Object>>) ((Map<String, Object>) r.getBody()).get("rows"); }

    @Test
    @DisplayName("production shape: 42 auto-posted (pending), 5 with its income soft-deleted, 3 never posted, a review-posted row, a seeded row with no PaymentIntent")
    void marksHistoricalRows() {
        Donation d42 = donation(42, "50.00", "pi_42");                       // old hook posted gross, review still pending
        Donation d5  = donation(5,  "1.00",  "pi_5");                        // income soft-deleted → not active → re-postable
        Donation d3  = donation(3,  "1.00",  "pi_3");                        // never posted
        Donation d9  = donation(9,  "100.00", "pi_9"); d9.setPostedIncomeId(1035); d9.setPostedExpenseId(2000);  // posted by review
        Donation seeded = donation(77, "40.00", null);                       // trial seed: no PaymentIntent → ledgerRef donation:77
        when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT)).thenReturn(List.of(d42, d5, d3, d9, seeded));

        Income i42 = new Income(); i42.setId(1223); i42.setAmount(new BigDecimal("50.00")); i42.setImportRef("pi_42");
        when(incomeRepo.findByAppClientIdAndImportRefInAndDeleteFlagFalse(eq(CLIENT), anyCollection())).thenReturn(List.of(i42));

        ResponseEntity<?> r = controller.getDonations(null, staff());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        Map<Long, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> m : rows(r)) byId.put(((Number) m.get("id")).longValue(), m);

        assertThat(byId.get(42L)).containsEntry("historicalIncomeId", 1223).containsEntry("inLedger", true).containsEntry("posted", false);
        assertThat(byId.get(42L).get("historicalIncomeAmount")).isEqualTo(new BigDecimal("50.00"));
        assertThat(byId.get(5L)).containsEntry("historicalIncomeId", null).containsEntry("inLedger", false);
        assertThat(byId.get(3L)).containsEntry("historicalIncomeId", null).containsEntry("inLedger", false);
        assertThat(byId.get(9L)).containsEntry("posted", true).containsEntry("postedIncomeId", 1035).containsEntry("inLedger", true)
                .containsEntry("historicalIncomeId", null);   // review-posted rows are not looked up as historical

        // The lookup is church-scoped and asks only for the unposted rows' ledger keys.
        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<String>> refs = ArgumentCaptor.forClass(Collection.class);
        verify(incomeRepo).findByAppClientIdAndImportRefInAndDeleteFlagFalse(eq(CLIENT), refs.capture());
        assertThat(refs.getValue()).containsExactlyInAnyOrder("pi_42", "pi_5", "pi_3", "donation:77");

        // Only ids and amounts are exposed for the income row — no note, member or donor fields from it.
        assertThat(byId.get(42L).keySet()).doesNotContain("historicalIncomeNote", "historicalMember", "historicalGuestName");
    }

    @Test
    @DisplayName("without the repository (unit-test wiring) the list still renders, with no historical flags")
    void toleratesMissingRepo() {
        controller.setIncomeRepo(null);
        when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT)).thenReturn(List.of(donation(1, "10.00", "pi_1")));
        ResponseEntity<?> r = controller.getDonations(null, staff());
        assertThat(rows(r).get(0)).containsEntry("historicalIncomeId", null).containsEntry("inLedger", false);
    }

    @Test
    @DisplayName("a lookup failure degrades to 'no flags' rather than breaking the page")
    void lookupFailureDegrades() {
        when(donationRepo.findByClientIdOrderByDonatedAtDesc(CLIENT)).thenReturn(List.of(donation(1, "10.00", "pi_1")));
        when(incomeRepo.findByAppClientIdAndImportRefInAndDeleteFlagFalse(eq(CLIENT), anyCollection())).thenThrow(new IllegalStateException("db"));
        ResponseEntity<?> r = controller.getDonations(null, staff());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(rows(r).get(0)).containsEntry("historicalIncomeId", null);
        verify(donationRepo, never()).save(any());
    }

    @Test
    @DisplayName("the page shows the historical badge and withholds Save for such rows")
    void pageRendersBadge() throws Exception {
        String page = java.nio.file.Files.readString(java.nio.file.Paths.get("src/main/resources/static/donationReview.html"));
        assertThat(page).contains("d.historicalIncomeId", "In Income #' + d.historicalIncomeId + ' (auto)", "acct-hist");
        assertThat(page).contains("return d && !d.posted && !d.historicalIncomeId;");          // Save selected skips them
        assertThat(page).contains("return !d.posted && !d.historicalIncomeId;");                // button enablement
    }
}
