package com.churchgeniuspro.util;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The business calendar for subscriptions and trials: America/Chicago.
 *
 * <p>Trial start and end dates, and the "has this subscription ended?" checks at
 * sign-in and on every request, all use this date so they agree with each other
 * whatever time zone the server or the database runs in. (The schedulers already
 * run on America/Chicago.) Access closes ON the end date: a 60-day trial that
 * starts October 5 ends December 4, and December 3 is its last usable day.
 */
public final class AppClock {

    public static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private AppClock() {}

    /** Today's date in America/Chicago. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
