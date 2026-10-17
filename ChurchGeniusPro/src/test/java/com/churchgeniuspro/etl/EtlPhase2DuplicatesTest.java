package com.churchgeniuspro.etl;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.service.EtlMatchService.Match;
import com.churchgeniuspro.util.SourcePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Migration Phase 2 — identity and duplicates: match tiers per data type (live and
 * in-file), the REVIEW gate, the Skip / Update / Merge / Import-as-new decisions and
 * their guard rails, import references, and source identity written on load.
 */
@DisplayName("ETL Phase 2 — identity and duplicates")
class EtlPhase2DuplicatesTest {

    private static final String TENANT = "CHR-test";
    private static final Long RUN = 77L;

    private static FamilyMember member(int id, String first, String last, String email, String phone, Integer m, Integer d, Integer y, String system, String sourceId) {
        FamilyMember x = new FamilyMember(); x.setId(id); x.setFirstName(first); x.setLastName(last); x.setEmail(email); x.setPhone(phone);
        x.setBirthdayMonth(m); x.setBirthdayDay(d); x.setBirthdayYear(y); x.setSourceSystem(system); x.setSourceId(sourceId);
        Family f = new Family(); f.setId(id * 10); f.setAppClientId(TENANT); x.setFamily(f); x.setAppClientId(TENANT);
        return x;
    }
    private static StagingFamily staged(long id, String rowId, String first, String last, String email, String phone, Integer m, Integer d, Integer y) {
        StagingFamily r = new StagingFamily(); r.setId(id); r.setRunId(RUN); r.setClientId(TENANT); r.setSourceRowId(rowId);
        r.setFirstName(first); r.setLastName(last); r.setEmail(email); r.setPhone(phone); r.setBirthdayMonth(m); r.setBirthdayDay(d); r.setBirthdayYear(y);
        r.setFamilyKey(rowId);
        return r;
    }

    @Nested
    @DisplayName("EtlMatchService")
    class Matching {
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        IncomeRepository incomeRepo = mock(IncomeRepository.class);
        ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        EtlMatchService svc = new EtlMatchService(memberRepo, incomeRepo, expenseRepo);

        @BeforeEach
        void live() {
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of(
                member(1, "Grace", "Example", "grace.example@example.org", "9135550101", 4, 12, 1978, "BREEZE", "10001"),
                member(2, "Thomas", "Example", null, "(913) 555-0102", 9, 30, 1976, null, null),
                member(3, "Lily", "Example", null, null, 2, 14, 2012, null, null),
                member(4, "Sam", "Sampleton", null, null, null, null, null, null, null),
                member(5, "Sam", "Sampleton", null, null, null, null, null, null, null)));
        }

        @Test
        @DisplayName("family tiers: source id → EXACT (same system only); e-mail / name+phone / name+DOB → STRONG; name only → POSSIBLE; new → NONE")
        void familyTiers() {
            EtlMatchService.FamilyIndex idx = svc.familyIndex(TENANT, "BREEZE");
            assertThat(idx.match(staged(1, "10001", "G", "X", null, null, null, null, null)).tier()).isEqualTo("EXACT");
            Match byEmail = idx.match(staged(2, "20002", "Grace", "Example", "GRACE.example@example.org", null, null, null, null));
            assertThat(byEmail.tier()).isEqualTo("STRONG"); assertThat(byEmail.liveId()).isEqualTo(1L); assertThat(byEmail.how()).isEqualTo("e-mail");
            Match byPhone = idx.match(staged(3, "20003", "thomas", "EXAMPLE", null, "+1 913-555-0102", null, null, null));
            assertThat(byPhone.tier()).isEqualTo("STRONG"); assertThat(byPhone.liveId()).isEqualTo(2L); assertThat(byPhone.how()).isEqualTo("name + phone");
            Match byDob = idx.match(staged(4, "20004", "Lily", "Example", null, null, 2, 14, 2012));
            assertThat(byDob.tier()).isEqualTo("STRONG"); assertThat(byDob.liveId()).isEqualTo(3L);
            Match possible = idx.match(staged(5, "20005", "Sam", "Sampleton", "sam@new.org", null, null, null, null));
            assertThat(possible.tier()).isEqualTo("POSSIBLE"); assertThat(possible.how()).contains("2 members");
            assertThat(idx.match(staged(6, "20006", "Nobody", "New", null, null, null, null, null)).tier()).isEqualTo("NONE");

            // a different source system never matches on id; a file without real ids (null system) never does either
            assertThat(svc.familyIndex(TENANT, "CHMEETINGS").match(staged(7, "10001", "G", "X", null, null, null, null, null)).tier()).isEqualTo("NONE");
            assertThat(svc.familyIndex(TENANT, null).match(staged(8, "10001", "G", "X", null, null, null, null, null)).tier()).isEqualTo("NONE");
        }

