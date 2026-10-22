package com.churchgeniuspro.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expense page: the Income voice improvements ported to expense-voice.js — explicit
 * "Set &lt;field&gt;" / "Done" commands, single-field routing (an amount can never land in
 * Expense or Fund), the low-confidence rule, and the speech-artefact guard. The routing
 * rules live in voice-core.js and the page applies ONLY what they return.
 */
@DisplayName("Expense voice — explicit field selection and single-field routing")
class ExpenseVoiceFieldDisciplineTest {

    static final String FW = "{expense:['expense','expenses','purpose','category','categories'],fund:['fund','funds','source'],date:['date','dated'],method:['method','payment','mode'],amount:['amount','value','total','money'],ref:['reference','ref'],note:['note','notes','memo','remark']}";
    static final String KW = "{expense:['expense','expenses','purpose','category'],fund:['fund','funds','source'],date:['date','dated'],method:['method','payment','mode'],amount:['amount','value','total'],ref:['reference','ref'],note:['note','notes','memo','remark']}";
    static final String ORDER = "['expense','fund','date','method','amount','ref','note']";

    @BeforeAll static void node() { NodeJs.assumeAvailable(); }

    static JsonNode route(String fieldsJs, String transcript, String active) throws Exception {
        return NodeJs.evalVoiceCore("VoiceCore.routeFields(" + fieldsJs + ", '" + transcript + "', " + (active == null ? "null" : "'" + active + "'") + ", " + KW + ", " + ORDER + ")");
    }
    static JsonNode set(String transcript) throws Exception { return NodeJs.evalVoiceCore("VoiceCore.parseSetCommand('" + transcript + "', " + FW + ")"); }

    @Test @DisplayName("'Set Expense to Office Supplies', 'Set Fund to Building', 'Set Amount to 100', 'Set Method to Check', 'Set Note …', 'Done'")
    void setCommands() throws Exception {
        assertThat(set("Set Expense to Office Supplies").get("field").asText()).isEqualTo("expense");
        assertThat(set("Set Expense to Office Supplies").get("value").asText()).isEqualTo("office supplies");
        assertThat(set("Set the expense category to Utilities").get("field").asText()).isEqualTo("expense");
        assertThat(set("Set the expense category to Utilities").get("value").asText()).isEqualTo("utilities");
        assertThat(set("Set Category Utilities").get("field").asText()).isEqualTo("expense");
        assertThat(set("Set Fund to Building").get("field").asText()).isEqualTo("fund");
        assertThat(set("Set Fund to Building").get("value").asText()).isEqualTo("building");
        assertThat(set("Set Amount to 100").get("value").asText()).isEqualTo("100");
        assertThat(set("Set payment method to check").get("field").asText()).isEqualTo("method");
        assertThat(set("Set Date to October 21, 2026").get("field").asText()).isEqualTo("date");
        assertThat(set("Set Reference Number to 4567").get("field").asText()).isEqualTo("ref");
        assertThat(set("Set the Note Field as Quarterly Bill").get("field").asText()).isEqualTo("note");
        assertThat(set("Set the Note Field as Quarterly Bill").get("value").asText()).isEqualTo("quarterly bill");
        assertThat(set("set expense").get("value").asText()).as("field only → wait for the value").isEmpty();
        assertThat(set("Done").get("kind").asText()).isEqualTo("done");
        assertThat(set("Office Supplies").isNull()).as("ordinary words are not commands").isTrue();
        assertThat(set("one hundred").isNull()).isTrue();
    }

    @Test @DisplayName("an amount phrase changes only the amount, even when the extractor also returned a category or fund")
    void amountNeverTouchesOtherFields() throws Exception {
        JsonNode r = route("{expense:'Office Supplies', amount:'100'}", "amount 100", null);
        assertThat(r.get("reason").asText()).isEqualTo("keyword");
        assertThat(r.get("pairs")).hasSize(1);
        assertThat(r.get("pairs").get(0).get("field").asText()).isEqualTo("amount");
        JsonNode r2 = route("{fund:'Building', amount:'100'}", "set amount to 100", "amount");
        assertThat(r2.get("pairs")).hasSize(1);
        assertThat(r2.get("pairs").get(0).get("field").asText()).isEqualTo("amount");
    }

