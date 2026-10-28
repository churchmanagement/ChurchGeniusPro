package com.churchgeniuspro.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The masker is the last thing standing between a credential and a 30-day log file, so
 * these tests are written as "this exact secret must not survive" rather than "the output
 * looks about right".
 */
class SensitiveDataMaskerTest {

    /** A distinctive value: if it appears anywhere in the output, masking failed. */
    private static final String SECRET = "Sup3rS3cretV4lue!";

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("credentials never survive masking, in any common serialisation")
    @ValueSource(strings = {
            // JSON bodies — the login endpoint's own request shape
            "{\"username\":\"john@example.com\",\"password\":\"Sup3rS3cretV4lue!\"}",
            "{\"newPassword\": \"Sup3rS3cretV4lue!\", \"token\": \"abc\"}",
            "{ \"clientSecret\" : \"Sup3rS3cretV4lue!\" }",
            // Map.toString(), which is how a logged request body usually appears
            "{username=john, password=Sup3rS3cretV4lue!, rememberMe=true}",
            // query strings and form bodies
            "POST /login?password=Sup3rS3cretV4lue!&user=john",
            "grant_type=client_credentials&client_secret=Sup3rS3cretV4lue!",
            // headers
            "Authorization: Bearer Sup3rS3cretV4lue!",
            "authorization=Basic Sup3rS3cretV4lue!",
            "Cookie: JSESSIONID=Sup3rS3cretV4lue!; other=1",
            "Set-Cookie: SESSION=Sup3rS3cretV4lue!; HttpOnly",
            // prose, as it appears in a hand-written log line
            "Login failed, password: Sup3rS3cretV4lue!",
            "apiKey = Sup3rS3cretV4lue!",
            "refresh_token=Sup3rS3cretV4lue!",
            "rememberToken=Sup3rS3cretV4lue!",
            "PLAID_TOKEN_ENC_KEY=Sup3rS3cretV4lue!",
    })
    void secretsAreRedacted(String line) {
        String masked = SensitiveDataMasker.mask(line);
        assertFalse(masked.contains(SECRET),
                () -> "secret leaked through masking!\n  in:  " + line + "\n  out: " + masked);
        assertTrue(masked.contains(SensitiveDataMasker.REDACTED),
                () -> "expected a redaction marker in: " + masked);
    }

    @Test
    @DisplayName("a BCrypt hash is redacted — a stored hash is still a credential")
    void bcryptHashIsRedacted() {
        String line = "stored=$2a$10$6pi7JA3CYLVakVOrebM9Be5p5xbHQ92y/gWS3Mr07agmZ4XkCWu0K";
        String masked = SensitiveDataMasker.mask(line);
        assertFalse(masked.contains("$2a$10$6pi7JA3CYLVakVOrebM9Be"), masked);
    }

    @Test
    @DisplayName("vendor key shapes are caught even without a recognisable field name")
    void vendorKeyShapesAreRedacted() {
        assertFalse(SensitiveDataMasker.mask("calling openai with sk-abcdefghij0123456789XYZ")
                .contains("sk-abcdefghij0123456789XYZ"));
        // Built at runtime so the source never holds a literal Twilio-SID shape
        // (GitHub push protection rejects even fake ones).
        String fakeTwilioSid = "AC" + "0123456789abcdef".repeat(2);
        assertFalse(SensitiveDataMasker.mask("twilio sid " + fakeTwilioSid)
                .contains(fakeTwilioSid));
        assertFalse(SensitiveDataMasker.mask("plaid access-production-0123456789abcdef0123")
                .contains("access-production-0123456789abcdef0123"));
    }

    @Test
    @DisplayName("the field name survives so the line stays diagnosable")
    void fieldNamesAreKept() {
        String masked = SensitiveDataMasker.mask("{\"username\":\"john\",\"password\":\"hunter2\"}");
        assertTrue(masked.contains("password"), "the key is useful; only the value is not");
        assertTrue(masked.contains("john"), "non-secret fields must be left alone");
        assertFalse(masked.contains("hunter2"), masked);
    }