        @Test
        @DisplayName("the same person twice in one upload is IN_FILE on the second row, pointing at the first")
        void inFile() {
            EtlMatchService.FamilyIndex idx = svc.familyIndex(TENANT, "BREEZE");
            assertThat(idx.match(staged(11, "30001", "New", "Person", "new@x.org", null, null, null, null)).tier()).isEqualTo("NONE");
            Match dup = idx.match(staged(12, "30002", "New", "Person", "NEW@x.org", null, null, null, null));
            assertThat(dup.tier()).isEqualTo("IN_FILE"); assertThat(dup.inFileRow()).isEqualTo(11L); assertThat(dup.how()).contains("row 11");
        }

        @Test
        @DisplayName("money tiers: import_ref → EXACT; donor+date+amount+fund → STRONG; date+amount+fund with donor unresolved → POSSIBLE; in-file by ref")
        void moneyTiers() {
            Income live = new Income(); live.setId(500); live.setAppClientId(TENANT); live.setIncomeDate(LocalDate.of(2026, 1, 4)); live.setAmount(new BigDecimal("1250.00"));
            live.setImportRef("ETL:CHMEETINGS:TX-9001"); SubSource ss = new SubSource(); ss.setSourceName("Tithe"); live.setSubSource(ss);
            FamilyMember donor = member(9, "G", "E", null, null, null, null, null, null, null); live.setMember(donor);
            when(incomeRepo.findByAppClientIdAndDeleteFlagFalseAndIncomeDateBetween(eq(TENANT), any(), any())).thenReturn(List.of(live));
            EtlMatchService.MoneyIndex idx = svc.moneyIndex(TENANT, "income", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

            StagingIncome a = new StagingIncome(); a.setId(1L); a.setImportRef("ETL:CHMEETINGS:TX-9001"); a.setIncomeDate(LocalDate.of(2026, 1, 4)); a.setAmount(new BigDecimal("1250")); a.setSourceName("Tithe");
            assertThat(idx.matchIncome(a, "m8").tier()).isEqualTo("EXACT");          // by reference, whoever the donor resolves to
            StagingIncome b = new StagingIncome(); b.setId(2L); b.setImportRef("ETL:X:fp:abc"); b.setIncomeDate(LocalDate.of(2026, 1, 4)); b.setAmount(new BigDecimal("1250.000")); b.setSourceName("tithe");
            Match strong = idx.matchIncome(b, "m9");
            assertThat(strong.tier()).isEqualTo("STRONG"); assertThat(strong.liveId()).isEqualTo(500L);
            StagingIncome c = new StagingIncome(); c.setId(3L); c.setImportRef("ETL:X:fp:def"); c.setIncomeDate(LocalDate.of(2026, 1, 4)); c.setAmount(new BigDecimal("1250")); c.setSourceName("Tithe");
            assertThat(idx.matchIncome(c, null).tier()).isEqualTo("POSSIBLE");
            StagingIncome d = new StagingIncome(); d.setId(4L); d.setImportRef("ETL:X:fp:abc"); d.setIncomeDate(LocalDate.of(2026, 1, 4)); d.setAmount(new BigDecimal("1250")); d.setSourceName("Tithe");
            Match inFile = idx.matchIncome(d, "m9");
            assertThat(inFile.tier()).isEqualTo("IN_FILE"); assertThat(inFile.inFileRow()).isEqualTo(2L);
            StagingIncome e = new StagingIncome(); e.setId(5L); e.setImportRef("ETL:X:fp:zzz"); e.setIncomeDate(LocalDate.of(2026, 1, 5)); e.setAmount(new BigDecimal("10")); e.setSourceName("Tithe");
            assertThat(idx.matchIncome(e, "m9").tier()).isEqualTo("NONE");
        }
    }

