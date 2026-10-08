package com.churchgeniuspro.util;

/**
 * The one place a phone number is turned into the format Twilio requires.
 *
 * <p>Twilio's {@code To} parameter must be
 * <a href="https://www.twilio.com/docs/api/errors/21211">E.164</a> — a {@code +}, a country
 * code, then the subscriber number. Hand it anything else and the request comes back as
 * error 21211, which the sending code catches and logs, so the message simply never
 * arrives and nobody finds out. That failure mode is the reason this class exists: the
 * numbers people type into a public form are almost never in E.164, and the gap is
 * invisible from the outside.
 *
 * <p>Before this, four separate copies of "normalize phone" existed in the codebase and
 * they did not agree — one returned {@code +1XXXXXXXXXX}, another returned bare ten
 * digits, a third accepted only exactly ten digits and rejected {@code (913) 555-0100}
 * because of the punctuation. That matters beyond tidiness: {@code sms_opt_in.phone_number}
 * is matched by exact string equality, so a number written by one path and looked up by
 * another silently fails to match, and a STOP request stops nothing.
 *
 * <p><b>Refuses rather than guesses.</b> Every method returns {@code null} for input it
 * cannot resolve confidently. Inventing a plausible-looking number would be worse than
 * failing: it would send a stranger somebody else's event details.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** Default country code applied to a bare national number. */
    private static final String DEFAULT_COUNTRY_CODE = "1";

    /** North American Numbering Plan subscriber length, without the country code. */
    private static final int NANP_NATIONAL_LENGTH = 10;

    /** E.164 allows at most 15 digits including the country code. */
    private static final int E164_MAX_DIGITS = 15;

    /**
     * Converts {@code raw} to E.164, or returns {@code null} when it cannot be resolved.
     *
     * <p>Handles the shapes that actually arrive from a registration form:
     * <ul>
     *   <li>{@code +19135550100} → unchanged (already E.164)</li>
     *   <li>{@code 9135550100} → {@code +19135550100} (bare NANP national number)</li>
     *   <li>{@code 19135550100} → {@code +19135550100} (national number with a country
     *       code but no {@code +} — the single most common way this goes wrong)</li>
     *   <li>{@code (913) 555-0100} and {@code 913.555.0100} → {@code +19135550100}</li>
     *   <li>{@code 270917242} → {@code null} (nine digits is not a number in any plan
     *       this can assume; a tenth digit cannot be invented)</li>
     * </ul>
     *
     * <p>A leading {@code +} is honoured as an explicit statement of the country code, so
     * international numbers pass through untouched and are never re-prefixed with
     * {@code +1}. Without the {@code +} the number is assumed to be North American, which
     * is the assumption every existing copy of this logic already made.
     */
    public static String toE164(String raw) {
        if (raw == null) return null;

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        boolean explicitCountryCode = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;

        if (explicitCountryCode) {
            // The caller told us the country code. Trust it for the rest of the world —
            // we have no dial plan for every country and refusing what we cannot verify
            // would block legitimate international numbers.
            if (digits.length() < 8 || digits.length() > E164_MAX_DIGITS) return null;

            // +1 is the exception: we DO know this plan, so apply it rather than passing
            // a malformed number through to fail silently at the provider.
            if (digits.startsWith(DEFAULT_COUNTRY_CODE)
                    && digits.length() == NANP_NATIONAL_LENGTH + 1) {
                return isValidNanp(digits.substring(1)) ? "+" + digits : null;
            }
            if (digits.startsWith(DEFAULT_COUNTRY_CODE)
                    && digits.length() < NANP_NATIONAL_LENGTH + 1) {
                return null;   // "+1" with too few digits is a typo, not a foreign number
            }
            return "+" + digits;
        }

        // Strip a NANP trunk/country prefix: 1 followed by a full national number.
        if (digits.length() == NANP_NATIONAL_LENGTH + 1 && digits.startsWith(DEFAULT_COUNTRY_CODE)) {
            digits = digits.substring(1);
        }
        if (!isValidNanp(digits)) return null;

        return "+" + DEFAULT_COUNTRY_CODE + digits;
    }

    /**
     * Whether ten digits form a dialable North American number.
     *
     * <p>In the North American Numbering Plan both the area code and the exchange code
     * begin with 2-9. Checking that catches entries which are the right length but cannot
     * be dialled — a digit transposed onto the front, or a leading zero pasted in from a
     * spreadsheet — instead of passing them to the provider to fail out of sight.
     */
    private static boolean isValidNanp(String tenDigits) {
        return tenDigits.length() == NANP_NATIONAL_LENGTH
            && tenDigits.charAt(0) >= '2'
            && tenDigits.charAt(3) >= '2';
    }

    /** True when {@code raw} can be delivered to — i.e. {@link #toE164} resolves it. */
    public static boolean isSendable(String raw) {
        return toE164(raw) != null;
    }

    /**
     * The ten-digit national portion of a NANP number, or {@code null}.
     *
     * <p>Kept because {@code event_registration_reminder_log.recipient_norm} already holds
     * numbers in this shape and is matched by equality to decide whether a reminder was
     * already sent. Rewriting those to E.164 would make every historical row stop matching
     * and re-send reminders to people who already had one.
     */
    public static String nationalDigits(String raw) {
        String e164 = toE164(raw);
        if (e164 == null) return null;
        String digits = e164.substring(1);
        if (!digits.startsWith(DEFAULT_COUNTRY_CODE) || digits.length() != NANP_NATIONAL_LENGTH + 1) {
            return null;   // not a NANP number — it has no ten-digit national form
        }
        return digits.substring(1);
    }
}
