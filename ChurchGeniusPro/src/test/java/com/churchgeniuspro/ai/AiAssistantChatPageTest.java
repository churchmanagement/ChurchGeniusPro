package com.churchgeniuspro.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /ai-assistant is a chat window: the page renders the transcript through the optional
 * {@code window.CGP_ASSISTANT_UI} adapter that the shared engine (ai-assistant.js) calls;
 * without the adapter the engine keeps its results-panel behaviour on every other page.
 */
@DisplayName("AI Assistant chat page")
class AiAssistantChatPageTest {

    private static String page() throws IOException { return Files.readString(Paths.get("src/main/resources/static/ai-assistant.html")); }
    private static String engine() throws IOException { return Files.readString(Paths.get("src/main/resources/static/ai-assistant.js")); }

    @Test
    @DisplayName("page: transcript, composer with Send, Enter sends / Shift+Enter newline, typing indicator, auto-scroll, clear, sessionStorage")
    void chatStructure() throws IOException {
        String s = page();
        assertThat(s).contains("id=\"chatLog\"", "role=\"log\"", "<textarea id=\"aiSearchInput\"", "id=\"aiSearchBtn\"", ">Send<", "id=\"chatClear\"")
                .contains("e.key === 'Enter' && !e.shiftKey", "e.preventDefault(); send();")
                .contains("id = 'chatTyping'", "class=\"dot\"", "log.scrollTop = log.scrollHeight")
                .contains("sessionStorage.setItem(STORE_KEY", "sessionStorage.removeItem(STORE_KEY)")
                .contains("window.CGP_ASSISTANT_UI = {", "question: function", "answer: function", "busy: function", "notice: function");
        // The shared engine still finds its host and composer hooks.
        assertThat(s).contains("class=\"ai-search-wrap\"", "class=\"ai-search-bar\"", "id=\"aiResultsWrap\"");
        // No placeholder runAiSearch: plain questions must reach the engine's data/LLM/help flow.
        assertThat(s).doesNotContain("window.runAiSearch = function () {}");
        // Server text is escaped before formatting; the welcome examples are kept.
        assertThat(s).contains("function esc(s)", "function formatAnswer(text)", "Who are our volunteers?", "How do I add a family?");
        // Another user's transcript is never shown.
        assertThat(s).contains("d.user !== user");
    }

    @Test
    @DisplayName("engine: the adapter is optional — every hook checks for it and the default results-panel path is intact")
    void engineHookOptional() throws IOException {
        String e = engine();
        assertThat(e).contains("function uiHook(){ var u=window.CGP_ASSISTANT_UI;")
                .contains("uiCall('question', query, mode);")
                .contains("uiCall('answer', holder.firstChild, a, lastQ);")
                .contains("if (uiCall('notice', msg)) return;")
                .contains("if (uiCall('busy', !!on)) return;");
        // Exactly one place reports a question (runAssist) and one renders answers (renderCard).
        assertThat(e.split("uiCall\\('question'", -1)).hasSize(2);
        assertThat(e.split("uiCall\\('answer'", -1)).hasSize(2);
        // Default path still writes the panel into #aiResultsWrap and wires its buttons.
        assertThat(e).contains("wrap.innerHTML=html; wrap.style.display='block';")
                .contains("b.addEventListener('click', function(){ open(b.getAttribute('data-url')");
        assertThat(e).contains("open:open,");   // exposed so restored transcript buttons use the same navigation
    }
}
