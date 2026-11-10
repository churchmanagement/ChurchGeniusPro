package com.churchgeniuspro.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-10: the Genius Assistant panel's full screen fills the whole window and can always be left; Minimize and
 * Close stay reachable; in the Android / iOS app the panel starts below the status bar (safe-area inset).
 */
@DisplayName("Genius Assistant window: full screen, exit, minimize, close, status bar")
class GeniusPanelWindowTest {

    static String chat() throws IOException { return Files.readString(Paths.get("src/main/resources/static/ai-chat.js")); }

    @Test @DisplayName("full screen is an in-place toggle (not a link), Esc leaves it first, state kept for the tab")
    void fullScreen() throws IOException {
        String s = chat();
        assertThat(s).contains("id=\"gaFull\"", "aria-pressed=\"false\"", "function setFull(on)",
                "'html.ga-open.ga-full .ga-panel{left:0;right:0;width:auto;max-width:none;height:auto;bottom:0;",
                "b.title = st.full ? 'Exit full screen (Esc)' : 'Full screen';",
                "if (st.full) { setFull(false); return; }", "full: st.full", "if (!pageMode && d.full) setFull(true);")
            .doesNotContain("id=\"gaExpand\"");
        // the full-page view is still reachable from the menu
        assertThat(s).contains("<a role=\"menuitem\" id=\"gaMPage\" href=\"/ai-assistant\">↗ Open as a page</a>");
    }

    @Test @DisplayName("minimize and close are separate buttons in every mode; minimize keeps the launcher")
    void minimizeAndClose() throws IOException {
        String s = chat();
        assertThat(s).contains("id=\"gaMin\"", "id=\"gaClose\"", "function minimize()",
                "'html.ga-min:not(.ga-open) .ga-fab{display:flex;}'", "document.documentElement.classList.remove('ga-min');");
    }

    @Test @DisplayName("the panel starts below the status bar in the app (safe-area inset), incl. the phone keyboard sizing")
    void statusBar() throws IOException {
        String s = chat();
        assertThat(s).contains("'.ga-panel{position:fixed;top:env(safe-area-inset-top,0px);",
                "height:calc(100dvh - env(safe-area-inset-top,0px))", "function safeTop()",
                "el.panel.style.top = (window.visualViewport.offsetTop + top) + 'px';");
    }
}
