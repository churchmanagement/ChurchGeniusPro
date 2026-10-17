package com.churchgeniuspro.etl;

import com.churchgeniuspro.controller.ImportEtlController;
import com.churchgeniuspro.hibernate.ImportFile;
import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.service.SourceExtractService.ExtractResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Migration Phase 1 at the HTTP layer: Service Admin gate, run creation with the
 * new fields, the presets listing, and the repeat-file guard (same content for
 * the same church → 409 with the earlier run; force=true → accepted and recorded).
 */
@DisplayName("ETL Phase 1 — controller: presets, run fields, repeat-file guard")
class EtlPhase1ControllerTest {

    private static final String TENANT = "CHR-test";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc mvc;
    private ImportRunService runService;
    private SourceExtractService extractService;
    private ImportFileRepository fileRepo;
    private final List<ImportFile> files = new ArrayList<>();

    private MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("serviceAdminId", 1L);
        return s;
    }

    @BeforeEach
    void setUp() {
        runService = mock(ImportRunService.class);
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        when(clients.findByClientId(TENANT)).thenReturn(Optional.of(new ServiceClient()));
        extractService = mock(SourceExtractService.class);
        SourceProfileService profileService = mock(SourceProfileService.class);
        when(profileService.profile(anyLong(), anyString(), anyString())).thenReturn(List.of());
        fileRepo = mock(ImportFileRepository.class);
        AtomicLong ids = new AtomicLong(1);
        when(fileRepo.save(any(ImportFile.class))).thenAnswer(inv -> { ImportFile f = inv.getArgument(0); if (f.getId() == null) f.setId(ids.getAndIncrement()); files.add(f); return f; });
        when(fileRepo.findByClientIdAndSha256(anyString(), anyString())).thenAnswer(inv ->
            files.stream().filter(f -> f.getClientId().equals(inv.getArgument(0)) && f.getSha256().equals(inv.getArgument(1))).toList());
        when(fileRepo.findByRunIdAndSourceTable(anyLong(), anyString())).thenReturn(List.of());

        ImportEtlController c = new ImportEtlController(runService, clients, extractService, profileService,
            mock(StagingRawRepository.class), mock(SourceColumnProfileRepository.class), mock(MappingService.class),
            mock(TransformStageService.class), mock(EtlValidationService.class), mock(StagingFamilyRepository.class),
            mock(StagingIncomeRepository.class), mock(StagingExpenseRepository.class), mock(EtlLoadService.class),
            new EtlConcurrencyGuard(), mock(EtlAuditVerifier.class));
        ReflectionTestUtils.setField(c, "fileRepo", fileRepo);
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    private ImportRun run(long id, String status) {
        ImportRun r = new ImportRun(); r.setId(id); r.setClientId(TENANT); r.setStatus(status); r.setSourcePreset("BREEZE"); r.setDateOrder("MDY");
        return r;
    }

    @Test
    @DisplayName("every Phase 1 endpoint needs the Service Admin session")
    void gate() throws Exception {
        mvc.perform(get("/api/serviceadmin/etl/presets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/serviceadmin/etl/runs/1/files").param("clientId", TENANT)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/serviceadmin/etl/runs/1/mappings/review").param("clientId", TENANT).param("targetTable", "family")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/serviceadmin/etl/runs/1/dictionaries").param("clientId", TENANT).param("targetTable", "family")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/serviceadmin/etl/runs/1/extract").file(new MockMultipartFile("file", "a.csv", "text/csv", "a,b\n1,2".getBytes()))
            .param("clientId", TENANT)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/serviceadmin/etl/runs/1/files").param("clientId", "CHR-unknown").session(admin())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("presets are listed with verification state; a run records preset, date order and AI opt-in")
    void presetsAndRunFields() throws Exception {
        JsonNode p = JSON.readTree(mvc.perform(get("/api/serviceadmin/etl/presets").session(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(p.get("presets")).hasSizeGreaterThanOrEqualTo(6);
        JsonNode breeze = null;
        for (JsonNode n : p.get("presets")) if ("BREEZE".equals(n.get("code").asText())) breeze = n;
        assertThat(breeze).isNotNull();
        assertThat(breeze.get("verified").asBoolean()).isFalse();
        assertThat(breeze.get("dateOrder").asText()).isEqualTo("MDY");
        assertThat(breeze.get("tables").get("family")).isNotNull();

        when(runService.createRun(eq(TENANT), anyString(), any(), eq("BREEZE"), eq("MDY"), eq(true))).thenAnswer(inv -> {
            ImportRun r = run(9, "DRAFT"); r.setAiAssist(true); return r; });
        JsonNode r = JSON.readTree(mvc.perform(post("/api/serviceadmin/etl/runs").session(admin()).contentType("application/json")
            .content("{\"clientId\":\"" + TENANT + "\",\"sourcePreset\":\"BREEZE\",\"dateOrder\":\"MDY\",\"aiAssist\":\"true\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(r.get("run").get("sourcePreset").asText()).isEqualTo("BREEZE");
        assertThat(r.get("run").get("sourcePresetLabel").asText()).isEqualTo("Breeze ChMS");
        assertThat(r.get("run").get("sourcePresetVerified").asBoolean()).isFalse();
        assertThat(r.get("run").get("dateOrder").asText()).isEqualTo("MDY");
        assertThat(r.get("run").get("aiAssist").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("same file for the same church → 409 naming the earlier run; force=true re-imports and marks the record; a discarded run does not block")
    void repeatFileGuard() throws Exception {
        when(runService.getRun(1L, TENANT)).thenReturn(run(1, "DRAFT"));
        when(runService.getRun(2L, TENANT)).thenReturn(run(2, "DRAFT"));
        when(runService.getRun(3L, TENANT)).thenReturn(run(3, "DRAFT"));
        when(extractService.extract(any(), anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> new ExtractResult(inv.getArgument(1), 3, List.of("Breeze ID", "First Name"), "Breeze ID"));
        byte[] csv = "Breeze ID,First Name\n1,Grace\n2,Thomas\n3,Lily\n".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile upload = new MockMultipartFile("file", "people.csv", "text/csv", csv);

        // first upload: accepted and recorded with its hash
        JsonNode first = JSON.readTree(mvc.perform(multipart("/api/serviceadmin/etl/runs/1/extract").file(upload)
            .param("clientId", TENANT).param("targetTable", "family").session(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.get("format").asText()).isEqualTo("csv");
        assertThat(first.get("idColumn").asText()).isEqualTo("Breeze ID");
        assertThat(first.get("file").get("sha256").asText()).hasSize(64);
        assertThat(files).hasSize(1);
        verify(extractService).extract(any(), eq("people"), eq("csv"), any(), eq("family"));

        // same bytes into another run of the same church → refused
        MvcResult dup = mvc.perform(multipart("/api/serviceadmin/etl/runs/2/extract").file(upload)
            .param("clientId", TENANT).session(admin())).andExpect(status().isConflict()).andReturn();
        JsonNode d = JSON.readTree(dup.getResponse().getContentAsString());
        assertThat(d.get("duplicateOf").asLong()).isEqualTo(1);
        assertThat(d.get("message").asText()).contains("run #1").contains("people.csv");
        assertThat(files).hasSize(1);

        // deliberate re-import
        JsonNode forced = JSON.readTree(mvc.perform(multipart("/api/serviceadmin/etl/runs/2/extract").file(upload)
            .param("clientId", TENANT).param("force", "true").session(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(forced.get("file").get("forced").asBoolean()).isTrue();
        assertThat(files).hasSize(2);

        // a different church is never blocked by this church's files
        // an earlier file that belongs to a DISCARDED run does not block
        files.clear();
        ImportFile old = new ImportFile(); old.setId(50L); old.setRunId(3L); old.setClientId(TENANT); old.setSourceTable("x");
        old.setSha256(SourceExtractService.sha256(csv)); old.setFileName("old.csv"); files.add(old);
        when(runService.getRun(3L, TENANT)).thenReturn(run(3, "DISCARDED"));
        mvc.perform(multipart("/api/serviceadmin/etl/runs/1/extract").file(upload)
            .param("clientId", TENANT).session(admin())).andExpect(status().isOk());

        // the same slot of the same run (re-upload of a corrected export) is not a duplicate either
        files.clear();
        ImportFile same = new ImportFile(); same.setId(51L); same.setRunId(1L); same.setClientId(TENANT); same.setSourceTable("people");
        same.setSha256(SourceExtractService.sha256(csv)); same.setFileName("people.csv"); files.add(same);
        mvc.perform(multipart("/api/serviceadmin/etl/runs/1/extract").file(upload)
            .param("clientId", TENANT).session(admin())).andExpect(status().isOk());
    }
}