    @Nested
    @DisplayName("Validation → statuses, defaults and the REVIEW gate")
    class Validation {
        StagingFamilyRepository familyRepo = mock(StagingFamilyRepository.class);
        StagingIncomeRepository incomeRepo = mock(StagingIncomeRepository.class);
        StagingExpenseRepository expenseRepo = mock(StagingExpenseRepository.class);
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        IncomeRepository liveIncome = mock(IncomeRepository.class);
        ExpenseRepository liveExpense = mock(ExpenseRepository.class);
        ImportRunService runService = mock(ImportRunService.class);
        TransformStageService stage = mock(TransformStageService.class);
        EtlValidationService svc = new EtlValidationService(familyRepo, incomeRepo, expenseRepo, memberRepo, runService,
            new EtlMatchService(memberRepo, liveIncome, liveExpense), stage);

        @BeforeEach
        void setUp() {
            ImportRun run = new ImportRun(); run.setId(RUN); run.setClientId(TENANT); run.setSourcePreset("BREEZE"); run.setStatus("STAGED");
            when(runService.getRun(RUN, TENANT)).thenReturn(run);
            when(stage.hasSourceIdsForTarget(RUN, "family")).thenReturn(true);
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of(
                member(1, "Grace", "Example", "grace.example@example.org", null, null, null, null, "BREEZE", "10001"),
                member(4, "Sam", "Sampleton", null, null, null, null, null, null, null)));
            when(familyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(incomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("EXACT/STRONG → WARN + SKIP default; POSSIBLE → REVIEW with no default; a prior decision is kept; NONE → INSERT")
        void familyStatuses() {
            StagingFamily exact = staged(1, "10001", "Grace", "Example", null, null, null, null, null);
            StagingFamily strong = staged(2, "10002", "Grace", "Example", "grace.example@example.org", null, null, null, null);
            StagingFamily possible = staged(3, "10003", "Sam", "Sampleton", null, null, null, null, null);
            StagingFamily decided = staged(4, "10004", "Sam", "Sampleton", null, null, null, null, null); decided.setDedupeAction("INSERT");
            StagingFamily fresh = staged(5, "10005", "Ada", "New", null, null, null, null, null);
            StagingFamily dupInFile = staged(6, "10001", "Grace", "Example", null, null, null, null, null);
            when(familyRepo.findByRunId(RUN)).thenReturn(List.of(exact, strong, possible, decided, fresh, dupInFile));

            EtlValidationService.ValidationSummary sum = svc.validate(RUN, TENANT, "actor", "family");

            assertThat(exact.getRowStatus()).isEqualTo("WARN"); assertThat(exact.getMatchTier()).isEqualTo("EXACT"); assertThat(exact.getDedupeAction()).isEqualTo("SKIP"); assertThat(exact.getDedupeMatchId()).isEqualTo(1L);
            assertThat(strong.getRowStatus()).isEqualTo("WARN"); assertThat(strong.getMatchTier()).isEqualTo("STRONG"); assertThat(strong.getDedupeAction()).isEqualTo("SKIP");
            assertThat(possible.getRowStatus()).isEqualTo("REVIEW"); assertThat(possible.getMatchTier()).isEqualTo("POSSIBLE"); assertThat(possible.getDedupeAction()).isNull();
            assertThat(possible.getValidationMsgs()).contains("\"level\":\"REVIEW\"");
            assertThat(decided.getRowStatus()).isEqualTo("WARN"); assertThat(decided.getDedupeAction()).isEqualTo("INSERT");   // admin's earlier choice survives re-validation
            assertThat(fresh.getRowStatus()).isEqualTo("VALID"); assertThat(fresh.getMatchTier()).isEqualTo("NONE"); assertThat(fresh.getDedupeAction()).isEqualTo("INSERT");
            assertThat(dupInFile.getMatchTier()).isEqualTo("IN_FILE"); assertThat(dupInFile.getDedupeAction()).isEqualTo("SKIP"); assertThat(dupInFile.getMatchDetail()).contains("row 1");
            assertThat(sum.review()).isEqualTo(1); assertThat(sum.exact()).isEqualTo(1); assertThat(sum.strong()).isEqualTo(1);
            assertThat(sum.possible()).isEqualTo(2); assertThat(sum.inFile()).isEqualTo(1); assertThat(sum.newRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("income: the donor link resolves through the member's source id from the same product; same gift twice in the file is IN_FILE")
        void incomeLinkAndInFile() {
            when(liveIncome.findByAppClientIdAndDeleteFlagFalseAndIncomeDateBetween(eq(TENANT), any(), any())).thenReturn(List.of());
            when(familyRepo.findByRunId(RUN)).thenReturn(List.of());
            StagingIncome a = new StagingIncome(); a.setId(1L); a.setRunId(RUN); a.setClientId(TENANT); a.setAmount(new BigDecimal("50")); a.setIncomeDate(LocalDate.of(2026, 2, 1)); a.setFamilyLink("10001"); a.setSourceName("Tithe"); a.setImportRef("ETL:BREEZE:P1");
            StagingIncome b = new StagingIncome(); b.setId(2L); b.setRunId(RUN); b.setClientId(TENANT); b.setAmount(new BigDecimal("50")); b.setIncomeDate(LocalDate.of(2026, 2, 1)); b.setFamilyLink("10001"); b.setSourceName("Tithe"); b.setImportRef("ETL:BREEZE:P1");
            StagingIncome c = new StagingIncome(); c.setId(3L); c.setRunId(RUN); c.setClientId(TENANT); c.setAmount(new BigDecimal("50")); c.setIncomeDate(LocalDate.of(2026, 2, 1)); c.setFamilyLink("99999"); c.setSourceName("Tithe"); c.setImportRef("ETL:BREEZE:P3");
            when(incomeRepo.findByRunId(RUN)).thenReturn(List.of(a, b, c));

            svc.validate(RUN, TENANT, "actor", "income");
            assertThat(a.getRowStatus()).isEqualTo("VALID");                       // link resolved via source id → no warning
            assertThat(a.getValidationMsgs()).isNull();
            assertThat(b.getMatchTier()).isEqualTo("IN_FILE"); assertThat(b.getDedupeAction()).isEqualTo("SKIP");
            assertThat(c.getRowStatus()).isEqualTo("WARN"); assertThat(c.getValidationMsgs()).contains("Could not resolve family link '99999'");
        }
    }

    @Nested
    @DisplayName("EtlLoadService — decisions, gate, identity, import refs")
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
        EtlLoadService service;
        ImportRun run;

        @BeforeEach
        void setUp() {
            MainSourceRepository mainRepo = mock(MainSourceRepository.class); SubSourceRepository subRepo = mock(SubSourceRepository.class);
            when(mainRepo.save(any(MainSource.class))).thenAnswer(inv -> { MainSource m = inv.getArgument(0); m.setId(1); return m; });
            when(subRepo.save(any(SubSource.class))).thenAnswer(inv -> { SubSource x = inv.getArgument(0); x.setId(2); return x; });
            EtlReferenceResolver ref = new EtlReferenceResolver(mainRepo, subRepo, mock(PurposeRepository.class), mock(TransactionTypeRepository.class));
            service = new EtlLoadService(batchRepo, sFamilyRepo, sIncomeRepo, sExpenseRepo, familyRepo, memberRepo, incomeRepo, expenseRepo, ref, runService,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
            service.setStageService(stage);
            run = new ImportRun(); run.setId(RUN); run.setClientId(TENANT); run.setStatus("VALIDATED"); run.setSourcePreset("BREEZE");
            when(runService.getRun(RUN, TENANT)).thenReturn(run);
            when(stage.hasSourceIdsForTarget(RUN, "family")).thenReturn(true);
            when(sFamilyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sIncomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(batchRepo.save(any())).thenAnswer(inv -> { ImportBatch b = inv.getArgument(0); if (b.getId() == null) b.setId(5L); return b; });
            when(familyRepo.save(any(Family.class))).thenAnswer(inv -> { Family f = inv.getArgument(0); if (f.getId() == null) f.setId(300); return f; });
            when(memberRepo.save(any(FamilyMember.class))).thenAnswer(inv -> { FamilyMember m = inv.getArgument(0); if (m.getId() == null) m.setId(400); return m; });
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of());
            when(sFamilyRepo.findByRunId(RUN)).thenReturn(List.of());
            when(sFamilyRepo.findByRunIdAndRowStatus(eq(RUN), anyString())).thenReturn(List.of());
            when(sIncomeRepo.findByRunIdAndRowStatus(eq(RUN), anyString())).thenReturn(List.of());
            when(sExpenseRepo.findByRunIdAndRowStatus(eq(RUN), anyString())).thenReturn(List.of());
        }

        @Test
        @DisplayName("approval is blocked while any row is still REVIEW")
        void reviewGate() {
            StagingFamily pending = staged(1, "1", "Sam", "Sampleton", null, null, null, null, null); pending.setRowStatus("REVIEW");
            when(sFamilyRepo.findByRunIdAndRowStatus(RUN, "REVIEW")).thenReturn(List.of(pending));
            assertThatThrownBy(() -> service.createBatch(RUN, TENANT, "actor", "family"))
                .isInstanceOf(EtlLoadService.LoadException.class).hasMessageContaining("possible duplicates awaiting a decision");
        }

        @Test
        @DisplayName("decisions: MERGE only for people with a match; money UPDATE only from EXACT/STRONG; a decided REVIEW row becomes WARN")
        void decisionRules() {
            StagingFamily fam = staged(1, "1", "Sam", "Sampleton", null, null, null, null, null); fam.setRowStatus("REVIEW"); fam.setMatchTier("POSSIBLE"); fam.setDedupeMatchId(4L);
            when(sFamilyRepo.findById(1L)).thenReturn(Optional.of(fam));
            service.updateRow(TENANT, "family", 1L, "merge", null);
            assertThat(fam.getDedupeAction()).isEqualTo("MERGE"); assertThat(fam.getRowStatus()).isEqualTo("WARN");

            StagingFamily noMatch = staged(2, "2", "A", "B", null, null, null, null, null); noMatch.setRowStatus("VALID"); noMatch.setMatchTier("NONE");
            when(sFamilyRepo.findById(2L)).thenReturn(Optional.of(noMatch));
            assertThatThrownBy(() -> service.updateRow(TENANT, "family", 2L, "UPDATE", null)).hasMessageContaining("needs a matched existing record");

            StagingIncome inc = new StagingIncome(); inc.setId(3L); inc.setClientId(TENANT); inc.setRowStatus("REVIEW"); inc.setMatchTier("POSSIBLE"); inc.setDedupeMatchId(500L);
            when(sIncomeRepo.findById(3L)).thenReturn(Optional.of(inc));
            assertThatThrownBy(() -> service.updateRow(TENANT, "income", 3L, "MERGE", null)).hasMessageContaining("cannot be merged");
            assertThatThrownBy(() -> service.updateRow(TENANT, "income", 3L, "UPDATE", null)).hasMessageContaining("exact or strong match");
            service.updateRow(TENANT, "income", 3L, "SKIP", null);
            assertThat(inc.getDedupeAction()).isEqualTo("SKIP"); assertThat(inc.getRowStatus()).isEqualTo("WARN");
            inc.setMatchTier("STRONG"); inc.setRowStatus("WARN");
            service.updateRow(TENANT, "income", 3L, "UPDATE", null);
            assertThat(inc.getDedupeAction()).isEqualTo("UPDATE");

            StagingIncome other = new StagingIncome(); other.setId(4L); other.setClientId("CHR-other");
            when(sIncomeRepo.findById(4L)).thenReturn(Optional.of(other));
            assertThatThrownBy(() -> service.updateRow(TENANT, "income", 4L, "SKIP", null)).hasMessageContaining("not found for tenant");
        }

        @Test
        @DisplayName("load: MERGE fills only blank live fields; INSERT stamps source system + id on member and household")
        void mergeAndIdentity() {
            FamilyMember live = member(4, "Sam", "Sampleton", null, "9135550150", null, null, null, null, null); live.setCity("Olathe");
            when(memberRepo.findById(4)).thenReturn(Optional.of(live));
            StagingFamily merge = staged(1, "10004", "Samuel", "Sampleton", "sam.sampleton@example.org", "0000000000", 11, 5, 1990);
            merge.setRowStatus("APPROVED"); merge.setBatchId(5L); merge.setDedupeAction("MERGE"); merge.setDedupeMatchId(4L); merge.setCity("Overland Park");
            StagingFamily insert = staged(2, "10005", "Ada", "New", null, null, null, null, null); insert.setFamilyKey("F9");
            insert.setRowStatus("APPROVED"); insert.setBatchId(5L); insert.setDedupeAction("INSERT");
            ImportBatch b = new ImportBatch(); b.setId(5L); b.setRunId(RUN); b.setClientId(TENANT); b.setTargetTable("family"); b.setStatus("APPROVED");
            when(batchRepo.findByIdAndClientId(5L, TENANT)).thenReturn(Optional.of(b));
            when(batchRepo.findById(5L)).thenReturn(Optional.of(b));
            when(sFamilyRepo.findByBatchId(5L)).thenReturn(List.of(merge, insert));
            when(sFamilyRepo.findAllById(any())).thenReturn(List.of(merge, insert));

            ImportBatch out = service.loadBatch(5L, TENANT, "actor");
            assertThat(out.getUpdatedCount()).isEqualTo(1); assertThat(out.getInsertedCount()).isEqualTo(1);
            // merge: existing values kept, blanks filled
            assertThat(live.getFirstName()).isEqualTo("Sam"); assertThat(live.getPhone()).isEqualTo("9135550150"); assertThat(live.getCity()).isEqualTo("Olathe");
            assertThat(live.getEmail()).isEqualTo("sam.sampleton@example.org"); assertThat(live.getBirthdayYear()).isEqualTo(1990);
            assertThat(live.getSourceSystem()).isEqualTo("BREEZE"); assertThat(live.getSourceId()).isEqualTo("10004");   // learned identity
            assertThat(merge.getBeforeImage()).contains("\"email\":null");
            // insert: identity on member and household
            ArgumentCaptor<FamilyMember> mc = ArgumentCaptor.forClass(FamilyMember.class);
            verify(memberRepo, times(2)).save(mc.capture());
            FamilyMember created = mc.getAllValues().stream().filter(m -> "Ada".equals(m.getFirstName())).findFirst().orElseThrow();
            assertThat(created.getSourceSystem()).isEqualTo("BREEZE"); assertThat(created.getSourceId()).isEqualTo("10005");
            ArgumentCaptor<Family> fc = ArgumentCaptor.forClass(Family.class);
            verify(familyRepo).save(fc.capture());
            assertThat(fc.getValue().getSourceSystem()).isEqualTo("BREEZE"); assertThat(fc.getValue().getSourceId()).isEqualTo("F9");
        }

        @Test
        @DisplayName("income INSERT writes the import reference; 'import as new' on a live duplicate gets a distinct suffixed reference; links resolve by source id")
        void importRefs() {
            run.setStatus("APPROVED");
            FamilyMember donor = member(9, "Grace", "Example", null, null, null, null, null, "BREEZE", "10001");
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of(donor));
            when(memberRepo.findById(9)).thenReturn(Optional.of(donor));
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(TENANT, "ETL:BREEZE:P1")).thenReturn(Optional.of(new Income()));
            when(incomeRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(TENANT, "ETL:BREEZE:P2")).thenReturn(Optional.empty());
            when(incomeRepo.save(any(Income.class))).thenAnswer(inv -> { Income e = inv.getArgument(0); if (e.getId() == null) e.setId(900); return e; });
            StagingIncome a = new StagingIncome(); a.setId(1L); a.setRunId(RUN); a.setClientId(TENANT); a.setBatchId(5L); a.setRowStatus("APPROVED"); a.setDedupeAction("INSERT");
            a.setAmount(new BigDecimal("50")); a.setIncomeDate(LocalDate.of(2026, 2, 1)); a.setSourceName("Tithe"); a.setImportRef("ETL:BREEZE:P1"); a.setFamilyLink("10001");
            StagingIncome b = new StagingIncome(); b.setId(2L); b.setRunId(RUN); b.setClientId(TENANT); b.setBatchId(5L); b.setRowStatus("APPROVED");
            b.setAmount(new BigDecimal("60")); b.setIncomeDate(LocalDate.of(2026, 2, 2)); b.setSourceName("Tithe"); b.setImportRef("ETL:BREEZE:P2");
            ImportBatch batch = new ImportBatch(); batch.setId(5L); batch.setRunId(RUN); batch.setClientId(TENANT); batch.setTargetTable("income"); batch.setStatus("APPROVED");
            when(batchRepo.findByIdAndClientId(5L, TENANT)).thenReturn(Optional.of(batch));
            when(batchRepo.findById(5L)).thenReturn(Optional.of(batch));
            when(sIncomeRepo.findByBatchId(5L)).thenReturn(List.of(a, b));
            when(sIncomeRepo.findAllById(any())).thenReturn(List.of(a, b));

            service.loadBatch(5L, TENANT, "actor");
            ArgumentCaptor<Income> cap = ArgumentCaptor.forClass(Income.class);
            verify(incomeRepo, times(2)).save(cap.capture());
            Income ia = cap.getAllValues().get(0), ib = cap.getAllValues().get(1);
            assertThat(ia.getImportRef()).isEqualTo("ETL:BREEZE:P1:1");      // live duplicate → suffixed, still unique
            assertThat(ia.getMember()).isSameAs(donor);                       // "10001" resolved through the member's source id
            assertThat(ib.getImportRef()).isEqualTo("ETL:BREEZE:P2");
        }

        @Test
        @DisplayName("TransformStageService.importRef: source id when the file has ids, fingerprint otherwise")
        void refShapes() {
            assertThat(TransformStageService.importRef(SourcePreset.CHMEETINGS, true, "TX-9001", "x")).isEqualTo("ETL:CHMEETINGS:TX-9001");
            String fp1 = TransformStageService.importRef(SourcePreset.GENERIC, false, "1", "2026-01-04", "1250.00", "Tithe", null, "501", null, "1047");
            String fp2 = TransformStageService.importRef(SourcePreset.GENERIC, false, "2", "2026-01-04", "1250.00", "tithe ", null, "501", null, "1047");
            String fp3 = TransformStageService.importRef(SourcePreset.GENERIC, false, "3", "2026-01-04", "1250.01", "Tithe", null, "501", null, "1047");
            assertThat(fp1).startsWith("ETL:GENERIC:fp:").isEqualTo(fp2).isNotEqualTo(fp3);   // ordinal ignored, case/space-insensitive, amount-sensitive
        }
    }
}
