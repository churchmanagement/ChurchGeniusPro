package com.churchgeniuspro.voice;

import com.churchgeniuspro.controller.VoiceCommandController;
import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * "Voice limit reached" was shown for EVERY 403 from /api/voice/command (staff-only,
 * per-church feature off, plan) because only the quota refusal carried a status map.
 * Every refusal now carries a reason + status, and the pages show the real reason.
 */
@DisplayName("Voice refusals name their real reason")
class VoiceRefusalReasonTest {

    static MockHttpServletRequest session(String role, Map<String, Object> extra) {
        MockHttpServletRequest r = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u@c.test"); s.setAttribute("role", role); s.setAttribute("appClientId", "CHR-1");
        extra.forEach(s::setAttribute); r.setSession(s); return r;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    @Test @DisplayName("staff-only, feature-off, limit and voice-off refusals each carry their reason code and the usage status")
    void reasons() {
        OpenAiVoiceService voice = mock(OpenAiVoiceService.class);
        OpenAiUsageService usage = mock(OpenAiUsageService.class);
        ChurchVoiceSettingService feats = mock(ChurchVoiceSettingService.class);
        when(voice.isEnabled()).thenReturn(true);
        when(feats.audioEnabled(anyString())).thenReturn(true);
        when(usage.voiceAvailable(anyString())).thenReturn(true);
        Map<String, Object> healthy = Map.of("voiceEnabled", true, "voiceLimitMinutes", 30, "voiceUsedSeconds", 639L, "voiceAvailable", true);
        when(usage.statusMap(anyString())).thenReturn(healthy);
        VoiceCommandController c = new VoiceCommandController(voice, usage, feats);
        MockMultipartFile audio = new MockMultipartFile("audio", "a.webm", "audio/webm", new byte[] { 1 });

        // Sharon Fellowship's case: healthy quota + flags, but a member-portal login
        ResponseEntity<Map<String, Object>> r = c.command(audio, "income", session("Member", Map.of("memberId", 7)));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(body(r)).containsEntry("reason", "STAFF_ONLY").containsEntry("status", healthy);
        assertThat(String.valueOf(body(r).get("error"))).contains("staff").doesNotContain("limit");

        when(feats.audioEnabled(anyString())).thenReturn(false);
        r = c.command(audio, "income", session("Admin", Map.of()));
        assertThat(body(r)).containsEntry("reason", "FEATURE_OFF").containsEntry("status", healthy);
        assertThat(String.valueOf(body(r).get("error"))).doesNotContain("limit");

        when(feats.audioEnabled(anyString())).thenReturn(true);
        when(usage.voiceAvailable(anyString())).thenReturn(false);
        when(usage.statusMap(anyString())).thenReturn(Map.of("voiceEnabled", true, "voiceAvailable", false));
        r = c.command(audio, "income", session("Admin", Map.of()));
        assertThat(body(r)).containsEntry("reason", "LIMIT");
        assertThat(String.valueOf(body(r).get("error"))).contains("Voice limit reached");

        when(usage.statusMap(anyString())).thenReturn(Map.of("voiceEnabled", false, "voiceAvailable", false));
        r = c.command(audio, "income", session("Admin", Map.of()));
        assertThat(body(r)).containsEntry("reason", "VOICE_OFF");
        assertThat(String.valueOf(body(r).get("error"))).contains("disabled by your administrator");
        verify(voice, never()).transcribe(any(), anyString(), anyString());
    }

    @Test @DisplayName("the recorder passes reason + error to the pages, and no page defaults a refusal to 'limit reached' any more")
    void pagesShowTheRealReason() throws IOException {
        String rec = Files.readString(Paths.get("src/main/resources/static/voice-openai.js"));
        assertThat(rec).contains("opts.onDisabled(stat, { reason: res.d.reason || res.d.code || '', error: res.d.error || '' })");
        for (String f : List.of("income-voice.js", "expense-voice.js", "home-voice.js", "voice-nav.js")) {
            String s = Files.readString(Paths.get("src/main/resources/static/" + f));
            assertThat(s).as(f).contains("function voiceRefusalMessage(st, why)")
                    .contains("if (reason === 'LIMIT' ||")
                    .contains("if (why && why.error) return why.error;")
                    .contains("voiceRefusalMessage(st, why)");
            // the generic text survives only inside voiceRefusalMessage (LIMIT) and the on-load quota check
            String handler = s.substring(s.indexOf("onDisabled:"), s.indexOf("\n", s.indexOf("onDisabled:")));
            assertThat(handler).doesNotContain("Voice limit reached");
        }
    }
}
