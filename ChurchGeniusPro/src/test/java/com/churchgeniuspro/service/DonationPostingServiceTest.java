package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.DonationSettings;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Donation Review — posting. The approved mode only: the full charge as Income,
 * Stripe's fee as Expense, linked back to the donation, in one transaction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DonationPostingService — gross Income + fee Expense, exactly once")
class DonationPostingServiceTest {

    static final String CLIENT = "CHR-1";
    static final String PI = "pi_3AbCdEfGhIjKlMnOpQrStUvW";

    /** Counts commits and rollbacks so a test can prove the transaction outcome. */
    static final class CountingTxManager implements PlatformTransactionManager {
        int begun, committed, rolledBack;
        @Override public TransactionStatus getTransaction(TransactionDefinition d) { begun++; return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus s) { committed++; }
        @Override public void rollback(TransactionStatus s) { rolledBack++; }
    }

    @Mock DonationRepository donationRepo;
    @Mock SubSourceRepository subSourceRepo;
    @Mock MainSourceRepository mainSourceRepo;
    @Mock PurposeRepository purposeRepo;
    @Mock TransactionTypeRepository ttRepo;
    @Mock FamilyMemberRepository memberRepo;
    @Mock IncomeService incomeService;
    @Mock ExpenseService expenseService;
    @Mock IncomeRepository incomeRepo;
    @Mock ExpenseRepository expenseRepo;
    @Mock DonationFeeService feeService;

    CountingTxManager txm;
    DonationSettingsService settingsService;
    ContributorMatcher matcher;
    DonationPostingService svc;
    DonationSettings settings;

    SubSource tithe; TransactionType bankTransfer, cardMethod; Purpose admin; MainSource general;
    FamilyMember binny;

