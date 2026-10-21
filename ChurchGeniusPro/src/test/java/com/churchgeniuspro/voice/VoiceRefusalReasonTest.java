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
        // ai-assistant.js is the LIVE AI Search box on every page (home-voice.js / home-assist.js
        // are retired) and meeting-voice.js the meeting assistant — both were missed by the
        // 2026-10-07 fix and kept saying "disabled or limit reached" for every refusal.
        for (String f : List.of("income-voice.js", "expense-voice.js", "home-voice.js", "voice-nav.js",
                                "ai-assistant.js", "meeting-voice.js")) {
            String s = Files.readString(Paths.get("src/main/resources/static/" + f));
            assertThat(s).as(f).contains("function voiceRefusalMessage(st, why)")
                    .contains("if (reason === 'LIMIT' ||")
                    .contains("if (why && why.error) return why.error;")
                    .contains("voiceRefusalMessage(st, why)");
            // the generic text survives only inside voiceRefusalMessage (LIMIT) and the on-load quota check
            String handler = s.substring(s.indexOf("onDisabled:"), s.indexOf("\n", s.indexOf("onDisabled:")));
            assertThat(handler).doesNotContain("Voice limit reached").doesNotContain("limit reached");
        }
    }

    @Test @DisplayName("a staff login whose own member record is linked on the session (memberId set) is still staff — voice is allowed")
    void staffWithLinkedMemberRecordIsStaff() {
        // Sharon Fellowship, 2026-10-21: SuperAdmin, 12.7 of 30 minutes used, every flag on,
        // yet every voice request was refused STAFF_ONLY because Give/Contribute had linked the
        // admin's own member row onto the staff session (MemberSessionResolver.resolve step 3).
        assertThat(VoiceCommandController.isStaffSession(session("SuperAdmin", Map.of("memberId", 42, "memberRef", "MBR-x")))).isTrue();
        assertThat(VoiceCommandController.isStaffSession(session("Admin", Map.of()))).isTrue();
        assertThat(VoiceCommandController.isStaffSession(session("User", Map.of("memberId", 42)))).isTrue();
        // the two kinds of session that really are not staff
        assertThat(VoiceCommandController.isStaffSession(session("Member", Map.of("memberId", 7)))).isFalse();
        assertThat(VoiceCommandController.isStaffSession(session("SuperAdmin", Map.of("tempAccessId", 3L)))).isFalse();

        OpenAiVoiceService voice = mock(OpenAiVoiceService.class);
        OpenAiUsageService usage = mock(OpenAiUsageService.class);
        ChurchVoiceSettingService feats = mock(ChurchVoiceSettingService.class);
        when(voice.isEnabled()).thenReturn(true);
        when(feats.audioEnabled(anyString())).thenReturn(true);
        when(usage.voiceAvailable(anyString())).thenReturn(true);
        when(usage.statusMap(anyString())).thenReturn(Map.of("voiceEnabled", true, "voiceAvailable", true));
        when(voice.transcribe(any(), anyString(), anyString())).thenReturn(new OpenAiVoiceService.Transcription("open the members page", 3.2, 0.9));
        when(voice.toCommand(anyString(), anyString())).thenReturn("go to members");
        VoiceCommandController c = new VoiceCommandController(voice, usage, feats);
        MockMultipartFile audio = new MockMultipartFile("audio", "a.webm", "audio/webm", new byte[] { 1 });

        ResponseEntity<Map<String, Object>> r = c.command(audio, "home", session("SuperAdmin", Map.of("memberId", 42)));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(body(r)).containsEntry("transcript", "open the members page");
        verify(usage).addVoiceSeconds("CHR-1", 3L);
    }

    @Test @DisplayName("the quota gate and the Service Admin numbers come from the same arithmetic: 12.7 of 30 minutes is available")
    void quotaMathMatchesServiceAdmin() {
        com.churchgeniuspro.hibernate.OpenAiUsage u = new com.churchgeniuspro.hibernate.OpenAiUsage();
        u.setClientId("CHR-1"); u.setVoiceEnabled(true); u.setVoiceLimitMinutes(30); u.setVoiceUsedSeconds(762L); // 12.7 min
        com.churchgeniuspro.repository.OpenAiUsageRepository repo = mock(com.churchgeniuspro.repository.OpenAiUsageRepository.class);
        when(repo.findByClientId("CHR-1")).thenReturn(java.util.Optional.of(u));
        OpenAiUsageService usage = new OpenAiUsageService(repo);

        assertThat(usage.voiceAvailable("CHR-1")).isTrue();
        Map<String, Object> admin = usage.adminMap("CHR-1");
        assertThat(Math.round(((Number) admin.get("voiceUsedSeconds")).doubleValue() / 60 * 10) / 10.0).isEqualTo(12.7); // what the page prints
        assertThat(admin).containsEntry("voiceLimitMinutes", 30).containsEntry("voiceAvailable", true)
                         .containsEntry("voiceRemainingSeconds", 30L * 60 - 762);

        // and the gate only closes when the same numbers say so
        u.setVoiceUsedSeconds(30L * 60);
        assertThat(usage.voiceAvailable("CHR-1")).isFalse();
        assertThat(usage.statusMap("CHR-1")).containsEntry("voiceAvailable", false).containsEntry("voiceRemainingSeconds", 0L);
        u.setVoiceUsedSeconds(762L); u.setVoiceEnabled(false);
        assertThat(usage.voiceAvailable("CHR-1")).isFalse();
        assertThat(usage.statusMap("CHR-1")).containsEntry("voiceEnabled", false);
    }
}
