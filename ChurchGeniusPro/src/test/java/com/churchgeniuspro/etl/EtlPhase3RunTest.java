package com.churchgeniuspro.etl;

import com.churchgeniuspro.common.ImportRunStatus;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Migration Phase 3 — multi-table runs: household proposals (admin-confirmed, never
 * automatic), the persistent link resolver, dependency-ordered run load, part-way
 * failure handling, and a rollback that is only ever as "complete" as the data.
 */
@DisplayName("ETL Phase 3 — households, links, ordered load, truthful rollback")
class EtlPhase3RunTest {

    private static final String TENANT = "CHR-test";
    private static final Long RUN = 88L;

    private static StagingFamily person(long id, String rowId, String first, String last, String addr, String city, String zip) {
        StagingFamily r = new StagingFamily(); r.setId(id); r.setRunId(RUN); r.setClientId(TENANT); r.setSourceRowId(rowId); r.setFamilyKey(rowId);
        r.setFirstName(first); r.setLastName(last); r.setAddress1(addr); r.setCity(city); r.setPinCode(zip); r.setRowStatus("PENDING");
        return r;
    }

    // ───────────────────────── households ─────────────────────────

    @Nested
    @DisplayName("Household proposals")
    class Households {
        @Test
        @DisplayName("same normalised last name + street + zip → one proposal; singles, rows with their own household id, or no address → none; prior decision survives when the key is unchanged")
        void propose() throws Exception {
            StagingFamily a = person(1, "P1", "Grace", "Example", "123 Main St.", "Olathe", "66061");
            StagingFamily b = person(2, "P2", "Thomas", "EXAMPLE", "123 Main Street", "Olathe", "66061");   // "Street" = "St." 
            StagingFamily c = person(3, "P3", "Lily", "Example", "123 Main St", "Olathe", "66061"); c.setHouseholdProposal("example|123mainst|66061"); c.setHouseholdDecision("ACCEPTED");
            StagingFamily single = person(4, "P4", "Sam", "Sampleton", "9 Oak Ave", "Olathe", "66061");
            StagingFamily ownHousehold = person(5, "P5", "Ada", "Example", "123 Main St", "Olathe", "66061"); ownHousehold.setFamilyKey("F77");   // file gave a household id
            StagingFamily noAddress = person(6, "P6", "Ben", "Example", null, "Olathe", "66061");
            StagingFamily stale = person(7, "P7", "Old", "Decision", "1 Elm", "Olathe", "66061"); stale.setHouseholdProposal("something|else"); stale.setHouseholdDecision("REJECTED");
            StagingFamily stale2 = person(8, "P8", "New", "Decision", "1 Elm", "Olathe", "66061");
            List<StagingFamily> rows = List.of(a, b, c, single, ownHousehold, noAddress, stale, stale2);

            var m = EtlValidationService.class.getDeclaredMethod("proposeHouseholds", List.class); m.setAccessible(true);
            int n = (int) m.invoke(null, rows);

            assertThat(n).isEqualTo(5);                                 // Example ×3 + Decision ×2
            assertThat(a.getHouseholdProposal()).isEqualTo("example|123mainst|66061");
            assertThat(b.getHouseholdProposal()).isEqualTo(a.getHouseholdProposal());
            assertThat(c.getHouseholdProposal()).isEqualTo(a.getHouseholdProposal());
            assertThat(a.getHouseholdDecision()).isNull();              // never decided for the admin
            assertThat(c.getHouseholdDecision()).isEqualTo("ACCEPTED"); // kept: same key as before
            assertThat(single.getHouseholdProposal()).isNull();
            assertThat(ownHousehold.getHouseholdProposal()).isNull();
            assertThat(noAddress.getHouseholdProposal()).isNull();
            assertThat(stale.getHouseholdProposal()).isEqualTo("decision|1elm|66061");
            assertThat(stale.getHouseholdDecision()).isNull();          // key changed since the decision → undecided again
        }

