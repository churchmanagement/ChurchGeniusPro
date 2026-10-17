package com.churchgeniuspro.etl;

import com.churchgeniuspro.controller.ImportEtlController;
import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.StagingFamily;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Migration Phase 2 at the HTTP layer: review counts, tier filters, decision patch, CSV report. */
@DisplayName("ETL Phase 2 — controller: summary, filters, decisions, CSV")
class EtlPhase2ControllerTest {

    private static final String TENANT = "CHR-test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private MockMvc mvc;
    private EtlLoadService loadService;

    private static StagingFamily row(long id, String src, String status, String tier, String action, Long match) {
        StagingFamily r = new StagingFamily(); r.setId(id); r.setRunId(1L); r.setClientId(TENANT); r.setSourceRowId(src);
        r.setRowStatus(status); r.setMatchTier(tier); r.setDedupeAction(action); r.setDedupeMatchId(match);
        r.setFirstName("First" + id); r.setLastName("Last, Jr"); r.setEmail("=cmd()"); r.setValidationMsgs(
            "POSSIBLE".equals(tier) ? "[{\"field\":\"_row\",\"level\":\"REVIEW\",\"message\":\"Possible duplicate\"}]" : null);
        return r;
    }

    @BeforeEach
    void setUp() {
        ImportRunService runService = mock(ImportRunService.class);
        ImportRun run = new ImportRun(); run.setId(1L); run.setClientId(TENANT); run.setStatus("VALIDATED");
        when(runService.getRun(1L, TENANT)).thenReturn(run);
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        when(clients.findByClientId(TENANT)).thenReturn(Optional.of(new ServiceClient()));
        StagingFamilyRepository familyRepo = mock(StagingFamilyRepository.class);
        List<StagingFamily> rows = List.of(
            row(1, "A", "VALID", "NONE", "INSERT", null),
            row(2, "B", "WARN", "STRONG", "SKIP", 10L),
            row(3, "C", "REVIEW", "POSSIBLE", null, 11L),
            row(4, "D", "ERROR", "NONE", "INSERT", null));
        when(familyRepo.findByRunId(1L)).thenReturn(rows);
        when(familyRepo.findByRunIdAndRowStatus(eq(1L), anyString())).thenAnswer(inv ->
            rows.stream().filter(r -> r.getRowStatus().equals(inv.getArgument(1))).toList());
        loadService = mock(EtlLoadService.class);

        ImportEtlController c = new ImportEtlController(runService, clients, mock(SourceExtractService.class), mock(SourceProfileService.class),
            mock(StagingRawRepository.class), mock(SourceColumnProfileRepository.class), mock(MappingService.class),
            mock(TransformStageService.class), mock(EtlValidationService.class), familyRepo,
            mock(StagingIncomeRepository.class), mock(StagingExpenseRepository.class), loadService,
            new EtlConcurrencyGuard(), mock(EtlAuditVerifier.class));
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    private MockHttpSession admin() { MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1L); return s; }

    @Test
    @DisplayName("summary counts tiers, statuses and decisions and says approval is blocked while REVIEW rows exist")
    void summary() throws Exception {
        mvc.perform(get("/api/serviceadmin/etl/runs/1/summary").param("clientId", TENANT).param("targetTable", "family")).andExpect(status().isUnauthorized());
        JsonNode s = JSON.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/summary").param("clientId", TENANT).param("targetTable", "family").session(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("summary");
        assertThat(s.get("total").asInt()).isEqualTo(4);
        assertThat(s.get("review").asInt()).isEqualTo(1);
        assertThat(s.get("strong").asInt()).isEqualTo(1);
        assertThat(s.get("possible").asInt()).isEqualTo(1);
        assertThat(s.get("new").asInt()).isEqualTo(2);
        assertThat(s.get("willSkip").asInt()).isEqualTo(1);
        assertThat(s.get("willInsert").asInt()).isEqualTo(2);
        assertThat(s.get("canApprove").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("preview filters: review, duplicates, new, skipped; rows carry tier and detail")
    void filters() throws Exception {
        for (String[] f : new String[][]{{"review", "1"}, {"duplicates", "2"}, {"new", "2"}, {"skipped", "1"}, {"all", "4"}, {"error", "1"}}) {
            JsonNode p = JSON.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/preview").param("clientId", TENANT).param("targetTable", "family")
                .param("filter", f[0]).session(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(p.get("total").asInt()).as(f[0]).isEqualTo(Integer.parseInt(f[1]));
        }
        JsonNode p = JSON.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/preview").param("clientId", TENANT).param("targetTable", "family")
            .param("filter", "review").session(admin())).andReturn().getResponse().getContentAsString());
        assertThat(p.get("rows").get(0).get("matchTier").asText()).isEqualTo("POSSIBLE");
        assertThat(p.get("rows").get(0).get("dedupeMatchId").asLong()).isEqualTo(11L);
    }

    @Test
    @DisplayName("a decision is forwarded to the loader; a refused decision returns 400 with the reason")
    void decision() throws Exception {
        mvc.perform(patch("/api/serviceadmin/etl/runs/1/rows/3").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\",\"targetTable\":\"family\",\"dedupeAction\":\"MERGE\"}")).andExpect(status().isOk());
        verify(loadService).updateRow(TENANT, "family", 3L, "MERGE", null);
        doThrow(new EtlLoadService.LoadException("Financial rows cannot be merged")).when(loadService).updateRow(TENANT, "income", 9L, "MERGE", null);
        String body = mvc.perform(patch("/api/serviceadmin/etl/runs/1/rows/9").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\",\"targetTable\":\"income\",\"dedupeAction\":\"MERGE\"}")).andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("cannot be merged");
    }

    @Test
    @DisplayName("CSV report: one line per row, tier/decision/messages columns, quoting and formula-injection guard")
    void csv() throws Exception {
        String csv = mvc.perform(get("/api/serviceadmin/etl/runs/1/report-csv").param("clientId", TENANT).param("targetTable", "family").session(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String[] lines = csv.replace("﻿", "").split("\r\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).startsWith("source_row,status,match_tier,match_detail,decision,matched_id,messages,firstName,lastName,email");
        assertThat(lines[3]).startsWith("C,REVIEW,POSSIBLE,,,11,REVIEW: Possible duplicate,First3,\"Last, Jr\",'=cmd()");
        mvc.perform(get("/api/serviceadmin/etl/runs/1/report-csv").param("clientId", TENANT).param("targetTable", "family")).andExpect(status().isUnauthorized());
    }
}
