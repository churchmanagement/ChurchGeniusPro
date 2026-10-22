package com.churchgeniuspro.ai;

import com.churchgeniuspro.service.AiQueryLexicon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search bar decides in the browser whether a question goes to the data engine. If its word
 * list misses a synonym the server understands, the question is answered as navigation instead
 * ("How much did Anson give" → "Donation/Give is under Finance"). This keeps the two in step, and
 * pins the bar's other rules: specific data questions go to the server before the "open a page"
 * shortcut, and public links are rendered only as same-site relative links.
 */
@DisplayName("AI Search — the bar's vocabulary and data-first rule")
class AiSearchBarVocabularyTest {

    static String engine() throws IOException { return Files.readString(Paths.get("src/main/resources/static/ai-assistant.js")); }

    static Pattern jsRegex(String js, String fn) {
        int at = js.indexOf("function " + fn + "(q){");
        assertThat(at).as(fn).isPositive();
        Matcher m = Pattern.compile("return /(.+?)/i\\.test\\(q\\)").matcher(js.substring(at, at + 2500));
        assertThat(m.find()).as(fn + " regex").isTrue();
        return Pattern.compile(m.group(1).replace("\\u2019", "’"), Pattern.CASE_INSENSITIVE);
    }

    @Test @DisplayName("every synonym the server maps also sends the question to the server")
    void barKnowsEveryServerSynonym() throws IOException {
        Pattern data = jsRegex(engine(), "looksLikeDataQuestion");
        List<String> missing = new ArrayList<>();
        for (String v : AiQueryLexicon.allVariants()) if (!data.matcher("x " + v + " y").find()) missing.add(v);
        assertThat(missing).as("synonyms the bar would answer as navigation").isEmpty();
    }

    @Test @DisplayName("the user's four example questions are data questions AND specific (so no page shortcut)")
    void examplesGoToTheServerFirst() throws IOException {
        String js = engine();
        Pattern data = jsRegex(js, "looksLikeDataQuestion");
        int at = js.indexOf("function looksSpecific(q){");
        String body = js.substring(at, js.indexOf("\n  }", at));
        List<Pattern> specific = new ArrayList<>();
        Matcher m = Pattern.compile("/(.+?)/(i?)\\.test\\(q\\)").matcher(body);
        while (m.find()) specific.add(Pattern.compile(m.group(1).replace("\\u2019", "’"), m.group(2).isEmpty() ? 0 : Pattern.CASE_INSENSITIVE));
        assertThat(specific).hasSizeGreaterThanOrEqualTo(3);
        for (String q : new String[] { "What is Anson's contribution this month?", "How much has Anson donated this month?",
                                       "How much did Anson give this month?", "Show Anson's donations this month." }) {
            assertThat(data.matcher(q).find()).as(q).isTrue();
            assertThat(specific.stream().anyMatch(p -> p.matcher(q).find())).as(q + " is specific").isTrue();
        }
        // A plain page command stays a page command.
        for (String q : new String[] { "Show donations", "Go to Events", "Open expenses" })
            assertThat(specific.stream().anyMatch(p -> p.matcher(q).find())).as(q).isFalse();
    }

    @Test @DisplayName("specific data questions are asked of the server before the direct-navigation shortcut, which runs only if unhandled")
    void dataFirstOrder() throws IOException {
        String js = engine();
        assertThat(js).contains("var dataFirst = looksLikeDataQuestion(query) && looksSpecific(query);")
                      .contains("if (!dataFirst && directNav(query)){")
                      .contains("if (dataFirst && directNav(query)){ if(input) input.value=''; assistDone(g); return; }");
        assertThat(js.indexOf("if (!dataFirst && directNav(query)){")).isLessThan(js.indexOf("tryDataSearch(query).then("));
    }

    @Test @DisplayName("public links render the screen name as the link, and only for a same-site relative address")
    void publicLinksAreSafeNamedLinks() throws IOException {
        String js = engine();
        assertThat(js).contains("Array.isArray(d.publicLinks)")
                      .contains("var safe=/^\\/(?!\\/)[^\\s\"'<>\\\\]*$/.test(url);")
                      .contains("'<a href=\"'+esc(url)+'\" target=\"_blank\" rel=\"noopener\">'+esc(l.label||'Public screen')+'</a>'");
        Pattern safe = Pattern.compile("^/(?!/)[^\\s\"'<>\\\\]*$");
        assertThat(safe.matcher("/viewEventCalendar?cid=abc").matches()).isTrue();
        assertThat(safe.matcher("/donate/abc").matches()).isTrue();
        for (String bad : new String[] { "javascript:alert(1)", "//evil.example/x", "https://evil.example", "/x\" onmouseover=\"y", "" })
            assertThat(safe.matcher(bad).matches()).as(bad).isFalse();
        String chat = Files.readString(Paths.get("src/main/resources/static/ai-assistant.html"));
        assertThat(chat).contains(".aa-summary, .aa-tablewrap, .aa-publinks, .aa-more, .aa-actions");
    }

    @Test @DisplayName("the Meetings page opens the meeting the AI link names (?meetingId=), digits only, via its own editMeeting()")
    void meetingsPageOpensLinkedMeeting() throws IOException {
        String page = Files.readString(Paths.get("src/main/resources/static/meeting.html"));
        assertThat(page).contains("new URLSearchParams(window.location.search).get('meetingId')")
                        .contains("/^\\d{1,10}$/.test(wantId)")
                        .contains("await editMeeting(parseInt(wantId, 10));");
    }
}
