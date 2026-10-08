package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.ImportBatch;
import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.StagingExpense;
import com.churchgeniuspro.hibernate.StagingIncome;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.ImportBatchRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.StagingExpenseRepository;
import com.churchgeniuspro.repository.StagingFamilyRepository;
import com.churchgeniuspro.repository.StagingIncomeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Financial audit M9: choosing UPDATE for a staged income/expense row the
 * dedupe step flagged as a duplicate never actually updated anything —
 * {@code loadIncomeChunk}/{@code loadExpenseChunk} only ever recognised SKIP;
 * every other action, UPDATE included, fell through to the same INSERT
 * branch and created a second, duplicate ledger row, with the batch's
 * "updated" counter permanently stuck at 0. {@link EtlLoadService#loadBatch}
 * now honours UPDATE with a tenant-filtered lookup by {@code dedupeMatchId},
 * mirroring the family table's own (already-correct) UPDATE branch, and
 * {@link EtlLoadService#rollbackBatch} restores an UPDATE's prior values
 * from a before-image on rollback rather than soft-deleting a live row the
 * import never created — a companion bug that shipping UPDATE without also
 * fixing would have introduced (rollback previously soft-deleted
 * unconditionally, with no before-image check at all).
 *
 * <p>No ETL test file existed before this one, so these tests drive the
 * service through its public entry points ({@code loadBatch}/{@code
 * rollbackBatch}) exactly as the controller layer does, with a mocked
 * {@link PlatformTransactionManager} left unstubbed — the loader's chunk
 * callbacks never touch the {@code TransactionStatus} they're handed, so a
 * plain mock transparently passes every call through.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ETL load — UPDATE and its rollback for income/expense (M9)")
class EtlLoadUpdateTest {

    private static final String TENANT       = "CHR-ours";
    private static final String OTHER_TENANT = "CHR-theirs";
    private static final Long   RUN_ID       = 900L;
    private static final Long   BATCH_ID     = 5000L;

    @Mock ImportBatchRepository      batchRepo;
    @Mock StagingFamilyRepository    sFamilyRepo;
    @Mock StagingIncomeRepository    sIncomeRepo;
    @Mock StagingExpenseRepository   sExpenseRepo;
    @Mock FamilyRepository           familyRepo;
    @Mock FamilyMemberRepository     memberRepo;
    @Mock IncomeRepository           incomeRepo;
    @Mock ExpenseRepository          expenseRepo;
    @Mock MainSourceRepository       mainSourceRepo;
    @Mock SubSourceRepository        subSourceRepo;
    @Mock PurposeRepository          purposeRepo;
    @Mock ImportRunService           runService;
    @Mock PlatformTransactionManager txManager;

    private EtlLoadService service;

    // Shared reference-data fixtures: "old"/"new" pairs so an UPDATE test can
    // prove the fund/purpose actually changed, and a rollback test can prove
    // it changed back.
    private MainSource mainTithes, mainOperating, mainFacilities;
    private SubSource  subGeneral, subBuilding;
    private Purpose    purposeUtilities, purposeSupplies;

    @BeforeEach
    void setUp() {
        EtlReferenceResolver refResolver = new EtlReferenceResolver(mainSourceRepo, subSourceRepo, purposeRepo);
        service = new EtlLoadService(batchRepo, sFamilyRepo, sIncomeRepo, sExpenseRepo,
            familyRepo, memberRepo, incomeRepo, expenseRepo, refResolver, runService, txManager);

        mainTithes     = mainSourceFixture(1, "Tithes");
        mainOperating  = mainSourceFixture(2, "Operating");
        mainFacilities = mainSourceFixture(3, "Facilities");
        subGeneral       = subSourceFixture(10, mainTithes, "General");
        subBuilding      = subSourceFixture(20, mainTithes, "Building Fund");
        purposeUtilities = purposeFixture(30, "Utilities");
        purposeSupplies  = purposeFixture(40, "Supplies");

        when(mainSourceRepo.findActiveByAppUser(TENANT))
            .thenReturn(List.of(mainTithes, mainOperating, mainFacilities));
        when(subSourceRepo.findAllActiveByAppUser(TENANT)).thenReturn(List.of(subGeneral, subBuilding));
        when(purposeRepo.findActiveByAppUser(TENANT)).thenReturn(List.of(purposeUtilities, purposeSupplies));
        when(mainSourceRepo.findById(1)).thenReturn(Optional.of(mainTithes));
        when(mainSourceRepo.findById(2)).thenReturn(Optional.of(mainOperating));
        when(mainSourceRepo.findById(3)).thenReturn(Optional.of(mainFacilities));
        when(subSourceRepo.findById(10)).thenReturn(Optional.of(subGeneral));
        when(subSourceRepo.findById(20)).thenReturn(Optional.of(subBuilding));
        when(purposeRepo.findById(30)).thenReturn(Optional.of(purposeUtilities));
        when(purposeRepo.findById(40)).thenReturn(Optional.of(purposeSupplies));

        when(sFamilyRepo.findByRunId(RUN_ID)).thenReturn(List.of());
        when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of());

        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sIncomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sExpenseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Simulate DB id assignment on INSERT (a fresh entity has id == null);
        // an UPDATE's entity already has an id, so it just passes through.
        AtomicInteger nextIncomeId = new AtomicInteger(9000);
        when(incomeRepo.save(any(Income.class))).thenAnswer(inv -> {
            Income e = inv.getArgument(0);
            if (e.getId() == null) e.setId(nextIncomeId.getAndIncrement());
            return e;
        });
        AtomicInteger nextExpenseId = new AtomicInteger(9500);
        when(expenseRepo.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            if (e.getId() == null) e.setId(nextExpenseId.getAndIncrement());
            return e;
        });

        ImportRun run = new ImportRun();
        run.setId(RUN_ID);
        run.setClientId(TENANT);
        run.setStatus("APPROVED");
        when(runService.getRun(RUN_ID, TENANT)).thenReturn(run);
    }

    @Nested
    @DisplayName("Income")
    class IncomeTests {

        @Test
        @DisplayName("UPDATE with a tenant-owned dedupeMatchId updates the live row instead of inserting a duplicate; an unflagged row in the same batch still inserts")
        void updateModifiesExistingRow_insertStillWorks_bothCountersCorrect() {
            Income existing = existingIncome(501, new BigDecimal("123.45"), LocalDate.of(2026, 1, 10),
                subGeneral, null, "OLD-REF-1", "Old note text");
            when(incomeRepo.findById(501)).thenReturn(Optional.of(existing));

            StagingIncome updateRow = incomeStagingRow(1L, "UPDATE", 501L,
                new BigDecimal("999.99"), LocalDate.of(2026, 2, 20), "Tithes", "Building Fund",
                null, null);
            StagingIncome insertRow = incomeStagingRow(2L, null, null,
                new BigDecimal("50.00"), LocalDate.of(2026, 3, 1), "Tithes", "General",
                "NEW-REF-2", "brand new row");

            ImportBatch batch = runIncomeLoad(List.of(updateRow, insertRow));

            assertThat(batch.getUpdatedCount()).isEqualTo(1);
            assertThat(batch.getInsertedCount()).isEqualTo(1);
            assertThat(batch.getFailedCount()).isEqualTo(0);

            // The UPDATE branch modified the SAME live row — never created a new one.
            assertThat(existing.getAmount()).isEqualByComparingTo("999.99");
            assertThat(existing.getIncomeDate()).isEqualTo(LocalDate.of(2026, 2, 20));
            assertThat(existing.getSubSource()).isSameAs(subBuilding);
            assertThat(updateRow.getTargetId()).isEqualTo(501L);
            assertThat(updateRow.getRowStatus()).isEqualTo("LOADED");
            assertThat(updateRow.getBeforeImage()).isNotNull().contains("123.45").contains("OLD-REF-1");

            // The plain row still inserted a brand-new Income.
            ArgumentCaptor<Income> savedCaptor = ArgumentCaptor.forClass(Income.class);
            verify(incomeRepo, times(2)).save(savedCaptor.capture());
            Income inserted = savedCaptor.getAllValues().stream()
                .filter(i -> "NEW-REF-2".equals(i.getRefNo())).findFirst().orElseThrow();
            assertThat(inserted.getId()).isNotNull().isNotEqualTo(501);
            assertThat(inserted.getAmount()).isEqualByComparingTo("50.00");
            assertThat(inserted.getAppClientId()).isEqualTo(TENANT);
            assertThat(insertRow.getRowStatus()).isEqualTo("LOADED");
        }

        @Test
        @DisplayName("UPDATE only overwrites refNo/note when the staging row actually supplies them, and never blanks an existing member link it can't resolve")
        void updateSparseFieldsPreserved_memberLinkPreservedWhenUnresolvable() {
            FamilyMember existingMember = member(77);
            Income existing = existingIncome(502, new BigDecimal("10.00"), LocalDate.of(2026, 1, 1),
                subGeneral, existingMember, "KEEP-ME", "keep this note");
            when(incomeRepo.findById(502)).thenReturn(Optional.of(existing));

            // Sparse: no referenceNo, no notes, no familyLink on the staging row.
            StagingIncome row = incomeStagingRow(3L, "UPDATE", 502L,
                new BigDecimal("11.00"), LocalDate.of(2026, 4, 4), "Tithes", "General", null, null);

            runIncomeLoad(List.of(row));

            assertThat(existing.getAmount()).isEqualByComparingTo("11.00");     // always overwritten
            assertThat(existing.getRefNo()).isEqualTo("KEEP-ME");               // sparse — preserved
            assertThat(existing.getNote()).isEqualTo("keep this note");         // sparse — preserved
            assertThat(existing.getMember()).isSameAs(existingMember);          // unresolvable link — preserved
        }

        @Test
        @DisplayName("UPDATE against a dedupeMatchId owned by another tenant fails safely — the row is marked FAILED and the other tenant's data is never touched")
        void crossTenantDedupeMatchFailsSafely() {
            Income foreign = existingIncome(503, new BigDecimal("77.00"), LocalDate.of(2026, 1, 1),
                subGeneral, null, "THEIRS", "their note");
            foreign.setAppClientId(OTHER_TENANT);
            when(incomeRepo.findById(503)).thenReturn(Optional.of(foreign));

            StagingIncome row = incomeStagingRow(4L, "UPDATE", 503L,
                new BigDecimal("999.00"), LocalDate.of(2026, 5, 5), "Tithes", "General", null, null);

            ImportBatch batch = runIncomeLoad(List.of(row));

            assertThat(batch.getFailedCount()).isEqualTo(1);
            assertThat(batch.getUpdatedCount()).isEqualTo(0);
            assertThat(row.getRowStatus()).isEqualTo("FAILED");
            verify(incomeRepo, never()).save(any());
            assertThat(foreign.getAmount()).isEqualByComparingTo("77.00");
            assertThat(foreign.getRefNo()).isEqualTo("THEIRS");
        }

        @Test
        @DisplayName("rollback of an UPDATE restores the exact prior amount and fund from the before-image, and never soft-deletes")
        void rollbackOfUpdateRestoresPriorValues() {
            Income existing = existingIncome(504, new BigDecimal("321.07"), LocalDate.of(2026, 1, 15),
                subGeneral, null, "PRE-UPDATE-REF", "pre-update note");
            when(incomeRepo.findById(504)).thenReturn(Optional.of(existing));

            StagingIncome row = incomeStagingRow(5L, "UPDATE", 504L,
                new BigDecimal("650.50"), LocalDate.of(2026, 6, 6), "Tithes", "Building Fund",
                "POST-UPDATE-REF", "post-update note");

            // Load first — this mutates `existing` to the new values AND populates
            // `row`'s real before-image via the production snapshot code (no
            // hand-authored JSON anywhere in this test).
            runIncomeLoad(List.of(row));
            assertThat(existing.getAmount()).isEqualByComparingTo("650.50"); // sanity: update applied

            ImportBatch rolledBack = runIncomeRollback(List.of(row));

            assertThat(rolledBack.getStatus()).isEqualTo("ROLLED_BACK");
            assertThat(row.getRowStatus()).isEqualTo("ROLLED_BACK");
            assertThat(existing.getAmount()).isEqualByComparingTo("321.07");
            assertThat(existing.getIncomeDate()).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(existing.getSubSource()).isSameAs(subGeneral);
            assertThat(existing.getRefNo()).isEqualTo("PRE-UPDATE-REF");
            assertThat(existing.getNote()).isEqualTo("pre-update note");
            assertThat(existing.isDeleteFlag()).isFalse();  // restored, never soft-deleted
            verify(incomeRepo, times(2)).save(existing);    // once at load, once at rollback
        }

        @Test
        @DisplayName("rollback of an INSERT still soft-deletes the created row (regression check — fixing UPDATE rollback must not change INSERT rollback)")
        void rollbackOfInsertStillSoftDeletes() {
            Income created = existingIncome(505, new BigDecimal("15.00"), LocalDate.of(2026, 7, 1),
                subGeneral, null, "INS-REF", "inserted note");
            when(incomeRepo.findById(505)).thenReturn(Optional.of(created));

            StagingIncome row = loadedIncomeRow(6L, 505L, null); // INSERTs never carry a before-image

            runIncomeRollback(List.of(row));

            assertThat(row.getRowStatus()).isEqualTo("ROLLED_BACK");
            assertThat(created.isDeleteFlag()).isTrue();
            assertThat(created.getAmount()).isEqualByComparingTo("15.00"); // untouched otherwise
            verify(incomeRepo).save(created);
        }

        @Test
        @DisplayName("rollback never writes to a live row owned by another tenant, even when targetId matches")
        void rollbackNeverTouchesAnotherTenantsRow() {
            Income foreign = existingIncome(506, new BigDecimal("42.00"), LocalDate.of(2026, 1, 1),
                subGeneral, null, "THEIRS-2", "their other note");
            foreign.setAppClientId(OTHER_TENANT);
            when(incomeRepo.findById(506)).thenReturn(Optional.of(foreign));

            StagingIncome row = loadedIncomeRow(7L, 506L, null);

            runIncomeRollback(List.of(row));

            assertThat(row.getRowStatus()).isEqualTo("ROLLED_BACK"); // staging bookkeeping still completes
            verify(incomeRepo, never()).save(any());
            assertThat(foreign.isDeleteFlag()).isFalse();
            assertThat(foreign.getAmount()).isEqualByComparingTo("42.00");
        }

        @Test
        @DisplayName("rollback restores a prior member link too, tenant-scoped")
        void rollbackRestoresMemberLink() {
            Family fam = familyFixture(200, TENANT);
            FamilyMember oldMember = member(80, fam);
            FamilyMember newMember = member(81, fam);
            newMember.setMemberRef("MREF-81");
            when(memberRepo.findById(80)).thenReturn(Optional.of(oldMember));
            when(memberRepo.findById(81)).thenReturn(Optional.of(newMember));
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of(newMember));

            Income existing = existingIncome(507, new BigDecimal("60.00"), LocalDate.of(2026, 1, 1),
                subGeneral, oldMember, "REF-507", "note 507");
            when(incomeRepo.findById(507)).thenReturn(Optional.of(existing));

            StagingIncome row = incomeStagingRow(8L, "UPDATE", 507L,
                new BigDecimal("70.00"), LocalDate.of(2026, 2, 2), "Tithes", "General", null, null);
            row.setFamilyLink("MREF-81");

            runIncomeLoad(List.of(row));
            assertThat(existing.getMember()).isSameAs(newMember); // sanity: update applied the new link

            runIncomeRollback(List.of(row));

            assertThat(existing.getMember()).isSameAs(oldMember);
        }

        @Test
        @DisplayName("defense-in-depth: rollback never re-attaches a member id from another tenant's family, even if a before-image somehow named it")
        void restoreNeverAttachesForeignTenantMember() {
            Family foreignFam = familyFixture(201, OTHER_TENANT);
            FamilyMember foreignMember = member(90, foreignFam);
            when(memberRepo.findById(90)).thenReturn(Optional.of(foreignMember));

            Income existing = existingIncome(508, new BigDecimal("15.00"), LocalDate.of(2026, 1, 1),
                subGeneral, foreignMember, "REF-508", "note 508");
            when(incomeRepo.findById(508)).thenReturn(Optional.of(existing));

            StagingIncome row = incomeStagingRow(9L, "UPDATE", 508L,
                new BigDecimal("16.00"), LocalDate.of(2026, 3, 3), "Tithes", "General", null, null);

            runIncomeLoad(List.of(row));
            runIncomeRollback(List.of(row));

            assertThat(existing.getMember()).isNull(); // refused, not re-attached — fails safe
        }
    }

    @Nested
    @DisplayName("Expense")
    class ExpenseTests {

        @Test
        @DisplayName("UPDATE with a tenant-owned dedupeMatchId updates the live row instead of inserting a duplicate")
        void updateModifiesExistingRow() {
            Expense existing = existingExpense(601, new BigDecimal("200.00"), LocalDate.of(2026, 1, 5),
                purposeUtilities, mainFacilities, "OLD-EXP-1", "Old expense note");
            when(expenseRepo.findById(601)).thenReturn(Optional.of(existing));

            StagingExpense row = expenseStagingRow(1L, "UPDATE", 601L,
                new BigDecimal("777.77"), LocalDate.of(2026, 2, 15), "Supplies", "Operating", null, null);

            ImportBatch batch = runExpenseLoad(List.of(row));

            assertThat(batch.getUpdatedCount()).isEqualTo(1);
            assertThat(batch.getInsertedCount()).isEqualTo(0);
            assertThat(existing.getAmount()).isEqualByComparingTo("777.77");
            assertThat(existing.getExpenseDate()).isEqualTo(LocalDate.of(2026, 2, 15));
            assertThat(existing.getPurpose()).isSameAs(purposeSupplies);
            assertThat(existing.getMainSource()).isSameAs(mainOperating);
            assertThat(existing.getRefNo()).isEqualTo("OLD-EXP-1");         // sparse — preserved
            assertThat(existing.getNote()).isEqualTo("Old expense note");   // sparse — preserved
            verify(expenseRepo, times(1)).save(existing);
        }

        @Test
        @DisplayName("UPDATE against a dedupeMatchId owned by another tenant fails safely with no cross-tenant write")
        void crossTenantDedupeMatchFailsSafely() {
            Expense foreign = existingExpense(602, new BigDecimal("88.00"), LocalDate.of(2026, 1, 1),
                purposeUtilities, mainFacilities, "THEIRS", "their note");
            foreign.setAppClientId(OTHER_TENANT);
            when(expenseRepo.findById(602)).thenReturn(Optional.of(foreign));

            StagingExpense row = expenseStagingRow(2L, "UPDATE", 602L,
                new BigDecimal("999.00"), LocalDate.of(2026, 3, 3), "Supplies", "Operating", null, null);

            ImportBatch batch = runExpenseLoad(List.of(row));

            assertThat(batch.getFailedCount()).isEqualTo(1);
            assertThat(row.getRowStatus()).isEqualTo("FAILED");
            verify(expenseRepo, never()).save(any());
            assertThat(foreign.getAmount()).isEqualByComparingTo("88.00");
        }

        @Test
        @DisplayName("rollback of an UPDATE restores both purpose and main source together from the before-image")
        void rollbackRestoresPurposeAndMainSourceTogether() {
            Expense existing = existingExpense(603, new BigDecimal("200.00"), LocalDate.of(2026, 1, 5),
                purposeUtilities, mainFacilities, "OLD-EXP-2", "Old expense note 2");
            when(expenseRepo.findById(603)).thenReturn(Optional.of(existing));

            StagingExpense row = expenseStagingRow(3L, "UPDATE", 603L,
                new BigDecimal("777.77"), LocalDate.of(2026, 2, 15), "Supplies", "Operating",
                "NEW-EXP-REF", "new expense note");

            runExpenseLoad(List.of(row));
            assertThat(existing.getMainSource()).isSameAs(mainOperating); // sanity: update applied

            runExpenseRollback(List.of(row));

            assertThat(row.getRowStatus()).isEqualTo("ROLLED_BACK");
            assertThat(existing.getAmount()).isEqualByComparingTo("200.00");
            assertThat(existing.getExpenseDate()).isEqualTo(LocalDate.of(2026, 1, 5));
            assertThat(existing.getPurpose()).isSameAs(purposeUtilities);
            assertThat(existing.getMainSource()).isSameAs(mainFacilities);
            assertThat(existing.getRefNo()).isEqualTo("OLD-EXP-2");
            assertThat(existing.getNote()).isEqualTo("Old expense note 2");
            assertThat(existing.isDeleteFlag()).isFalse();
        }
    }

    // ───────────────────────── batch drivers ─────────────────────────

    private ImportBatch runIncomeLoad(List<StagingIncome> rows) {
        ImportBatch batch = approvedBatch("income");
        when(batchRepo.findByIdAndClientId(BATCH_ID, TENANT)).thenReturn(Optional.of(batch));
        when(batchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(sIncomeRepo.findByBatchId(BATCH_ID)).thenReturn(rows);
        when(sIncomeRepo.findAllById(any())).thenReturn(rows);
        service.loadBatch(BATCH_ID, TENANT, "actor");
        return batch;
    }

    private ImportBatch runIncomeRollback(List<StagingIncome> rows) {
        ImportBatch batch = loadedBatch("income");
        when(batchRepo.findByIdAndClientId(BATCH_ID, TENANT)).thenReturn(Optional.of(batch));
        when(sIncomeRepo.findByBatchId(BATCH_ID)).thenReturn(rows);
        service.rollbackBatch(BATCH_ID, TENANT, "actor");
        return batch;
    }

    private ImportBatch runExpenseLoad(List<StagingExpense> rows) {
        ImportBatch batch = approvedBatch("expense");
        when(batchRepo.findByIdAndClientId(BATCH_ID, TENANT)).thenReturn(Optional.of(batch));
        when(batchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(sExpenseRepo.findByBatchId(BATCH_ID)).thenReturn(rows);
        when(sExpenseRepo.findAllById(any())).thenReturn(rows);
        service.loadBatch(BATCH_ID, TENANT, "actor");
        return batch;
    }

    private ImportBatch runExpenseRollback(List<StagingExpense> rows) {
        ImportBatch batch = loadedBatch("expense");
        when(batchRepo.findByIdAndClientId(BATCH_ID, TENANT)).thenReturn(Optional.of(batch));
        when(sExpenseRepo.findByBatchId(BATCH_ID)).thenReturn(rows);
        service.rollbackBatch(BATCH_ID, TENANT, "actor");
        return batch;
    }

    private ImportBatch approvedBatch(String table) {
        ImportBatch b = new ImportBatch();
        b.setId(BATCH_ID);
        b.setRunId(RUN_ID);
        b.setClientId(TENANT);
        b.setTargetTable(table);
        b.setStatus("APPROVED");
        return b;
    }

    private ImportBatch loadedBatch(String table) {
        ImportBatch b = approvedBatch(table);
        b.setStatus("LOADED");
        return b;
    }

    // ───────────────────────── fixtures ─────────────────────────

    private static MainSource mainSourceFixture(int id, String name) {
        MainSource m = new MainSource();
        m.setId(id);
        m.setSourceName(name);
        m.setAppClientId(TENANT);
        return m;
    }

    private static SubSource subSourceFixture(int id, MainSource main, String name) {
        SubSource s = new SubSource();
        s.setId(id);
        s.setMainSource(main);
        s.setSourceName(name);
        s.setAppClientId(TENANT);
        return s;
    }

    private static Purpose purposeFixture(int id, String name) {
        Purpose p = new Purpose();
        p.setId(id);
        p.setPurposeName(name);
        p.setAppClientId(TENANT);
        return p;
    }

    private static FamilyMember member(int id) {
        FamilyMember m = new FamilyMember();
        m.setId(id);
        return m;
    }

    private static FamilyMember member(int id, Family family) {
        FamilyMember m = new FamilyMember();
        m.setId(id);
        m.setFamily(family);
        return m;
    }

    private static Family familyFixture(int id, String tenant) {
        Family f = new Family();
        f.setId(id);
        f.setAppClientId(tenant);
        return f;
    }

    private Income existingIncome(int id, BigDecimal amount, LocalDate date, SubSource sub,
                                  FamilyMember mem, String refNo, String note) {
        Income e = new Income();
        e.setId(id);
        e.setAppClientId(TENANT);
        e.setAmount(amount);
        e.setIncomeDate(date);
        e.setSubSource(sub);
        e.setMember(mem);
        e.setRefNo(refNo);
        e.setNote(note);
        e.setDeleteFlag(false);
        return e;
    }

    private Expense existingExpense(int id, BigDecimal amount, LocalDate date, Purpose purpose,
                                    MainSource main, String refNo, String note) {
        Expense e = new Expense();
        e.setId(id);
        e.setAppClientId(TENANT);
        e.setAmount(amount);
        e.setExpenseDate(date);
        e.setPurpose(purpose);
        e.setMainSource(main);
        e.setRefNo(refNo);
        e.setNote(note);
        e.setDeleteFlag(false);
        return e;
    }

    private StagingIncome incomeStagingRow(long id, String dedupeAction, Long dedupeMatchId,
                                           BigDecimal amount, LocalDate date, String sourceName, String subSourceName,
                                           String referenceNo, String notes) {
        StagingIncome r = new StagingIncome();
        r.setId(id);
        r.setRunId(RUN_ID);
        r.setClientId(TENANT);
        r.setBatchId(BATCH_ID);
        r.setRowStatus("APPROVED");
        r.setDedupeAction(dedupeAction);
        r.setDedupeMatchId(dedupeMatchId);
        r.setAmount(amount);
        r.setIncomeDate(date);
        r.setSourceName(sourceName);
        r.setSubSourceName(subSourceName);
        r.setReferenceNo(referenceNo);
        r.setNotes(notes);
        return r;
    }

    private StagingExpense expenseStagingRow(long id, String dedupeAction, Long dedupeMatchId,
                                             BigDecimal amount, LocalDate date, String purposeName, String category,
                                             String referenceNo, String notes) {
        StagingExpense r = new StagingExpense();
        r.setId(id);
        r.setRunId(RUN_ID);
        r.setClientId(TENANT);
        r.setBatchId(BATCH_ID);
        r.setRowStatus("APPROVED");
        r.setDedupeAction(dedupeAction);
        r.setDedupeMatchId(dedupeMatchId);
        r.setAmount(amount);
        r.setExpenseDate(date);
        r.setPurposeName(purposeName);
        r.setCategory(category);
        r.setReferenceNo(referenceNo);
        r.setNotes(notes);
        return r;
    }

    private StagingIncome loadedIncomeRow(long id, Long targetId, String beforeImage) {
        StagingIncome r = new StagingIncome();
        r.setId(id);
        r.setClientId(TENANT);
        r.setBatchId(BATCH_ID);
        r.setRowStatus("LOADED");
        r.setTargetId(targetId);
        r.setBeforeImage(beforeImage);
        return r;
    }
}