        @Test
        @DisplayName("validation records the proposal as an INFO message without changing the row's grade")
        void validationInfo() {
            StagingFamilyRepository familyRepo = mock(StagingFamilyRepository.class);
            FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
            ImportRunService runService = mock(ImportRunService.class);
            TransformStageService stage = mock(TransformStageService.class);
            EtlValidationService svc = new EtlValidationService(familyRepo, mock(StagingIncomeRepository.class), mock(StagingExpenseRepository.class),
                memberRepo, runService, new EtlMatchService(memberRepo, null, null), stage);
            ImportRun run = new ImportRun(); run.setId(RUN); run.setClientId(TENANT); run.setSourcePreset("GENERIC"); run.setStatus("STAGED");
            when(runService.getRun(RUN, TENANT)).thenReturn(run);
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of());
            when(familyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            StagingFamily a = person(1, "1", "Grace", "Example", "123 Main St", "Olathe", "66061");
            StagingFamily b = person(2, "2", "Thomas", "Example", "123 Main St", "Olathe", "66061");
            when(familyRepo.findByRunId(RUN)).thenReturn(List.of(a, b));

            svc.validate(RUN, TENANT, "actor", "family");
            assertThat(a.getRowStatus()).isEqualTo("VALID");
            assertThat(a.getValidationMsgs()).contains("\"level\":\"INFO\"").contains("proposed household").contains("undecided");
            assertThat(b.getHouseholdProposal()).isEqualTo(a.getHouseholdProposal());
        }
    }

    // ───────────────────────── link service ─────────────────────────

    @Nested
    @DisplayName("EtlLinkService")
    class Links {
        ImportLinkRepository links = mock(ImportLinkRepository.class);
        SubSourceRepository subRepo = mock(SubSourceRepository.class);
        MainSourceRepository mainRepo = mock(MainSourceRepository.class);
        IncomeRepository incomeRepo = mock(IncomeRepository.class);
        ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        EtlLinkService svc = new EtlLinkService(links, subRepo, mainRepo, mock(PurposeRepository.class), mock(TransactionTypeRepository.class), incomeRepo, expenseRepo);

        @Test
        @DisplayName("record creates once, is a no-op for the same target, and re-points when the admin's decision moved the key")
        void record() {
            when(links.findByClientIdAndSourceSystemAndEntityAndSourceKey(TENANT, "BREEZE", "person", "10001")).thenReturn(Optional.empty());
            svc.record(TENANT, "BREEZE", "person", " 10001 ", 400L, RUN, 5L);
            ArgumentCaptor<ImportLink> cap = ArgumentCaptor.forClass(ImportLink.class);
            verify(links).save(cap.capture());
            ImportLink l = cap.getValue();
            assertThat(l.getSourceKey()).isEqualTo("10001"); assertThat(l.getTargetId()).isEqualTo(400L); assertThat(l.getBatchId()).isEqualTo(5L);

            l.setId(1L);
            when(links.findByClientIdAndSourceSystemAndEntityAndSourceKey(TENANT, "BREEZE", "person", "10001")).thenReturn(Optional.of(l));
            svc.record(TENANT, "BREEZE", "person", "10001", 400L, RUN, 6L);
            verify(links, times(1)).save(any());                        // same target → nothing written
            svc.record(TENANT, "BREEZE", "person", "10001", 401L, 99L, 7L);
            verify(links, times(2)).save(any());
            assertThat(l.getTargetId()).isEqualTo(401L); assertThat(l.getRunId()).isEqualTo(99L);

            svc.record(TENANT, "BREEZE", "person", null, 1L, RUN, 5L);   // no key → ignored
            svc.record(TENANT, "BREEZE", "person", "x", null, RUN, 5L);  // no target → ignored
            verify(links, times(2)).save(any());
        }

        @Test
        @DisplayName("index returns every link of one entity for the tenant + system; resolve finds one")
        void index() {
            ImportLink a = new ImportLink(); a.setSourceKey("10001"); a.setTargetId(400L);
            ImportLink b = new ImportLink(); b.setSourceKey("10002"); b.setTargetId(401L);
            when(links.findByClientIdAndSourceSystemAndEntity(TENANT, "BREEZE", "person")).thenReturn(List.of(a, b));
            assertThat(svc.index(TENANT, "BREEZE", "person")).containsEntry("10001", 400L).containsEntry("10002", 401L);
            assertThat(svc.index(TENANT, null, "person")).isEmpty();
            when(links.findByClientIdAndSourceSystemAndEntityAndSourceKey(TENANT, "BREEZE", "person", "10001")).thenReturn(Optional.of(a));
            assertThat(svc.resolve(TENANT, "BREEZE", "person", "10001")).contains(400L);
            assertThat(svc.resolve(TENANT, "BREEZE", "person", "nope")).isEmpty();
        }

