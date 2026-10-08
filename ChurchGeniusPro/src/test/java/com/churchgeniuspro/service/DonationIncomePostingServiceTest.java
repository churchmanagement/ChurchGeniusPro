package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.FamilyMemberRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Financial audit H9: a successfully-saved online donation (Stripe giving,
 * MidRegMeet event giving) is posted into {@code income} so it appears on
 * the Year-End Tax Report, a member's own giving statement, and every other
 * income-based report — none of which reads the {@code donation} table
 * today, even though the donor is told by email that the gift is
 * tax-deductible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DonationIncomePostingServiceTest {

    private static final String CLIENT = "CHR-1";
    private static final String CARD_METHOD = "Visa •••• 4242";

    @Mock IncomeService             incomeService;
    @Mock SourceService             sourceService;
    @Mock FamilyMemberRepository    memberRepo;
    @Mock TransactionTypeRepository transactionTypeRepo;

    private DonationIncomePostingService service;

    @BeforeEach
    void setUp() {
        service = new DonationIncomePostingService(incomeService, sourceService, memberRepo, transactionTypeRepo);

        MainSource main = new MainSource();
        main.setId(70);
        main.setSourceName("Online Giving");
        SubSource sub = new SubSource();
        sub.setId(71);
        sub.setSourceName("Online Donations");
        sub.setMainSource(main);
        when(sourceService.findOrCreateOnlineDonationsSubSource(CLIENT)).thenReturn(sub);
    }

    private static Donation donation(String email, String first, String last) {
        Donation d = new Donation();
        d.setId(900L);
        d.setClientId(CLIENT);
        d.setFirstName(first);
        d.setLastName(last);
        d.setEmail(email);
        d.setAmount(new BigDecimal("50.00"));
        d.setCurrency("USD");
        d.setPaymentMethod(CARD_METHOD);
        d.setStripePaymentIntentId("pi_abc123");
        d.setStripeChargeId("ch_abc123");
        d.setStatus("succeeded");
        d.setDonatedAt(LocalDateTime.of(2026, 3, 15, 10, 30));
        return d;
    }

    private void stubCardTransactionType() {
        TransactionType t = new TransactionType();
        t.setId(55);
        t.setTypeName(CARD_METHOD);
        when(transactionTypeRepo.findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(CARD_METHOD, CLIENT))
                .thenReturn(Optional.of(t));
    }

    @Nested
    @DisplayName("member matching")
    class MemberMatching {

        @Test
        @DisplayName("an email matching exactly one active member links that member — no guest name")
        void exactlyOneMatchLinksMember() {
            stubCardTransactionType();
            FamilyMember member = new FamilyMember();
            member.setId(42);
            when(memberRepo.findActiveByEmailAndTenant("donor@example.org", CLIENT))
                    .thenReturn(List.of(member));

            boolean posted = service.postToIncome(donation("donor@example.org", "Jamie", "Rivera"));

            assertThat(posted).isTrue();
            verify(incomeService).createIncome(
                    eq(42), eq(71), eq(LocalDate.of(2026, 3, 15)), eq(55),
                    isNull(), eq(new BigDecimal("50.00")), eq("Online donation"),
                    isNull(), eq(false), eq(CLIENT), eq("online-giving"), eq("pi_abc123"), eq(true));
        }

        @Test
        @DisplayName("no email at all falls back to a guest entry under the donor's name")
        void noEmailFallsBackToGuest() {
            stubCardTransactionType();

            boolean posted = service.postToIncome(donation(null, "Jamie", "Rivera"));

            assertThat(posted).isTrue();
            verifyNoInteractions(memberRepo);
            verify(incomeService).createIncome(
                    isNull(), eq(71), any(), eq(55), isNull(), any(), anyString(),
                    eq("Jamie Rivera"), eq(false), eq(CLIENT), eq("online-giving"), eq("pi_abc123"), eq(true));
        }

        @Test
        @DisplayName("an email matching zero members falls back to a guest entry, not left unmatched")
        void zeroMatchesFallsBackToGuest() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant("nobody@example.org", CLIENT)).thenReturn(List.of());

            service.postToIncome(donation("nobody@example.org", "Jamie", "Rivera"));

            verify(incomeService).createIncome(
                    isNull(), eq(71), any(), eq(55), isNull(), any(), anyString(),
                    eq("Jamie Rivera"), eq(false), eq(CLIENT), eq("online-giving"), eq("pi_abc123"), eq(true));
        }

        @Test
        @DisplayName("an email matching more than one member (a shared family inbox) is ambiguous, not guessed")
        void multipleMatchesFallsBackToGuestRatherThanGuessing() {
            stubCardTransactionType();
            FamilyMember m1 = new FamilyMember();
            m1.setId(1);
            FamilyMember m2 = new FamilyMember();
            m2.setId(2);
            when(memberRepo.findActiveByEmailAndTenant("family@example.org", CLIENT)).thenReturn(List.of(m1, m2));

            service.postToIncome(donation("family@example.org", "Jamie", "Rivera"));

            verify(incomeService).createIncome(
                    isNull(), eq(71), any(), eq(55), isNull(), any(), anyString(),
                    eq("Jamie Rivera"), eq(false), eq(CLIENT), eq("online-giving"), eq("pi_abc123"), eq(true));
        }

        @Test
        @DisplayName("a guest donor with no usable name at all still gets a non-blank label")
        void blankNameDefaultsToOnlineDonor() {
            stubCardTransactionType();

            service.postToIncome(donation(null, null, null));

            verify(incomeService).createIncome(
                    isNull(), eq(71), any(), eq(55), isNull(), any(), anyString(),
                    eq("Online Donor"), eq(false), eq(CLIENT), eq("online-giving"), eq("pi_abc123"), eq(true));
        }
    }

    @Nested
    @DisplayName("category and payment-method type")
    class CategoryAndType {

        @Test
        @DisplayName("files under this church's Online Giving / Online Donations category")
        void usesOnlineDonationsSubSource() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());

            service.postToIncome(donation("d@example.org", "D", "Onor"));

            ArgumentCaptor<Integer> subSourceId = ArgumentCaptor.forClass(Integer.class);
            verify(incomeService).createIncome(any(), subSourceId.capture(), any(), any(), any(), any(),
                    anyString(), any(), eq(false), eq(CLIENT), anyString(), anyString(), eq(true));
            assertThat(subSourceId.getValue()).isEqualTo(71);
        }

        @Test
        @DisplayName("reuses an existing transaction type matching the donation's payment method")
        void reusesExistingTransactionType() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());

            service.postToIncome(donation("d@example.org", "D", "Onor"));

            verify(transactionTypeRepo, never()).save(any());
        }

        @Test
        @DisplayName("creates a transaction type on first use of a new payment-method label")
        void createsTransactionTypeOnFirstUse() {
            when(transactionTypeRepo.findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(
                    "Bank Transfer", CLIENT)).thenReturn(Optional.empty());
            when(transactionTypeRepo.save(any(TransactionType.class))).thenAnswer(inv -> {
                TransactionType t = inv.getArgument(0);
                t.setId(99);
                return t;
            });
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
            Donation d = donation("d@example.org", "D", "Onor");
            d.setPaymentMethod("Bank Transfer");

            service.postToIncome(d);

            verify(incomeService).createIncome(any(), eq(71), any(), eq(99), any(), any(), anyString(),
                    any(), eq(false), eq(CLIENT), anyString(), anyString(), eq(true));
        }

        @Test
        @DisplayName("a blank payment method falls back to a generic \"Online\" type")
        void blankPaymentMethodFallsBackToOnline() {
            TransactionType online = new TransactionType();
            online.setId(77);
            online.setTypeName("Online");
            when(transactionTypeRepo.findFirstByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse("Online", CLIENT))
                    .thenReturn(Optional.of(online));
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
            Donation d = donation("d@example.org", "D", "Onor");
            d.setPaymentMethod(null);

            service.postToIncome(d);

            verify(incomeService).createIncome(any(), eq(71), any(), eq(77), any(), any(), anyString(),
                    any(), eq(false), eq(CLIENT), anyString(), anyString(), eq(true));
        }
    }

    @Nested
    @DisplayName("fee-covered donations (M3)")
    class FeeCovered {

        @Test
        @DisplayName("posts the donor's intended gift, not the fee-covered charge")
        void postsIntendedAmountNotCharge() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
            Donation d = donation("d@example.org", "D", "Onor");
            d.setAmount(new BigDecimal("26.13"));         // what was actually charged
            d.setIntendedAmount(new BigDecimal("25.00"));  // what the donor intended to give
            d.setFeeCovered(new BigDecimal("1.13"));

            service.postToIncome(d);

            verify(incomeService).createIncome(any(), any(), any(), any(), any(),
                    eq(new BigDecimal("25.00")), anyString(), any(), eq(false), eq(CLIENT),
                    anyString(), anyString(), eq(true));
        }

        @Test
        @DisplayName("the ledger note discloses the covered fee and the full charge")
        void noteDisclosesFee() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
            Donation d = donation("d@example.org", "D", "Onor");
            d.setAmount(new BigDecimal("26.13"));
            d.setIntendedAmount(new BigDecimal("25.00"));
            d.setFeeCovered(new BigDecimal("1.13"));

            service.postToIncome(d);

            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(incomeService).createIncome(any(), any(), any(), any(), any(), any(),
                    note.capture(), any(), eq(false), eq(CLIENT), anyString(), anyString(), eq(true));
            assertThat(note.getValue()).contains("$1.13").contains("$26.13").contains("processing fee");
        }

        @Test
        @DisplayName("a donation with no fee covered gets a plain note, with no mention of any fee")
        void noFeeNoteWhenNoneCovered() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
            Donation d = donation("d@example.org", "D", "Onor"); // feeCovered defaults to ZERO

            service.postToIncome(d);

            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(incomeService).createIncome(any(), any(), any(), any(), any(), any(),
                    note.capture(), any(), eq(false), eq(CLIENT), anyString(), anyString(), eq(true));
            assertThat(note.getValue()).isEqualTo("Online donation");
        }

        @Test
        @DisplayName("a donation saved before M3 (no intendedAmount recorded) posts its full charge, unaffected")
        void historicalDonationWithoutIntendedAmountUnaffected() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
            Donation d = donation("d@example.org", "D", "Onor");
            d.setIntendedAmount(null); // as any donation saved before M3 would be

            service.postToIncome(d);

            verify(incomeService).createIncome(any(), any(), any(), any(), any(),
                    eq(new BigDecimal("50.00")), anyString(), any(), eq(false), eq(CLIENT),
                    anyString(), anyString(), eq(true));
        }
    }

    @Nested
    @DisplayName("idempotency and failure handling")
    class Resilience {

        @Test
        @DisplayName("importRef carries the Stripe PaymentIntent id, so a re-post can't double the gift")
        void importRefIsThePaymentIntentId() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());

            service.postToIncome(donation("d@example.org", "D", "Onor"));

            ArgumentCaptor<String> importRef = ArgumentCaptor.forClass(String.class);
            verify(incomeService).createIncome(any(), any(), any(), any(), any(), any(), anyString(), any(),
                    eq(false), eq(CLIENT), anyString(), importRef.capture(), eq(true));
            assertThat(importRef.getValue()).isEqualTo("pi_abc123");
        }

        @Test
        @DisplayName("a re-post of an already-posted donation is swallowed, not treated as a failure")
        void duplicateRepostIsSwallowed() {
            stubCardTransactionType();
            when(memberRepo.findActiveByEmailAndTenant(anyString(), eq(CLIENT))).thenReturn(List.of());
            when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), anyString(), any(),
                    anyBoolean(), anyString(), anyString(), anyString(), anyBoolean()))
                    .thenThrow(new DuplicateImportException("Already imported.", "income", 1, "2026-03-15",
                            new BigDecimal("50.00"), null, true));

            boolean posted = service.postToIncome(donation("d@example.org", "D", "Onor"));

            assertThat(posted).isFalse();
        }

        @Test
        @DisplayName("any other failure is caught and logged, never thrown to the caller")
        void unexpectedFailureNeverPropagates() {
            when(sourceService.findOrCreateOnlineDonationsSubSource(CLIENT))
                    .thenThrow(new RuntimeException("db is down"));

            boolean posted = service.postToIncome(donation("d@example.org", "D", "Onor"));

            assertThat(posted).isFalse();
        }

        @Test
        @DisplayName("a null donation, or one missing clientId/amount, is a no-op — no repository calls at all")
        void guardsAgainstIncompleteInput() {
            assertThat(service.postToIncome(null)).isFalse();

            Donation noClient = donation("d@example.org", "D", "Onor");
            noClient.setClientId(null);
            assertThat(service.postToIncome(noClient)).isFalse();

            Donation noAmount = donation("d@example.org", "D", "Onor");
            noAmount.setAmount(null);
            assertThat(service.postToIncome(noAmount)).isFalse();

            verifyNoInteractions(sourceService, transactionTypeRepo, memberRepo, incomeService);
        }
    }
}
