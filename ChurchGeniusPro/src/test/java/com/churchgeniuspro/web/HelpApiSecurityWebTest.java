package com.churchgeniuspro.web;

import com.churchgeniuspro.controller.HelpController;
import com.churchgeniuspro.service.HelpAssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security test for the Help API: protected endpoints require a session, and the
 * permission-filtered article list is only returned to authenticated users.
 * Built with a standalone MockMvc (no autoconfigure slice).
 */
class HelpApiSecurityWebTest {

    private MockMvc mvc;
    private HelpAssistantService helpAssistant;

    @BeforeEach
    void setup() {
        helpAssistant = mock(HelpAssistantService.class);
        mvc = MockMvcBuilders.standaloneSetup(new HelpController(helpAssistant)).build();
    }

    @Test
    void articles_withoutSession_is401() throws Exception {
        mvc.perform(get("/api/help/articles"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void articles_withSession_returnsPermissionFilteredList() throws Exception {
        when(helpAssistant.articles(any(), any(), anyBoolean()))
            .thenReturn(List.of(Map.of("id", "members", "title", "Managing Members & Families")));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "alice");
        session.setAttribute("appClientId", "CLIENT-1");
        session.setAttribute("role", "User");

        mvc.perform(get("/api/help/articles").session(session))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("members"));
    }
}