        @Test
        @DisplayName("rollback deletes the batch's links, soft-deletes the funds it created that nothing uses, keeps one the church has since posted to")
        void rollback() {
            ImportLink person = new ImportLink(); person.setClientId(TENANT); person.setEntity("person"); person.setSourceKey("10001"); person.setTargetId(400L);
            ImportLink fundUnused = new ImportLink(); fundUnused.setClientId(TENANT); fundUnused.setEntity("ref:sub_source"); fundUnused.setSourceKey("1|Building"); fundUnused.setTargetId(21L);
            ImportLink fundUsed = new ImportLink(); fundUsed.setClientId(TENANT); fundUsed.setEntity("ref:sub_source"); fundUsed.setSourceKey("1|Tithe"); fundUsed.setTargetId(22L);
            ImportLink main = new ImportLink(); main.setClientId(TENANT); main.setEntity("ref:main_source"); main.setSourceKey("Imported"); main.setTargetId(1L);
            ImportLink foreign = new ImportLink(); foreign.setClientId("CHR-other"); foreign.setEntity("ref:sub_source"); foreign.setSourceKey("x"); foreign.setTargetId(99L);
            when(links.findByBatchId(5L)).thenReturn(List.of(person, fundUnused, fundUsed, main, foreign));
            SubSource unused = new SubSource(); unused.setId(21); unused.setAppClientId(TENANT); unused.setDeleteFlag(false);
            SubSource used = new SubSource(); used.setId(22); used.setAppClientId(TENANT); used.setDeleteFlag(false);
            MainSource ms = new MainSource(); ms.setId(1); ms.setAppClientId(TENANT); ms.setDeleteFlag(false);
            when(subRepo.findById(21)).thenReturn(Optional.of(unused));
            when(subRepo.findById(22)).thenReturn(Optional.of(used));
            when(mainRepo.findById(1)).thenReturn(Optional.of(ms));
            when(incomeRepo.countBySubSource_IdAndDeleteFlagFalse(21)).thenReturn(0L);
            when(incomeRepo.countBySubSource_IdAndDeleteFlagFalse(22)).thenReturn(3L);
            when(expenseRepo.countByMainSource_IdAndDeleteFlagFalse(1)).thenReturn(0L);
            when(subRepo.countByMainSource_IdAndDeleteFlagFalse(1)).thenReturn(1L);     // "Tithe" still alive under it

            int[] r = svc.rollbackBatch(TENANT, 5L);
            assertThat(r).containsExactly(4, 1, 2);                      // 4 tenant links deleted; Building removed; Tithe + main kept
            assertThat(unused.isDeleteFlag()).isTrue();
            assertThat(used.isDeleteFlag()).isFalse();
            assertThat(ms.isDeleteFlag()).isFalse();
            verify(links, times(4)).delete(any(ImportLink.class));
            verify(links, never()).delete(foreign);
        }
    }

    // ───────────────────────── loader ─────────────────────────

    @Nested
    @DisplayName("EtlLoadService — ordered run load, households, failure, truthful rollback")
    class Load {
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        StagingFamilyRepository sFamilyRepo = mock(StagingFamilyRepository.class);
        StagingIncomeRepository sIncomeRepo = mock(StagingIncomeRepository.class);
        StagingExpenseRepository sExpenseRepo = mock(StagingExpenseRepository.class);
        FamilyRepository familyRepo = mock(FamilyRepository.class);
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        IncomeRepository incomeRepo = mock(IncomeRepository.class);
        ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        ImportRunService runService = mock(ImportRunService.class);
        TransformStageService stage = mock(TransformStageService.class);
        EtlLinkService linkService = mock(EtlLinkService.class);
        EtlLoadService service;
        ImportRun run;
        List<ImportBatch> batches = new ArrayList<>();

