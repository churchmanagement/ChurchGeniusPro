package com.churchgeniuspro.logging;

import com.churchgeniuspro.util.EmailMask;
import com.churchgeniuspro.util.LogSafe;
import com.churchgeniuspro.util.PhoneMask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The call-site helpers the 2026-10-28 logging audit introduced for phone numbers, addresses and exception text. */
@DisplayName("PhoneMask / EmailMask.maskAll / LogSafe")
class LogMaskHelpersTest {

    @Test
    void phoneMaskKeepsOnlyTheLastFour() {
        assertEquals("***-***-0100", PhoneMask.mask("+19135550100"));
        assertEquals("***-***-0100", PhoneMask.mask("(913) 555-0100"));
        assertEquals("***-***-0100", PhoneMask.mask("913.555.0100"));
        assertEquals("(no number)", PhoneMask.mask(null));
        assertEquals("(no number)", PhoneMask.mask("  "));
        assertEquals("***", PhoneMask.mask("12"));
        // The masked form must not re-trigger the E.164 backstop in the layout.
        assertEquals("***-***-0100", SensitiveDataMasker.mask(PhoneMask.mask("+19135550100")));
    }

    @Test
    void phoneMaskAllOnlyTouchesPhoneSizedRuns() {
        assertEquals("To ***-***-0100 is not a valid number (code 21211) at 2026-10-28 00:25",
                PhoneMask.maskAll("To +19135550100 is not a valid number (code 21211) at 2026-10-28 00:25"));
        assertEquals("invoice INV-2026-00042 total 1,234.56", PhoneMask.maskAll("invoice INV-2026-00042 total 1,234.56"));
        assertNull(PhoneMask.maskAll(null));
    }

    @Test
    void emailMaskAllMasksEveryAddressInFreeText() {
        assertEquals("Invalid Addresses: jo***@exa***.org; ad***@chu***.net",
                EmailMask.maskAll("Invalid Addresses: john.doe@example.org; admin@church.net"));
        assertEquals("no address here", EmailMask.maskAll("no address here"));
        assertNull(EmailMask.maskAll(null));
    }

    @Test
    void logSafeExceptionIsClassPlusMaskedMessage() {
        Exception e = new IllegalStateException("550 Invalid Addresses: john.doe@example.org, To +19135550100, token=abc123secret");
        String s = LogSafe.exception(e);
        assertTrue(s.startsWith("IllegalStateException: "), s);
        assertFalse(s.contains("john.doe@example.org"), s);
        assertFalse(s.contains("+19135550100"), s);
        assertFalse(s.contains("abc123secret"), s);
        assertEquals("RuntimeException", LogSafe.exception(new RuntimeException()));
        assertEquals("(no exception)", LogSafe.exception(null));
        assertTrue(LogSafe.exception(new RuntimeException("x".repeat(2000))).length() < 600);
    }

    @Test
    void failedLoginUsernamesAreMaskedForTheAuditFile() {
        assertEquals("jo***@exa***.com", com.churchgeniuspro.service.LoginProtectionService.mask("john@example.com"));
        assertEquals("My***", com.churchgeniuspro.service.LoginProtectionService.mask("MyS3cretPassw0rd!"));   // a password typed into the username box
        assertEquals("***", com.churchgeniuspro.service.LoginProtectionService.mask("ab"));
        assertEquals("-", com.churchgeniuspro.service.LoginProtectionService.mask(null));
        assertEquals("-", com.churchgeniuspro.service.LoginProtectionService.mask(" "));
    }
}
