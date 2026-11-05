package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilterService;
import com.churchgeniuspro.controller.AiPeopleFilterController;
import com.churchgeniuspro.hibernate.AiSavedPeopleList;
import com.churchgeniuspro.repository.AiSavedPeopleListRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AI Phase 1 — the saved-lists / chip-edit API: session and role gates, the AI Assistant permission,
 * tenant-scoped reads, validation of the filter JSON, and the "save only what you may run" rule.
 */
@DisplayName("AI Phase 1 — AiPeopleFilterController gates and validation")
class AiPeopleFilterControllerTest {

    PeopleFilterService filters; AiSavedPeopleListRepository repo; MockMvc mvc;

    @BeforeEach
    void setUp() {
        filters = mock(PeopleFilterService.class);
        repo = mock(AiSavedPeopleListRepository.class);
        when(filters.run(any(), any(), any(), anyString(), any())).thenReturn(Map.of("handled", true, "denied", false, "intent", PeopleFilterService.INTENT,
                "answer", "1 person matches", "columns", List.of("Name", "Role"), "rows", List.of(List.of("=Ann Lee", "Adult")), "total", 1L, "filter", Map.of()));
        when(filters.run(any(), any(), any(), anyString(), any(), anyInt())).thenReturn(Map.of("handled", true, "denied", false, "intent", PeopleFilterService.INTENT,
                "answer", "1 person matches", "columns", List.of("Name", "Role"), "rows", List.of(List.of("=Ann Lee", "Adult")), "total", 1L, "filter", Map.of()));
        when(repo.save(any())).thenAnswer(i -> { AiSavedPeopleList l = i.getArgument(0); if (l.getId() == null) l.setId(7L); return l; });
        mvc = MockMvcBuilders.standaloneSetup(new AiPeopleFilterController(filters, new AiAccessPolicy(), repo)).build();
    }

    static MockHttpSession session(String role, String clientId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pat"); s.setAttribute("role", role); s.setAttribute("appClientId", clientId);
        return s;
    }
    static final String PREDS = "[{\"kind\":\"Gender\",\"gender\":\"Female\"},{\"kind\":\"InGroup\",\"negated\":true}]";

