package com.churchgeniuspro.accounting;

import com.churchgeniuspro.controller.AccountantDashboardController;
import com.churchgeniuspro.controller.AccountingReportController;
import com.churchgeniuspro.controller.CheckScanController;
import com.churchgeniuspro.controller.ExpenseCheckController;
import com.churchgeniuspro.controller.ExpenseController;
import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.controller.PurposeController;
import com.churchgeniuspro.controller.SourceController;
import com.churchgeniuspro.controller.TransactionTypeController;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.ExpenseCheckImage;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.IncomeCheckImage;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ExpenseCheckImageRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeCheckImageRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.CheckExtractor;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.service.PurposeService;
import com.churchgeniuspro.service.SourceService;
import com.churchgeniuspro.service.TransactionTypeService;
import com.churchgeniuspro.service.VisionCheckService;
import com.churchgeniuspro.service.VisionUploadService;
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
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Accounting APIs are tenant-bound and accountant/admin-only.
 *
 * <p>Before: every {@code /api/income/{id}}, {@code /api/expense/{id}},
 * {@code /api/sources/{id}}, {@code /api/sub-sources/{id}}, {@code /api/purposes/{id}}
 * and {@code /api/transaction-types/{id}} mutator loaded the row by bare id, the
 * foreign keys in the request body (member, fund, purpose, method) were resolved by
 * bare id, the check-image uploads attached files to any income/expense id, the tax
 * report and accountant dashboard trusted a client-supplied {@code clientId}, and
 * none of the API handlers carried the role guard the pages use.
 *
 * <p>Uses the real services over mocked repositories so the tenant filter is
 * exercised where it lives. Only the scoped finders are stubbed; a call to the
 * unscoped {@code findById} would return Mockito's default (empty) and is also
 * asserted never to happen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Accounting — tenant-bound, accountant/admin-only")
class AccountingTenantIsolationTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock IncomeRepository           incomeRepo;
    @Mock ExpenseRepository          expenseRepo;
    @Mock FamilyMemberRepository     memberRepo;
    @Mock SubSourceRepository        subSourceRepo;
    @Mock MainSourceRepository       mainSourceRepo;
    @Mock com.churchgeniuspro.service.LedgerSummaryService ledgerSummary;
    @Mock PurposeRepository          purposeRepo;
    @Mock TransactionTypeRepository  transactionTypeRepo;
    @Mock ChurchRegistrationRepository churchRepo;
    @Mock IncomeCheckImageRepository incomeImageRepo;
    @Mock ExpenseCheckImageRepository expenseImageRepo;
    @Mock PledgeController           pledgeController;
    @Mock com.churchgeniuspro.repository.FinancialReportLetterRepository letterRepo;
    @Mock CheckExtractor             extractor;
    @Mock VisionCheckService         vision;
    @Mock VisionUploadService        visionUpload;

    IncomeService          incomeService;
    ExpenseService         expenseService;
    IncomeController       incomeController;
    ExpenseController      expenseController;
    SourceController       sourceController;
    PurposeController      purposeController;
    TransactionTypeController transactionTypeController;
    AccountingReportController reportController;
    AccountantDashboardController dashboardController;
    CheckScanController    checkScanController;
    ExpenseCheckController expenseCheckController;

    @BeforeEach
    void setUp() {
        incomeService  = new IncomeService(incomeRepo, memberRepo, subSourceRepo, transactionTypeRepo, pledgeController);
        expenseService = new ExpenseService(expenseRepo, purposeRepo, mainSourceRepo, transactionTypeRepo);
        incomeController  = new IncomeController(incomeService);
        expenseController = new ExpenseController(expenseService);
        sourceController  = new SourceController(new SourceService(mainSourceRepo, subSourceRepo));
        purposeController = new PurposeController(new PurposeService(purposeRepo));
        transactionTypeController = new TransactionTypeController(new TransactionTypeService(transactionTypeRepo));
        reportController    = new AccountingReportController(incomeRepo, expenseRepo, purposeRepo, churchRepo);
        reportController.setLetterService(
                new com.churchgeniuspro.service.FinancialReportLetterService(letterRepo));
        dashboardController = new AccountantDashboardController(churchRepo, incomeRepo, expenseRepo, mainSourceRepo,
                                                                incomeService, expenseService, ledgerSummary);
        checkScanController    = new CheckScanController(extractor, incomeService, incomeImageRepo, incomeRepo,
                                                         vision, visionUpload);
        expenseCheckController = new ExpenseCheckController(extractor, vision, expenseImageRepo, expenseRepo, visionUpload);

        // save() echoes the row back, assigning an id to new rows (the controllers put it in a Map.of)
        when(incomeRepo.save(any(Income.class))).thenAnswer(i -> {
            Income x = i.getArgument(0); if (x.getId() == null) x.setId(101); return x; });
        when(expenseRepo.save(any(Expense.class))).thenAnswer(i -> {
            Expense x = i.getArgument(0); if (x.getId() == null) x.setId(101); return x; });
        when(mainSourceRepo.save(any(MainSource.class))).thenAnswer(i -> {
            MainSource x = i.getArgument(0); if (x.getId() == null) x.setId(101); return x; });
        when(subSourceRepo.save(any(SubSource.class))).thenAnswer(i -> {
            SubSource x = i.getArgument(0); if (x.getId() == null) x.setId(101); return x; });
        when(purposeRepo.save(any(Purpose.class))).thenAnswer(i -> {
            Purpose x = i.getArgument(0); if (x.getId() == null) x.setId(101); return x; });
        when(transactionTypeRepo.save(any(TransactionType.class))).thenAnswer(i -> {
            TransactionType x = i.getArgument(0); if (x.getId() == null) x.setId(101); return x; });
    }

    // ── session fixtures ────────────────────────────────────────────────────

    private static MockHttpServletRequest staffOf(String clientId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", clientId);
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "staff@" + clientId);
        s.setAttribute("role", role);
        req.setSession(s);
        return req;
    }

    private static MockHttpServletRequest accountant() { return staffOf(OURS, "Accountant"); }
    private static MockHttpServletRequest plainUser()  { return staffOf(OURS, "User"); }
    private static MockHttpServletRequest anonymous()  { return new MockHttpServletRequest(); }

    // ── entity fixtures ─────────────────────────────────────────────────────

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

    private static FamilyMember member(int id, String tenant) {
        FamilyMember m = new FamilyMember();
        m.setId(id); m.setFirstName("Ada"); m.setLastName("Lovelace"); m.setAppClientId(tenant);
        return m;
    }

    private static Income income(int id, String tenant) {
        Income i = new Income();
        i.setId(id); i.setAppClientId(tenant); i.setAmount(new BigDecimal("10.00"));
        i.setSubSource(subSource(1, tenant)); i.setTransactionType(method(1, tenant));
        return i;
    }

    private static Expense expense(int id, String tenant) {
        Expense e = new Expense();
        e.setId(id); e.setAppClientId(tenant); e.setAmount(new BigDecimal("10.00"));
        e.setPurpose(purpose(1, tenant)); e.setMainSource(mainSource(1, tenant)); e.setTransactionType(method(1, tenant));
        return e;
    }

    private static Map<String, Object> incomeBody(Integer memberId, int subSourceId, int methodId) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (memberId != null) b.put("memberId", memberId);
        b.put("subSourceId", subSourceId);
        b.put("incomeDate", "2026-01-15");
        b.put("transactionTypeId", methodId);
        b.put("amount", "25.00");
        return b;
    }

    private static Map<String, Object> expenseBody(int purposeId, int mainSourceId, int methodId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("purposeId", purposeId);
        b.put("mainSourceId", mainSourceId);
        b.put("expenseDate", "2026-01-15");
        b.put("transactionTypeId", methodId);
        b.put("amount", "25.00");
        return b;
    }

    /** Our own fund / method / purpose / member all resolve through the scoped finders. */
    private void ourLookupsExist() {
        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(subSource(1, OURS)));
        when(transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(method(1, OURS)));
        when(purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(purpose(1, OURS)));
        when(mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, OURS)).thenReturn(Optional.of(mainSource(1, OURS)));
        when(memberRepo.findByIdAndTenant(7, OURS)).thenReturn(Optional.of(member(7, OURS)));
    }

    private static int status(ResponseEntity<?> res) { return res.getStatusCode().value(); }

    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Income")
    class IncomeApi {

        @Test void updateCannotReachAnotherChurchsIncome() {
            ourLookupsExist();
            when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

            ResponseEntity<?> res = incomeController.updateIncome(9, incomeBody(null, 1, 1), accountant());

            assertThat(status(res)).isEqualTo(400);
            verify(incomeRepo, never()).save(any(Income.class));
            verify(incomeRepo, never()).findById(anyInt());
        }

        @Test void updateRejectsForeignMemberFundAndMethod() {
            when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(income(5, OURS)));
            // a member / fund / method that exists — but in THEIR church — is invisible to the scoped finders
            when(memberRepo.findById(55)).thenReturn(Optional.of(member(55, THEIRS)));
            when(subSourceRepo.findById(66)).thenReturn(Optional.of(subSource(66, THEIRS)));
            when(transactionTypeRepo.findById(77)).thenReturn(Optional.of(method(77, THEIRS)));

            ResponseEntity<?> res = incomeController.updateIncome(5, incomeBody(55, 66, 77), accountant());

            assertThat(status(res)).isEqualTo(400);
            verify(incomeRepo, never()).save(any(Income.class));
            verify(memberRepo, never()).findById(anyInt());
            verify(subSourceRepo, never()).findById(anyInt());
            verify(transactionTypeRepo, never()).findById(anyInt());
        }

        @Test void createRejectsForeignMember() {
            ourLookupsExist();
            when(memberRepo.findById(55)).thenReturn(Optional.of(member(55, THEIRS)));

            ResponseEntity<?> res = incomeController.createIncome(incomeBody(55, 1, 1), accountant());

            assertThat(status(res)).isEqualTo(400);
            verify(incomeRepo, never()).save(any(Income.class));
            verify(memberRepo).findByIdAndTenant(55, OURS);
        }

        @Test void unstarAndDeleteAreTenantScoped() {
            when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

            assertThat(status(incomeController.unstarIncome(9, accountant()))).isEqualTo(400);
            assertThat(status(incomeController.deleteIncome(9, accountant()))).isEqualTo(400);
            verify(incomeRepo, never()).save(any(Income.class));
            verify(incomeRepo, never()).findById(anyInt());
        }

        @Test void sameTenantUpdateAndDeleteStillWork() {
            ourLookupsExist();
            when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(income(5, OURS)));

            ResponseEntity<?> upd = incomeController.updateIncome(5, incomeBody(7, 1, 1), accountant());
            assertThat(status(upd)).as("%s", upd.getBody()).isEqualTo(200);
            ResponseEntity<?> del = incomeController.deleteIncome(5, accountant());
            assertThat(status(del)).isEqualTo(200);
            verify(incomeRepo, org.mockito.Mockito.times(2)).save(any(Income.class));
        }

        @Test void requiresAccountantOrAdminRole() {
            assertThat(status(incomeController.getRecentIncomes(0, 50, plainUser()))).isEqualTo(403);
            assertThat(status(incomeController.getSubSources(anonymous()))).isEqualTo(403);
            assertThat(status(incomeController.updateIncome(5, incomeBody(null, 1, 1), plainUser()))).isEqualTo(403);
            assertThat(status(incomeController.deleteIncome(5, plainUser()))).isEqualTo(403);
            assertThat(status(incomeController.unstarIncome(5, plainUser()))).isEqualTo(403);
            verify(incomeRepo, never()).findActivePageByAppUser(anyString(), any());
            verify(incomeRepo, never()).save(any(Income.class));
        }
    }

    @Nested
    @DisplayName("Expense")
    class ExpenseApi {

        @Test void updateCannotReachAnotherChurchsExpense() {
            ourLookupsExist();
            when(expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

            ResponseEntity<?> res = expenseController.update(9, expenseBody(1, 1, 1), accountant());

            assertThat(status(res)).isEqualTo(400);
            verify(expenseRepo, never()).save(any(Expense.class));
            verify(expenseRepo, never()).findById(anyInt());
        }

        @Test void updateRejectsForeignPurposeFundAndMethod() {
            when(expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(expense(5, OURS)));
            when(purposeRepo.findById(55)).thenReturn(Optional.of(purpose(55, THEIRS)));
            when(mainSourceRepo.findById(66)).thenReturn(Optional.of(mainSource(66, THEIRS)));
            when(transactionTypeRepo.findById(77)).thenReturn(Optional.of(method(77, THEIRS)));

            ResponseEntity<?> res = expenseController.update(5, expenseBody(55, 66, 77), accountant());

            assertThat(status(res)).isEqualTo(400);
            verify(expenseRepo, never()).save(any(Expense.class));
            verify(purposeRepo, never()).findById(anyInt());
            verify(mainSourceRepo, never()).findById(anyInt());
            verify(transactionTypeRepo, never()).findById(anyInt());
        }

        @Test void createRejectsForeignPurpose() {
            ourLookupsExist();
            when(purposeRepo.findById(55)).thenReturn(Optional.of(purpose(55, THEIRS)));

            ResponseEntity<?> res = expenseController.create(expenseBody(55, 1, 1), accountant());

            assertThat(status(res)).isEqualTo(400);
            verify(expenseRepo, never()).save(any(Expense.class));
        }

        @Test void unstarAndDeleteAreTenantScoped() {
            when(expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

            assertThat(status(expenseController.unstar(9, accountant()))).isEqualTo(400);
            assertThat(status(expenseController.delete(9, accountant()))).isEqualTo(400);
            verify(expenseRepo, never()).save(any(Expense.class));
            verify(expenseRepo, never()).findById(anyInt());
        }

        @Test void sameTenantUpdateAndDeleteStillWork() {
            ourLookupsExist();
            when(expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(expense(5, OURS)));

            ResponseEntity<?> upd = expenseController.update(5, expenseBody(1, 1, 1), accountant());
            assertThat(status(upd)).as("%s", upd.getBody()).isEqualTo(200);
            assertThat(status(expenseController.delete(5, accountant()))).isEqualTo(200);
            verify(expenseRepo, org.mockito.Mockito.times(2)).save(any(Expense.class));
        }

        @Test void requiresAccountantOrAdminRole() {
            assertThat(status(expenseController.getAll(0, 50, plainUser()))).isEqualTo(403);
            assertThat(status(expenseController.getPurposes(anonymous()))).isEqualTo(403);
            assertThat(status(expenseController.update(5, expenseBody(1, 1, 1), plainUser()))).isEqualTo(403);
            assertThat(status(expenseController.delete(5, plainUser()))).isEqualTo(403);
            assertThat(status(expenseController.unstar(5, plainUser()))).isEqualTo(403);
            verify(expenseRepo, never()).findActivePageByAppUser(anyString(), any());
            verify(expenseRepo, never()).save(any(Expense.class));
        }
    }

    @Nested
    @DisplayName("Sources and sub-sources")
    class SourcesApi {

        @Test void mainSourceUpdateAndDeleteAreTenantScoped() {
            when(mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());
            when(mainSourceRepo.findById(9)).thenReturn(Optional.of(mainSource(9, THEIRS)));

            assertThat(status(sourceController.updateMainSource(9, Map.of("sourceName", "X"), accountant()))).isEqualTo(400);
            assertThat(status(sourceController.deleteMainSource(9, accountant()))).isEqualTo(400);
            verify(mainSourceRepo, never()).save(any(MainSource.class));
            verify(subSourceRepo, never()).softDeleteByMainSource(any(MainSource.class));
            verify(mainSourceRepo, never()).findById(anyInt());
        }

        @Test void subSourcesCannotBeListedOrCreatedUnderAnotherChurchsMainSource() {
            when(mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

            assertThat(status(sourceController.getSubSources(9, accountant()))).isEqualTo(400);
            assertThat(status(sourceController.createSubSource(9, Map.of("sourceName", "X"), accountant()))).isEqualTo(400);
            verify(subSourceRepo, never()).save(any(SubSource.class));
            verify(subSourceRepo, never()).findByMainSourceActiveByAppUser(anyInt(), anyString());
        }

        @Test void subSourceUpdateAndDeleteAreTenantScoped() {
            when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());
            when(subSourceRepo.findById(9)).thenReturn(Optional.of(subSource(9, THEIRS)));

            assertThat(status(sourceController.updateSubSource(9, Map.of("sourceName", "X"), accountant()))).isEqualTo(400);
            assertThat(status(sourceController.deleteSubSource(9, accountant()))).isEqualTo(400);
            verify(subSourceRepo, never()).save(any(SubSource.class));
            verify(subSourceRepo, never()).findById(anyInt());
        }

        @Test void sameTenantSourcesStillWork() {
            ourLookupsExist();
            when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(2, OURS)).thenReturn(Optional.of(subSource(2, OURS)));

            assertThat(status(sourceController.updateMainSource(1, Map.of("sourceName", "Renamed"), accountant()))).isEqualTo(200);
            assertThat(status(sourceController.createSubSource(1, Map.of("sourceName", "New"), accountant()))).isEqualTo(200);
            assertThat(status(sourceController.updateSubSource(2, Map.of("sourceName", "Renamed"), accountant()))).isEqualTo(200);
            assertThat(status(sourceController.deleteMainSource(1, accountant()))).isEqualTo(200);
            verify(subSourceRepo).softDeleteByMainSource(any(MainSource.class));
        }

        @Test void requiresAccountantOrAdminRole() {
            assertThat(status(sourceController.getAllMainSources(plainUser()))).isEqualTo(403);
            assertThat(status(sourceController.deleteMainSource(1, plainUser()))).isEqualTo(403);
            assertThat(status(sourceController.updateSubSource(1, Map.of("sourceName", "X"), plainUser()))).isEqualTo(403);
            assertThat(status(sourceController.deleteSubSource(1, anonymous()))).isEqualTo(403);
            verify(mainSourceRepo, never()).findActiveByAppUser(anyString());
        }
    }

    @Nested
    @DisplayName("Purposes and transaction types")
    class LookupTablesApi {

        @Test void purposeUpdateAndDeleteAreTenantScoped() {
            when(purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());
            when(purposeRepo.findById(9)).thenReturn(Optional.of(purpose(9, THEIRS)));

            assertThat(status(purposeController.update(9, Map.of("purposeName", "X"), accountant()))).isEqualTo(400);
            assertThat(status(purposeController.delete(9, accountant()))).isEqualTo(400);
            verify(purposeRepo, never()).save(any(Purpose.class));
            verify(purposeRepo, never()).findById(anyInt());
        }

        @Test void transactionTypeUpdateAndDeleteAreTenantScoped() {
            when(transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());
            when(transactionTypeRepo.findById(9)).thenReturn(Optional.of(method(9, THEIRS)));

            assertThat(status(transactionTypeController.update(9, Map.of("typeName", "X"), accountant()))).isEqualTo(400);
            assertThat(status(transactionTypeController.delete(9, accountant()))).isEqualTo(400);
            verify(transactionTypeRepo, never()).save(any(TransactionType.class));
            verify(transactionTypeRepo, never()).findById(anyInt());
        }

        @Test void sameTenantRowsStillWork() {
            ourLookupsExist();
            assertThat(status(purposeController.update(1, Map.of("purposeName", "Renamed"), accountant()))).isEqualTo(200);
            assertThat(status(purposeController.delete(1, accountant()))).isEqualTo(200);
            assertThat(status(transactionTypeController.update(1, Map.of("typeName", "Renamed"), accountant()))).isEqualTo(200);
            assertThat(status(transactionTypeController.delete(1, accountant()))).isEqualTo(200);
        }

        @Test void requiresAccountantOrAdminRole() {
            assertThat(status(purposeController.getAll(plainUser()))).isEqualTo(403);
            assertThat(status(purposeController.delete(1, plainUser()))).isEqualTo(403);
            assertThat(status(transactionTypeController.getAll(anonymous()))).isEqualTo(403);
            assertThat(status(transactionTypeController.update(1, Map.of("typeName", "X"), plainUser()))).isEqualTo(403);
            verify(purposeRepo, never()).findActiveByAppUser(anyString());
            verify(transactionTypeRepo, never()).findActiveByAppUser(anyString());
        }
    }

    @Nested
    @DisplayName("Reports and accountant dashboard")
    class ReportsApi {

        @Test void purposeLookupIsTenantScoped() {
            when(purposeRepo.findActiveByAppUser(OURS)).thenReturn(List.of(purpose(1, OURS)));

            ResponseEntity<?> res = reportController.getPurposes(accountant());

            assertThat(status(res)).isEqualTo(200);
            assertThat(String.valueOf(res.getBody())).contains("Purpose 1");
            verify(purposeRepo, never()).findByDeleteFlagFalseOrderByPurposeNameAsc();
        }

        @Test void taxReportResolvesChurchFromSessionOnly() {
            ChurchRegistration ours = new ChurchRegistration();
            ours.setChurchName("Our Church");
            when(churchRepo.findByClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.of(ours));
            // the request carries another church's clientId — it must be ignored
            MockHttpServletRequest req = accountant();
            req.setParameter("clientId", THEIRS);

            ResponseEntity<?> res = reportController.taxReport(2025, null, null, req);

            assertThat(status(res)).isEqualTo(200);
            assertThat(String.valueOf(res.getBody())).contains("Our Church");
            verify(churchRepo, never()).findByClientIdAndDeleteFlagFalse(THEIRS);
            verify(incomeRepo).reportAnnualIncomeByMemberFiltered(eq(2025), eq(OURS), any(), any());
        }

        @Test void dashboardResolvesChurchFromSessionOnly() {
            ChurchRegistration ours = new ChurchRegistration();
            ours.setChurchName("Our Church");
            when(churchRepo.findByClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.of(ours));

            ResponseEntity<Map<String, Object>> res = dashboardController.dashboard(0, accountant());

            assertThat(status(res)).isEqualTo(200);
            assertThat(res.getBody().get("churchName")).isEqualTo("Our Church");
            verify(churchRepo, never()).findByClientIdAndDeleteFlagFalse(THEIRS);
            verify(ledgerSummary).totalIncome(OURS);
            verify(ledgerSummary).incomeBySubCategory(OURS);
            verify(ledgerSummary).expenseByMainSource(OURS);
            verify(ledgerSummary, never()).totalIncome(THEIRS);
        }

        @Test void ledgerSummaryRebuildIsScopedToSessionChurch() {
            when(ledgerSummary.rebuild(OURS)).thenReturn(42);
            MockHttpServletRequest req = accountant();
            req.setParameter("clientId", THEIRS);

            ResponseEntity<Map<String, Object>> res = dashboardController.rebuildLedgerSummary(req);

            assertThat(status(res)).isEqualTo(200);
            assertThat(res.getBody().get("rows")).isEqualTo(42);
            verify(ledgerSummary).rebuild(OURS);
            verify(ledgerSummary, never()).rebuild(THEIRS);
            assertThat(status(dashboardController.rebuildLedgerSummary(plainUser()))).isEqualTo(403);
        }

        @Test void requiresAccountantOrAdminRole() {
            assertThat(status(reportController.getPurposes(plainUser()))).isEqualTo(403);
            assertThat(status(reportController.incomeReport("2025-01-01", "2025-12-31", null, null, plainUser()))).isEqualTo(403);
            assertThat(status(reportController.expenseReport("2025-01-01", "2025-12-31", null, plainUser()))).isEqualTo(403);
            assertThat(status(reportController.transactionsReport("2025-01-01", "2025-12-31", plainUser()))).isEqualTo(403);
            assertThat(status(reportController.taxReport(2025, null, null, plainUser()))).isEqualTo(403);
            assertThat(status(reportController.getContributors(2025, plainUser()))).isEqualTo(403);
            assertThat(status(reportController.financialReport(2025, anonymous()))).isEqualTo(403);
            assertThat(status(dashboardController.dashboard(0, plainUser()))).isEqualTo(403);
            verify(ledgerSummary, never()).totalIncome(anyString());
            verify(incomeRepo, never()).reportIncomeTransactionsFiltered(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Giving-statement letter text")
    class LetterTextApi {

        private com.churchgeniuspro.hibernate.FinancialReportLetter letter(String tenant, String intro) {
            com.churchgeniuspro.hibernate.FinancialReportLetter r =
                    new com.churchgeniuspro.hibernate.FinancialReportLetter();
            r.setAppClientId(tenant);
            r.setIntroHtml(intro);
            r.setClosingHtml("");
            return r;
        }

        /** A row exists for each church; only one of them may ever be reachable. */
        private void bothChurchesHaveWording() {
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(OURS))
                    .thenReturn(Optional.of(letter(OURS, "<p>Our wording</p>")));
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(THEIRS))
                    .thenReturn(Optional.of(letter(THEIRS, "<p>Their wording</p>")));
        }

        @Test void editorReadsOnlyTheSessionsChurch() {
            bothChurchesHaveWording();
            MockHttpServletRequest req = accountant();
            req.setParameter("clientId", THEIRS);          // ignored: the tenant is the session's

            ResponseEntity<?> res = reportController.getLetterContent(req);

            assertThat(status(res)).isEqualTo(200);
            assertThat(String.valueOf(res.getBody())).contains("Our wording");
            assertThat(String.valueOf(res.getBody())).doesNotContain("Their wording");
            verify(letterRepo, never()).findFirstByAppClientIdAndDeleteFlagFalse(THEIRS);
            verify(letterRepo, never()).findById(anyInt());
            verify(letterRepo, never()).findAll();
        }

        @Test void savedWordingIsStampedWithTheSessionsChurch() {
            bothChurchesHaveWording();
            Map<String, String> body = new LinkedHashMap<>();
            body.put("introHtml",   "<p>New wording</p>");
            body.put("closingHtml", "");
            body.put("clientId",    THEIRS);               // a client-supplied tenant must not be honoured

            ResponseEntity<?> res = reportController.saveLetterContent(body, accountant());

            assertThat(status(res)).isEqualTo(200);
            org.mockito.ArgumentCaptor<com.churchgeniuspro.hibernate.FinancialReportLetter> saved =
                    org.mockito.ArgumentCaptor.forClass(
                            com.churchgeniuspro.hibernate.FinancialReportLetter.class);
            verify(letterRepo).save(saved.capture());
            assertThat(saved.getValue().getAppClientId()).isEqualTo(OURS);
            assertThat(saved.getValue().getIntroHtml()).isEqualTo("<p>New wording</p>");
            verify(letterRepo, never()).findFirstByAppClientIdAndDeleteFlagFalse(THEIRS);
        }

        @Test void restoringTheDefaultRetiresOnlyTheSessionsRow() {
            com.churchgeniuspro.hibernate.FinancialReportLetter ours = letter(OURS, "<p>Our wording</p>");
            com.churchgeniuspro.hibernate.FinancialReportLetter theirs = letter(THEIRS, "<p>Their wording</p>");
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.of(ours));
            when(letterRepo.findFirstByAppClientIdAndDeleteFlagFalse(THEIRS)).thenReturn(Optional.of(theirs));

            assertThat(status(reportController.resetLetterContent(accountant()))).isEqualTo(200);

            assertThat(ours.isDeleteFlag()).isTrue();
            assertThat(theirs.isDeleteFlag()).as("the other church is untouched").isFalse();
            verify(letterRepo).save(ours);
            verify(letterRepo, never()).save(theirs);
            verify(letterRepo, never()).deleteAll();
            verify(letterRepo, never()).deleteById(anyInt());
        }

        @Test void theReportCarriesThisChurchsWordingOnly() {
            bothChurchesHaveWording();
            ChurchRegistration reg = new ChurchRegistration();
            reg.setChurchName("Our Church");
            when(churchRepo.findByClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.of(reg));

            ResponseEntity<?> res = reportController.taxReport(2025, null, null, accountant());

            assertThat(String.valueOf(res.getBody())).contains("Our wording");
            assertThat(String.valueOf(res.getBody())).doesNotContain("Their wording");
            verify(letterRepo, never()).findFirstByAppClientIdAndDeleteFlagFalse(THEIRS);
        }

        @Test void requiresAccountantOrAdminRole() {
            assertThat(status(reportController.getLetterContent(plainUser()))).isEqualTo(403);
            assertThat(status(reportController.saveLetterContent(
                    Map.of("introHtml", "<p>x</p>"), plainUser()))).isEqualTo(403);
            assertThat(status(reportController.resetLetterContent(plainUser()))).isEqualTo(403);
            assertThat(status(reportController.getLetterContent(anonymous()))).isEqualTo(403);
            assertThat(status(reportController.saveLetterContent(
                    Map.of("introHtml", "<p>x</p>"), anonymous()))).isEqualTo(403);
            assertThat(status(reportController.resetLetterContent(anonymous()))).isEqualTo(403);

            verify(letterRepo, never()).save(any());
            verify(letterRepo, never()).findFirstByAppClientIdAndDeleteFlagFalse(anyString());
        }
    }

    @Nested
    @DisplayName("Check images")
    class CheckImagesApi {

        private final MockMultipartFile file =
                new MockMultipartFile("file", "check.png", "image/png", new byte[]{1, 2, 3});

        @Test void incomeImageCannotBeAttachedToAnotherChurchsIncome() {
            when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

            ResponseEntity<?> res = checkScanController.storeImage(9, file, accountant());

            assertThat(status(res)).isEqualTo(404);
            verify(incomeImageRepo, never()).save(any(IncomeCheckImage.class));
        }

        @Test void incomeImageAttachesToOwnIncome() {
            when(incomeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(income(5, OURS)));
            when(incomeImageRepo.save(any(IncomeCheckImage.class))).thenAnswer(i -> {
                IncomeCheckImage img = i.getArgument(0); img.setId(1L); return img;
            });

            ResponseEntity<?> res = checkScanController.storeImage(5, file, accountant());

            assertThat(status(res)).as("%s", res.getBody()).isEqualTo(200);
            verify(incomeImageRepo).save(any(IncomeCheckImage.class));
        }

        @Test void expenseImageCannotBeAttachedToAnotherChurchsExpense() {
            when(expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

            ResponseEntity<?> res = expenseCheckController.storeImage(9, file, accountant());

            assertThat(status(res)).isEqualTo(404);
            verify(expenseImageRepo, never()).save(any(ExpenseCheckImage.class));
        }

        @Test void expenseImageAttachesToOwnExpense() {
            when(expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(expense(5, OURS)));
            when(expenseImageRepo.save(any(ExpenseCheckImage.class))).thenAnswer(i -> {
                ExpenseCheckImage img = i.getArgument(0); img.setId(1L); return img;
            });

            ResponseEntity<?> res = expenseCheckController.storeImage(5, file, accountant());

            assertThat(status(res)).as("%s", res.getBody()).isEqualTo(200);
            verify(expenseImageRepo).save(any(ExpenseCheckImage.class));
        }

        @Test void requiresAccountantOrAdminRole() {
            assertThat(status(checkScanController.storeImage(5, file, plainUser()))).isEqualTo(403);
            assertThat(status(checkScanController.getImage(5, plainUser()))).isEqualTo(403);
            assertThat(status(expenseCheckController.storeImage(5, file, plainUser()))).isEqualTo(403);
            assertThat(status(expenseCheckController.getImage(5, anonymous()))).isEqualTo(403);
            verify(incomeImageRepo, never()).save(any(IncomeCheckImage.class));
            verify(expenseImageRepo, never()).save(any(ExpenseCheckImage.class));
        }
    }
}
