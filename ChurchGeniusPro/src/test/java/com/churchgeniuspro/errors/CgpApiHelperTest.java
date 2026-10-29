package com.churchgeniuspro.errors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract of static/cgp-api.js (error-handling audit 2026-10-28, M4 / M6 / L11).
 * Its behaviour is exercised in a browser by the Playwright checks listed in the
 * phase report; this pins the rules that make it safe to rely on.
 */
@DisplayName("cgp-api.js — the shared /api helper contract")
class CgpApiHelperTest {

    static String js() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/cgp-api.js"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("exports CGP_api, CGP_apiJson, CGP_errorMessage, CGP_showError, CGP_sendOutcome")
    void exports() throws IOException {
        assertThat(js()).contains("window.CGP_api = CGP_api;")
                        .contains("window.CGP_apiJson = CGP_apiJson;")
                        .contains("window.CGP_errorMessage = CGP_errorMessage;")
                        .contains("window.CGP_showError = CGP_showError;")
                        .contains("window.CGP_sendOutcome = CGP_sendOutcome;")
                        .contains("if (window.CGP_api) return;");   // loaded twice → harmless
    }

    @Test
    @DisplayName("parses JSON only when the server says it is JSON — a proxy's HTML page is never a SyntaxError")
    void parsesByContentType() throws IOException {
        String s = js();
        assertThat(s).contains("return /json/i.test(ct);")
                     .contains("return res.text()")
                     .contains("if (res.status === 204 || res.status === 205)");
    }

    @Test
    @DisplayName("message = body.error || body.message || a sentence for the status, with the reference appended")
    void messageRules() throws IOException {
        String s = js();
        assertThat(s).contains("if (typeof body.error === 'string' && body.error.trim())")
                     .contains("else if (typeof body.message === 'string' && body.message.trim())")
                     .contains("if (ref && msg.indexOf(ref) === -1) msg += ' (ref ' + ref + ')';")
                     .contains("if (status === 413) return 'File too large (max ' + MAX_UPLOAD + ').';")
                     .contains("var MAX_UPLOAD = '15 MB';");
    }

    @Test
    @DisplayName("rejects on any non-2xx and on a network failure (status 0); runs through window.fetch so the 401/403 interceptors still apply")
    void rejectsAndKeepsInterceptors() throws IOException {
        String s = js();
        assertThat(s).contains("p = window.fetch(url, opts);")
                     .contains("if (res.ok && !r.unreadable) return r.body;")
                     .contains("throw CgpApiError({ status: 0, message: sentenceFor(0), body: null, url: url });")
                     .contains("if (opts.credentials === undefined) opts.credentials = 'same-origin';");
    }

    @Test
    @DisplayName("never shows an alert() itself — the page's toast, or a small built-in one")
    void noAlert() throws IOException {
        String s = js();
        assertThat(s).doesNotContain("window.alert(").doesNotContain(" alert(");
        assertThat(s).contains("window.CGP_notify").contains("builtInToast(msg);");
    }
}
