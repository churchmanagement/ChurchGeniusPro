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
 * Ratchet from the 2026-10-28 logging audit: a log statement must not hand the logger a
 * recipient address or phone number, a provider response body, or a mail / SMS exception
 * object — those are exactly the arguments that put PII into application-*.log, and the
 * masking layout only knows credentials. Wrap the value in {@code EmailMask.mask},
 * {@code PhoneMask.mask}, {@code LogSafe.text} or {@code LogSafe.exception} instead.
 *
 * <p>Checked: every argument expression inside a {@code log.xxx(…)} call in {@code src/main/java}.
 * A raise of a ceiling here needs a comment saying why the value is safe.
 */
@DisplayName("Architecture — log statements must not pass raw addresses, numbers or provider bodies")
class SensitiveLogArgumentsTest {

    static final Path ROOT = Paths.get("src/main/java");
    static final Pattern LOG_CALL = Pattern.compile("\\b(?:log|LOG|logger|LOGGER)\\.(?:info|warn|error|debug|trace)\\(");
    /** Argument expressions that are PII unless wrapped. */
    static final Pattern RAW = Pattern.compile(
            "(?<![\\w.])(?:toNumber|e164|toPhone|normalized|gmEmail|recipient)(?![\\w(])"   // bare phone / address variables
          + "|\\.(?:getEmail|getPhone|getProspectEmail|getBillToEmail|getSubmitterEmail|getPhoneNumber)\\(\\)"
          + "|\\bresp\\.body\\(\\)"                                                     // provider response bodies
          + "|\\br\\.(?:email|phone)\\(\\)");
    static final Pattern WRAPPED = Pattern.compile("(?:EmailMask\\.mask(?:All)?|PhoneMask\\.mask(?:All)?|LogSafe\\.(?:text|exception)|\\.length\\(\\))");

    @Test
    void logArgumentsAreMasked() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f);
                Matcher m = LOG_CALL.matcher(src);
                while (m.find()) {
                    int end = closingParen(src, m.end());
                    if (end < 0) continue;
                    // Only code, not message text: the format string may legitimately say "recipient".
                    String args = src.substring(m.end(), end).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
                    Matcher raw = RAW.matcher(args);
                    while (raw.find()) {
                        // Wrapped when a mask helper opens before the raw expression on the same argument.
                        int argStart = Math.max(args.lastIndexOf(',', raw.start()), 0);
                        String before = args.substring(argStart, raw.start());
                        if (!WRAPPED.matcher(before).find()) {
                            int line = 1 + (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                            offenders.add(ROOT.relativize(f) + ":" + line + "  " + raw.group().trim());
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("log statements passing a raw address / number / provider body (wrap with EmailMask, PhoneMask or LogSafe)")
                .isEmpty();
    }

    /** Index of the parenthesis closing the call that opened just before {@code from}; -1 if unbalanced. */
    static int closingParen(String s, int from) {
        int depth = 1; boolean str = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) { if (c == '\\') i++; else if (c == '"') str = false; continue; }
            if (c == '"') str = true;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }
}
