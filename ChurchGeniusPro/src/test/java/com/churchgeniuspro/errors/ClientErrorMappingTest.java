package com.churchgeniuspro.errors;

import com.churchgeniuspro.controller.GlobalExceptionHandler;
import com.churchgeniuspro.util.ErrorResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Error-handling audit 2026-10-28, H3/H4: validation and not-found failures answer 400/404
 * instead of a 500 with a stack trace; constraint violations answer 409; raw exception text
 * never reaches the client; every unexpected failure carries a reference.
 */
@DisplayName("Client errors get their proper status; exception text stays in the log")
class ClientErrorMappingTest {

    @RestController
    static class Probe {
        @GetMapping("/p/iae")        public String iae()        { throw new IllegalArgumentException("Amount must be positive"); }
        @GetMapping("/p/iae-nf")     public String iaeNotFound(){ throw new IllegalArgumentException("Member not found: 42"); }
        @GetMapping("/p/iae-leak")   public String iaeLeak()    { throw new IllegalArgumentException("org.hibernate.exception.ConstraintViolationException: could not execute statement [select ...]"); }
        @GetMapping("/p/ise")        public String ise()        { throw new IllegalStateException("CGP_CID_KEY is not configured"); }
        @GetMapping("/p/nse")        public String nse()        { throw new NoSuchElementException(); }
        @GetMapping("/p/nfe")        public String nfe()        { Integer.parseInt("abc"); return ""; }
        @GetMapping("/p/dive")       public String dive()       { throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_app_user_email\" Detail: Key (email)=(ann@example.org)"); }
        @GetMapping("/p/num/{id}")   public String num(@PathVariable int id) { return "" + id; }
        @GetMapping("/p/param")      public String param(@RequestParam("q") String q) { return q; }
        @PostMapping("/p/body")      public String body(@RequestBody Map<String, Object> b) { return "ok"; }
        @GetMapping("/p/boom")       public String boom()       { throw new RuntimeException("jdbc:postgresql://db/secret failed"); }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(advice, "maxFileSize", "15MB");
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(advice).build();
    }

    @Test @DisplayName("IllegalArgumentException with an authored message → 400 with that message")
    void iaeIs400() throws Exception {
        mvc.perform(get("/p/iae")).andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Amount must be positive"));
    }

    @Test @DisplayName("'… not found' → 404")
    void iaeNotFoundIs404() throws Exception {
        mvc.perform(get("/p/iae-nf")).andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Member not found: 42"));
    }

    @Test @DisplayName("an IllegalArgumentException whose message looks like internals is replaced by a generic sentence")
    void internalLookingMessageIsHidden() throws Exception {
        mvc.perform(get("/p/iae-leak")).andExpect(status().isBadRequest())
           .andExpect(content().string(not(containsString("hibernate"))))
           .andExpect(content().string(not(containsString("select"))))
           .andExpect(jsonPath("$.error").value("The request could not be processed."));
    }

    @Test @DisplayName("IllegalStateException stays a 500 with a reference (configuration/state faults are the server's)")
    void iseStays500() throws Exception {
        mvc.perform(get("/p/ise")).andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.ref").exists())
           .andExpect(content().string(not(containsString("CGP_CID_KEY"))));
    }

    @Test @DisplayName("NoSuchElementException (orElseThrow) → 404 'Not found.'")
    void nseIs404() throws Exception {
        mvc.perform(get("/p/nse")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Not found."));
    }

    @Test @DisplayName("NumberFormatException, bad path variable, missing parameter, unreadable body → 400; wrong method → 405")
    void frameworkClientErrors() throws Exception {
        mvc.perform(get("/p/nfe")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("A number in the request is not valid."));
        mvc.perform(get("/p/num/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Invalid value for 'id'."));
        mvc.perform(get("/p/param")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Missing required parameter 'q'."));
        mvc.perform(post("/p/body").contentType(MediaType.APPLICATION_JSON).content("{not json")).andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("The request body could not be read. Please check the values and try again."));
        // no handler matched, so the package-scoped advice does not apply: Spring's own 405, empty body
        mvc.perform(post("/p/iae")).andExpect(status().isMethodNotAllowed());
    }

    @Test @DisplayName("DataIntegrityViolationException → 409 with a reference and none of the constraint text")
    void diveIs409() throws Exception {
        mvc.perform(get("/p/dive")).andExpect(status().isConflict())
           .andExpect(jsonPath("$.ref").value(matchesPattern("[0-9a-f]{8}")))
           .andExpect(content().string(not(containsString("ann@example.org"))))
           .andExpect(content().string(not(containsString("uq_app_user"))));
    }

    @Test @DisplayName("anything else → 500 with a reference and no exception text")
    void unexpectedIs500WithRef() throws Exception {
        mvc.perform(get("/p/boom")).andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.ref").value(matchesPattern("[0-9a-f]{8}")))
           .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test @DisplayName("ErrorResponses: the body carries the prefix and a reference, never the throwable's text")
    void errorResponsesHelper() {
        RuntimeException ex = new RuntimeException("could not execute statement [ERROR: duplicate key] Failing row contains (1, ann@example.org)");
        ResponseEntity<Map<String, Object>> r = ErrorResponses.internal(LoggerFactory.getLogger("test"), "Failed to save user", ex);
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        String error = (String) r.getBody().get("error");
        String ref = (String) r.getBody().get("ref");
        assertThat(ref).matches("[0-9a-f]{8}");
        assertThat(error).startsWith("Failed to save user. ").contains("reference " + ref + ".")
                         .doesNotContain("ann@example.org").doesNotContain("duplicate key");
        assertThat(ErrorResponses.message(LoggerFactory.getLogger("test"), "Could not read the file.", ex))
                .startsWith("Could not read the file. Please try again");
    }

    @Test @DisplayName("safeMessage passes short authored text and refuses anything that smells of internals")
    void safeMessage() {
        assertThat(GlobalExceptionHandler.safeMessage(new IllegalArgumentException("Date is required"), "x")).isEqualTo("Date is required");
        assertThat(GlobalExceptionHandler.safeMessage(new IllegalArgumentException((String) null), "fallback")).isEqualTo("fallback");
        assertThat(GlobalExceptionHandler.safeMessage(new IllegalArgumentException("java.lang.NullPointerException at Foo.bar"), "fallback")).isEqualTo("fallback");
        assertThat(GlobalExceptionHandler.safeMessage(new IllegalArgumentException("x".repeat(301)), "fallback")).isEqualTo("fallback");
    }
}
