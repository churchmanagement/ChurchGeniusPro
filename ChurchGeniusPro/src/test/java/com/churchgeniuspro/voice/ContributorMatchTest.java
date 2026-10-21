package com.churchgeniuspro.voice;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenarios 6, 7, 8: "Anson Mathew", "Anson" and the transcription slips seen on
 * 2026-10-21 ("Matthew", "Handsome") resolve to the existing contributor, offered or
 * applied according to how sure the match is; two plausible people are never decided by
 * sound alone; a new contributor is never suggested while a plausible match exists.
 */
@DisplayName("Income voice — contributor matching (voice-core.js)")
class ContributorMatchTest {

    static final String PEOPLE = "[{id:1,fullName:'Anson Mathew'},{id:2,fullName:'Matthew Johnson'},{id:3,fullName:'Hanna Thomas'},{id:4,fullName:'Philip George'}]";

    @BeforeAll static void node() { NodeJs.assumeAvailable(); }

    static JsonNode match(String spoken) throws Exception {
        return NodeJs.evalVoiceCore("VoiceCore.matchContributor('" + spoken + "', " + PEOPLE + ")");
    }
    static List<String> names(JsonNode r) { List<String> out = new ArrayList<>(); r.get("candidates").forEach(c -> out.add(c.get("fullName").asText())); return out; }
    static int score(JsonNode r, String name) { for (JsonNode c : r.get("candidates")) if (c.get("fullName").asText().equals(name)) return c.get("score").asInt(); return 0; }

    @Test @DisplayName("the full name and the first name alone resolve confidently (≥70, not ambiguous)")
    void fullAndFirstName() throws Exception {
        JsonNode full = match("Anson Mathew");
        assertThat(full.get("best").get("fullName").asText()).isEqualTo("Anson Mathew");
        assertThat(full.get("best").get("score").asInt()).isEqualTo(100);
        assertThat(full.get("ambiguous").asBoolean()).isFalse();
        JsonNode first = match("Anson");
        assertThat(first.get("best").get("fullName").asText()).isEqualTo("Anson Mathew");
        assertThat(first.get("best").get("score").asInt()).isGreaterThanOrEqualTo(70);
        assertThat(first.get("ambiguous").asBoolean()).isFalse();
    }

    @Test @DisplayName("\"Matthew\" (the 2026-10-21 transcript) offers Anson Mathew — and asks, because Matthew Johnson is plausible too")
    void matthewIsOfferedNotRejected() throws Exception {
        JsonNode r = match("Matthew");
        assertThat(names(r)).contains("Anson Mathew", "Matthew Johnson");
        assertThat(score(r, "Anson Mathew")).isGreaterThanOrEqualTo(70);
        assertThat(r.get("ambiguous").asBoolean()).as("two plausible people → confirm, never auto-pick").isTrue();
    }

    @Test @DisplayName("\"Handsome\" (the second slip) still surfaces Anson Mathew as a candidate, below the auto-apply bar")
    void handsomeSurfacesAnson() throws Exception {
        JsonNode r = match("Handsome");
        assertThat(names(r)).containsExactly("Anson Mathew");
        assertThat(score(r, "Anson Mathew")).isBetween(40, 69);
    }

    @Test @DisplayName("phonetic slips resolve: Fillip → Philip George, Mathew → Anson Mathew")
    void phonetic() throws Exception {
        assertThat(names(match("Fillip"))).containsExactly("Philip George");
        assertThat(match("Mathew").get("best").get("fullName").asText()).isEqualTo("Anson Mathew");
    }

    @Test @DisplayName("noise never produces a candidate, so the new-contributor question is only asked when nothing is plausible")
    void noise() throws Exception {
        JsonNode r = match("the weather is nice");
        assertThat(r.get("candidates")).isEmpty();
        assertThat(r.get("best").isNull()).isTrue();
    }
}
