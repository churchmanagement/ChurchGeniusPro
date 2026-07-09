package com.churchgeniuspro.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NTAG serial normalization — the root cause of the
 * "NTAG not recognized" class of bugs is a normalization mismatch between
 * registration and login, so this contract is pinned here.
 */
class NtagSerialTest {

    @Test
    void normalizes_caseAndSeparators() {
        // Hardware UID read by Web NFC ("04:a2:b3:...") must normalize identically
        // whether typed or scanned.
        assertEquals("04A2B3C4", NtagService.normalizeSerial("04:a2:b3:c4"));
        assertEquals("04A2B3C4", NtagService.normalizeSerial(" 04-A2-B3-C4 "));
        assertEquals("04A2B3C4", NtagService.normalizeSerial("04a2b3c4"));
    }

    @Test
    void blankOrNull_becomesNull() {
        assertNull(NtagService.normalizeSerial(null));
        assertNull(NtagService.normalizeSerial("   "));
        assertNull(NtagService.normalizeSerial("---"));
    }
}
