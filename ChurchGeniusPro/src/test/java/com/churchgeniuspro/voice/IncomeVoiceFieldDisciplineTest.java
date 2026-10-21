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
 * Scenarios 1, 2, 9, 11: which field a phrase may change. The routing rules live in
 * voice-core.js (routeFields / parseSetCommand) and the page applies ONLY what they
 * return, so an amount can never move the contributor or the fund.
 */
@DisplayName("Income voice — explicit field selection and single-field routing")
class IncomeVoiceFieldDisciplineTest {

    static final String FW = "{contributor:['contributor','member','donor','name'],fund:['fund','offering'],amount:['amount','value','total'],date:['date'],method:['method','payment'],ref:['reference','ref'],note:['note','notes']}";
    static final String ORDER = "['contributor','fund','date','method','amount','ref','note']";

    @BeforeAll static void node() { NodeJs.assumeAvailable(); }

    static JsonNode route(String fieldsJs, String transcript, String active) throws Exception {
        return NodeJs.evalVoiceCore("VoiceCore.routeFields(" + fieldsJs + ", '" + transcript + "', " + (active == null ? "null" : "'" + active + "'") + ", " + FW + ", " + ORDER + ")");
    }
    static JsonNode set(String transcript) throws Exception { return NodeJs.evalVoiceCore("VoiceCore.parseSetCommand('" + transcript + "', " + FW + ")"); }

    @Test @DisplayName("1 & 2: with Contributor / Fund already set, 'amount 100' changes only the amount even when the extractor also returned a contributor")
    void amountNeverTouchesOtherFields() throws Exception {
        JsonNode r = route("{contributor:'Handsome', amount:'100'}", "amount 100", null);
        assertThat(r.get("reason").asText()).isEqualTo("keyword");
        assertThat(r.get("pairs")).hasSize(1);
        assertThat(r.get("pairs").get(0).get("field").asText()).isEqualTo("amount");
        JsonNode r2 = route("{fund:'Youth', amount:'100'}", "set amount to 100", "amount");
        assertThat(r2.get("pairs")).hasSize(1);
        assertThat(r2.get("pairs").get(0).get("field").asText()).isEqualTo("amount");
    }

    @Test @DisplayName("several unnamed fields in one phrase → ask, apply nothing")
    void ambiguousAsks() throws Exception {
        JsonNode r = route("{contributor:'Anson', amount:'100'}", "something unclear", null);
        assertThat(r.get("reason").asText()).isEqualTo("ambiguous");
        assertThat(r.get("pairs")).isEmpty();
    }

    @Test @DisplayName("9: 'Set Contributor to Anson Mathew', 'Set Fund to Youth Fund', 'Set Amount 100', 'Set Date to …', 'Done'")
    void setCommands() throws Exception {
        assertThat(set("Set Contributor to Anson Mathew").get("field").asText()).isEqualTo("contributor");
        assertThat(set("Set Contributor to Anson Mathew").get("value").asText()).isEqualTo("anson mathew");
        assertThat(set("Set Fund to Youth Fund").get("value").asText()).isEqualTo("youth fund");
        assertThat(set("Set Amount 100").get("value").asText()).isEqualTo("100");
        assertThat(set("Set Date to October 21, 2026").get("field").asText()).isEqualTo("date");
        assertThat(set("Set payment method to cash").get("value").asText()).isEqualTo("cash");
        assertThat(set("set contributor").get("value").asText()).as("field only → wait for the value").isEmpty();
        assertThat(set("Set the Note Field as Thank You.").get("field").asText()).isEqualTo("note");   // 2026-10-21 12:47 log
        assertThat(set("Set the Note Field as Thank You.").get("value").asText()).isEqualTo("thank you.");
        assertThat(set("Done").get("kind").asText()).isEqualTo("done");
        assertThat(set("Anson Mathew").isNull()).as("ordinary words are not commands").isTrue();
        assertThat(set("one hundred").isNull()).isTrue();
    }

    @Test @DisplayName("an active field receives the next phrase and nothing else can change")
    void activeFieldOwnsTheSpeech() throws Exception {
        JsonNode r = route("{contributor:'Anson Mathew', note:'anson mathew'}", "Anson Mathew", "contributor");
        assertThat(r.get("reason").asText()).isEqualTo("active");
        assertThat(r.get("pairs")).hasSize(1);
        assertThat(r.get("pairs").get(0).get("field").asText()).isEqualTo("contributor");
        JsonNode r2 = route("{}", "one hundred", "amount");
        assertThat(r2.get("pairs").get(0).get("value").asText()).isEqualTo("one hundred");
    }

    @Test @DisplayName("speech-model artefacts ('Thank you for watching', non-Latin text) are never taken as the user's words")
    void hallucinations() throws Exception {
        for (String t : new String[] { "Thank you for watching.", "Thanks for watching", "please subscribe", "Subtitles by the Amara.org community", "Don't remember where I did Stop  меня attack" })
            assertThat(NodeJs.evalVoiceCore("VoiceCore.isLikelyHallucination(" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(t) + ")").asBoolean()).as(t).isTrue();
        for (String t : new String[] { "Thank you", "Anson Mathew", "one hundred", "Set amount to 100", "Thank you for the gift" })
            assertThat(NodeJs.evalVoiceCore("VoiceCore.isLikelyHallucination(" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(t) + ")").asBoolean()).as(t).isFalse();
        String js = Files.readString(Paths.get("src/main/resources/static/income-voice.js"));
        assertThat(js).contains("if(VC.isLikelyHallucination(transcript)){ logEvent('ignored','speech model artefact")   // Voice mode
                      .contains("if(VC.isLikelyHallucination(transcript)){ logEvent('ignored', 'speech model artefact")  // Converse
                      .contains("An explicit \"Set Note …\" REPLACES the note");
    }

    @Test @DisplayName("11: the page keeps a filled field when the transcript is low-confidence, and never flips Contributor to a guest without a click")
    void pageRules() throws IOException {
        String js = Files.readString(Paths.get("src/main/resources/static/income-voice.js"));
        assertThat(js).contains("if(lowConf && fieldPresent(p.field)){")                        // Voice mode
                      .contains("if(convo.lowConf && fieldPresent(f) &&")                        // Converse
                      .contains("window.__voicePick('newContributor'")                           // guest only via the suggestion
                      .doesNotContain("window.toggleGuestMode(true); var g=$('fGuestName'); if(g){ g.value=titleCase(value)");
        assertThat(js).contains("VC.parseSetCommand(transcript, FIELD_WORDS)").contains("VC.routeFields(fields, transcript, activeField, KEYWORDS, ORDER)");
        // "done" finishes a field in Converse; it no longer ends the conversation
        assertThat(js).contains("/^\\s*(stop|stop it|cancel|never ?mind|quit|exit|end)\\s*[.!]?$/");
    }
}
