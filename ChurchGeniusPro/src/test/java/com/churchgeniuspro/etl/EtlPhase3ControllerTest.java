package com.churchgeniuspro.etl;

import com.churchgeniuspro.controller.ImportEtlController;
import com.churchgeniuspro.hibernate.ImportBatch;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Migration Phase 3 at the HTTP layer: run plan, run load / rollback, household proposals and decisions. */
@DisplayName("ETL Phase 3 — controller: plan, run load/rollback, households")
class EtlPhase3ControllerTest {

    private static final String TENANT = "CHR-test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private MockMvc mvc;
    private EtlLoadService loadService;
    private ImportRunService runService;
    private StagingFamilyRepository familyRepo;
    private List<StagingFamily> rows;

    private static StagingFamily row(long id, String first, String last, String proposal, String decision, String status) {
        StagingFamily r = new StagingFamily(); r.setId(id); r.setRunId(1L); r.setClientId(TENANT); r.setSourceRowId("P" + id);
        r.setFirstName(first); r.setLastName(last); r.setAddress1("123 Main St"); r.setCity("Olathe"); r.setPinCode("66061");
        r.setHouseholdProposal(proposal); r.setHouseholdDecision(decision); r.setRowStatus(status);
        r.setValidationMsgs("[{\"field\":\"_household\",\"level\":\"INFO\",\"message\":\"proposed household (undecided: stays separate)\"}]");
        return r;
    }

