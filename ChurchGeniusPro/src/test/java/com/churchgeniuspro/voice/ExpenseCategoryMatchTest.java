package com.churchgeniuspro.voice;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expense categories are matched with the same tokenised / phonetic / letter-overlap
 * scorer the Income page uses for names: a clear match (≥70, not ambiguous) is applied,
 * two plausible categories are offered as a choice, a single weak-but-plausible one is
 * confirmed ("Did you mean Salaries?"), and unrelated speech matches nothing.
 */
@DisplayName("Expense voice — category matching (voice-core.js)")
class ExpenseCategoryMatchTest {

    static final String CATS = "[{id:1,fullName:'Office Supplies'},{id:2,fullName:'Utilities'},{id:3,fullName:'Building Maintenance'},{id:4,fullName:'Building Insurance'},{id:5,fullName:'Travel'},{id:6,fullName:'Salaries'}]";

    @BeforeAll static void node() { NodeJs.assumeAvailable(); }

    static JsonNode match(String spoken) throws Exception { return NodeJs.evalVoiceCore("VoiceCore.matchContributor('" + spoken + "', " + CATS + ")"); }
    static List<String> names(JsonNode r) { List<String> out = new ArrayList<>(); r.get("candidates").forEach(c -> out.add(c.get("fullName").asText())); return out; }

    @Test @DisplayName("exact, singular/plural and misspelt forms resolve confidently")
    void clearMatches() throws Exception {
        for (String[] c : new String[][] { {"Office Supplies", "Office Supplies"}, {"office supply", "Office Supplies"}, {"utilitys", "Utilities"}, {"travel", "Travel"}, {"maintenance", "Building Maintenance"} }) {
            JsonNode r = match(c[0]);
            assertThat(r.get("best").get("fullName").asText()).as(c[0]).isEqualTo(c[1]);
            assertThat(r.get("best").get("score").asInt()).as(c[0]).isGreaterThanOrEqualTo(70);
            assertThat(r.get("ambiguous").asBoolean()).as(c[0]).isFalse();
        }
    }

    @Test @DisplayName("'building' fits two categories → ambiguous: both offered, none applied")
    void ambiguousOffersBoth() throws Exception {
        JsonNode r = match("building");
        assertThat(r.get("ambiguous").asBoolean()).isTrue();
        assertThat(names(r)).containsExactlyInAnyOrder("Building Maintenance", "Building Insurance");
    }

    @Test @DisplayName("'salary' is plausible but not sure → below the apply threshold, above the confirm threshold")
    void weakSingleMatchIsConfirmed() throws Exception {
        JsonNode r = match("salary");
        assertThat(names(r)).containsExactly("Salaries");
        int score = r.get("best").get("score").asInt();
        assertThat(score).isGreaterThanOrEqualTo(40).isLessThan(70);   // page: status 'confirm' → "Did you mean Salaries?"
    }

    @Test @DisplayName("unrelated speech matches no category (nothing is guessed)")
    void noFalseMatch() throws Exception {
        for (String s : new String[] { "rent", "the weather is nice", "one hundred" }) {
            JsonNode r = match(s);
            assertThat(r.get("candidates")).as(s).isEmpty();
            assertThat(r.get("best").isNull()).as(s).isTrue();
        }
    }
}
