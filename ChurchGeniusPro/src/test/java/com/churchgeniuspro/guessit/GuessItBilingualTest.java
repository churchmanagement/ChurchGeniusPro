package com.churchgeniuspro.guessit;

import com.churchgeniuspro.util.GuessItText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Guess It games written in two languages (2026-11-08): "Moses | മോശെ". */
class GuessItBilingualTest {

    private static final List<String> OPTIONS = List.of(
            "Joshua | യോശുവ", "Moses | മോശെ", "Aaron | അഹരോൻ", "Joseph | യോസേഫ്", "Daniel | ദാനീയേൽ");

    @Test
    @DisplayName("the correct answer may be typed in either language or with another separator")
    void answerMatchesOptionByAnyLanguage() {
        for (String typed : List.of("Moses – മോശെ", "Moses | മോശെ", "Moses - മോശെ", "Moses / മോശെ",
                                    "moses", "MOSES", "മോശെ", "* Moses | മോശെ", "  Moses  ")) {
            assertEquals("Moses | മോശെ", GuessItText.canonicalAnswer(OPTIONS, typed), typed);
        }
    }

    @Test
    @DisplayName("an answer naming no option, or more than one, is refused")
    void unknownOrAmbiguousAnswer() {
        assertNull(GuessItText.canonicalAnswer(OPTIONS, "Abraham"));
        assertNull(GuessItText.canonicalAnswer(OPTIONS, ""));
        assertNull(GuessItText.canonicalAnswer(OPTIONS, "Moses | യോശുവ"), "parts of two different options");
        assertNull(GuessItText.canonicalAnswer(List.of("Mary | മറിയ", "Mary Magdalene | മഗ്ദലന മറിയ"), "മറിയ x"));
    }

    @Test
    @DisplayName("single-language games behave exactly as before")
    void plainOptionsUnchanged() {
        List<String> plain = List.of("Eve", "Sarah", "Ruth", "Esther");
        assertEquals("Ruth", GuessItText.canonicalAnswer(plain, "Ruth"));
        assertEquals("Ruth", GuessItText.canonicalAnswer(plain, "ruth"));
        assertNull(GuessItText.canonicalAnswer(plain, "Naomi"));
    }

    @Test
    @DisplayName("clue boxes keep the line break between a clue and its translation")
    void cluesShowTwoLines() throws Exception {
        String page = Files.readString(Path.of("src/main/resources/static/guessIt.html"));
        assertTrue(page.contains("function giParseClues"));
        assertTrue(page.contains(".gg-clue{white-space:pre-line;"));
        assertTrue(page.contains("white-space: pre-line; }"), ".clue-text on the big screen");
        assertEquals(2, page.split("giReadGameForm\\(", -1).length - 2, "both Create paths use the shared parser");
    }
}
