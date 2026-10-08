package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Financial audit M4 follow-through: {@link PledgeController#applyIncomeToPledge}
 * and {@link PledgeController#adjustPledgeByDelta} now need the income's own
 * date to pick which single campaign a gift belongs to, so every call site in
 * {@link IncomeService} must thread it through correctly — in particular,
 * {@link IncomeService#updateIncome} must snapshot the <em>original</em> date
 * before overwriting the row, or an edit that also changes the date would
 * reverse the wrong campaign's credit instead of the one that was actually
 * credited when the row was first saved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IncomeService — pledge auto-allocate is given the income's actual date")
class IncomeServicePledgeDateTest {

    private static final String CLIENT = "CHURCH-A";

    @Mock IncomeRepository          incomeRepo;
    @Mock FamilyMemberRepository    memberRepo;
    @Mock SubSourceRepository       subSourceRepo;
    @Mock TransactionTypeRepository transactionTypeRepo;
    @Mock PledgeController          pledgeController;

    private IncomeService service;

    @BeforeEach
    void setUp() {
        service = new IncomeService(incomeRepo, memberRepo, subSourceRepo, transactionTypeRepo, pledgeController);
    }

    private static FamilyMember member(int id) {
        FamilyMember m = new FamilyMember();
        m.setId(id);
        m.setAppClientId(CLIENT);
        return m;
    }

    private static SubSource fund(int id) {
        SubSource s = new SubSource();
        s.setId(id);
        s.setAppClientId(CLIENT);
        return s;
    }

    private static TransactionType txType(int id) {
        TransactionType t = new TransactionType();
        t.setId(id);
        t.setAppClientId(CLIENT);
        return t;
    }

    @Test
    @DisplayName("createIncome passes the new row's own incomeDate to applyIncomeToPledge")
    void createIncomePassesIncomeDate() {
        when(memberRepo.findByIdAndTenant(10, CLIENT)).thenReturn(Optional.of(member(10)));
        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CLIENT)).thenReturn(Optional.of(fund(7)));
        when(transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(3, CLIENT)).thenReturn(Optional.of(txType(3)));
        when(incomeRepo.save(any(Income.class))).thenAnswer(i -> i.getArgument(0));

        service.createIncome(10, 7, LocalDate.of(2026, 3, 15), 3, null,
                new BigDecimal("100.00"), null, null, false, CLIENT, "staff@example.org");

        verify(pledgeController).applyIncomeToPledge(
                eq(CLIENT), eq(7), eq(10), eq(new BigDecimal("100.00")), eq(LocalDate.of(2026, 3, 15)));
    }

    @Test
    @DisplayName("updateIncome reverses the OLD date's allocation and applies the NEW date's allocation")
    void updateIncomeThreadsOriginalAndNewDateSeparately() {
        Income existing = new Income();
        existing.setId(99);
        existing.setMember(member(10));
        existing.setSubSource(fund(7));
        existing.setIncomeDate(LocalDate.of(2024, 12, 20));
        existing.setAmount(new BigDecimal("50.00"));
        existing.setAppClientId(CLIENT);
        when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(99, CLIENT)).thenReturn(Optional.of(existing));

        when(memberRepo.findByIdAndTenant(10, CLIENT)).thenReturn(Optional.of(member(10)));
        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CLIENT)).thenReturn(Optional.of(fund(7)));
        when(transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(3, CLIENT)).thenReturn(Optional.of(txType(3)));
        when(incomeRepo.save(any(Income.class))).thenAnswer(i -> i.getArgument(0));

        // The correction: the gift was actually made in the new year, not
        // December — same member, same fund, only the date (and amount) change.
        service.updateIncome(99, 10, 7, LocalDate.of(2025, 1, 3), 3, null,
                new BigDecimal("75.00"), null, null, false, CLIENT, "staff@example.org");

        verify(pledgeController).adjustPledgeByDelta(
                eq(CLIENT), eq(7), eq(10), eq(new BigDecimal("-50.00")), eq(LocalDate.of(2024, 12, 20)));
        verify(pledgeController).applyIncomeToPledge(
                eq(CLIENT), eq(7), eq(10), eq(new BigDecimal("75.00")), eq(LocalDate.of(2025, 1, 3)));
    }

    @Test
    @DisplayName("deleteIncome reverses using the deleted row's own incomeDate")
    void deleteIncomeUsesTheRowsOwnDate() {
        Income existing = new Income();
        existing.setId(55);
        existing.setMember(member(10));
        existing.setSubSource(fund(7));
        existing.setIncomeDate(LocalDate.of(2026, 5, 1));
        existing.setAmount(new BigDecimal("40.00"));
        existing.setAppClientId(CLIENT);
        when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(55, CLIENT)).thenReturn(Optional.of(existing));
        when(incomeRepo.save(any(Income.class))).thenAnswer(i -> i.getArgument(0));

        service.deleteIncome(55, CLIENT);

        verify(pledgeController).adjustPledgeByDelta(
                eq(CLIENT), eq(7), eq(10), eq(new BigDecimal("-40.00")), eq(LocalDate.of(2026, 5, 1)));
    }
}
