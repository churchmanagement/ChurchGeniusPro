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
                .contains("(c => c ? `<div class=\"inv-row-chips\">${c}</div>` : '')(memberChips(inv))");
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
                .contains("voiceActive()")
                .contains("f.btn.disabled = !collapsed && busy;")
                .contains("#voiceMicBtn, #voiceConverseBtn")
                .contains("card.scrollIntoView = function")
                .contains("catch (e) { return null; }");
        assertThat(js).doesNotContain("innerHTML = ''").doesNotContain(".value = ''").doesNotContain("fetch(");
    }
}
