package com.churchgeniuspro.voice;

import com.churchgeniuspro.controller.VoiceCommandController;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.service.VoiceVocabularyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Scenario 10 (+ the vocabulary hint): the field being filled selects the names sent to
 * the transcriber, the user's locale is applied where the service supports it (an
 * English variety → language "en"), and both travel from the request to the API call.
 */
@DisplayName("Income voice — vocabulary hint and speech locale reach the transcriber")
class VoiceVocabularyAndLocaleTest {

    static FamilyMember member(String first, String last) { FamilyMember m = new FamilyMember(); m.setFirstName(first); m.setLastName(last); return m; }

    VoiceVocabularyService vocab() {
        FamilyMemberRepository members = mock(FamilyMemberRepository.class);
        SubSourceRepository funds = mock(SubSourceRepository.class);
        TransactionTypeRepository methods = mock(TransactionTypeRepository.class);
        when(members.findContributorsByAppUser("CHR-1")).thenReturn(List.of(member("Anson", "Mathew"), member("Hanna", "Thomas")));
        SubSource youth = new SubSource(); youth.setSourceName("Youth Fund");
        when(funds.findAllActiveByAppUser("CHR-1")).thenReturn(List.of(youth));
        TransactionType cash = new TransactionType(); cash.setTypeName("Cash");
        when(methods.findActiveByAppUser("CHR-1")).thenReturn(List.of(cash));
        return new VoiceVocabularyService(members, funds, methods);
    }

    @Test @DisplayName("the hint names the contributors when Contributor is expected, the funds for Fund, and always the Set/Done command words")
    void promptPerField() {
        VoiceVocabularyService v = vocab();
        String c = v.promptFor("CHR-1", "income", "contributor");
        assertThat(c).contains("Anson Mathew").contains("Hanna Thomas").contains("Set Contributor").contains("Done").doesNotContain("Youth Fund");
        String f = v.promptFor("CHR-1", "income", "fund");
        assertThat(f).contains("Youth Fund").doesNotContain("Anson Mathew");
        String all = v.promptFor("CHR-1", "income", "");
        assertThat(all).contains("Anson Mathew").contains("Youth Fund").contains("Cash");
        assertThat(v.promptFor("CHR-1", "home", "contributor")).isNull();
        assertThat(v.promptFor(null, "income", "contributor")).isNull();
    }

    @Test @DisplayName("Expense page: the hint names the expense categories for Expense and the Main Source funds for Fund — never the contributors")
    void expensePrompt() {
        com.churchgeniuspro.repository.PurposeRepository purposes = mock(com.churchgeniuspro.repository.PurposeRepository.class);
        com.churchgeniuspro.repository.MainSourceRepository mains = mock(com.churchgeniuspro.repository.MainSourceRepository.class);
        com.churchgeniuspro.hibernate.Purpose office = new com.churchgeniuspro.hibernate.Purpose(); office.setPurposeName("Office Supplies");
        com.churchgeniuspro.hibernate.Purpose util = new com.churchgeniuspro.hibernate.Purpose(); util.setPurposeName("Utilities");
        when(purposes.findActiveByAppUser("CHR-1")).thenReturn(List.of(office, util));
        com.churchgeniuspro.hibernate.MainSource general = new com.churchgeniuspro.hibernate.MainSource(); general.setSourceName("General Fund");
        when(mains.findActiveByAppUser("CHR-1")).thenReturn(List.of(general));
        FamilyMemberRepository members = mock(FamilyMemberRepository.class);
        when(members.findContributorsByAppUser("CHR-1")).thenReturn(List.of(member("Anson", "Mathew")));
        SubSourceRepository subs = mock(SubSourceRepository.class);
        SubSource youth = new SubSource(); youth.setSourceName("Youth Fund");
        when(subs.findAllActiveByAppUser("CHR-1")).thenReturn(List.of(youth));
        TransactionTypeRepository methods = mock(TransactionTypeRepository.class);
        TransactionType check = new TransactionType(); check.setTypeName("Check");
        when(methods.findActiveByAppUser("CHR-1")).thenReturn(List.of(check));
        VoiceVocabularyService v = new VoiceVocabularyService(members, subs, methods, purposes, mains);

        String e = v.promptFor("CHR-1", "expense", "expense");
        assertThat(e).contains("Set Expense").contains("Office Supplies").contains("Utilities").doesNotContain("Anson").doesNotContain("General Fund");
        String f = v.promptFor("CHR-1", "expense", "fund");
        assertThat(f).contains("General Fund").doesNotContain("Youth Fund").doesNotContain("Office Supplies");
        String all = v.promptFor("CHR-1", "expense", "");
        assertThat(all).contains("Office Supplies").contains("General Fund").contains("Check").doesNotContain("Anson");
        assertThat(v.promptFor("CHR-1", "income", "fund")).contains("Youth Fund").doesNotContain("General Fund");
        assertThat(v.promptFor("CHR-1", "meeting", "location")).as("meetings have no server-side hint").isNull();
    }

