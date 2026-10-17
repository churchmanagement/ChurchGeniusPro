package com.churchgeniuspro.etl;

import com.churchgeniuspro.controller.ImportEtlController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.service.SourceExtractService.ExtractResult;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.MappingMatcher;
import com.churchgeniuspro.util.Transforms;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Migration Phase 4 — the pipeline around the new data types: schema, transforms, mapping
 * fit, id-column detection, record staging, dependency order in the loader, the run plan,
 * the controller's record views, and the V21 migration's fresh-database guard.
 */
@DisplayName("ETL Phase 4 — pipeline: schema, staging, ordering, views, migration")
class EtlPhase4PipelineTest {

    static final String TENANT = "CHR-test";
    static final Long RUN = 92L;

    @Nested
    @DisplayName("schema, transforms, mapping fit")
    class SchemaAndTransforms {
        @Test
        @DisplayName("nine record tables, each with required columns, labels and row-id headers; family/income/expense unchanged and not record tables")
        void schema() {
            assertThat(EtlTargetSchema.RECORD_TABLES).containsExactly("pledge_campaign", "pledge", "event", "event_registration", "attendance",
                "group", "group_member", "km_child", "km_checkin");
            assertThat(EtlTargetSchema.tables()).startsWith("family", "income", "expense").containsAll(EtlTargetSchema.RECORD_TABLES);
            for (String t : List.of("family", "income", "expense")) assertThat(EtlTargetSchema.isRecordTable(t)).isFalse();
            assertThat(EtlTargetSchema.required("pledge")).extracting(EtlTargetSchema.TargetColumn::name).containsExactly("pledgeAmount");
            assertThat(EtlTargetSchema.required("event")).extracting(EtlTargetSchema.TargetColumn::name).containsExactly("eventName", "eventDate");
            assertThat(EtlTargetSchema.required("km_checkin")).extracting(EtlTargetSchema.TargetColumn::name).containsExactly("checkinTime");
            assertThat(EtlTargetSchema.column("attendance", "status").dictionary()).isEqualTo("attendanceStatus");
            assertThat(EtlTargetSchema.DICTIONARIES).contains("attendanceStatus");
            assertThat(EtlTargetSchema.rowIdColumns("event_registration")).startsWith("registrationid").doesNotContain("eventid");
            assertThat(EtlTargetSchema.suggestedTransform("DATETIME")).isEqualTo("parse_datetime");
            assertThat(EtlTargetSchema.suggestedTransform("TIME")).isEqualTo("parse_time");
            for (String t : EtlTargetSchema.tables()) assertThat(EtlTargetSchema.LABELS).containsKey(t);
        }

