package com.churchgeniuspro.trialtips;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The tip catalogue and loader: every tip is well formed, keys unique, permission/feature keys real, script loaded by the shell. */
@DisplayName("Trial tips script")
class TrialTipsScriptTest {

    static String read(String p) throws IOException { return Files.readString(Paths.get(p)); }

    @Test @DisplayName("shell.js loads trial-tips.js after the product tour; the script is a single-instance, self-activating module")
    void loaded() throws IOException {
        String shell = read("src/main/resources/static/shell.js");
        assertThat(shell).contains("add('/trial-tips.js', 'trial-tips-script');");
        assertThat(shell.indexOf("/product-tour.js")).isLessThan(shell.indexOf("/trial-tips.js"));
        String js = read("src/main/resources/static/trial-tips.js");
        assertThat(js).contains("if (window.__CGP_TRIAL_TIPS__) return;", "/api/trial-tips/state", "post(tip.key, 'shown')", "post(tip.key, 'dismissed')")
                .contains("cgpDemoTrialOverlay", "cgpTourOverlay", "cgpTrialNotice")   // never on top of the agreement, the tour or the trial notice
                .contains("MIN_GAP_MS = 10 * 60 * 1000", "MAX_PER_DAY = 3", "MAX_SHOWS = 2")
                .contains("position:fixed").doesNotContain("inset:0").doesNotContain(".focus()")
                .contains("aria-label=\"Close tip\"", "Got it");
    }

    @Test @DisplayName("every tip has a unique key, title, text and pages; permission and feature keys exist in session.js")
    void catalogue() throws IOException {
        String js = read("src/main/resources/static/trial-tips.js");
        String session = read("src/main/resources/static/session.js");
        String tips = js.substring(js.indexOf("var TIPS = ["), js.indexOf("];", js.indexOf("var TIPS = [")));
        Matcher m = Pattern.compile("\\{ key: '([a-z0-9-]+)', title: '([^']+)', pages: \\[([^\\]]+)\\](.*?)\\n\\s+text: '([^']+)'", Pattern.DOTALL).matcher(tips);
        Set<String> keys = new LinkedHashSet<>(); int n = 0;
        Set<String> perms = new HashSet<>(), feats = new HashSet<>();
        Matcher pm = Pattern.compile("'(?:[a-z-]+|/[^']*)':\\s*'([a-z]+\\.[a-z]+)'").matcher(session); while (pm.find()) perms.add(pm.group(1));
        Matcher fm = Pattern.compile("'/[^']*':\\s*'([a-zA-Z]+)'").matcher(session); while (fm.find()) feats.add(fm.group(1));
        while (m.find()) {
            n++;
            assertThat(keys.add(m.group(1))).as("duplicate key " + m.group(1)).isTrue();
            assertThat(m.group(1)).matches("^[a-z0-9][a-z0-9-]{1,39}$");
            assertThat(m.group(2).length()).isBetween(8, 60);
            assertThat(m.group(5).length()).isBetween(40, 260);
            String attrs = m.group(4);
            Matcher p = Pattern.compile("perm: '([^']+)'").matcher(attrs);
            if (p.find()) assertThat(perms).as("permission key " + p.group(1)).contains(p.group(1));
            Matcher f = Pattern.compile("feature: '([^']+)'").matcher(attrs);
            if (f.find()) assertThat(feats).as("feature key " + f.group(1)).contains(f.group(1));
        }
        assertThat(n).isGreaterThanOrEqualTo(23);
        assertThat(keys).contains("dashboard-layout", "favorites", "membership-form-scan", "bank-sync", "payroll", "ai-fill", "meeting-cleanup",
                "worship-auto-assign", "ai-search", "reminders", "public-screen-links", "song-book", "ntag-landing", "advanced-help", "portals",
                "stripe", "linked-accounts", "temporary-access");
        assertThat(js).contains("after 90 days");   // meetings purge is 90 days, not 30
    }
}
