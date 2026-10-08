package com.churchgeniuspro.accounting;

import com.churchgeniuspro.controller.ExpenseController;
import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.DuplicateImportException;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Duplicate prevention for imported income/expense rows (Bank Import, Plaid).
 *
 * <p>Financial audit H8. Before this, the only duplicate check was a separate,
 * purely advisory pre-save ping from the browser ({@code /api/bank-import/check-duplicate});
 * declining its warning — or the call itself failing — did not stop the save, and
 * Bank Import and Plaid could not see each other's postings at all. This suite
 * exercises the real enforcement now living in {@code IncomeService.createIncome}
 * and {@code ExpenseService.createExpense}, and the controllers' 409 mapping of it.
 *
 * <p>Uses the real services over mocked repositories, matching
 * {@link AccountingTenantIsolationTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Income / Expense — import duplicate prevention (H8)")
class ImportDuplicatePreventionTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";
    private static final LocalDate DATE = LocalDate.of(2026, 1, 15);
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final String REF = "stmt:2026-01-15|100.00|ACHDEPOSIT";

    @Mock IncomeRepository           incomeRepo;
    @Mock ExpenseRepository          expenseRepo;
    @Mock FamilyMemberRepository     memberRepo;
    @Mock SubSourceRepository        subSourceRepo;
    @Mock MainSourceRepository       mainSourceRepo;
    @Mock PurposeRepository          purposeRepo;
    @Mock TransactionTypeRepository  transactionTypeRepo;
    @Mock PledgeController           pledgeController;

    IncomeService     incomeService;
    ExpenseService    expenseService;
    IncomeController  incomeController;
    ExpenseController expenseController;

    private void wireUp() {
        incomeService  = new IncomeService(incomeRepo, memberRepo, subSourceRepo, transactionTypeRepo, pledgeController);
        expenseService = new ExpenseService(expenseRepo, purposeRepo, mainSourceRepo, transactionTypeRepo);
        incomeController  = new IncomeController(incomeService);
        expenseController = new ExpenseController(expenseService);
        when(incomeRepo.save(any(Income.class))).thenAnswer(inv -> {
            Income i = inv.getArgument(0);
            if (i.getId() == null) i.setId(900);
            return i;
        });
        when(expenseRepo.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            if (e.getId() == null) e.setId(901);
            return e;
        });
    }

    private void ourLookupsExist() {
        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(subSource(1, OURS)));
        when(transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(method(1, OURS)));
        when(purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(purpose(1, OURS)));
        when(mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(mainSource(1, OURS)));
    }

    private static MainSource mainSource(int id, String tenant) {
        MainSource ms = new MainSource();
        ms.setId(id); ms.setSourceName("Fund " + id); ms.setAppClientId(tenant);
        return ms;
    }

    private static SubSource subSource(int id, String tenant) {
        SubSource ss = new SubSource();
        ss.setId(id); ss.setSourceName("Sub " + id); ss.setAppClientId(tenant);
        ss.setMainSource(mainSource(1000 + id, tenant));
        return ss;
    }

    private static Purpose purpose(int id, String tenant) {
        Purpose p = new Purpose();
        p.setId(id); p.setPurposeName("Purpose " + id); p.setAppClientId(tenant);
        return p;
    }

    private static TransactionType method(int id, String tenant) {
        TransactionType tt = new TransactionType();
        tt.setId(id); tt.setTypeName("Method " + id); tt.setAppClientId(tenant);
        return tt;
    }

    private static Income existingIncome(int id, String tenant) {
        Income i = new Income();
        i.setId(id); i.setAppClientId(tenant); i.setAmount(AMOUNT); i.setIncomeDate(DATE); i.setRefNo("1042");
        return i;
    }

    private static Expense existingExpense(int id, String tenant) {
        Expense e = new Expense();
        e.setId(id); e.setAppClientId(tenant); e.setAmount(AMOUNT); e.setExpenseDate(DATE); e.setRefNo("1042");
        return e;
    }

    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IncomeService")
    class IncomeH8 {

        @Test
        @DisplayName("a manual entry (no importRef) is never duplicate-checked — two members can give the same amount the same day")
        void manualEntryNeverChecked() {
            wireUp(); ourLookupsExist();

            incomeService.createIncome(null, 1, DATE, 1, null, AMOUNT, null, "Guest A", false, OURS, "u");
            incomeService.createIncome(null, 1, DATE, 1, null, AMOUNT, null, "Guest B", false, OURS, "u");

            verify(incomeRepo, never()).findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(any(), any());
            verify(incomeRepo, never()).findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(any(), any(), any());
            verify(incomeRepo, org.mockito.Mockito.times(2)).save(any(Income.class));
        }

        @Test
        @DisplayName("a hard duplicate (same importRef already active) is refused, and never reaches save()")
        void hardDuplicateBlocked() {
            wireUp();
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF))
                    .thenReturn(Optional.of(existingIncome(5, OURS)));

            assertThatThrownBy(() -> incomeService.createIncome(
                    null, 1, DATE, 1, null, AMOUNT, null, null, false, OURS, "u", REF, false))
                    .isInstanceOf(DuplicateImportException.class)
                    .satisfies(ex -> {
                        DuplicateImportException d = (DuplicateImportException) ex;
                        assertThat(d.hard).isTrue();
                        assertThat(d.existingId).isEqualTo(5);
                        assertThat(d.type).isEqualTo("income");
                    });
            verify(incomeRepo, never()).save(any(Income.class));
            // The lookups for member/fund/method never even ran — the duplicate check is the first thing done.
            verify(subSourceRepo, never()).findByIdAndAppClientIdAndDeleteFlagFalse(any(), any());
        }

        @Test
        @DisplayName("hard duplicate check cannot be bypassed by force=true")
        void hardDuplicateNotOverridableByForce() {
            wireUp();
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF))
                    .thenReturn(Optional.of(existingIncome(5, OURS)));

            assertThatThrownBy(() -> incomeService.createIncome(
                    null, 1, DATE, 1, null, AMOUNT, null, null, false, OURS, "u", REF, true))
                    .isInstanceOfSatisfying(DuplicateImportException.class, d -> assertThat(d.hard).isTrue());
            verify(incomeRepo, never()).save(any(Income.class));
        }

        @Test
        @DisplayName("a soft duplicate (same date+amount, any source) is refused unless forced")
        void softDuplicateBlockedUnlessForced() {
            wireUp();
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF)).thenReturn(Optional.empty());
            when(incomeRepo.findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(OURS, DATE, AMOUNT))
                    .thenReturn(List.of(existingIncome(6, OURS)));

            assertThatThrownBy(() -> incomeService.createIncome(
                    null, 1, DATE, 1, null, AMOUNT, null, null, false, OURS, "u", REF, false))
                    .isInstanceOfSatisfying(DuplicateImportException.class, d -> {
                        assertThat(d.hard).isFalse();
                        assertThat(d.existingId).isEqualTo(6);
                    });
            verify(incomeRepo, never()).save(any(Income.class));
        }

        @Test
        @DisplayName("force=true skips the soft check and saves, carrying the importRef")
        void forceOverridesSoftCheck() {
            wireUp(); ourLookupsExist();
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF)).thenReturn(Optional.empty());
            when(incomeRepo.findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(OURS, DATE, AMOUNT))
                    .thenReturn(List.of(existingIncome(6, OURS)));

            Income saved = incomeService.createIncome(
                    null, 1, DATE, 1, null, AMOUNT, null, "Guest", false, OURS, "u", REF, true);

            assertThat(saved.getImportRef()).isEqualTo(REF);
            verify(incomeRepo).save(any(Income.class));
        }

        @Test
        @DisplayName("an import with no existing match at all saves cleanly and carries the importRef")
        void freshImportSaves() {
            wireUp(); ourLookupsExist();
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF)).thenReturn(Optional.empty());
            when(incomeRepo.findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(OURS, DATE, AMOUNT))
                    .thenReturn(List.of());

            Income saved = incomeService.createIncome(
                    null, 1, DATE, 1, null, AMOUNT, null, "Guest", false, OURS, "u", REF, false);

            assertThat(saved.getImportRef()).isEqualTo(REF);
        }

        @Test
        @DisplayName("the duplicate check is scoped to this church — another church's matching importRef doesn't block it")
        void tenantScoped() {
            wireUp(); ourLookupsExist();
            // THEIRS already used this importRef; OURS never stubbed for it → Mockito default empty.
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(THEIRS, REF))
                    .thenReturn(Optional.of(existingIncome(5, THEIRS)));
            when(incomeRepo.findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(OURS, DATE, AMOUNT))
                    .thenReturn(List.of());

            Income saved = incomeService.createIncome(
                    null, 1, DATE, 1, null, AMOUNT, null, "Guest", false, OURS, "u", REF, false);

            assertThat(saved).isNotNull();
            verify(incomeRepo).findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(eq(OURS), eq(REF));
        }

        @Test
        @DisplayName("a race (two concurrent imports of the same line) is caught by the unique index and surfaced as a hard duplicate")
        void raceConditionMapsToDuplicateException() {
            wireUp(); ourLookupsExist();
            // Passes the pre-check (empty), but the insert itself collides — another
            // request's import committed first. The post-race lookup then finds it.
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF))
                    .thenReturn(Optional.empty(), Optional.of(existingIncome(7, OURS)));
            when(incomeRepo.findByAppClientIdAndIncomeDateAndAmountAndDeleteFlagFalse(OURS, DATE, AMOUNT))
                    .thenReturn(List.of());
            when(incomeRepo.save(any(Income.class))).thenThrow(new DataIntegrityViolationException("unique violation"));

            assertThatThrownBy(() -> incomeService.createIncome(
                    null, 1, DATE, 1, null, AMOUNT, null, "Guest", false, OURS, "u", REF, false))
                    .isInstanceOfSatisfying(DuplicateImportException.class, d -> {
                        assertThat(d.hard).isTrue();
                        assertThat(d.existingId).isEqualTo(7);
                    });
        }
    }

    @Nested
    @DisplayName("ExpenseService")
    class ExpenseH8 {

        @Test
        @DisplayName("a manual entry (no importRef) is never duplicate-checked")
        void manualEntryNeverChecked() {
            wireUp(); ourLookupsExist();

            expenseService.createExpense(1, 1, DATE, 1, null, AMOUNT, null, false, OURS, "u");

            verify(expenseRepo, never()).findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(any(), any());
            verify(expenseRepo, never()).findByAppClientIdAndExpenseDateAndAmountAndDeleteFlagFalse(any(), any(), any());
        }

        @Test
        @DisplayName("a hard duplicate (same importRef already active) is refused, and never reaches save()")
        void hardDuplicateBlocked() {
            wireUp();
            when(expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF))
                    .thenReturn(Optional.of(existingExpense(5, OURS)));

            assertThatThrownBy(() -> expenseService.createExpense(
                    1, 1, DATE, 1, null, AMOUNT, null, false, OURS, "u", REF, false))
                    .isInstanceOfSatisfying(DuplicateImportException.class, d -> {
                        assertThat(d.hard).isTrue();
                        assertThat(d.type).isEqualTo("expense");
                    });
            verify(expenseRepo, never()).save(any(Expense.class));
        }

        @Test
        @DisplayName("a soft duplicate is refused unless forced, and force=true then saves with the importRef")
        void softDuplicateThenForced() {
            wireUp(); ourLookupsExist();
            when(expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF)).thenReturn(Optional.empty());
            when(expenseRepo.findByAppClientIdAndExpenseDateAndAmountAndDeleteFlagFalse(OURS, DATE, AMOUNT))
                    .thenReturn(List.of(existingExpense(6, OURS)));

            assertThatThrownBy(() -> expenseService.createExpense(
                    1, 1, DATE, 1, null, AMOUNT, null, false, OURS, "u", REF, false))
                    .isInstanceOfSatisfying(DuplicateImportException.class, d -> assertThat(d.hard).isFalse());
            verify(expenseRepo, never()).save(any(Expense.class));

            Expense saved = expenseService.createExpense(
                    1, 1, DATE, 1, null, AMOUNT, null, false, OURS, "u", REF, true);
            assertThat(saved.getImportRef()).isEqualTo(REF);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Controllers map DuplicateImportException to 409 with a duplicate payload")
    class ControllerMapping {

        @Test
        @DisplayName("POST /api/income surfaces the duplicate as 409, not 400 or 500")
        void incomeCreate409() {
            wireUp(); ourLookupsExist();
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF))
                    .thenReturn(Optional.of(existingIncome(5, OURS)));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subSourceId", 1); body.put("incomeDate", "2026-01-15");
            body.put("transactionTypeId", 1); body.put("amount", "100.00"); body.put("importRef", REF);

            ResponseEntity<Map<String, Object>> res = incomeController.createIncome(body, accountant());

            assertThat(res.getStatusCode().value()).isEqualTo(409);
            @SuppressWarnings("unchecked")
            Map<String, Object> dup = (Map<String, Object>) res.getBody().get("duplicate");
            assertThat(dup.get("type")).isEqualTo("income");
            assertThat(dup.get("id")).isEqualTo(5);
            assertThat(dup.get("hard")).isEqualTo(true);
        }

        @Test
        @DisplayName("POST /api/expense surfaces the duplicate as 409, not 400 or 500")
        void expenseCreate409() {
            wireUp(); ourLookupsExist();
            when(expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(OURS, REF))
                    .thenReturn(Optional.of(existingExpense(5, OURS)));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("purposeId", 1); body.put("mainSourceId", 1); body.put("expenseDate", "2026-01-15");
            body.put("transactionTypeId", 1); body.put("amount", "100.00"); body.put("importRef", REF);

            ResponseEntity<Map<String, Object>> res = expenseController.create(body, accountant());

            assertThat(res.getStatusCode().value()).isEqualTo(409);
            @SuppressWarnings("unchecked")
            Map<String, Object> dup = (Map<String, Object>) res.getBody().get("duplicate");
            assertThat(dup.get("type")).isEqualTo("expense");
            assertThat(dup.get("hard")).isEqualTo(true);
        }

        @Test
        @DisplayName("a normal create (no importRef in the body) behaves exactly as before — no 409 machinery involved")
        void ordinaryCreateUnaffected() {
            wireUp(); ourLookupsExist();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subSourceId", 1); body.put("incomeDate", "2026-01-15");
            body.put("transactionTypeId", 1); body.put("amount", "100.00");

            ResponseEntity<Map<String, Object>> res = incomeController.createIncome(body, accountant());

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(incomeRepo, never()).findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(any(), any());
        }
    }

    private static MockHttpServletRequest accountant() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", OURS);
        s.setAttribute("appClientId", OURS);
        s.setAttribute("username", "staff@" + OURS);
        s.setAttribute("role", "Accountant");
        req.setSession(s);
        return req;
    }
}
