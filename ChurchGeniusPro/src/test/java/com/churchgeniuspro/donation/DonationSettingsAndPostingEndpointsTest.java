package com.churchgeniuspro.donation;

import com.churchgeniuspro.controller.DonationPostingController;
import com.churchgeniuspro.controller.DonationSettingsController;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.DonationSettings;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.DonationSettingsRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.DonationFeeService;
import com.churchgeniuspro.service.DonationPostingService;
import com.churchgeniuspro.service.DonationSettingsService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Donation Settings and posting endpoints — the same gate as the review page
 * (Accountant/Admin + accounting.donation), strict church scoping, and the
 * statuses the page acts on (422 settings-incomplete, 422 fee-unavailable, 404).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Donation Settings + posting endpoints — gate, scoping, statuses")
class DonationSettingsAndPostingEndpointsTest {

    static final String CLIENT = "CHR-1";
    static final String PERM_ON  = "{\"accounting.donation\":true}";
    static final String PERM_OFF = "{\"accounting.donation\":false}";

    @Mock DonationSettingsRepository settingsRepo;
    @Mock SubSourceRepository subSourceRepo;
    @Mock MainSourceRepository mainSourceRepo;
    @Mock PurposeRepository purposeRepo;
    @Mock TransactionTypeRepository ttRepo;
    @Mock DonationPostingService posting;
    @Mock DonationFeeService fees;
    @Mock DonationRepository donationRepo;
    @Mock IncomeRepository incomeRepo;
    @Mock ExpenseRepository expenseRepo;

    DonationSettingsService settingsService;
    DonationSettingsController settingsController;
    DonationPostingController postingController;

