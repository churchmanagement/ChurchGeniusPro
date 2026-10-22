package com.churchgeniuspro.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Search Phase F — the bar's UI contract: one turn at a time (Enter / Search / a new utterance
 * waits while an answer is in flight), a 30 s client-side watchdog that aborts and tells the user,
 * a scrollable results panel, small-screen rules, ARIA on the input / results / tables, and a help
 * modal that describes data questions, period reports and meeting creation.
 */
@DisplayName("AI Search — UI polish (Phase F)")
class AiSearchUiPolishTest {

    static String engine() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/ai-assistant.js"));
    }

    @Test @DisplayName("one turn at a time: a second Enter while busy is refused with a hint, and Stop / completion / timeout release the bar")
    void oneTurnAtATime() throws IOException {
        String e = engine();
        assertThat(e).contains("if (_inFlight && !_stopped){ flashHint('Still working on your last question");
        assertThat(e).contains("_inFlight=true; setResultsBusy(true);");
        // released on completion (assistDone), on Stop, and by the watchdog
        assertThat(e).contains("_assistBusy = false;\n    endTurn();");
        assertThat(e).contains("_stopped = true;\n    endTurn();");
        assertThat(e).contains("busy(false); endTurn();");
        // the confirm click (Phase E) obeys the same rule
        assertThat(e).contains("var g=++runGen; _stopped=false; _inFlight=true; setResultsBusy(true);");
    }

    @Test @DisplayName("30 s watchdog: requests are aborted, a timeout card is shown, nothing stale can render afterwards")
    void watchdog() throws IOException {
        String e = engine();
        assertThat(e).contains("var AA_TIMEOUT_MS = 30000;");
        assertThat(e).contains("_turnTimer = aaAfter(AA_TIMEOUT_MS, function(){ if (aaLive(g)) onTurnTimeout(g); });");
        assertThat(e).contains("function onTurnTimeout(g){\n    runGen++;");
        assertThat(e).contains("intent:'timeout'");
        assertThat(e).contains("took too long to respond");
        // every assistant request carries the abortable signal
        assertThat(e.split("signal:aaSignal\\(\\)", -1).length - 1).isGreaterThanOrEqualTo(4);
    }

    @Test @DisplayName("results panel scrolls, small screens stack the controls, reduced motion is respected")
    void layout() throws IOException {
        String e = engine();
        assertThat(e).contains("#aiResultsWrap .ai-results-panel{").contains("max-height:min(65vh,560px);overflow:auto;");
        assertThat(e).contains("@media (max-width:640px){");
        assertThat(e).contains("#aiSearchInput.aa-input,.ai-search-wrap .ai-search-input{flex:1 1 100%;");
        assertThat(e).contains("@media (prefers-reduced-motion:reduce)");
        assertThat(e).contains(".aa-tablewrap{margin-top:10px;max-height:320px;overflow:auto;");
    }

    @Test @DisplayName("ARIA: labelled input and results region, live hint, busy state, table headers with scope, labelled close")
    void aria() throws IOException {
        String e = engine();
        assertThat(e).contains("aria-label=\"Ask the AI assistant\"");
        assertThat(e).contains("hi.setAttribute('aria-label','Ask the AI assistant')");           // home page input too
        assertThat(e).contains("resWrap.setAttribute('role','region'); resWrap.setAttribute('aria-label','Assistant results'); resWrap.setAttribute('aria-live','polite');");
        assertThat(e).contains("res.setAttribute('role','region');");                               // home page results too
        assertThat(e).contains("role=\"status\" aria-live=\"polite\"");
        assertThat(e).contains("w.setAttribute('aria-busy', on?'true':'false');");
        assertThat(e).contains("<th scope=\"col\">");
        assertThat(e).contains("aria-label=\"Close results\"");
        assertThat(e).contains("bar.setAttribute('role','toolbar');");
        assertThat(e).contains("role=\"dialog\" aria-modal=\"true\"");
    }

    @Test @DisplayName("help modal describes data questions, period reports, meeting creation, permissions and the one-at-a-time rule")
    void helpModal() throws IOException {
        String e = engine();
        int a = e.indexOf("function searchHelpHtml(){"), b = e.indexOf("function injectUI(){");
        String help = e.substring(a, b);
        assertThat(help).contains("Church data", "Reports for a period", "Create a meeting", "How-to questions", "Navigation");
        assertThat(help).contains("Create meeting", "Recurring meetings, locations and images are set up on the Meetings page");
        assertThat(help).contains("What you see depends on your access", "first 20 rows", "gross amount", "processing fees");
        assertThat(help).contains("within 30 seconds");
    }
}
