package com.churchgeniuspro.ai;

import com.churchgeniuspro.service.AiQueryLexicon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared AI Search vocabulary: many ways of asking map to the router's one word, whole
 * words only, idioms protected, names and period words untouched.
 */
@DisplayName("AI Search — synonym vocabulary")
class AiQueryLexiconTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        // giving
        "what is anson contribution this month      | what is anson contribution this month",
        "how much has anson donated this month      | how much has anson contribution this month",
        "how much did anson give this month         | how much did anson contribution this month",
        "show anson donations this month            | show anson contribution this month",
        "has anson given anything this year         | has anson contribution anything this year",
        "mark offerings in september                | mark contribution in september",
        "gifts received last week                   | contribution received last week",
        "who are the donors this year               | who are the contribution this year",
        "did maria tithe last month                 | did maria tithe last month",
        "total tithes this year                     | total tithe this year",
        // income / expenses / transactions
        "what was our revenue last quarter          | what was our income last quarter",
        "how much money received in march           | how much income in march",
        "how much did we spend last month           | how much did we expenses last month",
        "what were the costs in 2026                | what were the expenses in 2026",
        "bills paid out this month                  | expenses expenses this month",
        "show the ledger for september              | show the transactions for september",
        // meetings / events / volunteers
        "any meetups coming up                      | any meetings coming up",
        "upcoming programs                          | upcoming events",
        "what functions are planned next month      | what events are planned next month",
        "who are the helpers                        | who are the volunteers",
        "who is on duty this sunday                 | who is serving this sunday",
    })
    void synonymsMapToTheRoutersWords(String in, String out) {
        assertThat(AiQueryLexicon.canonical(in.trim())).isEqualTo(out.trim());
    }

    @Test @DisplayName("idioms are protected: \"give me a list\" asks to show, not about giving")
    void idioms() {
        assertThat(AiQueryLexicon.canonical("give me a list of meetings this week")).isEqualTo("show me a list of meetings this week");
        assertThat(AiQueryLexicon.isGiving(AiQueryLexicon.canonical("give me the upcoming events"))).isFalse();
        assertThat(AiQueryLexicon.isGiving(AiQueryLexicon.canonical("can you give me the expenses"))).isFalse();
    }

    @Test @DisplayName("whole words only — no partial rewrites inside other words or names")
    void wholeWordsOnly() {
        assertThat(AiQueryLexicon.canonical("forgiven")).isEqualTo("forgiven");
        assertThat(AiQueryLexicon.canonical("costello family")).isEqualTo("costello family");
        assertThat(AiQueryLexicon.canonical("eventually")).isEqualTo("eventually");
        assertThat(AiQueryLexicon.canonical("the meeting room")).isEqualTo("the meetings room");
    }

    @Test @DisplayName("a canonical word is never rewritten twice (no chains)")
    void noChains() {
        assertThat(AiQueryLexicon.canonical("contribution contributions donation")).isEqualTo("contribution contribution contribution");
        assertThat(AiQueryLexicon.canonical("events event programs")).isEqualTo("events events events");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "What is Anson's contribution this month?   | Anson",
        "How much has Anson donated this month?     | Anson",
        "How much did Anson give this month?        | Anson",
        "Show Anson's donations this month.         | Anson",
        "Did Anson Mathew tithe last month?         | Anson Mathew",
        "contributions from Maria in May            | Maria",
        "Has Peter given anything this year?        | Peter",
    })
    void personNamedInAGivingQuestion(String q, String who) {
        assertThat(AiQueryLexicon.personMentioned(q.trim())).isEqualTo(who.trim());
    }

    @Test @DisplayName("no person is invented from ordinary words")
    void noFalsePersons() {
        assertThat(AiQueryLexicon.personMentioned("How much did we give this month?")).isNull();
        assertThat(AiQueryLexicon.personMentioned("What were the donations this month?")).isNull();
        assertThat(AiQueryLexicon.personMentioned("Who gave the most this year?")).isNull();
        assertThat(AiQueryLexicon.personMentioned("Show the church's donations this month")).isNull();
        assertThat(AiQueryLexicon.personMentioned("What did members give last month?")).isNull();
    }
}
