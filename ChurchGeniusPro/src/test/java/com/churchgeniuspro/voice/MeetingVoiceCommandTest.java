package com.churchgeniuspro.voice;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meetings page (meeting-voice.js, run as shipped through the fake-DOM harness):
 * "Set &lt;field&gt; [to &lt;value&gt;]" selects ONE field and applies only to it, other
 * fields keep their values, invalid dates/times are rejected with a clarification,
 * ambiguous locations are offered as choices, "Done" finishes a field (never stops),
 * and the existing confirm / Save Now flow is untouched.
 */
@DisplayName("Meetings voice — Set/Done field commands, validation, preserved values")
class MeetingVoiceCommandTest {

    static final String LOCS = "[{\"id\":1,\"locType\":\"H\",\"displayName\":\"Fellowship Hall\"},{\"id\":2,\"locType\":\"M\",\"displayName\":\"Benny Peter\"},{\"id\":3,\"locType\":\"M\",\"displayName\":\"Benny Mathew\"}]";
    static final String CATS = "[\"Bible Study\",\"Prayer Meeting\"]";
    /** A fully captured meeting, conversation at "complete". */
    static final String FULL = "\"form\":{\"meetingType\":\"Bible Study\",\"locationSearch\":\"Fellowship Hall\",\"meetingDate\":\"2026-11-05\",\"startTime\":\"19:00\"},\"categories\":" + CATS + ",\"locations\":" + LOCS + ",\"stage\":\"complete\",\"today\":\"2026-10-21\"";

    @BeforeAll static void node() { NodeJs.assumeAvailable(); }

    static JsonNode run(String base, String... steps) throws Exception {
        StringBuilder sb = new StringBuilder("{").append(base).append(",\"steps\":[");
        for (int i = 0; i < steps.length; i++) sb.append(i > 0 ? "," : "").append('"').append(steps[i].replace("\"", "\\\"")).append('"');
        return NodeJs.meetingScenario(sb.append("]}").toString());
    }
    static String reply(JsonNode r, int i) { return r.get("replies").get(i).asText(); }

    @Test @DisplayName("'Set time to 7:30 pm' on a complete meeting changes only the time; category, location and date are untouched; flow returns to complete")
    void setOneFieldKeepsTheOthers() throws Exception {
        JsonNode r = run(FULL, "Set time to 7:30 pm", "yes");
        assertThat(reply(r, 0)).contains("7:30 PM").contains("Is that correct?");
        assertThat(reply(r, 1)).startsWith("Thank you. Time has been updated.").doesNotContain("Where will it be").doesNotContain("What should the occurrence be");
        JsonNode f = r.get("form");
        assertThat(f.get("startTime").asText()).isEqualTo("19:30");
        assertThat(f.get("meetingType").asText()).isEqualTo("Bible Study");
        assertThat(f.get("locationSearch").asText()).isEqualTo("Fellowship Hall");
        assertThat(f.get("meetingDate").asText()).isEqualTo("2026-11-05");
        assertThat(r.get("stage").asText()).isEqualTo("complete");
        assertThat(r.get("confirm").asBoolean()).isFalse();
    }

    @Test @DisplayName("'Set date to next Thursday' is validated and confirmed; a 'no' keeps the old date")
    void setDateConfirmedOrKept() throws Exception {
        JsonNode r = run(FULL, "Set the date to next Thursday", "yes");
        assertThat(reply(r, 0)).contains("October 22, 2026");
        assertThat(r.get("form").get("meetingDate").asText()).isEqualTo("2026-10-22");
        JsonNode no = run(FULL, "Set date to next Thursday", "no");
        assertThat(no.get("form").get("meetingDate").asText()).as("declined → previous value kept").isEqualTo("2026-11-05");
        assertThat(reply(no, 1)).contains("When will it be conducted?");
    }

    @Test @DisplayName("invalid values are rejected with a clarification and nothing is written")
    void invalidValuesRejected() throws Exception {
        JsonNode d = run(FULL, "Set date to banana");
        assertThat(reply(d, 0)).contains("didn't hear a valid date");
        assertThat(d.get("form").get("meetingDate").asText()).isEqualTo("2026-11-05");
        JsonNode t = run(FULL, "Set time to purple");
        assertThat(reply(t, 0)).contains("didn't hear a valid time");
        assertThat(t.get("form").get("startTime").asText()).isEqualTo("19:00");
        JsonNode c = run(FULL, "Set category to Knitting Circle");
        assertThat(reply(c, 0)).contains("didn't hear a valid category");
        assertThat(c.get("form").get("meetingType").asText()).isEqualTo("Bible Study");
    }

