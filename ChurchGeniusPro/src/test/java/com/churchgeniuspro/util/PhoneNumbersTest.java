package com.churchgeniuspro.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phone-number normalisation.
 *
 * <p>This is the string Twilio is handed as the {@code To} address, and getting it wrong
 * has a distinctive failure signature: the provider rejects the message with error 21211,
 * the sending code catches and logs it, the registration still succeeds, and the person
 * simply never receives a text. Nothing surfaces. So the shapes people actually type are
 * pinned here as fixtures rather than left to a generic "is it ten digits" check.
 */
class PhoneNumbersTest {

    @Nested
    @DisplayName("The numbers that were actually collected")
    class RealRegistrants {

        /**
         * These are the real numbers from an event's registration list. Before
         * normalisation existed on this path, exactly one of them — the single entry
         * somebody happened to type with a leading + — could be delivered to.
         */
        @Test
        @DisplayName("seven of the eight collected numbers become deliverable")
        void theRegistrationList() {
            assertEquals("+19133330704", PhoneNumbers.toE164("9133330704"));
            assertEquals("+19133901119", PhoneNumbers.toE164("19133901119"));
            assertEquals("+19132150755", PhoneNumbers.toE164("+19132150755"));
            assertEquals("+19139094249", PhoneNumbers.toE164("19139094249"));
            assertEquals("+13218050210", PhoneNumbers.toE164("13218050210"));
            assertEquals("+19134569296", PhoneNumbers.toE164("9134569296"));
            assertEquals("+13463705928", PhoneNumbers.toE164("3463705928"));
        }

        @Test
        @DisplayName("the nine-digit entry stays unresolvable — a tenth digit cannot be invented")
        void theBrokenOne() {
            // 270917242. Padding or truncating this to ten digits would produce a real,
            // dialable number belonging to a stranger, and we would text them somebody
            // else's event details. Refusing is the only correct answer; this one has to
            // be re-collected from the person.
            assertNull(PhoneNumbers.toE164("270917242"));
            assertFalse(PhoneNumbers.isSendable("270917242"));
        }
    }

    @Nested
    @DisplayName("Shapes a free-text field produces")
    class InputShapes {

        @Test
        @DisplayName("a bare ten-digit number gains +1")
        void bareNational() {
            assertEquals("+19135550100", PhoneNumbers.toE164("9135550100"));
        }

        @Test
        @DisplayName("a country code without the plus is the most common miss, and is handled")
        void countryCodeWithoutPlus() {
            assertEquals("+19135550100", PhoneNumbers.toE164("19135550100"));
        }

        @Test
        @DisplayName("punctuation people actually type is stripped")
        void punctuation() {
            for (String written : new String[]{
                    "(913) 555-0100", "913.555.0100", "913-555-0100",
                    "913 555 0100", " 913-555-0100 ", "1 (913) 555-0100", "+1 (913) 555-0100"}) {
                assertEquals("+19135550100", PhoneNumbers.toE164(written), "failed on: " + written);
            }
        }

        @Test
        @DisplayName("a number already in E.164 is returned unchanged, not re-prefixed")
        void alreadyE164() {
            assertEquals("+19135550100", PhoneNumbers.toE164("+19135550100"));
            // Idempotence matters: SmsService normalises again at the boundary, so a value
            // that has already been through here must survive a second pass untouched.
            assertEquals("+19135550100", PhoneNumbers.toE164(PhoneNumbers.toE164("+19135550100")));
        }

        @Test
        @DisplayName("an international number keeps its own country code")
        void international() {
            // The danger here is a well-meaning "+1" default mangling a foreign number
            // into a valid-looking US one.
            assertEquals("+447911123456", PhoneNumbers.toE164("+447911123456"));
            assertEquals("+919876543210", PhoneNumbers.toE164("+91 98765 43210"));
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("nothing is guessed — unusable input returns null rather than a plausible number")
        void neverGuesses() {
            assertNull(PhoneNumbers.toE164(null));
            assertNull(PhoneNumbers.toE164(""));
            assertNull(PhoneNumbers.toE164("   "));
            assertNull(PhoneNumbers.toE164("not a phone"));
            assertNull(PhoneNumbers.toE164("913555010"),   "nine digits");
            assertNull(PhoneNumbers.toE164("91355501000"), "eleven digits not starting with 1");
        }

        @Test
        @DisplayName("a ten-digit string that cannot be dialled is refused")
        void nanpDialPlan() {
            // Area code and exchange both start 2-9 in the North American plan. These are
            // the shapes a spreadsheet paste produces — a stripped leading zero, or a
            // trunk digit landing in the wrong column.
            assertNull(PhoneNumbers.toE164("0123456789"), "area code starting 0");
            assertNull(PhoneNumbers.toE164("1234567890"), "area code starting 1");
            assertNull(PhoneNumbers.toE164("9130550100"), "exchange starting 0");
            assertNull(PhoneNumbers.toE164("9131550100"), "exchange starting 1");
        }

        @Test
        @DisplayName("a truncated +1 number is refused rather than passed through to fail at Twilio")
        void truncatedPlusOne() {
            // We know this dial plan, so a short +1 number is a typo we can catch here
            // instead of discovering it as a provider error nobody reads.
            assertNull(PhoneNumbers.toE164("+1913215075"));
            assertNull(PhoneNumbers.toE164("+1913"));
        }

        @Test
        @DisplayName("an over-long number is refused — E.164 allows at most 15 digits")
        void overlong() {
            assertNull(PhoneNumbers.toE164("+1234567890123456"));
        }
    }

    @Nested
    @DisplayName("National form, for the reminder log's existing keys")
    class NationalDigits {

        @Test
        @DisplayName("returns the bare ten digits the reminder dedupe keys are stored as")
        void tenDigits() {
            // event_registration_reminder_log.recipient_norm holds this shape and is
            // matched by equality to decide whether a reminder already went out. If this
            // ever returned E.164, every historical row would stop matching and people
            // would get a second copy of a reminder they already had.
            assertEquals("9135550100", PhoneNumbers.nationalDigits("9135550100"));
            assertEquals("9135550100", PhoneNumbers.nationalDigits("+19135550100"));
            assertEquals("9135550100", PhoneNumbers.nationalDigits("1 (913) 555-0100"));
        }

        @Test
        @DisplayName("a non-NANP number has no ten-digit national form")
        void internationalHasNone() {
            assertNull(PhoneNumbers.nationalDigits("+447911123456"));
            assertNull(PhoneNumbers.nationalDigits("270917242"));
        }
    }
}
