package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.write.AiFormDraftService;
import com.churchgeniuspro.ai.write.AiWritingService;
import com.churchgeniuspro.ai.write.AiWritingService.Request;
import com.churchgeniuspro.config.SubscriptionPlanSeeder;
import com.churchgeniuspro.controller.AiFormBuilderController;
import com.churchgeniuspro.controller.AiWritingController;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AI Phase 3 — writing drafts and the form builder: validation, prompts, sanitiser, closed form schema, gates, never-sends. */
@DisplayName("AI Phase 3 — AI writing + form builder")
class AiWritingPhase3Test {

    static final ObjectMapper M = new ObjectMapper();
    OpenAiVoiceService openAi; AiWritingService writing; AiFormDraftService forms; MockMvc mvc;

    @BeforeEach
    void setUp() {
        openAi = mock(OpenAiVoiceService.class);
        when(openAi.isEnabled()).thenReturn(true);
        writing = new AiWritingService(openAi);
        forms = new AiFormDraftService(openAi);
        mvc = MockMvcBuilders.standaloneSetup(new AiWritingController(writing), new AiFormBuilderController(forms)).build();
    }

    static MockHttpSession session(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pat"); s.setAttribute("role", role); s.setAttribute("appClientId", "CHR-1");
        if (privileges != null) s.setAttribute("privileges", privileges);
        return s;
    }
    static JsonNode json(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test @DisplayName("request validation: allow-lists for action / channel / tone / length / language, size caps, draft needs a prompt, the others need text")
    void validation() {
        Request r = AiWritingService.validate(Map.of("action", "Draft", "channel", "SMS", "prompt", "remind ushers", "tone", "Warm", "language", "Spanish"));
        assertThat(r.action()).isEqualTo("draft"); assertThat(r.channel()).isEqualTo("sms"); assertThat(r.length()).isEqualTo("short"); assertThat(r.language()).isEqualTo("Spanish");
        assertThat(AiWritingService.validate(Map.of("action", "draft", "prompt", "x")).length()).isEqualTo("medium");
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("action", "send", "prompt", "x"), Map.of("action", "draft", "channel", "whatsapp", "prompt", "x"),
                Map.of("action", "draft", "prompt", "x", "tone", "angry"), Map.of("action", "draft", "prompt", "x", "length", "huge"),
                Map.of("action", "draft", "prompt", "x", "language", "Klingon"), Map.of("action", "draft", "prompt", ""),
                Map.of("action", "rewrite", "prompt", "x"), Map.of("action", "draft", "prompt", "x".repeat(2001)),
                Map.of("action", "fix", "text", "y".repeat(8001))))
            assertThatThrownBy(() -> AiWritingService.validate(bad)).as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiWritingService.validate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("prompts: SMS 160-char rule, no STOP wording, age-neutral rule, no invented facts, JSON keys; the model sees only prompt + text")
    void prompts() {
        Request sms = AiWritingService.validate(Map.of("action", "draft", "channel", "sms", "prompt", "remind ushers about Saturday 9am training", "churchName", "Grace Church"));
        String sys = writing.systemPrompt(sms);
        assertThat(sys).contains("TEXT MESSAGE").contains("160 characters").contains("never add STOP").contains("Never mention anyone's age").contains("Never invent facts").contains("\"body\"").contains("Grace Church").contains("English");
        assertThat(sys).doesNotContain("\"subject\"");
        Request email = AiWritingService.validate(Map.of("action", "draft", "channel", "email", "prompt", "volunteer thank-you", "wantSubject", true, "length", "long", "tone", "formal"));
        assertThat(writing.systemPrompt(email)).contains("E-MAIL body").contains("220–300 words").contains("formal").contains("\"subject\"");
        assertThat(writing.userPrompt(email)).isEqualTo("Write the message. Request: volunteer thank-you");
        Request tr = AiWritingService.validate(Map.of("action", "translate", "text", "See you Sunday!", "language", "Spanish", "prompt", "keep it short"));
        assertThat(writing.userPrompt(tr)).contains("Translate the following text into Spanish").contains("Extra guidance from the staff member: keep it short").endsWith("TEXT:\nSee you Sunday!");
        assertThat(writing.userPrompt(AiWritingService.validate(Map.of("action", "shorten", "channel", "sms", "text", "long text")))).contains("at most 160 characters");

        // the call: temperature 0.6 for drafts, 0.1 for fix; what reaches the model is exactly system + user prompt
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"subject\":\"Thank you!\",\"body\":\"Dear team,\\n\\nThank you.\"}"));
        var d = writing.write(email);
        ArgumentCaptor<Double> temp = ArgumentCaptor.forClass(Double.class);
        verify(openAi).chatJson(eq(writing.systemPrompt(email)), eq(writing.userPrompt(email)), temp.capture());
        assertThat(temp.getValue()).isEqualTo(0.6);
        assertThat(d.subject()).isEqualTo("Thank you!"); assertThat(d.body()).isEqualTo("Dear team,\n\nThank you."); assertThat(d.chars()).isEqualTo(d.body().length()); assertThat(d.note()).isNull();
        writing.write(AiWritingService.validate(Map.of("action", "fix", "text", "teh")));
        verify(openAi).chatJson(anyString(), anyString(), eq(0.1));
        // SMS over 160 → note; model off → null
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"body\":\"" + "x".repeat(200) + "\"}"));
        assertThat(writing.write(sms).note()).contains("Over 160");
        when(openAi.isEnabled()).thenReturn(false);
        assertThat(writing.write(sms)).isNull();
    }

    @Test @DisplayName("output sanitiser: tags (plain and entity-encoded) stripped, control characters removed, caps applied; empty body → no draft")
    void sanitiser() {
        assertThat(AiWritingService.sanitize("<p>Hi <b>there</b></p><script>alert(1)</script>", 100)).isEqualTo("Hi there");
        assertThat(AiWritingService.sanitize("&lt;img src=x onerror=alert(1)&gt;Hello &amp; welcome", 100)).isEqualTo("Hello & welcome");
        assertThat(AiWritingService.sanitize("a\u0007b\r\nc\n\n\n\n\nd", 100)).isEqualTo("ab\nc\n\nd");
        assertThat(AiWritingService.sanitize("x".repeat(50), 10)).hasSize(10);
        assertThat(AiWritingService.sanitize(null, 10)).isEmpty();
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"body\":\"<script>x</script>\"}"));
        assertThat(writing.write(AiWritingService.validate(Map.of("action", "draft", "prompt", "x")))).as("only markup → nothing usable").isNull();
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"body\":\"ok\",\"subject\":\"<b>Hi</b>\\nthere\"}"));
        assertThat(writing.write(AiWritingService.validate(Map.of("action", "draft", "prompt", "x", "wantSubject", true))).subject()).isEqualTo("Hi there");
    }

    @Test @DisplayName("form schema: valid draft passes with normalised values; unknown event field, unknown question type, bad date/time, options on a text field, end before start → refused")
    void formSchema() {
        Map<String, Object> ok = AiFormDraftService.validate(json("{\"event\":{\"eventName\":\"VBS 2027\",\"eventType\":\"Multiple Days\",\"eventDate\":\"2027-06-09\",\"endDate\":\"2027-06-13\",\"startTime\":\"09:00\",\"endTime\":\"12:00\","
                + "\"fee\":\"Free\",\"maxCapacity\":80,\"foodAvailable\":true,\"foodOptions\":[\"Snack\",\"No snack\"],\"note\":\"<b>Bring</b> a water bottle\"},"
                + "\"questions\":[{\"type\":\"text\",\"label\":\"Child's name\",\"required\":true},{\"type\":\"textarea\",\"label\":\"Allergies\"},{\"type\":\"phone\",\"label\":\"Parent phone\",\"required\":true},"
                + "{\"type\":\"yesno\",\"label\":\"Pickup authorisation\"},{\"type\":\"select\",\"label\":\"T-shirt size\",\"options\":[\"S\",\"M\",\"L\"]}],\"unsupported\":[\"waiver signature\"]}"));
        @SuppressWarnings("unchecked") Map<String, Object> ev = (Map<String, Object>) ok.get("event");
        assertThat(ev).containsEntry("eventName", "VBS 2027").containsEntry("maxCapacity", 80).containsEntry("foodLabel", "Dietary Preferences").containsEntry("note", "Bring a water bottle");
        @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) ok.get("questions");
        assertThat(qs).hasSize(5);
        assertThat(qs.get(4)).containsEntry("options", List.of("S", "M", "L"));
        assertThat(ok.get("unsupported")).isEqualTo(List.of("waiver signature"));
        assertThat(AiFormDraftService.questionsAsNote(qs)).startsWith("Registration questions:\n• Child's name (required)").contains("• T-shirt size: S / M / L");

        for (String bad : List.of(
                "{\"event\":{\"eventName\":\"x\",\"price\":\"$5\"}}",                                  // unknown event field
                "{\"questions\":[{\"type\":\"file\",\"label\":\"Photo\"}]}",                            // unknown field type
                "{\"questions\":[{\"type\":\"signature\",\"label\":\"Sign\"}]}",
                "{\"event\":{\"eventDate\":\"June 9\"}}",                                              // bad date
                "{\"event\":{\"startTime\":\"9am\"}}",                                                 // bad time
                "{\"event\":{\"eventType\":\"Weekly\"}}",
                "{\"event\":{\"maxCapacity\":\"eighty\"}}",
                "{\"event\":{\"eventType\":\"Multiple Days\",\"eventDate\":\"2027-06-13\",\"endDate\":\"2027-06-09\"}}",
                "{\"questions\":[{\"type\":\"text\",\"label\":\"Size\",\"options\":[\"S\",\"M\"]}]}",  // options on a text field
                "{\"questions\":[{\"type\":\"select\",\"label\":\"Size\",\"options\":[\"S\"]}]}",      // one option
                "{\"questions\":[{\"type\":\"text\",\"label\":\"\"}]}",
                "[]"))
            assertThatThrownBy(() -> AiFormDraftService.validate(json(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);

        // the service refuses invalid model output (null) and never throws for it; description limits
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"questions\":[{\"type\":\"file\",\"label\":\"x\"}]}"));
        assertThat(forms.draft("a form", LocalDate.of(2026, 11, 4))).isNull();
        assertThatThrownBy(() -> forms.draft("", LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> forms.draft("x".repeat(2001), LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(AiFormDraftService.systemPrompt(LocalDate.of(2026, 11, 4))).contains("Today is 2026-11-04").contains("text|textarea|email|phone|number|date|yesno|checkbox|select|radio|section").contains("Never include personal data");
    }

    @Test @DisplayName("endpoints: 401 / 403 (member, kids, church, temp, AI Assistant off), 400 on bad input, 503 without a key, 502 when the model fails, 200 draft; validate endpoint 400 on unknown type")
    void endpoints() throws Exception {
        String ok = "{\"action\":\"draft\",\"channel\":\"email\",\"prompt\":\"thank the volunteers\",\"wantSubject\":true}";
        mvc.perform(post("/api/ai/write").contentType("application/json").content(ok)).andExpect(status().isUnauthorized());
        MockHttpSession member = session("Member", null); member.setAttribute("memberId", 5);
        mvc.perform(post("/api/ai/write").session(member).contentType("application/json").content(ok)).andExpect(status().isForbidden());
        MockHttpSession church = session("SuperAdmin", null); church.setAttribute("church", true);
        mvc.perform(post("/api/ai/write").session(church).contentType("application/json").content(ok)).andExpect(status().isForbidden());
        MockHttpSession temp = session("User", null); temp.setAttribute("tempAccessId", 3);
        mvc.perform(post("/api/ai/write").session(temp).contentType("application/json").content(ok)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/write").session(session("Admin", "{\"more.aiassistant\":false}")).contentType("application/json").content(ok)).andExpect(status().isForbidden());
        verifyNoInteractions(openAi);

        MockHttpSession user = session("User", null);
        mvc.perform(post("/api/ai/write").session(user).contentType("application/json").content("{\"action\":\"send\",\"prompt\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai/write").session(user).contentType("application/json").content("{\"action\":\"draft\",\"prompt\":\"\"}")).andExpect(status().isBadRequest());
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(null);
        mvc.perform(post("/api/ai/write").session(user).contentType("application/json").content(ok)).andExpect(status().is(502));
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"subject\":\"Thanks\",\"body\":\"Dear volunteers,\\n\\nThank you.\"}"));
        mvc.perform(post("/api/ai/write").session(user).contentType("application/json").content(ok)).andExpect(status().isOk())
           .andExpect(jsonPath("$.subject").value("Thanks")).andExpect(jsonPath("$.body").value("Dear volunteers,\n\nThank you.")).andExpect(jsonPath("$.draft").value(true)).andExpect(jsonPath("$.chars").value(28));
        when(openAi.isEnabled()).thenReturn(false);
        mvc.perform(post("/api/ai/write").session(user).contentType("application/json").content(ok)).andExpect(status().is(503));
        when(openAi.isEnabled()).thenReturn(true);

        // forms
        mvc.perform(post("/api/ai/forms/draft").contentType("application/json").content("{\"description\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai/forms/draft").session(member).contentType("application/json").content("{\"description\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/forms/draft").session(user).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"event\":{\"eventName\":\"VBS\",\"eventDate\":\"2027-06-09\"},\"questions\":[{\"type\":\"text\",\"label\":\"Child's name\",\"required\":true}]}"));
        mvc.perform(post("/api/ai/forms/draft").session(user).contentType("application/json").content("{\"description\":\"VBS June 9\"}")).andExpect(status().isOk())
           .andExpect(jsonPath("$.event.eventName").value("VBS")).andExpect(jsonPath("$.questions[0].type").value("text")).andExpect(jsonPath("$.draft").value(true));
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"questions\":[{\"type\":\"file\",\"label\":\"x\"}]}"));
        mvc.perform(post("/api/ai/forms/draft").session(user).contentType("application/json").content("{\"description\":\"VBS\"}")).andExpect(status().is(502));
        mvc.perform(post("/api/ai/forms/validate").session(user).contentType("application/json").content("{\"questions\":[{\"type\":\"file\",\"label\":\"x\"}]}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid").value(false));
        mvc.perform(post("/api/ai/forms/validate").session(user).contentType("application/json").content("{\"questions\":[{\"type\":\"text\",\"label\":\"x\"}]}")).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }

    @Test @DisplayName("architecture: the AI writing / form classes never touch EmailService, SmsService or WhatsApp; both paths are plan-gated (aiWrite) and audited; FREE/STANDARD seeds carry aiWrite:false and old plans get it once")
    void neverSendsAndGated() throws Exception {
        for (String f : List.of("src/main/java/com/churchgeniuspro/ai/write/AiWritingService.java", "src/main/java/com/churchgeniuspro/ai/write/AiFormDraftService.java",
                                "src/main/java/com/churchgeniuspro/controller/AiWritingController.java", "src/main/java/com/churchgeniuspro/controller/AiFormBuilderController.java",
                                "src/main/resources/static/ai-write.js")) {
            // comments may NAME the forbidden classes (to say they are never used); code may not
            String src = Files.readString(Path.of(f)).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            assertThat(src).as(f).doesNotContain("EmailService").doesNotContain("SmsService").doesNotContain("WhatsApp").doesNotContain("sendEmail").doesNotContain("sendSms");
        }
        assertThat(Files.readString(Path.of("src/main/resources/static/ai-write.js"))).doesNotContain("/api/notify").doesNotContain("/send");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/ai/write")).isEqualTo("aiWrite");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/ai/forms/draft")).isEqualTo("aiWrite");
        assertThat(AiAuditService.channelOf("/api/ai/write")).isEqualTo("WRITE");
        assertThat(AiAuditService.channelOf("/api/ai/forms/draft")).isEqualTo("FORMS");

        // seeder: old FREE/STANDARD rows gain aiWrite:false once; an explicit value is never changed; PRO untouched
        SubscriptionPlanRepository repo = mock(SubscriptionPlanRepository.class);
        SubscriptionPlan free = new SubscriptionPlan(); free.setPlanCode("FREE"); free.setFeaturesJson("{\"accounting\":false}");
        SubscriptionPlan std = new SubscriptionPlan(); std.setPlanCode("STANDARD"); std.setFeaturesJson("{\"aiWrite\":true}");
        SubscriptionPlan empty = new SubscriptionPlan(); empty.setPlanCode("X"); empty.setFeaturesJson("{}");
        when(repo.findByPlanCodeIgnoreCase("FREE")).thenReturn(Optional.of(free));
        when(repo.findByPlanCodeIgnoreCase("STANDARD")).thenReturn(Optional.of(std));
        when(repo.findByPlanCodeIgnoreCase("X")).thenReturn(Optional.of(empty));
        SubscriptionPlanSeeder.denyFeatureOnce(repo, "FREE", "aiWrite");
        SubscriptionPlanSeeder.denyFeatureOnce(repo, "STANDARD", "aiWrite");
        SubscriptionPlanSeeder.denyFeatureOnce(repo, "X", "aiWrite");
        assertThat(free.getFeaturesJson()).isEqualTo("{\"accounting\":false,\"aiWrite\":false}");
        assertThat(M.readTree(free.getFeaturesJson()).get("aiWrite").asBoolean()).isFalse();
        assertThat(std.getFeaturesJson()).isEqualTo("{\"aiWrite\":true}");
        assertThat(empty.getFeaturesJson()).isEqualTo("{\"aiWrite\":false}");
        verify(repo, times(2)).save(any());
    }
}
