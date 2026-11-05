package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.write.AiFormDraftService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.workflow.WorkflowRuleDrafter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 2026-11-05: model replies with harmless format slips were refusing whole drafts (forms 502, rules "priority must be …"). */
class AiDraftNormalizationTest {

    static final ObjectMapper M = new ObjectMapper();
    static JsonNode json(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test @DisplayName("forms: blanks, numbers-as-text, 9 AM, 'one day', fee 0 and unknown keys no longer sink the draft")
    @SuppressWarnings("unchecked")
    void formDraftRepairsModelSlips() {
        OpenAiVoiceService ai = mock(OpenAiVoiceService.class);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("""
            {"event":{"eventName":"Vacation Bible School","eventType":"multiple days","eventDate":"2027-06-08","endDate":"2027-06-12T00:00:00",
                      "startTime":"9 AM","endTime":"12:00 pm","fee":0,"maxCapacity":"60","hostName":"","hostPhone":null,"registrationEndDate":"",
                      "foodAvailable":"no","foodOptions":[],"location":"Fellowship Hall","note":"Ages 4-12"},
             "questions":[{"type":"short text","label":"Child's name","required":"yes"},
                          {"type":"dropdown","label":"T-shirt size","options":["S","M","L"]},
                          {"type":"select","label":"Only one option","options":["x"]},
                          {"type":"text","label":""}],
             "unsupported":"payments"}"""));
        AiFormDraftService forms = new AiFormDraftService(ai);
        Map<String, Object> d = forms.draft("VBS June 8-12 2027 9am to noon free max 60", LocalDate.of(2026, 11, 5));
        assertThat(d).isNotNull();
        Map<String, Object> ev = (Map<String, Object>) d.get("event");
        assertThat(ev).containsEntry("eventName", "Vacation Bible School").containsEntry("eventType", "Multiple Days")
                .containsEntry("eventDate", "2027-06-08").containsEntry("endDate", "2027-06-12")
                .containsEntry("startTime", "09:00").containsEntry("endTime", "12:00").containsEntry("fee", "Free")
                .containsEntry("maxCapacity", 60).containsEntry("foodAvailable", false).containsEntry("note", "Ages 4-12")
                .doesNotContainKeys("hostName", "hostPhone", "registrationEndDate", "foodOptions", "location");
        List<Map<String, Object>> q = (List<Map<String, Object>>) d.get("questions");
        assertThat(q).hasSize(3);
        assertThat(q.get(0)).containsEntry("type", "text").containsEntry("required", true);
        assertThat(q.get(1)).containsEntry("type", "select").containsEntry("options", List.of("S", "M", "L"));
        assertThat(q.get(2)).containsEntry("type", "text").doesNotContainKey("options");
        assertThat((List<String>) d.get("unsupported")).containsExactly("payments");
    }

    @Test @DisplayName("forms: what the schema forbids is still refused — unknown question type from the model, and client JSON stays strict")
    void formStillStrictWhereItMatters() {
        OpenAiVoiceService ai = mock(OpenAiVoiceService.class);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"questions\":[{\"type\":\"file\",\"label\":\"Upload\"}]}"));
        assertThat(new AiFormDraftService(ai).draft("a form", LocalDate.of(2026, 11, 5))).isNull();
        // /api/ai/forms/validate (client JSON) is not normalised
        assertThatThrownBy(() -> AiFormDraftService.validate(json("{\"event\":{\"hostName\":\"\"}}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiFormDraftService.validate(json("{\"event\":{\"location\":\"Hall\"}}"))).hasMessageContaining("Unknown event field");
        // a time that is not a time is dropped, not guessed
        JsonNode n = AiFormDraftService.normalizeModelOutput(json("{\"event\":{\"startTime\":\"after lunch\",\"endTime\":\"25:00\",\"eventType\":\"weekly\"}}"));
        assertThat(n.path("event").size()).isZero();
    }

    @Test @DisplayName("rules: priority wording, tone case, '2 days' and blank params are repaired; unknown actions and members are still refused")
    void ruleDraftRepairsModelSlips() throws Exception {
        OpenAiVoiceService ai = mock(OpenAiVoiceService.class);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.chatJson(anyString(), eq("medium"), anyDouble())).thenReturn(json("""
            {"name":"First-time visitor follow-up","trigger":"VISITOR_RECORDED","conditions":[{"field":"newVisitor","op":"is_true"}],
             "actions":[{"type":"create_follow_up","params":{"title":"Welcome {{name}}","assignTo":"owner","dueInDays":"2 days","priority":"Medium (default)","description":""}},
                        {"type":"draft_email","params":{"prompt":"Welcome note for {{name}}","tone":"Warm"}}]}"""));
        when(ai.chatJson(anyString(), eq("normal"), anyDouble())).thenReturn(json(
            "{\"name\":\"x\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"create_follow_up\",\"params\":{\"title\":\"t\",\"priority\":\"Normal\",\"dueInDays\":2.0}}]}"));
        when(ai.chatJson(anyString(), eq("weird"), anyDouble())).thenReturn(json(
            "{\"name\":\"x\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"create_follow_up\",\"params\":{\"title\":\"t\",\"priority\":\"whenever\",\"tone\":\"sarcastic\"}}]}"));
        when(ai.chatJson(anyString(), eq("bad"), anyDouble())).thenReturn(json(
            "{\"name\":\"x\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"delete_member\",\"params\":{\"priority\":\"high\"}}]}"));
        when(ai.chatJson(anyString(), eq("unknown param"), anyDouble())).thenReturn(json(
            "{\"name\":\"x\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"create_follow_up\",\"params\":{\"title\":\"t\",\"sendTo\":\"member\"}}]}"));
        WorkflowRuleDrafter d = new WorkflowRuleDrafter(ai);

        WorkflowRuleDrafter.Draft ok = d.draft("medium");
        assertThat(ok.refusal()).isNull();
        assertThat(ok.rule().actions().get(0).params()).containsEntry("priority", "MEDIUM").containsEntry("dueInDays", "2").doesNotContainKey("description");
        assertThat(ok.rule().actions().get(1).params()).containsEntry("tone", "warm");

        WorkflowRuleDrafter.Draft normal = d.draft("normal");
        assertThat(normal.refusal()).isNull();
        assertThat(normal.rule().actions().get(0).params()).containsEntry("priority", "MEDIUM").containsEntry("dueInDays", "2");

        WorkflowRuleDrafter.Draft weird = d.draft("weird");
        assertThat(weird.refusal()).isNull();
        assertThat(weird.rule().actions().get(0).params()).doesNotContainKeys("priority", "tone");

        assertThat(d.draft("bad").refusal()).contains("Unknown action");
        assertThat(d.draft("unknown param").refusal()).contains("Unknown parameter");
    }
}