    @Test @DisplayName("no session 401; Member / Kids / temp sessions 403; a User may edit chips but not use Saved Lists")
    void gates() throws Exception {
        mvc.perform(post("/api/ai-search/filter").contentType("application/json").content("{\"predicates\":" + PREDS + "}")).andExpect(status().isUnauthorized());
        MockHttpSession member = session("Member", "CHR-1"); member.setAttribute("memberId", 5);
        mvc.perform(post("/api/ai-search/filter").session(member).contentType("application/json").content("{\"predicates\":" + PREDS + "}")).andExpect(status().isForbidden());
        MockHttpSession temp = session("User", "CHR-1"); temp.setAttribute("tempAccessId", 3);
        mvc.perform(get("/api/ai-search/saved-lists").session(temp)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai-search/filter").session(session("User", "CHR-1")).contentType("application/json").content("{\"predicates\":" + PREDS + "}")).andExpect(status().isOk());
        mvc.perform(get("/api/ai-search/saved-lists").session(session("User", "CHR-1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai-search/saved-lists").session(session("Accountant", "CHR-1"))).andExpect(status().isOk());
        // AI Assistant permission switched off
        MockHttpSession noAi = session("Admin", "CHR-1"); noAi.setAttribute("privileges", "{\"more.aiassistant\":false}");
        mvc.perform(post("/api/ai-search/filter").session(noAi).contentType("application/json").content("{\"predicates\":" + PREDS + "}")).andExpect(status().isForbidden());
        // Saved Lists permission switched off
        MockHttpSession noLists = session("Admin", "CHR-1"); noLists.setAttribute("privileges", "{\"more.savedlists\":false}");
        mvc.perform(get("/api/ai-search/saved-lists").session(noLists)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai-search/filter").session(noLists).contentType("application/json").content("{\"predicates\":" + PREDS + "}")).andExpect(status().isOk());
    }

    @Test @DisplayName("filter JSON is validated: unknown kind, bad value, missing list → 400 and the service is never called")
    void validation() throws Exception {
        MockHttpSession s = session("Admin", "CHR-1");
        mvc.perform(post("/api/ai-search/filter").session(s).contentType("application/json").content("{\"predicates\":[{\"kind\":\"DropTable\"}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai-search/filter").session(s).contentType("application/json").content("{\"predicates\":[{\"kind\":\"Status\",\"status\":\"x' OR 1=1\"}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai-search/filter").session(s).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai-search/saved-lists").session(s).contentType("application/json").content("{\"name\":\"\",\"predicates\":" + PREDS + "}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai-search/saved-lists").session(s).contentType("application/json").content("{\"name\":\"Empty\",\"predicates\":[]}")).andExpect(status().isBadRequest());
        verify(filters, never()).run(any(), any(), any(), anyString(), any());
    }

    @Test @DisplayName("create stores the filter under the session's church; list/run/delete read only that church; 404 for another church's id")
    void tenantScoped() throws Exception {
        MockHttpSession s = session("Admin", "CHR-1");
        mvc.perform(post("/api/ai-search/saved-lists").session(s).contentType("application/json").content("{\"name\":\"Women not in a group\",\"predicates\":" + PREDS + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(7)).andExpect(jsonPath("$.labels[0]").value("Women")).andExpect(jsonPath("$.labels[1]").value("Not in a group"));
        ArgumentCaptor<AiSavedPeopleList> cap = ArgumentCaptor.forClass(AiSavedPeopleList.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getAppClientId()).isEqualTo("CHR-1");
        assertThat(cap.getValue().getCreatedBy()).isEqualTo("pat");
        assertThat(PeopleFilter.fromJson(new com.fasterxml.jackson.databind.ObjectMapper().readValue(cap.getValue().getFilterJson(), List.class)).predicates()).hasSize(2);

        AiSavedPeopleList mine = cap.getValue();
        when(repo.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc("CHR-1")).thenReturn(List.of(mine));
        when(repo.findByIdAndAppClientIdAndDeleteFlagFalse(7L, "CHR-1")).thenReturn(Optional.of(mine));
        when(repo.findByIdAndAppClientIdAndDeleteFlagFalse(7L, "CHR-2")).thenReturn(Optional.empty());
        mvc.perform(get("/api/ai-search/saved-lists").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.lists[0].name").value("Women not in a group"));
        mvc.perform(post("/api/ai-search/saved-lists/7/run").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.intent").value(PeopleFilterService.INTENT)).andExpect(jsonPath("$.list.lastCount").value(1));
        mvc.perform(post("/api/ai-search/saved-lists/7/run").session(session("Admin", "CHR-2"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/ai-search/saved-lists/7").session(session("Admin", "CHR-2"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/ai-search/saved-lists/7/export").session(s)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Women_not_in_a_group.csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'=Ann Lee,Adult")));   // formula guard
        mvc.perform(delete("/api/ai-search/saved-lists/7").session(s)).andExpect(status().isOk());
        assertThat(mine.isDeleteFlag()).isTrue();
    }

    @Test @DisplayName("a list can only be saved when the session may run it; duplicate names and the per-church cap are refused")
    void saveRules() throws Exception {
        MockHttpSession user = session("User", "CHR-1");     // no Families access → Members module missing
        // Users cannot reach Saved Lists at all (role gate), so use an Accountant lacking Groups
        MockHttpSession acct = session("Accountant", "CHR-1");
        mvc.perform(post("/api/ai-search/saved-lists").session(acct).contentType("application/json").content("{\"name\":\"x\",\"predicates\":" + PREDS + "}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("allowed to run")));
        MockHttpSession admin = session("Admin", "CHR-1");
        when(repo.existsByAppClientIdAndDeleteFlagFalseAndNameIgnoreCase("CHR-1", "dup")).thenReturn(true);
        mvc.perform(post("/api/ai-search/saved-lists").session(admin).contentType("application/json").content("{\"name\":\"dup\",\"predicates\":" + PREDS + "}")).andExpect(status().isConflict());
        when(repo.countByAppClientIdAndDeleteFlagFalse("CHR-1")).thenReturn(200L);
        mvc.perform(post("/api/ai-search/saved-lists").session(admin).contentType("application/json").content("{\"name\":\"New\",\"predicates\":" + PREDS + "}")).andExpect(status().isConflict());
        assertThat(user.getAttribute("role")).isEqualTo("User");
    }
}
