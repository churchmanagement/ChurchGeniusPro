package com.churchgeniuspro.etl;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.service.SourceExtractService.ExtractResult;
import com.churchgeniuspro.util.SourcePreset;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Migration Phase 1 through the services, with Mockito repositories: Excel / CSV
 * extraction with source ids, preset-aware mapping with the AI fallback kept
 * private and opt-in, staging with dictionaries and helper columns, the
 * missing-date rule, and the loader writing the fields that used to be dropped.
 * Fixtures are the sanitized samples under src/test/resources/etl.
 */
@DisplayName("ETL Phase 1 — services")
class EtlPhase1ServicesTest {

    private static final String TENANT = "CHR-test";
    private static final Long RUN = 42L;

    private static ImportRun run(SourcePreset preset, String dateOrder, boolean ai) {
        ImportRun r = new ImportRun();
        r.setId(RUN); r.setClientId(TENANT); r.setStatus("PROFILED");
        r.setSourcePreset(preset.name()); r.setDateOrder(dateOrder); r.setAiAssist(ai);
        return r;
    }

    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/etl/" + name));
    }

    // ───────────────────────── extract ─────────────────────────

    @Nested
    @DisplayName("SourceExtractService")
    class Extract {
        StagingRawRepository rawRepo = mock(StagingRawRepository.class);
        SourceExtractService svc = new SourceExtractService(rawRepo);

        private List<StagingRaw> saved() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<StagingRaw>> cap = ArgumentCaptor.forClass(List.class);
            verify(rawRepo).saveAll(cap.capture());
            return cap.getValue();
        }

        @Test
        @DisplayName("Breeze-shaped CSV: source ids come from 'Breeze ID', not the row number")
        void csvWithPresetIdColumn() throws Exception {
            byte[] bytes = fixture("breeze_people_sample.csv");
            ExtractResult r = svc.extract(run(SourcePreset.BREEZE, "MDY", false), "people", "csv", bytes, "family");
            assertThat(r.rowCount()).isEqualTo(4);
            assertThat(r.idColumn()).isEqualTo("Breeze ID");
            assertThat(r.columns()).contains("Family Role", "Age", "Mobile");
            assertThat(saved()).extracting(StagingRaw::getSourceRowId).containsExactly("10001", "10002", "10003", "10004");
            assertThat(saved().get(1).getPayload()).contains("thomas.example@example.org, tom@example.org");
        }

        @Test
        @DisplayName("generic preset: a literal 'Transaction Id' is the row id; a 'Person Id' never is (it is the donor link)")
        void genericRowIds() throws Exception {
            ExtractResult r = svc.extract(run(SourcePreset.GENERIC, "AUTO", false), "giving", "csv",
                fixture("chmeetings_contributions_sample.csv"), null);
            assertThat(r.idColumn()).isEqualTo("Transaction Id");
            assertThat(saved()).extracting(StagingRaw::getSourceRowId).containsExactly("TX-9001", "TX-9002", "TX-9003");

            reset(rawRepo);
            byte[] noIds = "Person Id,Amount,Date\n501,10,2026-01-01\n502,20,2026-01-02\n".getBytes(StandardCharsets.UTF_8);
            ExtractResult r2 = svc.extract(run(SourcePreset.GENERIC, "AUTO", false), "giving2", "csv", noIds, null);
            assertThat(r2.idColumn()).isNull();
            assertThat(saved()).extracting(StagingRaw::getSourceRowId).containsExactly("1", "2");   // legacy ordinals
        }

        @Test
        @DisplayName("semicolon-delimited, Windows-1252 CSV is sniffed and decoded")
        void semicolonAndCharset() throws Exception {
            String text = new String(fixture("generic_people_semicolon.csv"), StandardCharsets.UTF_8).replace("Geburtstag", "Geburtstag (Ü)");
            byte[] cp1252 = text.getBytes(Charset.forName("windows-1252"));
            ExtractResult r = svc.extract(run(SourcePreset.GENERIC, "DMY", false), "leute", "csv", cp1252, "family");
            assertThat(r.columns()).containsExactly("Name", "E-Mail", "Telefon", "Geburtstag (Ü)", "Rolle");
            assertThat(saved().get(0).getPayload()).contains("\"Name\":\"Example, Grace\"");
        }

        @Test
        @DisplayName("XLSX: dates as ISO, whole numbers without .0, formulas evaluated, first non-blank row is the header")
        void xlsx() throws Exception {
            byte[] bytes = workbook(new XSSFWorkbook());
            assertThat(SourceExtractService.detectFormat(null, "export.xlsx", bytes)).isEqualTo("xlsx");
            assertThat(SourceExtractService.detectFormat(null, "noext", bytes)).isEqualTo("xlsx");    // PK signature
            ExtractResult r = svc.extract(run(SourcePreset.CHMEETINGS, "AUTO", false), "giving", "xlsx", bytes, "income");
            assertThat(r.columns()).containsExactly("Transaction Id", "Amount", "Date", "Fund(s)");
            assertThat(r.idColumn()).isEqualTo("Transaction Id");
            List<StagingRaw> rows = saved();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getSourceRowId()).isEqualTo("501");
            assertThat(rows.get(0).getPayload()).contains("\"Amount\":\"1250\"").contains("\"Date\":\"2026-01-04\"");
            assertThat(rows.get(1).getPayload()).contains("\"Amount\":\"40.5\"").contains("\"Fund(s)\":\"Tithe\"");
        }

        @Test
        @DisplayName("legacy .xls (CHMeetings) reads through HSSF")
        void xls() throws Exception {
            byte[] bytes = workbook(new HSSFWorkbook());
            assertThat(SourceExtractService.detectFormat(null, "export.xls", bytes)).isEqualTo("xls");
            assertThat(SourceExtractService.detectFormat(null, "noext", bytes)).isEqualTo("xls");     // OLE2 signature
            ExtractResult r = svc.extract(run(SourcePreset.CHMEETINGS, "AUTO", false), "giving", "xls", bytes, "income");
            assertThat(r.rowCount()).isEqualTo(2);
            assertThat(saved().get(0).getPayload()).contains("\"Date\":\"2026-01-04\"");
        }

        @Test
        @DisplayName("hash is stable and content-based")
        void sha256() throws Exception {
            byte[] a = fixture("breeze_people_sample.csv");
            assertThat(SourceExtractService.sha256(a)).hasSize(64).isEqualTo(SourceExtractService.sha256(a.clone()));
            assertThat(SourceExtractService.sha256(a)).isNotEqualTo(SourceExtractService.sha256(fixture("chmeetings_contributions_sample.csv")));
        }

        private byte[] workbook(Workbook wb) throws Exception {
            Sheet sh = wb.createSheet("Contributions");
            sh.createRow(0);                                        // blank first row
            Row h = sh.createRow(1);
            h.createCell(0).setCellValue("Transaction Id"); h.createCell(1).setCellValue("Amount");
            h.createCell(2).setCellValue("Date"); h.createCell(3).setCellValue("Fund(s)");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("m/d/yyyy"));
            Row r1 = sh.createRow(2);
            r1.createCell(0).setCellValue(501); r1.createCell(1).setCellValue(1250.0);
            Cell d1 = r1.createCell(2); d1.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 1, 4))); d1.setCellStyle(dateStyle);
            r1.createCell(3).setCellValue("Building");
            Row r2 = sh.createRow(3);
            r2.createCell(0).setCellValue(502); r2.createCell(1).setCellFormula("40+0.5");
            Cell d2 = r2.createCell(2); d2.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 1, 11))); d2.setCellStyle(dateStyle);
            r2.createCell(3).setCellValue("Tithe");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out); wb.close();
            return out.toByteArray();
        }
    }

    // ───────────────────────── mapping ─────────────────────────

    @Nested
    @DisplayName("MappingService with presets")
    class Mapping {
        MappingRuleRepository ruleRepo = mock(MappingRuleRepository.class);
        SourceColumnProfileRepository profileRepo = mock(SourceColumnProfileRepository.class);
        OpenAiVoiceService openAi = mock(OpenAiVoiceService.class);
        ImportRunService runService = mock(ImportRunService.class);
        MappingService svc = new MappingService(ruleRepo, profileRepo, openAi, runService);

        private List<SourceColumnProfile> breezeProfile() {
            String[] cols = {"Breeze ID", "First Name", "Last Name", "Nickname", "Gender", "Status", "Family ID", "Family Role",
                             "Age", "Mobile", "Home", "Email", "Street Address", "City", "State", "Zip"};
            List<SourceColumnProfile> out = new ArrayList<>();
            int i = 0;
            for (String c : cols) {
                SourceColumnProfile p = new SourceColumnProfile();
                p.setRunId(RUN); p.setClientId(TENANT); p.setSourceTable("people"); p.setColumnName(c); p.setOrdinal(i++);
                p.setInferredType(c.equals("Age") ? "DATE" : c.equals("Email") ? "EMAIL" : c.equals("Breeze ID") ? "INTEGER" : "STRING");
                p.setSampleValues("[\"grace.example@example.org\",\"913-555-0101\"]");
                out.add(p);
            }
            return out;
        }

        @BeforeEach
        void setUp() {
            when(profileRepo.findByRunIdAndSourceTableOrderByOrdinalAsc(RUN, "people")).thenReturn(breezeProfile());
            when(ruleRepo.findByRunIdAndTargetTableAndTargetColumn(eq(RUN), anyString(), anyString())).thenReturn(Optional.empty());
            when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(openAi.isEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("Breeze headers map at 1.0 from the preset; the generic matcher fills the rest; AI is not called without opt-in")
        void presetPass() {
            when(runService.getRun(RUN, TENANT)).thenReturn(run(SourcePreset.BREEZE, "MDY", false));
            List<MappingRule> rules = svc.suggest(RUN, TENANT, "actor", "family", "people");
            Map<String, MappingRule> by = new HashMap<>();
            for (MappingRule r : rules) by.put(r.getTargetColumn(), r);

            for (String[] exp : new String[][]{{"firstName", "First Name"}, {"lastName", "Last Name"}, {"role", "Family Role"},
                                                {"birthDate", "Age"}, {"phone", "Mobile"}, {"pinCode", "Zip"}, {"memberType", "Status"}}) {
                MappingRule r = by.get(exp[0]);
                assertThat(r.getSourceColumn()).as(exp[0]).isEqualTo(exp[1]);
                assertThat(r.getStatus()).as(exp[0]).isEqualTo("AUTO");
                assertThat(r.getConfidence()).as(exp[0]).isEqualByComparingTo("1.000");
                assertThat(r.getRationale()).contains("Breeze ChMS preset");
            }
            assertThat(by.get("birthDate").getTransform()).isEqualTo("parse_date");
            assertThat(by.get("email").getSourceColumn()).isEqualTo("Email");
            // "Home" (second phone) is deliberately not a preset alias and must not be stolen for phone.
            assertThat(by.get("phone").getSourceColumn()).isEqualTo("Mobile");
            verify(openAi, never()).chatJson(anyString(), anyString());
        }

        @Test
        @DisplayName("AI fallback runs only when the run opted in, and the prompt carries no sample values")
        void aiOptInAndPrivacy() {
            when(runService.getRun(RUN, TENANT)).thenReturn(run(SourcePreset.GENERIC, "AUTO", true));
            when(openAi.chatJson(anyString(), anyString())).thenReturn(null);
            svc.suggest(RUN, TENANT, "actor", "income", "people");    // people columns against income → plenty unmatched
            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(openAi).chatJson(anyString(), prompt.capture());
            assertThat(prompt.getValue()).contains("Breeze ID : INTEGER")
                .doesNotContain("grace.example@example.org").doesNotContain("913-555-0101").doesNotContain("sampleValues");
        }

        @Test
        @DisplayName("review lists unmapped required columns and unused source columns")
        void review() {
            when(runService.getRun(RUN, TENANT)).thenReturn(run(SourcePreset.BREEZE, "MDY", false));
            MappingRule amount = new MappingRule();
            amount.setRunId(RUN); amount.setTargetTable("income"); amount.setTargetColumn("amount");
            amount.setSourceTable("people"); amount.setSourceColumn("Zip"); amount.setStatus("CONFIRMED");
            when(ruleRepo.findByRunIdAndTargetTable(RUN, "income")).thenReturn(List.of(amount));

            Map<String, Object> rv = svc.review(RUN, TENANT, "income", "people");
            assertThat(rv.get("missingRequired")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).containsExactly("incomeDate");
            @SuppressWarnings("unchecked") List<String> unmapped = (List<String>) rv.get("unmappedSources");
            assertThat(unmapped).contains("First Name", "Mobile").doesNotContain("Zip");
            assertThat(rv.get("ready")).isEqualTo(false);
            assertThat(rv.get("presetVerified")).isEqualTo(false);
        }
    }

    // ───────────────────────── stage + validate ─────────────────────────

    @Nested
    @DisplayName("TransformStageService + EtlValidationService")
    class Stage {
        MappingRuleRepository ruleRepo = mock(MappingRuleRepository.class);
        StagingRawRepository rawRepo = mock(StagingRawRepository.class);
        StagingFamilyRepository familyRepo = mock(StagingFamilyRepository.class);
        StagingIncomeRepository incomeRepo = mock(StagingIncomeRepository.class);
        StagingExpenseRepository expenseRepo = mock(StagingExpenseRepository.class);
        ImportRunService runService = mock(ImportRunService.class);
        ImportValueMapRepository valueMapRepo = mock(ImportValueMapRepository.class);
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        ImportFileRepository fileRepo = mock(ImportFileRepository.class);
        TransformStageService stage = new TransformStageService(ruleRepo, rawRepo, familyRepo, incomeRepo, expenseRepo, runService, valueMapRepo, fileRepo);
        EtlValidationService validate = new EtlValidationService(familyRepo, incomeRepo, expenseRepo, memberRepo, runService);

        private MappingRule rule(String table, String target, String source, String transform) {
            MappingRule r = new MappingRule();
            r.setRunId(RUN); r.setClientId(TENANT); r.setTargetTable(table); r.setTargetColumn(target);
            r.setSourceTable("src"); r.setSourceColumn(source); r.setTransform(transform); r.setStatus("CONFIRMED");
            return r;
        }
        private StagingRaw raw(String id, String json) {
            StagingRaw r = new StagingRaw(); r.setRunId(RUN); r.setClientId(TENANT); r.setSourceTable("src"); r.setSourceRowId(id); r.setPayload(json);
            return r;
        }

        @Test
        @DisplayName("fullName is split, birthDate fills month/day/year, dictionaries translate role and gender, DMY order honoured")
        void familyStaging() {
            when(runService.getRun(RUN, TENANT)).thenReturn(run(SourcePreset.GENERIC, "DMY", false));
            when(ruleRepo.findByRunIdAndTargetTable(RUN, "family")).thenReturn(List.of(
                rule("family", "fullName", "Name", "trim"), rule("family", "birthDate", "Geburtstag", "parse_date"),
                rule("family", "role", "Rolle", "trim"), rule("family", "gender", "Sex", "trim"), rule("family", "email", "E-Mail", "trim")));
            when(rawRepo.findByRunIdAndSourceTable(RUN, "src")).thenReturn(List.of(
                raw("1", "{\"Name\":\"Example, Grace\",\"Geburtstag\":\"12/04/1978\",\"Rolle\":\"HoH\",\"Sex\":\"f\",\"E-Mail\":\"g@x.org\"}")));
            ImportValueMap m1 = new ImportValueMap(); m1.setDictionary("role"); m1.setSourceValue("HoH"); m1.setTargetValue("Head of Household");
            ImportValueMap m2 = new ImportValueMap(); m2.setDictionary("gender"); m2.setSourceValue("F"); m2.setTargetValue("Female");
            when(valueMapRepo.findByRunIdOrderByDictionaryAscSourceValueAsc(RUN)).thenReturn(List.of(m1, m2));

            stage.stage(RUN, TENANT, "actor", "family");

            ArgumentCaptor<StagingFamily> cap = ArgumentCaptor.forClass(StagingFamily.class);
            verify(familyRepo).save(cap.capture());
            StagingFamily f = cap.getValue();
            assertThat(f.getFirstName()).isEqualTo("Grace");
            assertThat(f.getLastName()).isEqualTo("Example");
            assertThat(f.getBirthdayDay()).isEqualTo(12); assertThat(f.getBirthdayMonth()).isEqualTo(4); assertThat(f.getBirthdayYear()).isEqualTo(1978);
            assertThat(f.getRole()).isEqualTo("Head of Household");
            assertThat(f.getGender()).isEqualTo("Female");
            assertThat(f.getRowStatus()).isEqualTo("PENDING");
            assertThat(f.getFamilyKey()).isEqualTo("1");
        }

        @Test
        @DisplayName("preset dictionary applies when the admin has no entry; an unknown value passes through unchanged")
        void presetDictionary() {
            when(runService.getRun(RUN, TENANT)).thenReturn(run(SourcePreset.CHMEETINGS, "AUTO", false));
            when(ruleRepo.findByRunIdAndTargetTable(RUN, "income")).thenReturn(List.of(
                rule("income", "amount", "Amount", "parse_amount"), rule("income", "incomeDate", "Date", "parse_date"),
                rule("income", "method", "Payment Method", "trim"), rule("income", "sourceName", "Fund(s)", "trim"),
                rule("income", "guestName", "Name", "trim")));
            when(rawRepo.findByRunIdAndSourceTable(RUN, "src")).thenReturn(List.of(
                raw("501", "{\"Amount\":\"$1,250.00\",\"Date\":\"2026-01-04\",\"Payment Method\":\"wire transfer\",\"Fund(s)\":\"Tithe\",\"Name\":\"Grace Example\"}"),
                raw("502", "{\"Amount\":\"40\",\"Date\":\"\",\"Payment Method\":\"Zelle\",\"Fund(s)\":\"Tithe\",\"Name\":\"\"}")));
            when(valueMapRepo.findByRunIdOrderByDictionaryAscSourceValueAsc(RUN)).thenReturn(List.of());

            stage.stage(RUN, TENANT, "actor", "income");
            ArgumentCaptor<StagingIncome> cap = ArgumentCaptor.forClass(StagingIncome.class);
            verify(incomeRepo, times(2)).save(cap.capture());
            StagingIncome a = cap.getAllValues().get(0), b = cap.getAllValues().get(1);
            assertThat(a.getAmount()).isEqualByComparingTo("1250.00");
            assertThat(a.getMethod()).isEqualTo("Wire Transfer");        // preset dictionary
            assertThat(a.getGuestName()).isEqualTo("Grace Example");
            assertThat(b.getMethod()).isEqualTo("Zelle");                // not in the dictionary → unchanged
            assertThat(b.getIncomeDate()).isNull();

            // validation: the missing date is now an ERROR, not a WARN
            when(incomeRepo.findByRunId(RUN)).thenReturn(List.of(a, b));
            when(familyRepo.findByRunId(RUN)).thenReturn(List.of());
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of());
            when(incomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            EtlValidationService.ValidationSummary sum = validate.validate(RUN, TENANT, "actor", "income");
            assertThat(sum.error()).isEqualTo(1);
            assertThat(sum.valid()).isEqualTo(1);
            assertThat(b.getRowStatus()).isEqualTo("ERROR");
            assertThat(b.getValidationMsgs()).contains("Date is required");
        }
    }

    // ───────────────────────── load ─────────────────────────

    @Nested
    @DisplayName("EtlLoadService — fields that used to be dropped")
    class Load {
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        StagingFamilyRepository sFamilyRepo = mock(StagingFamilyRepository.class);
        StagingIncomeRepository sIncomeRepo = mock(StagingIncomeRepository.class);
        StagingExpenseRepository sExpenseRepo = mock(StagingExpenseRepository.class);
        FamilyRepository familyRepo = mock(FamilyRepository.class);
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        IncomeRepository incomeRepo = mock(IncomeRepository.class);
        ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        MainSourceRepository mainSourceRepo = mock(MainSourceRepository.class);
        SubSourceRepository subSourceRepo = mock(SubSourceRepository.class);
        PurposeRepository purposeRepo = mock(PurposeRepository.class);
        TransactionTypeRepository txTypeRepo = mock(TransactionTypeRepository.class);
        ImportRunService runService = mock(ImportRunService.class);
        org.springframework.transaction.PlatformTransactionManager txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        EtlLoadService service;
        TransactionType existingCheck;

        @BeforeEach
        void setUp() {
            EtlReferenceResolver ref = new EtlReferenceResolver(mainSourceRepo, subSourceRepo, purposeRepo, txTypeRepo);
            service = new EtlLoadService(batchRepo, sFamilyRepo, sIncomeRepo, sExpenseRepo, familyRepo, memberRepo,
                incomeRepo, expenseRepo, ref, runService, txManager);
            existingCheck = new TransactionType(); existingCheck.setId(7); existingCheck.setTypeName("Check"); existingCheck.setAppClientId(TENANT);
            when(txTypeRepo.findActiveByAppUser(TENANT)).thenReturn(List.of(existingCheck));
            AtomicLong ids = new AtomicLong(100);
            when(txTypeRepo.save(any(TransactionType.class))).thenAnswer(inv -> { TransactionType t = inv.getArgument(0); if (t.getId() == null) t.setId((int) ids.getAndIncrement()); return t; });
            when(mainSourceRepo.findActiveByAppUser(TENANT)).thenReturn(List.of());
            when(subSourceRepo.findAllActiveByAppUser(TENANT)).thenReturn(List.of());
            when(purposeRepo.findActiveByAppUser(TENANT)).thenReturn(List.of());
            when(mainSourceRepo.save(any(MainSource.class))).thenAnswer(inv -> { MainSource m = inv.getArgument(0); m.setId(1); return m; });
            when(subSourceRepo.save(any(SubSource.class))).thenAnswer(inv -> { SubSource s = inv.getArgument(0); s.setId(2); return s; });
            when(purposeRepo.save(any(Purpose.class))).thenAnswer(inv -> { Purpose p = inv.getArgument(0); p.setId(3); return p; });
            when(sFamilyRepo.findByRunId(RUN)).thenReturn(List.of());
            when(memberRepo.findActiveByTenantForEtl(TENANT)).thenReturn(List.of());
            when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sIncomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sExpenseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(incomeRepo.save(any(Income.class))).thenAnswer(inv -> { Income e = inv.getArgument(0); if (e.getId() == null) e.setId(900); return e; });
            when(expenseRepo.save(any(Expense.class))).thenAnswer(inv -> { Expense e = inv.getArgument(0); if (e.getId() == null) e.setId(950); return e; });
            ImportRun run = run(SourcePreset.GENERIC, "AUTO", false); run.setStatus("APPROVED");
            when(runService.getRun(RUN, TENANT)).thenReturn(run);
        }

        private ImportBatch batch(String table) {
            ImportBatch b = new ImportBatch(); b.setId(5L); b.setRunId(RUN); b.setClientId(TENANT); b.setTargetTable(table); b.setStatus("APPROVED");
            when(batchRepo.findByIdAndClientId(5L, TENANT)).thenReturn(Optional.of(b));
            when(batchRepo.findById(5L)).thenReturn(Optional.of(b));
            return b;
        }

        @Test
        @DisplayName("income: payment method resolves to the tenant's transaction type (existing or created once); unresolved donor becomes guest name")
        void incomeMethodAndGuest() {
            batch("income");
            StagingIncome a = new StagingIncome(); a.setId(1L); a.setRunId(RUN); a.setClientId(TENANT); a.setBatchId(5L); a.setRowStatus("APPROVED");
            a.setAmount(new BigDecimal("1250.00")); a.setIncomeDate(LocalDate.of(2026, 1, 4)); a.setSourceName("Tithe"); a.setMethod("check"); a.setGuestName("Grace Example");
            StagingIncome b = new StagingIncome(); b.setId(2L); b.setRunId(RUN); b.setClientId(TENANT); b.setBatchId(5L); b.setRowStatus("APPROVED");
            b.setAmount(new BigDecimal("40")); b.setIncomeDate(LocalDate.of(2026, 1, 11)); b.setSourceName("Tithe"); b.setMethod("Zelle");
            StagingIncome c = new StagingIncome(); c.setId(3L); c.setRunId(RUN); c.setClientId(TENANT); c.setBatchId(5L); c.setRowStatus("APPROVED");
            c.setAmount(new BigDecimal("10")); c.setIncomeDate(LocalDate.of(2026, 1, 12)); c.setSourceName("Tithe"); c.setMethod("zelle");
            when(sIncomeRepo.findByBatchId(5L)).thenReturn(List.of(a, b, c));
            when(sIncomeRepo.findAllById(any())).thenReturn(List.of(a, b, c));

            ImportBatch out = service.loadBatch(5L, TENANT, "actor");
            assertThat(out.getInsertedCount()).isEqualTo(3);
            ArgumentCaptor<Income> cap = ArgumentCaptor.forClass(Income.class);
            verify(incomeRepo, times(3)).save(cap.capture());
            Income ia = cap.getAllValues().get(0), ib = cap.getAllValues().get(1), ic = cap.getAllValues().get(2);
            assertThat(ia.getTransactionType()).isSameAs(existingCheck);                 // case-insensitive hit on "Check"
            assertThat(ia.getGuestName()).isEqualTo("Grace Example");
            assertThat(ib.getTransactionType().getTypeName()).isEqualTo("Zelle");
            assertThat(ic.getTransactionType()).isSameAs(ib.getTransactionType());        // created once, reused
            verify(txTypeRepo, times(1)).save(any(TransactionType.class));
            assertThat(a.getBeforeImage()).isNull();                                      // INSERT: no before-image
        }

        @Test
        @DisplayName("expense: payee is kept at the head of the note; method resolves; UPDATE before-image records the old type and restores it")
        void expensePayeeMethodAndRestore() {
            batch("expense");
            Expense existing = new Expense(); existing.setId(77); existing.setAppClientId(TENANT); existing.setAmount(new BigDecimal("5"));
            existing.setExpenseDate(LocalDate.of(2026, 1, 1)); existing.setNote("old note"); existing.setTransactionType(existingCheck); existing.setDeleteFlag(false);
            when(expenseRepo.findById(77)).thenReturn(Optional.of(existing));
            when(txTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, TENANT)).thenReturn(Optional.of(existingCheck));

            StagingExpense upd = new StagingExpense(); upd.setId(1L); upd.setRunId(RUN); upd.setClientId(TENANT); upd.setBatchId(5L); upd.setRowStatus("APPROVED");
            upd.setDedupeAction("UPDATE"); upd.setDedupeMatchId(77L);
            upd.setAmount(new BigDecimal("99.00")); upd.setExpenseDate(LocalDate.of(2026, 2, 2)); upd.setCategory("Utilities");
            upd.setPayee("Acme Power"); upd.setNotes("Feb bill"); upd.setMethod("Card");
            when(sExpenseRepo.findByBatchId(5L)).thenReturn(List.of(upd));
            when(sExpenseRepo.findAllById(any())).thenReturn(List.of(upd));

            ImportBatch out = service.loadBatch(5L, TENANT, "actor");
            assertThat(out.getUpdatedCount()).isEqualTo(1);
            assertThat(existing.getNote()).isEqualTo("Payee: Acme Power\nFeb bill");
            assertThat(existing.getTransactionType().getTypeName()).isEqualTo("Card");
            assertThat(upd.getBeforeImage()).contains("\"transactionTypeId\":7").contains("old note");

            // rollback restores the old note and the old payment type
            ImportBatch loaded = batch("expense"); loaded.setStatus("LOADED"); upd.setRowStatus("LOADED"); upd.setTargetId(77L);
            when(sExpenseRepo.findByBatchId(5L)).thenReturn(List.of(upd));
            service.rollbackBatch(5L, TENANT, "actor");
            assertThat(existing.getNote()).isEqualTo("old note");
            assertThat(existing.getTransactionType()).isSameAs(existingCheck);
            assertThat(existing.getAmount()).isEqualByComparingTo("5");
            assertThat(existing.isDeleteFlag()).isFalse();
        }
    }
}