        private ImportBatch batch(long id, String table, String status) {
            ImportBatch b = new ImportBatch(); b.setId(id); b.setRunId(RUN); b.setClientId(TENANT); b.setTargetTable(table); b.setStatus(status);
            batches.add(b);
            when(batchRepo.findByIdAndClientId(id, TENANT)).thenReturn(Optional.of(b));
            when(batchRepo.findById(id)).thenReturn(Optional.of(b));
            return b;
        }

        @BeforeEach
        void setUp() {
            MainSourceRepository mainRepo = mock(MainSourceRepository.class); SubSourceRepository subRepo = mock(SubSourceRepository.class);
            when(mainRepo.save(any(MainSource.class))).thenAnswer(inv -> { MainSource m = inv.getArgument(0); m.setId(1); return m; });
            when(subRepo.save(any(SubSource.class))).thenAnswer(inv -> { SubSource x = inv.getArgument(0); x.setId(2); return x; });
            EtlReferenceResolver ref = new EtlReferenceResolver(mainRepo, subRepo, mock(PurposeRepository.class), mock(TransactionTypeRepository.class));
            service = new EtlLoadService(batchRepo, sFamilyRepo, sIncomeRepo, sExpenseRepo, familyRepo, memberRepo, incomeRepo, expenseRepo, ref, runService,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
            service.setStageService(stage);
            service.setLinkService(linkService);
            run = new ImportRun(); run.setId(RUN); run.setClientId(TENANT); run.setStatus("APPROVED"); run.setSourcePreset("BREEZE");
            when(runService.getRun(RUN, TENANT)).thenReturn(run);
            when(runService.transition(eq(RUN), eq(TENANT), anyString(), any())).thenAnswer(inv -> { run.setStatus(((ImportRunStatus) inv.getArgument(3)).name()); return run; });
            when(stage.hasSourceIdsForTarget(eq(RUN), anyString())).thenReturn(true);
            when(sFamilyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sIncomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(batchRepo.findByRunIdOrderByIdAsc(RUN)).thenAnswer(inv -> new ArrayList<>(batches));
            when(batchRepo.findByRunIdAndTargetTable(eq(RUN), anyString())).thenAnswer(inv -> batches.stream().filter(b -> b.getTargetTable().equals(inv.getArgument(1))).toList());
            AtomicInteger famIds = new AtomicInteger(300), memIds = new AtomicInteger(400), incIds = new AtomicInteger(900);
            when(familyRepo.save(any(Family.class))).thenAnswer(inv -> { Family f = inv.getArgument(0); if (f.getId() == null) f.setId(famIds.getAndIncrement()); return f; });
            when(memberRepo.save(any(FamilyMember.class))).thenAnswer(inv -> { FamilyMember m = inv.getArgument(0); if (m.getId() == null) m.setId(memIds.getAndIncrement()); return m; });
            when(incomeRepo.save(any(Income.class))).thenAnswer(inv -> { Income e = inv.getArgument(0); if (e.getId() == null) e.setId(incIds.getAndIncrement()); return e; });
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of());
            when(sFamilyRepo.findByRunId(RUN)).thenReturn(List.of());
            when(sIncomeRepo.findByRunId(RUN)).thenReturn(List.of());
            when(sExpenseRepo.findByRunId(RUN)).thenReturn(List.of());
            when(linkService.rollbackBatch(eq(TENANT), anyLong())).thenReturn(new int[]{0, 0, 0});
            when(linkService.index(anyString(), anyString(), anyString())).thenReturn(Map.of());
        }

        @Test
        @DisplayName("an ACCEPTED proposal loads into ONE household; an undecided one stays two; the proposal never becomes a source id")
        void households() {
            StagingFamily a = person(1, "P1", "Grace", "Example", "123 Main", "Olathe", "66061"); a.setHouseholdProposal("example|123main|66061"); a.setHouseholdDecision("ACCEPTED");
            StagingFamily b = person(2, "P2", "Thomas", "Example", "123 Main", "Olathe", "66061"); b.setHouseholdProposal("example|123main|66061"); b.setHouseholdDecision("ACCEPTED");
            StagingFamily c = person(3, "P3", "Sam", "Sampleton", "9 Oak", "Olathe", "66061"); c.setHouseholdProposal("sampleton|9oak|66061");
            StagingFamily d = person(4, "P4", "Sue", "Sampleton", "9 Oak", "Olathe", "66061"); d.setHouseholdProposal("sampleton|9oak|66061");
            StagingFamily e = person(5, "P5", "Ada", "Own", null, null, null); e.setFamilyKey("F9");
            List<StagingFamily> rows = List.of(a, b, c, d, e);
            rows.forEach(r -> { r.setRowStatus("APPROVED"); r.setBatchId(5L); r.setDedupeAction("INSERT"); });
            batch(5, "family", "APPROVED");
            when(sFamilyRepo.findByBatchId(5L)).thenReturn(rows);
            when(sFamilyRepo.findAllById(any())).thenReturn(rows);

            ImportBatch out = service.loadBatch(5L, TENANT, "actor");
            assertThat(out.getStatus()).isEqualTo("LOADED"); assertThat(out.getInsertedCount()).isEqualTo(5);
            assertThat(a.getParentTargetId()).isEqualTo(b.getParentTargetId());          // one household
            assertThat(c.getParentTargetId()).isNotEqualTo(d.getParentTargetId());       // undecided → separate
            assertThat(e.getParentTargetId()).isNotIn(a.getParentTargetId(), c.getParentTargetId(), d.getParentTargetId());
            ArgumentCaptor<Family> fc = ArgumentCaptor.forClass(Family.class);
            verify(familyRepo, times(4)).save(fc.capture());
            Family hh = fc.getAllValues().stream().filter(f -> f.getId().equals(a.getParentTargetId().intValue())).findFirst().orElseThrow();
            assertThat(hh.getSourceId()).isNull();                                        // proposal is not an id
            Family own = fc.getAllValues().stream().filter(f -> f.getId().equals(e.getParentTargetId().intValue())).findFirst().orElseThrow();
            assertThat(own.getSourceId()).isEqualTo("F9"); assertThat(own.getSourceSystem()).isEqualTo("BREEZE");
            // links learned: 5 people + 1 household with a real id
            verify(linkService, times(5)).record(eq(TENANT), eq("BREEZE"), eq("person"), anyString(), anyLong(), eq(RUN), eq(5L));
            verify(linkService, times(1)).record(eq(TENANT), eq("BREEZE"), eq("household"), eq("F9"), anyLong(), eq(RUN), eq(5L));
            assertThat(run.getStatus()).isEqualTo("LOADED");
        }

        @Test
        @DisplayName("loadRun loads people before gifts; a gift batch alone is refused while the people batch is unloaded; gifts resolve by the staged person's own id")
        void orderedLoad() {
            StagingFamily p = person(1, "10001", "Grace", "Example", null, null, null); p.setFamilyKey("F1"); p.setRowStatus("APPROVED"); p.setBatchId(5L); p.setDedupeAction("INSERT");
            batch(5, "family", "APPROVED");
            when(sFamilyRepo.findByBatchId(5L)).thenReturn(List.of(p));
            when(sFamilyRepo.findAllById(any())).thenReturn(List.of(p));
            when(sFamilyRepo.findByRunId(RUN)).thenReturn(List.of(p));
            StagingIncome g = new StagingIncome(); g.setId(1L); g.setRunId(RUN); g.setClientId(TENANT); g.setBatchId(6L); g.setRowStatus("APPROVED"); g.setDedupeAction("INSERT");
            g.setAmount(new BigDecimal("50")); g.setIncomeDate(LocalDate.of(2026, 2, 1)); g.setSourceName("Tithe"); g.setImportRef("ETL:BREEZE:TX1"); g.setFamilyLink("10001"); g.setSourceRowId("TX1");
            batch(6, "income", "APPROVED");
            when(sIncomeRepo.findByBatchId(6L)).thenReturn(List.of(g));
            when(sIncomeRepo.findAllById(any())).thenReturn(List.of(g));

            assertThatThrownBy(() -> service.loadBatch(6L, TENANT, "actor")).hasMessageContaining("Load the people batch #5 first");

            List<ImportBatch> done = service.loadRun(RUN, TENANT, "actor");
            assertThat(done).extracting(ImportBatch::getTargetTable).containsExactly("family", "income");
            assertThat(done).extracting(ImportBatch::getStatus).containsOnly("LOADED");
            when(memberRepo.findById(400)).thenReturn(Optional.of(new FamilyMember()));
            assertThat(g.getResolvedMemberId()).isEqualTo(400L);                            // "10001" → staged person's live id
            verify(linkService).record(TENANT, "BREEZE", "income", "TX1", 900L, RUN, 6L);
            verify(linkService, atLeastOnce()).recordReferenceRows(eq(TENANT), eq("BREEZE"), eq(RUN), eq(6L), any());
            assertThatThrownBy(() -> service.loadRun(RUN, TENANT, "actor")).hasMessageContaining("No approved batches");
        }

        @Test
        @DisplayName("a load that stops part-way leaves the batch FAILED with the committed counts and reason; retry continues; rollback reverses what committed")
        void partialFailure() throws Exception {
            var f = EtlLoadService.class.getDeclaredField("chunkSize"); f.setAccessible(true); f.setInt(service, 1);
            StagingFamily a = person(1, "P1", "A", "One", null, null, null); StagingFamily b = person(2, "P2", "B", "Two", null, null, null);
            for (StagingFamily r : List.of(a, b)) { r.setRowStatus("APPROVED"); r.setBatchId(5L); r.setDedupeAction("INSERT"); }
            ImportBatch batch = batch(5, "family", "APPROVED");
            when(sFamilyRepo.findByBatchId(5L)).thenReturn(List.of(a, b));
            AtomicInteger calls = new AtomicInteger();
            when(sFamilyRepo.findAllById(any())).thenAnswer(inv -> {
                if (calls.incrementAndGet() == 2) throw new IllegalStateException("connection reset");
                List<Long> ids = new ArrayList<>(); inv.<Iterable<Long>>getArgument(0).forEach(ids::add);
                return List.of(ids.contains(1L) ? a : b);
            });

            assertThatThrownBy(() -> service.loadBatch(5L, TENANT, "actor")).isInstanceOf(EtlLoadService.LoadException.class)
                .hasMessageContaining("Load stopped: connection reset").hasMessageContaining("retry continues");
            assertThat(batch.getStatus()).isEqualTo("FAILED");
            assertThat(batch.getInsertedCount()).isEqualTo(1);                              // the first chunk is real
            assertThat(batch.getErrorMessage()).isEqualTo("connection reset");
            assertThat(a.getRowStatus()).isEqualTo("LOADED"); assertThat(b.getRowStatus()).isEqualTo("APPROVED");
            assertThat(run.getStatus()).isEqualTo("APPROVED");                              // the run is NOT claimed loaded
            verify(runService).recordAudit(eq(RUN), eq(TENANT), eq("actor"), eq("LOAD_FAILED"), any());

            // retry: only the still-APPROVED row is loaded; counts continue from the batch
            when(sFamilyRepo.findByBatchId(5L)).thenReturn(List.of(b));
            ImportBatch again = service.loadBatch(5L, TENANT, "actor");
            assertThat(again.getStatus()).isEqualTo("LOADED"); assertThat(again.getInsertedCount()).isEqualTo(2); assertThat(again.getErrorMessage()).isNull();
            assertThat(run.getStatus()).isEqualTo("LOADED");

            // a FAILED batch is reversible too
            batch.setStatus("FAILED");
            when(sFamilyRepo.findByBatchId(5L)).thenReturn(List.of(a, b));
            when(memberRepo.findById(anyInt())).thenAnswer(inv -> { FamilyMember m = new FamilyMember(); m.setId(inv.getArgument(0)); Family fam = new Family(); fam.setAppClientId(TENANT); m.setFamily(fam); return Optional.of(m); });
            ImportBatch rb = service.rollbackBatch(5L, TENANT, "actor");
            assertThat(rb.getStatus()).isEqualTo("ROLLED_BACK");
            assertThat(a.getRowStatus()).isEqualTo("ROLLED_BACK"); assertThat(b.getRowStatus()).isEqualTo("ROLLED_BACK");
            verify(linkService).rollbackBatch(TENANT, 5L);
            assertThat(run.getStatus()).isEqualTo("ROLLED_BACK");
        }

        @Test
        @DisplayName("the run stays LOADED while any batch is still live; people cannot be reversed before the run's gifts; rollbackRun reverses in reverse order and ends ROLLED_BACK")
        void truthfulRollback() {
            run.setStatus("LOADED");
            ImportBatch fam = batch(5, "family", "LOADED"), inc = batch(6, "income", "LOADED"), exp = batch(7, "expense", "LOADED");
            when(sFamilyRepo.findByBatchId(5L)).thenReturn(List.of()); when(sIncomeRepo.findByBatchId(6L)).thenReturn(List.of()); when(sExpenseRepo.findByBatchId(7L)).thenReturn(List.of());

            assertThatThrownBy(() -> service.rollbackBatch(5L, TENANT, "actor")).hasMessageContaining("Roll back the giving batch #6 first");

            service.rollbackBatch(7L, TENANT, "actor");
            assertThat(exp.getStatus()).isEqualTo("ROLLED_BACK");
            assertThat(run.getStatus()).isEqualTo("LOADED");                                // two batches still live → not rolled back
            verify(runService, never()).transition(eq(RUN), eq(TENANT), anyString(), eq(ImportRunStatus.ROLLED_BACK));

            List<ImportBatch> done = service.rollbackRun(RUN, TENANT, "actor");
            assertThat(done).extracting(ImportBatch::getTargetTable).containsExactly("income", "family");
            assertThat(fam.getStatus()).isEqualTo("ROLLED_BACK"); assertThat(inc.getStatus()).isEqualTo("ROLLED_BACK");
            assertThat(run.getStatus()).isEqualTo("ROLLED_BACK");
            ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
            verify(runService, times(3)).recordAudit(eq(RUN), eq(TENANT), eq("actor"), eq("ROLLBACK"), detail.capture());
            assertThat(detail.getAllValues().get(0)).containsEntry("runStillLoaded", true);
            assertThat(detail.getAllValues().get(2)).containsEntry("runStillLoaded", false);
            assertThatThrownBy(() -> service.rollbackRun(RUN, TENANT, "actor")).hasMessageContaining("Nothing to roll back");
        }

        @Test
        @DisplayName("runPlan: one entry per table in load order with counts, batch state and canApprove")
        void plan() {
            StagingFamily v = person(1, "P1", "A", "B", null, null, null); v.setRowStatus("VALID");
            StagingFamily rv = person(2, "P2", "C", "D", null, null, null); rv.setRowStatus("REVIEW");
            when(sFamilyRepo.findByRunId(RUN)).thenReturn(List.of(v, rv));
            StagingIncome g = new StagingIncome(); g.setRowStatus("WARN");
            when(sIncomeRepo.findByRunId(RUN)).thenReturn(List.of(g));
            ImportBatch b = batch(6, "income", "APPROVED");
            List<Map<String, Object>> plan = service.runPlan(RUN, TENANT);
            assertThat(plan).extracting(m -> m.get("targetTable")).containsExactly("family", "income", "expense");
            Map<String, Object> f = plan.get(0), i = plan.get(1), e = plan.get(2);
            assertThat(f.get("staged")).isEqualTo(2); assertThat(f.get("review")).isEqualTo(1L); assertThat(f.get("canApprove")).isEqualTo(false); assertThat(f.get("state")).isEqualTo("REVIEW");
            assertThat(i.get("state")).isEqualTo("APPROVED"); assertThat(i.get("dependsOn")).isEqualTo("family"); assertThat(((List<?>) i.get("batches"))).hasSize(1);
            assertThat(e.get("state")).isEqualTo("NOT_STAGED"); assertThat(e.get("canApprove")).isEqualTo(false);
        }
    }