    @BeforeEach
    void setUp() {
        tithe = new SubSource(); tithe.setId(5); tithe.setSourceName("Tithe"); tithe.setAppClientId(CLIENT);
        bankTransfer = new TransactionType(); bankTransfer.setId(9); bankTransfer.setTypeName("Bank Transfer"); bankTransfer.setAppClientId(CLIENT);
        cardMethod = new TransactionType(); cardMethod.setId(10); cardMethod.setTypeName("Card"); cardMethod.setAppClientId(CLIENT);
        admin = new Purpose(); admin.setId(3); admin.setPurposeName("Administrative"); admin.setAppClientId(CLIENT);
        general = new MainSource(); general.setId(2); general.setSourceName("General Fund"); general.setAppClientId(CLIENT);
        binny = new FamilyMember(); binny.setId(11); binny.setFirstName("Binny"); binny.setLastName("Varghese");
        binny.setEmail("binny@example.org"); binny.setMemberType("Member"); binny.setAppClientId(CLIENT);

        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, CLIENT)).thenReturn(Optional.of(tithe));
        when(ttRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, CLIENT)).thenReturn(Optional.of(bankTransfer));
        when(ttRepo.findByIdAndAppClientIdAndDeleteFlagFalse(10, CLIENT)).thenReturn(Optional.of(cardMethod));
        when(purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(3, CLIENT)).thenReturn(Optional.of(admin));
        when(mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(2, CLIENT)).thenReturn(Optional.of(general));
        when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
        when(memberRepo.findActiveByNameAndTenant(anyString(), anyString(), eq(CLIENT))).thenReturn(List.of());
        when(memberRepo.findActiveWithPhoneByTenant(CLIENT)).thenReturn(List.of());
        when(memberRepo.findByIdAndTenant(11, CLIENT)).thenReturn(Optional.of(binny));

        settings = new DonationSettings();
        settings.setClientId(CLIENT);
        settings.setIncomeSubSourceId(5); settings.setIncomeTransactionTypeId(9);
        settings.setExpensePurposeId(3); settings.setExpenseMainSourceId(2); settings.setExpenseTransactionTypeId(10);
        settings.setDateMode(DonationSettings.DATE_DONATION);
        settings.setFeePercent(new BigDecimal("2.900")); settings.setFeeFixed(new BigDecimal("0.30"));
        settings.setIncomeNoteTemplate(DonationSettings.DEFAULT_INCOME_NOTE);
        settings.setExpenseNoteTemplate(DonationSettings.DEFAULT_EXPENSE_NOTE);

        com.churchgeniuspro.repository.DonationSettingsRepository settingsRepo = mock(com.churchgeniuspro.repository.DonationSettingsRepository.class);
        when(settingsRepo.findByClientId(CLIENT)).thenReturn(Optional.of(settings));
        settingsService = new DonationSettingsService(settingsRepo, subSourceRepo, mainSourceRepo, purposeRepo, ttRepo);
        matcher = new ContributorMatcher(memberRepo);

        when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(anyString(), anyString())).thenReturn(Optional.empty());
        when(expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(anyString(), anyString())).thenReturn(Optional.empty());

        // Stripe reported $3.20 on $100.00 unless a test says otherwise.
        when(feeService.resolve(any(), any())).thenAnswer(inv -> {
            Donation d = inv.getArgument(0);
            if (d == null) return null;                    // Mockito re-stubbing probe
            BigDecimal fee = new BigDecimal("3.20");
            return new DonationFeeService.Resolved(fee, d.getAmount().subtract(fee), Donation.FEE_SOURCE_STRIPE, false);
        });

        when(incomeService.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> { Income i = new Income(); i.setId(501); i.setAmount(inv.getArgument(5)); i.setImportRef(inv.getArgument(11)); return i; });
        when(expenseService.createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> { Expense e = new Expense(); e.setId(901); e.setAmount(inv.getArgument(5)); e.setImportRef(inv.getArgument(10)); return e; });

        txm = new CountingTxManager();
        svc = new DonationPostingService(donationRepo, settingsService, feeService, matcher, incomeService, expenseService,
                incomeRepo, expenseRepo, subSourceRepo, mainSourceRepo, purposeRepo, ttRepo, txm);
    }

    static Donation donation(long id, String amount) {
        Donation d = new Donation();
        d.setId(id); d.setClientId(CLIENT);
        d.setFirstName("Binny"); d.setLastName("Varghese"); d.setEmail("binny@example.org");
        d.setAmount(new BigDecimal(amount)); d.setIntendedAmount(new BigDecimal(amount)); d.setFeeCovered(BigDecimal.ZERO);
        d.setCurrency("USD"); d.setStatus("succeeded");
        d.setPaymentMethod("Visa •••• 6676");
        d.setNote("Rajan Uncle for 2026 SFCNA Summit");
        d.setStripePaymentIntentId(PI); d.setStripeChargeId("ch_3AbCdEfGhIjKlMnOpQrStUvW");
        d.setDonatedAt(LocalDateTime.of(2026, 9, 27, 10, 15));
        return d;
    }

    void stage(Donation d) {
        when(donationRepo.findByIdAndClientId(d.getId(), CLIENT)).thenReturn(Optional.of(d));
        when(donationRepo.lockByIdAndClientId(d.getId(), CLIENT)).thenReturn(Optional.of(d));
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("the brief's example: $100.00 donation, $3.20 Stripe fee")
    class BriefExample {

        @Test
        @DisplayName("Income $100.00 to the configured fund/method, Expense $3.20 to the configured type/fund/method, net $96.80")
        void postsGrossIncomeAndFeeExpense() {
            Donation d = donation(1, "100.00");
            stage(d);

            DonationPostingService.PostResult r = svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "bookkeeper@example.org");

            assertThat(r.status()).isEqualTo("POSTED");
            assertThat(r.incomeId()).isEqualTo(501);
            assertThat(r.expenseId()).isEqualTo(901);

            ArgumentCaptor<BigDecimal> incAmt = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<String> incNote = ArgumentCaptor.forClass(String.class);
            verify(incomeService).createIncome(isNull(), eq(5), eq(LocalDate.of(2026, 9, 27)), eq(9), isNull(),
                    incAmt.capture(), incNote.capture(), eq("Binny Varghese"), eq(false), eq(CLIENT), eq("donation-review"), eq(PI), eq(true));
            assertThat(incAmt.getValue()).isEqualByComparingTo("100.00");
            assertThat(incNote.getValue()).isEqualTo("Visa •••• 6676 — Online donation — Rajan Uncle for 2026 SFCNA Summit");

            ArgumentCaptor<BigDecimal> expAmt = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<String> expNote = ArgumentCaptor.forClass(String.class);
            verify(expenseService).createExpense(eq(3), eq(2), eq(LocalDate.of(2026, 9, 27)), eq(10), isNull(),
                    expAmt.capture(), expNote.capture(), eq(false), eq(CLIENT), eq("donation-review"), eq(PI + ":stripe-fee"), eq(true));
            assertThat(expAmt.getValue()).isEqualByComparingTo("3.20");
            assertThat(expNote.getValue()).isEqualTo("Stripe fees for donation of $100.00 on September 27, 2026 — Online donation — Rajan Uncle for 2026 SFCNA Summit");

            // net = income − expense = bank deposit
            assertThat(incAmt.getValue().subtract(expAmt.getValue())).isEqualByComparingTo("96.80");

            // links and status on the donation, posting fields and review fields both set
            assertThat(d.getPostedIncomeId()).isEqualTo(501);
            assertThat(d.getPostedExpenseId()).isEqualTo(901);
            assertThat(d.getPostedBy()).isEqualTo("bookkeeper@example.org");
            assertThat(d.getPostedAt()).isNotNull();
            assertThat(d.isReviewCompleted()).isTrue();
            assertThat(d.getContributorCategory()).isEqualTo("Visitor");   // no member matched in this fixture
            verify(donationRepo).save(d);
            assertThat(txm.committed).isEqualTo(1);
            assertThat(txm.rolledBack).isZero();
        }

        @Test
        @DisplayName("the method is the configured one — the card label never becomes a transaction type")
        void methodComesFromSettingsNotTheCard() {
            Donation d = donation(1, "100.00");
            stage(d);
            svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            verify(incomeService).createIncome(any(), anyInt(), any(), eq(9), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            verify(ttRepo, never()).save(any());
            verify(ttRepo, never()).findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(anyString(), anyString());
        }

        @Test
        @DisplayName("an unambiguous email match posts to that member (no guest name) and defaults the category to theirs")
        void matchedMemberIsLinked() {
            when(memberRepo.findActiveByEmailAndTenant("binny@example.org", CLIENT)).thenReturn(List.of(binny));
            Donation d = donation(1, "100.00");
            stage(d);
            svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            verify(incomeService).createIncome(eq(11), anyInt(), any(), anyInt(), any(), any(), any(), isNull(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(d.getMatchedMemberId()).isEqualTo(11);
            assertThat(d.getContributorCategory()).isEqualTo("Member");
            verify(memberRepo, never()).save(any());
        }

        @Test
        @DisplayName("a Member Portal gift posts to the signed-in member and the purpose they chose, not the settings' fund")
        void memberPortalGiftUsesMemberAndPurpose() {
            SubSource missions = new SubSource(); missions.setId(7); missions.setSourceName("Missions"); missions.setAppClientId(CLIENT);
            when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CLIENT)).thenReturn(Optional.of(missions));
            Donation d = donation(1, "10.00");
            d.setSource(Donation.SOURCE_MEMBER_PORTAL); d.setMemberId(11); d.setSubSourceId(7);
            d.setEmail(null);                                             // no email matching needed
            stage(d);
            when(feeService.resolve(any(), any())).thenReturn(new DonationFeeService.Resolved(
                    new BigDecimal("0.59"), new BigDecimal("9.41"), Donation.FEE_SOURCE_STRIPE, false));

            DonationPostingService.Proposal p = svc.preview(1L, CLIENT, DonationPostingService.Overrides.NONE);
            assertThat(p.matchedMemberId).isEqualTo(11);
            assertThat(p.matchReason).isEqualTo("MEMBER_PORTAL");
            assertThat(p.incomeSubSourceId).isEqualTo(7);
            assertThat(p.incomeFund).isEqualTo("Missions");
            assertThat(p.category).isEqualTo("Member");
            assertThat(p.incomeNote).startsWith("Visa \u2022\u2022\u2022\u2022 6676 \u2014 Member portal contribution");

            svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            verify(incomeService).createIncome(eq(11), eq(7), any(), eq(9), any(), any(), any(), isNull(), anyBoolean(), anyString(), anyString(), eq(PI), anyBoolean());
            verify(memberRepo, never()).save(any());
        }

        @Test
        @DisplayName("a Member Portal gift whose purpose was since deleted falls back to the settings' fund, with a warning")
        void memberPortalPurposeDeleted() {
            Donation d = donation(1, "10.00");
            d.setSource(Donation.SOURCE_MEMBER_PORTAL); d.setMemberId(11); d.setSubSourceId(404);
            stage(d);
            DonationPostingService.Proposal p = svc.preview(1L, CLIENT, DonationPostingService.Overrides.NONE);
            assertThat(p.incomeSubSourceId).isEqualTo(5);
            assertThat(p.warnings).anySatisfy(w -> assertThat(w).contains("purpose the member chose no longer exists"));
        }

        @Test
        @DisplayName("the reviewer can override date, notes, category and the person — amounts are never taken from the request")
        void overridesApplyButAmountsDoNot() {
            Donation d = donation(1, "100.00");
            stage(d);
            DonationPostingService.Overrides o = DonationPostingService.Overrides.fromBody(Map.of(
                    "incomeDate", "2026-10-01", "incomeNote", "Edited note", "category", "Guest",
                    "matchedMemberId", 11, "expenseNote", "Edited fee note",
                    "incomeAmount", "1.00", "fee", "0.00", "expenseAmount", "0.00"));   // ignored
            svc.post(1L, CLIENT, o, "u");
            ArgumentCaptor<BigDecimal> amt = ArgumentCaptor.forClass(BigDecimal.class);
            verify(incomeService).createIncome(eq(11), anyInt(), eq(LocalDate.of(2026, 10, 1)), anyInt(), any(),
                    amt.capture(), eq("Edited note"), isNull(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(amt.getValue()).isEqualByComparingTo("100.00");
            ArgumentCaptor<BigDecimal> fee = ArgumentCaptor.forClass(BigDecimal.class);
            verify(expenseService).createExpense(anyInt(), anyInt(), eq(LocalDate.of(2026, 10, 1)), anyInt(), any(),
                    fee.capture(), eq("Edited fee note"), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(fee.getValue()).isEqualByComparingTo("3.20");
            assertThat(d.getContributorCategory()).isEqualTo("Guest");
        }

        @Test
        @DisplayName("'today' date mode uses today, and an estimated fee is said so in the expense note")
        void todayModeAndEstimatedFee() {
            settings.setDateMode(DonationSettings.DATE_TODAY);
            when(feeService.resolve(any(), any())).thenReturn(new DonationFeeService.Resolved(
                    new BigDecimal("3.20"), new BigDecimal("96.80"), Donation.FEE_SOURCE_ESTIMATED, true));
            Donation d = donation(1, "100.00");
            stage(d);
            DonationPostingService.PostResult r = svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(expenseService).createExpense(anyInt(), anyInt(), eq(LocalDate.now(com.churchgeniuspro.util.AppClock.ZONE)), anyInt(), any(),
                    any(), note.capture(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(note.getValue()).containsIgnoringCase("estimated");
            assertThat(r.message()).containsIgnoringCase("estimated");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("never twice")
    class NeverTwice {

        @Test
        @DisplayName("a donation that already has posted_income_id is not posted again — nothing is written")
        void postedIncomeIdShortCircuits() {
            Donation d = donation(1, "100.00");
            d.setPostedIncomeId(777); d.setPostedExpenseId(778);
            stage(d);
            DonationPostingService.PostResult r = svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            assertThat(r.status()).isEqualTo("ALREADY_POSTED");
            assertThat(r.incomeId()).isEqualTo(777);
            assertThat(r.historical()).isFalse();
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            verify(expenseService, never()).createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            verify(donationRepo, never()).save(any());
        }

        @Test
        @DisplayName("a donation the OLD automatic hook posted (income with this PaymentIntent as import_ref) is reported as historical and left exactly as it is")
        void historicalIncomeIsReportedNotAdopted() {
            Income old = new Income(); old.setId(42); old.setAmount(new BigDecimal("100.00")); old.setImportRef(PI);
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(CLIENT, PI)).thenReturn(Optional.of(old));
            Donation d = donation(1, "100.00");
            stage(d);
            DonationPostingService.PostResult r = svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            assertThat(r.status()).isEqualTo("ALREADY_POSTED");
            assertThat(r.historical()).isTrue();
            assertThat(r.incomeId()).isEqualTo(42);
            assertThat(r.message()).contains("before the review workflow");
            // Decision 7: no backfill — the donation row is untouched.
            assertThat(d.getPostedIncomeId()).isNull();
            assertThat(d.isReviewCompleted()).isFalse();
            verify(donationRepo, never()).save(any());
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("the unique import_ref index wins a race: DuplicateImportException → rolled back, reported as already posted")
        void duplicateImportRollsBack() {
            Donation d = donation(1, "100.00");
            stage(d);
            when(incomeService.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean()))
                    .thenThrow(new DuplicateImportException("This transaction was already imported.", "income", 55, "2026-09-27", new BigDecimal("100.00"), null, true));
            DonationPostingService.PostResult r = svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            assertThat(r.status()).isEqualTo("ALREADY_POSTED");
            assertThat(txm.rolledBack).isEqualTo(1);
            assertThat(txm.committed).isZero();
            verify(expenseService, never()).createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(d.getPostedIncomeId()).isNull();
        }

        @Test
        @DisplayName("if the Expense fails after the Income was created, the whole post rolls back and the donation is not marked posted")
        void expenseFailureRollsBackIncome() {
            Donation d = donation(1, "100.00");
            stage(d);
            when(expenseService.createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean()))
                    .thenThrow(new IllegalStateException("database went away"));
            assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(txm.rolledBack).isEqualTo(1);
            assertThat(txm.committed).isZero();
            assertThat(d.getPostedIncomeId()).isNull();
            assertThat(d.isReviewCompleted()).isFalse();
            verify(donationRepo, never()).save(any());
        }

        @Test
        @DisplayName("the row is locked (SELECT … FOR UPDATE) for the post, not merely read")
        void usesTheLockingLookup() {
            Donation d = donation(1, "100.00");
            stage(d);
            svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            verify(donationRepo).lockByIdAndClientId(1L, CLIENT);
            verify(donationRepo, never()).findByIdAndClientId(1L, CLIENT);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("refusals — nothing is written")
    class Refusals {

        @Test
        @DisplayName("settings incomplete → the brief's message, no lock, no rows")
        void incompleteSettings() {
            settings.setExpensePurposeId(null);
            Donation d = donation(1, "100.00");
            stage(d);
            assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u"))
                    .isInstanceOf(DonationSettingsService.IncompleteSettingsException.class)
                    .hasMessage("Please configure the mandatory fields in Donation Settings before saving donations.");
            verify(donationRepo, never()).lockByIdAndClientId(any(), any());
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(txm.rolledBack).isEqualTo(1);
        }

        @Test
        @DisplayName("a fund soft-deleted AFTER settings were saved blocks posting too")
        void deletedFundBlocks() {
            when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, CLIENT)).thenReturn(Optional.empty());
            Donation d = donation(1, "100.00");
            stage(d);
            assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u"))
                    .isInstanceOf(DonationSettingsService.IncompleteSettingsException.class)
                    .satisfies(ex -> assertThat(((DonationSettingsService.IncompleteSettingsException) ex).getMissing()).contains("incomeSubSourceId"));
        }

        @Test
        @DisplayName("fee unknown and not estimable → refused before any row")
        void feeUnavailable() {
            when(feeService.resolve(any(), any())).thenThrow(new DonationFeeService.FeeUnavailableException("no fee"));
            Donation d = donation(1, "100.00");
            stage(d);
            assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u"))
                    .isInstanceOf(DonationFeeService.FeeUnavailableException.class);
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("a donor-covered fee (decision 6: unsupported this phase) is refused, not guessed at")
        void feeCoveredRefused() {
            Donation d = donation(1, "103.20");
            d.setIntendedAmount(new BigDecimal("100.00")); d.setFeeCovered(new BigDecimal("3.20"));
            stage(d);
            assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u"))
                    .isInstanceOf(DonationPostingService.UnsupportedDonationException.class)
                    .hasMessageContaining("donor-covered fee");
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(txm.rolledBack).isEqualTo(1);
        }

        @Test
        @DisplayName("a person from another church cannot be chosen")
        void foreignMemberRefused() {
            Donation d = donation(1, "100.00");
            stage(d);
            DonationPostingService.Overrides o = DonationPostingService.Overrides.fromBody(Map.of("matchedMemberId", 999));
            assertThatThrownBy(() -> svc.post(1L, CLIENT, o, "u"))
                    .isInstanceOf(DonationPostingService.InvalidReviewException.class);
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("an override fund/method/purpose from another church is refused")
        void foreignIdsRefused() {
            Donation d = donation(1, "100.00");
            stage(d);
            for (Map<String, Object> body : List.<Map<String, Object>>of(
                    Map.of("incomeSubSourceId", 55), Map.of("incomeTransactionTypeId", 55),
                    Map.of("expensePurposeId", 55), Map.of("expenseMainSourceId", 55), Map.of("expenseTransactionTypeId", 55))) {
                assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.fromBody(body), "u"))
                        .as(body.toString()).isInstanceOf(DonationPostingService.InvalidReviewException.class);
            }
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("another church's donation id → not found")
        void foreignDonation() {
            when(donationRepo.lockByIdAndClientId(1L, CLIENT)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u"))
                    .isInstanceOf(DonationPostingService.NotFoundException.class);
        }

        @Test
        @DisplayName("a payment that did not succeed is refused")
        void notSucceeded() {
            Donation d = donation(1, "100.00"); d.setStatus("processing");
            stage(d);
            assertThatThrownBy(() -> svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u"))
                    .isInstanceOf(DonationPostingService.UnsupportedDonationException.class);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("batch and preview")
    class BatchAndPreview {

        @Test
        @DisplayName("each donation is its own transaction: one failure neither blocks nor rolls back the others")
        void batchIsolation() {
            Donation a = donation(1, "100.00"), b = donation(2, "250.00"), c = donation(3, "25.00");
            b.setStatus("processing");                     // refused
            c.setPostedIncomeId(600);                      // already posted
            stage(a); stage(b); stage(c);
            List<DonationPostingService.PostResult> rs = svc.postBatch(List.of(1L, 2L, 3L), CLIENT, "u");
            assertThat(rs).extracting(DonationPostingService.PostResult::status)
                    .containsExactly("POSTED", "FAILED", "ALREADY_POSTED");
            assertThat(rs.get(1).message()).contains("not succeeded");
            assertThat(txm.committed).isEqualTo(2);        // a posted, c's short-circuit committed (no writes)
            assertThat(txm.rolledBack).isEqualTo(1);       // b
            assertThat(a.getPostedIncomeId()).isEqualTo(501);
            assertThat(b.getPostedIncomeId()).isNull();
        }

        @Test
        @DisplayName("incomplete settings stop the batch at the first id — the same failure would repeat")
        void batchStopsOnSettings() {
            settings.setIncomeSubSourceId(null);
            List<DonationPostingService.PostResult> rs = svc.postBatch(List.of(1L, 2L, 3L), CLIENT, "u");
            assertThat(rs).hasSize(1);
            assertThat(rs.get(0).status()).isEqualTo("FAILED");
            assertThat(rs.get(0).message()).contains("Donation Settings");
        }

        @Test
        @DisplayName("preview shows exactly what post would write, and writes nothing")
        void previewIsReadOnly() {
            Donation d = donation(1, "100.00");
            stage(d);
            DonationPostingService.Proposal p = svc.preview(1L, CLIENT, DonationPostingService.Overrides.NONE);
            assertThat(p.gross).isEqualByComparingTo("100.00");
            assertThat(p.fee).isEqualByComparingTo("3.20");
            assertThat(p.net).isEqualByComparingTo("96.80");
            assertThat(p.incomeAmount).isEqualByComparingTo("100.00");
            assertThat(p.expenseAmount).isEqualByComparingTo("3.20");
            assertThat(p.incomeFund).isEqualTo("Tithe");
            assertThat(p.incomeMethod).isEqualTo("Bank Transfer");
            assertThat(p.expenseType).isEqualTo("Administrative");
            assertThat(p.expenseFund).isEqualTo("General Fund");
            assertThat(p.expenseMethod).isEqualTo("Card");
            assertThat(p.category).isEqualTo("Visitor");
            assertThat(p.feeSource).isEqualTo("STRIPE");
            assertThat(p.alreadyPosted).isFalse();
            assertThat(p.describe()).containsKeys("gross", "fee", "net", "incomeNote", "expenseNote", "warnings");
            verify(incomeService, never()).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            verify(expenseService, never()).createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            verify(donationRepo, never()).save(any());
            verify(donationRepo, never()).lockByIdAndClientId(any(), any());
            assertThat(txm.begun).isZero();
        }

        @Test
        @DisplayName("preview of a historical auto-posted donation says so and points at the income row")
        void previewHistorical() {
            Income old = new Income(); old.setId(42);
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(CLIENT, PI)).thenReturn(Optional.of(old));
            Donation d = donation(1, "100.00");
            stage(d);
            DonationPostingService.Proposal p = svc.preview(1L, CLIENT, DonationPostingService.Overrides.NONE);
            assertThat(p.alreadyPosted).isTrue();
            assertThat(p.historical).isTrue();
            assertThat(p.existingIncomeId).isEqualTo(42);
        }

        @Test
        @DisplayName("a seeded demo/trial donation with no PaymentIntent gets its own import_ref (donation:<id>) — never a null or shared ref")
        void noPaymentIntentGetsPerDonationRef() {
            Donation d = donation(77, "40.00");
            d.setStripePaymentIntentId(null); d.setStripeChargeId(null);
            stage(d);
            when(feeService.resolve(any(), any())).thenReturn(new DonationFeeService.Resolved(
                    new BigDecimal("1.46"), new BigDecimal("38.54"), Donation.FEE_SOURCE_ESTIMATED, true));
            DonationPostingService.PostResult r = svc.post(77L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            assertThat(r.status()).isEqualTo("POSTED");
            verify(incomeService).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString(), eq("donation:77"), anyBoolean());
            verify(expenseService).createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString(), eq("donation:77:stripe-fee"), anyBoolean());
            assertThat(DonationPostingService.ledgerRef(d)).isEqualTo("donation:77");
        }

        @Test
        @DisplayName("a $0.00 Stripe fee posts the Income and skips the Expense, with a warning")
        void zeroFeeSkipsExpense() {
            when(feeService.resolve(any(), any())).thenReturn(new DonationFeeService.Resolved(
                    new BigDecimal("0.00"), new BigDecimal("100.00"), Donation.FEE_SOURCE_STRIPE, false));
            Donation d = donation(1, "100.00");
            stage(d);
            DonationPostingService.PostResult r = svc.post(1L, CLIENT, DonationPostingService.Overrides.NONE, "u");
            assertThat(r.status()).isEqualTo("POSTED");
            assertThat(r.expenseId()).isNull();
            verify(expenseService, never()).createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(d.getPostedExpenseId()).isNull();
        }
    }
}
