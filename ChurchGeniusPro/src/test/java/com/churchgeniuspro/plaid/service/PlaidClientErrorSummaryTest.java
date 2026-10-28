package com.churchgeniuspro.plaid.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Plaid HTTP errors reach the logs as error_type / error_code / request_id — never the response body (2026-10-28 logging audit M3). */
@DisplayName("PlaidClient — error message is a summary, not the body")
class PlaidClientErrorSummaryTest {

    @Test
    void summaryKeepsCodesOnly() {
        String body = "{\"error_type\":\"INVALID_INPUT\",\"error_code\":\"INVALID_ACCESS_TOKEN\","
                + "\"error_message\":\"provided token is in an invalid format: access-sandbox-1111-2222-3333-4444-555555555555 for user bob@example.org\","
                + "\"display_message\":null,\"request_id\":\"Aim3b\",\"causes\":[]}";
        String s = PlaidClient.errorSummary("/accounts/get", 400, body.getBytes(StandardCharsets.UTF_8));
        assertEquals("Plaid /accounts/get HTTP 400 error_type=INVALID_INPUT error_code=INVALID_ACCESS_TOKEN request_id=Aim3b", s);
        assertFalse(s.contains("access-sandbox"));
        assertFalse(s.contains("bob@"));
        assertEquals("Plaid /item/remove HTTP 502", PlaidClient.errorSummary("/item/remove", 502, new byte[0]));
        assertEquals("Plaid /x HTTP 500", PlaidClient.errorSummary("/x", 500, null));
    }
}