    @Test @DisplayName("the hint stays within the transcriber's budget for a large church")
    void promptIsBounded() {
        FamilyMemberRepository members = mock(FamilyMemberRepository.class);
        java.util.List<FamilyMember> many = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) many.add(member("Firstname" + i, "Lastname" + i));
        when(members.findContributorsByAppUser(anyString())).thenReturn(many);
        VoiceVocabularyService v = new VoiceVocabularyService(members, mock(SubSourceRepository.class), mock(TransactionTypeRepository.class));
        String p = v.promptFor("CHR-1", "income", "contributor");
        assertThat(p.length()).isLessThanOrEqualTo(VoiceVocabularyService.MAX_CHARS + 2);
        assertThat(p).contains("Firstname0 Lastname0");
    }

    @Test @DisplayName("every English locale maps to language 'en'; anything else keeps the server default")
    void locale() {
        assertThat(VoiceVocabularyService.languageFor("en-US")).isEqualTo("en");
        assertThat(VoiceVocabularyService.languageFor("en-IN")).isEqualTo("en");
        assertThat(VoiceVocabularyService.languageFor("EN-gb")).isEqualTo("en");
        assertThat(VoiceVocabularyService.languageFor("fr-FR")).isNull();
        assertThat(VoiceVocabularyService.languageFor("")).isNull();
        assertThat(VoiceVocabularyService.languageFor(null)).isNull();
    }

    @Test @DisplayName("/api/voice/command passes expecting + locale through: the transcriber gets the contributor names and 'en'")
    void controllerWiring() {
        OpenAiVoiceService voice = mock(OpenAiVoiceService.class);
        OpenAiUsageService usage = mock(OpenAiUsageService.class);
        ChurchVoiceSettingService feats = mock(ChurchVoiceSettingService.class);
        when(voice.isEnabled()).thenReturn(true);
        when(feats.audioEnabled(anyString())).thenReturn(true);
        when(usage.voiceAvailable(anyString())).thenReturn(true);
        when(usage.statusMap(anyString())).thenReturn(Map.of("voiceEnabled", true, "voiceAvailable", true));
        when(voice.transcribe(any(), anyString(), anyString(), any(), any())).thenReturn(new OpenAiVoiceService.Transcription("Anson Mathew", 1.4, 0.9));
        java.util.Map<String, Object> ex = new java.util.LinkedHashMap<>(); ex.put("fields", Map.of("contributor", "Anson Mathew")); ex.put("command", null);
        when(voice.extractFields(anyString(), anyString())).thenReturn(ex);
        VoiceCommandController c = new VoiceCommandController(voice, usage, feats);
        c.setVocabulary(vocab());

        MockHttpServletRequest r = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u@c.test"); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", "CHR-1"); r.setSession(s);
        MockMultipartFile audio = new MockMultipartFile("audio", "a.webm", "audio/webm", new byte[] { 1 });
        ResponseEntity<Map<String, Object>> resp = c.command(audio, "income", "contributor", "en-IN", r);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class), lang = ArgumentCaptor.forClass(String.class);
        verify(voice).transcribe(any(), anyString(), anyString(), prompt.capture(), lang.capture());
        assertThat(prompt.getValue()).contains("Anson Mathew").contains("Set Contributor");
        assertThat(lang.getValue()).isEqualTo("en");
        assertThat(resp.getBody()).containsEntry("expecting", "contributor").containsEntry("locale", "en-IN").containsEntry("transcript", "Anson Mathew");
        verify(usage).addVoiceSeconds("CHR-1", 1L);          // scenario 12: still metered
    }
}