    @Test @DisplayName("'Set location to Benny' fits two people → both offered, the chosen one applied, flow returns to complete")
    void ambiguousLocationOffersChoices() throws Exception {
        JsonNode r = run(FULL, "Set the location to Benny", "2");
        assertThat(reply(r, 0)).contains("Option 1. Benny Peter").contains("Option 2. Benny Mathew");
        assertThat(r.get("form").get("locationSearch").asText()).isEqualTo("Benny Mathew");
        assertThat(r.get("selected").get("id").asInt()).isEqualTo(3);
        assertThat(reply(r, 1)).startsWith("Thank you. Location has been updated.");
        assertThat(r.get("stage").asText()).isEqualTo("complete");
        // an exact name confirms directly
        JsonNode ex = run(FULL, "Set location to Benny Peter", "yes");
        assertThat(reply(ex, 0)).contains("Benny Peter").contains("Is that correct?");
        assertThat(ex.get("form").get("locationSearch").asText()).isEqualTo("Benny Peter");
    }

    @Test @DisplayName("'Set Notes to …' replaces the notes (dictation appends); 'Done' finishes a field and never stops the conversation")
    void notesAndDone() throws Exception {
        JsonNode r = run("\"form\":{\"note\":\"old note\"},\"stage\":\"complete\"", "Set notes to Bring snacks", "yes", "done");
        assertThat(reply(r, 0)).contains("\"Bring snacks\"");
        assertThat(r.get("form").get("note").asText()).isEqualTo("Bring snacks");
        assertThat(reply(r, 1)).contains("Still needed: Category, Date, Time");
        assertThat(r.get("stage").asText()).isEqualTo("complete");
        JsonNode d = run(FULL, "Set time", "done");
        assertThat(reply(d, 0)).contains("What time works best");
        assertThat(reply(d, 1)).startsWith("Thank you. Time has been updated.");   // value kept, back to complete
        assertThat(d.get("form").get("startTime").asText()).isEqualTo("19:00");
        JsonNode empty = run("\"form\":{\"meetingType\":\"Bible Study\"},\"categories\":" + CATS + ",\"stage\":\"complete\"", "Set date", "done");
        assertThat(reply(empty, 1)).contains("I don't have the date yet");
        assertThat(NodeJs.meetingScenario("{\"isStop\":\"done\",\"isDone\":\"Done.\"}").get("isStop").asBoolean()).isFalse();
        assertThat(NodeJs.meetingScenario("{\"isStop\":\"done\",\"isDone\":\"Done.\"}").get("isDone").asBoolean()).isTrue();
        assertThat(NodeJs.meetingScenario("{\"isStop\":\"stop\"}").get("isStop").asBoolean()).as("stop still asks to end").isTrue();
    }

    @Test @DisplayName("the guided flow itself is unchanged: category → location → date → time, each confirmed")
    void guidedFlowStillWorks() throws Exception {
        JsonNode r = run("\"form\":{},\"categories\":" + CATS + ",\"locations\":" + LOCS + ",\"start\":true,\"today\":\"2026-10-21\"",
                "Bible Study", "yes", "Fellowship Hall", "yes", "tomorrow", "yes", "7 pm", "yes");
        assertThat(reply(r, 0)).contains("Which category");
        assertThat(r.get("form").get("meetingType").asText()).isEqualTo("Bible Study");
        assertThat(r.get("form").get("locationSearch").asText()).isEqualTo("Fellowship Hall");
        assertThat(r.get("form").get("meetingDate").asText()).isEqualTo("2026-10-22");
        assertThat(r.get("form").get("startTime").asText()).isEqualTo("19:00");
        assertThat(r.get("stage").asText()).isEqualTo("occurrence");
    }

    @Test @DisplayName("page wiring: shared parser + artefact guard, locale sent with each request, voice-core loaded, save flow untouched")
    void pageRules() throws IOException {
        String js = Files.readString(Paths.get("src/main/resources/static/meeting-voice.js"));
        assertThat(js).contains("VC.parseSetCommand(raw, MEETING_FIELD_WORDS)")
                      .contains("VC.isLikelyHallucination(t)")
                      .contains("getExtra:function(){ return { expecting: (mstate.stage||''), locale: voiceLocale() }; }")
                      .contains("localStorage.getItem('cgp.voice.locale')")
                      .contains("if (isDoneCmd(low)) return handleDone();")
                      .contains("function doSave(){ var f=win('saveMeeting'); if(f){ f(); return true; } return false; }");
        String html = Files.readString(Paths.get("src/main/resources/static/meeting.html"));
        assertThat(html).contains("<script src=\"/voice-core.js\"></script>\n<script src=\"/voice-openai.js\"></script>\n<script src=\"/meeting-voice.js\"></script>");
    }
}