    @Test
    @DisplayName("the audit line format passes through untouched")
    void auditLinesAreNotMangled() {
        String auditLine = "LOGIN  | church=\"Grace Chapel\" | username=john@example.com | role=Admin "
                         + "| at=2026-08-17T09:14:22 | ip=203.0.113.7 | city=Olathe | state=Kansas "
                         + "| country=United States | sessionRef=9f2a1c4b8e01";
        String masked = SensitiveDataMasker.mask(auditLine);
        // sessionRef is a SHA-256 hash, deliberately named so it does NOT trip the
        // "session=" redaction rule — otherwise login and logout could not be paired up.
        assertTrue(masked.contains("sessionRef=9f2a1c4b8e01"), masked);
        assertTrue(masked.contains("username=john@example.com"));
        assertTrue(masked.contains("church=\"Grace Chapel\""));
        assertTrue(masked.contains("role=Admin"));
        assertTrue(masked.contains("ip=203.0.113.7"));
        assertTrue(masked.contains("city=Olathe"));
        assertTrue(masked.contains("state=Kansas"));
        assertTrue(masked.contains("country=United States"));
    }

    @Test
    @DisplayName("ordinary log lines are returned unchanged and cheaply")
    void ordinaryLinesAreUntouched() {
        String line = "Loaded 42 family members for client CHR-00001 in 13ms";
        assertSame(line, SensitiveDataMasker.mask(line),
                "a line with no trigger word should skip the regex work entirely");
    }

    @Test
    @DisplayName("null and empty input are handled")
    void nullSafety() {
        assertNull(SensitiveDataMasker.mask(null));
        assertEquals("", SensitiveDataMasker.mask(""));
    }

    @Test
    @DisplayName("a stack trace carrying a secret in its message is scrubbed")
    void stackTraceMessagesAreScrubbed() {
        String rendered = """
                java.lang.IllegalStateException: request failed: {"password":"Sup3rS3cretV4lue!"}
                \tat com.churchgeniuspro.service.Thing.call(Thing.java:42)
                \tat com.churchgeniuspro.controller.Other.go(Other.java:17)
                """;
        String masked = SensitiveDataMasker.mask(rendered);
        assertFalse(masked.contains(SECRET), masked);
        assertTrue(masked.contains("Thing.java:42"), "the trace itself must stay readable");
    }

    // ── 2026-10-28 logging audit additions ─────────────────────────────────────

    @Test
    @DisplayName("PostgreSQL constraint diagnostics: the rejected row and the duplicate key value are redacted")
    void postgresDetailsAreRedacted() {
        String failingRow = "ERROR: null value in column \"id\" violates not-null constraint\n"
                + "  Detail: Failing row contains (null, 100 Grace Ave, Springfield, demo.admin@gracefellowship.org, 555-200-1000).";
        String out = SensitiveDataMasker.mask(failingRow);
        assertFalse(out.contains("Grace Ave"), out);
        assertFalse(out.contains("gracefellowship"), out);
        assertTrue(out.contains("Failing row contains (" + SensitiveDataMasker.REDACTED + ")."), out);

        String dup = "ERROR: duplicate key value violates unique constraint \"uk_email\"\n  Detail: Key (email)=(ada@example.test) already exists.";
        String out2 = SensitiveDataMasker.mask(dup);
        assertFalse(out2.contains("ada@example.test"), out2);
        assertTrue(out2.contains("Key (email)=(" + SensitiveDataMasker.REDACTED + ") already exists"), out2);
    }

    @Test
    @DisplayName("Short link-token query parameters (?t=, ?k=, &code=, &otp=, &cid=) are redacted, other parameters kept")
    void shortTokenParametersAreRedacted() {
        String out = SensitiveDataMasker.mask("GET /api/invoice/view?t=AbC123xyz_-9&page=2 and /trialRequest.html?k=k9Lm2 and /pickup?id=7&code=482913&cid=CHR-1");
        assertFalse(out.contains("AbC123xyz"), out);
        assertFalse(out.contains("k9Lm2"), out);
        assertFalse(out.contains("482913"), out);
        assertFalse(out.contains("CHR-1"), out);
        assertTrue(out.contains("?t=" + SensitiveDataMasker.REDACTED + "&page=2"), out);
        assertTrue(out.contains("id=7&code=" + SensitiveDataMasker.REDACTED), out);
    }

    @Test
    @DisplayName("Plaid link / public tokens and E.164 numbers are redacted by shape; masked phone forms are left alone")
    void plaidTokensAndPhonesByShape() {
        String out = SensitiveDataMasker.mask("exchange link-sandbox-1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d then public-production-abcdefabcdef1234567890 to +19135550100 ok");
        assertFalse(out.contains("link-sandbox-1a2b"), out);
        assertFalse(out.contains("public-production-abc"), out);
        assertFalse(out.contains("+19135550100"), out);
        assertEquals("sent to ***-***-0100 at 2026-10-28 00:25:11", SensitiveDataMasker.mask("sent to ***-***-0100 at 2026-10-28 00:25:11"));
        assertEquals("order 12345 total 1234567", SensitiveDataMasker.mask("order 12345 total 1234567"));
    }
}
