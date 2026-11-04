package com.churchgeniuspro.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-03 display-only changes: the View Family member popup hides the Volunteer chip
 * for members who don't volunteer and the Follow-ups chip at 0, and the Income / Expense
 * entry forms can be minimised (shared form-collapse.js) without touching their logic.
 */
@DisplayName("Hidden zero chips + collapsible Income/Expense forms")
class FormCollapseAndZeroChipsTest {

    private static String read(String f) throws Exception {
        return Files.readString(Path.of("src/main/resources/static", f));
    }

    @Test @DisplayName("Member chips: no 'Not volunteering', no 'Follow-ups · 0', no empty chip row")
    void zeroChipsHidden() throws Exception {
        String vf = read("viewFamily.html");
        assertThat(vf).doesNotContain("Not volunteering")
                .contains("if (inv.volunteer) out.push(")
                .contains("if (n > 0) out.push(`<span class=\"fam-chip fam-chip-fu\"")
                .contains("(c => c ? `<div class=\"inv-row-chips\">${c}</div>` : '')(memberChips(inv))")
                .contains("if (n > 0) out.push(`<span class=\"fam-chip fam-chip-grp\"")
                .doesNotContain("fam-chip-grp${n ? '' : ' dim'}");
    }

    @Test @DisplayName("Both pages load form-collapse.js and expand the form when a row is edited")
    void pagesWired() throws Exception {
        String inc = read("income.html"), exp = read("expense.html");
        assertThat(inc).contains("<script src=\"/form-collapse.js\"></script>")
                .contains("CGP_formCollapse.init({ card: 'formCard', head: '.card-header'")
                .contains("CGP_formCollapse.expand('formCard')")
                .contains("<div class=\"card\" id=\"formCard\">");
        assertThat(exp).contains("<script src=\"/form-collapse.js\"></script>")
                .contains("CGP_formCollapse.init({ card: 'expenseFormCard', head: '.form-card-head'")
                .contains("CGP_formCollapse.expand('expenseFormCard')")
                .contains("<span id=\"formCardTitle\">New Expense</span>");
        // the title text is replaced on edit, so the control must not live inside #formCardTitle
        assertThat(exp).contains("<div class=\"form-card-title form-card-head\"><span id=\"formCardTitle\">");
    }

    @Test @DisplayName("form-collapse.js: CSS-only hiding, voice-safe, storage guarded")
    void helperRules() throws Exception {
        String js = read("form-collapse.js");
        assertThat(js).contains(".cgp-fc-collapsed > :not(.cgp-fc-head){display:none !important;}")
                .contains("aria-expanded")
                .contains("f.busy()")
                .contains("f.btn.disabled = !collapsed && busy;")
                .contains("#voiceMicBtn, #voiceConverseBtn")
                .contains("card.scrollIntoView = function")
                .contains("catch (e) { return null; }");
        assertThat(js).doesNotContain("innerHTML = ''").doesNotContain(".value = ''").doesNotContain("fetch(");
    }

    @Test @DisplayName("Meetings: Add Meeting form uses the shared minimise control, safe with the meeting assistant")
    void meetingsWired() throws Exception {
        String m = read("meeting.html");
        assertThat(m).contains("<script src=\"/form-collapse.js\"></script>")
                .contains("card: 'meetingFormCard', head: '.form-card-header', title: '#formTitle'")
                .contains("busy: function () { return shown('mvStopBtn') || shown('mvListening'); }")
                .contains("expandOn: '#mvMode_voice, #mvMode_converse, #mvSend'")
                .contains("CGP_formCollapse.expand('meetingFormCard')")
                .contains("<h3 id=\"formTitle\">Add Meeting</h3>");
    }

    @Test @DisplayName("Events Past: responsive card grid (max 3 per row), long names clamp, every action kept")
    void pastGrid() throws Exception {
        String e = read("events.html");
        assertThat(e).contains(".past-list { display:grid;")
                .contains("minmax(min(100%, max(240px, calc((100% - 24px) / 3))), 1fr)")
                .contains("-webkit-line-clamp:2")
                .contains("'<div class=\"past-name\" title=\"' + esc(ev.eventName) + '\">'");
        for (String a : new String[] {"'Check-In'", "'Edit'", "'Registrants'", "'Volunteers'", "'Invite'",
                "'Register Page'", "'Copy registration link'", "'Delete'"}) {
            assertThat(e).contains("pastAct(").contains(a);
        }
    }
}