    // ───────────────────────── stage guard ─────────────────────────

    @Test
    @DisplayName("TransformStageService refuses to re-stage a table whose batch is approved, loaded or part-loaded")
    void stageGuard() {
        MappingRuleRepository ruleRepo = mock(MappingRuleRepository.class);
        StagingRawRepository rawRepo = mock(StagingRawRepository.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        TransformStageService svc = new TransformStageService(ruleRepo, rawRepo, mock(StagingFamilyRepository.class), mock(StagingIncomeRepository.class),
            mock(StagingExpenseRepository.class), mock(ImportRunService.class), mock(ImportValueMapRepository.class), mock(ImportFileRepository.class));
        svc.setBatchRepo(batchRepo);
        MappingRule r = new MappingRule(); r.setTargetTable("family"); r.setTargetColumn("firstName"); r.setSourceTable("people"); r.setSourceColumn("First"); r.setStatus("AUTO");
        when(ruleRepo.findByRunIdAndTargetTable(RUN, "family")).thenReturn(List.of(r));
        when(rawRepo.findByRunIdAndSourceTable(RUN, "people")).thenReturn(List.of(new StagingRaw()));
        ImportBatch b = new ImportBatch(); b.setId(5L); b.setStatus("LOADED");
        when(batchRepo.findByRunIdAndTargetTable(RUN, "family")).thenReturn(List.of(b));
        assertThatThrownBy(() -> svc.stage(RUN, TENANT, "actor", "family")).isInstanceOf(TransformStageService.StageException.class)
            .hasMessageContaining("already has batch #5 (LOADED)");
    }

    // ───────────────────────── validation resolves across the run ─────────────────────────

    @Test
    @DisplayName("gift donors resolve to a staged person by their own id, to a skipped duplicate's live member, and through links from earlier runs")
    void moneyResolution() {
        StagingFamilyRepository familyRepo = mock(StagingFamilyRepository.class);
        StagingIncomeRepository incomeRepo = mock(StagingIncomeRepository.class);
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        IncomeRepository liveIncome = mock(IncomeRepository.class);
        ImportRunService runService = mock(ImportRunService.class);
        EtlLinkService links = mock(EtlLinkService.class);
        EtlValidationService svc = new EtlValidationService(familyRepo, incomeRepo, mock(StagingExpenseRepository.class), memberRepo, runService,
            new EtlMatchService(memberRepo, liveIncome, null), mock(TransformStageService.class));
        svc.setLinkService(links);
        ImportRun run = new ImportRun(); run.setId(RUN); run.setClientId(TENANT); run.setSourcePreset("BREEZE"); run.setStatus("STAGED");
        when(runService.getRun(RUN, TENANT)).thenReturn(run);
        FamilyMember live = new FamilyMember(); live.setId(7); FamilyMember linked = new FamilyMember(); linked.setId(8);
        when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of(live, linked));
        when(links.index(TENANT, "BREEZE", "person")).thenReturn(Map.of("30003", 8L, "30009", 999L));   // 999 no longer active → ignored
        StagingFamily stagedOnly = person(1, "10001", "A", "B", null, null, null); stagedOnly.setFamilyKey("F1"); stagedOnly.setRowStatus("VALID");
        StagingFamily skippedDup = person(2, "20002", "C", "D", null, null, null); skippedDup.setRowStatus("SKIPPED"); skippedDup.setDedupeMatchId(7L);
        when(familyRepo.findByRunId(RUN)).thenReturn(List.of(stagedOnly, skippedDup));
        when(liveIncome.findByAppClientIdAndDeleteFlagFalseAndIncomeDateBetween(eq(TENANT), any(), any())).thenReturn(List.of());
        when(incomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StagingIncome a = gift(1, "10001", "R1"), b = gift(2, "20002", "R2"), c = gift(3, "30003", "R3"), d = gift(4, "30009", "R4");
        when(incomeRepo.findByRunId(RUN)).thenReturn(List.of(a, b, c, d));

        svc.validate(RUN, TENANT, "actor", "income");
        assertThat(a.getRowStatus()).isEqualTo("VALID");     // staged person's own id (not just the household key)
        assertThat(b.getRowStatus()).isEqualTo("VALID");     // skipped duplicate → its live member
        assertThat(c.getRowStatus()).isEqualTo("VALID");     // link from an earlier run
        assertThat(d.getRowStatus()).isEqualTo("WARN"); assertThat(d.getValidationMsgs()).contains("Could not resolve family link '30009'");
    }

    private static StagingIncome gift(long id, String link, String ref) {
        StagingIncome g = new StagingIncome(); g.setId(id); g.setRunId(RUN); g.setClientId(TENANT); g.setAmount(new BigDecimal("10")); g.setIncomeDate(LocalDate.of(2026, 3, 1));
        g.setSourceName("Tithe"); g.setFamilyLink(link); g.setImportRef("ETL:BREEZE:" + ref);
        return g;
    }
}