    @BeforeEach
    void setUp() {
        runService = mock(ImportRunService.class);
        ImportRun run = new ImportRun(); run.setId(1L); run.setClientId(TENANT); run.setStatus("VALIDATED");
        when(runService.getRun(1L, TENANT)).thenReturn(run);
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        when(clients.findByClientId(TENANT)).thenReturn(Optional.of(new ServiceClient()));
        familyRepo = mock(StagingFamilyRepository.class);
        rows = List.of(
            row(1, "Grace", "Example", "example|123mainst|66061", null, "VALID"),
            row(2, "Thomas", "Example", "example|123mainst|66061", null, "VALID"),
            row(3, "Sam", "Sampleton", "sampleton|123mainst|66061", "ACCEPTED", "VALID"),
            row(4, "Sue", "Sampleton", "sampleton|123mainst|66061", "ACCEPTED", "VALID"),
            row(5, "Loaded", "Frozen", "frozen|123mainst|66061", null, "LOADED"),
            row(6, "Also", "Frozen", "frozen|123mainst|66061", null, "LOADED"),
            row(7, "Solo", "Person", null, null, "VALID"));
        when(familyRepo.findByRunId(1L)).thenReturn(rows);
        when(familyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        loadService = mock(EtlLoadService.class);

        ImportEtlController c = new ImportEtlController(runService, clients, mock(SourceExtractService.class), mock(SourceProfileService.class),
            mock(StagingRawRepository.class), mock(SourceColumnProfileRepository.class), mock(MappingService.class),
            mock(TransformStageService.class), mock(EtlValidationService.class), familyRepo,
            mock(StagingIncomeRepository.class), mock(StagingExpenseRepository.class), loadService,
            new EtlConcurrencyGuard(), mock(EtlAuditVerifier.class));
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    private MockHttpSession admin() { MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1L); return s; }

    private static ImportBatch batch(long id, String table, String status) {
        ImportBatch b = new ImportBatch(); b.setId(id); b.setRunId(1L); b.setClientId(TENANT); b.setTargetTable(table); b.setStatus(status); return b;
    }

    @Test
    @DisplayName("plan: per-table entries plus canLoadRun / canRollbackRun derived from batch states; gated")
    void plan() throws Exception {
        when(loadService.runPlan(1L, TENANT)).thenReturn(List.of(
            Map.of("targetTable", "family", "batches", List.of(Map.of("id", 5, "status", "LOADED"))),
            Map.of("targetTable", "income", "batches", List.of(Map.of("id", 6, "status", "APPROVED"))),
            Map.of("targetTable", "expense", "batches", List.of())));
        mvc.perform(get("/api/serviceadmin/etl/runs/1/plan").param("clientId", TENANT)).andExpect(status().isUnauthorized());
        JsonNode j = JSON.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/plan").param("clientId", TENANT).session(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(j.get("plan")).hasSize(3);
        assertThat(j.get("canLoadRun").asBoolean()).isTrue();
        assertThat(j.get("canRollbackRun").asBoolean()).isTrue();
        assertThat(j.get("loadOrder").get(0).asText()).isEqualTo("family");
        assertThat(j.get("run").get("status").asText()).isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("run load and run rollback forward to the loader; a loader refusal is a 409 with its reason")
    void runLoadRollback() throws Exception {
        when(loadService.loadRun(1L, TENANT, "serviceadmin:1")).thenReturn(List.of(batch(5, "family", "LOADED"), batch(6, "income", "LOADED")));
        JsonNode j = JSON.readTree(mvc.perform(post("/api/serviceadmin/etl/runs/1/load").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(j.get("batches")).hasSize(2);
        assertThat(j.get("batches").get(1).get("targetTable").asText()).isEqualTo("income");

        when(loadService.rollbackRun(1L, TENANT, "serviceadmin:1")).thenThrow(new EtlLoadService.LoadException("Nothing to roll back — no loaded batches in this run"));
        String body = mvc.perform(post("/api/serviceadmin/etl/runs/1/rollback").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\"}")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Nothing to roll back");
        mvc.perform(post("/api/serviceadmin/etl/runs/1/load").contentType("application/json").content("{\"clientId\":\"" + TENANT + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("households: grouped proposals with members, decision, locked flag and counts; the solo row is not a proposal")
    void households() throws Exception {
        JsonNode j = JSON.readTree(mvc.perform(get("/api/serviceadmin/etl/runs/1/households").param("clientId", TENANT).session(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(j.get("households")).hasSize(3);
        assertThat(j.get("undecided").asInt()).isEqualTo(2); assertThat(j.get("accepted").asInt()).isEqualTo(1); assertThat(j.get("rejected").asInt()).isEqualTo(0);
        JsonNode ex = j.get("households").get(0);
        assertThat(ex.get("lastName").asText()).isEqualTo("Example");
        assertThat(ex.get("address").asText()).isEqualTo("123 Main St, Olathe, 66061");
        assertThat(ex.get("members")).hasSize(2);
        assertThat(ex.get("members").get(0).get("name").asText()).isEqualTo("Grace Example");
        assertThat(ex.get("locked").asBoolean()).isFalse();
        assertThat(j.get("households").get(1).get("decision").asText()).isEqualTo("ACCEPTED");
        assertThat(j.get("households").get(2).get("locked").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("deciding a household sets every row of the group, rewrites the row message, audits; locked groups and bad decisions are refused")
    void decide() throws Exception {
        mvc.perform(patch("/api/serviceadmin/etl/runs/1/households").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\",\"key\":\"example|123mainst|66061\",\"decision\":\"accept\"}")).andExpect(status().isOk());
        assertThat(rows.get(0).getHouseholdDecision()).isEqualTo("ACCEPTED");
        assertThat(rows.get(1).getHouseholdDecision()).isEqualTo("ACCEPTED");
        assertThat(rows.get(0).getValidationMsgs()).contains("(accepted)").doesNotContain("undecided");
        assertThat(rows.get(2).getHouseholdDecision()).isEqualTo("ACCEPTED");   // other group untouched (was already accepted)
        verify(runService).recordAudit(eq(1L), eq(TENANT), eq("serviceadmin:1"), eq("HOUSEHOLD"), any());

        mvc.perform(patch("/api/serviceadmin/etl/runs/1/households").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\",\"key\":\"sampleton|123mainst|66061\",\"decision\":\"CLEAR\"}")).andExpect(status().isOk());
        assertThat(rows.get(2).getHouseholdDecision()).isNull();
        assertThat(rows.get(2).getValidationMsgs()).contains("undecided");

        String locked = mvc.perform(patch("/api/serviceadmin/etl/runs/1/households").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\",\"key\":\"frozen|123mainst|66061\",\"decision\":\"REJECT\"}")).andExpect(status().isConflict())
            .andReturn().getResponse().getContentAsString();
        assertThat(locked).contains("already approved or loaded");
        mvc.perform(patch("/api/serviceadmin/etl/runs/1/households").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\",\"key\":\"example|123mainst|66061\",\"decision\":\"MAYBE\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/serviceadmin/etl/runs/1/households").contentType("application/json").session(admin())
            .content("{\"clientId\":\"" + TENANT + "\",\"key\":\"nope\",\"decision\":\"ACCEPT\"}")).andExpect(status().isNotFound());
    }
}
