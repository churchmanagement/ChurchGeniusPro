package com.churchgeniuspro.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-readiness audit (2026-10-07), Phase 3: every line of application output
 * must go through SLF4J so {@code MaskingPatternLayout} (logback-spring.xml) can redact
 * credentials, and so levels/retention apply. {@code System.out}/{@code System.err}
 * bypass all of that and {@code printStackTrace()} can echo request bodies and
 * third-party error payloads unmasked. Ratchet: zero sites in {@code src/main/java}.
 */
@DisplayName("No System.out / System.err / printStackTrace in src/main/java")
class NoStdoutLoggingTest {

    static final Path ROOT = Paths.get("src/main/java");
    static final String[] FORBIDDEN = { "System.out.print", "System.err.print", ".printStackTrace(" };

    @Test
    void applicationCodeLogsOnlyThroughSlf4j() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String code = stripLineComment(lines.get(i));
                    for (String token : FORBIDDEN) {
                        if (code.contains(token)) offenders.add(ROOT.relativize(f) + ":" + (i + 1) + "  " + token);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("stdout/stderr logging bypasses MaskingPatternLayout — use the class's SLF4J logger")
                .isEmpty();
    }

    /** Drops {@code // …} and the body of {@code * …} Javadoc lines so prose mentions don't count. */
    static String stripLineComment(String line) {
        String t = line.trim();
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return "";
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