        @Test
        @DisplayName("parse_time → HH:mm (12/24-hour, 7pm); parse_datetime honours the run's date order and drops a zone")
        void transforms() {
            assertThat(Transforms.parseTime("9:30")).isEqualTo("09:30");
            assertThat(Transforms.parseTime("11:00 AM")).isEqualTo("11:00");
            assertThat(Transforms.parseTime("12:15 am")).isEqualTo("00:15");
            assertThat(Transforms.parseTime("7pm")).isEqualTo("19:00");
            assertThat(Transforms.parseTime("19:05:59")).isEqualTo("19:05");
            assertThatThrownBy(() -> Transforms.parseTime("25:00")).isInstanceOf(Transforms.TransformException.class);
            assertThatThrownBy(() -> Transforms.parseTime("9")).isInstanceOf(Transforms.TransformException.class);
            Transforms.Context dmy = Transforms.Context.of("DMY");
            assertThat(Transforms.parseDateTime("2026-03-01 09:45", null)).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 45));
            assertThat(Transforms.parseDateTime("03/08/2026 9:50 AM", Transforms.Context.of("MDY"))).isEqualTo(LocalDateTime.of(2026, 3, 8, 9, 50));
            assertThat(Transforms.parseDateTime("03/08/2026 9:50 PM", dmy)).isEqualTo(LocalDateTime.of(2026, 8, 3, 21, 50));
            assertThat(Transforms.parseDateTime("2026-03-01T09:45:30Z", null)).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 45, 30));
            assertThat(Transforms.parseDateTime("2026-03-01", null)).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
            assertThat(Transforms.apply("parse_time", "2:00 PM", null)).isEqualTo("14:00");
        }

        @Test
        @DisplayName("mapping: a 'Check In' timestamp column maps AUTO to checkinTime; 'Mobile Phone' maps AUTO to a person's phone")
        void mappingFit() {
            List<MappingMatcher.Suggestion> s = MappingMatcher.match("km_checkin", List.of(new MappingMatcher.SourceCol("Check In", "STRING"),
                new MappingMatcher.SourceCol("Child ID", "STRING")), Set.of());
            MappingMatcher.Suggestion in = s.stream().filter(x -> "checkinTime".equals(x.targetColumn)).findFirst().orElseThrow();
            assertThat(in.sourceColumn).isEqualTo("Check In"); assertThat(in.status).isEqualTo("AUTO");
            MappingMatcher.Suggestion ph = MappingMatcher.match("family", List.of(new MappingMatcher.SourceCol("Mobile Phone", "PHONE")), Set.of())
                .stream().filter(x -> "phone".equals(x.targetColumn)).findFirst().orElseThrow();
            assertThat(ph.status).isEqualTo("AUTO");
        }
    }

    @Nested
    @DisplayName("extract + stage")
    class ExtractAndStage {
        StagingRawRepository rawRepo = mock(StagingRawRepository.class);
        SourceExtractService extract = new SourceExtractService(rawRepo);

        private ImportRun run() { ImportRun r = new ImportRun(); r.setId(RUN); r.setClientId(TENANT); r.setSourcePreset("GENERIC"); r.setStatus("PROFILED"); return r; }
        private List<StagingRaw> saved() {
            @SuppressWarnings("unchecked") ArgumentCaptor<List<StagingRaw>> cap = ArgumentCaptor.forClass(List.class);
            verify(rawRepo).saveAll(cap.capture()); return cap.getValue();
        }

        @Test
        @DisplayName("row ids: the type's own id header when the admin names the type; a registration file's Event ID is a link; a people file's Person Id is the person's id")
        void rowIds() throws Exception {
            ExtractResult r = extract.extract(run(), "regs", "csv", Files.readAllBytes(Path.of("src/test/resources/etl/p4_registrations.csv")), "event_registration");
            assertThat(r.idColumn()).isEqualTo("Registration ID");
            assertThat(saved()).extracting(StagingRaw::getSourceRowId).containsExactly("R-1", "R-2", "R-3");
            reset(rawRepo);
            byte[] noOwnId = "Event ID,First Name\nE-1,Ann\n".getBytes(StandardCharsets.UTF_8);
            assertThat(extract.extract(run(), "regs2", "csv", noOwnId, "event_registration").idColumn()).isNull();   // never the event's id
            reset(rawRepo);
            assertThat(extract.extract(run(), "people", "csv", Files.readAllBytes(Path.of("src/test/resources/etl/p4_people.csv")), "family").idColumn()).isEqualTo("Person Id");
            reset(rawRepo);
            byte[] giving = "Person Id,Amount,Date\n501,10,2026-01-01\n".getBytes(StandardCharsets.UTF_8);
            assertThat(extract.extract(run(), "giving", "csv", giving, null).idColumn()).isNull();                    // Phase 1 rule unchanged
        }

        @Test
        @DisplayName("staging a record table writes staging_record rows with typed JSON (money as strings, ISO dates/times), fullName split, dictionaries applied, transform errors kept")
        void stage() {
            MappingRuleRepository ruleRepo = mock(MappingRuleRepository.class);
            StagingRecordRepository recordRepo = mock(StagingRecordRepository.class);
            ImportRunService runService = mock(ImportRunService.class);
            ImportValueMapRepository valueMapRepo = mock(ImportValueMapRepository.class);
            TransformStageService stage = new TransformStageService(ruleRepo, rawRepo, mock(StagingFamilyRepository.class), mock(StagingIncomeRepository.class),
                mock(StagingExpenseRepository.class), runService, valueMapRepo, mock(ImportFileRepository.class));
            stage.setRecordRepo(recordRepo);
            when(runService.getRun(RUN, TENANT)).thenReturn(run());
            ImportValueMap vm = new ImportValueMap(); vm.setDictionary("attendanceStatus"); vm.setSourceValue("here"); vm.setTargetValue("PRESENT");
            when(valueMapRepo.findByRunIdOrderByDictionaryAscSourceValueAsc(RUN)).thenReturn(List.of(vm));
            List<MappingRule> rules = List.of(rule("attendanceDate", "Date", "parse_date"), rule("fullName", "Name", "trim"),
                rule("status", "Status", "trim"), rule("checkInTime", "In", "parse_datetime"), rule("serviceType", "Service", "trim"));
            when(ruleRepo.findByRunIdAndTargetTable(RUN, "attendance")).thenReturn(rules);
            StagingRaw ok = new StagingRaw(); ok.setRunId(RUN); ok.setSourceTable("src"); ok.setSourceRowId("A-1");
            ok.setPayload("{\"Date\":\"3/1/2026\",\"Name\":\"Okafor, Grace\",\"Status\":\"here\",\"In\":\"2026-03-01 09:55\",\"Service\":\"Sunday\"}");
            StagingRaw bad = new StagingRaw(); bad.setRunId(RUN); bad.setSourceTable("src"); bad.setSourceRowId("A-2");
            bad.setPayload("{\"Date\":\"not a date\",\"Name\":\"Sam Lee\"}");
            when(rawRepo.findByRunIdAndSourceTable(RUN, "src")).thenReturn(List.of(ok, bad));

            TransformStageService.StageResult res = stage.stage(RUN, TENANT, "actor", "attendance");

            assertThat(res.total()).isEqualTo(2); assertThat(res.withErrors()).isEqualTo(1);
            verify(recordRepo).deleteByRunIdAndTargetTable(RUN, "attendance");
            ArgumentCaptor<StagingRecord> cap = ArgumentCaptor.forClass(StagingRecord.class);
            verify(recordRepo, times(2)).save(cap.capture());
            StagingRecord a = cap.getAllValues().get(0), b = cap.getAllValues().get(1);
            assertThat(a.getTargetTable()).isEqualTo("attendance"); assertThat(a.getClientId()).isEqualTo(TENANT); assertThat(a.getSourceRowId()).isEqualTo("A-1");
            assertThat(a.getValuesJson()).contains("\"attendanceDate\":\"2026-03-01\"").contains("\"firstName\":\"Grace\"").contains("\"lastName\":\"Okafor\"")
                .contains("\"status\":\"PRESENT\"").contains("\"checkInTime\":\"2026-03-01T09:55\"");
            assertThat(a.getRowStatus()).isEqualTo("PENDING");
            assertThat(b.getRowStatus()).isEqualTo("ERROR"); assertThat(b.getValidationMsgs()).contains("Not a recognizable date");
        }

        private MappingRule rule(String target, String source, String transform) {
            MappingRule r = new MappingRule(); r.setRunId(RUN); r.setClientId(TENANT); r.setTargetTable("attendance"); r.setTargetColumn(target);
            r.setSourceTable("src"); r.setSourceColumn(source); r.setTransform(transform); r.setStatus("AUTO"); return r;
        }
    }

    @Nested
    @DisplayName("loader: order, dependencies, plan")
    class Loader {
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        EtlRecordService records = mock(EtlRecordService.class);
        ImportRunService runService = mock(ImportRunService.class);
        StagingFamilyRepository fam = mock(StagingFamilyRepository.class);
        StagingIncomeRepository inc = mock(StagingIncomeRepository.class);
        StagingExpenseRepository exp = mock(StagingExpenseRepository.class);
        EtlLoadService svc = new EtlLoadService(batchRepo, fam, inc, exp, mock(FamilyRepository.class), mock(FamilyMemberRepository.class),
            mock(IncomeRepository.class), mock(ExpenseRepository.class), mock(EtlReferenceResolver.class), runService,
            mock(org.springframework.transaction.PlatformTransactionManager.class));
        List<ImportBatch> batches = new ArrayList<>();
        ImportRun run = new ImportRun();

        Loader() {
            svc.setRecordService(records);
            run.setId(RUN); run.setClientId(TENANT); run.setStatus("APPROVED"); run.setSourcePreset("GENERIC");
            when(runService.getRun(RUN, TENANT)).thenReturn(run);
            when(batchRepo.findByRunIdAndTargetTable(eq(RUN), anyString())).thenAnswer(inv -> batches.stream().filter(b -> b.getTargetTable().equals(inv.getArgument(1))).toList());
            when(batchRepo.findByRunIdOrderByIdAsc(RUN)).thenAnswer(inv -> List.copyOf(batches));
        }
        ImportBatch batch(long id, String table, String status) {
            ImportBatch b = new ImportBatch(); b.setId(id); b.setRunId(RUN); b.setClientId(TENANT); b.setTargetTable(table); b.setStatus(status);
            batches.add(b); when(batchRepo.findByIdAndClientId(id, TENANT)).thenReturn(Optional.of(b)); return b;
        }

        @Test
        @DisplayName("load order: parents (people, campaigns, events, groups, children) before the rows that link to them; DEPENDS covers every linking type")
        void order() {
            List<String> o = EtlLoadService.LOAD_ORDER;
            assertThat(o).hasSize(12).startsWith("family").containsAll(EtlTargetSchema.tables());
            EtlLoadService.DEPENDS.forEach((child, parents) -> parents.forEach(p -> assertThat(o.indexOf(p)).as(child + " after " + p).isLessThan(o.indexOf(child))));
            assertThat(EtlLoadService.DEPENDS.get("km_checkin")).containsExactly("km_child");
            assertThat(EtlLoadService.DEPENDS.get("pledge")).contains("pledge_campaign", "family");
        }

        @Test
        @DisplayName("a record batch waits for its parent batch and for parents staged-but-unloaded; rollback of a parent waits for the dependants")
        void gating() {
            batch(1, "group", "APPROVED");
            ImportBatch members = batch(2, "group_member", "APPROVED");
            assertThatThrownBy(() -> svc.loadBatch(2L, TENANT, "actor")).hasMessageContaining("Load the groups batch #1 first — the rows in this batch link to it");
            batches.get(0).setStatus("LOADED");
            when(records.waitingFor(2L, "group_member", TENANT)).thenReturn("3 row(s) refer to people staged in this run that are not loaded yet — approve and load the people first");
            assertThatThrownBy(() -> svc.loadBatch(2L, TENANT, "actor")).hasMessageContaining("approve and load the people first");

            members.setStatus("LOADED");
            assertThatThrownBy(() -> svc.rollbackBatch(1L, TENANT, "actor")).hasMessageContaining("Roll back the group memberships batch #2 first — its rows link to these groups");
            batch(3, "income", "LOADED");
            batch(4, "family", "LOADED");
            assertThatThrownBy(() -> svc.rollbackBatch(4L, TENANT, "actor")).hasMessageContaining("first — its rows link to these people");
        }

        @Test
        @DisplayName("run plan: the core three tables always; a record type only once the run uses it; label and dependencies included")
        void plan() {
            when(fam.findByRunId(RUN)).thenReturn(List.of()); when(inc.findByRunId(RUN)).thenReturn(List.of()); when(exp.findByRunId(RUN)).thenReturn(List.of());
            StagingRecord p = new StagingRecord(); p.setTargetTable("pledge"); p.setRowStatus("REVIEW");
            StagingRecord c = new StagingRecord(); c.setTargetTable("pledge_campaign"); c.setRowStatus("VALID");
            when(records.runRows(RUN)).thenReturn(List.of(p, c));
            List<Map<String, Object>> plan = svc.runPlan(RUN, TENANT);
            assertThat(plan).extracting(m -> m.get("targetTable")).containsExactly("family", "pledge_campaign", "income", "expense", "pledge");
            Map<String, Object> pl = plan.get(4);
            assertThat(pl.get("label")).isEqualTo("Pledges"); assertThat(pl.get("dependsOn")).isEqualTo("pledge_campaign,family");
            assertThat(pl.get("state")).isEqualTo("REVIEW"); assertThat(pl.get("canApprove")).isEqualTo(false); assertThat(pl.get("order")).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("controller: record rows")
    class Controller {
        @Test
        @DisplayName("preview shows a record row's values in schema order and what its references resolved to; the CSV report has stable columns; summary counts records")
        void views() throws Exception {
            ImportRunService runService = mock(ImportRunService.class);
            ImportRun run = new ImportRun(); run.setId(1L); run.setClientId(TENANT); run.setStatus("VALIDATED");
            when(runService.getRun(1L, TENANT)).thenReturn(run);
            ServiceClientRepository clients = mock(ServiceClientRepository.class);
            when(clients.findByClientId(TENANT)).thenReturn(Optional.of(new ServiceClient()));
            StagingRecordRepository recordRepo = mock(StagingRecordRepository.class);
            StagingRecord a = new StagingRecord(); a.setId(1L); a.setRunId(1L); a.setClientId(TENANT); a.setTargetTable("pledge"); a.setSourceRowId("PL-1");
            a.setRowStatus("VALID"); a.setMatchTier("NONE"); a.setDedupeAction("INSERT");
            a.setValuesJson("{\"pledgeAmount\":\"1200.00\",\"personLink\":\"701\"}"); a.setLinksJson("{\"parent\":\"s9\",\"person\":\"m40\"}");
            StagingRecord b = new StagingRecord(); b.setId(2L); b.setRunId(1L); b.setClientId(TENANT); b.setTargetTable("pledge"); b.setSourceRowId("PL-2");
            b.setRowStatus("REVIEW"); b.setMatchTier("POSSIBLE"); b.setDedupeMatchId(301L);
            b.setValuesJson("{\"guestName\":\"=HYPERLINK()\",\"pledgeAmount\":\"250\"}"); b.setLinksJson("{\"parent\":\"l1\"}");
            when(recordRepo.findByRunIdAndTargetTableOrderByIdAsc(1L, "pledge")).thenReturn(List.of(a, b));
            ImportEtlController c = new ImportEtlController(runService, clients, mock(SourceExtractService.class), mock(SourceProfileService.class),
                mock(StagingRawRepository.class), mock(SourceColumnProfileRepository.class), mock(MappingService.class),
                mock(TransformStageService.class), mock(EtlValidationService.class), mock(StagingFamilyRepository.class),
                mock(StagingIncomeRepository.class), mock(StagingExpenseRepository.class), mock(EtlLoadService.class),
                new EtlConcurrencyGuard(), mock(EtlAuditVerifier.class));
            c.setRecordRepo(recordRepo);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(c).build();
            MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1L);
            ObjectMapper json = new ObjectMapper();

            JsonNode p = json.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/preview").param("clientId", TENANT).param("targetTable", "pledge").session(s))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(p.get("total").asInt()).isEqualTo(2);
            JsonNode r0 = p.get("rows").get(0);
            List<String> keys = new ArrayList<>(); r0.get("values").fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactly("campaignLink", "campaignName", "pledgeAmount", "monthlyAmount", "personLink", "guestName", "amountCollected", "gifts", "notes");
            assertThat(r0.get("linkedTo").get("parent").asText()).isEqualTo("row staged in this run");
            assertThat(r0.get("linkedTo").get("person").asText()).isEqualTo("existing #40");
            JsonNode rev = json.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/preview").param("clientId", TENANT).param("targetTable", "pledge")
                .param("filter", "review").session(s)).andReturn().getResponse().getContentAsString());
            assertThat(rev.get("total").asInt()).isEqualTo(1);

            JsonNode sum = json.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/summary").param("clientId", TENANT).param("targetTable", "pledge").session(s))
                .andReturn().getResponse().getContentAsString()).get("summary");
            assertThat(sum.get("review").asInt()).isEqualTo(1); assertThat(sum.get("canApprove").asBoolean()).isFalse();

            String csv = mvc.perform(get("/api/serviceadmin/etl/runs/1/report-csv").param("clientId", TENANT).param("targetTable", "pledge").session(s))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String[] lines = csv.replace("﻿", "").split("\r\n");
            assertThat(lines[0]).isEqualTo("source_row,status,match_tier,match_detail,decision,matched_id,messages,campaignLink,campaignName,pledgeAmount,monthlyAmount,personLink,guestName,amountCollected,gifts,notes");
            assertThat(lines[2]).contains("'=HYPERLINK()");                                  // formula guard applies to record rows too
            mvc.perform(get("/api/serviceadmin/etl/runs/1/preview").param("clientId", TENANT).param("targetTable", "nope").session(s)).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("V21 is safe on a fresh database (the group_member ALTER is guarded, staging_record is created outright) and mirrored in migrate_production.sql")
    void migration() throws Exception {
        String v21 = Files.readString(Path.of("src/main/resources/db/migration/V21__etl_record_types.sql"));
        assertThat(v21).contains("CREATE TABLE IF NOT EXISTS staging_record");
        assertThat(v21).contains("IF to_regclass('public.group_member') IS NOT NULL THEN");
        assertThat(v21).doesNotContainPattern("(?m)^ALTER TABLE");                         // no un-guarded ALTER at top level
        String prod = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        assertThat(prod).contains("ETL Import — Migration Phase 4").contains("CREATE TABLE IF NOT EXISTS staging_record")
            .contains("ALTER TABLE group_member ADD COLUMN IF NOT EXISTS family_member_id INTEGER;");
    }
}
