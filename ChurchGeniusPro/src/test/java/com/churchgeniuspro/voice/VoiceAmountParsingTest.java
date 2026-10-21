package com.churchgeniuspro.voice;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenarios 3, 4, 5: a spoken amount is parsed to a valid number; a non-numeric phrase,
 * a run of separate numbers, an out-of-range or malformed value are refused (null) so
 * the page asks again instead of writing 1.2345678910111214e+38 into the form.
 */
@DisplayName("Income voice — amount parsing and validation (voice-core.js)")
class VoiceAmountParsingTest {

    @BeforeAll static void node() { NodeJs.assumeAvailable(); }

    static JsonNode parse(String spoken) throws Exception {
        return NodeJs.evalVoiceCore("VoiceCore.parseAmount(" + com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(spoken) + ")");
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
        "100, 100", "100.50, 100.5", "'1,250', 1250", "'1,250.75', 1250.75", "$45.678, 45.68",
        "one hundred, 100", "one hundred and fifty, 150", "two hundred fifty dollars, 250",
        "ten thousand, 10000", "twelve fifty, 1250", "one fifty, 150", "twenty five, 25",
        "amount 100, 100", "set amount to 250 dollars, 250", "amount is 100, 100"
    })
    @DisplayName("valid spoken amounts parse to cents-rounded numbers")
    void valid(String spoken, double expected) throws Exception {
        assertThat(parse(spoken).asDouble()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
        "hello there", "Anson Mathew", "0", "zero",
        "1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24",   // the e+38 case
        "100 200", "1 250 000",                                              // several numbers, not thousands groups
        "99999999999999", "10000000000000",                                  // above numeric(15,2)
        "1e38", "12345678910111214e+38"
    })
    @DisplayName("non-numeric, multi-number, oversized and malformed values are refused")
    void refused(String spoken) throws Exception {
        assertThat(parse(spoken).isNull()).as(spoken).isTrue();
    }

    @ParameterizedTest(name = "validateAmount({0})")
    @CsvSource({ "100, true", "0.01, true", "9999999999999.99, true", "10000000000000, false", "0, false", "-5, false" })
    @DisplayName("validateAmount enforces > 0 and the ledger column limit")
    void validate(String n, boolean ok) throws Exception {
        JsonNode r = NodeJs.evalVoiceCore("VoiceCore.validateAmount(" + n + ")");
        assertThat(r.get("ok").asBoolean()).isEqualTo(ok);
    }
}
