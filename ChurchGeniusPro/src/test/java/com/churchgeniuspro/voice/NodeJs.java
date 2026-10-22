package com.churchgeniuspro.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs a snippet against the page's own JavaScript (voice-core.js) in Node, so the
 * browser-side parsing/matching rules are tested as shipped, not re-implemented in
 * Java. Node is present on the GitHub runner and on developer machines with the
 * e2e tooling; where it is missing the tests are skipped, never failed.
 */
final class NodeJs {
    private static final ObjectMapper M = new ObjectMapper();

    static boolean available() {
        try {
            Process p = new ProcessBuilder(nodeBin(), "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    static void assumeAvailable() { Assumptions.assumeTrue(available(), "node is not installed — JS rule tests skipped"); }

    private static String nodeBin() {
        String env = System.getenv("NODE_BIN");
        return (env != null && !env.isBlank()) ? env : "node";
    }

    /** Evaluates {@code expr} with {@code VoiceCore} (from the static folder) in scope; returns its JSON. */
    static JsonNode evalVoiceCore(String expr) throws Exception {
        String script = "const vm=require('vm');const fs=require('fs');const ctx={};vm.createContext(ctx);"
                + "vm.runInContext(fs.readFileSync(process.argv[2],'utf8'),ctx);const VoiceCore=ctx.VoiceCore;"
                + "const out=(function(){ return " + expr + "; })();"
                + "process.stdout.write(JSON.stringify(out===undefined?null:out));";
        Path tmp = Files.createTempFile("voicecore-", ".cjs");   // .cjs: CommonJS regardless of any package.json nearby
        Files.writeString(tmp, script, StandardCharsets.UTF_8);
        try {
            Process p = new ProcessBuilder(nodeBin(), tmp.toString(), new File("src/main/resources/static/voice-core.js").getAbsolutePath())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("node failed: " + out);
            return M.readTree(out);
        } finally { Files.deleteIfExists(tmp); }
    }

    /**
     * Runs the Meetings Converse state machine (meeting-voice.js, as shipped) through the
     * fake-DOM harness in src/test/resources/voice; {@code scenarioJson} is the harness input.
     */
    static JsonNode meetingScenario(String scenarioJson) throws Exception {
        Process p = new ProcessBuilder(nodeBin(),
                new File("src/test/resources/voice/meeting-harness.cjs").getAbsolutePath(),
                new File("src/main/resources/static/voice-core.js").getAbsolutePath(),
                new File("src/main/resources/static/meeting-voice.js").getAbsolutePath(),
                scenarioJson).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("node failed: " + out);
        return M.readTree(out);
    }
}
