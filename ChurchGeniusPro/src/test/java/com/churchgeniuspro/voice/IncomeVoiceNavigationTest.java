package com.churchgeniuspro.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guided Voice / Converse on the Income page (2026-11-02): the navigation and clear
 * commands are recognised by VoiceCore.parseNavCommand on the WHOLE phrase — never by
 * the AI extractor — so "next" or "go to Fund" behave the same every time, and a value
 * that merely contains one of the words is never taken as a command.
 */
@DisplayName("Income / Expense voice — navigation and clear commands")
class IncomeVoiceNavigationTest {

    static final String FW = "{contributor:['contributor','contributer','contributors','member','donor','name'],fund:['offering','offerings','fund','category','purpose'],"
            + "date:['date','dated'],method:['method','payment','mode'],amount:['amount','value','total','money'],ref:['reference','ref'],note:['note','notes','memo','remark']}";
    static final ObjectMapper M = new ObjectMapper();

    @BeforeAll static void node() { NodeJs.assumeAvailable(); }

    static JsonNode nav(String transcript, boolean listOpen) throws Exception {
        return NodeJs.evalVoiceCore("VoiceCore.parseNavCommand(" + M.writeValueAsString(transcript) + ", " + FW + ", {options:" + listOpen + "})");
    }
    static String kind(String t) throws Exception { JsonNode n = nav(t, false); return n.isNull() ? null : n.get("kind").asText(); }
    static String field(String t) throws Exception { return nav(t, false).get("field").asText(); }

    @Test @DisplayName("'next' in its usual wordings")
    void next() throws Exception {
        for (String t : new String[] { "Next.", "next", "Next one", "Go to next one.", "Go to the next field", "Move to the next field", "Next field please", "Okay, next." })
            assertThat(kind(t)).as(t).isEqualTo("next");
    }

    @Test @DisplayName("'go to <field>' jumps to that field, including common mishearings")
    void gotoField() throws Exception {
        assertThat(kind("Go to Fund.")).isEqualTo("goto");
        assertThat(field("Go to Fund.")).isEqualTo("fund");
        assertThat(field("go to the fund field")).isEqualTo("fund");
        assertThat(field("Go to fun")).isEqualTo("fund");
        assertThat(field("move to payment method")).isEqualTo("method");
        assertThat(field("go to check number")).isEqualTo("ref");
        assertThat(field("go to amount")).isEqualTo("amount");
        assertThat(field("amount field")).isEqualTo("amount");
    }

    @Test @DisplayName("'clear all' keeps the contributor; 'clear form' / 'start over' clear everything; 'clear <field>' one field")
    void clearCommands() throws Exception {
        assertThat(kind("Clear all.")).isEqualTo("clearAll");
        assertThat(kind("clear all fields")).isEqualTo("clearAll");
        assertThat(kind("Clear form")).isEqualTo("clearForm");
        assertThat(kind("Reset form")).isEqualTo("clearForm");
        assertThat(kind("Start over")).isEqualTo("clearForm");
        assertThat(kind("clear everything")).isEqualTo("clearForm");
        assertThat(kind("Clear contributor")).isEqualTo("clearField");
        assertThat(field("Clear contributor")).isEqualTo("contributor");
        assertThat(field("clear the amount")).isEqualTo("amount");
        assertThat(kind("clear")).as("bare 'clear' stays with the existing 'reset the last field' rule").isNull();
    }

    @Test @DisplayName("values that only contain a command word are never commands")
    void valuesAreNotCommands() throws Exception {
        for (String t : new String[] { "Next Generation Fund", "Go to Missions", "go to fund building fund", "Anson Mathew", "one hundred", "Set amount to 100", "Clear Creek Church" })
            assertThat(nav(t, true).isNull()).as(t).isTrue();
    }

    @Test @DisplayName("a number picks from the Fund / Method list only while the list is open")
    void optionNumbers() throws Exception {
        assertThat(nav("3", true).get("num").asInt()).isEqualTo(3);
        assertThat(nav("number three", true).get("num").asInt()).isEqualTo(3);
        assertThat(nav("the second one", true).get("num").asInt()).isEqualTo(2);
        assertThat(nav("option 2", true).get("num").asInt()).isEqualTo(2);
        assertThat(nav("3", false).isNull()).as("no list → a number is a value (e.g. an amount)").isTrue();
        assertThat(nav("tithe", true).isNull()).isTrue();
    }

    @Test @DisplayName("Income page wiring: guided controller, ordered replies, voice auto-fill without Ref No")
    void pageWiring() throws Exception {
        String js = Files.readString(Paths.get("src/main/resources/static/income-voice.js"));
        String html = Files.readString(Paths.get("src/main/resources/static/income.html"));
        String rec = Files.readString(Paths.get("src/main/resources/static/voice-openai.js"));
        assertThat(js).contains("VC.parseNavCommand(transcript, FIELD_WORDS")   // both modes check commands first
                .contains("ordered:      true")
                .contains("resultChain.then(waitAutoFill)")
                .contains("{voice:true}");
        assertThat(html).contains("if (!opts.voice) document.getElementById('fRefNo').value")
                .contains("window.__incomeAutoFillPending")
                .contains("String(selectedContributorId) !== String(memberId)");
        assertThat(rec).contains("if (opts.ordered) deliver(mySeq, outcome);");
    }

    @Test @DisplayName("Expense page wiring: same guided controller; voice auto-fill without Ref No")
    void expenseWiring() throws Exception {
        String js = Files.readString(Paths.get("src/main/resources/static/expense-voice.js"));
        String html = Files.readString(Paths.get("src/main/resources/static/expense.html"));
        assertThat(js).contains("VC.parseNavCommand(transcript, FIELD_WORDS")
                .contains("ordered:      true")
                .contains("resultChain.then(waitAutoFill)")
                .contains("function clearAllExceptExpense()")
                .contains("window.selectExpense(id, name, false, {voice:true})");
        assertThat(html).contains("if (data.refNo && !opts.voice)")
                .contains("window.__expenseAutoFillPending")
                .contains("String(document.getElementById('fExpense').value) !== String(purposeId)");
    }
}
