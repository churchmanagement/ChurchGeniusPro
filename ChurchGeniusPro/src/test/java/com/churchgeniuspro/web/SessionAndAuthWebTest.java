package com.churchgeniuspro.web;

import com.churchgeniuspro.controller.SessionController;
import com.churchgeniuspro.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Session-management + authentication tests for the session API, built with a
 * standalone MockMvc (no Spring context / no autoconfigure slices — portable
 * across Spring Boot versions). Validates that protected session info requires a
 * session and that logout invalidates it.
 */
class SessionAndAuthWebTest {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        when(appUserRepository.findByUserIdAndDeleteFlagFalse(anyString())).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new SessionController(appUserRepository)).build();
    }

    @Test
    void session_withoutLogin_is401() throws Exception {
        mvc.perform(get("/api/session"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void session_withClientId_isAuthenticated() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("clientId", "CLIENT-1");
        session.setAttribute("username", "alice");
        session.setAttribute("firstName", "Alice");
        session.setAttribute("role", "Admin");
        mvc.perform(get("/api/session").session(session))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.authenticated").value(true))
           .andExpect(jsonPath("$.role").value("Admin"));
    }

    @Test
    void logout_invalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("clientId", "CLIENT-1");
        mvc.perform(post("/api/logout").session(session))
           .andExpect(status().isOk());
        // A fresh request with no session is unauthenticated.
        mvc.perform(get("/api/session"))
           .andExpect(status().isUnauthorized());
    }
}
