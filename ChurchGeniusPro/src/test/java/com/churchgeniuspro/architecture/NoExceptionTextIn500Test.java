package com.churchgeniuspro.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet (error-handling audit 2026-10-28, H4): a 500 body never carries {@code getMessage()}.
 *
 * <p>Hibernate/JDBC messages contain the failing row's values and constraint names, POI/PDFBox
 * messages contain paths, provider replies contain whatever the provider said. The log layer
 * masks those; the HTTP layer must not send them. Use {@code ErrorResponses.internal(log, "what", ex)}
 * (or let {@code GlobalExceptionHandler} answer) — the user gets a reference, the log gets the text.
 *
 * <p>Checked: every {@code status(500)} / {@code internalServerError()} / {@code INTERNAL_SERVER_ERROR}
 * expression in {@code src/main/java}, together with the two lines that follow it, for a
 * {@code getMessage()} call; and every {@code put("message", x.getMessage())} within three lines
 * before a {@code status(500)}.
 */
@DisplayName("Ratchet — no exception text in 500 bodies")
class NoExceptionTextIn500Test {

    static final Path ROOT = Paths.get("src/main/java");
    static final Pattern FIVE_HUNDRED = Pattern.compile("status\\(500\\)|internalServerError\\(\\)|HttpStatus\\.INTERNAL_SERVER_ERROR");

    @Test
    void noGetMessageIn500Bodies() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    if (!FIVE_HUNDRED.matcher(lines.get(i)).find()) continue;
                    // the 500 expression and the two lines after it (multi-line .body(...))
                    for (int j = i; j < Math.min(lines.size(), i + 3); j++) {
                        if (lines.get(j).contains("getMessage()") && !lines.get(j).contains("ErrorResponses")) {
                            problems.add(p + ":" + (j + 1) + "  " + lines.get(j).trim());
                        }
                    }
                    // map-style bodies built just before: resp.put("message", e.getMessage()); ... status(500)
                    for (int j = Math.max(0, i - 3); j < i; j++) {
                        Matcher m = Pattern.compile("put\\(\"(message|error)\",\\s*\\w+\\.getMessage\\(\\)\\)").matcher(lines.get(j));
                        if (m.find()) problems.add(p + ":" + (j + 1) + "  " + lines.get(j).trim());
                    }
                }
            }
        }
        assertThat(problems).as("500 responses that would send exception text to the client — use ErrorResponses.internal").isEmpty();
    }
}
