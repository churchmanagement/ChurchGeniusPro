package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.WhatsAppSettingsController;
import com.churchgeniuspro.hibernate.WhatsAppSettings;
import com.churchgeniuspro.repository.WhatsAppSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WhatsApp turned off for now (user decision 2026-11-06, App Store readiness): the page goes
 * back to the dashboard, the settings API answers 404 and every menu / assistant / reminder
 * entry is hidden. Saved settings are never read or changed while it is off.
 */
class WhatsAppTurnedOffTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static MockHttpServletRequest owner() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("church", true);
        s.setAttribute("username", "owner@church-a");
        s.setAttribute("clientId", "USRchurch-a");
        s.setAttribute("appClientId", "USRchurch-a");
        r.setSession(s);
        return r;
    }

    @Test
    @DisplayName("default is OFF: page redirects, API 404, nothing read or saved")
    void offByDefault() {
        WhatsAppSettingsRepository repo = mock(WhatsAppSettingsRepository.class);
        WhatsAppSettingsController c = new WhatsAppSettingsController(repo);
        assertFalse(c.isEnabled());
        assertEquals("redirect:/home", c.page(owner()));
        assertEquals(404, c.get(owner()).getStatusCode().value());
        assertEquals(404, c.save(Map.of("accountSid", "AC1", "senderPhone", "+1", "authToken", "t"), owner())
                .getStatusCode().value());
        verify(repo, never()).findByClientId(anyString());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("switch on: the owner gets the page and API back (owner-only rule unchanged)")
    void switchOn() {
        WhatsAppSettingsRepository repo = mock(WhatsAppSettingsRepository.class);
        when(repo.findByClientId(anyString())).thenReturn(Optional.of(new WhatsAppSettings()));
        WhatsAppSettingsController c = new WhatsAppSettingsController(repo);
        ReflectionTestUtils.setField(c, "enabled", true);
        assertEquals("forward:/whatsappIntegration.html", c.page(owner()));
        assertEquals(200, c.get(owner()).getStatusCode().value());
    }

    @Test
    @DisplayName("property defaults to false")
    void property() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("cgp.whatsapp.enabled=${CGP_WHATSAPP_ENABLED:false}"));
    }

    @Test
    @DisplayName("menu, assistant, voice and navigation catalogue no longer offer WhatsApp")
    void entriesHidden() throws Exception {
        for (String f : new String[] {"shell.js", "ai-assistant.js", "voice-nav.js"}) {
            String src = Files.readString(STATIC.resolve(f));
            for (String line : src.split("\n")) {
                if (line.contains("/whatsappIntegration") && (line.contains("href:") || line.contains("target:"))) {
                    assertTrue(line.trim().startsWith("//"), f + " still offers WhatsApp: " + line.trim());
                }
            }
        }
        String cat = Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/AiNavigationCatalog.java"));
        for (String line : cat.split("\n")) {
            if (line.contains("\"/whatsappIntegration\"")) assertTrue(line.trim().startsWith("//"), line.trim());
        }
        String help = Files.readString(STATIC.resolve("helpCenter.html"));
        assertTrue(help.contains("<div class=\"hc-card hc-off\" id=\"hc-whatsapp\" "));
        assertTrue(help.contains(".hc-card.hc-off { display: none !important; }"));
    }

    @Test
    @DisplayName("reminder pages: WhatsApp toggle hidden and never sent as true")
    void reminderToggles() throws Exception {
        for (String f : new String[] {"autoReminders.html", "eventReminders.html", "oneReminders.html"}) {
            String src = Files.readString(STATIC.resolve(f));
            assertTrue(src.contains("<label class=\"toggle-row\" for=\"chWhatsApp\" hidden style=\"display:none\""), f);
            assertFalse(src.contains("sendWhatsApp: document.getElementById('chWhatsApp').checked"), f);
            assertFalse(src.contains("sendWhatsApp:   document.getElementById('chWhatsApp').checked"), f);
        }
    }
}
