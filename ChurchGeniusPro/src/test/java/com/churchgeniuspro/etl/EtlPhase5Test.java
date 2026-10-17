package com.churchgeniuspro.etl;

import com.churchgeniuspro.controller.DataImportHistoryController;
import com.churchgeniuspro.controller.ImportEtlController;
import com.churchgeniuspro.controller.ServiceAdminController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.EtlTypeDetector;
import com.churchgeniuspro.util.SourcePreset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Migration Phase 5: the wizard's backend — page gate, church-side history, type detection,
 * run settings, date preview, migration summary / audit CSV, overview, audit batch ids.
 * Mocked repositories only; no database, no real data.
 */
@DisplayName("ETL Phase 5 — wizard backend, history and authorization")
class EtlPhase5Test {

    static final String TENANT = "CHR-test", OTHER = "CHR-other";
    static final ObjectMapper JSON = new ObjectMapper();

    static MockHttpSession admin() { MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1L); return s; }

    /** A church (tenant) login — has a username and clientId but no serviceAdminId. */
    static MockHttpSession tenant(String clientId, String role, boolean church) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "someone"); s.setAttribute("clientId", clientId); s.setAttribute("appClientId", clientId);
        if (role != null) s.setAttribute("role", role);
        if (church) s.setAttribute("church", true);
        return s;
    }

    static List<String> headers(String fixture) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                EtlPhase5Test.class.getResourceAsStream("/etl/" + fixture), StandardCharsets.UTF_8))) {
            return Arrays.asList(r.readLine().replace("﻿", "").split(","));
        }
    }

    static ImportBatch batch(long id, long run, String client, String table, String status, int ins, int upd) {
        ImportBatch b = new ImportBatch(); b.setId(id); b.setRunId(run); b.setClientId(client); b.setTargetTable(table); b.setStatus(status);
        b.setInsertedCount(ins); b.setUpdatedCount(upd); return b;
    }

    static ImportAudit audit(long run, String client, String action, LocalDateTime at, Long batchId) {
        ImportAudit a = new ImportAudit(); a.setRunId(run); a.setClientId(client); a.setAction(action); a.setActor("serviceadmin:1");
        a.setDetail("{}"); a.setBatchId(batchId); ReflectionTestUtils.setField(a, "createdDate", at); return a;
    }

    // ───────────────────────── page gate ─────────────────────────

    @Nested
    @DisplayName("/etlImport page gate")
    class PageGate {
        MockMvc mvc;

        @BeforeEach
        void setUp() {
            ServiceAdminController c = new ServiceAdminController(mock(ServiceAdminRepository.class), mock(ServiceClientService.class),
                mock(ServiceClientRepository.class), mock(BackupService.class), mock(OpenAiUsageService.class), mock(ChurchVoiceSettingService.class));
            mvc = MockMvcBuilders.standaloneSetup(c).build();
        }

        @Test
        @DisplayName("no session → redirect to the service-admin login; nothing of the page is served")
        void noSession() throws Exception {
            String body = mvc.perform(get("/etlImport")).andExpect(status().isFound())
                .andExpect(header().string("Location", "/serviceadminlogin")).andReturn().getResponse().getContentAsString();
            assertThat(body).isEmpty();
        }

        @Test
        @DisplayName("a church (tenant) session, even Admin/SuperAdmin, is refused the same way")
        void tenantSession() throws Exception {
            mvc.perform(get("/etlImport").session(tenant(TENANT, "SuperAdmin", false))).andExpect(status().isFound())
                .andExpect(header().string("Location", "/serviceadminlogin"));
            mvc.perform(get("/etlImport").session(tenant(TENANT, null, true))).andExpect(status().isFound());
        }

        @Test
        @DisplayName("a service-admin session gets the six-step wizard, uncached")
        void serviceAdmin() throws Exception {
            String html = mvc.perform(get("/etlImport").session(admin())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            for (int i = 1; i <= 6; i++) assertThat(html).contains("id=\"step" + i + "\"");
            assertThat(html).contains("id=\"runBar\"", "id=\"rail\"", "id=\"btnLoadRun\"", "id=\"auditDrawer\"");
        }

        @Test
        @DisplayName("the old static address redirects to the gate; the page is no longer under /static")
        void oldUrl() throws Exception {
            mvc.perform(get("/etl-import.html")).andExpect(status().isFound()).andExpect(header().string("Location", "/etlImport"));
            assertThat(getClass().getResource("/static/etl-import.html")).isNull();
            assertThat(getClass().getResource("/admin-pages/etl-import.html")).isNotNull();
        }
    }

    // ───────────────────────── church-side history ─────────────────────────

    @Nested
    @DisplayName("church-side read-only import history (/api/data-imports)")
    class History {
        ImportRunRepository runRepo; ImportBatchRepository batchRepo; ImportAuditRepository auditRepo; MockMvc mvc;

        @BeforeEach
        void setUp() {
            runRepo = mock(ImportRunRepository.class); batchRepo = mock(ImportBatchRepository.class); auditRepo = mock(ImportAuditRepository.class);
            ImportRun loaded = run(1, TENANT, "LOADED", "BREEZE");
            ImportRun undone = run(2, TENANT, "ROLLED_BACK", "GENERIC");
            ImportRun draft = run(3, TENANT, "MAPPED", "GENERIC");
            when(runRepo.findByClientIdOrderByCreatedDateDesc(TENANT)).thenReturn(List.of(loaded, undone, draft));
            when(runRepo.findByClientIdOrderByCreatedDateDesc(OTHER)).thenReturn(List.of(run(9, OTHER, "LOADED", "PCO")));
            when(batchRepo.findByRunIdOrderByIdAsc(1L)).thenReturn(List.of(batch(11, 1, TENANT, "family", "LOADED", 4, 1), batch(12, 1, TENANT, "income", "LOADED", 6, 0),
                batch(13, 1, OTHER, "income", "LOADED", 99, 0)));
            when(batchRepo.findByRunIdOrderByIdAsc(2L)).thenReturn(List.of(batch(21, 2, TENANT, "family", "ROLLED_BACK", 3, 0)));
            when(batchRepo.findByRunIdOrderByIdAsc(9L)).thenReturn(List.of(batch(91, 9, OTHER, "family", "LOADED", 50, 0)));
            LocalDateTime t = LocalDateTime.of(2026, 10, 1, 9, 30);
            when(auditRepo.findByRunIdAndClientIdOrderByCreatedDateAsc(1L, TENANT)).thenReturn(List.of(audit(1, TENANT, "LOAD", t, 11L)));
            when(auditRepo.findByRunIdAndClientIdOrderByCreatedDateAsc(2L, TENANT)).thenReturn(List.of(audit(2, TENANT, "LOAD", t, 21L), audit(2, TENANT, "ROLLBACK", t.plusDays(1), 21L)));
            when(auditRepo.findByRunIdAndClientIdOrderByCreatedDateAsc(9L, OTHER)).thenReturn(List.of(audit(9, OTHER, "LOAD", t, 91L)));
            mvc = MockMvcBuilders.standaloneSetup(new DataImportHistoryController(runRepo, batchRepo, auditRepo)).build();
        }

        ImportRun run(long id, String client, String status, String preset) {
            ImportRun r = new ImportRun(); r.setId(id); r.setClientId(client); r.setStatus(status); r.setSourcePreset(preset);
            r.setSourceLabel("internal note — never shown to the church"); return r;
        }

        @Test
        @DisplayName("no session → 401; a member-portal or non-admin role → 403")
        void gated() throws Exception {
            mvc.perform(get("/api/data-imports")).andExpect(status().isUnauthorized());
            MockHttpSession member = new MockHttpSession();
            member.setAttribute("role", "Member"); member.setAttribute("memberId", 5L); member.setAttribute("appClientId", TENANT);
            mvc.perform(get("/api/data-imports").session(member)).andExpect(status().isForbidden());
            mvc.perform(get("/api/data-imports").session(tenant(TENANT, "Accountant", false))).andExpect(status().isForbidden());
            verify(runRepo, never()).findByClientIdOrderByCreatedDateDesc(any());
        }

        @Test
        @DisplayName("own church only: loaded and undone runs, counts per data type, no notes, no operator name, other tenant's batch ignored")
        void ownTenant() throws Exception {
            String body = mvc.perform(get("/api/data-imports").session(tenant(TENANT, "Admin", false))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode j = JSON.readTree(body).get("imports");
            assertThat(j).hasSize(2);
            assertThat(j.get(0).get("importedBy").asText()).isEqualTo("ChurchGeniusPro support");
            assertThat(j.get(0).get("source").asText()).isEqualTo("Breeze ChMS");
            assertThat(j.get(0).get("status").asText()).isEqualTo("Imported");
            assertThat(j.get(0).get("added").get("Income / donations").asLong()).isEqualTo(6);   // run 1's OTHER-tenant batch is not counted
            assertThat(j.get(1).get("status").asText()).isEqualTo("Undone");
            assertThat(j.get(1).get("undoneOn").asText()).startsWith("2026-10-02");
            assertThat(j.get(1).get("source").asText()).isEqualTo("Spreadsheet (CSV / Excel)");
            assertThat(body).doesNotContain("internal note", "serviceadmin:", "\"id\"");
            verify(runRepo, never()).findByClientIdOrderByCreatedDateDesc(OTHER);
        }

        @Test
        @DisplayName("a church-account login sees its own history")
        void churchLogin() throws Exception {
            JsonNode j = JSON.readTree(mvc.perform(get("/api/data-imports").session(tenant(TENANT, null, true))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
            assertThat(j.get("imports")).hasSize(2);
        }
    }

    // ───────────────────────── type detection ─────────────────────────

    @Nested
    @DisplayName("EtlTypeDetector — data type from the headers")
    class Detector {
        @Test
        @DisplayName("Breeze people and giving exports are told apart")
        void breeze() throws Exception {
            assertThat(EtlTypeDetector.detect(headers("breeze_people_sample.csv"), SourcePreset.BREEZE).table()).isEqualTo("family");
            EtlTypeDetector.Result g = EtlTypeDetector.detect(headers("breeze_giving_sample.csv"), SourcePreset.BREEZE);
            assertThat(g.table()).isEqualTo("income");
            assertThat(g.ranking()).hasSizeLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("generic record exports: pledges, registrations, check-ins, people")
        void records() throws Exception {
            assertThat(EtlTypeDetector.detect(headers("p4_pledges.csv"), SourcePreset.GENERIC).table()).isEqualTo("pledge");
            assertThat(EtlTypeDetector.detect(headers("p4_registrations.csv"), SourcePreset.GENERIC).table()).isEqualTo("event_registration");
            assertThat(EtlTypeDetector.detect(headers("p4_checkins.csv"), SourcePreset.GENERIC).table()).isEqualTo("km_checkin");
            assertThat(EtlTypeDetector.detect(headers("p4_people.csv"), SourcePreset.GENERIC).table()).isEqualTo("family");
            assertThat(EtlTypeDetector.detect(headers("chmeetings_contributions_sample.csv"), SourcePreset.CHMEETINGS).table()).isEqualTo("income");
        }

        @Test
        @DisplayName("unrecognisable headers → no suggestion")
        void none() {
            EtlTypeDetector.Result r = EtlTypeDetector.detect(List.of("Foo", "Bar", "Baz"), SourcePreset.GENERIC);
            assertThat(r.table()).isNull();
            assertThat(r.confidence()).isEqualTo("NONE");
        }
    }

    // ───────────────────────── run service ─────────────────────────

    @Nested
    @DisplayName("ImportRunService — settings and audit batch id")
    class RunService {
        ImportRunRepository runRepo; ImportAuditRepository auditRepo; ImportRunService svc; ImportRun run;

        @BeforeEach
        void setUp() {
            runRepo = mock(ImportRunRepository.class); auditRepo = mock(ImportAuditRepository.class);
            svc = new ImportRunService(runRepo, auditRepo);
            run = new ImportRun(); run.setId(7L); run.setClientId(TENANT); run.setStatus("STAGED"); run.setDateOrder("AUTO");
            when(runRepo.findByIdAndClientId(7L, TENANT)).thenReturn(Optional.of(run));
            when(runRepo.findByIdAndClientId(7L, OTHER)).thenReturn(Optional.empty());
            when(runRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("date order changes before approval, audited as SETTINGS")
        void ok() {
            svc.updateSettings(7L, TENANT, "serviceadmin:1", "dmy", "  Breeze export  ");
            assertThat(run.getDateOrder()).isEqualTo("DMY");
            assertThat(run.getSourceLabel()).isEqualTo("Breeze export");
            ArgumentCaptor<ImportAudit> a = ArgumentCaptor.forClass(ImportAudit.class);
            verify(auditRepo).save(a.capture());
            assertThat(a.getValue().getAction()).isEqualTo("SETTINGS");
            assertThat(a.getValue().getDetail()).contains("DMY (was AUTO)");
        }

        @Test
        @DisplayName("refused once approved / loaded / discarded, for a bad order, and for another church")
        void refused() {
            assertThatThrownBy(() -> svc.updateSettings(7L, TENANT, "a", "XYZ", null)).hasMessageContaining("AUTO, MDY, DMY or YMD");
            for (String st : List.of("APPROVED", "LOADED", "DISCARDED")) {
                run.setStatus(st);
                assertThatThrownBy(() -> svc.updateSettings(7L, TENANT, "a", "MDY", null)).hasMessageContaining("before anything is approved");
            }
            assertThatThrownBy(() -> svc.updateSettings(7L, OTHER, "a", "MDY", null)).hasMessageContaining("not found");
            verify(auditRepo, never()).save(any());
        }

        @Test
        @DisplayName("an audit event whose detail names a batch records it in import_audit.batch_id")
        void batchId() {
            svc.recordAudit(7L, TENANT, "serviceadmin:1", "LOAD", Map.of("batchId", 42L, "targetTable", "income"));
            svc.recordAudit(7L, TENANT, "serviceadmin:1", "EXTRACT", Map.of("sourceTable", "people"));
            ArgumentCaptor<ImportAudit> a = ArgumentCaptor.forClass(ImportAudit.class);
            verify(auditRepo, times(2)).save(a.capture());
            assertThat(a.getAllValues().get(0).getBatchId()).isEqualTo(42L);
            assertThat(a.getAllValues().get(1).getBatchId()).isNull();
        }
    }

    // ───────────────────────── controller endpoints ─────────────────────────

    @Nested
    @DisplayName("ImportEtlController — Phase 5 endpoints")
    class Endpoints {
        ImportRunService runService; EtlLoadService loadService; MappingService mappingService; SourceColumnProfileRepository profileRepo;
        ImportFileRepository fileRepo; MockMvc mvc; ImportRun run;

        @BeforeEach
        void setUp() {
            runService = mock(ImportRunService.class); loadService = mock(EtlLoadService.class); mappingService = mock(MappingService.class);
            profileRepo = mock(SourceColumnProfileRepository.class); fileRepo = mock(ImportFileRepository.class);
            run = new ImportRun(); run.setId(1L); run.setClientId(TENANT); run.setStatus("LOADED"); run.setSourcePreset("BREEZE"); run.setDateOrder("MDY");
            when(runService.getRun(1L, TENANT)).thenReturn(run);
            when(runService.getRun(1L, OTHER)).thenThrow(new ImportRunService.ImportRunException("Import run 1 not found for this tenant"));
            when(runService.getAudit(1L, OTHER)).thenThrow(new ImportRunService.ImportRunException("Import run 1 not found for this tenant"));
            ServiceClientRepository clients = mock(ServiceClientRepository.class);
            ServiceClient sc = new ServiceClient(); sc.setChurchName("Example Fellowship");
            when(clients.findByClientId(TENANT)).thenReturn(Optional.of(sc));
            when(clients.findByClientId(OTHER)).thenReturn(Optional.of(new ServiceClient()));
            ImportEtlController c = new ImportEtlController(runService, clients, mock(SourceExtractService.class), mock(SourceProfileService.class),
                mock(StagingRawRepository.class), profileRepo, mappingService, mock(TransformStageService.class), mock(EtlValidationService.class),
                mock(StagingFamilyRepository.class), mock(StagingIncomeRepository.class), mock(StagingExpenseRepository.class), loadService,
                new EtlConcurrencyGuard(), mock(EtlAuditVerifier.class));
            ReflectionTestUtils.setField(c, "fileRepo", fileRepo);
            mvc = MockMvcBuilders.standaloneSetup(c).build();

            Map<String, Object> fam = new LinkedHashMap<>();
            fam.put("targetTable", "family"); fam.put("state", "LOADED"); fam.put("staged", 4);
            fam.put("counts", new HashMap<>(Map.of("LOADED", 3L, "SKIPPED", 1L)));
            fam.put("batches", List.of(Map.of("id", 5, "status", "LOADED", "inserted", 3, "updated", 0, "skipped", 1, "failed", 0)));
            Map<String, Object> inc = new LinkedHashMap<>();
            inc.put("targetTable", "income"); inc.put("state", "LOADED"); inc.put("staged", 6);
            inc.put("counts", new HashMap<>(Map.of("LOADED", 5L, "ERROR", 1L)));
            inc.put("batches", List.of(Map.of("id", 6, "status", "LOADED", "inserted", 5, "updated", 0, "skipped", 0, "failed", 0)));
            Map<String, Object> exp = new LinkedHashMap<>();
            exp.put("targetTable", "expense"); exp.put("state", "NOT_STAGED"); exp.put("staged", 0); exp.put("counts", new HashMap<>()); exp.put("batches", List.of());
            when(loadService.runPlan(1L, TENANT)).thenReturn(List.of(fam, inc, exp));
            when(runService.getAudit(1L, TENANT)).thenReturn(List.of(
                audit(1, TENANT, "LOAD", LocalDateTime.of(2026, 10, 1, 9, 0), 5L),
                audit(1, TENANT, "LOAD", LocalDateTime.of(2026, 10, 1, 9, 5), 6L)));
        }

        String json(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
            return mvc.perform(rb).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("every Phase 5 endpoint needs a service-admin session")
        void gated() throws Exception {
            for (String p : List.of("/api/serviceadmin/etl/overview", "/api/serviceadmin/etl/runs/1/date-preview?clientId=" + TENANT,
                    "/api/serviceadmin/etl/runs/1/migration-summary?clientId=" + TENANT))
                mvc.perform(get(p)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/serviceadmin/etl/runs/1/migration-summary-csv").param("clientId", TENANT)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/serviceadmin/etl/runs/1/audit-csv").param("clientId", TENANT)).andExpect(status().isUnauthorized());
            mvc.perform(put("/api/serviceadmin/etl/runs/1/settings").contentType("application/json").content("{\"clientId\":\"" + TENANT + "\",\"dateOrder\":\"DMY\"}"))
                .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/serviceadmin/etl/overview").session(tenant(TENANT, "SuperAdmin", false))).andExpect(status().isUnauthorized());
            verify(runService, never()).updateSettings(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("wrong church → 404 (the run is not found for that tenant); unknown church → 400")
        void wrongChurch() throws Exception {
            mvc.perform(get("/api/serviceadmin/etl/runs/1/migration-summary").param("clientId", OTHER).session(admin())).andExpect(status().isNotFound());
            mvc.perform(get("/api/serviceadmin/etl/runs/1/migration-summary-csv").param("clientId", OTHER).session(admin())).andExpect(status().isNotFound());
            mvc.perform(get("/api/serviceadmin/etl/runs/1/audit-csv").param("clientId", OTHER).session(admin())).andExpect(status().isNotFound());
            mvc.perform(get("/api/serviceadmin/etl/runs/1/date-preview").param("clientId", OTHER).session(admin())).andExpect(status().isNotFound());
            mvc.perform(get("/api/serviceadmin/etl/runs/1/migration-summary").param("clientId", "CHR-nope").session(admin())).andExpect(status().isBadRequest());
            when(runService.updateSettings(eq(1L), eq(OTHER), any(), any(), any())).thenThrow(new ImportRunService.ImportRunException("Import run 1 not found for this tenant"));
            mvc.perform(put("/api/serviceadmin/etl/runs/1/settings").session(admin()).contentType("application/json")
                .content("{\"clientId\":\"" + OTHER + "\",\"dateOrder\":\"DMY\"}")).andExpect(status().isNotFound());
            verify(loadService, never()).runPlan(1L, OTHER);
        }

        @Test
        @DisplayName("settings: a refusal after approval is a 409 with its reason")
        void settingsConflict() throws Exception {
            when(runService.updateSettings(eq(1L), eq(TENANT), any(), eq("DMY"), any()))
                .thenThrow(new ImportRunService.ImportRunException("Settings can only change before anything is approved or loaded (run is LOADED)"));
            String b = mvc.perform(put("/api/serviceadmin/etl/runs/1/settings").session(admin()).contentType("application/json")
                .content("{\"clientId\":\"" + TENANT + "\",\"dateOrder\":\"DMY\"}")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
            assertThat(b).contains("before anything is approved");
        }

        @Test
        @DisplayName("migration summary: per type and totals; untouched types left out; errors counted as not loaded")
        void summary() throws Exception {
            JsonNode s = JSON.readTree(json(get("/api/serviceadmin/etl/runs/1/migration-summary").param("clientId", TENANT).session(admin()))).get("summary");
            assertThat(s.get("church").asText()).isEqualTo("Example Fellowship");
            assertThat(s.get("tables")).hasSize(2);
            assertThat(s.get("totals").get("inserted").asLong()).isEqualTo(8);
            assertThat(s.get("totals").get("skipped").asLong()).isEqualTo(1);
            assertThat(s.get("totals").get("notLoaded").asLong()).isEqualTo(1);
            assertThat(s.get("tables").get(1).get("errors").asLong()).isEqualTo(1);
            assertThat(s.get("lastLoadedAt").asText()).startsWith("2026-10-01T09:05");
        }

        @Test
        @DisplayName("summary and audit CSVs: attachment, uncached, BOM, one line per type / event with its batch")
        void csvs() throws Exception {
            var res = mvc.perform(get("/api/serviceadmin/etl/runs/1/migration-summary-csv").param("clientId", TENANT).session(admin()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
            assertThat(res.getHeader("Content-Disposition")).contains("etl-run-1-migration-summary.csv");
            String csv = res.getContentAsString(StandardCharsets.UTF_8);
            assertThat(csv).startsWith("﻿").contains("People & households", "Income / donations", "Total,,,8,0,1,0,,1");
            assertThat(csv).doesNotContain("Expenses");
            String audit = mvc.perform(get("/api/serviceadmin/etl/runs/1/audit-csv").param("clientId", TENANT).session(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(audit.split("\r\n")).hasSize(3);
            assertThat(audit).contains(",LOAD,5,", ",LOAD,6,");
        }

        @Test
        @DisplayName("date preview: a mapped date column read four ways, flagged ambiguous when MDY and DMY disagree")
        void datePreview() throws Exception {
            MappingRule r = new MappingRule(); r.setRunId(1L); r.setTargetTable("income"); r.setTargetColumn("incomeDate");
            r.setSourceTable("giving"); r.setSourceColumn("Date"); r.setStatus("AUTO");
            MappingRule rejected = new MappingRule(); rejected.setRunId(1L); rejected.setTargetTable("income"); rejected.setTargetColumn("amount");
            rejected.setSourceTable("giving"); rejected.setSourceColumn("Amount"); rejected.setStatus("AUTO");
            when(mappingService.list(1L, "income")).thenReturn(List.of(r, rejected));
            SourceColumnProfile p = new SourceColumnProfile(); p.setColumnName("Date"); p.setSampleValues("[\"03/04/2024\",\"12/25/2024\"]");
            when(profileRepo.findByRunIdAndSourceTableOrderByOrdinalAsc(1L, "giving")).thenReturn(List.of(p));
            JsonNode j = JSON.readTree(json(get("/api/serviceadmin/etl/runs/1/date-preview").param("clientId", TENANT).param("targetTable", "income").session(admin())));
            assertThat(j.get("columns")).hasSize(1);   // amount is not a date
            JsonNode s0 = j.get("columns").get(0).get("samples").get(0);
            assertThat(s0.get("MDY").asText()).isEqualTo("2024-03-04");
            assertThat(s0.get("DMY").asText()).isEqualTo("2024-04-03");
            assertThat(j.get("columns").get(0).get("samples").get(1).get("DMY").asText()).startsWith("✕");
            assertThat(j.get("ambiguous").asBoolean()).isTrue();
            assertThat(j.get("dateOrder").asText()).isEqualTo("MDY");
        }

        @Test
        @DisplayName("overview: in-progress and completed runs across churches, with church name and what each loaded")
        void overview() throws Exception {
            ImportRun open = new ImportRun(); open.setId(2L); open.setClientId(OTHER); open.setStatus("MAPPED"); open.setSourcePreset("GENERIC");
            when(runService.recentAcrossChurches(argThat(c -> c != null && c.contains("MAPPED")))).thenReturn(List.of(open));
            when(runService.recentAcrossChurches(argThat(c -> c != null && c.contains("LOADED")))).thenReturn(List.of(run));
            when(loadService.listBatches(1L)).thenReturn(List.of(batch(5, 1, TENANT, "family", "LOADED", 3, 0), batch(6, 1, TENANT, "income", "LOADED", 5, 0)));
            when(loadService.listBatches(2L)).thenReturn(List.of());
            JsonNode j = JSON.readTree(json(get("/api/serviceadmin/etl/overview").session(admin())));
            assertThat(j.get("inProgress")).hasSize(1);
            assertThat(j.get("completed").get(0).get("church").asText()).isEqualTo("Example Fellowship");
            assertThat(j.get("completed").get(0).get("inserted").asLong()).isEqualTo(8);
            assertThat(j.get("completed").get(0).get("dataTypes")).hasSize(2);
        }
    }

    // ───────────────────────── migration ─────────────────────────

    @Test
    @DisplayName("V22 is additive and guarded (no-op on a fresh database) and mirrored in the production script")
    void v22Guarded() throws Exception {
        String v22 = Files.readString(Path.of("src/main/resources/db/migration/V22__etl_file_data_type.sql"));
        assertThat(v22).contains("to_regclass('public.import_file')", "ADD COLUMN IF NOT EXISTS target_table", "ADD COLUMN IF NOT EXISTS suggested_table");
        assertThat(v22.toUpperCase()).doesNotContain("DROP ", "RENAME ", "ALTER COLUMN");
        String prod = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        assertThat(prod).contains("suggested_table");
    }

    /**
     * Fresh-database safety for every ETL migration: Flyway runs before Hibernate creates the
     * entity tables, so an ALTER TABLE / CREATE INDEX on a table the file does not create itself
     * must sit behind {@code to_regclass('public.<table>')}. V19/V20 lacked this (a brand-new
     * database failed at V19 with "relation family does not exist"); this pins the fix.
     * (FlywayMigrationIT proves the same end to end but needs Docker.)
     */
    @Test
    @DisplayName("V19–V22: every statement on an existing table is to_regclass-guarded or targets a table the file creates")
    void etlMigrationsAreFreshDatabaseSafe() throws Exception {
        java.util.regex.Pattern touch = java.util.regex.Pattern.compile(
            "(?i)(?:ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?|CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\\w+\\s+ON\\s+)(\\w+)");
        java.util.regex.Pattern created = java.util.regex.Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)");
        for (String f : List.of("V19__etl_source_identity.sql", "V20__etl_import_link.sql", "V21__etl_record_types.sql", "V22__etl_file_data_type.sql")) {
            String sql = Files.readString(Path.of("src/main/resources/db/migration/" + f)).replaceAll("(?m)--.*$", "");
            Set<String> own = new HashSet<>();
            var cm = created.matcher(sql); while (cm.find()) own.add(cm.group(1).toLowerCase());
            var m = touch.matcher(sql);
            int checked = 0;
            while (m.find()) {
                String table = m.group(1).toLowerCase();
                checked++;
                if (own.contains(table)) continue;
                assertThat(sql).as(f + ": statement on '" + table + "' must be guarded").contains("to_regclass('public." + table + "')");
                int guard = sql.indexOf("to_regclass('public." + table + "')");
                assertThat(guard).as(f + ": guard for '" + table + "' comes before its statement").isLessThan(m.start());
            }
            assertThat(checked).as(f + " has statements to check").isPositive();
        }
    }
}