    @Test @DisplayName("an active field receives the next phrase; naming another field switches deliberately; unnamed multi-field phrases ask")
    void activeFieldOwnsTheSpeech() throws Exception {
        JsonNode r = route("{expense:'Utilities', note:'utilities'}", "Utilities", "expense");
        assertThat(r.get("reason").asText()).isEqualTo("active");
        assertThat(r.get("pairs")).hasSize(1);
        assertThat(r.get("pairs").get(0).get("field").asText()).isEqualTo("expense");
        JsonNode sw = route("{amount:'999'}", "amount 999", "expense");
        assertThat(sw.get("reason").asText()).isEqualTo("switch");
        assertThat(sw.get("pairs").get(0).get("field").asText()).isEqualTo("amount");
        JsonNode amb = route("{expense:'Office Supplies', amount:'100'}", "office supplies 100", null);
        assertThat(amb.get("reason").asText()).isEqualTo("ambiguous");
        assertThat(amb.get("pairs")).isEmpty();
    }

    @Test @DisplayName("speech-model artefacts are never taken as the user's words — and the page checks them in both modes")
    void hallucinations() throws Exception {
        ObjectMapper m = new ObjectMapper();
        for (String t : new String[] { "Thank you for watching.", "please subscribe", "Subtitles by the Amara.org community" })
            assertThat(NodeJs.evalVoiceCore("VoiceCore.isLikelyHallucination(" + m.writeValueAsString(t) + ")").asBoolean()).as(t).isTrue();
        for (String t : new String[] { "Office Supplies", "one hundred", "Set amount to 100" })
            assertThat(NodeJs.evalVoiceCore("VoiceCore.isLikelyHallucination(" + m.writeValueAsString(t) + ")").asBoolean()).as(t).isFalse();
        String js = Files.readString(Paths.get("src/main/resources/static/expense-voice.js"));
        assertThat(js).contains("if(VC.isLikelyHallucination(transcript)){ logEvent('ignored','speech model artefact")    // Voice mode
                      .contains("if(VC.isLikelyHallucination(transcript)){ logEvent('ignored', 'speech model artefact")   // Converse
                      .contains("An explicit \"Set Note …\" REPLACES the note");
    }

    @Test @DisplayName("page rules: Set/Done + routing from voice-core, low-confidence never overwrites, validated amounts, no silent category guess, locale + vocabulary hint, 'done' is not 'stop'")
    void pageRules() throws IOException {
        String js = Files.readString(Paths.get("src/main/resources/static/expense-voice.js"));
        assertThat(js).contains("VC.parseSetCommand(transcript, FIELD_WORDS)")
                      .contains("VC.routeFields(fields, transcript, activeField, KEYWORDS, ORDER)")
                      .contains("if(lowConf && fieldPresent(p.field)){")                        // Voice mode
                      .contains("if(convo.lowConf && fieldPresent(f) &&")                        // Converse
                      .contains("var a=parseAmount(value);          // null unless a valid, positive, in-range number")
                      .contains("problem:amountProblem(value)")
                      .contains("context:      'expense',")
                      .contains("getExtra:     function(){ return { expecting:")
                      .contains("locale: voiceLocale()")
                      .contains("id=\"voiceLocale\"")
                      .contains("window.__expenseVoice = {")
                      .contains("/^\\s*(stop|stop it|cancel|never ?mind|quit|exit|end)\\s*[.!]?$/")   // "done" finishes a field
                      .contains("typeof window.speechSynthesis.speak==='function'");
        // A weak category match is offered, never applied; nothing left over from the contributor/guest flow.
        assertThat(js).contains("cm.best.score>=70")
                      .contains("status:'confirm', option: opt(cm.best)")
                      .doesNotContain("toggleGuestMode").doesNotContain("fGuestName").doesNotContain("saveIncome").doesNotContain("__incomeVoice");
        // The expense form carries the same amount ceiling as the ledger.
        String html = Files.readString(Paths.get("src/main/resources/static/expense.html"));
        assertThat(html).contains("id=\"fAmount\" placeholder=\"0.00\" min=\"0.01\" max=\"9999999999999.99\" step=\"0.01\"");
        assertThat(html).contains("<script src=\"/voice-core.js\"></script>");
    }
}