    @BeforeEach
    void setUp() {
        settingsService = new DonationSettingsService(settingsRepo, subSourceRepo, mainSourceRepo, purposeRepo, ttRepo);
        settingsController = new DonationSettingsController(settingsService);
        postingController = new DonationPostingController(posting, fees, donationRepo, incomeRepo, expenseRepo);

        SubSource fund = new SubSource(); fund.setId(5); fund.setSourceName("Tithe");
        TransactionType bt = new TransactionType(); bt.setId(9); bt.setTypeName("Bank Transfer");
        Purpose adm = new Purpose(); adm.setId(3); adm.setPurposeName("Administrative");
        MainSource gen = new MainSource(); gen.setId(2); gen.setSourceName("General Fund");
        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, CLIENT)).thenReturn(Optional.of(fund));
        when(ttRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, CLIENT)).thenReturn(Optional.of(bt));
        when(purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(3, CLIENT)).thenReturn(Optional.of(adm));
        when(mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(2, CLIENT)).thenReturn(Optional.of(gen));
        // The mock "persists": after a save, findByClientId returns the saved row.
        final DonationSettings[] stored = new DonationSettings[1];
        when(settingsRepo.findByClientId(CLIENT)).thenAnswer(inv -> Optional.ofNullable(stored[0]));
        when(settingsRepo.save(any())).thenAnswer(inv -> { stored[0] = inv.getArgument(0); return stored[0]; });
    }

    static MockHttpServletRequest staff(String role, String privilegesJson) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "bookkeeper@example.org");
        session.setAttribute("role", role);
        session.setAttribute("appClientId", CLIENT);
        if (privilegesJson != null) session.setAttribute("privileges", privilegesJson);
        req.setSession(session);
        return req;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    static Map<String, Object> fullSettings() {
        Map<String, Object> b = new HashMap<>();
        b.put("incomeSubSourceId", 5); b.put("incomeTransactionTypeId", 9);
        b.put("expensePurposeId", 3); b.put("expenseMainSourceId", 2); b.put("expenseTransactionTypeId", 9);
        b.put("dateMode", "DONATION_DATE"); b.put("feePercent", "2.9"); b.put("feeFixed", "0.30");
        return b;
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Donation Settings")
    class Settings {

        @Test
        @DisplayName("a complete, valid settings body is saved and comes back complete")
        void savesCompleteSettings() {
            ResponseEntity<?> r = settingsController.put(fullSettings(), staff("Accountant", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(body(r)).containsEntry("complete", true).containsEntry("accountingMode", "GROSS_WITH_FEE_EXPENSE");
            assertThat(String.valueOf(body(r).get("missing"))).isEqualTo("[]");
            verify(settingsRepo).save(any());
        }

        @Test
        @DisplayName("GET before anything is saved: configured=false, complete=false, the brief's message, defaults filled")
        void getUnconfigured() {
            ResponseEntity<?> r = settingsController.get(staff("Admin", PERM_ON));
            assertThat(body(r)).containsEntry("configured", false).containsEntry("complete", false)
                    .containsEntry("incompleteMessage", DonationSettingsService.INCOMPLETE_MESSAGE)
                    .containsEntry("incomeNoteTemplate", DonationSettings.DEFAULT_INCOME_NOTE);
            assertThat(String.valueOf(body(r).get("missing"))).contains("incomeSubSourceId").contains("expensePurposeId");
        }

        @Test
        @DisplayName("a fund/method/purpose id from another church is refused as not found, and nothing is saved")
        void foreignIdsRefused() {
            for (String key : List.of("incomeSubSourceId", "incomeTransactionTypeId", "expensePurposeId", "expenseMainSourceId", "expenseTransactionTypeId")) {
                Map<String, Object> b = fullSettings(); b.put(key, 777);
                ResponseEntity<?> r = settingsController.put(b, staff("Admin", PERM_ON));
                assertThat(r.getStatusCode().value()).as(key).isEqualTo(400);
                assertThat(String.valueOf(body(r).get("error"))).contains("not found for this church");
            }
            verify(settingsRepo, never()).save(any());
        }

        @Test
        @DisplayName("mode B / net-only is not accepted in this phase")
        void netOnlyRefused() {
            Map<String, Object> b = fullSettings(); b.put("accountingMode", "NET_ONLY");
            ResponseEntity<?> r = settingsController.put(b, staff("Admin", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(400);
            verify(settingsRepo, never()).save(any());
        }

        @Test
        @DisplayName("fee rate: negative refused, half a rate refused, both blank allowed (no estimate then)")
        void feeRateValidation() {
            Map<String, Object> neg = fullSettings(); neg.put("feePercent", "-1");
            assertThat(settingsController.put(neg, staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
            Map<String, Object> half = fullSettings(); half.put("feeFixed", "");
            assertThat(settingsController.put(half, staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
            Map<String, Object> none = fullSettings(); none.remove("feePercent"); none.remove("feeFixed");
            ResponseEntity<?> r = settingsController.put(none, staff("Admin", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(body(r)).containsEntry("hasFeeRate", false).containsEntry("complete", true);
        }

        @Test
        @DisplayName("a missing mandatory field is refused by name")
        void missingMandatory() {
            Map<String, Object> b = fullSettings(); b.remove("expensePurposeId");
            ResponseEntity<?> r = settingsController.put(b, staff("Admin", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(String.valueOf(body(r).get("error"))).contains("Expense type is required");
        }

        @Test
        @DisplayName("gate: a non-accounting role, or the permission switched off, is refused on GET and PUT")
        void gate() {
            assertThat(settingsController.get(staff("Member", PERM_ON)).getStatusCode().value()).isEqualTo(403);
            assertThat(settingsController.put(fullSettings(), staff("Member", PERM_ON)).getStatusCode().value()).isEqualTo(403);
            assertThat(settingsController.get(staff("Accountant", PERM_OFF)).getStatusCode().value()).isEqualTo(403);
            assertThat(settingsController.put(fullSettings(), staff("Accountant", PERM_OFF)).getStatusCode().value()).isEqualTo(403);
            assertThat(settingsController.get(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
            verify(settingsRepo, never()).save(any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("posting endpoints")
    class Posting {

        @Test
        @DisplayName("settings incomplete → 422 with the brief's message and code SETTINGS_INCOMPLETE")
        void settingsIncomplete422() {
            when(posting.post(eq(1L), eq(CLIENT), any(), anyString()))
                    .thenThrow(new DonationSettingsService.IncompleteSettingsException(List.of("expensePurposeId")));
            ResponseEntity<?> r = postingController.post(1L, Map.of(), staff("Accountant", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(422);
            assertThat(body(r)).containsEntry("code", "SETTINGS_INCOMPLETE")
                    .containsEntry("error", DonationSettingsService.INCOMPLETE_MESSAGE);
            assertThat(String.valueOf(body(r).get("missing"))).isEqualTo("[expensePurposeId]");
        }

        @Test
        @DisplayName("fee unavailable → 422 FEE_UNAVAILABLE; unsupported donation → 422 UNSUPPORTED; not found → 404")
        void otherStatuses() {
            when(posting.post(eq(2L), eq(CLIENT), any(), anyString())).thenThrow(new DonationFeeService.FeeUnavailableException("no fee"));
            when(posting.post(eq(3L), eq(CLIENT), any(), anyString())).thenThrow(new DonationPostingService.UnsupportedDonationException("fee cover"));
            when(posting.post(eq(4L), eq(CLIENT), any(), anyString())).thenThrow(new DonationPostingService.NotFoundException("Donation not found."));
            when(posting.post(eq(5L), eq(CLIENT), any(), anyString())).thenThrow(new DonationPostingService.InvalidReviewException("bad override"));
            assertThat(body(postingController.post(2L, null, staff("Admin", PERM_ON)))).containsEntry("code", "FEE_UNAVAILABLE");
            assertThat(body(postingController.post(3L, null, staff("Admin", PERM_ON)))).containsEntry("code", "UNSUPPORTED");
            assertThat(postingController.post(4L, null, staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(404);
            assertThat(postingController.post(5L, null, staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("a successful post returns the result as-is")
        void postOk() {
            when(posting.post(eq(1L), eq(CLIENT), any(), eq("bookkeeper@example.org")))
                    .thenReturn(new DonationPostingService.PostResult(1L, "POSTED", 501, 901, "Posted.", false));
            ResponseEntity<?> r = postingController.post(1L, Map.of("incomeNote", "x"), staff("Accountant", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(body(r)).containsEntry("status", "POSTED").containsEntry("incomeId", 501).containsEntry("expenseId", 901);
        }

        @Test
        @DisplayName("batch: any id outside this church refuses the whole request before anything is posted")
        void batchAllOrNothingOnOwnership() {
            when(donationRepo.countByClientIdAndIdIn(eq(CLIENT), anyCollection())).thenReturn(2L);
            ResponseEntity<?> r = postingController.postBatch(Map.of("ids", List.of(1, 2, 3)), staff("Admin", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(404);
            verify(posting, never()).postBatch(anyCollection(), anyString(), anyString());
        }

        @Test
        @DisplayName("batch: per-id outcomes are returned with counts")
        void batchOutcomes() {
            when(donationRepo.countByClientIdAndIdIn(eq(CLIENT), anyCollection())).thenReturn(3L);
            when(posting.postBatch(anyCollection(), eq(CLIENT), anyString())).thenReturn(List.of(
                    new DonationPostingService.PostResult(1L, "POSTED", 501, 901, "Posted.", false),
                    new DonationPostingService.PostResult(2L, "ALREADY_POSTED", 42, null, "historical", true),
                    new DonationPostingService.PostResult(3L, "FAILED", null, null, "no fee", false)));
            ResponseEntity<?> r = postingController.postBatch(Map.of("ids", List.of(1, 2, 3)), staff("Admin", PERM_ON));
            assertThat(body(r)).containsEntry("requested", 3).containsEntry("posted", 1).containsEntry("alreadyPosted", 1).containsEntry("failed", 1);
            assertThat((List<?>) body(r).get("results")).hasSize(3);
        }

        @Test
        @DisplayName("batch: bad ids and empty lists are refused")
        void batchInput() {
            assertThat(postingController.postBatch(null, staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
            assertThat(postingController.postBatch(Map.of("ids", List.of()), staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
            assertThat(postingController.postBatch(Map.of("ids", List.of("x")), staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
            assertThat(postingController.postBatch(Map.of("ids", List.of(-1)), staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
            List<Integer> tooMany = new ArrayList<>();
            for (int i = 1; i <= 1001; i++) tooMany.add(i);   // MAX_IDS is 1000
            assertThat(postingController.postBatch(Map.of("ids", tooMany), staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("gate on every endpoint: wrong role, permission off, no session → 403, service never called")
        void gate() {
            for (MockHttpServletRequest req : List.of(staff("Member", PERM_ON), staff("Accountant", PERM_OFF), new MockHttpServletRequest())) {
                assertThat(postingController.preview(1L, req).getStatusCode().value()).isEqualTo(403);
                assertThat(postingController.post(1L, Map.of(), req).getStatusCode().value()).isEqualTo(403);
                assertThat(postingController.postBatch(Map.of("ids", List.of(1)), req).getStatusCode().value()).isEqualTo(403);
                assertThat(postingController.refreshFee(1L, req).getStatusCode().value()).isEqualTo(403);
                assertThat(postingController.reconciliation(req).getStatusCode().value()).isEqualTo(403);
            }
            verify(posting, never()).post(any(), anyString(), any(), anyString());
            verify(posting, never()).preview(any(), anyString(), any());
            verify(posting, never()).postBatch(anyCollection(), anyString(), anyString());
        }

        @Test
        @DisplayName("refresh-fee: another church's donation → 404; ours → asks the fee service and reports")
        void refreshFee() {
            when(donationRepo.findByIdAndClientId(9L, CLIENT)).thenReturn(Optional.empty());
            assertThat(postingController.refreshFee(9L, staff("Admin", PERM_ON)).getStatusCode().value()).isEqualTo(404);

            Donation d = new Donation(); d.setId(1L); d.setClientId(CLIENT); d.setAmount(new BigDecimal("100.00"));
            d.setStripeChargeId("ch_3AbCdEfGhIjKlMnOpQrStUvW");
            when(donationRepo.findByIdAndClientId(1L, CLIENT)).thenReturn(Optional.of(d));
            when(fees.refresh(d)).thenReturn(new DonationFeeService.Refresh(DonationFeeService.RefreshStatus.NOT_SETTLED, Optional.empty(), null));
            ResponseEntity<?> r = postingController.refreshFee(1L, staff("Admin", PERM_ON));
            assertThat(body(r)).containsEntry("refreshed", false).containsEntry("status", "NOT_SETTLED");
            assertThat(String.valueOf(body(r).get("message"))).contains("not reported");

            when(fees.refresh(d)).thenReturn(new DonationFeeService.Refresh(DonationFeeService.RefreshStatus.KEY_LACKS_PERMISSION, Optional.empty(), "Forbidden"));
            r = postingController.refreshFee(1L, staff("Admin", PERM_ON));
            assertThat(body(r)).containsEntry("refreshed", false).containsEntry("status", "KEY_LACKS_PERMISSION");
            assertThat(String.valueOf(body(r).get("message"))).contains("Balance transactions: read");
        }

        @Test
        @DisplayName("reconciliation is read-only and buckets every donation")
        void reconciliation() {
            Donation pending = new Donation(); pending.setId(1L); pending.setClientId(CLIENT); pending.setAmount(new BigDecimal("25.00")); pending.setStripePaymentIntentId("pi_1");
            Donation historical = new Donation(); historical.setId(2L); historical.setClientId(CLIENT); historical.setAmount(new BigDecimal("100.00")); historical.setStripePaymentIntentId("pi_2");
            Donation byReview = new Donation(); byReview.setId(3L); byReview.setClientId(CLIENT); byReview.setAmount(new BigDecimal("50.00")); byReview.setStripePaymentIntentId("pi_3"); byReview.setPostedIncomeId(501);
            Donation reviewedOnly = new Donation(); reviewedOnly.setId(4L); reviewedOnly.setClientId(CLIENT); reviewedOnly.setAmount(new BigDecimal("1.00")); reviewedOnly.setStripePaymentIntentId("pi_4"); reviewedOnly.setReviewStatus(Donation.REVIEW_COMPLETED);
            when(donationRepo.findByClientIdOrderByReviewStatusAscDonatedAtDesc(CLIENT)).thenReturn(List.of(pending, historical, byReview, reviewedOnly));
            Income old = new Income(); old.setId(42); old.setAmount(new BigDecimal("100.00")); old.setImportRef("pi_2"); old.setCreatedBy("online-giving");
            TransactionType card = new TransactionType(); card.setTypeName("Visa •••• 5504"); old.setTransactionType(card);
            when(incomeRepo.findByAppClientIdAndImportRefInAndDeleteFlagFalse(eq(CLIENT), anyCollection())).thenReturn(List.of(old));
            when(expenseRepo.findByAppClientIdAndImportRefInAndDeleteFlagFalse(eq(CLIENT), anyCollection())).thenReturn(List.of());

            ResponseEntity<?> r = postingController.reconciliation(staff("Accountant", PERM_ON));
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> b = body(r);
            assertThat(b).containsEntry("readOnly", true).containsEntry("total", 4);
            @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) b.get("counts");
            assertThat(counts).containsEntry("PENDING", 1).containsEntry("POSTED_HISTORICAL", 1)
                    .containsEntry("POSTED_BY_REVIEW", 1).containsEntry("REVIEWED_NO_LEDGER", 1);
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) b.get("rows");
            Map<String, Object> hist = rows.stream().filter(m -> Long.valueOf(2L).equals(m.get("donationId"))).findFirst().get();
            assertThat(hist).containsEntry("bucket", "POSTED_HISTORICAL").containsEntry("incomeId", 42)
                    .containsEntry("incomeMethod", "Visa •••• 5504").containsEntry("cardLabelMethod", true);
            // read-only: nothing saved anywhere
            verify(donationRepo, never()).save(any());
            verify(donationRepo, never()).saveAll(anyList());
            verify(incomeRepo, never()).save(any());
            verify(expenseRepo, never()).save(any());
        }
    }
}
